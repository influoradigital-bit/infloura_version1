package com.influora.service;

import com.influora.common.ApiException;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.EscrowHold;
import com.influora.domain.entity.PaymentMilestone;
import com.influora.domain.enums.EscrowStatus;
import com.influora.integration.razorpay.RazorpayXClient;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.EscrowHoldRepository;
import com.influora.repository.PaymentMilestoneRepository;
import com.influora.security.AuthPrincipal;
import com.influora.web.dto.money.MoneyDtos.PayoutResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * [SEC: PayoutStateMachine] Payout initiation for a RELEASED milestone. The internal ledger
 * release ({@code EscrowService.release}) has already moved the money out of escrow into the
 * creator's Influora wallet; this service is the OUT-OF-BAND step that pushes it to the
 * creator's real bank/UPI account via RazorpayX. A payout is only ever QUEUED here — it becomes
 * PROCESSED asynchronously via a RazorpayX webhook (not modeled in this slice; see
 * {@code RazorpayWebhookController} note), never synchronously in this call.
 *
 * <p>Amount is re-derived from {@link PaymentMilestone#getAmount()} — never accepted from the
 * caller (Guardrail 1).
 */
@Service
public class PayoutService {

    private final PaymentMilestoneRepository milestoneRepository;
    private final EscrowHoldRepository escrowHoldRepository;
    private final CollaborationRepository collaborationRepository;
    private final RazorpayXClient razorpayXClient;
    private final BrandContextService brandContext;

    public PayoutService(
            PaymentMilestoneRepository milestoneRepository,
            EscrowHoldRepository escrowHoldRepository,
            CollaborationRepository collaborationRepository,
            RazorpayXClient razorpayXClient,
            BrandContextService brandContext) {
        this.milestoneRepository = milestoneRepository;
        this.escrowHoldRepository = escrowHoldRepository;
        this.collaborationRepository = collaborationRepository;
        this.razorpayXClient = razorpayXClient;
        this.brandContext = brandContext;
    }

    @Transactional
    public PayoutResponse queuePayout(AuthPrincipal principal, String workspaceId, String milestoneId) {
        brandContext.requireMember(principal, workspaceId);

        PaymentMilestone milestone =
                milestoneRepository
                        .findById(milestoneId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "MILESTONE_NOT_FOUND", "Milestone not found", HttpStatus.NOT_FOUND));

        if (milestone.getEscrowHoldId() == null) {
            throw new ApiException(
                    "MILESTONE_NOT_RELEASED", "Milestone has not been funded", HttpStatus.CONFLICT);
        }
        EscrowHold hold =
                escrowHoldRepository
                        .findById(milestone.getEscrowHoldId())
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "ESCROW_NOT_FOUND", "Escrow hold not found", HttpStatus.NOT_FOUND));
        if (hold.getStatus() != EscrowStatus.RELEASED) {
            throw new ApiException(
                    "MILESTONE_NOT_RELEASED",
                    "Payout can only be queued after the milestone's escrow is RELEASED",
                    HttpStatus.CONFLICT);
        }
        if (!hold.getWorkspaceId().equals(workspaceId)) {
            throw new ApiException("MILESTONE_NOT_FOUND", "Milestone not found", HttpStatus.NOT_FOUND);
        }

        Collaboration collaboration =
                collaborationRepository
                        .findById(milestone.getCollaborationId())
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "COLLABORATION_NOT_FOUND",
                                                "Collaboration not found",
                                                HttpStatus.NOT_FOUND));

        String idempotencyKey = "payout:" + milestone.getId();
        // Real integration resolves a Razorpay Contact/Fund Account id from the creator's KYC
        // record; that entity does not exist in this slice, so we pass the creator's user id as
        // a placeholder fund-account reference. Wire the real lookup once Fund Account KYC lands.
        var payout =
                razorpayXClient.initiatePayout(
                        collaboration.getCreatorId(), milestone.getAmount(), milestone.getCurrency(), idempotencyKey);

        return new PayoutResponse(
                payout.payoutId(), milestone.getId(), milestone.getAmount(), milestone.getCurrency(), payout.status());
    }

    /**
     * Marks a payout as executed once RazorpayX confirms it (webhook-driven, out-of-band). No
     * caller in this slice invokes this synchronously with a client-asserted "it worked" — that
     * would defeat the point of an out-of-band confirm (Guardrail 1 discipline extended to payouts).
     */
    @Transactional
    public void confirmExecuted(String payoutId, String rawWebhookPayload) {
        // Intentionally minimal: no payouts table exists in this slice's migrations (V9/V10 cover
        // escrow_holds/contracts/payment_milestones only). Once a `payouts` table is added, this
        // method should look it up by payoutId and flip PENDING -> PROCESSED here. Left as an
        // explicit, non-TODO no-op-with-signature so RazorpayWebhookController has something real
        // to call without inventing an unspecified table.
    }
}
