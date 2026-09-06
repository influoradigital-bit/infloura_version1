package com.influora.service.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.config.AdminSecurityProperties;
import com.influora.domain.entity.AdminUser;
import com.influora.domain.enums.AdminRole;
import com.influora.domain.enums.UserType;
import com.influora.repository.AdminRefreshTokenRepository;
import com.influora.repository.AdminUserRepository;
import com.influora.security.AuthPrincipal;
import com.influora.security.JwtService;
import com.influora.security.TotpService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * ADMIN-BOOTSTRAP-0829 permanent fix #2 — {@link AdminAuthService#resetMfaForAdmin}. Plain
 * Mockito, no {@code @SpringBootTest}, matching every other {@code Admin*ServiceTest} in this
 * package (see {@code AdminBrandServiceTest} class javadoc for why).
 *
 * <p>Proves the three authorization outcomes the ticket asks for: an authorized SUPER_ADMIN can
 * reset a DIFFERENT admin's MFA; a non-SUPER_ADMIN caller is rejected before any row is touched;
 * and a SUPER_ADMIN targeting their OWN {@code adminId} is rejected before any row is touched (the
 * self-reset guard that keeps mandatory MFA enforcement meaningful).
 */
@ExtendWith(MockitoExtension.class)
class AdminAuthServiceTest {

    private static final String CALLER_ID = "01HWXYZCALLER000000000A";
    private static final String TARGET_ID = "01HWXYZTARGET000000000B";

    @Mock private AdminUserRepository adminUserRepository;
    @Mock private AdminRefreshTokenRepository adminRefreshTokenRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JwtService jwtService;
    @Mock private TotpService totpService;
    @Mock private AdminContextService adminContext;
    @Mock private AdminMfaSecretCipher mfaSecretCipher;
    @Mock private AdminSecurityProperties adminSecurityProperties;

    private AdminAuthService service;
    private AuthPrincipal callerPrincipal;

    @BeforeEach
    void setUp() {
        service =
                new AdminAuthService(
                        adminUserRepository,
                        adminRefreshTokenRepository,
                        passwordEncoder,
                        jwtService,
                        totpService,
                        adminContext,
                        mfaSecretCipher,
                        adminSecurityProperties);
        callerPrincipal = new AuthPrincipal(CALLER_ID, "caller@influora.in", UserType.ADMIN, null);
    }

    private AdminUser targetAdmin() {
        return AdminUser.create(TARGET_ID, "target@influora.in", "hash", AdminRole.SUPER_ADMIN);
    }

    private AdminUser callerAsSuperAdmin() {
        return AdminUser.create(CALLER_ID, "caller@influora.in", "hash", AdminRole.SUPER_ADMIN);
    }

    @Test
    @DisplayName("authorized SUPER_ADMIN resets a DIFFERENT admin's MFA")
    void authorizedSuperAdminResetsAnotherAdminsMfa() {
        when(adminContext.requireRoleWithMfaSatisfied(callerPrincipal, AdminRole.SUPER_ADMIN))
                .thenReturn(callerAsSuperAdmin());
        AdminUser target = targetAdmin();
        target.confirmMfa(); // simulate the target currently has MFA enrolled
        when(adminUserRepository.findById(TARGET_ID)).thenReturn(Optional.of(target));

        service.resetMfaForAdmin(callerPrincipal, TARGET_ID);

        assertEquals(false, target.isMfaEnabled(), "target's mfaEnabled must be cleared");
        assertEquals(null, target.getEncryptedMfaSecret(), "target's stored secret must be cleared");
        verify(adminUserRepository, times(1)).save(target);
    }

    @Test
    @DisplayName(
            "F-0648: resetting a SUPER_ADMIN/ADMIN-tier target's MFA also revokes ALL of that"
                    + " target's active refresh tokens")
    void resetMfaRevokesTargetsRefreshTokens_superAdminTarget() {
        when(adminContext.requireRoleWithMfaSatisfied(callerPrincipal, AdminRole.SUPER_ADMIN))
                .thenReturn(callerAsSuperAdmin());
        AdminUser target = targetAdmin(); // AdminRole.SUPER_ADMIN
        target.confirmMfa();
        when(adminUserRepository.findById(TARGET_ID)).thenReturn(Optional.of(target));

        service.resetMfaForAdmin(callerPrincipal, TARGET_ID);

        // The exact mechanism AdminAuthService#logout already uses for the caller's own id —
        // reused here for the TARGET's id so the compromised session actually stops working.
        verify(adminRefreshTokenRepository, times(1)).revokeAllForAdmin(TARGET_ID);
    }

    @Test
    @DisplayName(
            "F-0648: resetting a SUPPORT-tier target's MFA also revokes ALL of that target's"
                    + " active refresh tokens — SUPPORT was previously exempt from any"
                    + " session-side effect here")
    void resetMfaRevokesTargetsRefreshTokens_supportTarget() {
        when(adminContext.requireRoleWithMfaSatisfied(callerPrincipal, AdminRole.SUPER_ADMIN))
                .thenReturn(callerAsSuperAdmin());
        AdminUser supportTarget =
                AdminUser.create(TARGET_ID, "support-target@influora.in", "hash", AdminRole.SUPPORT);
        when(adminUserRepository.findById(TARGET_ID)).thenReturn(Optional.of(supportTarget));

        service.resetMfaForAdmin(callerPrincipal, TARGET_ID);

        verify(adminRefreshTokenRepository, times(1)).revokeAllForAdmin(TARGET_ID);
    }

    @Test
    @DisplayName("non-SUPER_ADMIN caller is rejected — adminContext throws before any row is touched")
    void nonSuperAdminCallerIsRejected() {
        when(adminContext.requireRoleWithMfaSatisfied(callerPrincipal, AdminRole.SUPER_ADMIN))
                .thenThrow(
                        new ApiException(
                                "INSUFFICIENT_ROLE",
                                "This action requires a higher-privilege admin role",
                                HttpStatus.FORBIDDEN));

        ApiException ex =
                assertThrows(
                        ApiException.class, () -> service.resetMfaForAdmin(callerPrincipal, TARGET_ID));

        assertEquals("INSUFFICIENT_ROLE", ex.getCode());
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        verify(adminUserRepository, never()).findById(any());
        verify(adminUserRepository, never()).save(any());
    }

    @Test
    @DisplayName("self-reset attempt is rejected even for a SUPER_ADMIN caller")
    void selfResetIsRejected() {
        when(adminContext.requireRoleWithMfaSatisfied(callerPrincipal, AdminRole.SUPER_ADMIN))
                .thenReturn(callerAsSuperAdmin());

        ApiException ex =
                assertThrows(
                        ApiException.class, () -> service.resetMfaForAdmin(callerPrincipal, CALLER_ID));

        assertEquals("CANNOT_RESET_OWN_MFA", ex.getCode());
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        verify(adminUserRepository, never()).findById(any());
        verify(adminUserRepository, never()).save(any());
    }
}
