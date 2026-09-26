package com.influora.service.meera;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influora.common.ApiException;
import com.influora.common.Ulids;
import com.influora.domain.entity.AiConversation;
import com.influora.domain.entity.AiMessage;
import com.influora.domain.enums.MessageRole;
import com.influora.integration.ai.MeeraVoiceAiClient.FrameCheckResult;
import com.influora.repository.AiConversationRepository;
import com.influora.repository.AiMessageRepository;
import com.influora.service.CreatorAgentConversationService;
import com.influora.service.IdempotencyService;
import java.io.IOException;
import java.text.Normalizer;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Keeps a photo check in Meera's chat: one USER row ("Check my set-up: ...") and one ASSISTANT row
 * (the {@link PhotoCheckSummary} text, with the check's result in {@code metadata}) written by
 * Spring from the JSON influora-ai returned. The client never writes an assistant row (F-08).
 *
 * <p><b>Order (Ash correction 1).</b> The idempotency key is reserved BEFORE the vision call, and
 * the call runs inside {@link IdempotencyService#executeOnce}. So:
 *
 * <ul>
 *   <li>a replayed key that already COMPLETED returns the stored rows (the stored result and the
 *       stored text) and never calls the model a second time -- the card on screen, the card that
 *       reloads and the text Meera reads later are the same check;
 *   <li>a key still IN_PROGRESS (a double tap while the first check runs) is a 409, again with no
 *       second model call;
 *   <li>a check that writes nothing (upstream failure, {@code fallback != false}, a body that is
 *       not a JSON object) leaves the key FAILED, which the next retry reclaims.
 * </ul>
 *
 * <p><b>Strict fallback (Ash correction 3).</b> Rows are written only when the body's {@code
 * fallback} is the JSON boolean {@code false}. A body with no {@code fallback} key writes nothing.
 * influora-ai's route adds {@code fallback:false} on its success return only.
 *
 * <p><b>Transaction.</b> The two rows, {@code last_message_at} and the creator's conversation
 * rollup ({@link CreatorAgentConversationService#recordTurnForUser}, which the DPDP
 * list/export/delete surface reads) commit in ONE real {@link TransactionTemplate} -- never a
 * self-invoked {@code @Transactional}, which would be inert. A database failure there is logged at
 * WARN and the check's result still goes back to the creator, with null ids.
 *
 * <p>Never stored: the image, {@code shot_context}, {@code answers}, and the result's geometry
 * ({@code at}, {@code box}, {@code layout}; {@link #withoutGeometry}). The metadata holds only
 * influora-ai's code-written result (minus {@code fallback} and geometry) and the sanitised shot
 * label.
 */
@Service
public class PhotoCheckChatWriter {

    private static final Logger log = LoggerFactory.getLogger(PhotoCheckChatWriter.class);

    /** {@link IdempotencyService} scope for a photo-check pair. */
    public static final String SCOPE = "photo-check-chat";
    /** {@code metadata.kind} of the ASSISTANT row; the history endpoint maps it to {@code card}. */
    public static final String METADATA_KIND = "photo_check";
    public static final int METADATA_VERSION = 1;
    /** {@code user_line}: the creator's own words for the USER row, counted in code points. */
    public static final int MAX_USER_LINE_CHARS = 200;
    /**
     * The client sends a UUID (36). {@link IdempotencyService} composes {@code scope:user:key} into
     * a {@code VARCHAR(128)} primary key: 16 + 1 + 26 + 1 + 64 = 108, safely inside it.
     */
    public static final int MAX_IDEMPOTENCY_KEY_CHARS = 64;

    static final String DEFAULT_USER_LINE = "Check my set-up";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final AiConversationRepository conversationRepository;
    private final AiMessageRepository messageRepository;
    private final IdempotencyService idempotencyService;
    private final CreatorAgentConversationService creatorAgentConversationService;
    private final TransactionTemplate transactionTemplate;

    public PhotoCheckChatWriter(
            AiConversationRepository conversationRepository,
            AiMessageRepository messageRepository,
            IdempotencyService idempotencyService,
            CreatorAgentConversationService creatorAgentConversationService,
            PlatformTransactionManager transactionManager) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.idempotencyService = idempotencyService;
        this.creatorAgentConversationService = creatorAgentConversationService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * What one call produced. {@code upstream} is influora-ai's raw answer ({@code null} on a
     * replay). {@code result} is the writable result minus {@code fallback} ({@code null} when the
     * check is not one to keep -- the caller then returns {@code upstream} unchanged). The ids are
     * {@code null} when nothing was stored.
     */
    public record Outcome(
            FrameCheckResult upstream,
            ObjectNode result,
            String text,
            String userMessageId,
            String assistantMessageId,
            boolean replayed) {}

    record Written(String userMessageId, String assistantMessageId) {
        /** Stored as {@code idempotency_keys.result_digest}: "assistantId,userId" (53 chars). */
        String digest() {
            return assistantMessageId + "," + userMessageId;
        }
    }

    /** Signals "the check ran but there is nothing to store"; marks the key FAILED (retryable). */
    private static final class NothingToWrite extends RuntimeException {
        NothingToWrite() {
            super("photo check has nothing to store", null, false, false);
        }
    }

    /**
     * Same ownership check {@link MeeraSessionService#listMessages} uses. The caller runs it BEFORE
     * the model call, so a foreign or unknown id costs nothing and learns nothing.
     */
    public AiConversation requireOwnedConversation(String creatorUserId, String conversationId) {
        return conversationRepository
                .findByIdAndWorkspaceId(conversationId, creatorUserId)
                .orElseThrow(
                        () -> new ApiException("CONVERSATION_NOT_FOUND", "Conversation not found", HttpStatus.NOT_FOUND));
    }

    /**
     * Runs the check under the idempotency key and stores the pair when the result is one to keep.
     * See the class javadoc for the replay / in-progress / failure rules.
     */
    public Outcome checkAndWrite(
            String creatorUserId,
            String conversationId,
            String idempotencyKey,
            String userLine,
            String shotLabel,
            Supplier<FrameCheckResult> vision) {
        String label = PhotoCheckSummary.sanitizeLabel(shotLabel);
        String userContent = userRowContent(userLine, label);

        AtomicBoolean visionStarted = new AtomicBoolean(false);
        AtomicReference<FrameCheckResult> upstream = new AtomicReference<>();
        AtomicReference<ObjectNode> writable = new AtomicReference<>();
        AtomicReference<String> text = new AtomicReference<>();
        try {
            Written written =
                    idempotencyService.executeOnce(
                            idempotencyKey,
                            creatorUserId,
                            SCOPE,
                            () -> {
                                visionStarted.set(true);
                                FrameCheckResult checked = vision.get();
                                upstream.set(checked);
                                ObjectNode result = writableResult(checked);
                                if (result == null) {
                                    throw new NothingToWrite();
                                }
                                writable.set(result);
                                String summary = PhotoCheckSummary.render(result, label);
                                text.set(summary);
                                return transactionTemplate.execute(
                                        status ->
                                                writePair(
                                                        creatorUserId, conversationId, userContent, summary, result, label));
                            },
                            Written::digest);
            return new Outcome(
                    upstream.get(),
                    writable.get(),
                    text.get(),
                    written.userMessageId(),
                    written.assistantMessageId(),
                    false);
        } catch (NothingToWrite nothing) {
            return new Outcome(upstream.get(), null, null, null, null, false);
        } catch (IdempotencyService.AlreadyCompletedException completed) {
            return replay(creatorUserId, conversationId, idempotencyKey);
        } catch (IdempotencyService.AlreadyInProgressException inProgress) {
            throw new ApiException(
                    "PHOTO_CHECK_IN_PROGRESS",
                    "This photo check is already running -- wait for its result",
                    HttpStatus.CONFLICT);
        } catch (RuntimeException failure) {
            if (writable.get() != null) {
                // The check succeeded; storing it did not. The creator still gets the card, and
                // this session's replay still carries the text; it just will not survive a reload.
                log.warn(
                        "PhotoCheckChatWriter: photo check not stored, result returned without ids ({}): {}",
                        conversationId,
                        failure.getClass().getSimpleName());
                return new Outcome(upstream.get(), writable.get(), text.get(), null, null, false);
            }
            if (upstream.get() != null) {
                // The model answered with nothing to store and marking the key FAILED also failed.
                return new Outcome(upstream.get(), null, null, null, null, false);
            }
            if (visionStarted.get()) {
                throw failure;
            }
            // The idempotency ledger failed before the model call (database unavailable): the
            // check still runs once, and nothing is stored.
            log.warn(
                    "PhotoCheckChatWriter: idempotency ledger unavailable, photo check runs without being stored: {}",
                    failure.getClass().getSimpleName());
            FrameCheckResult checked = vision.get();
            ObjectNode result = writableResult(checked);
            return new Outcome(
                    checked, result, result == null ? null : PhotoCheckSummary.render(result, label), null, null, false);
        }
    }

    private Written writePair(
            String creatorUserId,
            String conversationId,
            String userContent,
            String assistantText,
            ObjectNode result,
            String label) {
        AiConversation conversation = requireOwnedConversation(creatorUserId, conversationId);

        // UlidCreator.getUlid() is not monotonic inside one millisecond, and the history cursors
        // sort by id -- so the smaller of two fresh ids goes to the USER row, keeping the pair in
        // order for ?after= and ?before=.
        String first = Ulids.newUlid();
        String second = Ulids.newUlid();
        String userId = first.compareTo(second) < 0 ? first : second;
        String assistantId = first.compareTo(second) < 0 ? second : first;

        messageRepository.save(
                AiMessage.builder()
                        .id(userId)
                        .conversationId(conversationId)
                        .role(MessageRole.USER)
                        .content(userContent)
                        .creditsCharged(0)
                        .build());
        messageRepository.save(
                AiMessage.builder()
                        .id(assistantId)
                        .conversationId(conversationId)
                        .role(MessageRole.ASSISTANT)
                        .content(assistantText)
                        .metadataJson(metadataJson(result, label))
                        // The photo check is free (Swapnil's ruling): metered on influora-ai's USD
                        // cap only, never a chat credit.
                        .creditsCharged(0)
                        .build());

        Instant now = Instant.now();
        conversation.markMessageAt(now);
        conversationRepository.save(conversation);
        creatorAgentConversationService.recordTurnForUser(creatorUserId, conversationId, now);
        return new Written(userId, assistantId);
    }

    private Outcome replay(String creatorUserId, String conversationId, String idempotencyKey) {
        String digest = idempotencyService.findCompletedResultDigest(idempotencyKey, creatorUserId, SCOPE).orElse(null);
        String[] ids = digest == null ? new String[0] : digest.split(",", -1);
        if (ids.length == 2) {
            AiMessage assistant =
                    messageRepository
                            .findById(ids[0])
                            .filter(m -> conversationId.equals(m.getConversationId()))
                            .filter(m -> m.getRole() == MessageRole.ASSISTANT)
                            .orElse(null);
            ObjectNode result = assistant == null ? null : storedResult(assistant.getMetadataJson());
            if (result != null) {
                return new Outcome(null, result, assistant.getContent(), ids[1], ids[0], true);
            }
        }
        // The key was used for another conversation, or its rows are gone.
        throw new ApiException(
                "IDEMPOTENCY_KEY_REUSED",
                "This Idempotency-Key was already used for a different photo check",
                HttpStatus.CONFLICT);
    }

    // ------------------------------------------------------------------------------------------
    // Pure helpers, shared with CreatorMeeraController (which also answers without a conversation).
    // ------------------------------------------------------------------------------------------

    /**
     * The result to keep, or {@code null}: only an ok upstream answer whose body is a JSON object
     * with {@code "fallback": false} (the boolean, strictly). Returns a copy without {@code
     * fallback} (and without any {@code chat} key, which is ours to add).
     */
    public static ObjectNode writableResult(FrameCheckResult result) {
        if (result == null || !result.ok() || result.jsonBytes() == null) {
            return null;
        }
        JsonNode body;
        try {
            body = JSON.readTree(result.jsonBytes());
        } catch (IOException e) {
            return null;
        }
        if (body == null || !body.isObject()) {
            return null;
        }
        JsonNode fallback = body.get("fallback");
        if (fallback == null || !fallback.isBoolean() || fallback.booleanValue()) {
            return null;
        }
        ObjectNode copy = ((ObjectNode) body).deepCopy();
        copy.remove("fallback");
        copy.remove("chat");
        return copy;
    }

    /**
     * The 200 body for a check worth keeping: the result, {@code "fallback": false} (where
     * influora-ai put it), then {@code chat}. The same function renders a fresh check and a replay,
     * so both come back in one shape.
     */
    public static byte[] responseBody(ObjectNode result, String text, String userMessageId, String assistantMessageId) {
        ObjectNode out = result.deepCopy();
        out.put("fallback", false);
        ObjectNode chat = out.putObject("chat");
        chat.put("text", text);
        chat.put("message_id", assistantMessageId);
        chat.put("user_message_id", userMessageId);
        try {
            return JSON.writeValueAsBytes(out);
        } catch (IOException e) {
            throw new IllegalStateException("photo check body could not be written", e);
        }
    }

    /**
     * The USER row: the creator's {@code user_line} when sent, else "Check my set-up: label".
     *
     * <p>The line is made ONE line first ({@link #oneLineUserText}: control and line-separator
     * characters become spaces, invisible format characters are dropped, whitespace collapses), so
     * no header can hide on a later line or behind a bidi mark or zero-width space. Then every
     * header-like bracket is softened to "(" ({@link #neutralisePhotoCheckHeader}): a USER row can
     * never be a check (Ash correction 5; Kabir's fix round), whatever a direct API caller sends.
     */
    public static String userRowContent(String userLine, String sanitizedLabel) {
        String line = oneLineUserText(userLine);
        if (line.isEmpty()) {
            return sanitizedLabel == null ? DEFAULT_USER_LINE : DEFAULT_USER_LINE + ": " + sanitizedLabel;
        }
        return neutralisePhotoCheckHeader(line);
    }

    private static final Pattern USER_TEXT_SPACE_RUN = Pattern.compile("[\\s\\p{Z}]+");

    /**
     * {@code raw} as one line: ISO control characters and the Unicode line/paragraph separators
     * (U+2028, U+2029, NEL) become spaces; format characters ({@code Cf}: bidi marks, zero-width
     * space, soft hyphen, BOM, ...) are dropped, except the zero-width non-joiner and joiner that
     * Indic scripts and emoji need; whitespace runs collapse to one space; the ends are stripped.
     */
    static String oneLineUserText(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(raw.length());
        raw.codePoints()
                .forEach(
                        cp -> {
                            if (Character.isISOControl(cp) || cp == 0x2028 || cp == 0x2029 || cp == 0x0085) {
                                out.append(' ');
                                return;
                            }
                            if (Character.getType(cp) == Character.FORMAT && cp != 0x200C && cp != 0x200D) {
                                return;
                            }
                            out.appendCodePoint(cp);
                        });
        return USER_TEXT_SPACE_RUN.matcher(out).replaceAll(" ").strip();
    }

    /** Lookalike letters that fold to the Latin letters of "photo check" (after NFKD + lowercase):
     *  Cyrillic, Greek, Armenian and Latin small capitals. The same table as the web client's
     *  {@code neutralisePhotoCheckHeader} (meera-api.ts). */
    private static final Map<Integer, Integer> HOMOGLYPHS =
            Map.ofEntries(
                    Map.entry(0x0430, (int) 'a'), Map.entry(0x0435, (int) 'e'), Map.entry(0x043E, (int) 'o'),
                    Map.entry(0x0440, (int) 'p'), Map.entry(0x0441, (int) 'c'), Map.entry(0x0445, (int) 'x'),
                    Map.entry(0x0443, (int) 'y'), Map.entry(0x043A, (int) 'k'), Map.entry(0x0442, (int) 't'),
                    Map.entry(0x04BB, (int) 'h'), Map.entry(0x043D, (int) 'h'), Map.entry(0x0456, (int) 'i'),
                    Map.entry(0x03BF, (int) 'o'), Map.entry(0x03C1, (int) 'p'), Map.entry(0x03C4, (int) 't'),
                    Map.entry(0x03BA, (int) 'k'), Map.entry(0x03B5, (int) 'e'), Map.entry(0x03C7, (int) 'x'),
                    Map.entry(0x03F2, (int) 'c'), Map.entry(0x03B7, (int) 'h'), Map.entry(0x0585, (int) 'o'),
                    Map.entry(0x0570, (int) 'h'), Map.entry(0x1D18, (int) 'p'), Map.entry(0x029C, (int) 'h'),
                    Map.entry(0x1D0F, (int) 'o'), Map.entry(0x1D1B, (int) 't'), Map.entry(0x1D04, (int) 'c'),
                    Map.entry(0x1D07, (int) 'e'), Map.entry(0x1D0B, (int) 'k'));

    /** How many code points after a bracket a mid-line header is looked for. */
    private static final int HEADER_LOOKAHEAD = 64;

    private static final Pattern LINE_BREAK = Pattern.compile("\\r\\n|[\\n\\r\\x0B\\f\\x{85}\\x{2028}\\x{2029}]");

    /** The letters only, folded: NFKD, lowercase, lookalikes mapped, every non-letter dropped. */
    static String foldLetters(String text) {
        String decomposed = Normalizer.normalize(text, Normalizer.Form.NFKD).toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(decomposed.length());
        decomposed
                .codePoints()
                .map(cp -> HOMOGLYPHS.getOrDefault(cp, cp))
                .filter(Character::isLetter)
                .forEach(out::appendCodePoint);
        return out.toString();
    }

    private static boolean isLineLead(int cp) {
        if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
            return true;
        }
        int type = Character.getType(cp);
        if (type == Character.FORMAT
                || type == Character.NON_SPACING_MARK
                || type == Character.ENCLOSING_MARK
                || type == Character.COMBINING_SPACING_MARK) {
            return true;
        }
        return cp == 0x115F
                || cp == 0x1160
                || cp == 0x3164
                || cp == 0xFFA0
                || cp == 0x2800
                || cp == 0x2022
                || cp == 0x00B7
                || cp == 0x2023
                || cp == 0x25E6
                || cp == 0x25AA
                || cp == 0x25CF
                || "*_>#`~+-|=".indexOf(cp) >= 0;
    }

    private static String neutraliseLine(String line) {
        int[] cps = line.codePoints().toArray();
        int lead = 0;
        while (lead < cps.length) {
            if (isLineLead(cps[lead])) {
                lead++;
                continue;
            }
            // A list number: "1." / "12)".
            int digits = 0;
            while (lead + digits < cps.length && digits < 3 && cps[lead + digits] >= '0' && cps[lead + digits] <= '9') {
                digits++;
            }
            if (digits > 0 && lead + digits < cps.length && (cps[lead + digits] == '.' || cps[lead + digits] == ')')) {
                lead += digits + 1;
                continue;
            }
            break;
        }
        // 1) A line-leading bracket of any script becomes "(", whatever follows it; a markdown
        //    checkbox ("[ ]", "[x]") is a list, not a header.
        if (lead < cps.length
                && Character.getType(cps[lead]) == Character.START_PUNCTUATION
                && cps[lead] != '('
                && !isCheckbox(cps, lead)) {
            cps[lead] = '(';
        }
        // 2) Anywhere on the line: a bracket followed by letters that fold to "photo check".
        for (int i = 0; i < cps.length; i++) {
            if (cps[i] == '(' || Character.getType(cps[i]) != Character.START_PUNCTUATION) {
                continue;
            }
            int end = Math.min(cps.length, i + 1 + HEADER_LOOKAHEAD);
            if (foldLetters(new String(cps, i + 1, end - i - 1)).startsWith("photocheck")) {
                cps[i] = '(';
            }
        }
        return new String(cps, 0, cps.length);
    }

    private static boolean isCheckbox(int[] cps, int at) {
        return cps[at] == '['
                && at + 2 < cps.length
                && (cps[at + 1] == ' ' || cps[at + 1] == 'x' || cps[at + 1] == 'X')
                && cps[at + 2] == ']'
                && (at + 3 == cps.length || Character.isWhitespace(cps[at + 3]));
    }

    /**
     * Softens every header-like bracket in {@code text}, line by line: a line's first real
     * character (after whitespace, invisible format characters, combining marks and markdown
     * lead-ins such as "**", "- ", "> ", "# ", "1. ") becomes "(" when it is an opening bracket of
     * any script; and anywhere on a line, an opening bracket followed by letters that fold to
     * "photo check" (NFKD, lowercase, lookalike letters, any separator) becomes "(". The same rule
     * as the web client's {@code neutralisePhotoCheckHeader} and influora-ai's
     * {@code neutralize_photo_check_header}.
     */
    public static String neutralisePhotoCheckHeader(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        Matcher breaks = LINE_BREAK.matcher(text);
        int from = 0;
        while (breaks.find()) {
            out.append(neutraliseLine(text.substring(from, breaks.start()))).append(breaks.group());
            from = breaks.end();
        }
        out.append(neutraliseLine(text.substring(from)));
        return out.toString();
    }

    /**
     * {@code card} for {@code GET .../messages}: {@code {kind, v, result, shot_label}} for an
     * ASSISTANT row whose metadata says {@code kind == "photo_check"}; {@code null} for every other
     * row. Nothing else from the metadata (prompt_version, token_usage, ...) is ever copied.
     */
    public static Map<String, Object> historyCard(AiMessage message) {
        if (message == null || message.getRole() != MessageRole.ASSISTANT) {
            return null;
        }
        JsonNode metadata = parseObject(message.getMetadataJson());
        if (metadata == null || !METADATA_KIND.equals(metadata.path("kind").asText(null))) {
            return null;
        }
        JsonNode result = metadata.get("result");
        if (result == null || !result.isObject()) {
            return null;
        }
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("kind", METADATA_KIND);
        card.put("v", metadata.path("v").isInt() ? metadata.get("v").intValue() : METADATA_VERSION);
        card.put("result", result);
        JsonNode label = metadata.get("shot_label");
        if (label != null && label.isTextual()) {
            card.put("shot_label", label.asText());
        }
        return card;
    }

    /**
     * Geometry keys influora-ai may put on a result: a step's spot ({@code at}) and "remove this"
     * box ({@code box}), and the Reel layout's face/product boxes ({@code layout}). Shares of the
     * photo, which is never stored -- so positions without it mean nothing (spec 2.3).
     */
    static final Set<String> GEOMETRY_KEYS = Set.of("at", "box", "layout");

    /**
     * A deep copy of {@code result} with every {@link #GEOMETRY_KEYS} key removed at any depth
     * (top-level {@code layout}, {@code steps[].at}, {@code steps[].box}, and anywhere a later
     * shape nests them). The live response keeps them; the stored row never has them, so a reload
     * or a replayed key shows the numbered text only. {@code result} itself is not changed.
     */
    public static ObjectNode withoutGeometry(ObjectNode result) {
        if (result == null) {
            return null;
        }
        ObjectNode copy = result.deepCopy();
        stripGeometry(copy);
        return copy;
    }

    private static void stripGeometry(JsonNode node) {
        if (node instanceof ObjectNode object) {
            object.remove(GEOMETRY_KEYS);
            object.elements().forEachRemaining(PhotoCheckChatWriter::stripGeometry);
        } else if (node != null && node.isArray()) {
            node.elements().forEachRemaining(PhotoCheckChatWriter::stripGeometry);
        }
    }

    static String metadataJson(ObjectNode result, String label) {
        ObjectNode metadata = JSON.createObjectNode();
        metadata.put("kind", METADATA_KIND);
        metadata.put("v", METADATA_VERSION);
        // Never stored: positions (spec 2.3). The response built from the same result keeps them.
        metadata.set("result", withoutGeometry(result));
        if (label != null) {
            metadata.put("shot_label", label);
        }
        try {
            return JSON.writeValueAsString(metadata);
        } catch (IOException e) {
            throw new IllegalStateException("photo check metadata could not be written", e);
        }
    }

    private static ObjectNode storedResult(String metadataJson) {
        JsonNode metadata = parseObject(metadataJson);
        if (metadata == null || !METADATA_KIND.equals(metadata.path("kind").asText(null))) {
            return null;
        }
        JsonNode result = metadata.get("result");
        return result != null && result.isObject() ? (ObjectNode) result : null;
    }

    private static JsonNode parseObject(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode node = JSON.readTree(json);
            return node != null && node.isObject() ? node : null;
        } catch (IOException e) {
            return null;
        }
    }
}
