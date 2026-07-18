package com.influora.service;

import com.influora.common.ApiException;
import com.influora.common.JsonLists;
import com.influora.common.PageMeta;
import com.influora.common.Ulids;
import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.Workspace;
import com.influora.domain.enums.CampaignIntentType;
import com.influora.domain.enums.CampaignStatus;
import com.influora.domain.enums.MemberRole;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CampaignSpecs;
import com.influora.security.AuthPrincipal;
import com.influora.service.CampaignMapper.CampaignMetrics;
import com.influora.web.dto.campaign.CampaignDtos.CampaignPatchRequest;
import com.influora.web.dto.campaign.CampaignDtos.CampaignResponse;
import com.influora.web.dto.campaign.CampaignDtos.CampaignWriteRequest;
import com.influora.web.dto.campaign.CampaignDtos.DeleteResponse;
import com.influora.web.dto.campaign.CampaignDtos.DuplicateResponse;
import com.influora.web.dto.campaign.CampaignDtos.HypeConfigDto;
import com.influora.web.dto.campaign.CampaignDtos.TimelineDto;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CampaignService {

    private final CampaignRepository campaignRepository;
    private final BrandContextService brandContext;
    private final CampaignValidator validator;
    private final IntegrationHealthService integrationHealthService;
    private final BrandCampaignFeeService brandCampaignFeeService;

    public CampaignService(
            CampaignRepository campaignRepository,
            BrandContextService brandContext,
            CampaignValidator validator,
            IntegrationHealthService integrationHealthService,
            BrandCampaignFeeService brandCampaignFeeService) {
        this.campaignRepository = campaignRepository;
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

        List<CampaignResponse> items =
                result.getContent().stream()
                        .map(c -> CampaignMapper.toResponse(c, CampaignMetrics.empty()))
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
        return CampaignMapper.toResponse(campaign, CampaignMetrics.empty());
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

        Campaign campaign =
                Campaign.builder()
                        .id(Ulids.newUlid())
                        .workspaceId(workspace.getId())
                        .title(req.title().trim())
                        .description(req.description())
                        .status(status)
                        .campaignType(campaignType)
                        .hypeConfigJson(hypeConfigJson)
                        .budgetMin(req.budget().min())
                        .budgetMax(req.budget().max())
                        .currency(req.budget().currency())
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
        return CampaignMapper.toResponse(campaign, CampaignMetrics.empty());
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
        validator.ensureEditable(campaign.getStatus());

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
        if (req.hype() != null && campaign.getCampaignType() == CampaignIntentType.HYPE) {
            HypeConfigDto existingHype =
                    JsonLists.objectFromJson(campaign.getHypeConfigJson(), HypeConfigDto.class);
            HypeConfigDto mergedHype = mergeHype(existingHype, req.hype());
            validator.validateHypeConfig(campaign.getCampaignType(), mergedHype);
            hypeConfigJson = JsonLists.toJsonObject(normalizeHype(mergedHype));
        }

        campaign.applyPatch(
                req.title(),
                req.description(),
                req.status(),
                req.budget() != null ? req.budget().min() : null,
                req.budget() != null ? req.budget().max() : null,
                req.budget() != null ? req.budget().currency() : null,
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
        return CampaignMapper.toResponse(campaign, CampaignMetrics.empty());
    }

    @Transactional
    public DeleteResponse delete(AuthPrincipal principal, String campaignId) {
        Workspace workspace = brandContext.requireBrandWorkspace(principal);
        var member = brandContext.requireMember(principal, workspace.getId());
        brandContext.requireRole(member, MemberRole.OWNER);

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
