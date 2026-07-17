package com.influora.repository;

import com.influora.domain.entity.BrandAiCredit;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface BrandAiCreditRepository extends JpaRepository<BrandAiCredit, String> {

    /** 1:1 workspace lookup — the PK is the workspaceId, so this is inherently tenant-scoped. */
    Optional<BrandAiCredit> findByWorkspaceId(String workspaceId);

    /**
     * Atomic, race-safe decrement: only succeeds (returns 1) if credits are still available.
     * Mirrors the {@code UPDATE ... WHERE credits_remaining > 0} pattern mandated by Guardrail 5
     * so concurrent turns can never drive the balance negative.
     */
    @Modifying
    @Transactional
    @Query(
            "UPDATE BrandAiCredit c SET c.creditsRemaining = c.creditsRemaining - :cost, "
                    + "c.updatedAt = CURRENT_TIMESTAMP "
                    + "WHERE c.workspaceId = :workspaceId AND c.creditsRemaining >= :cost")
    int tryDecrement(@Param("workspaceId") String workspaceId, @Param("cost") int cost);
}
