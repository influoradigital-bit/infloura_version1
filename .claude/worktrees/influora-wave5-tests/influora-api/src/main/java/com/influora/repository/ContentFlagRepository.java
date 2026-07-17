package com.influora.repository;

import com.influora.domain.entity.ContentFlag;
import com.influora.domain.enums.ContentFlagStatus;
import com.influora.domain.enums.ContentFlagType;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContentFlagRepository extends JpaRepository<ContentFlag, String> {

    /**
     * Open (not-yet-actioned) flag count for one piece of content — backs {@code
     * AdminCreatorService}'s {@code CreatorDetail.flaggedContentCount} rollup (contentType=PROFILE,
     * contentId=creatorProfileId), matching the field's javadoc in admin.types.ts ("count of open
     * PENDING + REVIEWED ContentFlag rows").
     */
    long countByContentTypeAndContentIdAndStatusIn(
            ContentFlagType contentType, String contentId, Collection<ContentFlagStatus> statuses);

    /** Flags actually awaiting a decision — backs the CONTENT_MODERATION slice of the pending-approvals queue. */
    Page<ContentFlag> findByStatusOrderByCreatedAtAsc(ContentFlagStatus status, Pageable pageable);

    /** True when this user already flagged this content (V46 unique gate). */
    boolean existsByContentIdAndFlaggedByUserId(String contentId, String flaggedByUserId);
}
