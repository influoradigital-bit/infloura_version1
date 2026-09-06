package com.influora.service.tracking;

import com.influora.common.ApiException;
import com.influora.domain.entity.UtmCampaign;
import com.influora.repository.UtmCampaignRepository;
import com.influora.service.AuditLogService;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * [W1-7 / H15/H16] Extracted from {@link ConversionTrackingService#doRecordConversion} — this is
 * the actual mutating write, called by {@link ConversionTrackingService#recordConversion} FROM
 * INSIDE the {@link com.influora.service.IdempotencyService#executeOnce} supplier lambda it passes
 * in. When that lambda lived in {@code ConversionTrackingService} itself and called {@code
 * this.doRecordConversion(...)} directly, the call bypassed Spring's transactional proxy entirely
 * (a lambda captures the enclosing instance's raw {@code this}) — {@code @Transactional} on that
 * method was a silent no-op, so a failure between {@code utmCampaignRepository.save(utm)} and the
 * audit-log write would not roll back the already-persisted counter/revenue increment. Moving the
 * write to a genuinely separate {@code @Component} means {@link ConversionTrackingService} now
 * calls it through this bean's real Spring proxy, so {@code @Transactional} actually demarcates a
 * transaction.
 */
@Component
public class ConversionTrackingWriter {

    private final UtmCampaignRepository utmCampaignRepository;
    private final AuditLogService auditLogService;

    public ConversionTrackingWriter(
            UtmCampaignRepository utmCampaignRepository, AuditLogService auditLogService) {
        this.utmCampaignRepository = utmCampaignRepository;
        this.auditLogService = auditLogService;
    }

    /**
     * Runs ONLY inside {@code executeOnce} (called from {@link
     * ConversionTrackingService#recordConversion}) — see class javadoc. Identical logic to the
     * pre-extraction {@code ConversionTrackingService#doRecordConversion}.
     */
    @Transactional
    public void doRecordConversion(
            String utmCampaignId, String orderId, BigDecimal orderAmount, String idempotencyKey) {
        UtmCampaign utm =
                utmCampaignRepository
                        .findById(utmCampaignId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "UTM_NOT_FOUND", "Tracking link not found", HttpStatus.NOT_FOUND));

        if (orderAmount == null || orderAmount.signum() < 0) {
            throw new ApiException(
                    "ORDER_AMOUNT_INVALID", "orderAmount must be a non-negative amount", HttpStatus.BAD_REQUEST);
        }

        utm.incrementConversionCount();
        utm.addRevenue(orderAmount);
        utmCampaignRepository.save(utm);

        // Campaign-level attribution rollup (spec's campaign.addAttribution(orderAmount)) is
        // deliberately out of scope -- Campaign has no attribution field. See
        // ConversionTrackingService's class javadoc.

        String auditIdempotencyKey =
                (idempotencyKey != null && !idempotencyKey.isBlank())
                        ? idempotencyKey
                        : "conv:" + utm.getCampaignId() + ":" + orderId;

        // [T-FESTIVALBOX-0905 phase 7, landmine 1, CRITICAL] This was Map.of(...), which throws
        // NullPointerException on a null VALUE. Once utm_campaigns.creator_profile_id became
        // nullable (V20260905180000), every conversion on a page-level Festival Box "Shop button"
        // link would have thrown here — and because this runs inside the conversion write path,
        // the throw does not just skip the audit entry, it LOSES THE SALE.
        //
        // Identical bug and identical fix to RedemptionWriter's coupon branch (phase 4): a
        // null-safe mutable map with an explicit boolean flag, and creatorId added only when there
        // actually is one. The key is ABSENT rather than null for a page-level conversion — an
        // audit record should not assert "creatorId: null", it should simply not claim a creator.
        Map<String, Object> auditDetail = new HashMap<>();
        auditDetail.put("utmCampaignId", utmCampaignId);
        auditDetail.put("campaignId", utm.getCampaignId());
        auditDetail.put("orderId", orderId == null ? "" : orderId);
        auditDetail.put("pageLevel", utm.isPageLevel());
        if (utm.getCreatorProfileId() != null) {
            auditDetail.put("creatorId", utm.getCreatorProfileId());
        }

        auditLogService.recordMoneyEvent(
                null,
                "CONVERSION_TRACKED",
                orderAmount,
                null,
                null,
                auditIdempotencyKey,
                auditDetail);
    }
}
