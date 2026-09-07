package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.User;
import com.influora.domain.entity.Workspace;
import com.influora.domain.enums.UserType;
import com.influora.repository.UserRepository;
import com.influora.repository.WorkspaceMemberRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.security.AuthPrincipal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * F-0457 — {@code requireBrandWorkspace} is the resolver every brand endpoint funnels through, so
 * it is the only place that closes F-0451's actual stated symptom: "a suspended brand keeps full
 * access to its current workspace".
 *
 * <p>Gating brandLogin and refresh alone left a window equal to the access-token lifetime (900s,
 * application.yml:185) during which a just-suspended brand kept operating normally, because
 * {@code JwtAuthenticationFilter} is a pure token parse that never touches the database.
 *
 * <p>This suite exists because the first repair added the guard here with NO test: removing the
 * guard still left the F-0451 gate green (exit 0), which is precisely the "guarded but unproven"
 * shape the ledger keeps recording.
 */
@ExtendWith(MockitoExtension.class)
class BrandContextServiceTest {

    private static final String USER_ID = "01HBRANDUSER1234567890AA";
    private static final String WORKSPACE_ID = "01HWORKSPACE123456789AA";

    @Mock private WorkspaceRepository workspaceRepository;
    @Mock private WorkspaceMemberRepository workspaceMemberRepository;
    @Mock private UserRepository userRepository;
    @Mock private AuthPrincipal principal;

    private BrandContextService service;

    @BeforeEach
    void setUp() {
        service =
                new BrandContextService(workspaceRepository, workspaceMemberRepository, userRepository);
        // F-0708: requireBrand now reads the user row to check deletedAt, and every entry point
        // funnels through it. Default the mocks to a LIVE brand user so the pre-existing
        // suspension tests keep testing suspension; the deletion tests below override this.
        // lenient because the WRONG_USER_TYPE case throws before the lookup.
        lenient().when(principal.getUserId()).thenReturn(USER_ID);
        lenient().when(userRepository.findById(USER_ID)).thenReturn(Optional.of(brandUser(false)));
    }

    /** @param deleted whether {@code softDelete()} has been applied. */
    private User brandUser(boolean deleted) {
        User u = User.newBrand(USER_ID, "brand@example.com", "hashed-pw", "Bee", "Rand", "Bee Rand");
        if (deleted) {
            u.softDelete();
        }
        return u;
    }

    private Workspace workspace(boolean suspended) {
        Workspace ws = Workspace.newBrand(WORKSPACE_ID, "Acme Co", "acme-co", "RETAIL", "SMALL");
        if (suspended) {
            ws.suspend("fraud review", "01HADMIN12345678901234AA");
        }
        return ws;
    }

    @Test
    @DisplayName("F-0457: requireBrandWorkspace refuses a SUSPENDED workspace -> WORKSPACE_SUSPENDED 403")
    void requireBrandWorkspace_suspended_rejected() {
        when(principal.getUserType()).thenReturn(UserType.BRAND);
        when(principal.getWorkspaceId()).thenReturn(WORKSPACE_ID);
        when(workspaceRepository.findById(WORKSPACE_ID)).thenReturn(Optional.of(workspace(true)));

        ApiException ex =
                assertThrows(ApiException.class, () -> service.requireBrandWorkspace(principal));

        assertEquals("WORKSPACE_SUSPENDED", ex.getCode());
        assertEquals(403, ex.getStatus().value());
    }

    // ── F-0708: account deletion must take effect for BRANDS, not only creators ──
    // AccountController's javadoc claimed both context gates re-checked deletedAt. Only the creator
    // one did. These pin the brand half, at all three entry points, because requireBrandWorkspace
    // and requireMember inherit the check from requireBrand rather than repeating it — a future
    // refactor that stops delegating would silently reopen the hole at the inheriting sites while
    // the requireBrand test stayed green.

    @Test
    @DisplayName("F-0708: requireBrand refuses a soft-deleted brand user -> ACCOUNT_DELETED 401")
    void requireBrand_softDeleted_rejected() {
        when(principal.getUserType()).thenReturn(UserType.BRAND);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(brandUser(true)));

        ApiException ex = assertThrows(ApiException.class, () -> service.requireBrand(principal));

        assertEquals("ACCOUNT_DELETED", ex.getCode());
        assertEquals(401, ex.getStatus().value());
    }

    @Test
    @DisplayName("F-0708: requireBrandWorkspace inherits the deletion check -> ACCOUNT_DELETED 401")
    void requireBrandWorkspace_softDeleted_rejected() {
        when(principal.getUserType()).thenReturn(UserType.BRAND);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(brandUser(true)));

        ApiException ex =
                assertThrows(ApiException.class, () -> service.requireBrandWorkspace(principal));

        // Refused BEFORE the workspace is even resolved: no stub for workspaceRepository is needed
        // here, which is itself the proof that the gate runs first.
        assertEquals("ACCOUNT_DELETED", ex.getCode());
        assertEquals(401, ex.getStatus().value());
    }

    @Test
    @DisplayName("F-0708: requireMember inherits the deletion check -> ACCOUNT_DELETED 401")
    void requireMember_softDeleted_rejected() {
        when(principal.getUserType()).thenReturn(UserType.BRAND);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(brandUser(true)));

        ApiException ex =
                assertThrows(
                        ApiException.class, () -> service.requireMember(principal, WORKSPACE_ID));

        assertEquals("ACCOUNT_DELETED", ex.getCode());
        assertEquals(401, ex.getStatus().value());
    }

    /**
     * The safe default copied from {@code CreatorContextService.requireCreator}: a principal whose
     * user row cannot be found is refused, not waved through. Without this, {@code orElse(true)}
     * could be flipped to {@code orElse(false)} and every test above would still pass.
     */
    @Test
    @DisplayName("F-0708: a principal with no user row at all is refused, not admitted")
    void requireBrand_missingUserRow_rejected() {
        when(principal.getUserType()).thenReturn(UserType.BRAND);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        ApiException ex = assertThrows(ApiException.class, () -> service.requireBrand(principal));

        assertEquals("ACCOUNT_DELETED", ex.getCode());
    }

    @Test
    @DisplayName("F-0708: a live brand user still passes requireBrand (no false positive)")
    void requireBrand_liveUser_allowed() {
        when(principal.getUserType()).thenReturn(UserType.BRAND);

        service.requireBrand(principal); // must not throw
    }

    @Test
    @DisplayName("F-0457: an unsuspended workspace still resolves normally (no false positive)")
    void requireBrandWorkspace_active_returnsWorkspace() {
        when(principal.getUserType()).thenReturn(UserType.BRAND);
        when(principal.getWorkspaceId()).thenReturn(WORKSPACE_ID);
        when(workspaceRepository.findById(WORKSPACE_ID)).thenReturn(Optional.of(workspace(false)));

        Workspace resolved = service.requireBrandWorkspace(principal);

        assertEquals(WORKSPACE_ID, resolved.getId());
    }
}
