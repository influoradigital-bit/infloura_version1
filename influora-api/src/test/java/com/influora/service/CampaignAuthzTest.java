package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.Workspace;
import com.influora.domain.entity.WorkspaceMember;
import com.influora.domain.enums.MemberRole;
import com.influora.domain.enums.UserType;
import com.influora.repository.CampaignIntentRepository;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.EscrowHoldRepository;
import com.influora.repository.MeeraToolCallRepository;
import com.influora.repository.UserRepository;
import com.influora.repository.WorkspaceMemberRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.security.AuthPrincipal;
import com.influora.service.meera.MeeraInteractionLogService;
import com.influora.service.meera.tool.CreateCampaignExecutor;
import com.influora.web.dto.campaign.CampaignDtos.BudgetDto;
import com.influora.web.dto.campaign.CampaignDtos.CampaignWriteRequest;
import com.influora.web.dto.campaign.CampaignDtos.TimelineDto;
import com.influora.web.dto.meera.MeeraToolDtos.CreateCampaignResult;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * F-0530 (REGRESSION — currently RED): {@code CampaignService.create} resolves the caller's
 * workspace membership via {@code brandContext.requireMember} but, unlike every sibling write
 * path ({@code update} at line ~236, and the publish/delete paths), never follows up with {@code
 * brandContext.requireRole(member, OWNER, ADMIN, MANAGER)}. A workspace member holding MEMBER or
 * VIEWER — roles that are refused on update/publish/delete — can therefore create a campaign they
 * are not permitted to touch afterward.
 *
 * <p>Deliberately wires a REAL {@link BrandContextService} (only its repositories are mocked)
 * instead of mocking {@code brandContext} outright, the way {@link CampaignServiceTest} does for
 * its other coverage. A mocked {@code brandContext.requireRole} would pass or fail purely on
 * however this test chose to stub it, which would prove nothing about whether {@code create()}
 * actually calls it. Running the real role check is what lets a low-privilege role genuinely reach
 * (or fail to reach) enforcement, and is why the failure below is a real defect, not a test-double
 * artifact.
 */
@ExtendWith(MockitoExtension.class)
class CampaignAuthzTest {

    private static final String WORKSPACE_ID = "01HWORKSPACE12345678A";
    private static final String USER_ID = "01HUSER123456789012AB";

    @Mock private CampaignRepository campaignRepository;
    @Mock private CollaborationRepository collaborationRepository;
    @Mock private EscrowHoldRepository escrowHoldRepository;
    @Mock private WorkspaceRepository workspaceRepository;
    @Mock private WorkspaceMemberRepository workspaceMemberRepository;
    @Mock private UserRepository userRepository;
    @Mock private IntegrationHealthService integrationHealthService;
    @Mock private BrandCampaignFeeService brandCampaignFeeService;

    // F-0530 (Meera path): CreateCampaignExecutor's own dependencies, unrelated to CampaignService
    // above. Wired into a SEPARATE executor instance per test below (not shared/constructed here in
    // setUp()) so the existing CampaignService coverage above is untouched.
    @Mock private CampaignIntentRepository campaignIntentRepository;
    @Mock private MeeraToolCallRepository toolCallRepository;
    @Mock private AuditLogService auditLogService;
    @Mock private IdempotencyService idempotencyService;
    @Mock private CampaignTemplateService campaignTemplateService;
    @Mock private MeeraInteractionLogService meeraInteractionLogService;

    private CampaignService service;
    private BrandContextService brandContext;

    @BeforeEach
    void setUp() {
        brandContext =
                new BrandContextService(workspaceRepository, workspaceMemberRepository, userRepository);
        // Real CampaignValidator: no dependencies of its own (see CampaignServiceTest's identical
        // rationale) — a mock would add noise without changing what this test is asserting.
        service =
                new CampaignService(
                        campaignRepository,
                        collaborationRepository,
                        escrowHoldRepository,
                        brandContext,
                        new CampaignValidator(),
                        integrationHealthService,
                        brandCampaignFeeService);
    }

    /**
     * Builds a REAL {@link CreateCampaignExecutor} (only its repositories/collaborator services are
     * mocked, matching this class's rationale above for {@code service}) wired to the SAME real
     * {@code brandContext} instance {@code service} uses — this is the Meera {@code create_campaign}
     * tool path's executor, the sibling entry point F-0530 also had to close.
     */
    private CreateCampaignExecutor meeraCreateCampaignExecutor() {
        return new CreateCampaignExecutor(
                campaignIntentRepository,
                campaignRepository,
                toolCallRepository,
                auditLogService,
                idempotencyService,
                campaignTemplateService,
                meeraInteractionLogService,
                workspaceRepository,
                brandContext);
    }

    @ParameterizedTest
    @EnumSource(value = MemberRole.class, names = {"VIEWER", "MEMBER"})
    @DisplayName(
            "F-0530: a VIEWER/MEMBER workspace member is refused when creating a campaign, matching"
                    + " the requireRole(OWNER, ADMIN, MANAGER) gate update()/publish/delete already"
                    + " enforce -> ApiException, nothing persisted")
    void testLowPrivilegeRoleCannotCreateCampaign(MemberRole role) {
        AuthPrincipal principal =
                new AuthPrincipal(USER_ID, "brand@test.com", UserType.BRAND, WORKSPACE_ID);
        when(workspaceRepository.findById(WORKSPACE_ID)).thenReturn(Optional.of(brandWorkspace()));
        when(workspaceMemberRepository.findByWorkspaceIdAndUserIdAndActiveTrue(WORKSPACE_ID, USER_ID))
                .thenReturn(Optional.of(WorkspaceMember.fromInvite("mem1", WORKSPACE_ID, USER_ID, role)));

        ApiException ex =
                assertThrows(ApiException.class, () -> service.create(principal, writeRequest()));

        assertEquals("FORBIDDEN", ex.getCode());
        assertEquals(403, ex.getStatus().value());
        verify(campaignRepository, never()).save(any(Campaign.class));
    }

    @ParameterizedTest
    @EnumSource(value = MemberRole.class, names = {"OWNER", "ADMIN", "MANAGER"})
    @DisplayName("OWNER/ADMIN/MANAGER workspace members can still create a campaign")
    void testHighPrivilegeRoleCanCreateCampaign(MemberRole role) {
        AuthPrincipal principal =
                new AuthPrincipal(USER_ID, "brand@test.com", UserType.BRAND, WORKSPACE_ID);
        when(workspaceRepository.findById(WORKSPACE_ID)).thenReturn(Optional.of(brandWorkspace()));
        when(workspaceMemberRepository.findByWorkspaceIdAndUserIdAndActiveTrue(WORKSPACE_ID, USER_ID))
                .thenReturn(Optional.of(WorkspaceMember.fromInvite("mem1", WORKSPACE_ID, USER_ID, role)));
        when(campaignRepository.save(any(Campaign.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.create(principal, writeRequest());

        assertNotNull(response);
        verify(campaignRepository).save(any(Campaign.class));
    }

    // -----------------------------------------------------------------------------------------
    // F-0530 (Meera path): CampaignService#create was the REST write path's gap; the sibling gap
    // was Meera's create_campaign tool -- CreateCampaignExecutor (reached via
    // POST /internal/meera/create_campaign, MeeraInternalController) never called
    // brandContext.requireRole at all, so a VIEWER/MEMBER-role on-behalf principal could draft a
    // campaign purely because the on-behalf JWT's SCOPE claim authorized the create_campaign
    // TOOL -- scope says which tool may run, role says which member may act, and only the role
    // check was missing. These two cases exercise the SAME real brandContext.requireRole gate
    // this class's tests above already prove for CampaignService#create, now through
    // CreateCampaignExecutor#execute instead.
    // -----------------------------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(value = MemberRole.class, names = {"VIEWER", "MEMBER"})
    @DisplayName(
            "F-0530 (Meera path): a VIEWER/MEMBER on-behalf principal is refused when Meera's"
                    + " create_campaign tool executes, matching the REST create() gate -> ApiException,"
                    + " nothing persisted, idempotency ledger never touched")
    void testLowPrivilegeRoleCannotCreateCampaignViaMeera(MemberRole role) {
        when(workspaceMemberRepository.findByWorkspaceIdAndUserIdAndActiveTrue(WORKSPACE_ID, USER_ID))
                .thenReturn(Optional.of(WorkspaceMember.fromInvite("mem1", WORKSPACE_ID, USER_ID, role)));

        CreateCampaignExecutor executor = meeraCreateCampaignExecutor();
        Map<String, Object> input = Map.of("product_name", "Widget", "campaign_type", "STANDARD");

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                executor.execute(
                                        WORKSPACE_ID, "conv-1", USER_ID, UserType.BRAND, "idem-key-1", input));

        assertEquals("FORBIDDEN", ex.getCode());
        assertEquals(403, ex.getStatus().value());
        verify(campaignRepository, never()).save(any(Campaign.class));
        verify(toolCallRepository, never()).save(any());
        verify(idempotencyService, never()).executeOnce(any(), any(), any(), any());
    }

    @ParameterizedTest
    @EnumSource(value = MemberRole.class, names = {"OWNER", "ADMIN", "MANAGER"})
    @DisplayName("OWNER/ADMIN/MANAGER on-behalf principals can still create a campaign via Meera's create_campaign tool")
    void testHighPrivilegeRoleCanCreateCampaignViaMeera(MemberRole role) {
        when(workspaceMemberRepository.findByWorkspaceIdAndUserIdAndActiveTrue(WORKSPACE_ID, USER_ID))
                .thenReturn(Optional.of(WorkspaceMember.fromInvite("mem1", WORKSPACE_ID, USER_ID, role)));
        when(workspaceRepository.findById(WORKSPACE_ID)).thenReturn(Optional.of(brandWorkspace()));
        when(campaignIntentRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(campaignRepository.save(any(Campaign.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(idempotencyService.executeOnce(any(), any(), any(), any()))
                .thenAnswer(
                        invocation -> {
                            Supplier<CreateCampaignResult> action = invocation.getArgument(3);
                            return action.get();
                        });

        CreateCampaignExecutor executor = meeraCreateCampaignExecutor();
        Map<String, Object> input = Map.of("product_name", "Widget", "campaign_type", "STANDARD");

        CreateCampaignResult result =
                executor.execute(WORKSPACE_ID, "conv-1", USER_ID, UserType.BRAND, "idem-key-1", input);

        assertNotNull(result);
        assertEquals("DRAFT", result.status());
        verify(campaignRepository).save(any(Campaign.class));
    }

    private static Workspace brandWorkspace() {
        return Workspace.newBrand(WORKSPACE_ID, "Test Brand", "test-brand", "Fashion", "SMB");
    }

    private static CampaignWriteRequest writeRequest() {
        return new CampaignWriteRequest(
                "Test Campaign Title",
                "description",
                null,
                null,
                null,
                null,
                new BudgetDto(BigDecimal.TEN, BigDecimal.valueOf(100), "INR"),
                new TimelineDto(LocalDate.now().plusDays(1), LocalDate.now().plusDays(30)),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "Test Brand",
                "Test Category");
    }
}
