package com.influora.repository;

import com.influora.domain.entity.AiConversation;
import com.influora.domain.enums.ConversationStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiConversationRepository extends JpaRepository<AiConversation, String> {

    /** Tenant-scoped — every finder takes workspaceId (Guardrail 4), never a global unscoped list. */
    List<AiConversation> findByWorkspaceIdAndStatus(String workspaceId, ConversationStatus status);

    Optional<AiConversation> findFirstByWorkspaceIdAndStatusOrderByLastMessageAtDesc(
            String workspaceId, ConversationStatus status);

    Optional<AiConversation> findByIdAndWorkspaceId(String id, String workspaceId);
}
