package com.influora.service;

import com.influora.common.ApiException;
import com.influora.domain.entity.AiConversation;
import com.influora.domain.entity.AiMessage;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.MeeraCreatorConversation;
import com.influora.repository.AiConversationRepository;
import com.influora.repository.AiMessageRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.MeeraCreatorConversationRepository;
import com.influora.web.dto.creator.CreatorAgentDtos.ConversationExportMessage;
import com.influora.web.dto.creator.CreatorAgentDtos.ConversationExportResponse;
import com.influora.web.dto.creator.CreatorAgentDtos.ConversationListResponse;
import com.influora.web.dto.creator.CreatorAgentDtos.ConversationSummary;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * T-MEERA-CREATOR-PHASE-A (SPEC.md 2.5-2.7, A6) — DPDP list/export/delete over a creator's Meera
 * conversations. Ownership is enforced entirely via {@link MeeraCreatorConversationRepository}
 * (whose {@code creatorId} is this creator's own {@code creator_profiles.id}) rather than via
 * {@code AiConversation.workspaceId} — for a CREATOR-audience turn that field carries the
 * creator's USER id (SPEC.md 2.9's {@code workspace_id: "creator_user_id_here"}), a different id
 * space than {@code creator_profiles.id}, so cross-checking against the tracking row sidesteps
 * that mismatch entirely and gives one single ownership check for list/export/delete alike.
 *
 * <p><b>Write side (fix round 1, item 4 — closed; fix round 2, item 2 — corrected):</b> {@link
 * #recordTurnForUser} is called from exactly ONE place: {@code
 * MeeraSessionService#doPersistAssistantWriteback}, once the turn's on-behalf-verified {@code
 * UserType} is confirmed CREATOR and the ASSISTANT reply has actually been persisted. It is
 * deliberately NOT also called from {@code doSendTurn} (the USER-message-persist step) any more —
 * that used to inflate {@code message_count} and create/touch a {@link MeeraCreatorConversation}
 * row for a turn whose provider call never completed (Python down, stream dropped, etc.), leaving
 * a dangling tracked "conversation" with no assistant reply. {@code message_count} now counts only
 * COMPLETED turns, while the DPDP consent screen's "you can export or delete your conversations
 * anytime" promise (SPEC.md 2.5-2.7) is still backed by real data for every turn that actually
 * finished.
 */
@Service
public class CreatorAgentConversationService {

    private final MeeraCreatorConversationRepository conversationRepository;
    private final CreatorProfileRepository creatorProfileRepository;
    private final AiConversationRepository aiConversationRepository;
    private final AiMessageRepository aiMessageRepository;

    public CreatorAgentConversationService(
            MeeraCreatorConversationRepository conversationRepository,
            CreatorProfileRepository creatorProfileRepository,
            AiConversationRepository aiConversationRepository,
            AiMessageRepository aiMessageRepository) {
        this.conversationRepository = conversationRepository;
        this.creatorProfileRepository = creatorProfileRepository;
        this.aiConversationRepository = aiConversationRepository;
        this.aiMessageRepository = aiMessageRepository;
    }

    private CreatorProfile requireCreatorProfile(String userId) {
        return creatorProfileRepository
                .findByUserId(userId)
                .orElseThrow(
                        () ->
                                new ApiException(
                                        "CREATOR_PROFILE_NOT_FOUND", "Creator profile not found", HttpStatus.NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public ConversationListResponse listConversations(String userId) {
        CreatorProfile profile = requireCreatorProfile(userId);
        List<ConversationSummary> summaries =
                conversationRepository.findByCreatorIdOrderByLastMessageAtDesc(profile.getId()).stream()
                        .map(
                                c ->
                                        new ConversationSummary(
                                                c.getConversationId(), c.getStartedAt(), c.getLastMessageAt(), c.getMessageCount()))
                        .toList();
        return new ConversationListResponse(summaries);
    }

    @Transactional(readOnly = true)
    public ConversationExportResponse exportConversation(String userId, String conversationId) {
        CreatorProfile profile = requireCreatorProfile(userId);
        MeeraCreatorConversation tracking = requireOwnedTracking(profile.getId(), conversationId);
        AiConversation conversation =
                aiConversationRepository
                        .findById(conversationId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "CONVERSATION_NOT_FOUND", "Conversation not found", HttpStatus.NOT_FOUND));
        List<ConversationExportMessage> messages =
                aiMessageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId).stream()
                        .map(
                                m ->
                                        new ConversationExportMessage(
                                                m.getRole().name().toLowerCase(java.util.Locale.ROOT),
                                                m.getContent(),
                                                m.getCreatedAt()))
                        .toList();
        return new ConversationExportResponse(tracking.getConversationId(), conversation.getCreatedAt(), messages);
    }

    @Transactional
    public void deleteConversation(String userId, String conversationId) {
        CreatorProfile profile = requireCreatorProfile(userId);
        MeeraCreatorConversation tracking = requireOwnedTracking(profile.getId(), conversationId);

        List<AiMessage> messages = aiMessageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId);
        aiMessageRepository.deleteAll(messages);
        conversationRepository.delete(tracking);
        aiConversationRepository.findById(conversationId).ifPresent(aiConversationRepository::delete);
    }

    private MeeraCreatorConversation requireOwnedTracking(String creatorProfileId, String conversationId) {
        return conversationRepository
                .findByConversationIdAndCreatorId(conversationId, creatorProfileId)
                .orElseThrow(
                        () ->
                                new ApiException(
                                        "CONVERSATION_NOT_FOUND", "Conversation not found", HttpStatus.NOT_FOUND));
    }

    /**
     * T-MEERA-CREATOR-PHASE-A (fix round 1, item 4) — convenience for {@link
     * com.influora.service.meera.MeeraSessionService}, which only ever has the creator's USER id
     * in scope for a CREATOR-audience turn (the {@code workspace_id} field is reused to carry it —
     * see {@code MeeraContextService#assembleCreatorContext} javadoc), never a {@code
     * creator_profiles.id} directly. Resolves the profile first, then delegates to {@link
     * #recordTurn}. Closes the "Write side" gap this class's javadoc used to flag: this is now
     * actually called — from {@code MeeraSessionService#doPersistAssistantWriteback} only, once
     * the ASSISTANT reply for a CREATOR turn has actually persisted (see class javadoc, fix round
     * 2 item 2, for why the earlier {@code doSendTurn}-side call was removed).
     */
    @Transactional
    public void recordTurnForUser(String creatorUserId, String conversationId, java.time.Instant when) {
        CreatorProfile profile = requireCreatorProfile(creatorUserId);
        recordTurn(profile.getId(), conversationId, when);
    }

    /**
     * Idempotent upsert for one persisted turn — see {@link #recordTurnForUser}, its usual caller.
     */
    @Transactional
    public void recordTurn(String creatorProfileId, String conversationId, java.time.Instant when) {
        MeeraCreatorConversation tracking =
                conversationRepository
                        .findByConversationId(conversationId)
                        .orElseGet(
                                () ->
                                        MeeraCreatorConversation.start(
                                                com.influora.common.Ulids.newUlid(), creatorProfileId, conversationId, when));
        tracking.recordMessage(when);
        conversationRepository.save(tracking);
    }
}
