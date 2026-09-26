package com.influora.service.creatorcopilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.influora.common.AfterCommit;
import com.influora.common.SensitiveTextRedactor;
import com.influora.common.Ulids;
import com.influora.domain.entity.CreatorChallenge;
import com.influora.domain.entity.CreatorChallengeDay;
import com.influora.domain.entity.CreatorRecommendation;
import com.influora.domain.enums.ChallengeDayType;
import com.influora.domain.enums.CreatorRecommendationSource;
import com.influora.repository.CreatorRecommendationRepository;
import com.influora.web.dto.meera.MeeraToolDtos.WritebackRecommendation;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Meera intelligence v1, slice 2 (spec 8.3) -- records what a creator was recommended to post.
 * No AI-written facts and no model tool: challenge rows come from the deterministic challenge
 * plan, plan and script rows from influora-ai's deterministic parsers of Meera's final text, sent
 * as the write-back's {@code metadata.recommendations}.
 *
 * <h2>Write-back recording never fails or slows the chat write-back</h2>
 *
 * <p>{@link #recordFromWriteback} is called by {@code MeeraSessionService#persistAssistantWriteback}
 * after the write-back's own transaction has returned. It parses and validates the items in
 * memory (no database work, never throws) and hands the insert to {@link AfterCommit}, which runs
 * {@link CreatorRecommendationWriter#insert} in its OWN {@code REQUIRES_NEW} transaction only after
 * any caller transaction has committed (inline when there is none, which is the case today). So:
 *
 * <ul>
 *   <li>It never runs inside the write-back transaction, which holds the creator credit account
 *       lock ({@code CreatorCreditService#assertTurnNotReleased}). That is the lock-stall shape
 *       {@code ApplicationHistoryService} was fixed for (a {@code REQUIRES_NEW} insert waiting on
 *       its own caller's row lock until {@code innodb_lock_wait_timeout}); this insert can only
 *       start after those locks are released.
 *   <li>A failure (bad data, a constraint, a dead connection) is caught by {@link AfterCommit}'s
 *       guard and logged; the persisted message and the HTTP 200 are unaffected. A replay racing
 *       the first insert on {@code uk_creator_rec_source} is logged at INFO, not as a loss.
 *   <li>The work is bounded: at most {@link #MAX_WRITEBACK_ITEMS} short rows, one existence query
 *       and one insert batch.
 * </ul>
 *
 * <h2>Validation</h2>
 *
 * <p>Each item is validated on its own. An unknown {@code source} or {@code post_type}, a {@code
 * CHALLENGE} source (server-made only), a missing or negative {@code line_index}, or a plan line
 * without a valid date is dropped with a WARN; the other items are still recorded. Free text is
 * neutralised (control and format characters removed, whitespace collapsed), passed through
 * {@link SensitiveTextRedactor}, and cut to its column size. A replay is a no-op: rows whose
 * {@code source_ref} already exists are skipped, and the unique key is the backstop.
 */
@Service
public class CreatorRecommendationService {

    private static final Logger log = LoggerFactory.getLogger(CreatorRecommendationService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    /** The write-back contract's cap: the week plan has 7 lines. Items past it are ignored. */
    public static final int MAX_WRITEBACK_ITEMS = 7;

    /** A script card has no date; a post within this many IST days of the recommendation fills it. */
    public static final int SCRIPT_MATCH_DAYS = 7;

    /** A plan date further than this from the day it was recommended is not a real plan line. */
    static final int MAX_PLAN_DATE_DISTANCE_DAYS = 14;

    // Column sizes (V20260925150100).
    static final int SOURCE_REF_MAX = 64;
    static final int WINDOW_LABEL_MAX = 24;
    static final int STRUCTURE_NAME_MAX = 80;
    static final int HOOK_TEMPLATE_MAX = 80;
    static final int TOPIC_MAX = 160;
    static final int FESTIVAL_MAX = 80;
    static final int VERSION_MAX = 32;

    /**
     * Control characters, format characters (bidi overrides, zero-width space, BOM, soft hyphen...),
     * line/paragraph separators -- EXCEPT U+200C ZERO WIDTH NON-JOINER and U+200D ZERO WIDTH
     * JOINER. Those two are format characters too, but text needs them: they select Devanagari
     * half-forms and conjuncts (and Bengali, Malayalam...) and join emoji sequences, so stripping
     * them changed the words and broke the emoji. They carry no direction and cannot reorder text.
     */
    private static final Pattern UNSAFE_CHARS =
            Pattern.compile("[[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]&&[^\\x{200C}\\x{200D}]]");

    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");

    private final CreatorRecommendationRepository repository;
    private final CreatorRecommendationWriter writer;
    private final ObjectMapper objectMapper;

    public CreatorRecommendationService(
            CreatorRecommendationRepository repository,
            CreatorRecommendationWriter writer,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.writer = writer;
        this.objectMapper = objectMapper;
    }

    // ============================== CHALLENGE ==============================

    /**
     * One OPEN row per non-REST day, {@code source_ref = challengeId:dayIndex}, type and window
     * copied from the planned day. Called by {@code CreatorChallengeService#start} after its {@code
     * dayRepository.saveAll}, in the SAME transaction: the rows are deterministic server data and
     * belong with the challenge they describe. A replay is a no-op (existing refs are skipped).
     */
    public void recordChallengePlan(CreatorChallenge challenge, List<CreatorChallengeDay> days, Instant now) {
        Map<String, CreatorRecommendation> byRef = new LinkedHashMap<>();
        for (CreatorChallengeDay day : days) {
            if (day.getPlannedType() == null || day.getPlannedType() == ChallengeDayType.REST) {
                continue;
            }
            String ref = challenge.getId() + ":" + day.getDayIndex();
            byRef.put(
                    ref,
                    CreatorRecommendation.open(
                            Ulids.newUlid(),
                            challenge.getCreatorUserId(),
                            challenge.getCreatorProfileId(),
                            CreatorRecommendationSource.CHALLENGE,
                            ref,
                            null,
                            day.getDate(),
                            day.getDate().plusDays(1),
                            day.getPlannedType(),
                            day.getWindowLabel(),
                            day.getWindowFrom(),
                            day.getWindowTo(),
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            now));
        }
        if (byRef.isEmpty()) {
            return;
        }
        List<String> existing =
                repository.findExistingSourceRefs(
                        challenge.getCreatorProfileId(), CreatorRecommendationSource.CHALLENGE, byRef.keySet());
        existing.forEach(byRef::remove);
        if (!byRef.isEmpty()) {
            repository.saveAll(List.copyOf(byRef.values()));
        }
    }

    // ============================== PLAN_MY_WEEK / SCRIPT_CARD ==============================

    /** One validated write-back item, ready to insert. Free text already neutralised and capped. */
    public record ValidItem(
            CreatorRecommendationSource source,
            int lineIndex,
            LocalDate recommendedFor,
            LocalDate matchUntil,
            ChallengeDayType postType,
            String windowLabel,
            LocalTime windowFrom,
            LocalTime windowTo,
            String structureName,
            String hookTemplate,
            String topic,
            String festival) {}

    /** Everything one write-back records, captured before the insert is deferred. */
    public record WritebackRecording(
            String creatorUserId,
            String conversationId,
            String messageId,
            String promptVersion,
            String knowledgeVersion,
            Instant createdAt,
            List<ValidItem> items) {

        String toLogContext() {
            return "creatorUserId="
                    + creatorUserId
                    + " conversationId="
                    + conversationId
                    + " messageId="
                    + messageId
                    + " items="
                    + items.size()
                    + " promptVersion="
                    + promptVersion;
        }
    }

    /**
     * Records a CREATOR write-back's {@code metadata.recommendations}. Never throws, never touches
     * the database in the caller's transaction: see the class javadoc.
     *
     * @param messageId the turn's server-minted messageId (the write-back's Idempotency-Key), so a
     *     replay produces the same {@code source_ref}s
     */
    public void recordFromWriteback(
            String creatorUserId, String conversationId, String messageId, Map<String, Object> metadata, Instant now) {
        try {
            WritebackRecording recording = parse(creatorUserId, conversationId, messageId, metadata, now);
            if (recording == null || recording.items().isEmpty()) {
                return;
            }
            AfterCommit.run(
                    "creator recommendations",
                    recording::toLogContext,
                    () -> {
                        try {
                            writer.insert(recording);
                        } catch (DataIntegrityViolationException raced) {
                            // Either a concurrent replay inserted the same source_refs first (the
                            // rows exist), or the creator deleted this conversation before the insert
                            // ran and fk_creator_rec_conversation refused the orphan (Kabir L-2):
                            // nothing should be recorded then. Neither is a loss.
                            log.info(
                                    "creator recommendations not inserted (a concurrent replay recorded them, or"
                                            + " the conversation was deleted first): {} cause={}",
                                    recording.toLogContext(),
                                    raced.getMostSpecificCause().getClass().getSimpleName());
                        }
                    });
        } catch (RuntimeException e) {
            log.warn(
                    "creator recommendations not recorded (write-back unaffected): creatorUserId={}"
                            + " conversationId={} messageId={} error={}",
                    creatorUserId,
                    conversationId,
                    messageId,
                    e.toString());
        }
    }

    /**
     * Pure: validates the metadata into a {@link WritebackRecording}, or null when there is
     * nothing to record. Package-visible for tests and for the fixture test.
     */
    WritebackRecording parse(
            String creatorUserId, String conversationId, String messageId, Map<String, Object> metadata, Instant now) {
        if (metadata == null || creatorUserId == null || messageId == null || messageId.isBlank()) {
            return null;
        }
        Object raw = metadata.get("recommendations");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return null;
        }
        if (list.size() > MAX_WRITEBACK_ITEMS) {
            log.warn(
                    "write-back carried {} recommendations; only the first {} are recorded (messageId={})",
                    list.size(),
                    MAX_WRITEBACK_ITEMS,
                    messageId);
        }
        LocalDate today = LocalDate.ofInstant(now, IST);
        List<ValidItem> items = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Object element : list.subList(0, Math.min(list.size(), MAX_WRITEBACK_ITEMS))) {
            ValidItem item = validate(element, today, messageId);
            if (item == null) {
                continue;
            }
            String key = item.source() + ":" + item.lineIndex();
            if (!seen.add(key)) {
                log.warn("duplicate recommendation line_index {} dropped (messageId={})", item.lineIndex(), messageId);
                continue;
            }
            items.add(item);
        }
        return new WritebackRecording(
                creatorUserId,
                conversationId,
                messageId,
                cleanText(stringOrNull(metadata.get("prompt_version")), VERSION_MAX),
                cleanText(stringOrNull(metadata.get("knowledge_version")), VERSION_MAX),
                now,
                List.copyOf(items));
    }

    private ValidItem validate(Object element, LocalDate today, String messageId) {
        WritebackRecommendation wire;
        try {
            wire = objectMapper.convertValue(element, WritebackRecommendation.class);
        } catch (IllegalArgumentException malformed) {
            log.warn("recommendation item dropped: not the wire shape (messageId={})", messageId);
            return null;
        }
        if (wire == null) {
            return null;
        }
        CreatorRecommendationSource source = writebackSource(wire.source());
        ChallengeDayType postType = postType(wire.postType());
        if (source == null || postType == null) {
            log.warn(
                    "recommendation item dropped: unknown source {} or post_type {} (messageId={})",
                    safeForLog(wire.source()),
                    safeForLog(wire.postType()),
                    messageId);
            return null;
        }
        Integer lineIndex = wire.lineIndex();
        if (lineIndex == null || lineIndex < 0 || (messageId + ":" + lineIndex).length() > SOURCE_REF_MAX) {
            log.warn("recommendation item dropped: bad line_index {} (messageId={})", lineIndex, messageId);
            return null;
        }

        LocalDate recommendedFor = null;
        LocalDate matchUntil;
        if (source == CreatorRecommendationSource.PLAN_MY_WEEK) {
            recommendedFor = parseDate(wire.recommendedFor());
            if (recommendedFor == null
                    || Math.abs(ChronoUnit.DAYS.between(today, recommendedFor)) > MAX_PLAN_DATE_DISTANCE_DAYS) {
                log.warn(
                        "plan recommendation dropped: recommended_for {} is not a date near {} (messageId={})",
                        safeForLog(wire.recommendedFor()),
                        today,
                        messageId);
                return null;
            }
            // Exclusive end: the plan's day only; MISSED from recommended_for + 1 day + 12 h.
            matchUntil = recommendedFor.plusDays(1);
        } else {
            // A script card has no date: any post in [today, today + 7) fills it.
            matchUntil = today.plusDays(SCRIPT_MATCH_DAYS);
        }

        return new ValidItem(
                source,
                lineIndex,
                recommendedFor,
                matchUntil,
                postType,
                cleanText(wire.windowLabel(), WINDOW_LABEL_MAX),
                parseTime(wire.windowFrom()),
                parseTime(wire.windowTo()),
                cleanText(wire.structureName(), STRUCTURE_NAME_MAX),
                cleanText(wire.hookTemplate(), HOOK_TEMPLATE_MAX),
                cleanText(wire.topic(), TOPIC_MAX),
                cleanText(wire.festival(), FESTIVAL_MAX));
    }

    /** PLAN_MY_WEEK or SCRIPT_CARD; CHALLENGE rows are server-made and never accepted from here. */
    static CreatorRecommendationSource writebackSource(String value) {
        if ("PLAN_MY_WEEK".equals(value)) {
            return CreatorRecommendationSource.PLAN_MY_WEEK;
        }
        if ("SCRIPT_CARD".equals(value)) {
            return CreatorRecommendationSource.SCRIPT_CARD;
        }
        return null;
    }

    /** REEL, CAROUSEL or POST exactly; REST and anything else is not a post to make. */
    static ChallengeDayType postType(String value) {
        if (value == null) {
            return null;
        }
        return switch (value) {
            case "REEL" -> ChallengeDayType.REEL;
            case "CAROUSEL" -> ChallengeDayType.CAROUSEL;
            case "POST" -> ChallengeDayType.POST;
            default -> null;
        };
    }

    private static LocalDate parseDate(String value) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** "HH:mm" exactly; anything else becomes null (the item is kept, the time is not). */
    private static LocalTime parseTime(String value) {
        if (value == null || value.length() != 5) {
            return null;
        }
        try {
            return LocalTime.parse(value, HH_MM);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String stringOrNull(Object value) {
        return value instanceof String s ? s : null;
    }

    /**
     * Neutralises free text for storage: control, format and line-separator characters become
     * spaces, whitespace runs collapse, {@link SensitiveTextRedactor} scrubs secrets and personal
     * numbers, and the result is cut to {@code max} characters without splitting a surrogate pair.
     * Blank becomes null.
     */
    static String cleanText(String value, int max) {
        if (value == null) {
            return null;
        }
        String cleaned = WHITESPACE_RUN.matcher(UNSAFE_CHARS.matcher(value).replaceAll(" ")).replaceAll(" ").trim();
        if (cleaned.isEmpty()) {
            return null;
        }
        String redacted = SensitiveTextRedactor.redact(cleaned);
        if (redacted.length() <= max) {
            return redacted;
        }
        int end = max;
        if (Character.isHighSurrogate(redacted.charAt(end - 1))) {
            end--;
        }
        String cut = redacted.substring(0, end).trim();
        return cut.isEmpty() ? null : cut;
    }

    private static String safeForLog(String value) {
        String cleaned = cleanText(value, 40);
        return cleaned == null ? "null" : cleaned;
    }
}
