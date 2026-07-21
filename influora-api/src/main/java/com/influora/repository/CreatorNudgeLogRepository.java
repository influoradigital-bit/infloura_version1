package com.influora.repository;

import com.influora.domain.entity.CreatorNudgeLog;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CreatorNudgeLogRepository extends JpaRepository<CreatorNudgeLog, String> {

    /** Idempotent-read-first — this IS the per-creator/day cap mechanism (be-services-plan §1 step
     * 2), not a separate check. A present result means today's suggestion was already generated;
     * the caller returns it as-is with no re-scoring and no AI call. */
    Optional<CreatorNudgeLog> findByCreatorProfileIdAndShownAtAfter(
            String creatorProfileId, Instant after);

    /** Resolves the row THEN the caller checks ownership — never trusts the id path param alone
     * (same IDOR discipline as {@code NudgeLogRepository.findByIdAndWorkspaceId}). */
    Optional<CreatorNudgeLog> findByIdAndCreatorProfileId(String id, String creatorProfileId);
}
