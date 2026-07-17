package com.influora.web;

import com.influora.common.ApiException;
import com.influora.common.ApiResponse;
import com.influora.domain.entity.User;
import com.influora.repository.UserRepository;
import com.influora.security.AuthCookieService;
import com.influora.security.AuthPrincipal;
import com.influora.service.AuthService;
import com.influora.web.dto.user.UserDtos.DeleteAccountResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * <p><b>[SEC: Kabir H-1 — fixed]</b> Revoking refresh tokens here does NOT by itself invalidate an
 * already-issued access token — access tokens are stateless JWTs verified with no DB round-trip
 * (see {@code JwtAuthenticationFilter}), so a token issued before deletion would otherwise keep
 * authenticating for up to its remaining TTL. The actual immediate-effect guarantee comes from
 * {@code CreatorContextService.requireCreator}/{@code BrandContextService.requireBrand} — the two
 * gates every creator/brand-scoped endpoint (including every wallet/payout endpoint) already
 * calls — now re-checking {@code deletedAt} on every request. That is what makes deletion take
 * effect on the very next request rather than at token expiry; the token-revocation calls below
 * are still worth doing (stop new access tokens being minted from the refresh token, clear the
 * cookie) but are not sufficient alone.
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
        authCookieService.clearRefreshCookie(response);

        return ResponseEntity.ok(ApiResponse.ok(new DeleteAccountResponse(true)));
    }
}
