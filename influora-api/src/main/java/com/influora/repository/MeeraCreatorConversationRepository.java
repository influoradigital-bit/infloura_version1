package com.influora.repository;

import com.influora.domain.entity.MeeraCreatorConversation;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** T-MEERA-CREATOR-PHASE-A (SPEC.md 1.4, A6). {@code creatorId} is a {@code creator_profiles.id}. */
public interface MeeraCreatorConversationRepository extends JpaRepository<MeeraCreatorConversation, String> {

    List<MeeraCreatorConversation> findByCreatorIdOrderByLastMessageAtDesc(String creatorId);

    Optional<MeeraCreatorConversation> findByConversationId(String conversationId);

    Optional<MeeraCreatorConversation> findByConversationIdAndCreatorId(String conversationId, String creatorId);
}
