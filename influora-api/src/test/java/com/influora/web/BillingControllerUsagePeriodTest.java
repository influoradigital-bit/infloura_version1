package com.influora.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.influora.common.ApiResponse;
import com.influora.domain.entity.Plan;
import com.influora.domain.entity.Workspace;
import com.influora.domain.enums.UsageMetric;
import com.influora.domain.enums.UserType;
import com.influora.repository.BrandAiCreditRepository;
import com.influora.security.AuthPrincipal;
import com.influora.service.BrandContextService;
import com.influora.service.WorkspaceMemberService;
import com.influora.service.billing.InvoiceService;
import com.influora.service.billing.SubscriptionService;
import com.influora.service.billing.UsageCounterService;
import com.influora.web.dto.billing.BillingDtos.UsageSummaryResponse;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

/**
 * MEDIUM fix gate — {@code GET /billing/usage} must LABEL its counts with the billing period those
 * counts were actually read against.
 *
 * <p>Before the fix, {@code getUsage} reported {@code periodStart} as a locally re-derived {@code
 * LocalDate.now(UTC).withDayOfMonth(1)} while the counts beside it came from {@code
 * UsageCounterService.getUsageForCurrentPeriod}, which anchors on {@code
 * Subscription.currentPeriodStart} whenever a Subscription row exists and only falls back to the
 * calendar month when none does. Those genuinely diverge: {@code
 * SubscriptionService#grantCompSubscription} writes {@code currentPeriodStart = Instant.now()},
 * {@code RazorpayWebhookController} writes whatever boundary Razorpay sends, and {@code
 * SubscriptionRenewalResetJob} advances it by the previous cycle length — none of which land on
 * the 1st. The response also contradicted {@code GET /billing/plan}, which reports the real
 * {@code currentPeriodStart}.
 *
 * <p><b>Falsification shape:</b> the anchor stubbed below ({@code 2025-03-17}) is not the first of
 * any month and is not today, so the old hardcoded expression cannot produce it. Reinstating
 * {@code LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1).toString()} in {@code getUsage} turns
 * this test RED on any calendar day.
 */
@ExtendWith(MockitoExtension.class)
class BillingControllerUsagePeriodTest {

    private static final String WORKSPACE_ID = "ws-comp-1";

    /** Mid-month, past year: unreachable by any "first of the current month" derivation. */
    private static final LocalDate SUBSCRIPTION_ANCHOR = LocalDate.of(2025, 3, 17);

    @Mock private BrandContextService brandContextService;
    @Mock private SubscriptionService subscriptionService;
    @Mock private InvoiceService invoiceService;
    @Mock private UsageCounterService usageCounterService;
    @Mock private BrandAiCreditRepository brandAiCreditRepository;
    @Mock private WorkspaceMemberService workspaceMemberService;

    @Test
    @DisplayName("GET /billing/usage reports the subscription's real billing-cycle anchor, not a re-derived calendar month")
    void testReportedPeriodIsTheOneTheCountsWereReadAgainst() {
        BillingController controller =
                new BillingController(
                        brandContextService,
                        subscriptionService,
                        invoiceService,
                        usageCounterService,
                        brandAiCreditRepository,
                        workspaceMemberService);

        AuthPrincipal principal =
                new AuthPrincipal("brand-user", "b@x.com", UserType.BRAND, WORKSPACE_ID);
        Workspace workspace = mock(Workspace.class);
        when(workspace.getId()).thenReturn(WORKSPACE_ID);
        when(brandContextService.requireBrandWorkspace(principal)).thenReturn(workspace);
        when(subscriptionService.getActivePlanForWorkspace(WORKSPACE_ID)).thenReturn(mock(Plan.class));
        when(brandAiCreditRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(Optional.empty());

        // The single source of truth for "which period are we in" — the same method the counters
        // resolve against internally.
        when(usageCounterService.getCurrentPeriodStart(WORKSPACE_ID)).thenReturn(SUBSCRIPTION_ANCHOR);
        when(usageCounterService.getUsageForCurrentPeriod(WORKSPACE_ID, UsageMetric.TRACKED_CREATOR))
                .thenReturn(3);
        when(usageCounterService.getUsageForCurrentPeriod(
                        WORKSPACE_ID, UsageMetric.CREATOR_ANALYTICS_VIEW))
                .thenReturn(7);

        ResponseEntity<ApiResponse<UsageSummaryResponse>> response = controller.getUsage(principal);

        UsageSummaryResponse body = response.getBody().data();
        assertNotNull(body);
        assertEquals(
                SUBSCRIPTION_ANCHOR.toString(),
                body.periodStart(),
                "periodStart must be the anchor the counts were read against, not the calendar month");
        // The counts really did come from that same period-resolved read, so the label describes them.
        assertEquals(3, body.trackedCreatorsUsed());
        assertEquals(7, body.analyticsViewsUsed());
    }
}
