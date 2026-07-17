package com.influora.service.meera.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.CampaignIntent;
import com.influora.domain.entity.MeeraToolCall;
import com.influora.domain.enums.CampaignIntentType;
import com.influora.domain.enums.MeeraToolName;
import com.influora.domain.enums.ToolCallStatus;
import com.influora.domain.enums.ToolResultRefType;
import com.influora.repository.MeeraToolCallRepository;
import com.influora.service.AmountDerivationService;
import com.influora.service.AmountDerivationService.DerivedAmount;
import com.influora.service.AuditLogService;
import com.influora.service.IdempotencyService;
import com.influora.web.dto.meera.MeeraToolDtos.RequestPaymentResult;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * P7: Unit tests for RequestPaymentExecutor (16-VIKRAM-REMAINING-TASKS.md).
 * Priority: amount-tamper resistance ([SEC: Kabir G1], [MF-1], [LB-3]).
 */
@ExtendWith(MockitoExtension.class)
class RequestPaymentExecutorTest {

    private static final String WORKSPACE_ID = "01HWXYZ123456789012345";
    private static final String CONVERSATION_ID = "01HWXYZ123456789012346";
    private static final String CAMPAIGN_INTENT_ID = "01HWXYZ123456789012347";
    private static final String IDEMPOTENCY_KEY = "meera.tool.01HWXYZ123456789012348";

    @Mock private AmountDerivationService amountDerivationService;
    @Mock private MeeraToolCallRepository toolCallRepository;
    @Mock private AuditLogService auditLogService;
    @Mock private IdempotencyService idempotencyService;

    private RequestPaymentExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new RequestPaymentExecutor(
                amountDerivationService, toolCallRepository, auditLogService, idempotencyService);
    }

    @Test
    @DisplayName("Amount tampering: AI-proposed amount differs from server-derived -> 409 AMOUNT_MISMATCH")
    void testAmountTamperingRejected() {
        // Server derives Rs.5000 but AI proposes Rs.100 (tampering attempt)
        BigDecimal serverAmount = new BigDecimal("5000.00");
        BigDecimal aiTamperedAmount = new BigDecimal("100.00");

        CampaignIntent mockIntent = CampaignIntent.builder()
                .id(CAMPAIGN_INTENT_ID)
                .workspaceId(WORKSPACE_ID)
                .conversationId(CONVERSATION_ID)
                .campaignType(CampaignIntentType.STANDARD)
                .productPrice(serverAmount)
                .creatorCount(1)
                .build();
        DerivedAmount derived = new DerivedAmount(serverAmount, BigDecimal.ZERO, serverAmount, mockIntent);
        when(amountDerivationService.deriveForCampaignIntent(WORKSPACE_ID, CAMPAIGN_INTENT_ID))
                .thenReturn(derived);
        when(amountDerivationService.exceedsDriftTolerance(serverAmount, aiTamperedAmount))
                .thenReturn(true); // Drift exceeds tolerance

        mockIdempotencyExecuteOnce();

        Map<String, Object> input = Map.of(
                "campaign_intent_id", CAMPAIGN_INTENT_ID,
                "display_amount_hint", aiTamperedAmount.toString());

        ApiException ex = assertThrows(ApiException.class, () ->
                executor.execute(WORKSPACE_ID, CONVERSATION_ID, IDEMPOTENCY_KEY, input));

        assertEquals("AMOUNT_MISMATCH", ex.getCode());
        assertEquals(409, ex.getStatus().value());

        // Verify audit log was written for the rejection
        verify(auditLogService).recordToolCall(
                eq(WORKSPACE_ID),
                eq("request_payment"),
                eq("C"),
                eq(AuditLogService.OUTCOME_REJECTED),
                eq("AMOUNT_MISMATCH"),
                eq(IDEMPOTENCY_KEY),
                eq(serverAmount),
                any());

        // Verify no tool call was saved (transaction should rollback)
        verify(toolCallRepository, never()).save(any(MeeraToolCall.class));
    }

    @Test
    @DisplayName("Valid request: server-derived amount accepted, returns PENDING_CONFIRM")
    void testValidRequestReturnsPendingConfirm() {
        BigDecimal serverAmount = new BigDecimal("5000.00");
        BigDecimal aiHint = new BigDecimal("5000.00");

        CampaignIntent mockIntent = CampaignIntent.builder()
                .id(CAMPAIGN_INTENT_ID)
                .workspaceId(WORKSPACE_ID)
                .conversationId(CONVERSATION_ID)
                .campaignType(CampaignIntentType.STANDARD)
                .productPrice(serverAmount)
                .creatorCount(1)
                .build();
        DerivedAmount derived = new DerivedAmount(serverAmount, BigDecimal.ZERO, serverAmount, mockIntent);
        when(amountDerivationService.deriveForCampaignIntent(WORKSPACE_ID, CAMPAIGN_INTENT_ID))
                .thenReturn(derived);
        when(amountDerivationService.exceedsDriftTolerance(serverAmount, aiHint))
                .thenReturn(false); // Within tolerance

        mockIdempotencyExecuteOnce();

        Map<String, Object> input = Map.of(
                "campaign_intent_id", CAMPAIGN_INTENT_ID,
                "display_amount_hint", aiHint.toString());

        RequestPaymentResult result =
                executor.execute(WORKSPACE_ID, CONVERSATION_ID, IDEMPOTENCY_KEY, input);

        assertNotNull(result);
        assertEquals("PENDING_CONFIRM", result.status());
        assertEquals(CAMPAIGN_INTENT_ID, result.campaignIntentId());
        assertEquals(serverAmount, result.serverAmount());
        assertEquals("INR", result.currency());
        assertEquals(false, result.replay());

        // Verify tool call was saved
        verify(toolCallRepository).save(any(MeeraToolCall.class));

        // Verify success audit log
        verify(auditLogService).recordToolCall(
                eq(WORKSPACE_ID),
                eq("request_payment"),
                eq("C"),
                eq(AuditLogService.OUTCOME_ALLOWED),
                eq(null),
                eq(IDEMPOTENCY_KEY),
                eq(serverAmount),
                any());
    }

    @Test
    @DisplayName("Idempotency: replay returns prior result without re-executing")
    void testIdempotencyReplayReturnsPriorResult() {
        BigDecimal serverAmount = new BigDecimal("5000.00");

        // Simulate existing tool call in DB
        MeeraToolCall existing = MeeraToolCall.builder()
                .id("01HWXYZEXISTING1234567")
                .workspaceId(WORKSPACE_ID)
                .conversationId(CONVERSATION_ID)
                .toolName(MeeraToolName.request_payment)
                .idempotencyKey(IDEMPOTENCY_KEY)
                .status(ToolCallStatus.EXECUTED)
                .resultRefType(ToolResultRefType.INTENT)
                .resultRefId(CAMPAIGN_INTENT_ID)
                .serverAmount(serverAmount)
                .build();

        when(toolCallRepository.findByIdempotencyKey(IDEMPOTENCY_KEY))
                .thenReturn(Optional.of(existing));

        Map<String, Object> input = Map.of(
                "campaign_intent_id", CAMPAIGN_INTENT_ID,
                "display_amount_hint", "5000.00");

        RequestPaymentResult result =
                executor.execute(WORKSPACE_ID, CONVERSATION_ID, IDEMPOTENCY_KEY, input);

        assertNotNull(result);
        assertEquals("PENDING_CONFIRM", result.status());
        assertEquals(serverAmount, result.serverAmount());
        assertEquals(true, result.replay()); // This is a replay

        // Verify no new derivation or save occurred
        verify(amountDerivationService, never()).deriveForCampaignIntent(any(), any());
        verify(toolCallRepository, never()).save(any(MeeraToolCall.class));
    }

    @Test
    @DisplayName("Missing campaign_intent_id: 400 BAD_REQUEST")
    void testMissingCampaignIntentIdRejected() {
        mockIdempotencyExecuteOnce();

        Map<String, Object> input = Map.of("display_amount_hint", "5000.00");

        ApiException ex = assertThrows(ApiException.class, () ->
                executor.execute(WORKSPACE_ID, CONVERSATION_ID, IDEMPOTENCY_KEY, input));

        assertEquals("CAMPAIGN_INTENT_ID_REQUIRED", ex.getCode());
        assertEquals(400, ex.getStatus().value());
    }

    @Test
    @DisplayName("Tenant mismatch on replay: idempotency key from different workspace rejected")
    void testTenantMismatchOnReplayRejected() {
        String differentWorkspaceId = "01HWXYZDIFFERENT12345";
        BigDecimal serverAmount = new BigDecimal("5000.00");

        MeeraToolCall existing = MeeraToolCall.builder()
                .id("01HWXYZEXISTING1234567")
                .workspaceId(differentWorkspaceId) // Different workspace!
                .conversationId(CONVERSATION_ID)
                .toolName(MeeraToolName.request_payment)
                .idempotencyKey(IDEMPOTENCY_KEY)
                .status(ToolCallStatus.EXECUTED)
                .resultRefType(ToolResultRefType.INTENT)
                .resultRefId(CAMPAIGN_INTENT_ID)
                .serverAmount(serverAmount)
                .build();

        when(toolCallRepository.findByIdempotencyKey(IDEMPOTENCY_KEY))
                .thenReturn(Optional.of(existing));

        Map<String, Object> input = Map.of(
                "campaign_intent_id", CAMPAIGN_INTENT_ID,
                "display_amount_hint", "5000.00");

        ApiException ex = assertThrows(ApiException.class, () ->
                executor.execute(WORKSPACE_ID, CONVERSATION_ID, IDEMPOTENCY_KEY, input));

        assertEquals("IDEMPOTENCY_KEY_TENANT_MISMATCH", ex.getCode());
        assertEquals(409, ex.getStatus().value());
    }

    @SuppressWarnings("unchecked")
    private void mockIdempotencyExecuteOnce() {
        // Mock idempotency service to just execute the supplier
        when(idempotencyService.executeOnce(anyString(), anyString(), anyString(), any()))
                .thenAnswer(invocation -> {
                    Supplier<RequestPaymentResult> supplier = invocation.getArgument(3);
                    return supplier.get();
                });
    }
}
