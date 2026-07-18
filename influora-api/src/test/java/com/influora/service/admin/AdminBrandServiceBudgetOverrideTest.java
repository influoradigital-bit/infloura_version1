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
import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.EscrowHold;
import com.influora.domain.entity.Workspace;
import com.influora.domain.enums.AdminRole;
import com.influora.domain.enums.CampaignStatus;
import com.influora.domain.enums.CollaborationStatus;
import com.influora.domain.enums.EscrowStatus;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.EscrowHoldRepository;
import com.influora.repository.UserRepository;
import com.influora.repository.WalletRepository;
import com.influora.repository.WalletTransactionRepository;
import com.influora.repository.WorkspaceMemberRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.security.AuthPrincipal;
import com.influora.web.dto.admin.AdminBrandDtos.BudgetOverrideRequest;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

/**
 * Mockito unit tests for {@code AdminBrandService#overrideCampaignBudget}'s committed-spend floor
 * (MONEY PATH). Focuses exclusively on the floor math + edge validation that was fixed but shipped
 * compile-verified only (Kabir M-2 partial-escrow remainder, L-3 FROZEN/disputed, L-4 scale, IDOR
 * uniform 404). RBAC is mocked to pass here — {@code requireRoleWithMfaSatisfied} is stubbed to
 * return a SUPER_ADMIN — because the role/MFA path is covered elsewhere; these tests target the
 * FLOOR, not the gate. Plain Mockito, no {@code @SpringBootTest}, matching every other {@code
 * Admin*ServiceTest} in this package (Testcontainers/Docker discovery does not work in this
 * environment; the live DB path is blocked on a separate Flyway approval).
 *
 * <p>The service computes the floor in two non-overlapping passes: (a) per committed collaboration
 * {@code max(agreedRate, sum of its counted holds)}; (b) every counted hold NOT bound to a
 * committed collaboration. COUNTED = {FUNDED, RELEASED, FROZEN}. Committed statuses EXCLUDE
 * DISPUTED/CANCELLED/COMPLETED and all pre-agreement states.
 */
@ExtendWith(MockitoExtension.class)
class AdminBrandServiceBudgetOverrideTest {

    private static final String BRAND_ID = "01HWXYZBRAND00000000001";
    private static final String OTHER_BRAND_ID = "01HWXYZBRAND00000000999";
    private static final String CAMPAIGN_ID = "01HWXYZCAMPAIGN000000001";
    private static final String ADMIN_ID = "01HWXYZADMIN000000000001";
    private static final String COLLAB_ID = "01HWXYZCOLLAB00000000001";
    private static final String CREATOR_ID = "01HWXYZCREATOR0000000001";

    @Mock private AdminContextService adminContext;
    @Mock private AdminAuditLogService adminAuditLogService;
    @Mock private WorkspaceRepository workspaceRepository;
    @Mock private WorkspaceMemberRepository workspaceMemberRepository;
    @Mock private UserRepository userRepository;
    @Mock private CampaignRepository campaignRepository;
    @Mock private CollaborationRepository collaborationRepository;
    @Mock private EscrowHoldRepository escrowHoldRepository;
    @Mock private WalletRepository walletRepository;
    @Mock private WalletTransactionRepository walletTransactionRepository;
    @Mock private AuthPrincipal principal;
    @Mock private HttpServletRequest request;

    private AdminBrandService service;
    private AdminUser superAdmin;

    @BeforeEach
    void setUp() {
        service =
                new AdminBrandService(
                        adminContext,
                        adminAuditLogService,
                        workspaceRepository,
                        workspaceMemberRepository,
                        userRepository,
                        campaignRepository,
                        collaborationRepository,
                        escrowHoldRepository,
                        walletRepository,
                        walletTransactionRepository);
        superAdmin = AdminUser.create(ADMIN_ID, "ops@influora.ai", "hash", AdminRole.SUPER_ADMIN);
    }

    // ============================================================
    // 1. Partial-escrow — the core Kabir M-2 fix
    // ============================================================
    @Test
    @DisplayName(
            "partial escrow: agreedRate 100k IN_PROGRESS + one FUNDED 30k hold floors at 100k (max, not"
                + " the 30k escrowed) — 30k REJECTED 409 BUDGET_BELOW_COMMITTED, 100k SUCCEEDS")
    void testPartialEscrowFloorsAtAgreedRate() {
        Campaign campaign = brandCampaign(bd("200000"));
        stubSuperAdmin();
        stubBrandAndCampaign(campaign);
        when(collaborationRepository.findByCampaignId(CAMPAIGN_ID))
                .thenReturn(List.of(collab(COLLAB_ID, bd("100000"), CollaborationStatus.IN_PROGRESS)));
        when(escrowHoldRepository.findByCampaignId(CAMPAIGN_ID))
                .thenReturn(List.of(hold("h1", COLLAB_ID, bd("30000"), EscrowStatus.FUNDED)));

        // 30k is below the 100k floor -> reject, nothing persisted.
        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.overrideCampaignBudget(principal, request, BRAND_ID, CAMPAIGN_ID, req(bd("30000"))));
        assertEquals("BUDGET_BELOW_COMMITTED", ex.getCode());
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        verify(campaignRepository, never()).save(any());

        // 100k exactly at the floor -> accepted, persisted, audited.
        service.overrideCampaignBudget(principal, request, BRAND_ID, CAMPAIGN_ID, req(bd("100000")));
        assertEquals(0, campaign.getBudgetMax().compareTo(bd("100000")));
        verify(campaignRepository).save(campaign);
        verifyBudgetOverrideAudited();
    }

    // ============================================================
    // 2. Over-escrowed — floor takes the higher hold sum
    // ============================================================
    @Test
    @DisplayName(
            "over-escrowed: agreedRate 50k IN_PROGRESS but FUNDED holds sum to 80k -> floor 80k (max) ->"
                    + " newBudget 60k REJECTED 409")
    void testOverEscrowedFloorsAtHoldSum() {
        Campaign campaign = brandCampaign(bd("200000"));
        stubSuperAdmin();
        stubBrandAndCampaign(campaign);
        when(collaborationRepository.findByCampaignId(CAMPAIGN_ID))
                .thenReturn(List.of(collab(COLLAB_ID, bd("50000"), CollaborationStatus.IN_PROGRESS)));
        when(escrowHoldRepository.findByCampaignId(CAMPAIGN_ID))
                .thenReturn(
                        List.of(
                                hold("h1", COLLAB_ID, bd("50000"), EscrowStatus.FUNDED),
                                hold("h2", COLLAB_ID, bd("30000"), EscrowStatus.FUNDED)));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.overrideCampaignBudget(principal, request, BRAND_ID, CAMPAIGN_ID, req(bd("60000"))));
        assertEquals("BUDGET_BELOW_COMMITTED", ex.getCode());
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        verify(campaignRepository, never()).save(any());
        verifyNoAudit();
    }

    // ============================================================
    // 3. Unbound hold (null collaborationId) — pass (b)
    // ============================================================
    @Test
    @DisplayName(
            "unbound campaign-scoped hold (collaborationId == null): FUNDED 20k floors at 20k ->"
                    + " newBudget 10k REJECTED 409")
    void testUnboundHoldFloorsViaPassB() {
        Campaign campaign = brandCampaign(bd("200000"));
        stubSuperAdmin();
        stubBrandAndCampaign(campaign);
        when(collaborationRepository.findByCampaignId(CAMPAIGN_ID)).thenReturn(List.of());
        when(escrowHoldRepository.findByCampaignId(CAMPAIGN_ID))
                .thenReturn(List.of(hold("h1", null, bd("20000"), EscrowStatus.FUNDED)));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.overrideCampaignBudget(principal, request, BRAND_ID, CAMPAIGN_ID, req(bd("10000"))));
        assertEquals("BUDGET_BELOW_COMMITTED", ex.getCode());
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        verify(campaignRepository, never()).save(any());
        verifyNoAudit();
    }

    // ============================================================
    // 4. FROZEN hold on a DISPUTED collaboration — L-3, agreedRate NOT invented
    // ============================================================
    @Test
    @DisplayName(
            "FROZEN hold 40k on a DISPUTED collab (not committed) floors via the hold only (40k); the"
                + " collab's large agreedRate is NOT counted -> 40k OK, 39,999 REJECTED 409")
    void testFrozenHoldOnDisputedCollabFloorsViaHoldNotAgreedRate() {
        Campaign campaign = brandCampaign(bd("200000"));
        stubSuperAdmin();
        stubBrandAndCampaign(campaign);
        // agreedRate deliberately huge (1,000,000): if DISPUTED agreedRate were counted the floor
        // would be 1,000,000 and 40k would be rejected. It is not — floor is the 40k FROZEN hold.
        when(collaborationRepository.findByCampaignId(CAMPAIGN_ID))
                .thenReturn(List.of(collab(COLLAB_ID, bd("1000000"), CollaborationStatus.DISPUTED)));
        when(escrowHoldRepository.findByCampaignId(CAMPAIGN_ID))
                .thenReturn(List.of(hold("h1", COLLAB_ID, bd("40000"), EscrowStatus.FROZEN)));

        // Just below the frozen amount -> reject.
        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.overrideCampaignBudget(principal, request, BRAND_ID, CAMPAIGN_ID, req(bd("39999"))));
        assertEquals("BUDGET_BELOW_COMMITTED", ex.getCode());
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        verify(campaignRepository, never()).save(any());

        // Exactly at the frozen floor -> accepted (proves agreedRate 1,000,000 was ignored).
        service.overrideCampaignBudget(principal, request, BRAND_ID, CAMPAIGN_ID, req(bd("40000")));
        assertEquals(0, campaign.getBudgetMax().compareTo(bd("40000")));
        verify(campaignRepository).save(campaign);
        verifyBudgetOverrideAudited();
    }

    // ============================================================
    // 5. Scale > 2 — Kabir L-4
    // ============================================================
    @Test
    @DisplayName("scale > 2 decimals: newBudget 100.999 -> 400 INVALID_BUDGET_SCALE, before any floor load")
    void testExcessScaleRejected() {
        Campaign campaign = brandCampaign(bd("200000"));
        stubSuperAdmin();
        stubBrandAndCampaign(campaign);

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.overrideCampaignBudget(principal, request, BRAND_ID, CAMPAIGN_ID, req(new BigDecimal("100.999"))));
        assertEquals("INVALID_BUDGET_SCALE", ex.getCode());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        verify(campaignRepository, never()).save(any());
        verifyNoAudit();
    }

    // ============================================================
    // 6. IDOR — campaign belongs to a different brand
    // ============================================================
    @Test
    @DisplayName(
            "IDOR: campaign whose workspaceId != path brandId -> 404 CAMPAIGN_NOT_FOUND (uniform, same"
                    + " as a missing campaign — no leak)")
    void testForeignCampaignRejectedAsNotFound() {
        Campaign foreignCampaign =
                Campaign.builder()
                        .id(CAMPAIGN_ID)
                        .workspaceId(OTHER_BRAND_ID) // belongs to a different brand
                        .title("Someone else's campaign")
                        .status(CampaignStatus.ACTIVE)
                        .budgetMax(bd("200000"))
                        .createdBy(CREATOR_ID)
                        .build();
        stubSuperAdmin();
        stubBrandAndCampaign(foreignCampaign);

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.overrideCampaignBudget(principal, request, BRAND_ID, CAMPAIGN_ID, req(bd("50000"))));
        assertEquals("CAMPAIGN_NOT_FOUND", ex.getCode());
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        verify(campaignRepository, never()).save(any());
        verifyNoAudit();
    }

    // ============================================================
    // 7. Zero / negative newBudget -> 400
    // ============================================================
    @Test
    @DisplayName("zero and negative newBudget are both rejected with 400 INVALID_BUDGET, no persistence")
    void testZeroAndNegativeBudgetRejected() {
        Campaign campaign = brandCampaign(bd("200000"));
        stubSuperAdmin();
        stubBrandAndCampaign(campaign);

        ApiException zero =
                assertThrows(
                        ApiException.class,
                        () -> service.overrideCampaignBudget(principal, request, BRAND_ID, CAMPAIGN_ID, req(BigDecimal.ZERO)));
        assertEquals("INVALID_BUDGET", zero.getCode());
        assertEquals(HttpStatus.BAD_REQUEST, zero.getStatus());

        ApiException negative =
                assertThrows(
                        ApiException.class,
                        () -> service.overrideCampaignBudget(principal, request, BRAND_ID, CAMPAIGN_ID, req(bd("-100"))));
        assertEquals("INVALID_BUDGET", negative.getCode());
        assertEquals(HttpStatus.BAD_REQUEST, negative.getStatus());

        verify(campaignRepository, never()).save(any());
        verifyNoAudit();
    }

    // ============================================================
    // 8. Happy path — no commitments, persists + audits
    // ============================================================
    @Test
    @DisplayName(
            "happy path: no holds/collabs, newBudget within bounds -> persists budgetMax and writes a"
                    + " BUDGET_OVERRIDE audit entry")
    void testHappyPathPersistsAndAudits() {
        Campaign campaign = brandCampaign(bd("200000"));
        stubSuperAdmin();
        stubBrandAndCampaign(campaign);
        when(collaborationRepository.findByCampaignId(CAMPAIGN_ID)).thenReturn(List.of());
        when(escrowHoldRepository.findByCampaignId(CAMPAIGN_ID)).thenReturn(List.of());

        service.overrideCampaignBudget(principal, request, BRAND_ID, CAMPAIGN_ID, req(bd("50000")));

        assertEquals(0, campaign.getBudgetMax().compareTo(bd("50000")));
        verify(campaignRepository).save(campaign);
        verify(adminAuditLogService)
                .record(
                        eq(principal),
                        eq(request),
                        eq("BUDGET_OVERRIDE"),
                        eq("CAMPAIGN"),
                        eq(CAMPAIGN_ID),
                        any(),
                        any(),
                        anyString());
    }

    // ============================================================
    // Helpers
    // ============================================================

    private void stubSuperAdmin() {
        when(adminContext.requireRoleWithMfaSatisfied(principal, AdminRole.SUPER_ADMIN))
                .thenReturn(superAdmin);
    }

    private void stubBrandAndCampaign(Campaign campaign) {
        when(workspaceRepository.findById(BRAND_ID)).thenReturn(Optional.of(brandWorkspace()));
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));
    }

    private void verifyBudgetOverrideAudited() {
        verify(adminAuditLogService)
                .record(
                        eq(principal),
                        eq(request),
                        eq("BUDGET_OVERRIDE"),
                        eq("CAMPAIGN"),
                        eq(CAMPAIGN_ID),
                        any(),
                        any(),
                        anyString());
    }

    private void verifyNoAudit() {
        verify(adminAuditLogService, never())
                .record(any(), any(), anyString(), anyString(), anyString(), any(), any(), anyString());
    }

    private static Workspace brandWorkspace() {
        return Workspace.newBrand(BRAND_ID, "Acme Brand", "acme-brand", "Retail", "11-50");
    }

    private static Campaign brandCampaign(BigDecimal budgetMax) {
        return Campaign.builder()
                .id(CAMPAIGN_ID)
                .workspaceId(BRAND_ID)
                .title("Summer launch")
                .status(CampaignStatus.ACTIVE)
                .budgetMax(budgetMax)
                .createdBy(CREATOR_ID)
                .build();
    }

    private static Collaboration collab(String id, BigDecimal agreedRate, CollaborationStatus status) {
        Collaboration c = Collaboration.propose(id, CAMPAIGN_ID, CREATOR_ID, agreedRate, "INR", "terms");
        c.transitionTo(status);
        return c;
    }

    private static EscrowHold hold(String id, String collaborationId, BigDecimal amount, EscrowStatus status) {
        return EscrowHold.builder()
                .id(id)
                .workspaceId(BRAND_ID)
                .campaignId(CAMPAIGN_ID)
                .collaborationId(collaborationId)
                .amount(amount)
                .status(status)
                .idempotencyKey("idem-" + id)
                .build();
    }

    private static BudgetOverrideRequest req(BigDecimal newBudget) {
        return new BudgetOverrideRequest(newBudget, "admin correction reason");
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }
}
