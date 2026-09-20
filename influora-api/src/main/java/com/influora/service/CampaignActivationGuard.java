package com.influora.service;

import com.influora.common.ApiException;
import com.influora.domain.entity.Campaign;
import com.influora.domain.enums.CampaignStatus;
import com.influora.domain.enums.EscrowStatus;
import com.influora.repository.EscrowHoldRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * F-0848 (Priya ruling c, wiki/tech/BUILD-PLAN-F0848-MEMORY-0917.md 1.4c) — the ONE place a
 * campaign becomes {@link CampaignStatus#ACTIVE}.
 *
 * <p>Before this class, the two publish paths ({@code CampaignService.update} and Meera's {@code
 * ConfirmLaunchExecutor.doExecute}) each carried their own copy of "funds secured, charge the fee,
 * flip the status", and {@code CampaignService.create} carried none at all — a campaign POSTed with
 * {@code status=ACTIVE} went live with no platform fee and no secured funds. Every activation now
 * runs through {@link #activate}, in this fixed order:
 *
 * <ol>
 *   <li>refuse a campaign that is already ACTIVE (the fee is charged at the real transition only;
 *       callers treat a re-sent ACTIVE as a no-op BEFORE reaching here);
 *   <li>require at least one {@code FUNDED} hold for this campaign id, read fresh from the
 *       database — nothing client- or model-supplied is consulted;
 *   <li>charge the brand platform fee ({@link BrandCampaignFeeService#chargeOnPublish});
 *   <li>set the status to ACTIVE.
 * </ol>
 *
 * <p>{@code Propagation.MANDATORY}: this never opens its own transaction. It throws if the caller
 * has none, so a fee failure always rolls back the caller's whole activation and a half-activated
 * campaign can never commit. Enforced by {@code CampaignActivationPathTest}: only this class may
 * call {@code chargeOnPublish}, and only this class may set {@code CampaignStatus.ACTIVE}.
 */
@Service
public class CampaignActivationGuard {

    private final EscrowHoldRepository escrowHoldRepository;
    private final BrandCampaignFeeService brandCampaignFeeService;

    public CampaignActivationGuard(
            EscrowHoldRepository escrowHoldRepository, BrandCampaignFeeService brandCampaignFeeService) {
        this.escrowHoldRepository = escrowHoldRepository;
        this.brandCampaignFeeService = brandCampaignFeeService;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void activate(Campaign campaign, String workspaceId) {
        if (campaign.getStatus() == CampaignStatus.ACTIVE) {
            throw new ApiException(
                    "CAMPAIGN_ALREADY_ACTIVE", "This campaign is already live", HttpStatus.CONFLICT);
        }
        requireFundedHold(campaign.getId());
        // Same transaction as the status flip below: if the fee throws (low wallet balance, missing
        // fee config) nothing is set and the caller's transaction rolls back.
        brandCampaignFeeService.chargeOnPublish(campaign, workspaceId);
        // F-0872: Campaign.setStatus() now refuses ACTIVE unconditionally (structural guard) --
        // activateForPublish() is the one entity method that may set it, and this is its one
        // legitimate caller.
        campaign.activateForPublish();
    }

    /**
     * Moved verbatim from {@code CampaignService.requireFundedEscrow} (F-0503): at least one hold
     * for this campaign id must be {@code FUNDED}, or activation is refused with the existing
     * {@code ESCROW_NOT_FUNDED}/409 contract. The code is internal; the message is brand-facing and
     * never says "escrow".
     */
    private void requireFundedHold(String campaignId) {
        boolean hasFundedHold =
                escrowHoldRepository.findByCampaignId(campaignId).stream()
                        .anyMatch(h -> h.getStatus() == EscrowStatus.FUNDED);
        if (!hasFundedHold) {
            throw new ApiException(
                    "ESCROW_NOT_FUNDED",
                    "Campaign has no secured payment in FUNDED status — cannot activate",
                    HttpStatus.CONFLICT);
        }
    }
}
