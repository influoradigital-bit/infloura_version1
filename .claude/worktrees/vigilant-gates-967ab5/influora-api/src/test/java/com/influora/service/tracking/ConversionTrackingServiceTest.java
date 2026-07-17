package com.influora.service.tracking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.UtmCampaign;
import com.influora.repository.UtmCampaignRepository;
import com.influora.service.AuditLogService;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Phase 4 UTM/Coupon Tracking: unit tests for ConversionTrackingService -- UTM-not-found handling,
 * order-amount validation, and correct increment behavior for the newly-added {@code
 * UtmCampaign#incrementConversionCount}/{@code addRevenue} methods (this pass is what first wires
 * anything to call them -- see that entity's javadoc "scope cut" note).
 */
@ExtendWith(MockitoExtension.class)
class ConversionTrackingServiceTest {

    private static final String UTM_ID = "01HUTM1234567890ABCDE";
    private static final String CAMPAIGN_ID = "01HCAMPAIGN123456789A";
    private static final String CREATOR_PROFILE_ID = "01HCREATORPROFILE1234";
    private static final String ORDER_ID = "order-7001";

    @Mock private UtmCampaignRepository utmCampaignRepository;
    @Mock private AuditLogService auditLogService;

    private ConversionTrackingService service;

    @BeforeEach
    void setUp() {
        service = new ConversionTrackingService(utmCampaignRepository, auditLogService);
    }

    @Test
    @DisplayName("recordConversion: UTM_NOT_FOUND (404) when the tracking link id does not exist")
    void testUtmNotFound() {
        when(utmCampaignRepository.findById(UTM_ID)).thenReturn(Optional.empty());

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.recordConversion(UTM_ID, ORDER_ID, BigDecimal.valueOf(100)));

        assertEquals("UTM_NOT_FOUND", ex.getCode());
        assertEquals(404, ex.getStatus().value());
        verify(utmCampaignRepository, never()).save(any());
        verifyNoInteractions(auditLogService);
    }

    @Test
    @DisplayName("recordConversion: rejects a null orderAmount without saving or auditing")
    void testRejectsNullOrderAmount() {
        UtmCampaign utm = utm();
        when(utmCampaignRepository.findById(UTM_ID)).thenReturn(Optional.of(utm));

        ApiException ex =
                assertThrows(
                        ApiException.class, () -> service.recordConversion(UTM_ID, ORDER_ID, null));

        assertEquals("ORDER_AMOUNT_INVALID", ex.getCode());
        assertEquals(400, ex.getStatus().value());
        assertEquals(0, utm.getConversionCount());
        verify(utmCampaignRepository, never()).save(any());
        verifyNoInteractions(auditLogService);
    }

    @Test
    @DisplayName("recordConversion: rejects a negative orderAmount without saving or auditing")
    void testRejectsNegativeOrderAmount() {
        UtmCampaign utm = utm();
        when(utmCampaignRepository.findById(UTM_ID)).thenReturn(Optional.of(utm));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.recordConversion(UTM_ID, ORDER_ID, BigDecimal.valueOf(-1)));

        assertEquals("ORDER_AMOUNT_INVALID", ex.getCode());
        verify(utmCampaignRepository, never()).save(any());
        verifyNoInteractions(auditLogService);
    }

    @Test
    @DisplayName("recordConversion: increments conversionCount by exactly one per call")
    void testIncrementsConversionCountByOne() {
        UtmCampaign utm = utm();
        when(utmCampaignRepository.findById(UTM_ID)).thenReturn(Optional.of(utm));

        service.recordConversion(UTM_ID, ORDER_ID, BigDecimal.valueOf(150));

        assertEquals(1, utm.getConversionCount());
    }

    @Test
    @DisplayName("recordConversion: adds orderAmount to revenueAttributed (running total, not overwrite)")
    void testAddsToRevenueRunningTotal() {
        UtmCampaign utm = utm();
        when(utmCampaignRepository.findById(UTM_ID)).thenReturn(Optional.of(utm));

        service.recordConversion(UTM_ID, ORDER_ID, BigDecimal.valueOf(150));
        service.recordConversion(UTM_ID, "order-7002", BigDecimal.valueOf(50));

        assertEquals(2, utm.getConversionCount());
        assertEquals(0, BigDecimal.valueOf(200).compareTo(utm.getRevenueAttributed()));
    }

    @Test
    @DisplayName("recordConversion: persists the mutated UtmCampaign via save")
    void testSavesMutatedUtmCampaign() {
        UtmCampaign utm = utm();
        when(utmCampaignRepository.findById(UTM_ID)).thenReturn(Optional.of(utm));

        service.recordConversion(UTM_ID, ORDER_ID, BigDecimal.valueOf(150));

        ArgumentCaptor<UtmCampaign> captor = ArgumentCaptor.forClass(UtmCampaign.class);
        verify(utmCampaignRepository).save(captor.capture());
        assertEquals(1, captor.getValue().getConversionCount());
        assertEquals(0, BigDecimal.valueOf(150).compareTo(captor.getValue().getRevenueAttributed()));
    }

    @Test
    @DisplayName(
            "recordConversion: audit-logs CONVERSION_TRACKED with workspaceId null (UtmCampaign has no"
                    + " workspace_id column), orderAmount as serverAmount, before/after balances null")
    void testAuditLogsConversionTracked() {
        UtmCampaign utm = utm();
        when(utmCampaignRepository.findById(UTM_ID)).thenReturn(Optional.of(utm));

        service.recordConversion(UTM_ID, ORDER_ID, BigDecimal.valueOf(150));

        ArgumentCaptor<BigDecimal> amountCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(auditLogService)
                .recordMoneyEvent(
                        isNull(),
                        eq("CONVERSION_TRACKED"),
                        amountCaptor.capture(),
                        isNull(),
                        isNull(),
                        eq("conv:" + CAMPAIGN_ID + ":" + ORDER_ID),
                        anyMap());
        assertEquals(0, BigDecimal.valueOf(150).compareTo(amountCaptor.getValue()));
    }

    private static UtmCampaign utm() {
        return UtmCampaign.builder()
                .id(UTM_ID)
                .campaignId(CAMPAIGN_ID)
                .collaborationId("01HCOLLAB1234567890AB")
                .creatorProfileId(CREATOR_PROFILE_ID)
                .baseUrl("https://example.com")
                .build();
    }
}
