package com.influora.repository;

import com.influora.domain.entity.Campaign;
import com.influora.domain.enums.CampaignStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CampaignRepository
        extends JpaRepository<Campaign, String>, JpaSpecificationExecutor<Campaign> {

    Optional<Campaign> findByIdAndWorkspaceId(String id, String workspaceId);

    /** Platform-wide count by status — powers AdminDashboardController's CEO Pulse/operations reads. */
    long countByStatus(CampaignStatus status);

    /** All campaigns for a workspace — powers AdminBrandController's brand-detail campaign list. */
    List<Campaign> findByWorkspaceId(String workspaceId);

    /**
     * Row lock for the status-transition-plus-side-effects sequence around campaign activation
     * (Kabir red-team finding: unlike {@code WalletRepository}/{@code EscrowHoldRepository}, no
     * lock previously existed here, so two concurrent activation requests could both pass the
     * pre-ACTIVE check unlocked). Matches the exact {@code @Lock(PESSIMISTIC_WRITE)} +
     * {@code @Query} pattern used by those repositories. Callers are responsible for their own
     * tenant (workspaceId) check after loading, same as the unlocked {@code findByIdAndWorkspaceId}
     * 404-for-both-cases discipline elsewhere in this codebase.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Campaign c where c.id = :id")
    Optional<Campaign> findByIdForUpdate(@Param("id") String id);
}
