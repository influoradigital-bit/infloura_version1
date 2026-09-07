package com.influora.web;

import com.influora.common.ApiException;
import com.influora.common.ApiResponse;
import com.influora.domain.entity.User;
import com.influora.repository.UserRepository;
import com.influora.security.AuthCookieService;
import com.influora.security.AuthPrincipal;
import com.influora.service.AuthService;
import com.influora.web.dto.user.UserDtos.ChangePasswordRequest;
import com.influora.web.dto.user.UserDtos.ChangePasswordResponse;
import com.influora.web.dto.user.UserDtos.DeleteAccountResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Self-service account management (Domain: account lifecycle).
 *
 * <p>DELETE /me/account implements the "Delete Account" button as a real soft-delete (CEO
 * decision, 2026-07-14) instead of the prior no-op: {@link User#softDelete()} anonymizes PII and
 * stamps {@code deletedAt}, but deliberately does NOT cascade into deals/contracts/messages/
 * workspaces -- those rows must survive for the other party's records and for compliance
 * retention. The user's row itself (id/status/userType) is left intact so existing foreign keys
 * keep resolving.
 *
 * <p><b>[SEC: Kabir H-1 — PARTIAL, creators only]</b> Revoking refresh tokens here does NOT by
 * itself invalidate an already-issued access token — access tokens are stateless JWTs verified with
 * no DB round-trip (see {@code JwtAuthenticationFilter}), so a token issued before deletion keeps
 * authenticating for up to its remaining TTL. Immediate effect therefore has to come from the
 * per-request context gates re-checking {@code deletedAt}; the token-revocation calls below are
 * worth doing (they stop new access tokens being minted from the refresh token) but are not
 * sufficient alone.
 *
 * <p><b>Both gates now do that — the brand half only since 2026-09-07 (F-0708).</b> This paragraph
 * claimed for months that both re-checked {@code deletedAt}; that was false, and it is worth
 * recording why it survived: the comment asserting the work was done is exactly what stopped anyone
 * looking. {@code CreatorContextService.requireCreator} always did check it. {@code
 * BrandContextService} did not, anywhere, so a soft-deleted BRAND user kept full brand-scoped
 * access — wallet and payout endpoints included — until their access token expired. {@code
 * BrandContextService.requireBrand} now performs the same check, and because {@code
 * requireBrandWorkspace} and {@code requireMember} both delegate to it, all three entry points
 * inherit it. Pinned by {@code BrandContextServiceTest} and gated by
 * {@code .proof-os/gates/brand-deletion-enforced.sh}.
 *
 * <p>Admin sessions are unaffected either way: {@code AdminContextService} resolves a separate
 * {@code AdminUser} entity, which {@link User#softDelete()} never touches.
 */
@RestController
@RequestMapping("/me")
public class AccountController {

    private final UserRepository userRepository;
    private final AuthService authService;
    private final AuthCookieService authCookieService;

    public AccountController(
            UserRepository userRepository,
            AuthService authService,
            AuthCookieService authCookieService) {
        this.userRepository = userRepository;
        this.authService = authService;
        this.authCookieService = authCookieService;
    }

    /**
     * DELETE /me/account - soft-deletes the authenticated user's own account, then invalidates
     * their session (revokes all refresh tokens + clears the refresh cookie) so the deletion takes
     * effect immediately rather than waiting for access-token expiry.
     */
    @DeleteMapping("/account")
    public ResponseEntity<ApiResponse<DeleteAccountResponse>> deleteAccount(
            @AuthenticationPrincipal AuthPrincipal principal, HttpServletResponse response) {

        User user =
                userRepository
                        .findById(principal.getUserId())
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "USER_NOT_FOUND", "User not found", HttpStatus.NOT_FOUND));

        user.softDelete();
        userRepository.save(user);

        // Same session-invalidation path as /auth/logout: revoke every refresh token for this user
        // and expire the refresh cookie, so the deleted account cannot silently keep working off a
        // still-valid access token past its natural expiry.
        authService.logout(principal.getUserId());

        // F-0702 — refresh tokens were the only thing this path invalidated. Outstanding
        // password-reset tokens survived deletion and stayed resolvable by userId (softDelete()
        // blanks the email and hash but leaves the row), so a link mailed before deletion could
        // still write a fresh password hash onto the anonymized account afterwards. Up to 7 days
        // for the sponsor-provisioning link, 1 hour for ordinary forgot-password.
        // AuthService#resetPassword now also refuses a deleted user outright; this removes the rows
        // so the second defence never has to fire.
        authService.purgePasswordResetTokens(principal.getUserId());
        authCookieService.clearRefreshCookie(response);

        return ResponseEntity.ok(ApiResponse.ok(new DeleteAccountResponse(true)));
    }

    /**
     * BR-05 — POST /me/password. The only in-session password-change path ({@code PATCH
     * /users/me} does not accept a password). Re-authenticates on {@code currentPassword} before
     * accepting {@code newPassword} — see {@link AuthService#changePassword} for the 401/400
     * split.
     *
     * <p><b>[SEC: Priya audit, e60d249 follow-up]</b> Reads the caller's own raw refresh token off
     * the same HttpOnly cookie {@code AuthController} reads for {@code /auth/refresh}, purely so
     * {@code AuthService#changePassword} can identify and spare the caller's own session while
     * revoking every other one — see that method's javadoc. Never logged, never put in the
     * response; if the cookie is absent (e.g. a non-browser client) {@code null} flows straight
     * into the service's existing fallback path.
     */
    @PostMapping("/password")
    public ResponseEntity<ApiResponse<ChangePasswordResponse>> changePassword(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody ChangePasswordRequest body,
            HttpServletRequest request) {
        String currentRefreshToken = authCookieService.readRefreshToken(request);
        authService.changePassword(
                principal.getUserId(), body.currentPassword(), body.newPassword(), currentRefreshToken);
        return ResponseEntity.ok(ApiResponse.ok(new ChangePasswordResponse(true)));
    }
}
