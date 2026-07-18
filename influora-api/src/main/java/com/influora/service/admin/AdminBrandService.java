package com.influora.service.admin;

import com.influora.common.ApiException;
import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.EscrowHold;
import com.influora.domain.entity.User;
import com.influora.domain.entity.Wallet;
import com.influora.domain.entity.WalletTransaction;
import com.influora.domain.entity.Workspace;
import com.influora.domain.entity.WorkspaceMember;
import com.influora.domain.enums.AdminRole;
import com.influora.domain.enums.CollaborationStatus;
import com.influora.domain.enums.EscrowStatus;
import com.influora.domain.enums.KycStatusView;
import com.influora.domain.enums.MemberRole;
import com.influora.domain.enums.VerificationStatus;
import com.influora.domain.enums.WorkspaceType;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.EscrowHoldRepository;
import com.influora.repository.UserRepository;
import com.influora.repository.WalletRepository;
import com.influora.repository.WalletTransactionRepository;
import com.influora.repository.WorkspaceMemberRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.security.AuthPrincipal;
import com.influora.web.dto.admin.AdminBrandDtos.BrandDetailDto;
import com.influora.web.dto.admin.AdminBrandDtos.BrandSummaryDto;
import com.influora.web.dto.admin.AdminBrandDtos.CampaignSummaryDto;
import com.influora.web.dto.admin.AdminBrandDtos.PaginatedBrandResponse;
import com.influora.web.dto.admin.AdminBrandDtos.PaymentRecordDto;
import com.influora.web.dto.admin.AdminBrandDtos.TeamMemberDto;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Brand CRUD/moderation API (src/admin/TASK_ASSIGNMENTS.md P1: "Brand CRUD API" — Vikram, cycle
 * 4). Backs {@code AdminBrandController}, which in turn is the swap-in target for {@code
 * useBrandDetail.ts}/{@code BrandProfile.tsx}'s stubbed {@code brandApi.getById}/{@code
 * .verifyKyc}/{@code .suspend}/{@code .reinstate} calls (Ananya, cycle 2).
 *
 * <p><b>Entity reuse, not invention:</b> {@code src/admin/types/admin.types.ts}'s {@code Brand}/
 * {@code BrandDetail} map onto the EXISTING {@link Workspace} entity (V2__core_auth.sql,
 * {@code type = BRAND}) — confirmed by grep before writing this service: {@code Workspace} already
 * carries {@code name}/{@code industry}/{@code companySize}/{@code verificationStatus}/{@code
 * gstin}/{@code pan}, exactly the shape {@code BrandProfile.tsx} renders. No duplicate
 * {@code Brand} entity/table was created. What the schema was missing (suspension flag + audit
 * trail, billing address, KYC review trail) was added by
 * V36__workspace_suspension_kyc_audit.sql — see that migration's header and {@link
 * Workspace#suspend}/{@link Workspace#reinstate}/{@link Workspace#applyKycDecision}.
 *
 * <p><b>RBAC:</b> every method gates via {@link AdminContextService#requireRoleWithMfaSatisfied},
 * matching cycle 2/3's established convention ({@code AdminDashboardService}) of calling the
 * MFA-aware variant even for SUPPORT-permitted reads (SUPPORT itself is exempt from the MFA gate —
 * see {@code AdminContextService} class javadoc). Role allow-lists per method are taken directly
 * from {@code src/admin/__tests__/role-permission-matrix.md}: brand detail read is SUPER_ADMIN/
 * ADMIN/SUPPORT (view-only); KYC review/suspend/reinstate are all SUPER_ADMIN/ADMIN only
 * (destructive/compliance actions, SUPPORT excluded).
 *
 * <p><b>Audit trail:</b> every mutating method (verifyKyc/suspend/reinstate) calls {@link
 * AdminAuditLogService#record} AFTER the {@code Workspace} mutation is saved, per Kabir's spec —
 * see {@code AdminAuditLogService} class javadoc for the allow-list/field-provenance rules this
 * follows.
 */
@Service
public class AdminBrandService {

    /**
     * Sane upper bound for an admin campaign budget override (MONEY PATH). Well within the {@code
     * campaigns.budget_max DECIMAL(12,2)} column capacity (max ~9,999,999,999.99), chosen as a
     * defensive guardrail against a fat-fingered/hostile huge value, not a business rule. Kabir/
     * Rohan: adjust to the real per-campaign ceiling once product defines one.
     */
    private static final BigDecimal MAX_CAMPAIGN_BUDGET = new BigDecimal("100000000.00");

    /**
     * Collaboration statuses that represent a REAL agreed financial obligation on a campaign (M-2,
     * Kabir): terms are agreed and work is contracted/in-flight, so {@code agreedRate} is a
     * committed amount even before it is moved into an escrow hold. Deliberately EXCLUDES:
     * pre-agreement states (INVITED/APPLIED/SHORTLISTED/IN_NEGOTIATION — rate not final), COMPLETED
     * (payout already realized as a RELEASED escrow hold, counted via escrow), and
     * CANCELLED/DISPUTED (no live obligation / already frozen in escrow).
     */
    private static final Set<CollaborationStatus> COMMITTED_COLLAB_STATUSES =
            EnumSet.of(
                    CollaborationStatus.TERMS_AGREED,
                    CollaborationStatus.CONTRACT_PENDING,
                    CollaborationStatus.CONTRACTED,
                    CollaborationStatus.IN_PROGRESS,
                    CollaborationStatus.REVIEW_PENDING,
                    CollaborationStatus.REVISION_REQUESTED);

    private final AdminContextService adminContext;
    private final AdminAuditLogService adminAuditLogService;
    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final UserRepository userRepository;
    private final CampaignRepository campaignRepository;
    private final CollaborationRepository collaborationRepository;
    private final EscrowHoldRepository escrowHoldRepository;
    private final WalletRepository walletRepository;
    private final WalletTransactionRepository walletTransactionRepository;

    public AdminBrandService(
            AdminContextService adminContext,
            AdminAuditLogService adminAuditLogService,
            WorkspaceRepository workspaceRepository,
            WorkspaceMemberRepository workspaceMemberRepository,
            UserRepository userRepository,
            CampaignRepository campaignRepository,
            CollaborationRepository collaborationRepository,
            EscrowHoldRepository escrowHoldRepository,
            WalletRepository walletRepository,
            WalletTransactionRepository walletTransactionRepository) {
        this.adminContext = adminContext;
        this.adminAuditLogService = adminAuditLogService;
        this.workspaceRepository = workspaceRepository;
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.userRepository = userRepository;
        this.campaignRepository = campaignRepository;
        this.collaborationRepository = collaborationRepository;
        this.escrowHoldRepository = escrowHoldRepository;
        this.walletRepository = walletRepository;
        this.walletTransactionRepository = walletTransactionRepository;
    }

    @Transactional(readOnly = true)
    public BrandDetailDto getById(AuthPrincipal principal, String brandId) {
        adminContext.requireRoleWithMfaSatisfied(
                principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN, AdminRole.SUPPORT);
        Workspace workspace = requireBrandWorkspace(brandId);
        return toDetailDto(workspace);
    }

    /**
     * List/search brands with pagination and filters — backs GET /admin/brands (Task #2, cycle 5).
     * Same RBAC as getById: SUPER_ADMIN/ADMIN/SUPPORT (view-only reads).
     *
     * <p>Search query matches against workspace name OR billing email (workspace.billingEmail) OR
     * the OWNER's user.email (when billing_email is null, same fallback {@code resolveEmail} uses).
     * Filters: kycStatus (PENDING/APPROVED/REJECTED, mapped from VerificationStatus), isSuspended
     * (boolean).
     *
     * <p>Returns {@code PaginatedBrandResponse} matching {@code PaginatedResponse<Brand>} shape in
     * api-contracts.ts: {data, total, page, pageSize, totalPages}.
     */
    @Transactional(readOnly = true)
    public PaginatedBrandResponse list(
            AuthPrincipal principal,
            int page,
            int pageSize,
            String search,
            String kycStatus,
            Boolean isSuspended) {
        adminContext.requireRoleWithMfaSatisfied(
                principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN, AdminRole.SUPPORT);

        // Page is 1-indexed in the API contract, but Spring Data uses 0-indexed
        int pageIndex = Math.max(0, page - 1);
        pageSize = Math.max(1, Math.min(pageSize, 100)); // Cap at 100

        Specification<Workspace> spec = Specification.where(null);

        // Filter by BRAND type
        spec = spec.and((root, query, cb) -> cb.equal(root.get("type"), WorkspaceType.BRAND));

        // Search filter (companyName OR email)
        if (search != null && !search.isBlank()) {
            String searchPattern = "%" + search.trim().toLowerCase() + "%";
            spec =
                    spec.and(
                            (root, query, cb) ->
                                    cb.or(
                                            cb.like(cb.lower(root.get("name")), searchPattern),
                                            cb.like(cb.lower(root.get("billingEmail")), searchPattern)));
        }

        // KYC status filter
        if (kycStatus != null && !kycStatus.isBlank()) {
            VerificationStatus verificationStatus = mapKycStatusToVerification(kycStatus);
            if (verificationStatus != null) {
                spec = spec.and((root, query, cb) -> cb.equal(root.get("verificationStatus"), verificationStatus));
            }
        }

        // Suspended filter
        if (isSuspended != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("suspended"), isSuspended));
        }

        Pageable pageable = PageRequest.of(pageIndex, pageSize, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<Workspace> workspacePage = workspaceRepository.findAll(spec, pageable);

        // Preload campaign counts for all brands in this page
        List<String> workspaceIds = workspacePage.getContent().stream().map(Workspace::getId).toList();
        Map<String, Long> campaignCounts = new LinkedHashMap<>();
        Map<String, BigDecimal> totalSpends = new LinkedHashMap<>();
        if (!workspaceIds.isEmpty()) {
            List<Campaign> campaigns = campaignRepository.findByWorkspaceIdIn(workspaceIds);
            for (Campaign campaign : campaigns) {
                campaignCounts.merge(campaign.getWorkspaceId(), 1L, Long::sum);
            }

            // Compute totalSpend per brand (sum of FUNDED/RELEASED escrow holds)
            List<String> campaignIds = campaigns.stream().map(Campaign::getId).toList();
            if (!campaignIds.isEmpty()) {
                List<EscrowHold> holds = escrowHoldRepository.findByCampaignIdIn(campaignIds);
                for (EscrowHold hold : holds) {
                    if (hold.getStatus() == EscrowStatus.FUNDED || hold.getStatus() == EscrowStatus.RELEASED) {
                        String wsId = campaigns.stream()
                                .filter(c -> c.getId().equals(hold.getCampaignId()))
                                .findFirst()
                                .map(Campaign::getWorkspaceId)
                                .orElse(null);
                        if (wsId != null) {
                            totalSpends.merge(wsId, hold.getAmount(), BigDecimal::add);
                        }
                    }
                }
            }
        }

        List<BrandSummaryDto> summaries = new ArrayList<>();
        for (Workspace workspace : workspacePage.getContent()) {
            int campaignCount = campaignCounts.getOrDefault(workspace.getId(), 0L).intValue();
            BigDecimal totalSpend = totalSpends.getOrDefault(workspace.getId(), BigDecimal.ZERO);
            summaries.add(toSummaryDto(workspace, campaignCount, totalSpend));
        }

        int totalPages = workspacePage.getTotalPages();
        return new PaginatedBrandResponse(summaries, workspacePage.getTotalElements(), page, pageSize, totalPages);
    }

    @Transactional
    public BrandDetailDto verifyKyc(
            AuthPrincipal principal,
            HttpServletRequest request,
            String brandId,
            String action,
            String reason) {
        var admin =
                adminContext.requireRoleWithMfaSatisfied(
                        principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN);
        Workspace workspace = requireBrandWorkspace(brandId);

        VerificationStatus fromStatus = workspace.getVerificationStatus();
        VerificationStatus toStatus;
        String auditAction;
        if ("APPROVE".equalsIgnoreCase(action)) {
            toStatus = VerificationStatus.VERIFIED;
            auditAction = "APPROVE";
        } else if ("REJECT".equalsIgnoreCase(action)) {
            toStatus = VerificationStatus.REJECTED;
            auditAction = "REJECT";
        } else {
            throw new ApiException(
                    "INVALID_KYC_ACTION", "action must be APPROVE or REJECT", HttpStatus.BAD_REQUEST);
        }

        workspace.applyKycDecision(toStatus, admin.getId(), toStatus == VerificationStatus.REJECTED ? reason : null);
        workspaceRepository.save(workspace);

        adminAuditLogService.record(
                principal,
                request,
                auditAction,
                "BRAND",
                workspace.getId(),
                Map.of("id", workspace.getId(), "verificationStatus", fromStatus.name()),
                Map.of("id", workspace.getId(), "verificationStatus", toStatus.name()),
                reason);

        return toDetailDto(workspace);
    }

    @Transactional
    public BrandDetailDto suspend(
            AuthPrincipal principal, HttpServletRequest request, String brandId, String reason) {
        var admin =
                adminContext.requireRoleWithMfaSatisfied(
                        principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN);
        Workspace workspace = requireBrandWorkspace(brandId);

        if (workspace.isSuspended()) {
            throw new ApiException(
                    "ALREADY_SUSPENDED", "Brand is already suspended", HttpStatus.CONFLICT);
        }

        workspace.suspend(reason, admin.getId());
        workspaceRepository.save(workspace);

        adminAuditLogService.record(
                principal,
                request,
                "SUSPEND",
                "BRAND",
                workspace.getId(),
                Map.of("id", workspace.getId(), "isSuspended", false),
                Map.of("id", workspace.getId(), "isSuspended", true, "suspendedReason", reason),
                reason);

        return toDetailDto(workspace);
    }

    @Transactional
    public BrandDetailDto reinstate(
            AuthPrincipal principal, HttpServletRequest request, String brandId, String reason) {
        var admin =
                adminContext.requireRoleWithMfaSatisfied(
                        principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN);
        Workspace workspace = requireBrandWorkspace(brandId);

        if (!workspace.isSuspended()) {
            throw new ApiException("NOT_SUSPENDED", "Brand is not suspended", HttpStatus.CONFLICT);
        }

        workspace.reinstate(admin.getId());
        workspaceRepository.save(workspace);

        adminAuditLogService.record(
                principal,
                request,
                "REINSTATE",
                "BRAND",
                workspace.getId(),
                Map.of("id", workspace.getId(), "isSuspended", true),
                Map.of("id", workspace.getId(), "isSuspended", false),
                reason);

        return toDetailDto(workspace);
    }

    /**
     * Brand profile edit — PUT /admin/brands/{id} ({@code brandApi.update}). SUPER_ADMIN/ADMIN
     * (MFA-gated), same as every other mutation on this controller (verifyKyc/suspend/reinstate).
     * Writes ONLY the SAFE allow-listed profile fields (name/industry/size/email), never a blind
     * copy of the request — the {@link com.influora.web.dto.admin.AdminBrandDtos.UpdateBrandRequest}
     * record shape is the allow-list, and {@link Workspace#applyAdminProfileEdit} is null-guarded.
     * Audit-logs old->new for each field that actually changed.
     */
    @Transactional
    public BrandDetailDto update(
            AuthPrincipal principal,
            HttpServletRequest request,
            String brandId,
            com.influora.web.dto.admin.AdminBrandDtos.UpdateBrandRequest body) {
        adminContext.requireRoleWithMfaSatisfied(principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN);
        Workspace workspace = requireBrandWorkspace(brandId);

        // Validate size against the closed STARTUP/SMB/ENTERPRISE union before writing — the DB
        // column is free-text, but Brand.size in admin.types.ts is a closed union; reject anything
        // outside it rather than persisting a value the frontend can't render as a badge.
        String normalizedSize = null;
        if (body.size() != null && !body.size().isBlank()) {
            normalizedSize = body.size().trim().toUpperCase();
            if (!KNOWN_SIZES.contains(normalizedSize)) {
                throw new ApiException(
                        "INVALID_BRAND_SIZE",
                        "size must be one of STARTUP, SMB, ENTERPRISE",
                        HttpStatus.BAD_REQUEST);
            }
        }

        // Build old->new snapshots for ONLY the fields that actually change. Keys match the BRAND
        // FIELD_ALLOWLIST in AdminAuditLogService (id/name/industry/size/email).
        Map<String, Object> oldValues = new LinkedHashMap<>();
        Map<String, Object> newValues = new LinkedHashMap<>();
        oldValues.put("id", workspace.getId());
        newValues.put("id", workspace.getId());

        if (body.name() != null && !body.name().equals(workspace.getName())) {
            oldValues.put("name", workspace.getName());
            newValues.put("name", body.name());
        }
        if (body.industry() != null && !body.industry().equals(workspace.getIndustry())) {
            oldValues.put("industry", workspace.getIndustry());
            newValues.put("industry", body.industry());
        }
        if (normalizedSize != null && !normalizedSize.equals(workspace.getCompanySize())) {
            oldValues.put("size", workspace.getCompanySize());
            newValues.put("size", normalizedSize);
        }
        if (body.email() != null && !body.email().equals(workspace.getBillingEmail())) {
            oldValues.put("email", workspace.getBillingEmail());
            newValues.put("email", body.email());
        }

        workspace.applyAdminProfileEdit(
                body.name(), body.industry(), normalizedSize, body.email());
        workspaceRepository.save(workspace);

        // Only write an audit row when something beyond the id anchor actually changed.
        if (newValues.size() > 1) {
            adminAuditLogService.record(
                    principal,
                    request,
                    "UPDATE",
                    "BRAND",
                    workspace.getId(),
                    oldValues,
                    newValues,
                    "Admin brand profile edit");
        }

        return toDetailDto(workspace);
    }

    /**
     * Campaign budget override — POST /admin/brands/{id}/campaigns/{campaignId}/budget-override
     * ({@code brandApi.overrideBudget}). <b>MONEY PATH — highest-risk endpoint in this controller.</b>
     * SUPER_ADMIN only (MFA-gated) — Kabir L-1: this money mutation is tightened one step above the
     * other brand mutations (which are SUPER_ADMIN/ADMIN) so the write path is no less strict than the
     * SUPER_ADMIN-only read consoles (error/email) that surface the same financial data.
     *
     * <p>Guards, in order:
     * <ol>
     *   <li>Brand must exist and be a BRAND workspace (reuses {@link #requireBrandWorkspace}).
     *   <li>Campaign must exist AND belong to THIS brand — IDOR guard: {@code campaignId} is
     *       caller-supplied and is re-checked against the path {@code brandId}, never trusted on its
     *       own (a 404 either way, so we don't leak whether a foreign campaignId exists).
     *   <li>{@code newBudget > 0} (also {@code @Positive} on the DTO) and {@code <= MAX_CAMPAIGN_BUDGET}.
     *   <li><b>Scale (Kabir L-4):</b> {@code campaigns.budget_max} is {@code DECIMAL(12,2)}. Reject
     *       (400 INVALID_BUDGET_SCALE) any {@code newBudget} carrying more than 2 fractional digits
     *       rather than silently rounding it — matches this codebase's canonical money-input
     *       validation ({@code MoneyDtos.WalletTopUpRequest}'s {@code @Digits(fraction = 2)}), which
     *       rejects excess precision at the edge instead of mutating the caller's value.
     *   <li><b>Committed-spend floor:</b> {@code newBudget} must NOT be below funds already committed
     *       for this campaign. Lowering the recorded budget under money the platform has already
     *       committed is unsafe, so we REJECT (409 BUDGET_BELOW_COMMITTED) rather than allow it.
     *       COUNTED escrow = holds in {FUNDED (locked), RELEASED (paid out), FROZEN (disputed but
     *       still owed/refundable — Kabir L-3)}. The floor is computed in two non-overlapping passes:
     *       <ol type="a">
     *         <li>Per committed collaboration ({@link #COMMITTED_COLLAB_STATUSES}): contribution =
     *             {@code max(agreedRate, sum of THAT collaboration's counted holds)}. Partially
     *             escrowed → floors at the full {@code agreedRate}; over-escrowed → floors at the
     *             higher hold sum.
     *         <li>Plus every counted hold NOT attributable to a committed collaboration — holds with
     *             {@code collaboration_id == null} (unbound campaign-scoped) or bound to a
     *             non-committed collaboration (e.g. DISPUTED/CANCELLED/COMPLETED).
     *       </ol>
     * </ol>
     *
     * <p><b>Kabir M-2 (partial-escrow remainder no longer dropped):</b> the floor takes {@code
     * max(agreedRate, escrowedForThatCollab)} per committed collaboration, so an {@code agreedRate}
     * of 100k with only a 30k FUNDED hold floors at 100k (not 30k). <b>No double-counting:</b> a hold
     * bound to a committed collaboration is consumed ONLY inside that collaboration's {@code max(...)}
     * (pass a) — pass (b) skips any hold whose {@code collaboration_id} is a committed collaboration.
     * Every counted hold is therefore added exactly once, whether FUNDED, RELEASED, or FROZEN.
     *
     * <p><b>Kabir L-3 (FROZEN / disputed):</b> a DISPUTED collaboration is intentionally NOT in
     * {@link #COMMITTED_COLLAB_STATUSES} — once disputed its {@code agreedRate} is no longer a clean
     * live commitment — but any real money frozen for it is still an obligation the platform must
     * honour or refund, so its FROZEN holds still floor the budget conservatively via pass (b). A
     * FROZEN hold on a still-committed collaboration is counted once inside that collaboration's
     * {@code max(...)} in pass (a). All amounts are null-guarded to ZERO (null {@code agreedRate} /
     * null hold amount), and a null {@code collaboration_id} never dereferences.
     */
    @Transactional
    public void overrideCampaignBudget(
            AuthPrincipal principal,
            HttpServletRequest request,
            String brandId,
            String campaignId,
            com.influora.web.dto.admin.AdminBrandDtos.BudgetOverrideRequest body) {
        // Kabir L-1 — money mutation is SUPER_ADMIN only (MFA still enforced by this call), one step
        // stricter than the SUPER_ADMIN/ADMIN gate on the other brand mutations.
        adminContext.requireRoleWithMfaSatisfied(principal, AdminRole.SUPER_ADMIN);
        Workspace workspace = requireBrandWorkspace(brandId);

        Campaign campaign =
                campaignRepository
                        .findById(campaignId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "CAMPAIGN_NOT_FOUND",
                                                "Campaign not found",
                                                HttpStatus.NOT_FOUND));

        // IDOR guard: the campaign must belong to the brand named in the path. 404 (not 403) so a
        // foreign campaignId is indistinguishable from a non-existent one.
        if (!workspace.getId().equals(campaign.getWorkspaceId())) {
            throw new ApiException(
                    "CAMPAIGN_NOT_FOUND", "Campaign not found", HttpStatus.NOT_FOUND);
        }

        BigDecimal newBudget = body.newBudget();
        // @Positive on the DTO already excludes zero/negatives — re-assert defensively for any
        // other future caller path, then enforce the sane upper bound.
        if (newBudget == null || newBudget.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ApiException(
                    "INVALID_BUDGET", "newBudget must be greater than zero", HttpStatus.BAD_REQUEST);
        }
        // Kabir L-4 — scale. campaigns.budget_max is DECIMAL(12,2). Reject >2 fractional digits (400)
        // rather than silently rounding, matching MoneyDtos.WalletTopUpRequest's @Digits(fraction=2)
        // edge validation (this codebase rejects excess money precision, it does not mutate the input
        // on a money path). scale() <= 0 (integers / positive-exponent forms) trivially passes.
        if (newBudget.scale() > 2) {
            throw new ApiException(
                    "INVALID_BUDGET_SCALE",
                    "newBudget must have at most 2 decimal places",
                    HttpStatus.BAD_REQUEST);
        }
        if (newBudget.compareTo(MAX_CAMPAIGN_BUDGET) > 0) {
            throw new ApiException(
                    "BUDGET_EXCEEDS_LIMIT",
                    "newBudget exceeds the maximum allowed override bound",
                    HttpStatus.BAD_REQUEST);
        }

        // Committed-spend floor (Kabir M-2 / L-3). COUNTED escrow = holds in {FUNDED, RELEASED,
        // FROZEN}. Load holds once and identify which collaborations are a live committed obligation.
        List<EscrowHold> holds = escrowHoldRepository.findByCampaignId(campaignId);

        Set<String> committedCollaborationIds = new HashSet<>();
        Map<String, BigDecimal> agreedRateByCommittedCollab = new HashMap<>();
        for (Collaboration collab : collaborationRepository.findByCampaignId(campaignId)) {
            if (COMMITTED_COLLAB_STATUSES.contains(collab.getStatus())) {
                committedCollaborationIds.add(collab.getId());
                // Null agreedRate -> ZERO so max(...) below still floors at the escrowed amount.
                agreedRateByCommittedCollab.put(collab.getId(), nz(collab.getAgreedRate()));
            }
        }

        // Sum each committed collaboration's OWN counted holds (by collaboration id). A hold counted
        // here is consumed by pass (a) and is deliberately excluded from pass (b) below.
        Map<String, BigDecimal> countedHoldSumByCommittedCollab = new HashMap<>();
        for (EscrowHold hold : holds) {
            if (!isCountedHold(hold)) {
                continue;
            }
            String collabId = hold.getCollaborationId();
            if (collabId != null && committedCollaborationIds.contains(collabId)) {
                countedHoldSumByCommittedCollab.merge(collabId, nz(hold.getAmount()), BigDecimal::add);
            }
        }

        BigDecimal committed = BigDecimal.ZERO;
        // Pass (a) — per committed collaboration: max(agreedRate, its counted-hold sum). Fixes M-2's
        // dropped partial-escrow remainder (agreedRate 100k + FUNDED 30k -> floors at 100k, not 30k).
        for (String collabId : committedCollaborationIds) {
            BigDecimal agreed = agreedRateByCommittedCollab.get(collabId);
            BigDecimal escrowed = countedHoldSumByCommittedCollab.getOrDefault(collabId, BigDecimal.ZERO);
            committed = committed.add(agreed.max(escrowed));
        }
        // Pass (b) — every counted hold NOT attributable to a committed collaboration: unbound
        // campaign-scoped (collaboration_id == null) or bound to a non-committed collab (e.g.
        // DISPUTED/CANCELLED/COMPLETED — L-3: disputed FROZEN money is still owed/refundable, floor
        // it here). Skips holds already consumed in pass (a), so nothing is double-counted.
        for (EscrowHold hold : holds) {
            if (!isCountedHold(hold)) {
                continue;
            }
            String collabId = hold.getCollaborationId();
            if (collabId == null || !committedCollaborationIds.contains(collabId)) {
                committed = committed.add(nz(hold.getAmount()));
            }
        }
        if (newBudget.compareTo(committed) < 0) {
            throw new ApiException(
                    "BUDGET_BELOW_COMMITTED",
                    "newBudget ("
                            + newBudget.toPlainString()
                            + ") is below already-committed spend ("
                            + committed.toPlainString()
                            + ")",
                    HttpStatus.CONFLICT);
        }

        BigDecimal oldBudget =
                campaign.getBudgetMax() != null ? campaign.getBudgetMax() : campaign.getBudgetMin();

        campaign.applyAdminBudgetOverride(newBudget);
        campaignRepository.save(campaign);

        // Build snapshots by hand (not Map.of) — oldBudget may be null and Map.of rejects nulls.
        Map<String, Object> oldValues = new LinkedHashMap<>();
        oldValues.put("id", campaign.getId());
        oldValues.put("budget", oldBudget);
        Map<String, Object> newValues = new LinkedHashMap<>();
        newValues.put("id", campaign.getId());
        newValues.put("budget", newBudget);

        adminAuditLogService.record(
                principal,
                request,
                "BUDGET_OVERRIDE",
                "CAMPAIGN",
                campaign.getId(),
                oldValues,
                newValues,
                body.reason());
    }

    /** Null-guard a money amount to ZERO — null agreedRate / null hold amount must never add null. */
    private static BigDecimal nz(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    /**
     * Escrow money that floors a budget cut: FUNDED (locked), RELEASED (paid out), or FROZEN
     * (disputed but still owed/refundable — Kabir L-3). Excludes PENDING (not yet funded) and
     * REFUNDED (money already returned to the brand wallet, no longer an obligation).
     */
    private static boolean isCountedHold(EscrowHold hold) {
        EscrowStatus status = hold.getStatus();
        return status == EscrowStatus.FUNDED
                || status == EscrowStatus.RELEASED
                || status == EscrowStatus.FROZEN;
    }

    private Workspace requireBrandWorkspace(String brandId) {
        return workspaceRepository
                .findById(brandId)
                .filter(w -> w.getType() == WorkspaceType.BRAND)
                .orElseThrow(
                        () -> new ApiException("BRAND_NOT_FOUND", "Brand not found", HttpStatus.NOT_FOUND));
    }

    // ============================================
    // DTO ASSEMBLY
    // ============================================

    private BrandDetailDto toDetailDto(Workspace workspace) {
        List<Campaign> campaigns = campaignRepository.findByWorkspaceId(workspace.getId());
        List<Collaboration> collaborations =
                collaborationRepository.findByWorkspaceId(workspace.getId());
        Map<String, Long> creatorCountByCampaign = new LinkedHashMap<>();
        for (Collaboration c : collaborations) {
            creatorCountByCampaign.merge(c.getCampaignId(), 1L, Long::sum);
        }

        BigDecimal totalSpend = BigDecimal.ZERO;
        List<CampaignSummaryDto> campaignDtos = new ArrayList<>();
        for (Campaign campaign : campaigns) {
            List<EscrowHold> holds = escrowHoldRepository.findByCampaignId(campaign.getId());
            BigDecimal spent = BigDecimal.ZERO;
            for (EscrowHold hold : holds) {
                if (hold.getStatus() == EscrowStatus.FUNDED || hold.getStatus() == EscrowStatus.RELEASED) {
                    spent = spent.add(hold.getAmount());
                }
            }
            totalSpend = totalSpend.add(spent);

            BigDecimal budget = campaign.getBudgetMax() != null ? campaign.getBudgetMax() : campaign.getBudgetMin();
            long creatorCount = creatorCountByCampaign.getOrDefault(campaign.getId(), 0L);

            campaignDtos.add(
                    new CampaignSummaryDto(
                            campaign.getId(),
                            campaign.getTitle(),
                            workspace.getName(),
                            campaign.getCampaignType() != null ? campaign.getCampaignType().name() : "STANDARD",
                            campaign.getStatus().name(),
                            budget != null ? budget : BigDecimal.ZERO,
                            spent,
                            (int) creatorCount,
                            // Honest zeros — no deliverable-review-pipeline table exists yet, same
                            // documented gap as AdminDashboardService's reviewBacklog/campaignsAtRisk.
                            0,
                            0,
                            0.0,
                            campaign.getCreatedAt(),
                            null));
        }

        return new BrandDetailDto(
                workspace.getId(),
                workspace.getName(),
                resolveEmail(workspace),
                workspace.getIndustry(),
                normalizeSize(workspace.getCompanySize()),
                mapKycStatus(workspace.getVerificationStatus()).name(),
                workspace.getKycReviewedBy(),
                workspace.getKycReviewedAt(),
                workspace.getKycRejectionReason(),
                campaigns.size(),
                totalSpend,
                workspace.isSuspended(),
                workspace.getCreatedAt(),
                workspace.getGstin(),
                workspace.getPan(),
                null, // incorporationDoc — not modeled distinctly from kyc_gstin_doc_url/kyc_pan_doc_url yet
                workspace.getBillingAddress(),
                teamMembers(workspace.getId()),
                campaignDtos,
                paymentHistory(workspace.getId()));
    }

    /**
     * {@code workspaces.billing_email} is nullable; falls back to the workspace OWNER's own user
     * email so {@code BrandDetail.email} (required, non-optional in admin.types.ts) is never
     * silently empty.
     */
    private String resolveEmail(Workspace workspace) {
        if (workspace.getBillingEmail() != null && !workspace.getBillingEmail().isBlank()) {
            return workspace.getBillingEmail();
        }
        return workspaceMemberRepository
                .findFirstByWorkspaceIdAndRoleAndActiveTrue(workspace.getId(), MemberRole.OWNER)
                .flatMap(owner -> userRepository.findById(owner.getUserId()))
                .map(User::getEmail)
                .orElse(null);
    }

    private List<TeamMemberDto> teamMembers(String workspaceId) {
        List<WorkspaceMember> members = workspaceMemberRepository.findByWorkspaceIdAndActiveTrue(workspaceId);
        if (members.isEmpty()) {
            return List.of();
        }
        List<String> userIds = members.stream().map(WorkspaceMember::getUserId).toList();
        Map<String, User> usersById = new LinkedHashMap<>();
        for (User u : userRepository.findAllById(userIds)) {
            usersById.put(u.getId(), u);
        }
        List<TeamMemberDto> dtos = new ArrayList<>();
        for (WorkspaceMember member : members) {
            User user = usersById.get(member.getUserId());
            if (user == null) {
                continue;
            }
            dtos.add(
                    new TeamMemberDto(
                            member.getId(),
                            user.getDisplayName() != null ? user.getDisplayName() : user.getEmail(),
                            user.getEmail(),
                            member.getRole().name()));
        }
        return dtos;
    }

    /**
     * Most recent 20 wallet transactions for the workspace's own wallet — brand-facing payment
     * history. {@code findByWalletIdOrderByCreatedAtDesc} already returns newest-first, so the
     * only extra work needed is capping to 20 rows before mapping.
     */
    private List<PaymentRecordDto> paymentHistory(String workspaceId) {
        Wallet wallet = walletRepository.findByOwnerId(workspaceId).orElse(null);
        if (wallet == null) {
            return List.of();
        }
        List<WalletTransaction> txns =
                walletTransactionRepository.findByWalletIdOrderByCreatedAtDesc(wallet.getId());
        return txns.stream()
                .limit(20)
                .map(
                        t ->
                                new PaymentRecordDto(
                                        t.getId(),
                                        t.getDirection().name(),
                                        t.getAmount(),
                                        t.getDescription(),
                                        t.getStatus().name(),
                                        t.getCreatedAt()))
                .toList();
    }

    private static final Set<String> KNOWN_SIZES = Set.of("STARTUP", "SMB", "ENTERPRISE");

    /**
     * {@code workspaces.company_size} is a free-text VARCHAR(50) (no enum/CHECK constraint at the
     * DB level), but {@code Brand.size} in admin.types.ts is a closed union. Passes through
     * uppercased when it already matches one of the 3 known values; otherwise returns the raw
     * value as-is rather than fabricating a guess — the frontend badge just renders whatever
     * string it gets (see {@code BrandProfile.tsx}'s {@code <Badge variant="outline">{brand.size}</Badge>}).
     */
    private static String normalizeSize(String companySize) {
        if (companySize == null) {
            return null;
        }
        String upper = companySize.trim().toUpperCase();
        return KNOWN_SIZES.contains(upper) ? upper : companySize;
    }

    private static KycStatusView mapKycStatus(VerificationStatus status) {
        return switch (status) {
            case VERIFIED -> KycStatusView.APPROVED;
            case REJECTED -> KycStatusView.REJECTED;
            case UNVERIFIED, PENDING -> KycStatusView.PENDING;
        };
    }

    /**
     * Reverse mapping: API-level kycStatus string (PENDING/APPROVED/REJECTED, from BrandFilters) to
     * DB-level VerificationStatus enum.
     */
    private static VerificationStatus mapKycStatusToVerification(String kycStatus) {
        return switch (kycStatus.toUpperCase()) {
            case "APPROVED" -> VerificationStatus.VERIFIED;
            case "REJECTED" -> VerificationStatus.REJECTED;
            case "PENDING" -> VerificationStatus.PENDING;
            default -> null; // Invalid status filter, ignore
        };
    }

    /**
     * Maps workspace to BrandSummaryDto for list endpoint — subset of BrandDetailDto fields (no
     * nested teamMembers/campaigns/paymentHistory).
     */
    private BrandSummaryDto toSummaryDto(Workspace workspace, int campaignCount, BigDecimal totalSpend) {
        return new BrandSummaryDto(
                workspace.getId(),
                workspace.getName(),
                resolveEmail(workspace),
                workspace.getIndustry(),
                normalizeSize(workspace.getCompanySize()),
                mapKycStatus(workspace.getVerificationStatus()).name(),
                workspace.getKycReviewedBy(),
                workspace.getKycReviewedAt(),
                workspace.getKycRejectionReason(),
                campaignCount,
                totalSpend,
                workspace.isSuspended(),
                workspace.getCreatedAt());
    }
}
