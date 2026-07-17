package com.influora.repository;

import com.influora.domain.entity.AiMessage;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiMessageRepository extends JpaRepository<AiMessage, String> {

    /**
     * Scoped by conversationId — callers MUST first resolve the conversation via
     * {@code AiConversationRepository.findByIdAndWorkspaceId} to enforce tenant isolation
     * (Guardrail 4) before calling this finder.
     */
    List<AiMessage> findByConversationIdOrderByCreatedAtAsc(String conversationId);

    /**
     * The most-recently-persisted message for a conversation — used by {@code
     * MeeraSessionService#persistAssistantWriteback}'s idempotent-replay path [E2 audit finding
     * #16, MEDIUM] to serve a graceful replay response when a retried write-back callback hits an
     * already-completed {@code Idempotency-Key} (no dedicated idempotency-key column exists on
     * {@code ai_messages} itself — see that method's javadoc for why the shared {@code
     * IdempotencyService} ledger is the sole dedup gate and this finder only supplies the replay
     * response body, never the dedup decision itself).
     */
    Optional<AiMessage> findTopByConversationIdOrderByCreatedAtDesc(String conversationId);

    /**
     * F10 — cursor page for {@code MeeraSessionService#listMessages(String, String, String)}: every
     * message strictly after {@code afterId} in id order. Ids are ULIDs (monotonically sortable by
     * creation time, {@code VARCHAR(26)}), so a lexicographic {@code >} comparison is equivalent to
     * "created after" without the millisecond-collision risk a {@code createdAt} cursor would have.
     * Same tenant-isolation contract as {@link #findByConversationIdOrderByCreatedAtAsc}: callers
     * MUST first resolve the conversation via {@code AiConversationRepository
     * .findByIdAndWorkspaceId}.
     */
    List<AiMessage> findByConversationIdAndIdGreaterThanOrderByIdAsc(String conversationId, String afterId);
}
