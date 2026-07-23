package com.influora.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
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
import com.influora.web.dto.meera.MeeraContextDtos.ContextRequest;
import com.influora.web.dto.meera.MeeraContextDtos.ContextResponse;
import com.influora.web.dto.meera.MeeraContextDtos.CreditState;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Platform-AI Phase 1, Wave 1a (Priya A2) — {@code POST /internal/meera/context}. Unit-level:
 * verifies the controller (a) reuses {@link OnBehalfAuthResolver#resolveForWorkspace} (no new
 * auth — the {@code InternalServiceTokenFilter}/HMAC half of the mesh gate is filter-chain-level
 * and out of scope for a controller unit test) and (b) delegates to {@link MeeraContextService}
 * with the SIGNED body's {@code workspace_id}/{@code audience}, never trusting a query param or
 * anything client-side beyond the signed JSON body.
 */
@ExtendWith(MockitoExtension.class)
class MeeraInternalControllerContextTest {

    private static final String WORKSPACE_ID = "01HWXYZWORKSPACE123456";
    private static final String ON_BEHALF_JWT = "signed.jwt.value";

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
    @DisplayName("valid on-behalf JWT for workspace -> resolves via resolveForWorkspace (no scope check) and returns the assembled context")
    void testContextHappyPath() {
        when(onBehalfAuthResolver.resolveForWorkspace(ON_BEHALF_JWT, WORKSPACE_ID))
                .thenReturn(new OnBehalfContext("user1", WORKSPACE_ID, UserType.BRAND, "conv-1"));
        ContextResponse expected =
                new ContextResponse(
                        WORKSPACE_ID,
                        "Acme",
                        null,
                        null,
                        List.of(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        "READY",
                        List.of(),
                        List.of(),
                        new CreditState("metered", 10),
                        null);
        when(contextService.assemble(WORKSPACE_ID, "BRAND")).thenReturn(expected);

        var response = controller.context(ON_BEHALF_JWT, new ContextRequest(WORKSPACE_ID, "BRAND"));

        assertEquals(200, response.getStatusCode().value());
        assertEquals(expected, response.getBody().data());
        verify(onBehalfAuthResolver).resolveForWorkspace(ON_BEHALF_JWT, WORKSPACE_ID);
        verify(contextService).assemble(WORKSPACE_ID, "BRAND");
    }

    @Test
    @DisplayName("on-behalf JWT workspace mismatch -> rejected before contextService is ever called")
    void testContextRejectsWorkspaceMismatch() {
        when(onBehalfAuthResolver.resolveForWorkspace(ON_BEHALF_JWT, WORKSPACE_ID))
                .thenThrow(
                        new ApiException(
                                "ON_BEHALF_WORKSPACE_MISMATCH",
                                "mismatch",
                                org.springframework.http.HttpStatus.FORBIDDEN));

        assertThrows(
                ApiException.class,
                () -> controller.context(ON_BEHALF_JWT, new ContextRequest(WORKSPACE_ID, "BRAND")));

        org.mockito.Mockito.verifyNoInteractions(contextService);
    }
}
