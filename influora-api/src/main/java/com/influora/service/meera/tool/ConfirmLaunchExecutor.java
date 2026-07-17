package com.influora.service.meera.tool;

import com.influora.common.ApiException;
import com.influora.common.Ulids;
import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.CampaignIntent;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.EscrowHold;
import com.influora.domain.entity.MeeraToolCall;
import com.influora.domain.enums.CampaignStatus;
import com.influora.domain.enums.EscrowStatus;
import com.influora.domain.enums.MeeraToolName;
import com.influora.domain.enums.ToolCallStatus;
import com.influora.domain.enums.ToolResultRefType;
import com.influora.repository.CampaignIntentRepository;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.EscrowHoldRepository;
import com.influora.repository.MeeraToolCallRepository;
import com.influora.service.AuditLogService;
import com.influora.service.BrandCampaignFeeService;
import com.influora.service.IdempotencyService;
import com.influora.service.meera.AICreditService;
import com.influora.web.dto.meera.MeeraToolDtos.ConfirmLaunchResult;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * C-tier executor (06-MEERA-PERMISSIONS-MATRIX.md; 11-AI-FLOW-DETAILED.md Flow 3): "only if a
 * human-confirmed action exists; escrow hold + invites." Per
 * 16-VIKRAM-REMAINING-TASKS.md row for this class: "Only proceeds if escrow == FUNDED verified
 * from DB (not asserted by AI). Then invites + escrow-hold + credit reset. Idempotent."
 *
 * <p><b>The critical check, concretely:</b> this executor reads {@link EscrowHold#getStatus()}
 * fresh from the database for every hold tied to the campaign and requires at least one
 * {@code FUNDED} hold before proceeding. Nothing the AI asserts in its tool input (e.g. an
 * {@code "escrow_funded": true} field, if ever present) is consulted — there is no code path
 * here that reads a boolean off {@code input} to decide FUNDED-ness. A prompt-injected or
 * hallucinated claim that "the customer paid" cannot trigger a launch; only a real webhook-driven
 * {@code EscrowStatus.FUNDED} row (written exclusively by {@code EscrowService.confirmFunded} on
 * a verified Razorpay webhook) can.
 *
 * <p><b>Full DoD (invite + escrow-hold + credit reset), concretely:</b>
 *
 * <ul>
 *   <li><b>Invite:</b> selects up to {@code campaign_intents.creator_count} discoverable creators
 *       not already collaborating on this campaign and writes {@link Collaboration#invite} rows
 *       for them — the actual creator invitation side-effect the DoD requires, not a hard-coded
 *       zero. Selection reuses the discoverable-pool + dedupe rules already enforced elsewhere in
 *       this package ({@link ShowCreatorsExecutor}, {@code CreatorDiscoveryService.invite}); this
 *       executor writes the rows directly (rather than calling {@code CreatorDiscoveryService}) because
 *       that service is keyed on a browser {@code AuthPrincipal}, which does not exist on this
 *       internal, service-token-authenticated path — the on-behalf JWT is already re-validated by
 *       {@code OnBehalfAuthResolver} upstream in {@code MeeraInternalController} before this runs.
 *   <li><b>Escrow-hold:</b> the FUNDED hold(s) already verified above are bound to the campaign's
 *       newly-created collaborations ({@link EscrowHold#getCollaborationId()}) so the funded money
 *       is traceable to the specific creators invited at launch, not left dangling
 *       campaign-only.
 *   <li><b>Credit reset:</b> {@link AICreditService#applyEscrowFundedReset} is invoked — the seam
 *       that resets the brand's AI credits and opens the unlimited-usage window for a funded
 *       campaign (01-DATA-MODEL.md §8), rather than leaving the brand's credit state untouched by
 *       a launch that just moved real money into escrow.
 * </ul>
 *
 * <p>Idempotent via {@link IdempotencyService#executeOnce} (V15 {@code idempotency_keys},
 * insert-first-wins on {@code UNIQUE(idempotency_key)}) — a concurrent double-submit is arbitrated
 * by the database, not by a check-then-act read against {@code meera_tool_calls} ([SEC: LB-3]).
 * {@code meera_tool_calls} (V14) remains the result ledger consulted first so a replay never
 * re-invites creators or re-triggers a credit reset.
 *
 * <p><b>[SEC: Kabir red-team CRITICAL-1 fix, 2026-07-14] Brand publish fee.</b> {@link
 * BrandCampaignFeeService} was previously removed from this class's constructor entirely (a
 * mistaken "P3-20" assumption that {@code CampaignService} covered the AI-launch path too — it
 * does not, since this class never calls into {@code CampaignService}). The result was a complete
 * brand-fee bypass on every campaign launched via Meera's {@code confirm_launch} tool: an AI-driven
 * launch charged a silent 0% fee while the equivalent brand-initiated PATCH
 * {@code /campaigns/{id}} path (via {@code CampaignService.update}) charged the real one. {@link
 * BrandCampaignFeeService#chargeOnPublish} is now called from {@link #doExecute} at the real
 * DRAFT/PAUSED/PENDING_APPROVAL -&gt; ACTIVE transition, mirroring {@code
 * CampaignService.update()}'s charge-then-save pattern exactly (same {@code @Transactional}
 * atomicity contract — see that method's javadoc: a fee-charge failure rolls back the whole
 * launch, including the status flip, so a campaign never ends up ACTIVE without having paid).
 *
 * <p><b>[SEC: Kavya B1-REGRESSION-1 fix] "Already ACTIVE" is not automatically a safe no-op.</b>
 * {@link #doExecute} now distinguishes two cases for a campaign that is already {@code ACTIVE} by
 * the time it loads: (1) a genuine REPLAY of a prior {@code confirm_launch} for THIS campaign
 * (a real {@code EXECUTED} row exists in {@code meera_tool_calls} for {@code confirm_launch} +
 * this campaign id, just under a DIFFERENT tool-call idempotency key than the one on THIS request
 * — so {@code IdempotencyService.executeOnce}'s own dedup never caught it) — a clean no-op, never
 * re-invites/re-binds/re-resets-credits/re-charges; versus (2) the campaign was activated via some
 * OTHER path entirely (e.g. the brand's own {@code CampaignService.update()} PATCH) with NO prior
 * {@code confirm_launch} record — rejected with {@code 409 CAMPAIGN_ACTIVATED_WITHOUT_LAUNCH}
 * rather than silently treated as a no-op that skips the invite/bind/credit-reset DoD this tool is
 * supposed to guarantee.
 */
@Service
public class ConfirmLaunchExecutor {

    private static final String IDEMPOTENCY_SCOPE = "meera.confirm_launch";

    /** Funded-campaign unlimited-AI-usage window, mirroring the loyalty-reset seam's intent. */
    private static final int UNLIMITED_WINDOW_DAYS = 30;

    private final CampaignIntentRepository campaignIntentRepository;
    private final CampaignRepository campaignRepository;
    private final EscrowHoldRepository escrowHoldRepository;
    private final MeeraToolCallRepository toolCallRepository;
    private final CollaborationRepository collaborationRepository;
    private final CreatorProfileRepository creatorProfileRepository;
    private final AuditLogService auditLogService;
    private final AICreditService aiCreditService;
    private final IdempotencyService idempotencyService;
    private final BrandCampaignFeeService brandCampaignFeeService;
    private final ConfirmLaunchExecutor self;

    public ConfirmLaunchExecutor(
            CampaignIntentRepository campaignIntentRepository,
            CampaignRepository campaignRepository,
            EscrowHoldRepository escrowHoldRepository,
            MeeraToolCallRepository toolCallRepository,
            CollaborationRepository collaborationRepository,
            CreatorProfileRepository creatorProfileRepository,
            AuditLogService auditLogService,
            AICreditService aiCreditService,
            IdempotencyService idempotencyService,
            BrandCampaignFeeService brandCampaignFeeService,
            @Lazy ConfirmLaunchExecutor self) {
        this.campaignIntentRepository = campaignIntentRepository;
        this.campaignRepository = campaignRepository;
        this.escrowHoldRepository = escrowHoldRepository;
        this.toolCallRepository = toolCallRepository;
        this.collaborationRepository = collaborationRepository;
        this.creatorProfileRepository = creatorProfileRepository;
        this.auditLogService = auditLogService;
        this.aiCreditService = aiCreditService;
        this.idempotencyService = idempotencyService;
        this.brandCampaignFeeService = brandCampaignFeeService;
        this.self = self;
    }

    public ConfirmLaunchResult execute(
            String workspaceId, String conversationId, String idempotencyKey, Map<String, Object> input) {
        ConfirmLaunchResult replay = replayIfPresent(workspaceId, idempotencyKey);
        if (replay != null) {
            return replay;
        }

        try {
            // [SEC: @Transactional self-invocation fix] — call via the injected self-proxy so
            // Spring AOP intercepts and wraps doExecute() in a real transaction. Direct
            // this.doExecute() bypasses the proxy and the @Transactional annotation is ignored,
            // causing partial-commit on multi-write sequences if any step fails.
            return idempotencyService.executeOnce(
                    idempotencyKey,
                    workspaceId,
                    IDEMPOTENCY_SCOPE,
                    () -> self.doExecute(workspaceId, conversationId, idempotencyKey, input));
        } catch (IdempotencyService.AlreadyInProgressException
                | IdempotencyService.AlreadyCompletedException raced) {
            // Lost the insert-first race to a concurrent caller with the same key — replay the
            // winner's result instead of a bare 500/409 on the generic idempotency_keys table.
            ConfirmLaunchResult won = replayIfPresent(workspaceId, idempotencyKey);
            if (won != null) {
                return won;
            }
            throw new ApiException(
                    "IDEMPOTENCY_KEY_IN_PROGRESS",
                    "This request is already being processed — retry shortly",
                    HttpStatus.CONFLICT);
        }
    }

    private ConfirmLaunchResult replayIfPresent(String workspaceId, String idempotencyKey) {
        var existingCall = toolCallRepository.findByIdempotencyKey(idempotencyKey);
        if (existingCall.isEmpty()) {
            return null;
        }
        MeeraToolCall prior = existingCall.get();
        if (!prior.getWorkspaceId().equals(workspaceId)) {
            throw new ApiException(
                    "IDEMPOTENCY_KEY_TENANT_MISMATCH",
                    "Idempotency key belongs to a different workspace",
                    HttpStatus.CONFLICT);
        }
        return new ConfirmLaunchResult(
                prior.getResultRefType() == ToolResultRefType.CAMPAIGN ? prior.getResultRefId() : null,
                prior.getStatus().name(),
                0,
                true);
    }

    @Transactional
    public ConfirmLaunchResult doExecute(
            String workspaceId, String conversationId, String idempotencyKey, Map<String, Object> input) {
        // Field name matches app/tools/schemas.py CONFIRM_LAUNCH input_schema exactly:
        // campaign_intent_id (required) — resolve the confirmed intent's campaignId server-side,
        // never trust a campaign_id the AI might supply directly.
        String campaignIntentId = stringArg(input, "campaign_intent_id");
        if (campaignIntentId == null || campaignIntentId.isBlank()) {
            throw new ApiException(
                    "CAMPAIGN_INTENT_ID_REQUIRED",
                    "confirm_launch requires campaign_intent_id",
                    HttpStatus.BAD_REQUEST);
        }

        CampaignIntent intent =
                campaignIntentRepository
                        .findByIdAndWorkspaceId(campaignIntentId, workspaceId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "INTENT_NOT_FOUND", "Campaign intent not found", HttpStatus.NOT_FOUND));
        String campaignId = intent.getCampaignId();
        if (campaignId == null || campaignId.isBlank()) {
            throw new ApiException(
                    "INTENT_NOT_CONFIRMED",
                    "Campaign intent has no linked campaign yet — create_campaign must run first",
                    HttpStatus.CONFLICT);
        }

        Campaign campaign =
                campaignRepository
                        .findByIdAndWorkspaceId(campaignId, workspaceId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "CAMPAIGN_NOT_FOUND", "Campaign not found", HttpStatus.NOT_FOUND));

        // [SEC: EscrowStateMachine verifies FUNDED from DB] — read fresh from the repository, not
        // from any AI-asserted field. Nothing short of a real FUNDED row unblocks this. Checked
        // BEFORE the already-ACTIVE distinction below (regardless of outcome) so a replay of an
        // already-launched campaign is still proven against a currently-FUNDED hold, mirroring the
        // original DoD ordering this executor has always used.
        List<EscrowHold> holds = escrowHoldRepository.findByCampaignId(campaignId);
        List<EscrowHold> fundedHolds = holds.stream().filter(h -> h.getStatus() == EscrowStatus.FUNDED).toList();
        if (fundedHolds.isEmpty()) {
            auditLogService.recordToolCall(
                    workspaceId,
                    "confirm_launch",
                    "C",
                    AuditLogService.OUTCOME_REJECTED,
                    "ESCROW_NOT_FUNDED",
                    idempotencyKey,
                    null,
                    Map.of("campaignId", campaignId));
            throw new ApiException(
                    "ESCROW_NOT_FUNDED",
                    "Campaign has no FUNDED escrow hold — cannot confirm launch",
                    HttpStatus.CONFLICT);
        }

        // [SEC: Kavya B1-REGRESSION-1 fix] A campaign that is ALREADY ACTIVE by the time it loads
        // is NOT automatically a safe replay no-op — that would silently skip the invite/bind/
        // credit-reset/fee DoD if the campaign was activated via some OTHER path (e.g. the brand's
        // own CampaignService.update() PATCH). Distinguish "genuine confirm_launch replay" from
        // "activated elsewhere" using the independent meera_tool_calls ledger, not campaign.status
        // alone (see MeeraToolCallRepository#existsByToolNameAndResultRefIdAndStatus javadoc).
        if (campaign.getStatus() == CampaignStatus.ACTIVE) {
            boolean genuinePriorLaunch =
                    toolCallRepository.existsByToolNameAndResultRefIdAndStatus(
                            MeeraToolName.confirm_launch, campaign.getId(), ToolCallStatus.EXECUTED);
            if (!genuinePriorLaunch) {
                auditLogService.recordToolCall(
                        workspaceId,
                        "confirm_launch",
                        "C",
                        AuditLogService.OUTCOME_REJECTED,
                        "CAMPAIGN_ACTIVATED_WITHOUT_LAUNCH",
                        idempotencyKey,
                        null,
                        Map.of("campaignId", campaign.getId()));
                throw new ApiException(
                        "CAMPAIGN_ACTIVATED_WITHOUT_LAUNCH",
                        "Campaign is ACTIVE but was never launched via confirm_launch — refusing to"
                                + " silently skip invite/escrow-bind/credit-reset",
                        HttpStatus.CONFLICT);
            }

            // Genuine replay (this request's own idempotencyKey differs from the one that actually
            // ran, but confirm_launch DID already execute for this campaign) — clean no-op. Still
            // records a ledger row for THIS idempotency key so a later replay of it is itself clean.
            toolCallRepository.save(
                    MeeraToolCall.builder()
                            .id(Ulids.newUlid())
                            .workspaceId(workspaceId)
                            .conversationId(conversationId)
                            .toolName(MeeraToolName.confirm_launch)
                            .idempotencyKey(idempotencyKey)
                            .status(ToolCallStatus.EXECUTED)
                            .resultRefType(ToolResultRefType.CAMPAIGN)
                            .resultRefId(campaign.getId())
                            .build());
            auditLogService.recordToolCall(
                    workspaceId,
                    "confirm_launch",
                    "C",
                    AuditLogService.OUTCOME_ALLOWED,
                    "ALREADY_ACTIVE_NOOP",
                    idempotencyKey,
                    null,
                    Map.of("campaignId", campaign.getId()));
            return new ConfirmLaunchResult(campaign.getId(), CampaignStatus.ACTIVE.name(), 0, true);
        }

        // [SEC: Kabir red-team CRITICAL-1 fix] Real DRAFT/PAUSED/PENDING_APPROVAL -> ACTIVE
        // transition — charge the brand publish fee BEFORE the status flip is persisted, mirroring
        // CampaignService.update()'s charge-then-save pattern exactly. If this throws (insufficient
        // wallet balance, missing fee config), the whole @Transactional method rolls back — the
        // campaign never ends up ACTIVE, and no invite/bind/credit-reset ever runs, without having
        // paid the fee.
        campaign.setStatus(CampaignStatus.ACTIVE);
        brandCampaignFeeService.chargeOnPublish(campaign, workspaceId);
        campaignRepository.save(campaign);

        // --- Invite: select up to creator_count discoverable creators not already on this
        // campaign and write real Collaboration rows (never a hard-coded zero). ---
        // [SEC: Kabir fix 2b] inviteCreators already pre-filters via
        // collaborationRepository.existsByCampaignIdAndCreatorId, but that check-then-act has a
        // TOCTOU gap under concurrency (two launches racing to invite the same creator) — the
        // database's own uq_campaign_creator UNIQUE constraint is the real arbiter. A genuine
        // violation here is translated to a clean 409, never an unhandled 500.
        List<Collaboration> invited;
        try {
            invited = inviteCreators(workspaceId, campaign, intent);
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(
                    "COLLABORATION_EXISTS",
                    "One or more selected creators are already invited/collaborating on this campaign",
                    HttpStatus.CONFLICT);
        }

        // --- Escrow-hold: bind the verified FUNDED hold(s) to the collaborations just created so
        // the funded money is traceable to the creators actually invited at launch. ---
        bindFundedHoldsToCollaborations(fundedHolds, invited);

        // --- Credit reset: a funded launch resets the brand's AI credits and opens the
        // unlimited-usage window (01-DATA-MODEL.md §8) rather than leaving credit state
        // untouched by the campaign that just moved real money into escrow. ---
        Instant unlimitedUntil = Instant.now().plus(UNLIMITED_WINDOW_DAYS, ChronoUnit.DAYS);
        aiCreditService.applyEscrowFundedReset(workspaceId, unlimitedUntil);

        toolCallRepository.save(
                MeeraToolCall.builder()
                        .id(Ulids.newUlid())
                        .workspaceId(workspaceId)
                        .conversationId(conversationId)
                        .toolName(MeeraToolName.confirm_launch)
                        .idempotencyKey(idempotencyKey)
                        .status(ToolCallStatus.EXECUTED)
                        .resultRefType(ToolResultRefType.CAMPAIGN)
                        .resultRefId(campaign.getId())
                        .build());

        auditLogService.recordToolCall(
                workspaceId,
                "confirm_launch",
                "C",
                AuditLogService.OUTCOME_ALLOWED,
                null,
                idempotencyKey,
                null,
                Map.of(
                        "campaignId", campaign.getId(),
                        "status", CampaignStatus.ACTIVE.name(),
                        "creatorsInvited", invited.size()));

        return new ConfirmLaunchResult(campaign.getId(), CampaignStatus.ACTIVE.name(), invited.size(), false);
    }

    /**
     * Selects up to {@code campaign_intents.creator_count} discoverable creators not already
     * collaborating on this campaign and writes {@code INVITED} {@link Collaboration} rows for
     * them. Mirrors {@link ShowCreatorsExecutor}'s discoverable-pool read and
     * {@code CreatorDiscoveryService.invite}'s per-creator dedupe
     * ({@code existsByCampaignIdAndCreatorId}), inlined here because this internal path has no
     * {@code AuthPrincipal} to hand that service.
     */
    private List<Collaboration> inviteCreators(String workspaceId, Campaign campaign, CampaignIntent intent) {
        Integer requestedCount = intent.getCreatorCount();
        int targetCount = requestedCount != null && requestedCount > 0 ? requestedCount : 0;
        if (targetCount == 0) {
            return List.of();
        }

        List<CreatorProfile> candidates =
                creatorProfileRepository
                        .findAll(PageRequest.of(0, targetCount, Sort.by(Sort.Direction.DESC, "totalFollowers")))
                        .getContent();

        return candidates.stream()
                .filter(CreatorProfile::isDiscoverable)
                .filter(
                        c ->
                                !collaborationRepository.existsByCampaignIdAndCreatorId(
                                        campaign.getId(), c.getUserId()))
                .limit(targetCount)
                .map(
                        c ->
                                collaborationRepository.save(
                                        Collaboration.invite(
                                                Ulids.newUlid(),
                                                campaign.getId(),
                                                c.getUserId(),
                                                "Invited by Meera at campaign launch.",
                                                campaign.getCurrency())))
                .toList();
    }

    /**
     * Binds each verified FUNDED hold to one of the collaborations just created, so the escrow
     * money that unblocked this launch is traceable to a specific invited creator rather than
     * left campaign-scoped only. A hold already bound to a collaboration (from an earlier flow)
     * is left untouched; only unbound holds are assigned, one-to-one, in stable order. This never
     * re-derives or re-checks FUNDED-ness — {@code fundedHolds} was already verified from the DB
     * by the caller; this method only persists the collaboration linkage.
     */
    private void bindFundedHoldsToCollaborations(List<EscrowHold> fundedHolds, List<Collaboration> invited) {
        if (invited.isEmpty()) {
            return;
        }
        int i = 0;
        for (EscrowHold hold : fundedHolds) {
            if (hold.getCollaborationId() != null) {
                continue;
            }
            if (i >= invited.size()) {
                break;
            }
            hold.bindCollaboration(invited.get(i).getId());
            escrowHoldRepository.save(hold);
            i++;
        }
    }

    private static String stringArg(Map<String, Object> input, String key) {
        Object value = input == null ? null : input.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
