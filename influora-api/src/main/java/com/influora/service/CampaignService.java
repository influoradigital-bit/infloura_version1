package com.influora.service;

import com.influora.common.ApiException;
import com.influora.common.JsonLists;
import com.influora.common.PageMeta;
import com.influora.common.Ulids;
import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.Workspace;
import com.influora.domain.enums.CampaignIntentType;
import com.influora.domain.enums.CampaignStatus;
import com.influora.domain.enums.CollaborationStatus;
import com.influora.domain.enums.EscrowStatus;
import com.influora.domain.enums.MemberRole;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CampaignSpecs;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.CollaborationRepository.CampaignStatusCount;
import com.influora.repository.EscrowHoldRepository;
import com.influora.repository.EscrowHoldRepository.CampaignSpend;
import com.influora.security.AuthPrincipal;
import com.influora.service.CampaignMapper.CampaignMetrics;
import com.influora.web.dto.campaign.CampaignDtos.BudgetDto;
import com.influora.web.dto.campaign.CampaignDtos.CampaignPatchRequest;
import com.influora.web.dto.campaign.CampaignDtos.CampaignResponse;
import com.influora.web.dto.campaign.CampaignDtos.CampaignWriteRequest;
import com.influora.web.dto.campaign.CampaignDtos.DeleteResponse;
import com.influora.web.dto.campaign.CampaignDtos.DuplicateResponse;
import com.influora.web.dto.campaign.CampaignDtos.HypeConfigDto;
import com.influora.web.dto.campaign.CampaignDtos.TimelineDto;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CampaignService {

    /**
     * D-5 (BrandF.md Section 19/20) — statuses that count as "the creator is actively engaged past
     * negotiation." {@code CONTRACT_PENDING} is the cut line, reusing the exact same ruling
     * {@link Collaboration#canReject()}'s javadoc documents (CR-22a, Priya): it is the first status
     * with a durable artifact (a {@code Contract} row), so everything from here through {@code
     * REVISION_REQUESTED} represents real, contracted-or-working engagement. Pre-contract
     * negotiation states (INVITED/APPLIED/SHORTLISTED/IN_NEGOTIATION/TERMS_AGREED) are not yet a
     * committed collaboration; {@code COMPLETED}/{@code CANCELLED}/{@code DISPUTED} are terminal and
     * excluded (COMPLETED is counted separately, the other two are dead ends).
     */
    private static final Set<CollaborationStatus> ACTIVE_COLLABORATION_STATUSES =
            Set.of(
                    CollaborationStatus.CONTRACT_PENDING,
                    CollaborationStatus.CONTRACTED,
                    CollaborationStatus.IN_PROGRESS,
                    CollaborationStatus.REVIEW_PENDING,
                    CollaborationStatus.REVISION_REQUESTED);

    private final CampaignRepository campaignRepository;
    private final CollaborationRepository collaborationRepository;
    private final EscrowHoldRepository escrowHoldRepository;
    private final BrandContextService brandContext;
    private final CampaignValidator validator;
    private final IntegrationHealthService integrationHealthService;
    private final BrandCampaignFeeService brandCampaignFeeService;

    public CampaignService(
            CampaignRepository campaignRepository,
            CollaborationRepository collaborationRepository,
            EscrowHoldRepository escrowHoldRepository,
            BrandContextService brandContext,
            CampaignValidator validator,
            IntegrationHealthService integrationHealthService,
            BrandCampaignFeeService brandCampaignFeeService) {
        this.campaignRepository = campaignRepository;
        this.collaborationRepository = collaborationRepository;
        this.escrowHoldRepository = escrowHoldRepository;
        this.brandContext = brandContext;
        this.validator = validator;
        this.integrationHealthService = integrationHealthService;
        this.brandCampaignFeeService = brandCampaignFeeService;
    }

    public record PagedCampaigns(List<CampaignResponse> items, PageMeta meta) {}

    public PagedCampaigns list(
            AuthPrincipal principal,
            int page,
            int limit,
            String statusParam,
            String search,
            String sortBy,
            String sortOrder) {
        Workspace workspace = brandContext.requireBrandWorkspace(principal);
        brandContext.requireMember(principal, workspace.getId());

        int safePage = Math.max(page, 1);
        int safeLimit = Math.min(Math.max(limit, 1), 100);
        List<CampaignStatus> statuses = parseStatuses(statusParam);

        Sort sort = buildSort(sortBy, sortOrder);
        Specification<Campaign> spec =
                CampaignSpecs.forWorkspace(workspace.getId(), statuses, search);

        Page<Campaign> result =
                campaignRepository.findAll(spec, PageRequest.of(safePage - 1, safeLimit, sort));

        List<String> pageCampaignIds = result.getContent().stream().map(Campaign::getId).toList();
        Map<String, CampaignMetrics> metricsByCampaignId = batchComputeMetrics(pageCampaignIds);

        List<CampaignResponse> items =
                result.getContent().stream()
                        .map(
                                c ->
                                        CampaignMapper.toResponse(
                                                c,
                                                metricsByCampaignId.getOrDefault(
                                                        c.getId(), CampaignMetrics.empty())))
                        .toList();

        PageMeta meta =
                new PageMeta(
                        safePage,
                        safeLimit,
                        result.getTotalElements(),
                        result.hasNext());

        return new PagedCampaigns(items, meta);
    }

    public CampaignResponse get(AuthPrincipal principal, String campaignId) {
        Campaign campaign = requireCampaign(principal, campaignId);
        return CampaignMapper.toResponse(campaign, computeMetrics(campaign.getId()));
    }

    @Transactional
    public CampaignResponse create(AuthPrincipal principal, CampaignWriteRequest req) {
        Workspace workspace = brandContext.requireBrandWorkspace(principal);
        brandContext.requireMember(principal, workspace.getId());

        validator.validateBudget(req.budget());
        validator.validateTimeline(req.timeline(), req.applicationDeadline());

        CampaignStatus status = req.status() != null ? req.status() : CampaignStatus.DRAFT;
        validator.validateStatusForWorkspace(status, workspace);

        // Wave D task D3 default: null/unspecified campaignType is treated as STANDARD from here on
        // (matches CreateCampaignExecutor#parseCampaignType, the AI-drafted path's existing
        // default, and AdminBrandService's existing "STANDARD" display fallback for a null type).
        // requiresStoreIntegration only gates DIRECT, so this default changes no gating behavior.
        CampaignIntentType campaignType =
                req.campaignType() != null ? req.campaignType() : CampaignIntentType.STANDARD;
        if (IntegrationHealthService.requiresStoreIntegration(campaignType)
                && !integrationHealthService.hasActiveStoreIntegration(workspace.getId())) {
            throw new ApiException(
                    "NO_STORE_INTEGRATION",
                    "Connect a store (Shopify) before creating a sale/conversion campaign — order"
                            + " attribution has nothing to attribute to otherwise",
                    HttpStatus.CONFLICT);
        }

        validator.validateHypeConfig(campaignType, req.hype());
        String hypeConfigJson =
                campaignType == CampaignIntentType.HYPE
                        ? JsonLists.toJsonObject(normalizeHype(req.hype()))
                        : null;
        // [SEC: Kabir follow-up] For HYPE the budget is DERIVED, never client-supplied — see
        // hypeDerivedBudget. validateHypeConfig has already guaranteed perReelRate/slotCap are
        // present and positive for a HYPE create, so this always resolves to rate x slots.
        BudgetDto effectiveBudget =
                campaignType == CampaignIntentType.HYPE
                        ? hypeDerivedBudget(req.hype(), req.budget())
                        : req.budget();

        Campaign campaign =
                Campaign.builder()
                        .id(Ulids.newUlid())
                        .workspaceId(workspace.getId())
                        .title(req.title().trim())
                        .description(req.description())
                        .status(status)
                        .campaignType(campaignType)
                        .hypeConfigJson(hypeConfigJson)
                        .budgetMin(effectiveBudget.min())
                        .budgetMax(effectiveBudget.max())
                        .currency(effectiveBudget.currency())
                        .startDate(req.timeline().startDate())
                        .endDate(req.timeline().endDate())
                        .applicationDeadline(req.applicationDeadline())
                        .platformsJson(JsonLists.toJson(req.platforms()))
                        .contentTypesJson(JsonLists.toJson(req.contentTypes()))
                        .objectivesJson(JsonLists.toJson(req.objectives()))
                        .requirementsJson(JsonLists.toJson(req.requirements()))
                        .hashtagsJson(JsonLists.toJson(req.hashtags()))
                        .targetAudienceJson(JsonLists.toJsonObject(req.targetAudience()))
                        .brandGuidelines(req.brandGuidelines())
                        .isPrivate(req.isPrivate() != null && req.isPrivate())
                        .maxCollaborators(req.maxCollaborators())
                        .createdBy(principal.getUserId())
                        .build();

        campaignRepository.save(campaign);
        // A campaign is brand new at this point (id just minted above) so this will always resolve
        // to CampaignMetrics.empty() in practice — computed anyway rather than special-cased so
        // there is exactly one metrics code path for the whole service (D-5).
        return CampaignMapper.toResponse(campaign, computeMetrics(campaign.getId()));
    }

    @Transactional
    public CampaignResponse update(AuthPrincipal principal, String campaignId, CampaignPatchRequest req) {
        Workspace workspace = brandContext.requireBrandWorkspace(principal);
        var member = brandContext.requireMember(principal, workspace.getId());
        brandContext.requireRole(member, MemberRole.OWNER, MemberRole.ADMIN, MemberRole.MANAGER);

        // [SEC: Kabir fix 2a] Locked load — the whole status-transition-plus-fee-charge sequence
        // below must be serialized by a real row lock, not merely by the fee ledger's idempotency
        // key (which stops protecting concurrent activation once brandFeeBps resolves to 0).
        Campaign campaign = loadOwnedForUpdate(campaignId, workspace.getId());
        validator.ensureEditable(campaign.getStatus(), isStatusOnlyPatch(req));

        if (req.budget() != null) {
            validator.validateBudget(req.budget());
        }
        TimelineDto mergedTimeline = mergedTimeline(campaign, req);
        if (req.timeline() != null || req.applicationDeadline() != null) {
            validator.validateTimeline(
                    mergedTimeline,
                    req.applicationDeadline() != null
                            ? req.applicationDeadline()
                            : campaign.getApplicationDeadline());
        }

        CampaignStatus newStatus = req.status() != null ? req.status() : campaign.getStatus();
        if (campaign.getStatus() == CampaignStatus.PAUSED && newStatus == CampaignStatus.ACTIVE) {
            validator.validateResumeActive(newStatus, workspace);
        } else if (newStatus == CampaignStatus.ACTIVE) {
            validator.validateStatusForWorkspace(newStatus, workspace);
        }

        // [B1 — LOCKED ruling, wiki/tech/BRAND_EXECUTION_PLAN.md] Snapshot the pre-patch status
        // BEFORE applyPatch mutates it below — the brand publish fee is charged exactly once, at
        // the real DRAFT/PENDING_APPROVAL/PAUSED -> ACTIVE transition, never on a no-op PATCH that
        // merely re-sends status=ACTIVE for an already-ACTIVE campaign.
        boolean transitioningToActive =
                campaign.getStatus() != CampaignStatus.ACTIVE && newStatus == CampaignStatus.ACTIVE;

        // campaignType is immutable post-creation (not part of CampaignPatchRequest) — only a
        // campaign already created as HYPE can have its hype config revised here; a PATCH that
        // sends `hype` for any other (or untyped/legacy-null) campaign is silently ignored, same as
        // STANDARD ignores it on create.
        //
        // [G3 fix] hype is a *nested named-field record*, not a top-level scalar or a homogeneous
        // list — a caller that wants to bump one sub-field (e.g. slotsFilled after a deliverable
        // is approved, or slotCap alone) must not be forced to resend the other 8 just to avoid
        // silently wiping them. Merge field-by-field against the currently-stored config using the
        // exact same null-check-per-field convention Campaign.applyPatch already uses for every
        // top-level scalar (title/description/budget/...): null in the incoming DTO means "leave
        // this sub-field alone," non-null means "overwrite it." This is deliberately NOT a
        // full-replace like platforms/hashtags/targetAudience — those are homogeneous lists where
        // "PATCH one element" isn't a coherent operation and the FE always resends the whole list;
        // hype instead mirrors Campaign's own record-of-named-fields shape, so it gets Campaign's
        // own merge convention one level down.
        //
        // Validate the MERGED result (not the raw patch) so: (a) a partial patch can never leave
        // the 5 required fields (sourceReelUrl/hashtag/formatLanes/perReelRate/slotCap) missing —
        // they're always backed by the existing stored value unless explicitly overwritten; and
        // (b) a patch that explicitly blanks/invalidates a field (empty sourceReelUrl, perReelRate
        // <= 0, etc.) is still rejected with 400, whether that field was required or merely
        // optional (audioTrack/currency/slotsFilled/liveUntil) — malformed input on those optional
        // fields no longer sails through silently either.
        String hypeConfigJson = null;
        BudgetDto effectiveBudget = req.budget();
        if (campaign.getCampaignType() == CampaignIntentType.HYPE) {
            HypeConfigDto existingHype =
                    JsonLists.objectFromJson(campaign.getHypeConfigJson(), HypeConfigDto.class);
            HypeConfigDto mergedHype =
                    req.hype() != null ? mergeHype(existingHype, req.hype()) : existingHype;

            // [SEC: Kabir follow-up] Validate whenever the patch touches hype at all — AND
            // unconditionally at the ->ACTIVE edge, even for a patch that sends no hype block.
            // Publishing is the moment budgetMax becomes real money (brand publish fee base +
            // EscrowService.deriveFundAmount), so a HYPE campaign must not go live on a partial
            // Meera draft whose perReelRate/slotCap are still null and whose budget therefore
            // could not be derived below.
            if (req.hype() != null || transitioningToActive) {
                validator.validateHypeConfig(campaign.getCampaignType(), mergedHype);
            }
            if (req.hype() != null) {
                hypeConfigJson = JsonLists.toJsonObject(normalizeHype(mergedHype));
            }

            // Derived from the MERGED config regardless of whether this patch carried a hype
            // block, so a budget-only PATCH cannot decouple budgetMax from rate x slots either.
            effectiveBudget = hypeDerivedBudget(mergedHype, req.budget());
        }

        campaign.applyPatch(
                req.title(),
                req.description(),
                req.status(),
                effectiveBudget != null ? effectiveBudget.min() : null,
                effectiveBudget != null ? effectiveBudget.max() : null,
                effectiveBudget != null ? effectiveBudget.currency() : null,
                req.timeline() != null ? req.timeline().startDate() : null,
                req.timeline() != null ? req.timeline().endDate() : null,
                req.applicationDeadline(),
                req.platforms() != null ? JsonLists.toJson(req.platforms()) : null,
                req.contentTypes() != null ? JsonLists.toJson(req.contentTypes()) : null,
                req.objectives() != null ? JsonLists.toJson(req.objectives()) : null,
                req.requirements() != null ? JsonLists.toJson(req.requirements()) : null,
                req.hashtags() != null ? JsonLists.toJson(req.hashtags()) : null,
                req.targetAudience() != null ? JsonLists.toJsonObject(req.targetAudience()) : null,
                req.brandGuidelines(),
                req.isPrivate(),
                req.maxCollaborators(),
                hypeConfigJson);

        // [B1] Same @Transactional method as the status flip above — if this throws (insufficient
        // wallet balance, missing fee config), the whole PATCH rolls back and the campaign never
        // ends up ACTIVE without having paid the fee. No follow-up job, no refund path needed.
        if (transitioningToActive) {
            brandCampaignFeeService.chargeOnPublish(campaign, workspace.getId());
        }

        campaignRepository.save(campaign);
        return CampaignMapper.toResponse(campaign, computeMetrics(campaign.getId()));
    }

    @Transactional
    public DeleteResponse delete(AuthPrincipal principal, String campaignId) {
        Workspace workspace = brandContext.requireBrandWorkspace(principal);
        var member = brandContext.requireMember(principal, workspace.getId());
        // Align with every other destructive brand action (escrow release, payout, member removal,
        // wallet top-up are all OWNER+ADMIN). Campaign delete was an OWNER-only outlier — stricter
        // than moving real money — with no justifying test/comment. Deleting a DRAFT is reversible
        // in practice (re-createable) and less impactful than a payout, so OWNER+ADMIN is correct.
        brandContext.requireRole(member, MemberRole.OWNER, MemberRole.ADMIN);

        Campaign campaign = loadOwned(campaignId, workspace.getId());
        validator.ensureDeletable(campaign.getStatus());
        campaignRepository.delete(campaign);
        return new DeleteResponse(true);
    }

    @Transactional
    public DuplicateResponse duplicate(AuthPrincipal principal, String campaignId) {
        Workspace workspace = brandContext.requireBrandWorkspace(principal);
        var member = brandContext.requireMember(principal, workspace.getId());
        brandContext.requireRole(member, MemberRole.OWNER, MemberRole.ADMIN, MemberRole.MANAGER);

        Campaign source = loadOwned(campaignId, workspace.getId());
        String title = source.getTitle();
        if (!title.endsWith(" (Copy)")) {
            title = title + " (Copy)";
        }
        Campaign copy = source.duplicateCopy(Ulids.newUlid(), title, principal.getUserId());
        campaignRepository.save(copy);
        return new DuplicateResponse(copy.getId());
    }

    private Campaign requireCampaign(AuthPrincipal principal, String campaignId) {
        Workspace workspace = brandContext.requireBrandWorkspace(principal);
        brandContext.requireMember(principal, workspace.getId());
        return loadOwned(campaignId, workspace.getId());
    }

    private Campaign loadOwned(String campaignId, String workspaceId) {
        Campaign campaign =
                campaignRepository
                        .findById(campaignId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "CAMPAIGN_NOT_FOUND",
                                                "Campaign not found",
                                                HttpStatus.NOT_FOUND));
        if (!campaign.getWorkspaceId().equals(workspaceId)) {
            throw new ApiException("CAMPAIGN_NOT_FOUND", "Campaign not found", HttpStatus.NOT_FOUND);
        }
        return campaign;
    }

    /**
     * Same tenant-scoped lookup as {@link #loadOwned}, but under a pessimistic row lock
     * ({@code CampaignRepository.findByIdForUpdate}) — used only by {@link #update} because that
     * is the one path that can flip a campaign to {@code ACTIVE} and trigger the brand publish fee
     * + side effects. {@code get}/{@code delete}/{@code duplicate} stay on the unlocked
     * {@link #loadOwned} since they never mutate campaign status.
     */
    private Campaign loadOwnedForUpdate(String campaignId, String workspaceId) {
        Campaign campaign =
                campaignRepository
                        .findByIdForUpdate(campaignId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "CAMPAIGN_NOT_FOUND",
                                                "Campaign not found",
                                                HttpStatus.NOT_FOUND));
        if (!campaign.getWorkspaceId().equals(workspaceId)) {
            throw new ApiException("CAMPAIGN_NOT_FOUND", "Campaign not found", HttpStatus.NOT_FOUND);
        }
        return campaign;
    }

    /**
     * Fills the {@code hype} block's lenient/optional sub-fields with the same defaults the
     * frontend form applies (currency "INR", slotsFilled 0) before it is persisted, so a HYPE
     * campaign's stored config never has to null-check them on read.
     */
    private static HypeConfigDto normalizeHype(HypeConfigDto hype) {
        if (hype == null) {
            return null;
        }
        return new HypeConfigDto(
                hype.sourceReelUrl(),
                hype.audioTrack(),
                hype.hashtag(),
                hype.formatLanes(),
                hype.perReelRate(),
                hype.currency() != null && !hype.currency().isBlank() ? hype.currency() : "INR",
                hype.slotCap(),
                hype.slotsFilled() != null ? hype.slotsFilled() : 0,
                hype.liveUntil());
    }

    /**
     * [SEC: Kabir follow-up, meera-completion-flow review] For a HYPE campaign the budget is a
     * DERIVED quantity, not an independent input: the campaign advertises {@code slotCap} slots at
     * a flat {@code perReelRate} each, so the only coherent pool amount is rate x slots.
     *
     * <p>Previously {@code budget.min}/{@code budget.max} were stored from the client verbatim and
     * only checked for {@code min > 0 && max >= min}, while {@code validateHypeConfig} only checked
     * {@code perReelRate > 0 && slotCap > 0} — neither side ever cross-checked the other. Since
     * {@code BrandCampaignFeeService.chargeOnPublish} and {@code EscrowService.deriveFundAmount}
     * both use {@code budgetMax} as their base, a hand-crafted request (Meera cannot reach these
     * fields — it never sets rate/slots/budget) could advertise 100 slots x Rs.3500 while funding
     * escrow and paying the publish fee on Rs.1000.
     *
     * <p>Re-deriving server-side rather than merely asserting equality makes the mismatch
     * unrepresentable instead of merely rejected, and is a no-op for the real form, which already
     * submits {@code min == max == rate x slots} (brand-new-hype-campaign.tsx).
     *
     * <p>Returns {@code clientBudget} untouched when the money slots are still null — that is the
     * partial HYPE draft Meera persists ({@code CreateCampaignExecutor}), which has nothing to
     * derive from yet and is separately blocked from reaching {@code ACTIVE} in {@link #update}.
     */
    private static BudgetDto hypeDerivedBudget(HypeConfigDto hype, BudgetDto clientBudget) {
        if (hype == null || hype.perReelRate() == null || hype.slotCap() == null) {
            return clientBudget;
        }
        BigDecimal total = hype.perReelRate().multiply(BigDecimal.valueOf(hype.slotCap()));
        String currency =
                clientBudget != null && clientBudget.currency() != null
                                && !clientBudget.currency().isBlank()
                        ? clientBudget.currency()
                        : hype.currency() != null && !hype.currency().isBlank() ? hype.currency() : "INR";
        return new BudgetDto(total, total, currency);
    }

    /**
     * Field-by-field PATCH merge for the hype config sub-object — see the [G3 fix] comment in
     * {@link #update}. {@code existing} is only ever null defensively: a HYPE campaign should
     * always have a stored config after {@link #create} (validateHypeConfig blocks a HYPE create
     * without one), but if it somehow doesn't, falling back to the raw patch keeps this a clean
     * {@code validateHypeConfig}-driven 400 for missing required fields instead of an NPE.
     */
    private static HypeConfigDto mergeHype(HypeConfigDto existing, HypeConfigDto patch) {
        if (existing == null) {
            return patch;
        }
        return new HypeConfigDto(
                patch.sourceReelUrl() != null ? patch.sourceReelUrl() : existing.sourceReelUrl(),
                patch.audioTrack() != null ? patch.audioTrack() : existing.audioTrack(),
                patch.hashtag() != null ? patch.hashtag() : existing.hashtag(),
                patch.formatLanes() != null ? patch.formatLanes() : existing.formatLanes(),
                patch.perReelRate() != null ? patch.perReelRate() : existing.perReelRate(),
                patch.currency() != null ? patch.currency() : existing.currency(),
                patch.slotCap() != null ? patch.slotCap() : existing.slotCap(),
                patch.slotsFilled() != null ? patch.slotsFilled() : existing.slotsFilled(),
                patch.liveUntil() != null ? patch.liveUntil() : existing.liveUntil());
    }

    /**
     * D-5 (BrandF.md Section 19/20) — real {@link CampaignMetrics} for ONE campaign, used by
     * {@code get}/{@code create}/{@code update}. A single {@code findByCampaignId} plus a single
     * scoped SUM query is fine here (unlike {@link #list}) because this path only ever handles one
     * campaign per call, never a page of up to 100.
     */
    private CampaignMetrics computeMetrics(String campaignId) {
        List<Collaboration> collaborations = collaborationRepository.findByCampaignId(campaignId);
        int active = 0;
        int completed = 0;
        for (Collaboration c : collaborations) {
            if (c.getStatus() == CollaborationStatus.COMPLETED) {
                completed++;
            } else if (ACTIVE_COLLABORATION_STATUSES.contains(c.getStatus())) {
                active++;
            }
        }
        BigDecimal totalSpend =
                escrowHoldRepository.sumAmountByCampaignIdAndStatus(campaignId, EscrowStatus.RELEASED);
        if (totalSpend == null) {
            totalSpend = BigDecimal.ZERO;
        }
        return new CampaignMetrics(collaborations.size(), active, completed, totalSpend);
    }

    /**
     * D-5 (BrandF.md Section 19/20) — batched {@link CampaignMetrics} for a whole page of
     * campaigns (up to 100 rows, {@link #list}'s page-size cap). Exactly two queries total — one
     * GROUP BY over {@code collaborations} for the counts, one GROUP BY over {@code escrow_holds}
     * for RELEASED spend — never one query per campaign row (the N+1 this fix exists to avoid).
     * A campaignId absent from either aggregate legitimately has zero of that figure (no
     * collaborations, or no RELEASED escrow yet) and is defaulted to {@link CampaignMetrics#empty}
     * by the caller via {@code Map.getOrDefault}.
     */
    private Map<String, CampaignMetrics> batchComputeMetrics(List<String> campaignIds) {
        if (campaignIds.isEmpty()) {
            return Map.of();
        }

        record Counts(int total, int active, int completed) {
            Counts {}
        }
        Map<String, Counts> countsByCampaignId = new HashMap<>();
        for (CampaignStatusCount row :
                collaborationRepository.countByCampaignIdInGroupByStatus(campaignIds)) {
            Counts prev = countsByCampaignId.getOrDefault(row.getCampaignId(), new Counts(0, 0, 0));
            int total = prev.total() + (int) row.getTotal();
            int active =
                    prev.active()
                            + (ACTIVE_COLLABORATION_STATUSES.contains(row.getStatus())
                                    ? (int) row.getTotal()
                                    : 0);
            int completed =
                    prev.completed()
                            + (row.getStatus() == CollaborationStatus.COMPLETED ? (int) row.getTotal() : 0);
            countsByCampaignId.put(row.getCampaignId(), new Counts(total, active, completed));
        }

        Map<String, BigDecimal> spendByCampaignId = new HashMap<>();
        for (CampaignSpend row :
                escrowHoldRepository.sumAmountByCampaignIdInAndStatusGroupByCampaignId(
                        campaignIds, EscrowStatus.RELEASED)) {
            spendByCampaignId.put(
                    row.getCampaignId(), row.getTotal() != null ? row.getTotal() : BigDecimal.ZERO);
        }

        Map<String, CampaignMetrics> result = new HashMap<>();
        for (String campaignId : campaignIds) {
            Counts c = countsByCampaignId.getOrDefault(campaignId, new Counts(0, 0, 0));
            BigDecimal spend = spendByCampaignId.getOrDefault(campaignId, BigDecimal.ZERO);
            result.put(campaignId, new CampaignMetrics(c.total(), c.active(), c.completed(), spend));
        }
        return result;
    }

    private static List<CampaignStatus> parseStatuses(String statusParam) {
        if (statusParam == null || statusParam.isBlank() || "ALL".equalsIgnoreCase(statusParam)) {
            return List.of();
        }
        List<CampaignStatus> out = new ArrayList<>();
        for (String part : statusParam.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) continue;
            try {
                out.add(CampaignStatus.valueOf(trimmed.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                // skip invalid
            }
        }
        return out;
    }

    private static Sort buildSort(String sortBy, String sortOrder) {
        String field =
                switch (sortBy != null ? sortBy : "createdAt") {
                    case "updatedAt" -> "updatedAt";
                    case "title" -> "title";
                    default -> "createdAt";
                };
        Sort.Direction dir =
                "asc".equalsIgnoreCase(sortOrder) ? Sort.Direction.ASC : Sort.Direction.DESC;
        return Sort.by(dir, field);
    }

    /**
     * True when the patch carries a {@code status} change (or nothing at all) and no other field —
     * the shape {@code campaignsApi.update(id, { status })} always sends for pause/resume/cancel/
     * complete (src/lib/api.ts, called from brand-campaign-detail.tsx and campaigns-list.tsx). Used
     * by {@link CampaignValidator#ensureEditable(CampaignStatus, boolean)} to let an ACTIVE
     * campaign's status still be changed while blocking any patch that also edits real content.
     */
    private static boolean isStatusOnlyPatch(CampaignPatchRequest req) {
        return req.title() == null
                && req.description() == null
                && req.objectives() == null
                && req.hype() == null
                && req.budget() == null
                && req.timeline() == null
                && req.applicationDeadline() == null
                && req.platforms() == null
                && req.contentTypes() == null
                && req.requirements() == null
                && req.hashtags() == null
                && req.brandGuidelines() == null
                && req.isPrivate() == null
                && req.maxCollaborators() == null
                && req.targetAudience() == null;
    }

    private static TimelineDto mergedTimeline(Campaign campaign, CampaignPatchRequest req) {
        if (req.timeline() != null) {
            return req.timeline();
        }
        if (campaign.getStartDate() != null && campaign.getEndDate() != null) {
            return new TimelineDto(campaign.getStartDate(), campaign.getEndDate());
        }
        return null;
    }
}
