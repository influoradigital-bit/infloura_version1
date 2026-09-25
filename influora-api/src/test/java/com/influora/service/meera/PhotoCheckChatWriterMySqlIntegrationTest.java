package com.influora.service.meera;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influora.common.ApiException;
import com.influora.common.Ulids;
import com.influora.domain.entity.AiConversation;
import com.influora.domain.entity.AiMessage;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.enums.MessageRole;
import com.influora.integration.ai.MeeraVoiceAiClient.FrameCheckResult;
import com.influora.repository.AiMessageRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.service.CreatorAgentConversationService;
import com.influora.testsupport.AbstractIntegrationTest;
import com.influora.web.dto.creator.CreatorAgentDtos.ConversationExportMessage;
import com.influora.web.dto.creator.CreatorAgentDtos.ConversationExportResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Photo check in Meera's chat, against REAL MySQL (Testcontainers) -- the half a mocked repository
 * cannot prove: the pair lands in {@code ai_messages} with its metadata in the {@code json}
 * column, {@code ai_conversations.last_message_at} and the creator's {@code
 * meera_creator_conversations} rollup move in the same transaction, a replayed key reads the
 * stored check back (MySQL re-orders JSON keys, so equality is by tree), the {@code ?before=}
 * query pages by id, and the DPDP delete removes the pair.
 *
 * <p>Named {@code *IntegrationTest} (not {@code *IT}) on purpose: surefire's default includes end
 * in {@code Test}, and this pom has no failsafe plugin, so a {@code *IT} class would never run in
 * CI -- a gate that passes by not running.
 *
 * <p><b>[READ BEFORE TRUSTING A GREEN OR SKIPPED RUN LOCALLY]</b> Like every {@link
 * AbstractIntegrationTest} subclass this is SKIPPED where Docker is unreachable (this repo's
 * Windows sandbox). It runs in CI.
 */
class PhotoCheckChatWriterMySqlIntegrationTest extends AbstractIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired private PhotoCheckChatWriter writer;
    @Autowired private MeeraSessionService sessionService;
    @Autowired private CreatorAgentConversationService creatorAgentConversationService;
    @Autowired private CreatorProfileRepository creatorProfileRepository;
    @Autowired private AiMessageRepository messageRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final List<String> seededUserIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (String userId : seededUserIds) {
            jdbcTemplate.update(
                    "DELETE FROM ai_messages WHERE conversation_id IN (SELECT id FROM ai_conversations WHERE workspace_id = ?)",
                    userId);
            jdbcTemplate.update(
                    "DELETE FROM meera_creator_conversations WHERE creator_id IN (SELECT id FROM creator_profiles WHERE user_id = ?)",
                    userId);
            jdbcTemplate.update("DELETE FROM ai_conversations WHERE workspace_id = ?", userId);
            jdbcTemplate.update("DELETE FROM idempotency_keys WHERE workspace_id = ?", userId);
            jdbcTemplate.update("DELETE FROM creator_profiles WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        }
        seededUserIds.clear();
    }

    private String seedCreator() {
        String userId = Ulids.newUlid();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, user_type, status) VALUES (?, ?, 'CREATOR', 'ACTIVE')",
                userId,
                "photo-check-" + userId.toLowerCase() + "@test.influora");
        seededUserIds.add(userId);
        creatorProfileRepository.saveAndFlush(CreatorProfile.newForUser(Ulids.newUlid(), userId, "Asha Test"));
        return userId;
    }

    private static ObjectNode fixture(String name) throws Exception {
        JsonNode all =
                JSON.readTree(
                        Files.readString(
                                Paths.get("..", "src", "lib", "__fixtures__", "shoot-check-frame-bodies.json"),
                                StandardCharsets.UTF_8));
        return ((ObjectNode) all.get(name)).deepCopy();
    }

    /** influora-ai's success envelope: {@code {**parsed, "fallback": False}}. */
    private static Supplier<FrameCheckResult> upstream(ObjectNode parsed, boolean routeShaped, AtomicInteger calls) {
        ObjectNode body = parsed.deepCopy();
        if (routeShaped) {
            body.put("fallback", false);
        }
        return () -> {
            calls.incrementAndGet();
            try {
                return new FrameCheckResult(true, JSON.writeValueAsBytes(body), "application/json", 200);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
    }

    private Timestamp conversationLastMessageAt(String conversationId) {
        return jdbcTemplate.queryForObject(
                "SELECT last_message_at FROM ai_conversations WHERE id = ?", Timestamp.class, conversationId);
    }

    @Test
    @DisplayName(
            "the pair commits with the JSON metadata, last_message_at and the rollup; a replay reads it back"
                    + " with no second model call; the DPDP delete removes it")
    void writeReplayAndDelete() throws Exception {
        String creator = seedCreator();
        AiConversation conversation = sessionService.startOrResumeForCreator(creator, creator, "Asha Test", "en-IN");
        Timestamp before = conversationLastMessageAt(conversation.getId());
        Thread.sleep(1100); // TIMESTAMP columns have second resolution
        AtomicInteger calls = new AtomicInteger();
        ObjectNode bedroom = fixture("bedroom_window_behind_en_a78");
        String key = java.util.UUID.randomUUID().toString();

        PhotoCheckChatWriter.Outcome first =
                writer.checkAndWrite(creator, conversation.getId(), key, null, "0-3s · By the window", upstream(bedroom, true, calls));

        assertThat(first.userMessageId()).isNotNull();
        assertThat(first.assistantMessageId()).isNotNull();
        List<Map<String, Object>> rows =
                jdbcTemplate.queryForList(
                        "SELECT id, role, content, credits_charged, JSON_UNQUOTE(JSON_EXTRACT(metadata, '$.kind')) AS kind"
                                + " FROM ai_messages WHERE conversation_id = ? ORDER BY id",
                        conversation.getId());
        assertThat(rows).hasSize(3); // greeting + the pair
        assertThat(rows.get(1).get("id")).isEqualTo(first.userMessageId());
        assertThat(rows.get(1).get("role")).isEqualTo("USER");
        assertThat(rows.get(1).get("content")).isEqualTo("Check my set-up: 0-3s · By the window");
        assertThat(rows.get(2).get("id")).isEqualTo(first.assistantMessageId());
        assertThat(rows.get(2).get("kind")).isEqualTo("photo_check");
        assertThat(rows.get(2).get("content")).isEqualTo(first.text());
        assertThat(((Number) rows.get(2).get("credits_charged")).intValue()).isZero();

        assertThat(conversationLastMessageAt(conversation.getId())).isAfter(before);
        Integer messageCount =
                jdbcTemplate.queryForObject(
                        "SELECT message_count FROM meera_creator_conversations WHERE conversation_id = ?",
                        Integer.class,
                        conversation.getId());
        assertThat(messageCount).isEqualTo(2); // the greeting's recordTurn + this pair's, once

        // The history card comes back from the MySQL json column.
        AiMessage stored = messageRepository.findById(first.assistantMessageId()).orElseThrow();
        Map<String, Object> card = PhotoCheckChatWriter.historyCard(stored);
        assertThat(card).isNotNull();
        assertThat((JsonNode) card.get("result")).isEqualTo(first.result());

        // Replay: the stored check, no second model call, same ids.
        PhotoCheckChatWriter.Outcome replay =
                writer.checkAndWrite(creator, conversation.getId(), key, null, "a different label", upstream(bedroom, true, calls));
        assertThat(calls.get()).isEqualTo(1);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.userMessageId()).isEqualTo(first.userMessageId());
        assertThat(replay.assistantMessageId()).isEqualTo(first.assistantMessageId());
        assertThat(replay.text()).isEqualTo(first.text());
        assertThat((JsonNode) replay.result()).isEqualTo(first.result());

        // Ash 3: the parser output with no fallback key writes nothing.
        PhotoCheckChatWriter.Outcome noFallback =
                writer.checkAndWrite(
                        creator, conversation.getId(), java.util.UUID.randomUUID().toString(), null, null, upstream(bedroom, false, calls));
        assertThat(noFallback.result()).isNull();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM ai_messages WHERE conversation_id = ?", Integer.class, conversation.getId()))
                .isEqualTo(3);

        // DPDP export: the check row carries its stored card, read back from the MySQL json column.
        ConversationExportResponse export = creatorAgentConversationService.exportConversation(creator, conversation.getId());
        List<ConversationExportMessage> exportedChecks =
                export.messages().stream().filter(m -> m.photoCheck() != null).toList();
        assertThat(exportedChecks).hasSize(1);
        assertThat(exportedChecks.get(0).content()).isEqualTo(first.text());
        assertThat((JsonNode) exportedChecks.get(0).photoCheck().get("result")).isEqualTo(first.result());
        assertThat(exportedChecks.get(0).photoCheck().get("shot_label")).isEqualTo("0-3s · By the window");

        // DPDP delete removes the pair with the rest of the conversation.
        creatorAgentConversationService.deleteConversation(creator, conversation.getId());
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM ai_messages WHERE id IN (?, ?)",
                        Integer.class,
                        first.userMessageId(),
                        first.assistantMessageId()))
                .isZero();
    }

    @Test
    @DisplayName(
            "?before= returns the 50 messages just older than the cursor, oldest-first, from MySQL -- only"
                    + " that conversation's rows, and another creator gets 404")
    void beforeCursorPagesById() {
        String creator = seedCreator();
        AiConversation conversation = sessionService.startOrResumeForCreator(creator, creator, "Asha Test", "en-IN");
        String otherCreator = seedCreator();
        AiConversation otherConversation =
                sessionService.startOrResumeForCreator(otherCreator, otherCreator, "Other Test", "en-IN");
        // 180 ids in one sorted run: every third goes to the OTHER creator's conversation, so its
        // rows sit between this conversation's ids (and below the cursor).
        List<String> all = new ArrayList<>();
        for (int i = 0; i < 180; i++) {
            all.add(Ulids.newUlid());
        }
        all.sort(String::compareTo);
        List<String> ids = new ArrayList<>();
        List<String> otherIds = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            boolean other = i % 3 == 2;
            (other ? otherIds : ids).add(all.get(i));
            messageRepository.saveAndFlush(
                    AiMessage.builder()
                            .id(all.get(i))
                            .conversationId(other ? otherConversation.getId() : conversation.getId())
                            .role(i % 2 == 0 ? MessageRole.USER : MessageRole.ASSISTANT)
                            .content("m" + i)
                            .creditsCharged(0)
                            .build());
        }
        assertThat(ids).hasSize(120);
        String cursor = ids.get(100);

        List<AiMessage> page = sessionService.listMessagesBefore(creator, conversation.getId(), cursor);

        assertThat(page).extracting(AiMessage::getId).containsExactlyElementsOf(ids.subList(50, 100));
        assertThat(page).allMatch(m -> m.getConversationId().equals(conversation.getId()));

        // The other creator asking for THIS conversation: the same 404 as an unknown id.
        assertThatThrownBy(() -> sessionService.listMessagesBefore(otherCreator, conversation.getId(), cursor))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("CONVERSATION_NOT_FOUND");
        assertThatThrownBy(() -> sessionService.listMessagesBefore(creator, Ulids.newUlid(), cursor))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("CONVERSATION_NOT_FOUND");
        // And the other creator's own page never holds this conversation's rows.
        List<AiMessage> otherPage = sessionService.listMessagesBefore(otherCreator, otherConversation.getId(), cursor);
        assertThat(otherPage).isNotEmpty();
        assertThat(otherPage).allMatch(m -> m.getConversationId().equals(otherConversation.getId()));
        assertThat(otherPage).extracting(AiMessage::getId).doesNotContainAnyElementsOf(ids);
    }
}
