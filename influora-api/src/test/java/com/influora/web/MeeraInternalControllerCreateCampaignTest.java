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
import com.influora.web.dto.meera.MeeraToolDtos.CreateCampaignResult;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * BUG FIX (live 2026-07-23, right after the M-1 on-behalf scope fix let Meera reach {@code
 * create_campaign}): {@code POST /internal/meera/create_campaign} was throwing a 409 (MySQL
 * {@code Column 'conversation_id' cannot be null}) because the body's optional {@code
 * conversation_id} (see {@link MeeraInternalController} class javadoc — the create_campaign tool
 * body is raw AI-proposed input + {@code workspace_id} only, no {@code conversation_id} field at
 * all) was fed straight into {@link CreateCampaignExecutor#execute}, which persists it into {@code
 * campaign_intents.conversation_id} (NOT NULL, FK {@code ai_conversations(id)} —
 * {@code V13__campaign_intents.sql}).
 *
 * <p>This test pins the fix: the controller must source {@code conversationId} from {@link
 * OnBehalfContext#conversationId()} (the JWT-verified, server-minted claim — see {@link
 * OnBehalfAuthResolver} class javadoc) and must do so EVEN WHEN the request body carries no {@code
 * conversation_id} key, and even when the body carries a different/spoofed one — the JWT-derived
 * value always wins, per the tenant-safety requirement that a client-body value must never be
 * trusted over the signed on-behalf token.
 */
@ExtendWith(MockitoExtension.class)
class MeeraInternalControllerCreateCampaignTest {

    private static final String WORKSPACE_ID = "01HWXYZWORKSPACE123456";
    private static final String CONVERSATION_ID = "01HWXYZCONVERSATION123";
    private static final String USER_ID = "01HWXYZUSER1234567890A";
    private static final String ON_BEHALF_JWT = "signed.jwt.value";
    private static final String IDEMPOTENCY_KEY = "meera.tool.idem-key-1";

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

    @Test
    @DisplayName(
            "create_campaign: body has NO conversation_id key at all (the real production shape) --"
                    + " executor is still called with the JWT-derived conversationId, not null")
    void testCreateCampaignSourcesConversationIdFromJwtWhenBodyOmitsIt() {
        OnBehalfContext ctx = new OnBehalfContext(USER_ID, WORKSPACE_ID, UserType.BRAND, CONVERSATION_ID);
        when(onBehalfAuthResolver.resolveForWorkspaceRequiringScope(
                        ON_BEHALF_JWT, WORKSPACE_ID, MeeraToolName.create_campaign.name()))
                .thenReturn(ctx);
        when(toolCallValidator.validateAndResolve(MeeraToolName.create_campaign.name(), WORKSPACE_ID))
                .thenReturn(MeeraToolName.create_campaign);
        when(createCampaignExecutor.execute(
                        eq(WORKSPACE_ID), anyString(), eq(USER_ID), eq(IDEMPOTENCY_KEY), any()))
                .thenReturn(new CreateCampaignResult("campaign-1", "intent-1", "DRAFT", false));

        // Deliberately no "conversation_id" entry -- matches spring.py's real wire contract for
        // tool-call bodies (workspace_id merged in, nothing else).
        Map<String, Object> body =
                Map.of("workspace_id", WORKSPACE_ID, "product_name", "Widget", "creator_count", 3);

        var response = controller.createCampaign(ON_BEHALF_JWT, IDEMPOTENCY_KEY, body);

        assertEquals(201, response.getStatusCode().value());
        verify(createCampaignExecutor)
                .execute(WORKSPACE_ID, CONVERSATION_ID, USER_ID, IDEMPOTENCY_KEY, body);
    }

    @Test
    @DisplayName(
            "create_campaign: body carries a DIFFERENT/spoofed conversation_id -- the JWT-derived"
                    + " value from OnBehalfContext still wins (tenant-safety: never trust a client-body"
                    + " conversation_id over the signed on-behalf token)")
    void testCreateCampaignIgnoresBodyConversationIdEvenWhenPresent() {
        OnBehalfContext ctx = new OnBehalfContext(USER_ID, WORKSPACE_ID, UserType.BRAND, CONVERSATION_ID);
        when(onBehalfAuthResolver.resolveForWorkspaceRequiringScope(
                        ON_BEHALF_JWT, WORKSPACE_ID, MeeraToolName.create_campaign.name()))
                .thenReturn(ctx);
        when(toolCallValidator.validateAndResolve(MeeraToolName.create_campaign.name(), WORKSPACE_ID))
                .thenReturn(MeeraToolName.create_campaign);
        when(createCampaignExecutor.execute(
                        eq(WORKSPACE_ID), anyString(), eq(USER_ID), eq(IDEMPOTENCY_KEY), any()))
                .thenReturn(new CreateCampaignResult("campaign-1", "intent-1", "DRAFT", false));

        Map<String, Object> body =
                Map.of(
                        "workspace_id", WORKSPACE_ID,
                        "conversation_id", "some-other-workspaces-conversation-id",
                        "product_name", "Widget");

        controller.createCampaign(ON_BEHALF_JWT, IDEMPOTENCY_KEY, body);

        verify(createCampaignExecutor)
                .execute(WORKSPACE_ID, CONVERSATION_ID, USER_ID, IDEMPOTENCY_KEY, body);
    }
}
