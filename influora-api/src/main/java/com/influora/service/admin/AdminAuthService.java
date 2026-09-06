package com.influora.service.admin;

import com.influora.common.ApiException;
import com.influora.common.Ulids;
import com.influora.config.AdminSecurityProperties;
import com.influora.domain.entity.AdminRefreshToken;
import com.influora.domain.entity.AdminUser;
import com.influora.domain.enums.AdminRole;
import com.influora.domain.enums.UserType;
import com.influora.repository.AdminRefreshTokenRepository;
import com.influora.repository.AdminUserRepository;
import com.influora.security.AuthPrincipal;
import com.influora.security.JwtService;
import com.influora.security.TotpService;
import com.influora.web.dto.admin.AdminAuthDtos.AdminUserDto;
import com.influora.web.dto.admin.AdminAuthDtos.LoginRequest;
import com.influora.web.dto.admin.AdminAuthDtos.LoginResponse;
import com.influora.web.dto.admin.AdminAuthDtos.MfaSetupResponse;
import java.time.Duration;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin login/session service (src/admin/TASK_ASSIGNMENTS.md P0: "Admin auth endpoints"). Mirrors
 * {@code AuthService}'s brand/creator login+refresh-rotation shape, scoped to {@code admin_users}
 * / {@code admin_refresh_tokens} (V34__admin_tables.sql).
 *
 * <p>JWTs are minted via the EXISTING {@link JwtService} with {@code UserType.ADMIN} — no changes
 * to that shared class, so the brand/creator token pipeline is untouched. {@code workspaceId} is
 * always null for admin tokens; role/MFA state is not carried in the JWT (it can change between
 * requests) and is instead re-read from {@code admin_users} on every privileged call via
 * {@link AdminContextService} + this service's {@link #me}.
 *
 * <p>[SEC: Kabir cycle 2 audit finding, ship-blocking per {@code
 * wiki/decisions/admin-panel-security-priority.md}] {@code admin_users.encrypted_mfa_secret} is
 * AES-256-GCM ciphertext ({@link AdminMfaSecretCipher}, same pattern as {@code MetaTokenStorage} /
 * {@code ConversionWebhookSecretService}). This service is the ONLY place that ever handles the
 * plaintext TOTP secret: {@link #setupMfa} generates it and encrypts it before it ever reaches
 * {@link AdminUser}/the database; {@link #login} and {@link #verifyMfa} decrypt just-in-time to
 * verify a submitted code. The plaintext is never logged.
 *
 * <p><b>P1 security hardening (Kabir §8 HIGH, 2026-07-12) — closed this cycle:</b>
 * <ul>
 *   <li>Failed-login lockout: {@link #login} now increments {@code admin_users
 *       .failed_login_attempts} on every wrong password/MFA code and locks the account for
 *       {@link AdminSecurityProperties#getLockoutCooldownSeconds()} once {@link
 *       AdminSecurityProperties#getLockoutMaxAttempts()} is reached (V20260712130000 migration).
 *       This is IN ADDITION TO, not instead of, {@code AuthRateLimitFilter}'s per-IP throttle —
 *       the filter limits request VOLUME from one IP, this limits attempts against one ACCOUNT
 *       regardless of source IP (credential stuffing across many IPs).
 *   <li>MFA-required-at-login for {@code SUPER_ADMIN}/{@code ADMIN} tier, gated by {@link
 *       AdminSecurityProperties#isMfaEnforceOnLogin()} (default {@code true} per CTO instruction)
 *       — see that class's javadoc for the full "this can lock out a pre-existing unenrolled
 *       admin, there is no in-app recovery path" decision writeup. {@code SUPPORT} tier is exempt,
 *       mirroring {@code AdminContextService#requireMfaSatisfied}'s identical role split.
 * </ul>
 *
 * <p><b>ADMIN-BOOTSTRAP-0829 (closed this cycle):</b> the two gaps below are now fixed.
 * <ul>
 *   <li>The very first {@code SUPER_ADMIN} row is provisioned by {@code
 *       scripts/provision-super-admin.sh} (a one-shot ops runner, {@code
 *       com.influora.ops.ProvisionSuperAdminRunner}) — it pre-enrolls MFA before ever saving the
 *       row, so it can never recreate the {@code mfaEnabled = false} + {@code
 *       ADMIN_MFA_ENFORCE_ON_LOGIN = true} deadlock that locked out {@code
 *       influoradigital@gmail.com}. General ADMIN/SUPPORT row creation still has no endpoint
 *       (future {@code AdminUserController}, P1, not this cycle's scope) — only the first-admin
 *       bootstrap case is covered.
 *   <li>{@link #resetMfaForAdmin} lets a SUPER_ADMIN clear ANOTHER admin's MFA enrollment (never
 *       their own — see that method's javadoc) so a legitimately locked-out admin can be recovered
 *       in-app instead of via a direct DB edit.
 * </ul>
 */
@Service
public class AdminAuthService {

    private final AdminUserRepository adminUserRepository;
    private final AdminRefreshTokenRepository adminRefreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final TotpService totpService;
    private final AdminContextService adminContext;
    private final AdminMfaSecretCipher mfaSecretCipher;
    private final AdminSecurityProperties adminSecurityProperties;

    public AdminAuthService(
            AdminUserRepository adminUserRepository,
            AdminRefreshTokenRepository adminRefreshTokenRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            TotpService totpService,
            AdminContextService adminContext,
            AdminMfaSecretCipher mfaSecretCipher,
            AdminSecurityProperties adminSecurityProperties) {
        this.adminUserRepository = adminUserRepository;
        this.adminRefreshTokenRepository = adminRefreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.totpService = totpService;
        this.adminContext = adminContext;
        this.mfaSecretCipher = mfaSecretCipher;
        this.adminSecurityProperties = adminSecurityProperties;
    }

    @Transactional
    public LoginResponse login(LoginRequest req) {
        AdminUser admin =
                adminUserRepository
                        .findByEmailIgnoreCase(req.email())
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "INVALID_CREDENTIALS",
                                                "Invalid email or password",
                                                HttpStatus.UNAUTHORIZED));

        Instant now = Instant.now();
        if (admin.isLockedOut(now)) {
            // Kabir P1-2 Gap 3 (MEDIUM) — generic error to prevent lockout enumeration
            throw new ApiException(
                    "INVALID_CREDENTIALS",
                    "Invalid email or password",
                    HttpStatus.UNAUTHORIZED);
        }

        if (!passwordEncoder.matches(req.password(), admin.getPasswordHash())) {
            recordFailedAttemptAndThrow(admin, now, "INVALID_CREDENTIALS", "Invalid email or password");
            return null; // unreachable — recordFailedAttemptAndThrow always throws
        }

        if (!admin.isActive()) {
            throw new ApiException(
                    "ACCOUNT_SUSPENDED", "This admin account has been deactivated", HttpStatus.FORBIDDEN);
        }

        if (admin.isMfaEnabled()) {
            // Kabir P1-2 Gap 2 (HIGH) — check MFA-specific lockout before verifying code
            if (admin.isMfaLockedOut(now)) {
                throw new ApiException(
                        "INVALID_CREDENTIALS",
                        "Invalid email or password",
                        HttpStatus.UNAUTHORIZED);
            }

            if (req.mfaCode() == null || req.mfaCode().isBlank()) {
                throw new ApiException(
                        "MFA_REQUIRED", "MFA code is required for this account", HttpStatus.UNAUTHORIZED);
            }

            if (!totpService.verifyCode(mfaSecretCipher.decrypt(admin.getEncryptedMfaSecret()), req.mfaCode())) {
                recordFailedMfaAttemptAndThrow(admin, now);
                return null; // unreachable
            }

            // MFA succeeded — reset both counters
            admin.resetFailedMfaAttempts();
        } else if (adminSecurityProperties.isMfaEnforceOnLogin() && roleRequiresMfa(admin.getRole())) {
            // [DECISION FLAGGED — see AdminSecurityProperties javadoc] rejects login outright for
            // an unenrolled SUPER_ADMIN/ADMIN rather than issuing a session that can only reach
            // /me, /logout, /mfa/* — closes the gap where password compromise alone let an
            // attacker log in AND be the one to complete MFA enrollment on the victim's account.
            throw new ApiException(
                    "MFA_ENROLLMENT_REQUIRED",
                    "Multi-factor authentication must be enabled for this role. Contact a"
                            + " SUPER_ADMIN to arrange enrollment.",
                    HttpStatus.FORBIDDEN);
        }

        admin.resetFailedLoginAttempts();
        admin.markLogin();
        adminUserRepository.save(admin);
        return issueTokens(admin);
    }

    /** {@code SUPER_ADMIN}/{@code ADMIN} require MFA; {@code SUPPORT} is exempt — MUST stay in lockstep with {@code AdminContextService#requireMfaSatisfied}'s identical role split. */
    private static boolean roleRequiresMfa(AdminRole role) {
        return role == AdminRole.SUPER_ADMIN || role == AdminRole.ADMIN;
    }

    /** Records one failed attempt (password or MFA code), persists it, and throws the caller-supplied error — or a lockout error if this attempt just tripped the threshold. */
    private void recordFailedAttemptAndThrow(
            AdminUser admin, Instant now, String errorCode, String errorMessage) {
        boolean justLocked =
                admin.recordFailedLogin(
                        adminSecurityProperties.getLockoutMaxAttempts(),
                        Duration.ofSeconds(adminSecurityProperties.getLockoutCooldownSeconds()),
                        now);
        adminUserRepository.save(admin);
        if (justLocked) {
            // Kabir P1-2 Gap 3 (MEDIUM) — generic error to prevent lockout enumeration
            throw new ApiException(
                    "INVALID_CREDENTIALS",
                    "Invalid email or password",
                    HttpStatus.UNAUTHORIZED);
        }
        throw new ApiException(errorCode, errorMessage, HttpStatus.UNAUTHORIZED);
    }

    /**
     * Kabir P1-2 Gap 2 (HIGH) — Records one failed MFA code attempt, separate from password failures.
     * Defends against distributed TOTP brute-force (attacker with valid password rotating IPs to try
     * many MFA codes before account locks). Uses tighter threshold (3 attempts/1 hour) than password
     * lockout (5 attempts/15 min).
     */
    private void recordFailedMfaAttemptAndThrow(AdminUser admin, Instant now) {
        boolean justLocked =
                admin.recordFailedMfaAttempt(
                        adminSecurityProperties.getMfaLockoutMaxAttempts(),
                        Duration.ofSeconds(adminSecurityProperties.getMfaLockoutCooldownSeconds()),
                        now);
        adminUserRepository.save(admin);
        // Always throw generic error (locked or not) — no timestamp leakage (Kabir Gap 3)
        throw new ApiException(
                "INVALID_CREDENTIALS",
                "Invalid email or password",
                HttpStatus.UNAUTHORIZED);
    }

    /** Result of a refresh: mirrors {@code AuthService.RefreshRotation}. */
    public record RefreshRotation(String accessToken, String newRefreshToken) {}

    @Transactional
    public RefreshRotation refresh(String rawRefreshToken) {
        String hash = JwtService.hashToken(rawRefreshToken);
        AdminRefreshToken stored =
                adminRefreshTokenRepository
                        .findByTokenHashAndRevokedFalse(hash)
                        .filter(t -> t.getExpiresAt().isAfter(Instant.now()))
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "INVALID_REFRESH_TOKEN",
                                                "Refresh token is invalid or expired",
                                                HttpStatus.UNAUTHORIZED));

        AdminUser admin =
                adminUserRepository
                        .findById(stored.getAdminId())
                        .filter(AdminUser::isActive)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "ADMIN_NOT_FOUND", "Admin not found", HttpStatus.UNAUTHORIZED));

        // Rotation: burn the presented token, mint a new one (same defense as the brand/creator
        // flow — a leaked admin refresh token is single-use).
        stored.revoke();
        adminRefreshTokenRepository.save(stored);

        String newRefreshRaw = jwtService.createRefreshTokenValue();
        adminRefreshTokenRepository.save(
                AdminRefreshToken.create(
                        Ulids.newUlid(),
                        admin.getId(),
                        JwtService.hashToken(newRefreshRaw),
                        Instant.now().plusSeconds(jwtService.getRefreshExpirySeconds())));

        String access =
                jwtService.createAccessToken(admin.getId(), UserType.ADMIN, admin.getEmail(), null);
        return new RefreshRotation(access, newRefreshRaw);
    }

    @Transactional
    public void logout(AuthPrincipal principal) {
        String adminId = adminContext.requireAdminId(principal);
        adminRefreshTokenRepository.revokeAllForAdmin(adminId);
    }

    @Transactional(readOnly = true)
    public AdminUserDto me(AuthPrincipal principal) {
        String adminId = adminContext.requireAdminId(principal);
        return toDto(loadActive(adminId));
    }

    @Transactional
    public MfaSetupResponse setupMfa(AuthPrincipal principal) {
        String adminId = adminContext.requireAdminId(principal);
        AdminUser admin = loadActive(adminId);
        String secret = totpService.generateSecret();
        // otpAuthUri is built from the PLAINTEXT secret in memory, before it is ever encrypted or
        // persisted -- matches MetaTokenStorage's discipline of never writing plaintext to the DB.
        String otpAuthUri = totpService.buildOtpAuthUri(admin.getEmail(), secret);
        // Staged, not enabled yet -- mfaEnabled flips true only after verifyMfa() confirms the
        // caller actually holds a working authenticator (see AdminUser#confirmMfa javadoc). Only
        // the AES-256-GCM ciphertext is ever written to encrypted_mfa_secret.
        admin.stageMfaSecret(mfaSecretCipher.encrypt(secret));
        adminUserRepository.save(admin);
        return new MfaSetupResponse(otpAuthUri, secret);
    }

    @Transactional
    public void verifyMfa(AuthPrincipal principal, String code) {
        String adminId = adminContext.requireAdminId(principal);
        AdminUser admin = loadActive(adminId);
        if (admin.getEncryptedMfaSecret() == null) {
            throw new ApiException(
                    "MFA_NOT_STARTED", "Call /auth/mfa/setup before verifying", HttpStatus.CONFLICT);
        }
        if (!totpService.verifyCode(mfaSecretCipher.decrypt(admin.getEncryptedMfaSecret()), code)) {
            throw new ApiException("INVALID_MFA_CODE", "Invalid MFA code", HttpStatus.UNAUTHORIZED);
        }
        admin.confirmMfa();
        adminUserRepository.save(admin);
    }

    /**
     * ADMIN-BOOTSTRAP-0829 — SUPER_ADMIN-only recovery path for an admin locked out of MFA (lost
     * device, etc.) that does not require a direct DB edit. Clears the TARGET's MFA enrollment,
     * forcing them through {@code /mfa/setup} + {@code /mfa/verify} again on their next
     * login/privileged request.
     *
     * <p>Authorization, deliberately strict given this is a privilege-escalation-shaped surface:
     *
     * <ul>
     *   <li>Caller must be an active SUPER_ADMIN <b>with their own MFA already satisfied</b> —
     *       {@link AdminContextService#requireRoleWithMfaSatisfied} enforces both, so a compromised
     *       password alone (without the caller's own working MFA) can never reach this endpoint.
     *   <li>Caller may NOT target their own {@code adminId}. Without this, a SUPER_ADMIN could
     *       reset their OWN MFA at will — defeating the entire point of mandatory MFA enforcement,
     *       since "forgot my second factor" would always have a self-service bypass. Checked AFTER
     *       loading the caller's id fresh from {@link AdminContextService}, not from anything
     *       caller-supplied, so it cannot be spoofed via the request body.
     *   <li>Target must be an active admin row ({@link #loadActive}) — resetting a suspended or
     *       nonexistent admin's MFA is meaningless and rejected the same way {@code /me} rejects it.
     * </ul>
     *
     * <p>No role restriction on the TARGET (SUPER_ADMIN can reset another SUPER_ADMIN's, an
     * ADMIN's, or a SUPPORT's MFA) — the only restriction is caller-tier and not-self.
     *
     * <p><b>F-0648 (fixed):</b> clearing {@code mfaEnabled} alone left any refresh token the
     * target already held valid until its natural expiry — a compromised admin session (the exact
     * scenario this recovery path exists to contain) kept working after the "recovery". This is
     * especially true for {@code SUPPORT}, which is exempt from {@link
     * AdminContextService#requireMfaSatisfied}'s MFA gate and so previously had zero session-side
     * effect from this call at all. Reuses the same {@link
     * AdminRefreshTokenRepository#revokeAllForAdmin} bulk-revoke {@link #logout} already uses —
     * same mechanism, applied to the TARGET's id instead of the caller's — for every target role.
     */
    @Transactional
    public void resetMfaForAdmin(AuthPrincipal principal, String targetAdminId) {
        AdminUser caller = adminContext.requireRoleWithMfaSatisfied(principal, AdminRole.SUPER_ADMIN);
        if (caller.getId().equals(targetAdminId)) {
            throw new ApiException(
                    "CANNOT_RESET_OWN_MFA",
                    "Use a different SUPER_ADMIN account to reset your own MFA",
                    HttpStatus.FORBIDDEN);
        }
        AdminUser target = loadActive(targetAdminId);
        target.resetMfa();
        adminUserRepository.save(target);
        adminRefreshTokenRepository.revokeAllForAdmin(target.getId());
    }

    private AdminUser loadActive(String adminId) {
        return adminUserRepository
                .findById(adminId)
                .filter(AdminUser::isActive)
                .orElseThrow(
                        () ->
                                new ApiException(
                                        "ADMIN_NOT_FOUND", "Admin not found", HttpStatus.UNAUTHORIZED));
    }

    private LoginResponse issueTokens(AdminUser admin) {
        String access =
                jwtService.createAccessToken(admin.getId(), UserType.ADMIN, admin.getEmail(), null);
        String refreshRaw = jwtService.createRefreshTokenValue();
        adminRefreshTokenRepository.save(
                AdminRefreshToken.create(
                        Ulids.newUlid(),
                        admin.getId(),
                        JwtService.hashToken(refreshRaw),
                        Instant.now().plusSeconds(jwtService.getRefreshExpirySeconds())));
        return new LoginResponse(access, refreshRaw, toDto(admin));
    }

    private AdminUserDto toDto(AdminUser admin) {
        return new AdminUserDto(
                admin.getId(),
                admin.getEmail(),
                admin.getRole(),
                admin.isMfaEnabled(),
                admin.getLastLoginAt(),
                admin.getCreatedAt());
    }
}
