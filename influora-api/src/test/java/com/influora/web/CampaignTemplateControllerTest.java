package com.influora.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.CampaignTemplate;
import com.influora.domain.entity.Plan;
import com.influora.domain.entity.Workspace;
import com.influora.domain.enums.CampaignTemplateCategory;
import com.influora.domain.enums.CampaignTemplateScope;
import com.influora.domain.enums.PlanFeature;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CampaignTemplateRepository;
import com.influora.security.AuthPrincipal;
import com.influora.security.PlanGateFilter;
import com.influora.security.PlanGateInterceptor;
import com.influora.service.BrandContextService;
import com.influora.service.CampaignTemplateService;
import com.influora.web.dto.campaigntemplate.CampaignTemplateDtos.CampaignTemplateResponse;
import com.influora.web.dto.campaigntemplate.CampaignTemplateDtos.SaveAsTemplateRequest;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

/**
 * BR-14 — {@link CampaignTemplateController} tests. Wires a *real* {@link CampaignTemplateService}
 * against mocked repositories/{@link BrandContextService} (rather than mocking the service
 * itself, the "mock the collaborators" convention used elsewhere in this test package) because the
 * behavior under test here — the {@code requireVisible} 404-not-403 cross-workspace guard and the
 * SYSTEM_TEMPLATE_IMMUTABLE check — lives in the service, and a fully-mocked service would only
 * prove the controller doesn't swallow exceptions, not that the guard itself is correct.
 *
 * <p>The 402 upgrade-gate case exercises the real {@link PlanGateInterceptor} against a real
 * {@link HandlerMethod} resolved from {@code CampaignTemplateController.saveAsTemplate} — proving
 * the {@code @RequiresPlan(CAMPAIGN_TEMPLATES)} annotation on the actual endpoint is gated live,
 * not just the generic fake-controller mechanism {@code PlanGateInterceptorTest} covers.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CampaignTemplateControllerTest {

    private static final String WORKSPACE_ID = "01HWORKSPACE12345678A";
    private static final String OTHER_WORKSPACE_ID = "01HOTHERWORKSPACE1234";
    private static final String TEMPLATE_ID = "01HTEMPLATE1234567890";

    @Mock private CampaignTemplateRepository templateRepository;
    @Mock private CampaignRepository campaignRepository;
    @Mock private BrandContextService brandContext;
    @Mock private AuthPrincipal principal;

    private CampaignTemplateController controller;
    private Workspace workspace;

    @BeforeEach
    void setUp() {
        CampaignTemplateService service =
                new CampaignTemplateService(templateRepository, campaignRepository, brandContext);
        controller = new CampaignTemplateController(service);

        workspace = Workspace.newBrand(WORKSPACE_ID, "Acme Co", "acme-co", "Retail", "1-10");
        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
    }

    private static CampaignTemplate systemTemplate() {
        return CampaignTemplate.builder()
                .id(TEMPLATE_ID)
                .name("Editor's Pick")
                .category(CampaignTemplateCategory.AWARENESS)
                .scope(CampaignTemplateScope.SYSTEM)
                .build();
    }

    private static CampaignTemplate customTemplate(String ownerWorkspaceId) {
        return CampaignTemplate.builder()
                .id(TEMPLATE_ID)
                .name("My Template")
                .category(CampaignTemplateCategory.CUSTOM)
                .scope(CampaignTemplateScope.CUSTOM)
                .workspaceId(ownerWorkspaceId)
                .createdBy("user-001")
                .build();
    }

    @Test
    @DisplayName("GET /campaign-templates: lists SYSTEM presets plus only the caller's own CUSTOM templates")
    void list_returnsSystemAndOwnWorkspaceTemplates() {
        CampaignTemplate system = systemTemplate();
        CampaignTemplate own = customTemplate(WORKSPACE_ID);
        when(templateRepository.findByScope(CampaignTemplateScope.SYSTEM)).thenReturn(List.of(system));
        when(templateRepository.findByScopeAndWorkspaceId(CampaignTemplateScope.CUSTOM, WORKSPACE_ID))
                .thenReturn(List.of(own));

        ResponseEntity<com.influora.common.ApiResponse<List<CampaignTemplateResponse>>> response =
                controller.list(principal);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        List<CampaignTemplateResponse> body = response.getBody().data();
        assertEquals(2, body.size());
        assertTrue(body.stream().anyMatch(t -> t.id().equals(TEMPLATE_ID)));
    }

    @Test
    @DisplayName("GET /campaign-templates/{id}: SYSTEM template is visible regardless of caller's workspace")
    void get_systemTemplateVisibleToAnyWorkspace() {
        when(templateRepository.findById(TEMPLATE_ID)).thenReturn(Optional.of(systemTemplate()));

        ResponseEntity<com.influora.common.ApiResponse<CampaignTemplateResponse>> response =
                controller.get(principal, TEMPLATE_ID);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(TEMPLATE_ID, response.getBody().data().id());
    }

    @Test
    @DisplayName("GET /campaign-templates/{id}: a CUSTOM template owned by a different workspace is 404, never 403")
    void get_crossWorkspaceCustomTemplateIsNotFoundNotForbidden() {
        when(templateRepository.findById(TEMPLATE_ID))
                .thenReturn(Optional.of(customTemplate(OTHER_WORKSPACE_ID)));

        ApiException ex =
                assertThrows(ApiException.class, () -> controller.get(principal, TEMPLATE_ID));

        assertEquals("TEMPLATE_NOT_FOUND", ex.getCode());
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }

    @Test
    @DisplayName("GET /campaign-templates/{id}: the owning workspace's own CUSTOM template is visible")
    void get_ownCustomTemplateIsVisible() {
        when(templateRepository.findById(TEMPLATE_ID))
                .thenReturn(Optional.of(customTemplate(WORKSPACE_ID)));

        ResponseEntity<com.influora.common.ApiResponse<CampaignTemplateResponse>> response =
                controller.get(principal, TEMPLATE_ID);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(TEMPLATE_ID, response.getBody().data().id());
    }

    @Test
    @DisplayName("DELETE /campaign-templates/{id}: SYSTEM template is immutable — 400 SYSTEM_TEMPLATE_IMMUTABLE")
    void delete_systemTemplateRejectedAsImmutable() {
        when(templateRepository.findById(TEMPLATE_ID)).thenReturn(Optional.of(systemTemplate()));

        ApiException ex = assertThrows(ApiException.class, () -> controller.delete(principal, TEMPLATE_ID));

        assertEquals("SYSTEM_TEMPLATE_IMMUTABLE", ex.getCode());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    @DisplayName("DELETE /campaign-templates/{id}: a CUSTOM template owned by another workspace is forbidden")
    void delete_crossWorkspaceCustomTemplateIsForbidden() {
        when(templateRepository.findById(TEMPLATE_ID))
                .thenReturn(Optional.of(customTemplate(OTHER_WORKSPACE_ID)));

        ApiException ex = assertThrows(ApiException.class, () -> controller.delete(principal, TEMPLATE_ID));

        assertEquals("FORBIDDEN", ex.getCode());
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
    }

    // ------------------------------------------------------------------------------------------
    // POST /campaign-templates (save-as-template) — 402 upgrade gate, exercised against the real
    // PlanGateInterceptor + the real annotated HandlerMethod, not a fake stand-in controller.
    // ------------------------------------------------------------------------------------------

    private HandlerMethod saveAsTemplateHandlerMethod() throws NoSuchMethodException {
        Method method =
                CampaignTemplateController.class.getMethod(
                        "saveAsTemplate", AuthPrincipal.class, SaveAsTemplateRequest.class);
        return new HandlerMethod(controller, method);
    }

    @Test
    @DisplayName("POST /campaign-templates: Free plan (campaignTemplatesEnabled=false) is genuinely blocked with 402 UPGRADE_REQUIRED")
    void saveAsTemplate_freePlanBlockedWith402() throws Exception {
        PlanGateInterceptor interceptor = new PlanGateInterceptor();
        Plan freePlan = mock(Plan.class);
        when(freePlan.isCampaignTemplatesEnabled()).thenReturn(false);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(PlanGateFilter.RESOLVED_PLAN_ATTR, freePlan);

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                interceptor.preHandle(
                                        request, new MockHttpServletResponse(), saveAsTemplateHandlerMethod()));

        assertEquals("UPGRADE_REQUIRED", ex.getCode());
        assertEquals(HttpStatus.PAYMENT_REQUIRED, ex.getStatus());
    }

    @Test
    @DisplayName("POST /campaign-templates: Pro plan (campaignTemplatesEnabled=true) is genuinely allowed through the gate")
    void saveAsTemplate_proPlanAllowedThroughGate() throws Exception {
        PlanGateInterceptor interceptor = new PlanGateInterceptor();
        Plan proPlan = mock(Plan.class);
        when(proPlan.isCampaignTemplatesEnabled()).thenReturn(true);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(PlanGateFilter.RESOLVED_PLAN_ATTR, proPlan);

        boolean allowed =
                interceptor.preHandle(
                        request, new MockHttpServletResponse(), saveAsTemplateHandlerMethod());

        assertTrue(allowed);
    }

    @Test
    @DisplayName("POST /campaign-templates: feature() on the real annotation is CAMPAIGN_TEMPLATES, not EXPORT")
    void saveAsTemplate_annotationCarriesCorrectFeature() throws Exception {
        HandlerMethod handlerMethod = saveAsTemplateHandlerMethod();
        PlanFeature feature = handlerMethod.getMethodAnnotation(com.influora.security.RequiresPlan.class).feature();
        assertEquals(PlanFeature.CAMPAIGN_TEMPLATES, feature);
    }
}
