package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
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
