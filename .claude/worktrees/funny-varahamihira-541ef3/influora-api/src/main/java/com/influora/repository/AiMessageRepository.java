package com.influora.repository;

import com.influora.domain.entity.AiMessage;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiMessageRepository extends JpaRepository<AiMessage, String> {

    /**
     * Scoped by conversationId — callers MUST first resolve the conversation via
     * {@code AiConversationRepository.findByIdAndWorkspaceId} to enforce tenant isolation
     * (Guardrail 4) before calling this finder.
     */
    List<AiMessage> findByConversationIdOrderByCreatedAtAsc(String conversationId);
}
