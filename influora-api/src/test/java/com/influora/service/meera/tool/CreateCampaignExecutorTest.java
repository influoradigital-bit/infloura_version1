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
import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.CampaignIntent;
import com.influora.domain.entity.CampaignTemplate;
import com.influora.domain.entity.MeeraToolCall;
import com.influora.domain.enums.CampaignIntentType;
import com.influora.domain.enums.CampaignStatus;
import com.influora.domain.enums.CampaignTemplateCategory;
import com.influora.domain.enums.CampaignTemplateScope;
import com.influora.repository.CampaignIntentRepository;
import com.influora.repository.CampaignRepository;
import com.influora.repository.MeeraToolCallRepository;
import com.influora.service.AuditLogService;
import com.influora.service.CampaignTemplateService;
import com.influora.service.IdempotencyService;
import com.influora.service.IntegrationHealthService;
import com.influora.web.dto.meera.MeeraToolDtos.CreateCampaignResult;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Wave D task D3: {@code CreateCampaignExecutor} must reject drafting a {@code DIRECT}
 * ("sale"/conversion-shaped) campaign when the workspace has no active store integration, while
 * leaving {@code HYPE}/{@code REVIEW}/{@code STANDARD} campaign creation unaffected by
 * integration status.
 */
@ExtendWith(MockitoExtension.class)
class CreateCampaignExecutorTest {

    private static final String WORKSPACE_ID = "01HWXYZ123456789012345";
    private static final String CONVERSATION_ID = "01HWXYZ123456789012346";
    private static final String IDEMPOTENCY_KEY = "meera.tool.01HWXYZ123456789012347";
    private static final String USER_ID = "01HWXYZ123456789012348";

    @Mock private CampaignIntentRepository campaignIntentRepository;
    @Mock private CampaignRepository campaignRepository;
    @Mock private MeeraToolCallRepository toolCallRepository;
    @Mock private AuditLogService auditLogService;
    @Mock private IdempotencyService idempotencyService;
    @Mock private CampaignTemplateService campaignTemplateService;
    @Mock private com.influora.service.meera.MeeraInteractionLogService meeraInteractionLogService;

    private CreateCampaignExecutor executor;

    @BeforeEach
    void setUp() {
        executor =
                new CreateCampaignExecutor(
                        campaignIntentRepository,
                        campaignRepository,
                        toolCallRepository,
                        auditLogService,
                        idempotencyService,
                        campaignTemplateService,
                        meeraInteractionLogService);
    }

    // testDirectCampaignRejectedWithoutStoreIntegration removed (P3-20 Vikram fix): the
    // NO_STORE_INTEGRATION gate moved off this Meera executor to CampaignService.java:117-120
    // (the real go-live path). This executor now only drafts campaigns (see javadoc), so it never
    // throws NO_STORE_INTEGRATION. Equivalent coverage lives in
    // CampaignServiceTest.testDirectCampaignRejectedWithoutStoreIntegration (verified present).

    @Test
    @DisplayName("DIRECT (sale) campaign with an active store integration -> succeeds, draft campaign created")
    void testDirectCampaignSucceedsWithStoreIntegration() {
        mockIdempotencyExecuteOnce();
        // NOTE: integrationHealthService removed from constructor
        when(campaignIntentRepository.save(any(CampaignIntent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(campaignRepository.save(any(Campaign.class)))
                .thenAnswer(
                        invocation -> {
                            Campaign c = invocation.getArgument(0);
                            return c;
                        });

        Map<String, Object> input =
                Map.of(
                        "product_name", "Widget",
                        "campaign_type", "DIRECT",
                        "product_price", "999.00",
                        "creator_count", 3);

        CreateCampaignResult result =
                executor.execute(WORKSPACE_ID, CONVERSATION_ID, USER_ID, IDEMPOTENCY_KEY, input);

        assertNotNull(result);
        assertEquals(CampaignStatus.DRAFT.name(), result.status());
        assertEquals(false, result.replay());

        verify(campaignRepository).save(any(Campaign.class));
        verify(toolCallRepository).save(any(MeeraToolCall.class));
        verify(auditLogService)
                .recordToolCall(
                        eq(WORKSPACE_ID),
                        eq("create_campaign"),
                        eq("D"),
                        eq(AuditLogService.OUTCOME_ALLOWED),
                        eq(null),
                        eq(IDEMPOTENCY_KEY),
                        eq(null),
                        any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"HYPE", "REVIEW", "STANDARD"})
    @DisplayName("Non-store-dependent campaign types succeed even with zero integrations connected")
    void testNonStoreDependentTypesIgnoreIntegrationStatus(String campaignType) {
        mockIdempotencyExecuteOnce();
        when(campaignIntentRepository.save(any(CampaignIntent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(campaignRepository.save(any(Campaign.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Map<String, Object> input =
                Map.of(
                        "product_name", "Widget",
                        "campaign_type", campaignType,
                        "creator_count", 3);

        CreateCampaignResult result =
                executor.execute(WORKSPACE_ID, CONVERSATION_ID, USER_ID, IDEMPOTENCY_KEY, input);

        assertNotNull(result);
        assertEquals(CampaignStatus.DRAFT.name(), result.status());

        // NOTE: integrationHealthService removed from constructor
        verify(campaignRepository).save(any(Campaign.class));
    }

    @Test
    @DisplayName("Idempotency: replay returns prior result without re-checking integration health")
    void testIdempotencyReplaySkipsIntegrationCheck() {
        MeeraToolCall existing =
                MeeraToolCall.builder()
                        .id("01HWXYZEXISTING1234567")
                        .workspaceId(WORKSPACE_ID)
                        .conversationId(CONVERSATION_ID)
                        .toolName(com.influora.domain.enums.MeeraToolName.create_campaign)
                        .idempotencyKey(IDEMPOTENCY_KEY)
                        .status(com.influora.domain.enums.ToolCallStatus.EXECUTED)
                        .resultRefType(com.influora.domain.enums.ToolResultRefType.CAMPAIGN)
                        .resultRefId("01HWXYZCAMPAIGN123456")
                        .build();
        when(toolCallRepository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.of(existing));

        Map<String, Object> input =
                Map.of(
                        "product_name", "Widget",
                        "campaign_type", "DIRECT",
                        "creator_count", 3);

        CreateCampaignResult result =
                executor.execute(WORKSPACE_ID, CONVERSATION_ID, USER_ID, IDEMPOTENCY_KEY, input);

        assertNotNull(result);
        assertEquals(true, result.replay());
        // NOTE: integrationHealthService removed from constructor
    }

    // ---------------------------------------------------------------------------------------
    // Platform-AI Phase 1, Wave 1b (Priya A3 + Ash's STANDARD-enum ruling): optional template_id.
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "template_id present -> derives campaign_type from the template row (incl. STANDARD),"
                    + " ignoring any AI-supplied campaign_type, and copies requirements/hashtags/"
                    + "target_audience/brand_guidelines; budget stays null")
    void testTemplateIdPresentDerivesCampaignTypeAndCopiesFields() {
        mockIdempotencyExecuteOnce();
        String templateId = "01HWXYZTEMPLATE1234567";
        CampaignTemplate template =
                CampaignTemplate.builder()
                        .id(templateId)
                        .name("UGC Starter")
                        .category(CampaignTemplateCategory.UGC)
                        .scope(CampaignTemplateScope.SYSTEM)
                        .campaignType(CampaignIntentType.STANDARD) // the UGC SYSTEM preset's real type
                        .requirementsJson("[\"Include tracked link\",\"Disclose partnership\"]")
                        .hashtagsJson("[\"#ad\"]")
                        .targetAudienceJson("{\"ageRange\":\"18-25\"}")
                        .brandGuidelines("Keep it authentic")
                        .build();
        when(campaignTemplateService.requireVisible(templateId, WORKSPACE_ID)).thenReturn(template);
        when(campaignIntentRepository.save(any(CampaignIntent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(campaignRepository.save(any(Campaign.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Map<String, Object> input =
                Map.of(
                        "product_name", "Widget",
                        "campaign_type", "DIRECT", // must be IGNORED — template is the authority
                        "template_id", templateId,
                        "creator_count", 3);

        CreateCampaignResult result =
                executor.execute(WORKSPACE_ID, CONVERSATION_ID, USER_ID, IDEMPOTENCY_KEY, input);

        assertNotNull(result);
        assertEquals(CampaignStatus.DRAFT.name(), result.status());

        org.mockito.ArgumentCaptor<Campaign> campaignCaptor = org.mockito.ArgumentCaptor.forClass(Campaign.class);
        verify(campaignRepository).save(campaignCaptor.capture());
        Campaign saved = campaignCaptor.getValue();
        assertEquals(CampaignIntentType.STANDARD, saved.getCampaignType());
        assertEquals(template.getRequirementsJson(), saved.getRequirementsJson());
        assertEquals(template.getHashtagsJson(), saved.getHashtagsJson());
        assertEquals(template.getTargetAudienceJson(), saved.getTargetAudienceJson());
        assertEquals(template.getBrandGuidelines(), saved.getBrandGuidelines());
        assertEquals(null, saved.getBudgetMin());
        assertEquals(null, saved.getBudgetMax());

        org.mockito.ArgumentCaptor<CampaignIntent> intentCaptor =
                org.mockito.ArgumentCaptor.forClass(CampaignIntent.class);
        // Saved twice by design (initial insert, then confirm() after the campaign id is known) —
        // same pattern the pre-existing tests in this class don't need to assert on explicitly.
        verify(campaignIntentRepository, org.mockito.Mockito.times(2)).save(intentCaptor.capture());
        assertEquals(CampaignIntentType.STANDARD, intentCaptor.getValue().getCampaignType());
    }

    @Test
    @DisplayName("template_id present but not visible to this workspace -> propagates 404, no campaign created")
    void testTemplateIdNotVisiblePropagates404() {
        mockIdempotencyExecuteOnce();
        String templateId = "01HWXYZOTHERWORKSPACE12";
        when(campaignTemplateService.requireVisible(templateId, WORKSPACE_ID))
                .thenThrow(new ApiException("TEMPLATE_NOT_FOUND", "Template not found", org.springframework.http.HttpStatus.NOT_FOUND));

        Map<String, Object> input =
                Map.of("product_name", "Widget", "template_id", templateId, "creator_count", 3);

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> executor.execute(WORKSPACE_ID, CONVERSATION_ID, USER_ID, IDEMPOTENCY_KEY, input));
        assertEquals("TEMPLATE_NOT_FOUND", ex.getCode());
        verify(campaignRepository, never()).save(any(Campaign.class));
    }

    @Test
    @DisplayName("template_id absent -> behavior unchanged: AI-supplied campaign_type used, no template fields copied")
    void testTemplateIdAbsentUnchangedBehavior() {
        mockIdempotencyExecuteOnce();
        when(campaignIntentRepository.save(any(CampaignIntent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(campaignRepository.save(any(Campaign.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Map<String, Object> input =
                Map.of("product_name", "Widget", "campaign_type", "HYPE", "creator_count", 3);

        CreateCampaignResult result =
                executor.execute(WORKSPACE_ID, CONVERSATION_ID, USER_ID, IDEMPOTENCY_KEY, input);

        assertNotNull(result);
        org.mockito.ArgumentCaptor<Campaign> campaignCaptor = org.mockito.ArgumentCaptor.forClass(Campaign.class);
        verify(campaignRepository).save(campaignCaptor.capture());
        Campaign saved = campaignCaptor.getValue();
        assertEquals(CampaignIntentType.HYPE, saved.getCampaignType());
        assertEquals(null, saved.getRequirementsJson());
        assertEquals(null, saved.getHashtagsJson());
        assertEquals(null, saved.getBudgetMin());
        assertEquals(null, saved.getBudgetMax());
        org.mockito.Mockito.verifyNoInteractions(campaignTemplateService);
    }

    @SuppressWarnings("unchecked")
    private void mockIdempotencyExecuteOnce() {
        when(idempotencyService.executeOnce(anyString(), anyString(), anyString(), any()))
                .thenAnswer(
                        invocation -> {
                            Supplier<CreateCampaignResult> supplier = invocation.getArgument(3);
                            return supplier.get();
                        });
    }
}
