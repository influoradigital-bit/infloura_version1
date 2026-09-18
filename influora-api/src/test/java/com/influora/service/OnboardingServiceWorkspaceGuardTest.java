package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.config.R2Properties;
import com.influora.domain.entity.User;
import com.influora.domain.entity.Workspace;
import com.influora.domain.entity.WorkspaceMember;
import com.influora.domain.enums.MemberRole;
import com.influora.domain.enums.UserType;
import com.influora.domain.enums.VerificationStatus;
import com.influora.domain.enums.WorkspaceType;
import com.influora.integration.storage.R2StorageService;
import com.influora.repository.UserRepository;
import com.influora.repository.WorkspaceMemberRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.security.AuthPrincipal;
import com.influora.service.brand.AnalyzeSiteTriggerService;
import com.influora.web.dto.onboarding.OnboardingDtos.BrandCompanyRequest;
import com.influora.web.dto.onboarding.OnboardingDtos.KycRequest;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * An invitee's session can now be scoped to a workspace they do not own (workspace switching).
 * {@code users.onboarding_completed} is per USER, and the onboarding company step writes to
 * whatever workspace the session names, so an invitee whose own onboarding was unfinished was
 * sent to the wizard INSIDE the invited workspace and would have overwritten that brand's name,
 * slug and website. Found in QA review of the invite fixes.
 *
 * <p>Uses the REAL {@link BrandContextService}: with it mocked, {@code requireRole} is a no-op and
 * these tests would pass whether or not the gate exists.
 */
@ExtendWith(MockitoExtension.class)
class OnboardingServiceWorkspaceGuardTest {

    private static final String INVITED_WS = "01HWORKSPACEINVITED0AB";
    private static final String USER_ID = "01HUSERINVITEE00000001";

    @Mock private UserRepository userRepository;
    @Mock private WorkspaceRepository workspaceRepository;
    @Mock private WorkspaceMemberRepository workspaceMemberRepository;
    @Mock private WorkspaceSlugService slugService;
    @Mock private AnalyzeSiteTriggerService analyzeSiteTrigger;
    @Mock private R2StorageService r2StorageService;
    @Mock private R2Properties r2Properties;

    private OnboardingService service;
    private Workspace invitedWorkspace;
    private final AuthPrincipal principal =
            new AuthPrincipal(USER_ID, "invitee@example.com", UserType.BRAND, INVITED_WS);

    @BeforeEach
    void setUp() {
        BrandContextService brandContext =
                new BrandContextService(workspaceRepository, workspaceMemberRepository, userRepository);
        service =
                new OnboardingService(
                        userRepository,
                        workspaceRepository,
                        brandContext,
                        slugService,
                        analyzeSiteTrigger,
                        r2StorageService,
                        r2Properties);
        invitedWorkspace = Workspace.newBrand(INVITED_WS, "Acme Co", "acme-co", "Retail", "SMB");
        when(workspaceRepository.findById(INVITED_WS)).thenReturn(Optional.of(invitedWorkspace));
        // The real BrandContextService#requireBrand refuses a caller whose account row is gone.
        org.mockito.Mockito.lenient()
                .when(userRepository.findById(USER_ID))
                .thenReturn(
                        Optional.of(User.newBrand(USER_ID, "invitee@example.com", "hash", "In", "Vitee", "In Vitee")));
    }

    private void memberWithRole(MemberRole role) {
        WorkspaceMember member =
                role == MemberRole.OWNER
                        ? WorkspaceMember.owner("01HMEMBER000000000001", INVITED_WS, USER_ID)
                        : WorkspaceMember.fromInvite("01HMEMBER000000000001", INVITED_WS, USER_ID, role);
        when(workspaceMemberRepository.findByWorkspaceIdAndUserIdAndActiveTrue(INVITED_WS, USER_ID))
                .thenReturn(Optional.of(member));
    }

    private static BrandCompanyRequest hostileCompanyRequest() {
        return new BrandCompanyRequest(
                "Invitee Overwrite Co", "invitee-overwrite", WorkspaceType.BRAND, "other", "STARTUP", null, null, null);
    }

    @Test
    @DisplayName("an invited MANAGER cannot rewrite the workspace through the onboarding company step")
    void saveBrandCompany_refusesANonAdminMember() {
        memberWithRole(MemberRole.MANAGER);

        ApiException ex =
                assertThrows(ApiException.class, () -> service.saveBrandCompany(principal, hostileCompanyRequest()));

        assertEquals(403, ex.getStatus().value());
        assertEquals("Acme Co", invitedWorkspace.getName());
        verify(workspaceRepository, never()).save(any());
    }

    @Test
    @DisplayName("the OWNER (every brand-new signup, in their own workspace) still can")
    void saveBrandCompany_allowsTheOwner() {
        memberWithRole(MemberRole.OWNER);
        when(workspaceRepository.save(any(Workspace.class))).thenAnswer(inv -> inv.getArgument(0));

        service.saveBrandCompany(principal, hostileCompanyRequest());

        assertEquals("Invitee Overwrite Co", invitedWorkspace.getName());
    }

    @Test
    @DisplayName("an invited VIEWER cannot resubmit KYC and knock a verified workspace back to PENDING")
    void submitBrandKyc_refusesANonAdminMember() {
        memberWithRole(MemberRole.VIEWER);
        invitedWorkspace.applyKyc("27ABCDE1234F1Z5", "ABCDE1234F", "k1", "k2");
        VerificationStatus before = invitedWorkspace.getVerificationStatus();

        assertThrows(
                ApiException.class,
                () ->
                        service.submitBrandKyc(
                                principal, new KycRequest("29ZZZZZ9999Z1Z9", "ZZZZZ9999Z", "x1", "x2")));

        assertEquals(before, invitedWorkspace.getVerificationStatus());
        assertEquals("27ABCDE1234F1Z5", invitedWorkspace.getGstin());
        verify(workspaceRepository, never()).save(any());
    }

    @Test
    @DisplayName(
            "onboarding status: a guest in another brand's workspace has nothing to onboard there, even"
                    + " if their OWN onboarding is unfinished, so the route guard must not send them to the wizard")
    void onboardingStatus_guestIsNeverSentToTheWizard() {
        memberWithRole(MemberRole.MANAGER);
        User invitee = User.newBrand(USER_ID, "invitee@example.com", "hash", "In", "Vitee", "In Vitee");
        assertFalse(invitee.isOnboardingCompleted());
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(invitee));

        assertTrue(service.getBrandOnboardingStatus(principal).onboardingCompleted());
    }

    @Test
    @DisplayName("onboarding status: an OWNER who has not finished is still sent to the wizard")
    void onboardingStatus_unfinishedOwnerStillOnboards() {
        memberWithRole(MemberRole.OWNER);
        User owner = User.newBrand(USER_ID, "owner@example.com", "hash", "Ow", "Ner", "Ow Ner");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(owner));

        assertFalse(service.getBrandOnboardingStatus(principal).onboardingCompleted());
    }
}
