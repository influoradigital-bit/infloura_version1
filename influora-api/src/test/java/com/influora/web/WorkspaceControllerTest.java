package com.influora.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiResponse;
import com.influora.domain.entity.Workspace;
import com.influora.domain.enums.UserType;
import com.influora.domain.enums.VerificationStatus;
import com.influora.security.AuthPrincipal;
import com.influora.service.WorkspaceService;
import com.influora.service.WorkspaceSlugService;
import com.influora.web.dto.workspace.WorkspaceMemberDtos.WorkspaceUpdateRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Unit tests for the {@code GET /workspaces/me} / {@code PATCH /workspaces/me} endpoints — I7
 * (brand Settings &gt; General &gt; Workspace Information persistence). Mocks {@link
 * WorkspaceService} and asserts delegation + response mapping, same "mock the collaborators, no
 * MockMvc" convention as {@code MeeraControllerTest} (this codebase has no MockMvc harness).
 */
@ExtendWith(MockitoExtension.class)
class WorkspaceControllerTest {

    private static final String WORKSPACE_ID = "01HWORKSPACE12345678A";

    @Mock private WorkspaceSlugService slugService;
    @Mock private WorkspaceService workspaceService;

    private WorkspaceController controller;

    private final AuthPrincipal principal =
            new AuthPrincipal("user-001", "owner@example.com", UserType.BRAND, WORKSPACE_ID);

    @BeforeEach
    void setUp() {
        controller = new WorkspaceController(slugService, workspaceService);
    }

    @Test
    @DisplayName("GET /workspaces/me: maps the resolved workspace, including billingEmail as \"email\"")
    void getMyWorkspace_mapsResponse() {
        Workspace workspace = Workspace.newBrand(WORKSPACE_ID, "Acme Co", "acme-co", "Retail", "1-10");
        workspace.setVerificationStatus(VerificationStatus.VERIFIED);
        workspace.updateContactEmail("contact@acme.example");
        when(workspaceService.getMyWorkspace(principal)).thenReturn(workspace);

        ResponseEntity<ApiResponse<com.influora.web.dto.workspace.WorkspaceMemberDtos.WorkspaceReadResponse>>
                response = controller.getMyWorkspace(principal);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        var body = response.getBody().data();
        assertEquals(WORKSPACE_ID, body.id());
        assertEquals("Acme Co", body.name());
        assertEquals("contact@acme.example", body.email());
        assertEquals("VERIFIED", body.verificationStatus());
    }

    @Test
    @DisplayName("PATCH /workspaces/me: delegates every field (including email) to WorkspaceService and returns the saved workspace")
    void updateMyWorkspace_delegatesAllFields() {
        Workspace saved = Workspace.newBrand(WORKSPACE_ID, "New Name", "acme-co", "Fashion", "11-50");
        saved.updateContactEmail("new@acme.example");
        when(workspaceService.updateMyWorkspace(
                        eq(principal),
                        eq("New Name"),
                        eq("new@acme.example"),
                        eq("Fashion"),
                        eq("11-50"),
                        eq("https://acme.example"),
                        any(),
                        any()))
                .thenReturn(saved);

        WorkspaceUpdateRequest body =
                new WorkspaceUpdateRequest(
                        "New Name",
                        "new@acme.example",
                        "Fashion",
                        "11-50",
                        "https://acme.example",
                        null,
                        null);

        ResponseEntity<ApiResponse<com.influora.web.dto.workspace.WorkspaceMemberDtos.WorkspaceReadResponse>>
                response = controller.updateMyWorkspace(principal, body);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("New Name", response.getBody().data().name());
        assertEquals("new@acme.example", response.getBody().data().email());
        verify(workspaceService)
                .updateMyWorkspace(
                        principal,
                        "New Name",
                        "new@acme.example",
                        "Fashion",
                        "11-50",
                        "https://acme.example",
                        null,
                        null);
    }
}
