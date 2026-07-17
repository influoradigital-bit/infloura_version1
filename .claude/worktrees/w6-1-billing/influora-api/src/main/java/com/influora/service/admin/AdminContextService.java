package com.influora.service.admin;

import com.influora.common.ApiException;
import com.influora.domain.entity.AdminUser;
import com.influora.domain.enums.AdminRole;
import com.influora.domain.enums.UserType;
import com.influora.repository.AdminUserRepository;
import com.influora.security.AuthPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Admin-surface guard, same role as {@code BrandContextService} for the brand surface. Every
 * {@code Admin*Controller} endpoint that requires an authenticated admin calls
 * {@link #requireAdminId(AuthPrincipal)} (or, for privileged reads/writes, the stricter
 * {@link #requireAdminIdWithMfaSatisfied(AuthPrincipal)}) first.
 *
 * <p>Why this check exists even though {@code SecurityConfig}'s {@code anyRequest().authenticated()}
 * already requires a valid JWT: that check only proves the caller holds SOME valid access token —
 * a brand or creator's token is equally valid there. Admin tokens are minted with
 * {@code UserType.ADMIN} (see {@code AdminAuthService#issueTokens}), reusing the existing
 * {@code JwtService}/{@code JwtAuthenticationFilter} infra unchanged rather than forking a
 * parallel admin-only JWT pipeline. This method is the actual "is this an admin at all" gate;
 * {@link #requireRole}/{@link #requireRoleWithMfaSatisfied} below are the "which TIER of admin"
 * gate.
 *
 * <p><b>Mandatory-MFA gate (Kabir, wiki/admin-progress/SECURITY-NOTES.md P0 item 4):</b>
 * {@code SUPER_ADMIN} and {@code ADMIN} roles must have MFA enabled to reach privileged surface —
 * a compromised password alone must not be enough given this panel can eventually release/refund
 * escrow and override budgets. {@link #requireAdminIdWithMfaSatisfied} enforces that;
 * {@link #requireAdminId} deliberately does NOT, because {@code /admin/auth/me},
 * {@code /admin/auth/logout}, {@code /admin/auth/mfa/setup} and {@code /admin/auth/mfa/verify}
 * must stay reachable by an admin who hasn't finished MFA enrollment yet — otherwise a fresh
 * SUPER_ADMIN with {@code mfaEnabled=false} could never bootstrap into MFA setup at all.
 *
 * <p><b>Role gate (Kabir, wiki/admin-progress/SECURITY-NOTES.md P0 item 1, cycle-2 fix):</b>
 * cycle 1 shipped {@code AdminDashboardController} calling only {@link #requireAdminIdWithMfaSatisfied},
 * which proves "some admin, any tier" — a {@code SUPPORT}-tier admin JWT passed the exact same
 * check as {@code SUPER_ADMIN}. {@link #requireRole}/{@link #requireRoleWithMfaSatisfied} close
 * that gap: they load the caller's CURRENT role fresh from {@code admin_users} (role is
 * deliberately never minted into the JWT — see {@code AdminAuthService} class javadoc — so a
 * demotion/suspension takes effect on the caller's very next request, not after token expiry) and
 * reject with 403 {@code INSUFFICIENT_ROLE} unless it's in the caller-supplied allow-list. Mirrors
 * {@code BrandContextService#requireRole(WorkspaceMember, MemberRole...)}'s shape — that is the
 * established precedent for role checks in this codebase (manual, per-call allow-lists in the
 * service layer; there is zero use of {@code @PreAuthorize}/{@code @EnableMethodSecurity}/
 * {@code hasRole} anywhere in {@code com.influora}, confirmed by grep before writing this).
 *
 * <p>Every {@code Admin*Controller} endpoint that isn't pure self-service (own-account
 * {@code me}/{@code logout}/{@code mfa/*}) MUST call {@link #requireRole} or
 * {@link #requireRoleWithMfaSatisfied} with an explicit role allow-list — a bare
 * {@link #requireAdminId}/{@link #requireAdminIdWithMfaSatisfied} call is NOT sufficient RBAC by
 * itself. Suggested default matrix (Kabir's note; not yet ratified by Priya as a formal doc):
 * {@code SUPPORT} = read + ticket/moderation actions only; {@code ADMIN} = + brand/creator
 * KYC/tier/suspend; {@code SUPER_ADMIN} = + escrow release/refund, budget override, payout retry,
 * reconciliation write-off. Every future mutating admin endpoint (escrow/budget-override/etc.,
 * P1+) must pick its own allow-list from that matrix, not default to "any admin."
 */
@Service
public class AdminContextService {

    private final AdminUserRepository adminUserRepository;

    public AdminContextService(AdminUserRepository adminUserRepository) {
        this.adminUserRepository = adminUserRepository;
    }

    /** Returns the caller's admin_users.id, or throws 403 if the token isn't an admin token. */
    public String requireAdminId(AuthPrincipal principal) {
        if (principal == null || principal.getUserType() != UserType.ADMIN) {
            throw new ApiException("FORBIDDEN", "Admin access required", HttpStatus.FORBIDDEN);
        }
        return principal.getUserId();
    }

    /**
     * Same as {@link #requireAdminId}, plus: for {@code SUPER_ADMIN}/{@code ADMIN} roles, rejects
     * the call with 403 {@code MFA_SETUP_REQUIRED} unless MFA is enabled. {@code SUPPORT} is not
     * gated here — Kabir's note only calls out SUPER_ADMIN/ADMIN explicitly given their higher
     * blast radius; revisit if SUPPORT gains write access to anything sensitive.
     */
    public String requireAdminIdWithMfaSatisfied(AuthPrincipal principal) {
        AdminUser admin = loadActive(requireAdminId(principal));
        requireMfaSatisfied(admin);
        return admin.getId();
    }

    /**
     * Role-tier gate (no MFA requirement layered in — use {@link #requireRoleWithMfaSatisfied} for
     * privileged reads/writes). Throws 403 {@code FORBIDDEN} if the caller isn't an admin at all,
     * 401 {@code ADMIN_NOT_FOUND} if the admin row is missing/inactive, or 403
     * {@code INSUFFICIENT_ROLE} if the caller's current role isn't in {@code allowedRoles}.
     */
    public AdminUser requireRole(AuthPrincipal principal, AdminRole... allowedRoles) {
        AdminUser admin = loadActive(requireAdminId(principal));
        return requireRole(admin, allowedRoles);
    }

    /**
     * Combines {@link #requireAdminIdWithMfaSatisfied}'s MFA gate with {@link #requireRole}'s
     * role-tier gate in a single DB read. This is the variant every privileged
     * ({@code SUPER_ADMIN}/{@code ADMIN}-tier) mutating or sensitive-read endpoint should call.
     */
    public AdminUser requireRoleWithMfaSatisfied(AuthPrincipal principal, AdminRole... allowedRoles) {
        AdminUser admin = loadActive(requireAdminId(principal));
        requireMfaSatisfied(admin);
        return requireRole(admin, allowedRoles);
    }

    private AdminUser requireRole(AdminUser admin, AdminRole... allowedRoles) {
        for (AdminRole role : allowedRoles) {
            if (admin.getRole() == role) {
                return admin;
            }
        }
        throw new ApiException(
                "INSUFFICIENT_ROLE",
                "This action requires a higher-privilege admin role",
                HttpStatus.FORBIDDEN);
    }

    private void requireMfaSatisfied(AdminUser admin) {
        boolean mfaRequiredForRole =
                admin.getRole() == AdminRole.SUPER_ADMIN || admin.getRole() == AdminRole.ADMIN;
        if (mfaRequiredForRole && !admin.isMfaEnabled()) {
            throw new ApiException(
                    "MFA_SETUP_REQUIRED",
                    "Multi-factor authentication must be enabled for this role before continuing",
                    HttpStatus.FORBIDDEN);
        }
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
}
