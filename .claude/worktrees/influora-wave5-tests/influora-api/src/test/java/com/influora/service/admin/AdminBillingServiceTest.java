package com.influora.service.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.AdminUser;
import com.influora.domain.entity.Plan;
import com.influora.domain.entity.Subscription;
import com.influora.domain.entity.Workspace;
import com.influora.domain.enums.AdminRole;
import com.influora.domain.enums.PlanCode;
import com.influora.domain.enums.SubscriptionStatus;
import com.influora.domain.enums.WorkspaceType;
import com.influora.repository.SubscriptionRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.security.AuthPrincipal;
import com.influora.service.billing.PlanService;
import com.influora.service.billing.SubscriptionService;
import com.influora.web.dto.admin.AdminBillingDtos.AdminSubscriptionActionResultDto;
import com.influora.web.dto.admin.AdminBillingDtos.CompSubscriptionRequest;
import com.influora.web.dto.admin.AdminBillingDtos.OverrideSubscriptionRequest;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * MP-1 wiring tests for {@code AdminBillingController}'s {@code /comp}/{@code /override}
 * endpoints (Task 25) — per {@code wiki/tech/security.md} MP-1, these assert the {@code
 * AdminAuditLogService#record} call actually FIRES with the right action/entityType/reason for
 * every mutating path, and that a non-SUPER_ADMIN principal is genuinely rejected (via {@code
 * AdminContextService#requireRoleWithMfaSatisfied} propagating), not merely that the DTO shapes
 * look right in isolation. {@link SubscriptionService}'s own {@code grantAdminPlan} wiring
 * (comp creates an ACTIVE subscription + reconciles AI credits) is covered separately in {@code
 * SubscriptionServiceTest} — this class covers the admin-surface layer on top of it (gating +
 * audit trail), matching the "reuse SubscriptionService's activation logic, don't duplicate it"
 * design.
 */
@ExtendWith(MockitoExtension.class)
class AdminBillingServiceTest {

    private static final String WORKSPACE_ID = "01HWXYZWORKSPACE00000001";
    private static final String ADMIN_ID = "01HWXYZADMIN000000000001";
    private static final String FREE_PLAN_ID = "01HWXYZPLANFREE000000001";
    private static final String PRO_PLAN_ID = "01HWXYZPLANPRO0000000001";
    private static final String SUBSCRIPTION_ID = "01HWXYZSUB0000000000009";

    @Mock private AdminContextService adminContext;
    @Mock private AdminAuditLogService adminAuditLogService;
    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private WorkspaceRepository workspaceRepository;
    @Mock private PlanService planService;
    @Mock private SubscriptionService subscriptionService;
    @Mock private AuthPrincipal principal;
    @Mock private HttpServletRequest request;

    private AdminBillingService adminBillingService;

    private Workspace brandWorkspace;
    private Plan proPlan;
    private Plan freePlan;
    private AdminUser superAdmin;

    @BeforeEach
    void setUp() {
        adminBillingService =
                new AdminBillingService(
                        adminContext, adminAuditLogService, subscriptionRepository, workspaceRepository, planService, subscriptionService);

        brandWorkspace = Workspace.newBrand(WORKSPACE_ID, "Acme Brand", "acme-brand", "Retail", "11-50");

        proPlan =
                Plan.builder()
                        .id(PRO_PLAN_ID)
                        .code(PlanCode.PRO)
                        .name("Pro")
                        .priceInr(499900)
                        .aiMonthlyAllotment(400)
                        .active(true)
                        .build();
        freePlan =
                Plan.builder()
                        .id(FREE_PLAN_ID)
                        .code(PlanCode.FREE)
                        .name("Free")
                        .priceInr(0)
                        .aiMonthlyAllotment(100)
                        .active(true)
                        .build();

        superAdmin = AdminUser.create(ADMIN_ID, "ops@influora.ai", "hash", AdminRole.SUPER_ADMIN);
    }

    @Test
    @DisplayName("wiring: comp grant writes admin_audit_log with action=TIER_ADJUST, entityType=SUBSCRIPTION, and the supplied reason")
    void testGrantCompWritesAuditLogWithTierAdjust() {
        when(adminContext.requireRoleWithMfaSatisfied(principal, AdminRole.SUPER_ADMIN)).thenReturn(superAdmin);
        when(workspaceRepository.findById(WORKSPACE_ID)).thenReturn(Optional.of(brandWorkspace));
        when(planService.getByCode(PlanCode.PRO)).thenReturn(proPlan);
        when(planService.getActivePlans()).thenReturn(List.of(proPlan, freePlan));
        when(subscriptionRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(Optional.empty());

        Subscription granted = compSubscriptionRow();
        when(subscriptionService.grantAdminPlan(eq(WORKSPACE_ID), eq(proPlan), eq(ADMIN_ID), anyString(), any()))
                .thenReturn(granted);

        CompSubscriptionRequest body =
                new CompSubscriptionRequest(WORKSPACE_ID, "PRO", "Q3 partnership comp — CEO approved", null);

        AdminSubscriptionActionResultDto result = adminBillingService.grantComp(principal, request, body);

        assertEquals(SUBSCRIPTION_ID, result.subscriptionId());
        assertEquals("PRO", result.planCode());
        assertEquals(true, result.isComp());

        // The MP-1 wiring assertion: the audit write call actually fires with the right shape —
        // not just that a javadoc claims it does.
        verify(adminAuditLogService)
                .record(
                        eq(principal),
                        eq(request),
                        eq("TIER_ADJUST"),
                        eq("SUBSCRIPTION"),
                        eq(SUBSCRIPTION_ID),
                        any(),
                        any(),
                        eq("Q3 partnership comp — CEO approved"));
        verify(subscriptionService)
                .grantAdminPlan(WORKSPACE_ID, proPlan, ADMIN_ID, "Q3 partnership comp — CEO approved", null);
    }

    @Test
    @DisplayName("wiring: override writes admin_audit_log with action=BUDGET_OVERRIDE (not TIER_ADJUST), same underlying grantAdminPlan call")
    void testOverridePlanWritesAuditLogWithBudgetOverride() {
        when(adminContext.requireRoleWithMfaSatisfied(principal, AdminRole.SUPER_ADMIN)).thenReturn(superAdmin);
        when(workspaceRepository.findById(WORKSPACE_ID)).thenReturn(Optional.of(brandWorkspace));
        when(planService.getByCode(PlanCode.FREE)).thenReturn(freePlan);
        when(planService.getActivePlans()).thenReturn(List.of(proPlan, freePlan));
        when(subscriptionRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(Optional.empty());

        Subscription granted = compSubscriptionRow();
        when(subscriptionService.grantAdminPlan(eq(WORKSPACE_ID), eq(freePlan), eq(ADMIN_ID), anyString(), any()))
                .thenReturn(granted);

        OverrideSubscriptionRequest body =
                new OverrideSubscriptionRequest(WORKSPACE_ID, "FREE", "Correcting mistaken manual Pro grant", null);

        adminBillingService.overridePlan(principal, request, body);

        verify(adminAuditLogService)
                .record(
                        eq(principal),
                        eq(request),
                        eq("BUDGET_OVERRIDE"),
                        eq("SUBSCRIPTION"),
                        eq(SUBSCRIPTION_ID),
                        any(),
                        any(),
                        eq("Correcting mistaken manual Pro grant"));
        verify(subscriptionService)
                .grantAdminPlan(WORKSPACE_ID, freePlan, ADMIN_ID, "Correcting mistaken manual Pro grant", null);
    }

    @Test
    @DisplayName("a non-SUPER_ADMIN principal is rejected before any workspace/subscription lookup or mutation happens")
    void testNonSuperAdminRejectedBeforeAnyMutation() {
        when(adminContext.requireRoleWithMfaSatisfied(principal, AdminRole.SUPER_ADMIN))
                .thenThrow(new ApiException("INSUFFICIENT_ROLE", "nope", org.springframework.http.HttpStatus.FORBIDDEN));

        CompSubscriptionRequest body = new CompSubscriptionRequest(WORKSPACE_ID, "PRO", "should never reach this", null);

        assertThrows(ApiException.class, () -> adminBillingService.grantComp(principal, request, body));

        verify(workspaceRepository, never()).findById(anyString());
        verify(subscriptionService, never()).grantAdminPlan(any(), any(), any(), any(), any());
        verify(adminAuditLogService, never())
                .record(any(), any(), anyString(), anyString(), anyString(), any(), any(), anyString());
    }

    @Test
    @DisplayName("unknown workspace id is rejected with WORKSPACE_NOT_FOUND, no mutation or audit write")
    void testUnknownWorkspaceRejected() {
        when(adminContext.requireRoleWithMfaSatisfied(principal, AdminRole.SUPER_ADMIN)).thenReturn(superAdmin);
        when(workspaceRepository.findById(WORKSPACE_ID)).thenReturn(Optional.empty());

        CompSubscriptionRequest body = new CompSubscriptionRequest(WORKSPACE_ID, "PRO", "grant for a bad workspace id", null);

        ApiException ex = assertThrows(ApiException.class, () -> adminBillingService.grantComp(principal, request, body));
        assertEquals("WORKSPACE_NOT_FOUND", ex.getCode());
        verify(subscriptionService, never()).grantAdminPlan(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a non-BRAND workspace (e.g. AGENCY) is rejected — subscriptions only apply to BRAND workspaces")
    void testNonBrandWorkspaceRejected() {
        Workspace agencyWorkspace = agencyWorkspace();
        when(adminContext.requireRoleWithMfaSatisfied(principal, AdminRole.SUPER_ADMIN)).thenReturn(superAdmin);
        when(workspaceRepository.findById(WORKSPACE_ID)).thenReturn(Optional.of(agencyWorkspace));

        CompSubscriptionRequest body = new CompSubscriptionRequest(WORKSPACE_ID, "PRO", "grant for an agency workspace", null);

        ApiException ex = assertThrows(ApiException.class, () -> adminBillingService.grantComp(principal, request, body));
        assertEquals("NOT_A_BRAND_WORKSPACE", ex.getCode());
        verify(subscriptionService, never()).grantAdminPlan(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("an unrecognized plan code is rejected with INVALID_PLAN_CODE before any mutation")
    void testInvalidPlanCodeRejected() {
        when(adminContext.requireRoleWithMfaSatisfied(principal, AdminRole.SUPER_ADMIN)).thenReturn(superAdmin);
        when(workspaceRepository.findById(WORKSPACE_ID)).thenReturn(Optional.of(brandWorkspace));

        CompSubscriptionRequest body = new CompSubscriptionRequest(WORKSPACE_ID, "ENTERPRISE", "not a real plan code", null);

        ApiException ex = assertThrows(ApiException.class, () -> adminBillingService.grantComp(principal, request, body));
        assertEquals("INVALID_PLAN_CODE", ex.getCode());
        verify(subscriptionService, never()).grantAdminPlan(any(), any(), any(), any(), any());
    }

    private Subscription compSubscriptionRow() {
        return Subscription.builder()
                .id(SUBSCRIPTION_ID)
                .workspaceId(WORKSPACE_ID)
                .planId(PRO_PLAN_ID)
                .status(SubscriptionStatus.ACTIVE)
                .currentPeriodStart(Instant.now())
                .currentPeriodEnd(Instant.now().plusSeconds(315360000L))
                .comp(true)
                .compReason("test reason")
                .compGrantedBy(ADMIN_ID)
                .build();
    }

    private Workspace agencyWorkspace() {
        Workspace w = Workspace.newBrand(WORKSPACE_ID, "Some Agency", "some-agency", "Marketing", "1-10");
        // newBrand() hardcodes WorkspaceType.BRAND — reflection isn't used elsewhere in this
        // codebase's tests for entity state setup, so this test instead relies on
        // applyCompanyDetails() to flip the type field to AGENCY, mirroring how the entity itself
        // is the only supported mutation path.
        w.applyCompanyDetails(
                "Some Agency", "some-agency", WorkspaceType.AGENCY, "Marketing", "1-10", null, null, null);
        return w;
    }
}
