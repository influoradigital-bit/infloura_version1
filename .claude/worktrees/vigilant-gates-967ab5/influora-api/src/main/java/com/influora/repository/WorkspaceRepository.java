package com.influora.repository;

import com.influora.domain.entity.Workspace;
import com.influora.domain.enums.VerificationStatus;
import com.influora.domain.enums.WorkspaceType;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkspaceRepository extends JpaRepository<Workspace, String> {

    boolean existsBySlug(String slug);

    boolean existsBySlugAndIdNot(String slug, String id);

    /**
     * Brand workspaces awaiting a KYC decision — backs {@code ApprovalWorkflowController}'s
     * unified pending-approvals queue (BRAND_KYC slice). Only {@code PENDING} (docs actually
     * submitted and awaiting review), not {@code UNVERIFIED} (nothing submitted yet — there is
     * nothing for an admin to action) — same distinction {@code AdminBrandService.mapKycStatus}
     * documents for the brand-detail KYC-status display, applied here to "needs action" filtering
     * instead.
     */
    List<Workspace> findByTypeAndVerificationStatusOrderByCreatedAtAsc(
            WorkspaceType type, VerificationStatus verificationStatus, Pageable pageable);
}
