package com.influora.repository;

import com.influora.domain.entity.MeeraToolCall;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MeeraToolCallRepository extends JpaRepository<MeeraToolCall, String> {

    /** Idempotency lookup — retries resolve to the prior result instead of a second effect. */
    Optional<MeeraToolCall> findByIdempotencyKey(String idempotencyKey);

    /** Tenant-scoped listing (Guardrail 4) — never call findAll() for this table. */
    List<MeeraToolCall> findByWorkspaceId(String workspaceId);

    List<MeeraToolCall> findByWorkspaceIdAndConversationId(String workspaceId, String conversationId);
}
