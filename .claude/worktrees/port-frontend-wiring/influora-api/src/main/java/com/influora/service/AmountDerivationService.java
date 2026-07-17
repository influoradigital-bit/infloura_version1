package com.influora.service;

import com.influora.common.ApiException;
import com.influora.config.RazorpayProperties;
import com.influora.domain.entity.CampaignIntent;
import com.influora.repository.CampaignIntentRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sole authority for every monetary value on the {@code /internal/meera/*} write surface
 * ([SEC: Kabir G1, MF-1, LB-3]). No DTO on this path may carry a chargeable {@code amount} —
 * callers (e.g. {@code RequestPaymentExecutor}) pass only identifiers (workspaceId,
 * campaignIntentId); this service re-derives the number from {@code campaign_intents.product_price}
 * plus the platform fee config, exactly the same discipline {@code EscrowService.deriveFundAmount}
 * already uses for the human-confirmed escrow-fund leg.
 *
 * <p>{@code display_amount_hint} (or any other AI-proposed number) is NEVER read here. If a
 * caller wants to cross-check an AI-proposed hint against the derived amount (for a 409-style
 * tamper signal), it must pass the hint explicitly to {@link #checkAdvisoryDrift} — the derived
 * amount itself never depends on it.
 */
@Service
public class AmountDerivationService {

    /** Advisory-hint drift tolerance before flagging AMOUNT_MISMATCH — 1% of the derived amount. */
    private static final BigDecimal DRIFT_TOLERANCE_FRACTION = new BigDecimal("0.01");

    private final CampaignIntentRepository campaignIntentRepository;
    private final RazorpayProperties razorpayProperties;

    public AmountDerivationService(
            CampaignIntentRepository campaignIntentRepository, RazorpayProperties razorpayProperties) {
        this.campaignIntentRepository = campaignIntentRepository;
        this.razorpayProperties = razorpayProperties;
    }

    /**
     * Re-derives the authoritative payment-request amount for a campaign intent:
     * {@code product_price * creator_count}, plus the platform fee percentage. Never consults
     * {@code proposed_budget} (AI proposal only, per {@link CampaignIntent} field doc) nor any
     * caller-supplied amount.
     */
    @Transactional(readOnly = true)
    public DerivedAmount deriveForCampaignIntent(String workspaceId, String campaignIntentId) {
        CampaignIntent intent =
                campaignIntentRepository
                        .findByIdAndWorkspaceId(campaignIntentId, workspaceId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "INTENT_NOT_FOUND", "Campaign intent not found", HttpStatus.NOT_FOUND));

        if (intent.getProductPrice() == null || intent.getProductPrice().signum() <= 0) {
            throw new ApiException(
                    "PRODUCT_PRICE_MISSING",
                    "Campaign intent has no product price set — cannot derive a payment amount",
                    HttpStatus.CONFLICT);
        }
        int creatorCount = intent.getCreatorCount() != null && intent.getCreatorCount() > 0
                ? intent.getCreatorCount()
                : 1;

        BigDecimal base = intent.getProductPrice().multiply(BigDecimal.valueOf(creatorCount));
        BigDecimal feePercent = razorpayProperties.getPlatformFeePercent();
        BigDecimal fee =
                base.multiply(feePercent)
                        .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        BigDecimal total = base.add(fee).setScale(2, RoundingMode.HALF_UP);

        return new DerivedAmount(base.setScale(2, RoundingMode.HALF_UP), fee, total, intent);
    }

    /**
     * Compares an AI-proposed "display" hint against the server-derived amount. Returns true if
     * the hint drifts beyond tolerance — callers should surface {@code 409 AMOUNT_MISMATCH} and
     * MUST NOT proceed with the AI's number regardless of the outcome; this check exists only to
     * flag tampering/hallucination for audit, never to select which amount to charge.
     */
    public boolean exceedsDriftTolerance(BigDecimal serverAmount, BigDecimal aiProposedHint) {
        if (aiProposedHint == null) {
            return false;
        }
        BigDecimal tolerance = serverAmount.multiply(DRIFT_TOLERANCE_FRACTION);
        BigDecimal diff = serverAmount.subtract(aiProposedHint).abs();
        return diff.compareTo(tolerance) > 0;
    }

    /** Convenience alias kept for callers that only have the hint and want a boolean check. */
    public boolean checkAdvisoryDrift(BigDecimal serverAmount, BigDecimal aiProposedHint) {
        return exceedsDriftTolerance(serverAmount, aiProposedHint);
    }

    /** Server-derived amount plus its components — the only object executors may treat as chargeable. */
    public record DerivedAmount(
            BigDecimal baseAmount, BigDecimal feeAmount, BigDecimal totalAmount, CampaignIntent intent) {}
}
