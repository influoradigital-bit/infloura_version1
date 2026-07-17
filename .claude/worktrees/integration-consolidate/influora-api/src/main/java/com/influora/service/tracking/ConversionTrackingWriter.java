package com.influora.service.tracking;

import com.influora.common.ApiException;
import com.influora.domain.entity.UtmCampaign;
import com.influora.repository.UtmCampaignRepository;
import com.influora.service.AuditLogService;
import java.math.BigDecimal;
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

        auditLogService.recordMoneyEvent(
                null,
                "CONVERSION_TRACKED",
                orderAmount,
                null,
                null,
                auditIdempotencyKey,
                Map.of(
                        "utmCampaignId", utmCampaignId,
                        "campaignId", utm.getCampaignId(),
                        "creatorId", utm.getCreatorProfileId(),
                        "orderId", orderId == null ? "" : orderId));
    }
}
