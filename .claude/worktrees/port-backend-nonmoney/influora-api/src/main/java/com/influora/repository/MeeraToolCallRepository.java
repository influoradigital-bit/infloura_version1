package com.influora.repository;

import com.influora.domain.entity.MeeraToolCall;
import com.influora.domain.enums.MeeraToolName;
import com.influora.domain.enums.ToolCallStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MeeraToolCallRepository extends JpaRepository<MeeraToolCall, String> {

    /** Idempotency lookup — retries resolve to the prior result instead of a second effect. */
    Optional<MeeraToolCall> findByIdempotencyKey(String idempotencyKey);

    /** Tenant-scoped listing (Guardrail 4) — never call findAll() for this table. */
    List<MeeraToolCall> findByWorkspaceId(String workspaceId);

    List<MeeraToolCall> findByWorkspaceIdAndConversationId(String workspaceId, String conversationId);

    /**
     * [SEC: Kavya B1-REGRESSION-1 fix] Audit-ledger lookup used by {@code ConfirmLaunchExecutor}
     * to tell "confirm_launch genuinely already ran for this campaign" apart from "campaign.status
     * became ACTIVE via some other path" (e.g. {@code CampaignService.update()}, the plain PATCH
     * /campaigns/{id} endpoint). Deliberately independent of {@code campaign.status} — that field
     * cannot distinguish the two cases on its own.
     */
    boolean existsByToolNameAndResultRefIdAndStatus(
            MeeraToolName toolName, String resultRefId, ToolCallStatus status);
}
