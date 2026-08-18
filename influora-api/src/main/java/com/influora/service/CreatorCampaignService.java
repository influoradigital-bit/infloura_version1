package com.influora.service;

import com.influora.common.ApiException;
import com.influora.common.JsonLists;
import com.influora.common.PageMeta;
import com.influora.common.Ulids;
import com.influora.repository.DealMessageRepository;
import com.influora.domain.enums.DealSenderType;
import com.influora.domain.enums.DealMessageKind;
import com.influora.domain.entity.DealMessage;
import com.influora.common.TextSanitizer;
import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.Workspace;
import com.influora.domain.enums.ApplicationHistoryActorType;
import com.influora.domain.enums.ApplicationHistoryEventType;
import com.influora.domain.enums.CampaignStatus;
import com.influora.domain.enums.CollaborationSource;
import com.influora.domain.enums.CollaborationStatus;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CampaignSpecs;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.security.AuthPrincipal;
import com.influora.service.notification.event.ApplicationCreatedEvent;
import com.influora.web.dto.creatorcampaign.CreatorCampaignDtos.ApplyRequest;
import com.influora.web.dto.creatorcampaign.CreatorCampaignDtos.ApplyResponse;
import com.influora.web.dto.creatorcampaign.CreatorCampaignDtos.CreatorCampaignDetailResponse;
import com.influora.web.dto.creatorcampaign.CreatorCampaignDtos.CreatorCampaignListItem;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Task #7 (creator campaign browse/apply, Creator Week 2 sprint — TASK_INBOX.md P0 #7). Creator
 * -facing counterpart to the brand-facing {@link CampaignService}: browse/apply are read paths
 * plus one mutation (apply), all gated through {@link CreatorContextService#requireCreatorProfile}
 * — never a client-supplied creator id (TECH-STACK.md cross-cutting rule #2).
 */
@Service
public class CreatorCampaignService {

    private static final Logger log = LoggerFactory.getLogger(CreatorCampaignService.class);

    private final CampaignRepository campaignRepository;
    private final CollaborationRepository collaborationRepository;
    private final WorkspaceRepository workspaceRepository;
    private final CreatorContextService creatorContext;
    private final BrandContextService brandContext;
    private final ApplicationEventPublisher eventPublisher;
    /** F-0290 — the deal-room timeline an application must appear on. */
    private final DealMessageRepository dealMessageRepository;
    /** F-0225 — the shared "may this pair start again?" decision. See the service's javadoc. */
    private final CollaborationReviveService collaborationReviveService;
    /** Persistent application-history timeline — see {@link ApplicationHistoryService}'s javadoc. */
    private final ApplicationHistoryService applicationHistoryService;

    public CreatorCampaignService(
            CampaignRepository campaignRepository,
            CollaborationRepository collaborationRepository,
            WorkspaceRepository workspaceRepository,
            CreatorContextService creatorContext,
            BrandContextService brandContext,
            ApplicationEventPublisher eventPublisher,
            CollaborationReviveService collaborationReviveService,
            DealMessageRepository dealMessageRepository,
            ApplicationHistoryService applicationHistoryService) {
        this.campaignRepository = campaignRepository;
        this.collaborationRepository = collaborationRepository;
        this.workspaceRepository = workspaceRepository;
        this.creatorContext = creatorContext;
        this.brandContext = brandContext;
        this.eventPublisher = eventPublisher;
        this.collaborationReviveService = collaborationReviveService;
        this.dealMessageRepository = dealMessageRepository;
        this.applicationHistoryService = applicationHistoryService;
    }

    public record PagedCreatorCampaigns(List<CreatorCampaignListItem> items, PageMeta meta) {}

    /**
     * Platform/niche filters are applied in-memory after the DB-level status/visibility/deadline/
     * budget filters, same pattern as {@code CreatorDiscoveryService.search}'s vertical post-filter
     * (Campaign has no dedicated niche/category column to filter on at the DB level — see
     * TECH-STACK.md's note that 05_CREATOR_CAMPAIGNS_SPEC.md entity shapes are a feature reference,
     * not literal). When either post-filter is active, {@code total}/{@code hasMore} reflect only
     * the current page (same documented limitation as the vertical post-filter above it) — TODO for
     * Kavya: flag if creators need exact totals under these filters, which would require moving the
     * niche/platform match into the DB query (e.g. promoting platforms to a join table like
     * PlatformStat).
     */
    @Transactional(readOnly = true)
    public PagedCreatorCampaigns browse(
            AuthPrincipal principal,
            String niche,
            BigDecimal budgetMin,
            BigDecimal budgetMax,
            String platform,
            int page,
            int limit) {
        CreatorProfile creator = creatorContext.requireCreatorProfile(principal);
        int safePage = Math.max(page, 1);
        int safeLimit = Math.min(Math.max(limit, 1), 100);

        Specification<Campaign> spec =
                Specification.where(CampaignSpecs.browsableForCreator())
                        .and(CampaignSpecs.applicationDeadlineNotPassed())
                        .and(CampaignSpecs.budgetOverlap(budgetMin, budgetMax));

        Page<Campaign> result =
                campaignRepository.findAll(
                        spec,
                        PageRequest.of(safePage - 1, safeLimit, Sort.by(Sort.Direction.DESC, "createdAt")));

        List<Campaign> campaigns = result.getContent();
        boolean postFiltered = false;
        if (platform != null && !platform.isBlank()) {
            campaigns = campaigns.stream().filter(c -> matchesPlatform(c, platform)).toList();
            postFiltered = true;
        }
        if (niche != null && !niche.isBlank()) {
            campaigns = campaigns.stream().filter(c -> matchesNiche(c, niche)).toList();
            postFiltered = true;
        }

        Map<String, Workspace> workspaces = loadWorkspaces(campaigns);
        Map<String, Collaboration> applicationsByCampaign = loadApplications(creator, campaigns);

        List<CreatorCampaignListItem> items =
                campaigns.stream()
                        .map(
                                c ->
                                        CreatorCampaignMapper.toListItem(
                                                c,
                                                workspaces.get(c.getWorkspaceId()),
                                                applicationsByCampaign.get(c.getId())))
                        .toList();

        long total = postFiltered ? items.size() : result.getTotalElements();
        boolean hasMore = postFiltered ? false : result.hasNext();
        return new PagedCreatorCampaigns(items, new PageMeta(safePage, safeLimit, total, hasMore));
    }

    @Transactional(readOnly = true)
    public CreatorCampaignDetailResponse getDetail(AuthPrincipal principal, String campaignId) {
        CreatorProfile creator = creatorContext.requireCreatorProfile(principal);
        Campaign campaign = requireVisibleCampaign(campaignId, creator.getUserId());
        Workspace workspace = workspaceRepository.findById(campaign.getWorkspaceId()).orElse(null);
        Collaboration existing =
                collaborationRepository
                        .findByCampaignIdAndCreatorId(campaign.getId(), creator.getUserId())
                        .orElse(null);
        return CreatorCampaignMapper.toDetail(campaign, workspace, existing);
    }

    /**
     * Idempotency: the {@code UNIQUE(campaign_id, creator_id)} constraint on {@code collaborations}
     * (V6__creators_collaborations.sql) already exists and backs this — the {@code existsBy...}
     * pre-check below handles the sequential duplicate, and the {@code DataIntegrityViolationException}
     * catch handles the concurrent-race loser, same shape as {@code CreatorDiscoveryService#invite}'s
     * documented TOCTOU fix.
     */
    @Transactional
    public ApplyResponse apply(AuthPrincipal principal, String campaignId, ApplyRequest req) {
        CreatorProfile creator = creatorContext.requireCreatorProfile(principal);
        Campaign campaign = requireVisibleCampaign(campaignId, creator.getUserId());

        if (campaign.getStatus() != CampaignStatus.ACTIVE) {
            throw new ApiException(
                    "CAMPAIGN_NOT_OPEN", "This campaign is not open for applications", HttpStatus.CONFLICT);
        }
        if (campaign.getApplicationDeadline() != null
                && campaign.getApplicationDeadline().isBefore(LocalDate.now())) {
            throw new ApiException(
                    "APPLICATION_DEADLINE_PASSED",
                    "The application deadline for this campaign has passed",
                    HttpStatus.CONFLICT);
        }
        // F-0225 — this used to be a status-blind `existsBy...` check, so a WITHDRAWN application
        // (which leaves the row behind as CANCELLED rather than deleting it) blocked the creator
        // from ever applying to this campaign again. Relaxing the check alone would not have
        // worked either: UNIQUE(campaign_id, creator_id) means the insert below has no second row
        // to take. The existing row is revived instead. The decision lives in
        // CollaborationReviveService because three other call sites share it — including WHY a
        // CANCELLED row still has to be checked for attached contracts/escrow/shipments rather
        // than trusting canReject() to imply their absence.
        Collaboration prior =
                collaborationReviveService.reviveOrRefuse(
                        campaign.getId(),
                        creator.getUserId(),
                        CollaborationStatus.APPLIED,
                        CollaborationSource.APPLICATION,
                        req != null ? req.message() : null,
                        campaign.getCurrency(),
                        "ALREADY_APPLIED",
                        "You have already applied to this campaign");
        if (prior != null) {
            recordApplicationOnTimeline(prior, req != null ? req.message() : null);
            recordApplicationHistory(campaign, prior, creator);
            try {
                notifyApplicationCreated(campaign, creator, prior);
            } catch (RuntimeException e) {
                log.error(
                        "ApplicationCreatedEvent notification failed for revived collaboration {} —"
                                + " the re-application itself already succeeded",
                        prior.getId(),
                        e);
            }
            return new ApplyResponse(prior.getId(), prior.getStatus().name(), prior.getAppliedAt());
        }

        Collaboration collaboration =
                Collaboration.apply(
                        Ulids.newUlid(),
                        campaign.getId(),
                        creator.getUserId(),
                        req != null ? req.message() : null,
                        campaign.getCurrency());
        try {
            collaborationRepository.save(collaboration);
        } catch (DataIntegrityViolationException dup) {
            throw new ApiException(
                    "ALREADY_APPLIED", "You have already applied to this campaign", HttpStatus.CONFLICT);
        }

        // W3-1 — #9 "creator applies to campaign" (07-NOTIFICATION-SYSTEM-SPEC.md §3.2). Notifies
        // the brand a new application arrived. Best-effort — a lookup failure here must never fail
        // the application itself, which has already fully succeeded above.
        recordApplicationOnTimeline(collaboration, req != null ? req.message() : null);
        recordApplicationHistory(campaign, collaboration, creator);
        try {
            notifyApplicationCreated(campaign, creator, collaboration);
        } catch (RuntimeException e) {
            log.error(
                    "ApplicationCreatedEvent notification failed for collaboration {} — application"
                            + " itself already succeeded",
                    collaboration.getId(),
                    e);
        }

        return new ApplyResponse(
                collaboration.getId(), collaboration.getStatus().name(), collaboration.getAppliedAt());
    }

    /**
     * F-0290 — record the application as a visible event in the deal room.
     *
     * <p>Applying created a {@code Collaboration} and nothing else: no {@code DealMessage} row at
     * all. Both parties then opened a deal room with an empty thread — the creator had no record
     * of what they had asked for or when, and the brand saw a deal appear with no request behind
     * it. Worse, when the brand later accepted, {@code DealService#doAccept} appended "Brand
     * accepted the proposal" — a line referring to a proposal card that had never existed.
     *
     * <p>Two rows, deliberately, because they are two different things: a {@code system} row is
     * the EVENT (an application happened, at this moment, and it survives a re-application), and
     * the creator's optional note is a {@code text} row genuinely authored BY the creator, so it
     * renders on their side of the thread and the brand can reply to it in place. Folding the note
     * into the system line would attribute the creator's words to the platform.
     *
     * <p>Best-effort: a failure here must not roll back an application that already succeeded,
     * for the same reason the notification below is best-effort. An application with no timeline
     * is the bug being fixed; an application that 500s because its timeline could not be written
     * is worse.
     */
    private void recordApplicationOnTimeline(Collaboration collaboration, String message) {
        try {
            dealMessageRepository.save(
                    DealMessage.create(
                            Ulids.newUlid(),
                            collaboration.getId(),
                            DealMessageKind.system,
                            "system",
                            DealSenderType.system,
                            "Creator applied to this campaign",
                            null));
            if (message != null && !message.isBlank()) {
                dealMessageRepository.save(
                        DealMessage.create(
                                Ulids.newUlid(),
                                collaboration.getId(),
                                DealMessageKind.text,
                                collaboration.getCreatorId(),
                                DealSenderType.creator,
                                TextSanitizer.sanitizePlainText(message),
                                null));
            }
        } catch (RuntimeException e) {
            log.error(
                    "Could not write the application timeline for collaboration {} — the application"
                            + " itself already succeeded",
                    collaboration.getId(),
                    e);
        }
    }

    /**
     * Persistent application-history counterpart to {@link #recordApplicationOnTimeline}. Two
     * rows for the same reason {@code DealService#doAccept}/{@code #doReject} record ACCEPTED/
     * REJECTED as their own events rather than reusing the {@code DealMessage} row: {@code
     * CAMPAIGN_APPLIED} is the creator-facing "you applied" fact, {@code APPLICATION_RECEIVED} is
     * the brand-facing "an application arrived" fact — same moment, two audiences, both belong in
     * an append-only history a status-only field like {@code Collaboration.updatedAt} cannot
     * reconstruct. {@code dealRoomId} is left {@code null}: no deal room exists yet at this point
     * (see {@link com.influora.domain.entity.ApplicationHistoryEvent}'s javadoc).
     *
     * <p><b>targetRoute correctness (post-refuse fix).</b> Both rows are rendered on the
     * CREATOR's own timeline ({@code GET /creator/applications/{dealId}/history} is creator-only —
     * see {@link CreatorApplicationService#history}), and the frontend prefers {@code targetRoute}
     * over every fallback. {@code CAMPAIGN_APPLIED} points at {@code
     * "/creator/campaigns/" + campaign.getId()} — the CAMPAIGN id, a real route ({@code
     * src/App.tsx}'s {@code path="/creator/campaigns/:id"}) — never {@code collaboration.getId()}:
     * there is no {@code /creator/deals/:id} route (only the bare list, {@code
     * path="/creator/deals"}), so that would have 404'd via the catch-all. {@code
     * APPLICATION_RECEIVED} gets {@code targetRoute = null, targetId = null}: it is the
     * brand-facing fact rendered on the creator's own timeline, there is nothing the creator can
     * act on yet (no deal room exists before acceptance), and a brand-app route (the mistake this
     * replaced) would have bounced a creator to the brand login. The frontend already renders no
     * CTA when both {@code targetRoute} and {@code dealRoomId} are absent, so {@code null} is the
     * honest value here, not a substitute route. A future deal-room-opening event should use
     * {@code "/creator/chat?deal=" + collaboration.getId()} — the established creator convention
     * ({@code src/components/creator/CreatorApplicationCard.tsx:52}), not {@code /creator/deals/}.
     *
     * <p>Best-effort — a failure here must never roll back an application that already succeeded.
     *
     * <p><b>Sign-off review fix (F-history-apply-fk-race).</b> This used to call {@link
     * ApplicationHistoryService#record} directly and synchronously, right here, inside {@code
     * apply()}'s own ambient {@code @Transactional}. That is unsafe for the NEW-application branch
     * specifically: {@link Collaboration}'s {@code @Id} is a pre-assigned ULID with no {@code
     * @GeneratedValue}, so {@code collaborationRepository.save(collaboration)} a few lines above
     * this call routes through {@code em.merge()} and DEFERS the actual {@code INSERT} to flush —
     * normally at this ambient transaction's own commit. {@code record()} is {@code REQUIRES_NEW}
     * (see its class javadoc): calling it from here suspends the ambient transaction and opens a
     * brand-new one on a SEPARATE connection, which cannot see the not-yet-flushed {@code
     * collaborations} row. {@code application_history_events.application_id} carries a real FK to
     * {@code collaborations(id)} (V69, {@code fk_app_history_application}), so that FK check fails
     * on the {@code REQUIRES_NEW} connection — {@code DataIntegrityViolationException}, silently
     * swallowed by the catch this method used to have, 200 returned, and the creator's timeline is
     * permanently missing its origin event. (The revive branch, {@code prior != null} in {@link
     * #apply}, does not hit this — {@code prior} is an already-persisted row from an earlier,
     * already-committed transaction — but this method is shared by both branches.)
     *
     * <p>Publishing an event here instead, consumed by {@link
     * #onApplicationHistoryRecorded(ApplicationHistoryRecordedEvent)} at {@code AFTER_COMMIT},
     * closes this structurally rather than by ordering two statements just right: by the time the
     * listener runs, the {@code collaborations} row this FK depends on is guaranteed durably
     * committed, on every branch, forever — not an invariant that depends on this method staying
     * positioned after {@code collaborationRepository.save(...)} in the source. Same idiom this
     * codebase already uses for exactly this class of hazard ({@code
     * AnalyzeSiteTriggerService#trigger}/{@code onAnalyzeSiteRequested}; {@code
     * NotificationListener}'s handlers, which already consume THIS class's own {@code
     * ApplicationCreatedEvent} — published from this same {@code apply()} transaction — the same
     * way). {@code apply()} is the top-level {@code @Transactional} entry point (called directly
     * from {@code CreatorCampaignController}, never nested inside another {@code @Transactional}
     * caller), so a real transaction is always open when this publishes — the "published with no
     * active transaction, listener silently never fires" trap ({@code SubscriptionDunningJob},
     * {@code RazorpayWebhookController}) does not apply here.
     */
    private void recordApplicationHistory(Campaign campaign, Collaboration collaboration, CreatorProfile creator) {
        eventPublisher.publishEvent(
                new ApplicationHistoryRecordedEvent(
                        campaign.getId(), collaboration.getId(), collaboration.getStatus(), creator.getUserId()));
    }

    /**
     * AFTER_COMMIT handler for {@link ApplicationHistoryRecordedEvent} — see {@link
     * #recordApplicationHistory}'s javadoc for the FK race this defers around.
     *
     * <p><b>What happens if this fails.</b> By construction this runs strictly after the
     * surrounding transaction has already committed — the {@link Collaboration} row is durable,
     * the application already fully succeeded, and there is nothing left to roll back. A failure
     * in either {@code record(...)} call below is therefore a silently MISSING timeline row (the
     * creator's history simply starts one event later, e.g. at {@code APPLICATION_VIEWED}), never
     * a phantom one — the strictly safer of the two failure modes (see {@code
     * BrandDeliverableServiceApprovalRollbackIsolationTest}'s sibling fix: an append-only ledger
     * asserting an undone fact is worse than a missing row). Logged, not retried: same best-effort,
     * log-and-move-on discipline as every other {@code AFTER_COMMIT}/post-commit write in this
     * codebase ({@code NotificationListener}, {@code AnalyzeSiteTriggerService}, {@code
     * SubscriptionDunningJob#publishHaltedEmail}) — there is no outbox/retry mechanism for this
     * class of best-effort side write anywhere else either.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onApplicationHistoryRecorded(ApplicationHistoryRecordedEvent event) {
        try {
            applicationHistoryService.record(
                    event.campaignId(),
                    event.collaborationId(),
                    null,
                    ApplicationHistoryEventType.CAMPAIGN_APPLIED,
                    event.collaborationStatus(),
                    ApplicationHistoryActorType.CREATOR,
                    event.creatorUserId(),
                    "Creator applied to this campaign",
                    null,
                    "/creator/campaigns/" + event.campaignId(),
                    event.campaignId());
            applicationHistoryService.record(
                    event.campaignId(),
                    event.collaborationId(),
                    null,
                    ApplicationHistoryEventType.APPLICATION_RECEIVED,
                    event.collaborationStatus(),
                    ApplicationHistoryActorType.SYSTEM,
                    "system",
                    "Brand received a new application",
                    null,
                    null,
                    null);
        } catch (RuntimeException e) {
            log.error(
                    "Could not write the application-history event for collaboration {} — the"
                            + " application itself already succeeded (and already committed, before"
                            + " this AFTER_COMMIT listener ran)",
                    event.collaborationId(),
                    e);
        }
    }

    /**
     * Internal-only signal (F-history-apply-fk-race) — published by {@link
     * #recordApplicationHistory} from inside {@code apply()}'s ambient {@code @Transactional},
     * consumed by this same class's {@link #onApplicationHistoryRecorded} at {@code AFTER_COMMIT}.
     * Carries plain field values, not live entity references — by the time the listener runs the
     * persistence context that produced {@code campaign}/{@code collaboration} is long closed, so
     * this must not hold onto anything that needs a session to read.
     */
    record ApplicationHistoryRecordedEvent(
            String campaignId, String collaborationId, CollaborationStatus collaborationStatus, String creatorUserId) {}

    private void notifyApplicationCreated(
            Campaign campaign, CreatorProfile creator, Collaboration collaboration) {
        var recipient = brandContext.resolveBillingRecipient(campaign.getWorkspaceId());
        if (recipient == null) {
            return;
        }
        eventPublisher.publishEvent(
                new ApplicationCreatedEvent(
                        recipient.userId(),
                        campaign.getWorkspaceId(),
                        collaboration.getId(),
                        creator.getDisplayName(),
                        campaign.getTitle()));
    }

    /**
     * Never trust campaign visibility on id alone: DRAFT campaigns are never creator-visible, and a
     * {@code isPrivate} (invite-only) campaign is visible only if this creator already holds a
     * {@code Collaboration} row (i.e. was invited) — otherwise this 404s exactly like a campaign
     * that doesn't exist, so brands' invite-only campaigns can't be enumerated by id.
     */
    private Campaign requireVisibleCampaign(String campaignId, String creatorUserId) {
        Campaign campaign =
                campaignRepository
                        .findById(campaignId)
                        .filter(c -> c.getStatus() != CampaignStatus.DRAFT)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "CAMPAIGN_NOT_FOUND", "Campaign not found", HttpStatus.NOT_FOUND));
        if (campaign.isPrivate()
                && !collaborationRepository.existsByCampaignIdAndCreatorId(campaign.getId(), creatorUserId)) {
            throw new ApiException("CAMPAIGN_NOT_FOUND", "Campaign not found", HttpStatus.NOT_FOUND);
        }
        return campaign;
    }

    private Map<String, Workspace> loadWorkspaces(List<Campaign> campaigns) {
        List<String> workspaceIds = campaigns.stream().map(Campaign::getWorkspaceId).distinct().toList();
        if (workspaceIds.isEmpty()) {
            return Map.of();
        }
        return workspaceRepository.findAllById(workspaceIds).stream()
                .collect(Collectors.toMap(Workspace::getId, Function.identity()));
    }

    private Map<String, Collaboration> loadApplications(CreatorProfile creator, List<Campaign> campaigns) {
        List<String> campaignIds = campaigns.stream().map(Campaign::getId).toList();
        if (campaignIds.isEmpty()) {
            return Map.of();
        }
        return collaborationRepository
                .findByCreatorIdAndCampaignIdIn(creator.getUserId(), campaignIds)
                .stream()
                .collect(Collectors.toMap(Collaboration::getCampaignId, Function.identity()));
    }

    private static boolean matchesPlatform(Campaign c, String platform) {
        String needle = platform.trim().toUpperCase(Locale.ROOT);
        return JsonLists.stringListFromJson(c.getPlatformsJson()).stream()
                .anyMatch(p -> p.toUpperCase(Locale.ROOT).equals(needle));
    }

    /**
     * Case-insensitive niche matching (CR-62): lowercases the incoming niche parameter and all
     * campaign fields before comparison, so the frontend need not lowercase before calling browse().
     */
    private static boolean matchesNiche(Campaign c, String niche) {
        String needle = niche.trim().toLowerCase(Locale.ROOT);
        if (containsIgnoreCase(c.getTitle(), needle) || containsIgnoreCase(c.getDescription(), needle)) {
            return true;
        }
        return anyListContains(c.getHashtagsJson(), needle)
                || anyListContains(c.getRequirementsJson(), needle)
                || anyListContains(c.getObjectivesJson(), needle);
    }

    private static boolean anyListContains(String json, String needleLower) {
        return JsonLists.stringListFromJson(json).stream()
                .anyMatch(v -> containsIgnoreCase(v, needleLower));
    }

    private static boolean containsIgnoreCase(String value, String needleLower) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needleLower);
    }
}
