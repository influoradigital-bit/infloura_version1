package com.influora.service.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.AdminUser;
import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.FestivalEnquiry;
import com.influora.domain.entity.PasswordResetToken;
import com.influora.domain.entity.User;
import com.influora.domain.entity.Wallet;
import com.influora.domain.entity.Workspace;
import com.influora.domain.entity.WorkspaceMember;
import com.influora.domain.enums.AdminRole;
import com.influora.domain.enums.FestivalEnquiryStatus;
import com.influora.domain.enums.FestivalTier;
import com.influora.domain.enums.MemberRole;
import com.influora.domain.enums.UserType;
import com.influora.domain.enums.WorkspaceType;
import com.influora.repository.CampaignRepository;
import com.influora.repository.FestivalEnquiryRepository;
import com.influora.repository.PasswordResetTokenRepository;
import com.influora.repository.UserRepository;
import com.influora.repository.WalletRepository;
import com.influora.repository.WorkspaceMemberRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.security.AuthPrincipal;
import com.influora.security.JwtService;
import com.influora.service.notification.event.PasswordResetEvent;
import com.influora.web.dto.admin.FestivalSponsorProvisioningDtos.ExistingSponsorAccountResponse;
import com.influora.web.dto.admin.FestivalSponsorProvisioningDtos.LinkExistingSponsorResponse;
import com.influora.web.dto.admin.FestivalSponsorProvisioningDtos.ProvisionFestivalSponsorResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Field;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * T-FESTIVALBOX-0905 phase 2 — {@link FestivalSponsorProvisioningService}.
 *
 * <p>Plain Mockito, matches every other {@code Admin*ServiceTest} in this package (see e.g.
 * {@code AdminCreatorConnectionServiceTest}). {@code adminContext} is stubbed to pass RBAC in
 * every test here; the tests are about the provisioning logic, not about
 * {@code AdminContextService} (which has its own tests).
 */
@ExtendWith(MockitoExtension.class)
class FestivalSponsorProvisioningServiceTest {

    private static final String ENQUIRY_ID = "01HENQUIRY0000000000001";

    @Mock private AdminContextService adminContext;
    @Mock private AdminAuditLogService adminAuditLogService;
    @Mock private FestivalEnquiryRepository festivalEnquiryRepository;
    @Mock private UserRepository userRepository;
    @Mock private WorkspaceRepository workspaceRepository;
    @Mock private WorkspaceMemberRepository workspaceMemberRepository;
    @Mock private WalletRepository walletRepository;
    @Mock private CampaignRepository campaignRepository;
    @Mock private PasswordResetTokenRepository passwordResetTokenRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JwtService jwtService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private HttpServletRequest httpRequest;

    private AuthPrincipal principal;
    private FestivalSponsorProvisioningService service;

    @BeforeEach
    void setUp() throws Exception {
        principal = new AuthPrincipal("01HADMIN00000000000001", "admin@influora.in", UserType.ADMIN, null);

        service =
                new FestivalSponsorProvisioningService(
                        adminContext,
                        adminAuditLogService,
                        festivalEnquiryRepository,
                        userRepository,
                        workspaceRepository,
                        workspaceMemberRepository,
                        walletRepository,
                        campaignRepository,
                        passwordResetTokenRepository,
                        passwordEncoder,
                        jwtService,
                        eventPublisher);
        Field f = FestivalSponsorProvisioningService.class.getDeclaredField("webBaseUrl");
        f.setAccessible(true);
        f.set(service, "http://localhost:5173");

        when(adminContext.requireRoleWithMfaSatisfied(principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN))
                .thenReturn(mock(AdminUser.class));
    }

    private FestivalEnquiry wonBrandEnquiry() {
        FestivalEnquiry e =
                FestivalEnquiry.brand(
                        ENQUIRY_ID,
                        "MUMBAI_FESTIVE_2026",
                        "Jane Doe",
                        "jane@acme.com",
                        "9999999999",
                        "Acme Corp",
                        "https://acme.com",
                        FestivalTier.FEATURED,
                        "Beauty",
                        "We want in");
        e.applyStatus(FestivalEnquiryStatus.WON, "01HSOMEADMIN0000000001", null);
        return e;
    }

    // ------------------------------------------------------------------------------------------
    // Happy path — exact insert order
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("happy path: creates user, workspace, member, wallet, campaign and reset token in"
            + " that exact order, then updates the enquiry and audit-logs")
    void provision_happyPath_insertsInExactOrder() {
        FestivalEnquiry enquiry = wonBrandEnquiry();
        when(festivalEnquiryRepository.findByIdForUpdate(ENQUIRY_ID)).thenReturn(Optional.of(enquiry));
        when(userRepository.existsByEmailIgnoreCase("jane@acme.com")).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("bcrypt-hash");
        when(jwtService.createRefreshTokenValue()).thenReturn("raw-token-value");

        ProvisionFestivalSponsorResponse response =
                service.provision(principal, httpRequest, ENQUIRY_ID);

        assertEquals(ENQUIRY_ID, response.enquiryId());
        assertNotNull(response.userId());
        assertNotNull(response.workspaceId());
        assertNotNull(response.campaignId());
        assertNotNull(response.provisionedAt());

        InOrder order =
                inOrder(
                        userRepository,
                        workspaceRepository,
                        workspaceMemberRepository,
                        walletRepository,
                        campaignRepository,
                        passwordResetTokenRepository,
                        eventPublisher,
                        festivalEnquiryRepository,
                        adminAuditLogService);
        order.verify(userRepository).saveAndFlush(any(User.class));
        order.verify(workspaceRepository).save(any(Workspace.class));
        order.verify(workspaceMemberRepository).save(any(WorkspaceMember.class));
        order.verify(walletRepository).save(any(Wallet.class));
        order.verify(campaignRepository).save(any(Campaign.class));
        order.verify(passwordResetTokenRepository).save(any(PasswordResetToken.class));
        order.verify(eventPublisher).publishEvent(any(PasswordResetEvent.class));
        order.verify(festivalEnquiryRepository).save(enquiry);
        order.verify(adminAuditLogService)
                .record(
                        eq(principal),
                        eq(httpRequest),
                        eq("CREATE"),
                        eq("FESTIVAL_SPONSOR_PROVISIONING"),
                        eq(ENQUIRY_ID),
                        any(),
                        any(),
                        eq(null));

        assertEquals(response.userId(), enquiry.getProvisionedUserId());
        assertEquals(response.workspaceId(), enquiry.getProvisionedWorkspaceId());
        assertEquals(response.campaignId(), enquiry.getProvisionedCampaignId());
        assertNotNull(enquiry.getProvisionedAt());

        ArgumentCaptor<Campaign> campaignCaptor = ArgumentCaptor.forClass(Campaign.class);
        verify(campaignRepository).save(campaignCaptor.capture());
        Campaign campaign = campaignCaptor.getValue();
        assertEquals(response.workspaceId(), campaign.getWorkspaceId());
        assertEquals(response.userId(), campaign.getCreatedBy());
        assertEquals(com.influora.domain.enums.CampaignStatus.DRAFT, campaign.getStatus());
        assertTrue(campaign.getTitle().contains("Acme Corp"));
    }

    // ------------------------------------------------------------------------------------------
    // Idempotency
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a second provision call on an already-provisioned enquiry returns"
            + " 409 ALREADY_PROVISIONED and creates nothing")
    void provision_alreadyProvisioned_returns409AndCreatesNothing() {
        FestivalEnquiry enquiry = wonBrandEnquiry();
        enquiry.markProvisioned("01HUSER00000000000001", "01HWORKSPACE000000001", "01HCAMPAIGN0000000001", java.time.Instant.now());
        when(festivalEnquiryRepository.findByIdForUpdate(ENQUIRY_ID)).thenReturn(Optional.of(enquiry));

        ApiException ex =
                assertThrows(
                        ApiException.class, () -> service.provision(principal, httpRequest, ENQUIRY_ID));

        assertEquals("ALREADY_PROVISIONED", ex.getCode());
        assertEquals(409, ex.getStatus().value());
        verify(userRepository, never()).saveAndFlush(any());
        verify(workspaceRepository, never()).save(any());
        verify(campaignRepository, never()).save(any());
    }

    // ------------------------------------------------------------------------------------------
    // Existing account refusal
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("an enquiry email that already has a User is refused with"
            + " 409 EMAIL_ALREADY_REGISTERED, never grafted onto the existing account")
    void provision_emailAlreadyRegistered_returns409AndCreatesNoWorkspace() {
        FestivalEnquiry enquiry = wonBrandEnquiry();
        when(festivalEnquiryRepository.findByIdForUpdate(ENQUIRY_ID)).thenReturn(Optional.of(enquiry));
        when(userRepository.existsByEmailIgnoreCase("jane@acme.com")).thenReturn(true);

        ApiException ex =
                assertThrows(
                        ApiException.class, () -> service.provision(principal, httpRequest, ENQUIRY_ID));

        assertEquals("EMAIL_ALREADY_REGISTERED", ex.getCode());
        assertEquals(409, ex.getStatus().value());
        verify(userRepository, never()).saveAndFlush(any());
        verify(workspaceRepository, never()).save(any());
        verify(workspaceMemberRepository, never()).save(any());
        verify(walletRepository, never()).save(any());
        verify(campaignRepository, never()).save(any());
    }

    // ------------------------------------------------------------------------------------------
    // Status / type guards
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("an enquiry not in WON status is refused with 400 ENQUIRY_NOT_WON")
    void provision_notWon_returns400() {
        FestivalEnquiry enquiry =
                FestivalEnquiry.brand(
                        ENQUIRY_ID,
                        "MUMBAI_FESTIVE_2026",
                        "Jane Doe",
                        "jane@acme.com",
                        "9999999999",
                        "Acme Corp",
                        "https://acme.com",
                        FestivalTier.FEATURED,
                        "Beauty",
                        "message");
        enquiry.applyStatus(FestivalEnquiryStatus.CONTACTED, "01HSOMEADMIN0000000001", null);
        when(festivalEnquiryRepository.findByIdForUpdate(ENQUIRY_ID)).thenReturn(Optional.of(enquiry));

        ApiException ex =
                assertThrows(
                        ApiException.class, () -> service.provision(principal, httpRequest, ENQUIRY_ID));

        assertEquals("ENQUIRY_NOT_WON", ex.getCode());
        assertEquals(400, ex.getStatus().value());
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("a CREATOR-type enquiry is refused with 400 ENQUIRY_NOT_BRAND regardless of status")
    void provision_creatorType_returns400() {
        FestivalEnquiry enquiry =
                FestivalEnquiry.creator(
                        ENQUIRY_ID,
                        "MUMBAI_FESTIVE_2026",
                        "Jane Creator",
                        "jane@creator.com",
                        "9999999999",
                        "jane.creates",
                        50_000L,
                        "Mumbai",
                        "message");
        when(festivalEnquiryRepository.findByIdForUpdate(ENQUIRY_ID)).thenReturn(Optional.of(enquiry));

        ApiException ex =
                assertThrows(
                        ApiException.class, () -> service.provision(principal, httpRequest, ENQUIRY_ID));

        assertEquals("ENQUIRY_NOT_BRAND", ex.getCode());
        assertEquals(400, ex.getStatus().value());
        verify(userRepository, never()).saveAndFlush(any());
    }

    // ------------------------------------------------------------------------------------------
    // Password hash cannot be derived from any known password
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("the raw value passed to the password encoder is a fresh high-entropy random"
            + " string, never a guessable/known password, and no password is ever returned")
    void provision_generatesUnguessableRandomPassword() {
        FestivalEnquiry enquiry = wonBrandEnquiry();
        when(festivalEnquiryRepository.findByIdForUpdate(ENQUIRY_ID)).thenReturn(Optional.of(enquiry));
        when(userRepository.existsByEmailIgnoreCase(anyString())).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("bcrypt-hash");
        when(jwtService.createRefreshTokenValue()).thenReturn("raw-token-value");

        service.provision(principal, httpRequest, ENQUIRY_ID);

        ArgumentCaptor<String> rawPasswordCaptor = ArgumentCaptor.forClass(String.class);
        verify(passwordEncoder).encode(rawPasswordCaptor.capture());
        String rawPassword = rawPasswordCaptor.getValue();

        // 32 random bytes, base64url without padding -> 43 chars. Far longer/higher-entropy than
        // any real password, and drawn from SecureRandom -- not derived from the enquiry's name,
        // email, company, or any common/weak password string.
        assertTrue(rawPassword.length() >= 40, "expected a high-entropy generated value");
        assertNotEquals("password", rawPassword);
        assertNotEquals("12345678", rawPassword);
        assertFalse(rawPassword.contains("jane"));
        assertFalse(rawPassword.toLowerCase(java.util.Locale.ROOT).contains("acme"));
    }

    // ------------------------------------------------------------------------------------------
    // Reset token stored as a HASH, never the raw value
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("the persisted PasswordResetToken stores the SHA-256 hash of the raw token, not"
            + " the raw token itself, and the emailed link carries the raw token")
    void provision_storesTokenHashNotRawValue() {
        FestivalEnquiry enquiry = wonBrandEnquiry();
        when(festivalEnquiryRepository.findByIdForUpdate(ENQUIRY_ID)).thenReturn(Optional.of(enquiry));
        when(userRepository.existsByEmailIgnoreCase(anyString())).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("bcrypt-hash");
        when(jwtService.createRefreshTokenValue()).thenReturn("raw-token-value");

        service.provision(principal, httpRequest, ENQUIRY_ID);

        ArgumentCaptor<PasswordResetToken> tokenCaptor = ArgumentCaptor.forClass(PasswordResetToken.class);
        verify(passwordResetTokenRepository).save(tokenCaptor.capture());
        PasswordResetToken saved = tokenCaptor.getValue();

        String expectedHash = JwtService.hashToken("raw-token-value");
        assertEquals(expectedHash, saved.getTokenHash());
        assertNotEquals("raw-token-value", saved.getTokenHash());

        ArgumentCaptor<PasswordResetEvent> eventCaptor = ArgumentCaptor.forClass(PasswordResetEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        PasswordResetEvent event = eventCaptor.getValue();
        assertTrue(event.resetLink().contains("token=raw-token-value"));
        assertEquals("jane@acme.com", event.email());

        // 7-day expiry, not forgot-password's 1 hour.
        long secondsUntilExpiry = saved.getExpiresAt().getEpochSecond() - java.time.Instant.now().getEpochSecond();
        assertTrue(secondsUntilExpiry > java.time.Duration.ofDays(6).getSeconds());
        assertTrue(secondsUntilExpiry <= java.time.Duration.ofDays(7).getSeconds() + 5);
    }

    @Test
    @DisplayName("the provisioned user is email-verified, or they can never log in (403 EMAIL_NOT_VERIFIED)")
    void provisionedUserIsEmailVerifiedSoLoginIsPossible() {
        // REGRESSION PIN. AuthService#login refuses with 403 EMAIL_NOT_VERIFIED when all three of
        // these hold: requireEmailVerification (a hardcoded `true` in application.yml) &&
        // !user.isEmailVerified() && status == PENDING_VERIFICATION. User.newBrand sets the latter
        // two, so without an explicit setEmailVerified(true) EVERY provisioned brand could set a
        // password via the emailed link and then be locked out permanently, with no self-service
        // recovery. The whole feature was dead on arrival and no other test noticed, because none
        // of them assert anything about the user's post-provisioning login eligibility.
        //
        // Delete the setEmailVerified(true) line in the service and this test fails.
        FestivalEnquiry enquiry = wonBrandEnquiry();
        when(festivalEnquiryRepository.findByIdForUpdate(ENQUIRY_ID)).thenReturn(Optional.of(enquiry));
        when(userRepository.existsByEmailIgnoreCase("jane@acme.com")).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("bcrypt-hash");
        when(jwtService.createRefreshTokenValue()).thenReturn("raw-token-value");

        service.provision(principal, httpRequest, ENQUIRY_ID);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(userCaptor.capture());
        User saved = userCaptor.getValue();

        assertTrue(
                saved.isEmailVerified(),
                "provisioned user must be email-verified; control of the mailbox is proven by the "
                        + "single-use token emailed to that exact address, which is the only way "
                        + "this account can ever be signed into");
    }

    // ============================================================================================
    // Phase 8 — linkExisting (returning-sponsor path)
    // ============================================================================================

    private static final String EXISTING_WORKSPACE_ID = "01HWORKSPACE0RETURNING01";
    private static final String EXISTING_OWNER_USER_ID = "01HUSEROWNER00000000001";

    private Workspace existingBrandWorkspace() {
        return Workspace.newBrand(EXISTING_WORKSPACE_ID, "Acme Corp", "acme-corp", "Beauty", null);
    }

    /**
     * [Kabir H-2] Stubs the server-side enquiry-to-workspace binding that {@code linkExisting} now
     * requires: the enquiry's own email resolves to a user who is an ACTIVE member of the target
     * workspace. Every happy path must set this up, because without it the link is refused — which
     * is the entire point of the fix.
     */
    private void bindEnquiryEmailToWorkspace(String workspaceId) {
        User enquiryUser = mock(User.class);
        lenient().when(enquiryUser.getId()).thenReturn(EXISTING_OWNER_USER_ID);
        lenient()
                .when(userRepository.findByEmailIgnoreCase("jane@acme.com"))
                .thenReturn(Optional.of(enquiryUser));
        lenient()
                .when(
                        workspaceMemberRepository.findByWorkspaceIdAndUserIdAndActiveTrue(
                                workspaceId, EXISTING_OWNER_USER_ID))
                .thenReturn(
                        Optional.of(
                                WorkspaceMember.owner(
                                        "01HMEMBER00000000000002", workspaceId, EXISTING_OWNER_USER_ID)));
    }

    @Test
    @DisplayName("linkExisting: creates only a DRAFT Campaign on the existing workspace, creates NO"
            + " User/Workspace/Wallet, sends no email, and sets link-back fields with"
            + " provisionedUserId null")
    void linkExisting_happyPath_createsOnlyCampaign() {
        FestivalEnquiry enquiry = wonBrandEnquiry();
        when(festivalEnquiryRepository.findByIdForUpdate(ENQUIRY_ID)).thenReturn(Optional.of(enquiry));
        when(workspaceRepository.findById(EXISTING_WORKSPACE_ID))
                .thenReturn(Optional.of(existingBrandWorkspace()));
        bindEnquiryEmailToWorkspace(EXISTING_WORKSPACE_ID);
        when(workspaceMemberRepository.findFirstByWorkspaceIdAndRoleAndActiveTrue(
                        EXISTING_WORKSPACE_ID, MemberRole.OWNER))
                .thenReturn(
                        Optional.of(
                                WorkspaceMember.owner(
                                        "01HMEMBER00000000000001", EXISTING_WORKSPACE_ID, EXISTING_OWNER_USER_ID)));

        LinkExistingSponsorResponse response =
                service.linkExisting(principal, httpRequest, ENQUIRY_ID, EXISTING_WORKSPACE_ID);

        assertEquals(ENQUIRY_ID, response.enquiryId());
        assertEquals(EXISTING_WORKSPACE_ID, response.workspaceId());
        assertNotNull(response.campaignId());
        assertNotNull(response.provisionedAt());
        assertTrue(response.linkedExisting());

        // No new identity/account rows of any kind.
        verify(userRepository, never()).saveAndFlush(any());
        verify(userRepository, never()).save(any());
        verify(workspaceRepository, never()).save(any());
        verify(workspaceMemberRepository, never()).save(any());
        verify(walletRepository, never()).save(any());
        verify(passwordResetTokenRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());

        // Exactly one Campaign, DRAFT, on the existing workspace, attributed to its real owner.
        ArgumentCaptor<Campaign> campaignCaptor = ArgumentCaptor.forClass(Campaign.class);
        verify(campaignRepository).save(campaignCaptor.capture());
        Campaign campaign = campaignCaptor.getValue();
        assertEquals(EXISTING_WORKSPACE_ID, campaign.getWorkspaceId());
        assertEquals(com.influora.domain.enums.CampaignStatus.DRAFT, campaign.getStatus());
        assertEquals(EXISTING_OWNER_USER_ID, campaign.getCreatedBy());
        assertEquals(response.campaignId(), campaign.getId());

        // Link-back fields: provisionedUserId is the honest null — no User was created.
        assertEquals(EXISTING_WORKSPACE_ID, enquiry.getProvisionedWorkspaceId());
        assertEquals(response.campaignId(), enquiry.getProvisionedCampaignId());
        assertNotNull(enquiry.getProvisionedAt());
        assertEquals(null, enquiry.getProvisionedUserId());

        verify(festivalEnquiryRepository).save(enquiry);
    }

    @Test
    @DisplayName("linkExisting: audit-logged after the write")
    void linkExisting_isAuditLogged() {
        FestivalEnquiry enquiry = wonBrandEnquiry();
        when(festivalEnquiryRepository.findByIdForUpdate(ENQUIRY_ID)).thenReturn(Optional.of(enquiry));
        when(workspaceRepository.findById(EXISTING_WORKSPACE_ID))
                .thenReturn(Optional.of(existingBrandWorkspace()));
        bindEnquiryEmailToWorkspace(EXISTING_WORKSPACE_ID);
        when(workspaceMemberRepository.findFirstByWorkspaceIdAndRoleAndActiveTrue(
                        EXISTING_WORKSPACE_ID, MemberRole.OWNER))
                .thenReturn(
                        Optional.of(
                                WorkspaceMember.owner(
                                        "01HMEMBER00000000000001", EXISTING_WORKSPACE_ID, EXISTING_OWNER_USER_ID)));

        service.linkExisting(principal, httpRequest, ENQUIRY_ID, EXISTING_WORKSPACE_ID);

        verify(adminAuditLogService)
                .record(
                        eq(principal),
                        eq(httpRequest),
                        eq("CREATE"),
                        eq("FESTIVAL_SPONSOR_PROVISIONING"),
                        eq(ENQUIRY_ID),
                        any(),
                        any(),
                        eq(null));
    }

    @Test
    @DisplayName("linkExisting: a second call on the same already-provisioned enquiry returns"
            + " 409 ALREADY_PROVISIONED and creates no Campaign")
    void linkExisting_alreadyProvisioned_returns409() {
        FestivalEnquiry enquiry = wonBrandEnquiry();
        enquiry.markProvisioned(
                "01HUSER00000000000001",
                "01HWORKSPACE000000001",
                "01HCAMPAIGN0000000001",
                java.time.Instant.now());
        when(festivalEnquiryRepository.findByIdForUpdate(ENQUIRY_ID)).thenReturn(Optional.of(enquiry));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.linkExisting(principal, httpRequest, ENQUIRY_ID, EXISTING_WORKSPACE_ID));

        assertEquals("ALREADY_PROVISIONED", ex.getCode());
        assertEquals(409, ex.getStatus().value());
        verify(campaignRepository, never()).save(any());
        verify(workspaceRepository, never()).findById(anyString());
    }

    @Test
    @DisplayName("linkExisting: a non-WON enquiry is refused with 400 ENQUIRY_NOT_WON")
    void linkExisting_notWon_returns400() {
        FestivalEnquiry enquiry =
                FestivalEnquiry.brand(
                        ENQUIRY_ID,
                        "MUMBAI_FESTIVE_2026",
                        "Jane Doe",
                        "jane@acme.com",
                        "9999999999",
                        "Acme Corp",
                        "https://acme.com",
                        FestivalTier.FEATURED,
                        "Beauty",
                        "message");
        enquiry.applyStatus(FestivalEnquiryStatus.CONTACTED, "01HSOMEADMIN0000000001", null);
        when(festivalEnquiryRepository.findByIdForUpdate(ENQUIRY_ID)).thenReturn(Optional.of(enquiry));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.linkExisting(principal, httpRequest, ENQUIRY_ID, EXISTING_WORKSPACE_ID));

        assertEquals("ENQUIRY_NOT_WON", ex.getCode());
        assertEquals(400, ex.getStatus().value());
        verify(campaignRepository, never()).save(any());
    }

    @Test
    @DisplayName("linkExisting: a CREATOR-type enquiry is refused with 400 ENQUIRY_NOT_BRAND")
    void linkExisting_creatorType_returns400() {
        FestivalEnquiry enquiry =
                FestivalEnquiry.creator(
                        ENQUIRY_ID,
                        "MUMBAI_FESTIVE_2026",
                        "Jane Creator",
                        "jane@creator.com",
                        "9999999999",
                        "jane.creates",
                        50_000L,
                        "Mumbai",
                        "message");
        when(festivalEnquiryRepository.findByIdForUpdate(ENQUIRY_ID)).thenReturn(Optional.of(enquiry));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.linkExisting(principal, httpRequest, ENQUIRY_ID, EXISTING_WORKSPACE_ID));

        assertEquals("ENQUIRY_NOT_BRAND", ex.getCode());
        assertEquals(400, ex.getStatus().value());
        verify(campaignRepository, never()).save(any());
    }

    @Test
    @DisplayName("linkExisting: an unknown workspaceId is refused with 404 WORKSPACE_NOT_FOUND")
    void linkExisting_unknownWorkspace_returns404() {
        FestivalEnquiry enquiry = wonBrandEnquiry();
        when(festivalEnquiryRepository.findByIdForUpdate(ENQUIRY_ID)).thenReturn(Optional.of(enquiry));
        when(workspaceRepository.findById("nope")).thenReturn(Optional.empty());

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.linkExisting(principal, httpRequest, ENQUIRY_ID, "nope"));

        assertEquals("WORKSPACE_NOT_FOUND", ex.getCode());
        assertEquals(404, ex.getStatus().value());
        verify(campaignRepository, never()).save(any());
    }

    @Test
    @DisplayName("linkExisting: a non-BRAND (AGENCY) workspace is refused with 400"
            + " WORKSPACE_NOT_BRAND — distinct code from the unknown-workspace case")
    void linkExisting_nonBrandWorkspace_returns400() {
        FestivalEnquiry enquiry = wonBrandEnquiry();
        when(festivalEnquiryRepository.findByIdForUpdate(ENQUIRY_ID)).thenReturn(Optional.of(enquiry));
        Workspace agencyWorkspace = mock(Workspace.class);
        when(agencyWorkspace.getType()).thenReturn(WorkspaceType.AGENCY);
        when(workspaceRepository.findById(EXISTING_WORKSPACE_ID)).thenReturn(Optional.of(agencyWorkspace));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.linkExisting(
                                        principal, httpRequest, ENQUIRY_ID, EXISTING_WORKSPACE_ID));

        assertEquals("WORKSPACE_NOT_BRAND", ex.getCode());
        assertEquals(400, ex.getStatus().value());
        verify(campaignRepository, never()).save(any());
    }

    // --------------------------------------------------------------------------------------------
    // [Kabir H-2] The server-side binding between the enquiry and the workspace it is linked to.
    // Before this, workspaceId was taken on the caller's word: any BRAND workspace on the platform
    // could be named and would receive a DRAFT campaign titled after this enquiry's company, with
    // the enquiry permanently stamped provisioned against it.
    // --------------------------------------------------------------------------------------------

    @Test
    @DisplayName("[H-2] a BRAND workspace the enquiry's contact is NOT a member of is refused with"
            + " 400 WORKSPACE_NOT_LINKED_TO_ENQUIRY, and no Campaign is written")
    void linkExisting_workspaceNotLinkedToEnquiry_returns400() {
        FestivalEnquiry enquiry = wonBrandEnquiry();
        when(festivalEnquiryRepository.findByIdForUpdate(ENQUIRY_ID)).thenReturn(Optional.of(enquiry));

        // A real, perfectly valid BRAND workspace — it just belongs to somebody else. This is the
        // shape of the actual failure: a mis-picked id in an admin console listing several
        // similarly-named brands, not a malformed request.
        String unrelatedWorkspaceId = "01HWORKSPACE0UNRELATED01";
        when(workspaceRepository.findById(unrelatedWorkspaceId))
                .thenReturn(
                        Optional.of(
                                Workspace.newBrand(
                                        unrelatedWorkspaceId, "Other Brand", "other-brand", "Fashion", null)));

        User enquiryUser = mock(User.class);
        when(enquiryUser.getId()).thenReturn(EXISTING_OWNER_USER_ID);
        when(userRepository.findByEmailIgnoreCase("jane@acme.com")).thenReturn(Optional.of(enquiryUser));
        // The enquiry's contact has no active membership in THAT workspace.
        when(workspaceMemberRepository.findByWorkspaceIdAndUserIdAndActiveTrue(
                        unrelatedWorkspaceId, EXISTING_OWNER_USER_ID))
                .thenReturn(Optional.empty());

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.linkExisting(principal, httpRequest, ENQUIRY_ID, unrelatedWorkspaceId));

        assertEquals("WORKSPACE_NOT_LINKED_TO_ENQUIRY", ex.getCode());
        assertEquals(400, ex.getStatus().value());

        // Nothing was written, and — critically — the enquiry was NOT stamped provisioned. The
        // provisionedWorkspaceId marker is one-shot: had it been set here, the correct link could
        // never be made afterwards (every retry would 409 ALREADY_PROVISIONED).
        verify(campaignRepository, never()).save(any());
        verify(festivalEnquiryRepository, never()).save(any());
        assertEquals(null, enquiry.getProvisionedWorkspaceId());
    }

    @Test
    @DisplayName("[H-2] an enquiry whose email has no account at all cannot be linked to anything")
    void linkExisting_noAccountForEnquiryEmail_returns400() {
        FestivalEnquiry enquiry = wonBrandEnquiry();
        when(festivalEnquiryRepository.findByIdForUpdate(ENQUIRY_ID)).thenReturn(Optional.of(enquiry));
        when(workspaceRepository.findById(EXISTING_WORKSPACE_ID))
                .thenReturn(Optional.of(existingBrandWorkspace()));
        when(userRepository.findByEmailIgnoreCase("jane@acme.com")).thenReturn(Optional.empty());

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.linkExisting(principal, httpRequest, ENQUIRY_ID, EXISTING_WORKSPACE_ID));

        // link-existing is for a RETURNING sponsor. No account means there is nothing to return to,
        // and the right action is provision(), which this message says.
        assertEquals("NO_EXISTING_ACCOUNT", ex.getCode());
        assertEquals(400, ex.getStatus().value());
        verify(campaignRepository, never()).save(any());
        verify(festivalEnquiryRepository, never()).save(any());
    }

    // ============================================================================================
    // Phase 8 — findExistingAccountForEmail (makes EMAIL_ALREADY_REGISTERED actionable)
    // ============================================================================================

    @Test
    @DisplayName("findExistingAccountForEmail: resolves the enquiry's email to the existing user's"
            + " oldest active workspace")
    void findExistingAccountForEmail_resolvesWorkspace() {
        FestivalEnquiry enquiry = wonBrandEnquiry();
        when(festivalEnquiryRepository.findById(ENQUIRY_ID)).thenReturn(Optional.of(enquiry));

        User existingUser = mock(User.class);
        when(existingUser.getId()).thenReturn(EXISTING_OWNER_USER_ID);
        when(userRepository.findByEmailIgnoreCase("jane@acme.com")).thenReturn(Optional.of(existingUser));

        WorkspaceMember membership =
                WorkspaceMember.owner("01HMEMBER00000000000001", EXISTING_WORKSPACE_ID, EXISTING_OWNER_USER_ID);
        when(workspaceMemberRepository.findFirstByUserIdAndActiveTrueOrderByCreatedAtAsc(
                        EXISTING_OWNER_USER_ID))
                .thenReturn(Optional.of(membership));

        Workspace workspace = existingBrandWorkspace();
        when(workspaceRepository.findById(EXISTING_WORKSPACE_ID)).thenReturn(Optional.of(workspace));

        ExistingSponsorAccountResponse response =
                service.findExistingAccountForEmail(principal, ENQUIRY_ID);

        assertEquals(EXISTING_OWNER_USER_ID, response.userId());
        assertEquals(EXISTING_WORKSPACE_ID, response.workspaceId());
        assertEquals("Acme Corp", response.workspaceName());
    }

    @Test
    @DisplayName("findExistingAccountForEmail: no user for the email returns 404 NO_EXISTING_ACCOUNT")
    void findExistingAccountForEmail_noUser_returns404() {
        FestivalEnquiry enquiry = wonBrandEnquiry();
        when(festivalEnquiryRepository.findById(ENQUIRY_ID)).thenReturn(Optional.of(enquiry));
        when(userRepository.findByEmailIgnoreCase("jane@acme.com")).thenReturn(Optional.empty());

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.findExistingAccountForEmail(principal, ENQUIRY_ID));

        assertEquals("NO_EXISTING_ACCOUNT", ex.getCode());
        assertEquals(404, ex.getStatus().value());
    }

    // --------------------------------------------------------------------------------------------
    // [Kabir M-5] The response must not carry another brand's Meta Pixel id. The email match behind
    // this endpoint is UNVERIFIED — a collision is precisely the case it exists to surface — so
    // anything it returns may describe a brand that has nothing to do with this enquiry.
    // --------------------------------------------------------------------------------------------

    @Test
    @DisplayName("[M-5] reports only WHETHER the workspace has a Meta Pixel, and no component of"
            + " the response can carry the id itself")
    void findExistingAccountForEmail_doesNotLeakMetaPixelId() {
        String realPixelId = "987654321098765";

        FestivalEnquiry enquiry = wonBrandEnquiry();
        when(festivalEnquiryRepository.findById(ENQUIRY_ID)).thenReturn(Optional.of(enquiry));

        User existingUser = mock(User.class);
        when(existingUser.getId()).thenReturn(EXISTING_OWNER_USER_ID);
        when(userRepository.findByEmailIgnoreCase("jane@acme.com")).thenReturn(Optional.of(existingUser));
        when(workspaceMemberRepository.findFirstByUserIdAndActiveTrueOrderByCreatedAtAsc(
                        EXISTING_OWNER_USER_ID))
                .thenReturn(
                        Optional.of(
                                WorkspaceMember.owner(
                                        "01HMEMBER00000000000001", EXISTING_WORKSPACE_ID, EXISTING_OWNER_USER_ID)));

        Workspace workspace = mock(Workspace.class);
        when(workspace.getId()).thenReturn(EXISTING_WORKSPACE_ID);
        when(workspace.getName()).thenReturn("Acme Corp");
        when(workspace.getMetaPixelId()).thenReturn(realPixelId);
        when(workspaceRepository.findById(EXISTING_WORKSPACE_ID)).thenReturn(Optional.of(workspace));

        ExistingSponsorAccountResponse response =
                service.findExistingAccountForEmail(principal, ENQUIRY_ID);

        assertTrue(response.hasMetaPixelId(), "a configured pixel should still be reported as present");

        // The id must not appear ANYWHERE in the response — asserting on a named field would only
        // prove the field we happen to remember, and would not catch someone reintroducing the
        // value through workspaceName or a new field. toString() covers every component the record
        // actually carries, including any added later.
        assertFalse(
                response.toString().contains(realPixelId),
                "ExistingSponsorAccountResponse must not carry another brand's Meta Pixel id: "
                        + response);
    }

    @Test
    @DisplayName("[M-5] a workspace with no Meta Pixel reports hasMetaPixelId=false")
    void findExistingAccountForEmail_noPixel_reportsFalse() {
        FestivalEnquiry enquiry = wonBrandEnquiry();
        when(festivalEnquiryRepository.findById(ENQUIRY_ID)).thenReturn(Optional.of(enquiry));

        User existingUser = mock(User.class);
        when(existingUser.getId()).thenReturn(EXISTING_OWNER_USER_ID);
        when(userRepository.findByEmailIgnoreCase("jane@acme.com")).thenReturn(Optional.of(existingUser));
        when(workspaceMemberRepository.findFirstByUserIdAndActiveTrueOrderByCreatedAtAsc(
                        EXISTING_OWNER_USER_ID))
                .thenReturn(
                        Optional.of(
                                WorkspaceMember.owner(
                                        "01HMEMBER00000000000001", EXISTING_WORKSPACE_ID, EXISTING_OWNER_USER_ID)));

        // existingBrandWorkspace() is built with a null metaPixelId — the common case, since no
        // sponsor has provided one yet.
        when(workspaceRepository.findById(EXISTING_WORKSPACE_ID))
                .thenReturn(Optional.of(existingBrandWorkspace()));

        ExistingSponsorAccountResponse response =
                service.findExistingAccountForEmail(principal, ENQUIRY_ID);

        assertFalse(response.hasMetaPixelId());
    }

    // ============================================================================================
    // Regression pin — provision() must still refuse EMAIL_ALREADY_REGISTERED and still create the
    // full set for a genuinely new sponsor. Covered by the pre-existing
    // provision_emailAlreadyRegistered_returns409AndCreatesNoWorkspace and
    // provision_happyPath_insertsInExactOrder tests above, which phase 8 leaves untouched.
    // ============================================================================================
}
