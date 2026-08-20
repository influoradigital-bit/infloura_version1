package com.influora.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.domain.enums.MeeraToolName;
import com.influora.domain.enums.UserType;
import com.influora.security.OnBehalfAuthResolver;
import com.influora.security.OnBehalfAuthResolver.OnBehalfContext;
import com.influora.service.brand.AnalyzeSiteTriggerService;
import com.influora.service.meera.MeeraContextService;
import com.influora.service.meera.MeeraSessionService;
import com.influora.service.meera.tool.CalculateBudgetExecutor;
import com.influora.service.meera.tool.ConfirmLaunchExecutor;
import com.influora.service.meera.tool.CreateCampaignExecutor;
import com.influora.service.meera.tool.GetCampaignPerformanceExecutor;
import com.influora.service.meera.tool.RequestPaymentExecutor;
import com.influora.service.meera.tool.ShowCreatorsExecutor;
import com.influora.service.meera.tool.ToolCallValidator;
import com.influora.web.dto.meera.MeeraToolDtos.ConfirmLaunchResult;
import com.influora.web.dto.meera.MeeraToolDtos.RequestPaymentResult;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * BUG FIX (2026-07-23 follow-up to the {@code create_campaign} conversation_id fix — see {@link
 * MeeraInternalControllerCreateCampaignTest}): the two remaining C-tier tool routes, {@code
 * request_payment} and {@code confirm_launch}, were still sourcing {@code conversationId} from the
 * request body via {@code conversationIdOf(body)}. Per {@link MeeraInternalController}'s class
 * javadoc the tool-call body is raw AI-proposed input + {@code workspace_id} only and never carries
 * a {@code conversation_id} field, so that value was always {@code null} — leaving both routes'
 * {@code meera_tool_calls} ledger rows (and, for {@code confirm_launch}, the fire-and-forget {@code
 * DRAFT_FUNDED} flywheel event's {@code sessionId}) with no conversation link.
 *
 * <p>These tests pin the fix: both controller routes must source {@code conversationId} from {@link
 * OnBehalfContext#conversationId()} (the JWT-verified, server-minted claim — see {@link
 * OnBehalfAuthResolver} class javadoc), EVEN WHEN the body carries no {@code conversation_id} key
 * (the real production shape) and EVEN WHEN the body carries a different/spoofed one — the
 * JWT-derived value always wins, never a client-influenced body value.
 */
@ExtendWith(MockitoExtension.class)
class MeeraInternalControllerConversationIdTest {

    private static final String WORKSPACE_ID = "01HWXYZWORKSPACE123456";
    private static final String CONVERSATION_ID = "01HWXYZCONVERSATION123";
    private static final String USER_ID = "01HWXYZUSER1234567890A";
    private static final String ON_BEHALF_JWT = "signed.jwt.value";
    private static final String IDEMPOTENCY_KEY = "meera.tool.idem-key-1";
    private static final String SPOOFED_CONVERSATION_ID = "some-other-workspaces-conversation-id";

    @Mock private OnBehalfAuthResolver onBehalfAuthResolver;
    @Mock private ToolCallValidator toolCallValidator;
    @Mock private ShowCreatorsExecutor showCreatorsExecutor;
    @Mock private CalculateBudgetExecutor calculateBudgetExecutor;
    @Mock private CreateCampaignExecutor createCampaignExecutor;
    @Mock private RequestPaymentExecutor requestPaymentExecutor;
    @Mock private ConfirmLaunchExecutor confirmLaunchExecutor;
    @Mock private GetCampaignPerformanceExecutor getCampaignPerformanceExecutor;
    @Mock private MeeraSessionService sessionService;
    @Mock private AnalyzeSiteTriggerService analyzeSiteTriggerService;
    @Mock private MeeraContextService contextService;
    @Mock private com.influora.service.meera.MeeraInteractionLogService meeraInteractionLogService;

    private MeeraInternalController controller;

    @BeforeEach
    void setUp() {
        controller =
                new MeeraInternalController(
                        onBehalfAuthResolver,
                        toolCallValidator,
                        showCreatorsExecutor,
                        calculateBudgetExecutor,
                        createCampaignExecutor,
                        requestPaymentExecutor,
                        confirmLaunchExecutor,
                        getCampaignPerformanceExecutor,
                        sessionService,
                        analyzeSiteTriggerService,
                        contextService,
                        meeraInteractionLogService);
    }

    private OnBehalfContext ctxWithConversation() {
        return new OnBehalfContext(USER_ID, WORKSPACE_ID, UserType.BRAND, CONVERSATION_ID);
    }

    private void stubElevatedScope(MeeraToolName tool) {
        when(onBehalfAuthResolver.resolveForWorkspaceRequiringElevatedRoleAndScope(
                        ON_BEHALF_JWT, WORKSPACE_ID, tool.name()))
                .thenReturn(ctxWithConversation());
        when(toolCallValidator.validateAndResolve(tool.name(), WORKSPACE_ID)).thenReturn(tool);
    }

    // --- request_payment ---------------------------------------------------

    @Test
    @DisplayName(
            "request_payment: body has NO conversation_id key (the real production shape) -- executor"
                    + " is called with the JWT-derived conversationId, not null")
    void testRequestPaymentSourcesConversationIdFromJwtWhenBodyOmitsIt() {
        stubElevatedScope(MeeraToolName.request_payment);
        when(requestPaymentExecutor.execute(eq(WORKSPACE_ID), anyString(), eq(IDEMPOTENCY_KEY), any()))
                .thenReturn(
                        new RequestPaymentResult(
                                "PENDING_CONFIRM", "intent-1", new BigDecimal("100.00"), "INR", "/url", false));

        // Deliberately no "conversation_id" entry -- matches spring.py's real wire contract for
        // tool-call bodies (workspace_id merged in, nothing else).
        Map<String, Object> body =
                Map.of("workspace_id", WORKSPACE_ID, "campaign_intent_id", "intent-1");

        var response = controller.requestPayment(ON_BEHALF_JWT, IDEMPOTENCY_KEY, body);

        assertEquals(200, response.getStatusCode().value());
        verify(requestPaymentExecutor).execute(WORKSPACE_ID, CONVERSATION_ID, IDEMPOTENCY_KEY, body);
    }

    @Test
    @DisplayName(
            "request_payment: body carries a DIFFERENT/spoofed conversation_id -- the JWT-derived value"
                    + " from OnBehalfContext still wins (never trust a client-body conversation_id)")
    void testRequestPaymentIgnoresBodyConversationIdEvenWhenPresent() {
        stubElevatedScope(MeeraToolName.request_payment);
        when(requestPaymentExecutor.execute(eq(WORKSPACE_ID), anyString(), eq(IDEMPOTENCY_KEY), any()))
                .thenReturn(
                        new RequestPaymentResult(
                                "PENDING_CONFIRM", "intent-1", new BigDecimal("100.00"), "INR", "/url", false));

        Map<String, Object> body =
                Map.of(
                        "workspace_id", WORKSPACE_ID,
                        "conversation_id", SPOOFED_CONVERSATION_ID,
                        "campaign_intent_id", "intent-1");

        controller.requestPayment(ON_BEHALF_JWT, IDEMPOTENCY_KEY, body);

        verify(requestPaymentExecutor).execute(WORKSPACE_ID, CONVERSATION_ID, IDEMPOTENCY_KEY, body);
    }

    // --- confirm_launch ----------------------------------------------------

    @Test
    @DisplayName(
            "confirm_launch: body has NO conversation_id key (the real production shape) -- executor is"
                    + " called with the JWT-derived conversationId, not null")
    void testConfirmLaunchSourcesConversationIdFromJwtWhenBodyOmitsIt() {
        stubElevatedScope(MeeraToolName.confirm_launch);
        when(confirmLaunchExecutor.execute(eq(WORKSPACE_ID), anyString(), eq(IDEMPOTENCY_KEY), any()))
                .thenReturn(new ConfirmLaunchResult("campaign-1", "ACTIVE", 3, false));

        Map<String, Object> body =
                Map.of("workspace_id", WORKSPACE_ID, "campaign_intent_id", "intent-1");

        var response = controller.confirmLaunch(ON_BEHALF_JWT, IDEMPOTENCY_KEY, body);

        assertEquals(200, response.getStatusCode().value());
        verify(confirmLaunchExecutor).execute(WORKSPACE_ID, CONVERSATION_ID, IDEMPOTENCY_KEY, body);
    }

    @Test
    @DisplayName(
            "confirm_launch: body carries a DIFFERENT/spoofed conversation_id -- the JWT-derived value"
                    + " from OnBehalfContext still wins (never trust a client-body conversation_id)")
    void testConfirmLaunchIgnoresBodyConversationIdEvenWhenPresent() {
        stubElevatedScope(MeeraToolName.confirm_launch);
        when(confirmLaunchExecutor.execute(eq(WORKSPACE_ID), anyString(), eq(IDEMPOTENCY_KEY), any()))
                .thenReturn(new ConfirmLaunchResult("campaign-1", "ACTIVE", 3, false));

        Map<String, Object> body =
                Map.of(
                        "workspace_id", WORKSPACE_ID,
                        "conversation_id", SPOOFED_CONVERSATION_ID,
                        "campaign_intent_id", "intent-1");

        controller.confirmLaunch(ON_BEHALF_JWT, IDEMPOTENCY_KEY, body);

        verify(confirmLaunchExecutor).execute(WORKSPACE_ID, CONVERSATION_ID, IDEMPOTENCY_KEY, body);
    }
}
