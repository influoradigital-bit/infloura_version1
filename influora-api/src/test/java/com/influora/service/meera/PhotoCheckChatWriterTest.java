package com.influora.service.meera;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influora.domain.entity.AiConversation;
import com.influora.domain.entity.AiMessage;
import com.influora.domain.entity.IdempotencyKeyRecord;
import com.influora.domain.enums.ConversationStatus;
import com.influora.domain.enums.ConversationTenantType;
import com.influora.domain.enums.MessageRole;
import com.influora.integration.ai.MeeraVoiceAiClient.FrameCheckResult;
import com.influora.repository.AiConversationRepository;
import com.influora.repository.AiMessageRepository;
import com.influora.repository.IdempotencyKeyRecordRepository;
import com.influora.service.CreatorAgentConversationService;
import com.influora.service.IdempotencyReservationOps;
import com.influora.service.IdempotencyService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * {@link PhotoCheckChatWriter#withoutGeometry} and where it applies (spec 2.3): the stored
 * ASSISTANT row never carries {@code at}, {@code box} or {@code layout}, while the live result the
 * creator gets back still does, and a replayed key (read back from the stored row) is text only.
 *
 * <p>Repositories and the idempotency ledger are mocks (the ledger in a map, with the same
 * insert-first / COMPLETED rules as {@code CreatorMeeraControllerPhotoCheckTest}); the real-MySQL
 * half is {@code PhotoCheckChatWriterMySqlIntegrationTest}, which skips where Docker is missing.
 * The input is influora-ai's committed parser fixture plus the Phase 3/4 geometry keys.
 */
@ExtendWith(MockitoExtension.class)
class PhotoCheckChatWriterTest {

    private static final String CREATOR = "01HWPHOTOCHECKCREATOR00001";
    private static final String CONV = "01HWPHOTOCHECKCONVERSATN01";
    private static final String KEY = "6f1d3c2a-8a4b-4d0e-9a57-0c1b2d3e4f50";
    private static final Path FRAME_BODIES = Paths.get("..", "src", "lib", "__fixtures__", "shoot-check-frame-bodies.json");
    private static final ObjectMapper JSON = new ObjectMapper();

    @Mock private AiConversationRepository conversationRepository;
    @Mock private AiMessageRepository messageRepository;
    @Mock private CreatorAgentConversationService creatorAgentConversationService;
    @Mock private IdempotencyReservationOps reservationOps;
    @Mock private IdempotencyKeyRecordRepository idempotencyRepository;
    @Mock private PlatformTransactionManager transactionManager;

    private final Map<String, AiMessage> savedMessages = new LinkedHashMap<>();
    private final Map<String, IdempotencyKeyRecord> ledger = new ConcurrentHashMap<>();

    private PhotoCheckChatWriter writer;

    @BeforeEach
    void setUp() {
        writer =
                new PhotoCheckChatWriter(
                        conversationRepository,
                        messageRepository,
                        new IdempotencyService(idempotencyRepository, reservationOps),
                        creatorAgentConversationService,
                        transactionManager);
        AiConversation conversation =
                AiConversation.builder()
                        .id(CONV)
                        .workspaceId(CREATOR)
                        .tenantType(ConversationTenantType.CREATOR)
                        .startedBy(CREATOR)
                        .status(ConversationStatus.ACTIVE)
                        .build();
        lenient().when(conversationRepository.findByIdAndWorkspaceId(CONV, CREATOR)).thenReturn(Optional.of(conversation));
        lenient()
                .when(messageRepository.save(any(AiMessage.class)))
                .thenAnswer(
                        inv -> {
                            AiMessage m = inv.getArgument(0);
                            savedMessages.put(m.getId(), m);
                            return m;
                        });
        lenient().when(messageRepository.findById(anyString())).thenAnswer(inv -> Optional.ofNullable(savedMessages.get((String) inv.getArgument(0))));
        lenient().when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        lenient()
                .when(reservationOps.tryReserve(anyString(), any(), anyString()))
                .thenAnswer(
                        inv ->
                                ledger.putIfAbsent(
                                                inv.getArgument(0),
                                                IdempotencyKeyRecord.builder()
                                                        .idempotencyKey(inv.getArgument(0))
                                                        .workspaceId(inv.getArgument(1))
                                                        .scope(inv.getArgument(2))
                                                        .build())
                                        == null);
        lenient().when(reservationOps.reclaimFailedForRetry(anyString())).thenReturn(0);
        lenient()
                .doAnswer(
                        inv -> {
                            ledger.get((String) inv.getArgument(0)).markCompleted(inv.getArgument(1));
                            return null;
                        })
                .when(reservationOps)
                .markCompleted(anyString(), any());
        lenient()
                .doAnswer(
                        inv -> {
                            ledger.get((String) inv.getArgument(0)).markFailed();
                            return null;
                        })
                .when(reservationOps)
                .markFailed(anyString());
        lenient()
                .when(idempotencyRepository.findByIdempotencyKey(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(ledger.get((String) inv.getArgument(0))));
    }

    // ------------------------------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------------------------------

    private static ObjectNode box(double x, double y, double w, double h) {
        ObjectNode box = JSON.createObjectNode();
        box.put("x", x).put("y", y).put("w", w).put("h", h);
        return box;
    }

    /**
     * The bedroom fixture with every geometry key the spec adds: {@code steps[].at}, {@code
     * steps[].box} (Phase 3), top-level {@code layout} with faces and product (Phase 4), plus the
     * code-written {@code checks} list, which is text and stays.
     */
    private static ObjectNode withGeometry() throws Exception {
        JsonNode all = JSON.readTree(Files.readString(FRAME_BODIES, StandardCharsets.UTF_8));
        ObjectNode body = ((ObjectNode) all.get("bedroom_window_behind_en_a78")).deepCopy();
        ArrayNode steps = (ArrayNode) body.get("steps");
        ObjectNode first = (ObjectNode) steps.get(0);
        first.putObject("at").put("x", 0.62).put("y", 0.41);
        first.set("box", box(0.55, 0.3, 0.2, 0.25));
        if (steps.size() > 1) {
            ((ObjectNode) steps.get(1)).putObject("at").put("x", 0.2).put("y", 0.7);
        }
        ObjectNode layout = body.putObject("layout");
        layout.putArray("faces").add(box(0.3, 0.2, 0.25, 0.2)).add(box(0.6, 0.25, 0.2, 0.18));
        layout.set("product", box(0.62, 0.5, 0.2, 0.2));
        body.putArray("checks").add("Your face is near the edge; the app's buttons can cover it.");
        return body;
    }

    private static Supplier<FrameCheckResult> upstream(ObjectNode parsed, AtomicInteger calls) throws Exception {
        ObjectNode routeShaped = parsed.deepCopy();
        routeShaped.put("fallback", false);
        byte[] bytes = JSON.writeValueAsBytes(routeShaped);
        return () -> {
            calls.incrementAndGet();
            return new FrameCheckResult(true, bytes, "application/json", 200);
        };
    }

    /** Every field name at any depth, as "path.key", for a readable failure. */
    private static List<String> geometryPaths(JsonNode node, String path) {
        List<String> out = new ArrayList<>();
        if (node.isObject()) {
            node.fields()
                    .forEachRemaining(
                            e -> {
                                String here = path + "." + e.getKey();
                                if (PhotoCheckChatWriter.GEOMETRY_KEYS.contains(e.getKey())) {
                                    out.add(here);
                                }
                                out.addAll(geometryPaths(e.getValue(), here));
                            });
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                out.addAll(geometryPaths(node.get(i), path + "[" + i + "]"));
            }
        }
        return out;
    }

    private AiMessage storedAssistant() {
        return savedMessages.values().stream().filter(m -> m.getRole() == MessageRole.ASSISTANT).findFirst().orElseThrow();
    }

    // ------------------------------------------------------------------------------------------
    // withoutGeometry
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("withoutGeometry drops layout, steps[].at and steps[].box and keeps every other key, text and checks included")
    void withoutGeometry_dropsOnlyGeometry() throws Exception {
        ObjectNode result = withGeometry();
        ObjectNode input = result.deepCopy();

        ObjectNode stripped = PhotoCheckChatWriter.withoutGeometry(result);

        assertEquals(List.of(), geometryPaths(stripped, "$"));
        assertEquals(input, result, "the live result must not be changed");

        // Put back only the geometry the fixture had: everything else is byte-for-byte the same.
        ObjectNode expected = input.deepCopy();
        expected.remove("layout");
        for (JsonNode step : expected.get("steps")) {
            ((ObjectNode) step).remove(List.of("at", "box"));
        }
        assertEquals(expected, stripped);
        assertEquals("Your face is near the edge; the app's buttons can cover it.", stripped.at("/checks/0").asText());
        assertNull(PhotoCheckChatWriter.withoutGeometry(null));
    }

    @Test
    @DisplayName("withoutGeometry strips geometry keys at any depth, not only where Phases 3 and 4 put them")
    void withoutGeometry_anyDepth() {
        ObjectNode result = JSON.createObjectNode();
        ObjectNode nested = result.putObject("ask").putArray("options").addObject();
        nested.put("en", "Yes");
        nested.putObject("at").put("x", 0.1).put("y", 0.1);
        nested.putObject("deeper").set("box", box(0.1, 0.1, 0.1, 0.1));

        ObjectNode stripped = PhotoCheckChatWriter.withoutGeometry(result);

        assertEquals(List.of(), geometryPaths(stripped, "$"));
        assertEquals("Yes", stripped.at("/ask/options/0/en").asText());
        assertTrue(stripped.at("/ask/options/0/deeper").isObject());
    }

    @Test
    @DisplayName("metadataJson stores the result without at, box or layout")
    void metadataJson_hasNoGeometry() throws Exception {
        JsonNode metadata = JSON.readTree(PhotoCheckChatWriter.metadataJson(withGeometry(), "0-3s · Hook"));
        assertEquals(List.of(), geometryPaths(metadata, "$"));
        assertEquals("photo_check", metadata.get("kind").asText());
        assertTrue(metadata.at("/result/steps/0/text").isTextual());
        assertTrue(metadata.at("/result/checks").isArray());
    }

    // ------------------------------------------------------------------------------------------
    // checkAndWrite: stored row vs live response vs replay
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("spec 2.3: the stored row has no at, box or layout; the live result and 200 body still have them")
    void storedRowHasNoGeometry_responseKeepsIt() throws Exception {
        ObjectNode parsed = withGeometry();
        AtomicInteger calls = new AtomicInteger();

        PhotoCheckChatWriter.Outcome outcome =
                writer.checkAndWrite(CREATOR, CONV, KEY, null, "0-3s · Hook", upstream(parsed, calls));

        assertEquals(1, calls.get());
        assertFalse(outcome.replayed());
        assertNotNull(outcome.assistantMessageId(), "the pair was stored");

        // Live: exactly influora-ai's body minus fallback, positions included.
        assertEquals(parsed, outcome.result());
        JsonNode response =
                JSON.readTree(
                        PhotoCheckChatWriter.responseBody(
                                outcome.result(), outcome.text(), outcome.userMessageId(), outcome.assistantMessageId()));
        assertEquals(parsed.get("layout"), response.get("layout"));
        assertEquals(parsed.at("/steps/0/at"), response.at("/steps/0/at"));
        assertEquals(parsed.at("/steps/0/box"), response.at("/steps/0/box"));

        // Stored: no geometry anywhere in the metadata, and the history card built from it has none.
        AiMessage assistant = storedAssistant();
        assertEquals(outcome.assistantMessageId(), assistant.getId());
        JsonNode metadata = JSON.readTree(assistant.getMetadataJson());
        assertEquals(List.of(), geometryPaths(metadata, "$"));
        assertEquals(PhotoCheckChatWriter.withoutGeometry(parsed), metadata.get("result"));
        JsonNode card = JSON.valueToTree(PhotoCheckChatWriter.historyCard(assistant));
        assertEquals(List.of(), geometryPaths(card, "$"));

        // The text is the same either way (it never reads positions) and carries the quick check.
        assertEquals(PhotoCheckSummary.render(PhotoCheckChatWriter.withoutGeometry(parsed), "0-3s · Hook"), assistant.getContent());
        assertTrue(assistant.getContent().contains("\nQuick checks: Your face is near the edge"), assistant.getContent());
    }

    @Test
    @DisplayName("spec Phase 3: a replayed idempotency key returns the stored check, text only, and never calls the model again")
    void replayedKeyReturnsTextOnly() throws Exception {
        ObjectNode parsed = withGeometry();
        AtomicInteger calls = new AtomicInteger();
        PhotoCheckChatWriter.Outcome first =
                writer.checkAndWrite(CREATOR, CONV, KEY, null, "0-3s · Hook", upstream(parsed, calls));

        PhotoCheckChatWriter.Outcome replay =
                writer.checkAndWrite(CREATOR, CONV, KEY, null, "0-3s · Hook", upstream(parsed, calls));

        assertEquals(1, calls.get(), "the replay must not call the model");
        assertTrue(replay.replayed());
        assertEquals(first.assistantMessageId(), replay.assistantMessageId());
        assertEquals(first.userMessageId(), replay.userMessageId());
        assertEquals(first.text(), replay.text());
        assertEquals(List.of(), geometryPaths(replay.result(), "$"));
        JsonNode body =
                JSON.readTree(
                        PhotoCheckChatWriter.responseBody(
                                replay.result(), replay.text(), replay.userMessageId(), replay.assistantMessageId()));
        assertEquals(List.of(), geometryPaths(body, "$"));
        assertEquals(parsed.at("/steps/0/text"), body.at("/steps/0/text"));
    }
}
