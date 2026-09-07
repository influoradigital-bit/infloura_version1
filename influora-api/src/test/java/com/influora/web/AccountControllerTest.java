package com.influora.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiResponse;
import com.influora.domain.entity.User;
import com.influora.domain.enums.UserType;
import com.influora.repository.UserRepository;
import com.influora.security.AuthCookieService;
import com.influora.security.AuthPrincipal;
import com.influora.service.AuthService;
import com.influora.web.dto.user.UserDtos.DeleteAccountResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

/**
 * Unit tests for {@code DELETE /me/account}. Same "mock the collaborators, no MockMvc" convention
 * as {@link WorkspaceControllerTest} — this codebase has no MockMvc harness.
 *
 * <p>This class exists because of F-0702. The delete path revoked refresh tokens but left
 * outstanding password-reset tokens resolvable by {@code userId}, so a link mailed before deletion
 * could still write a fresh password hash onto the anonymized row afterwards — up to 7 days on the
 * sponsor-provisioning link. The service-level guard is covered in {@code AuthServiceTest}; what
 * had no coverage at all was the CALL SITE. AccountController had no test class whatsoever, which
 * is precisely how a purge can be written, reviewed, and never actually invoked.
 */
@ExtendWith(MockitoExtension.class)
class AccountControllerTest {

    private static final String USER_ID = "01HACCOUNTUSER12345678A";

    @Mock private UserRepository userRepository;
    @Mock private AuthService authService;
    @Mock private AuthCookieService authCookieService;
    @Mock private HttpServletResponse response;

    private AccountController controller;

    private final AuthPrincipal principal =
            new AuthPrincipal(USER_ID, "riya@example.com", UserType.CREATOR, null);

    @BeforeEach
    void setUp() {
        controller = new AccountController(userRepository, authService, authCookieService);
    }

    private User creatorUser() {
        return User.newCreator(USER_ID, "riya@example.com", "hashed-pw", "Riya", "Sharma", "Riya Sharma");
    }

    @Test
    @DisplayName(
            "DELETE /me/account F-0702: purges the user's outstanding password-reset tokens,"
                    + " not just their refresh tokens")
    void testDeleteAccountPurgesPasswordResetTokens() {
        User user = creatorUser();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        ResponseEntity<ApiResponse<DeleteAccountResponse>> res =
                controller.deleteAccount(principal, response);

        assertEquals(200, res.getStatusCode().value());
        assertNotNull(res.getBody());
        assertTrue(res.getBody().data().deleted());

        // The row is anonymized and stamped...
        assertNull(user.getEmail());
        assertNull(user.getPasswordHash());
        assertNotNull(user.getDeletedAt());
        verify(userRepository).save(user);

        // ...and BOTH token families are invalidated. Before F-0702 only the first of these two
        // lines existed, and a pending reset link kept working against the deleted account.
        verify(authService).logout(USER_ID);
        verify(authService).purgePasswordResetTokens(USER_ID);
        verify(authCookieService).clearRefreshCookie(response);
    }

    /**
     * The purge must happen AFTER the soft-delete is persisted. Reversed, a reset token minted in
     * the window between the purge and the save would survive — narrow, but the ordering is free
     * and this pins it so a later tidy-up of the method body cannot silently reintroduce it.
     */
    @Test
    @DisplayName("DELETE /me/account F-0702: soft-delete is saved before the tokens are purged")
    void testDeleteAccountSavesBeforePurging() {
        User user = creatorUser();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        controller.deleteAccount(principal, response);

        InOrder order = inOrder(userRepository, authService);
        order.verify(userRepository).save(any(User.class));
        order.verify(authService).purgePasswordResetTokens(USER_ID);
    }
}
