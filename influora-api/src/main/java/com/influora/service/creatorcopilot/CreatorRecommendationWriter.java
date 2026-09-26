package com.influora.service.creatorcopilot;

import com.influora.common.Ulids;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.CreatorRecommendation;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.CreatorRecommendationRepository;
import com.influora.service.creatorcopilot.CreatorRecommendationService.ValidItem;
import com.influora.service.creatorcopilot.CreatorRecommendationService.WritebackRecording;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one place a write-back's recommendation rows are inserted (slice 2, spec 8.3).
 *
 * <p><b>A separate bean on purpose,</b> exactly like {@code ApplicationHistoryWriter}: {@link
 * CreatorRecommendationService} hands this call to {@code AfterCommit} as a lambda, and a call on
 * {@code this} would be self-invocation with a silently inert {@code @Transactional}.
 *
 * <p><b>{@code REQUIRES_NEW} is safe here</b> because this only ever runs after the write-back
 * transaction has committed and released its locks (or with no transaction at all), never while a
 * caller holds a row lock this insert could wait on. It still needs its own transaction: during
 * {@code afterCommit} the committed transaction's resources are still bound to the thread, and the
 * pre-assigned ULID ids mean the INSERT happens at flush, which must happen inside this call so
 * the caller's guard can catch a failure.
 */
@Component
public class CreatorRecommendationWriter {

    private static final Logger log = LoggerFactory.getLogger(CreatorRecommendationWriter.class);

    private final CreatorRecommendationRepository repository;
    private final CreatorProfileRepository creatorProfileRepository;

    public CreatorRecommendationWriter(
            CreatorRecommendationRepository repository, CreatorProfileRepository creatorProfileRepository) {
        this.repository = repository;
        this.creatorProfileRepository = creatorProfileRepository;
    }

    /**
     * DPDP account deletion (Kabir M-1): deletes every recommendation row of this creator,
     * challenge rows included. {@code DELETE /me/account} soft-deletes the {@code users} row and
     * never deletes {@code creator_profiles}, so the profile FK cascade never fires; this is the
     * only thing that removes them. A user with no creator profile has none: a no-op.
     *
     * @return the number of rows deleted
     */
    @Transactional
    public int deleteAllForCreator(String creatorUserId) {
        return creatorProfileRepository
                .findByUserId(creatorUserId)
                .map(profile -> repository.deleteByCreatorProfileId(profile.getId()))
                .orElse(0);
    }

    /**
     * Inserts the rows whose {@code source_ref} is not stored yet. Throws on a database failure:
     * the caller's {@code AfterCommit} guard owns the catch and the log line (a unique-key replay
     * race, or the conversation FK when the conversation was deleted before this insert ran).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insert(WritebackRecording recording) {
        Optional<CreatorProfile> profile = creatorProfileRepository.findByUserId(recording.creatorUserId());
        if (profile.isEmpty()) {
            log.warn("creator recommendations not recorded: no creator profile for {}", recording.toLogContext());
            return;
        }
        String profileId = profile.get().getId();

        Map<String, List<ValidItem>> bySource = new LinkedHashMap<>();
        for (ValidItem item : recording.items()) {
            bySource.computeIfAbsent(item.source().name(), k -> new java.util.ArrayList<>()).add(item);
        }
        List<CreatorRecommendation> toInsert = new java.util.ArrayList<>();
        for (List<ValidItem> items : bySource.values()) {
            Map<String, ValidItem> byRef = new LinkedHashMap<>();
            for (ValidItem item : items) {
                byRef.put(recording.messageId() + ":" + item.lineIndex(), item);
            }
            repository
                    .findExistingSourceRefs(profileId, items.get(0).source(), byRef.keySet())
                    .forEach(byRef::remove);
            byRef.forEach(
                    (ref, item) ->
                            toInsert.add(
                                    CreatorRecommendation.open(
                                            Ulids.newUlid(),
                                            recording.creatorUserId(),
                                            profileId,
                                            item.source(),
                                            ref,
                                            recording.conversationId(),
                                            item.recommendedFor(),
                                            item.matchUntil(),
                                            item.postType(),
                                            item.windowLabel(),
                                            item.windowFrom(),
                                            item.windowTo(),
                                            item.structureName(),
                                            item.hookTemplate(),
                                            item.topic(),
                                            item.festival(),
                                            recording.promptVersion(),
                                            recording.knowledgeVersion(),
                                            recording.createdAt())));
        }
        if (!toInsert.isEmpty()) {
            repository.saveAll(toInsert);
        }
    }
}
