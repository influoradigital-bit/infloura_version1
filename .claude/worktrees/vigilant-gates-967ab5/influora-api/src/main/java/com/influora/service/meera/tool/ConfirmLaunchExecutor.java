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
import com.influora.service.IdempotencyService;
import com.influora.service.meera.AICreditService;
import com.influora.web.dto.meera.MeeraToolDtos.ConfirmLaunchResult;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
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
        // from any AI-asserted field. Nothing short of a real FUNDED row unblocks this.
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

        campaign.setStatus(CampaignStatus.ACTIVE);
        campaignRepository.save(campaign);

        // --- Invite: select up to creator_count discoverable creators not already on this
        // campaign and write real Collaboration rows (never a hard-coded zero). ---
        List<Collaboration> invited = inviteCreators(workspaceId, campaign, intent);

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
