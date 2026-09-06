package com.influora.service.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.AdminUser;
import com.influora.domain.entity.Workspace;
import com.influora.domain.enums.AdminRole;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.EscrowHoldRepository;
import com.influora.repository.UserRepository;
import com.influora.repository.WalletRepository;
import com.influora.repository.WalletTransactionRepository;
import com.influora.repository.WorkspaceMemberRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.security.AuthPrincipal;
import com.influora.web.dto.admin.AdminBrandDtos.BrandDetailDto;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

/**
 * Mockito unit tests for {@code AdminBrandService#updateMetaPixel} (T-FESTIVALBOX-0905 phase 6,
 * {@code PATCH /admin/brands/{id}/meta-pixel}). Same plain-Mockito, no-{@code @SpringBootTest}
 * pattern as {@code AdminBrandServiceBudgetOverrideTest} in this package.
 *
 * <p>The DTO-level shape check (digits-only, 8-20 chars) is covered by {@code
 * AdminBrandDtosMetaPixelTest} — these tests exercise the SERVICE: valid set persists + audits,
 * explicit null clears + audits, an unchanged value is a no-op (no save, no audit row), and the
 * role gate is enforced before anything else runs.
 */
@ExtendWith(MockitoExtension.class)
class AdminBrandServiceMetaPixelTest {

    private static final String BRAND_ID = "01HWXYZBRAND00000000001";
    private static final String ADMIN_ID = "01HWXYZADMIN000000000001";

    @Mock private AdminContextService adminContext;
    @Mock private AdminAuditLogService adminAuditLogService;
    @Mock private WorkspaceRepository workspaceRepository;
    @Mock private WorkspaceMemberRepository workspaceMemberRepository;
    @Mock private UserRepository userRepository;
    @Mock private CampaignRepository campaignRepository;
    @Mock private CollaborationRepository collaborationRepository;
    @Mock private EscrowHoldRepository escrowHoldRepository;
    @Mock private WalletRepository walletRepository;
    @Mock private WalletTransactionRepository walletTransactionRepository;
    @Mock private AuthPrincipal principal;
    @Mock private HttpServletRequest request;

    private AdminBrandService service;
    private AdminUser superAdmin;
    private Workspace workspace;

    @BeforeEach
    void setUp() {
        service =
                new AdminBrandService(
                        adminContext,
                        adminAuditLogService,
                        workspaceRepository,
                        workspaceMemberRepository,
                        userRepository,
                        campaignRepository,
                        collaborationRepository,
                        escrowHoldRepository,
                        walletRepository,
                        walletTransactionRepository);
        superAdmin = AdminUser.create(ADMIN_ID, "ops@influora.ai", "hash", AdminRole.SUPER_ADMIN);
        workspace = Workspace.newBrand(BRAND_ID, "Acme Corp", "acme-corp", "Retail", "SMB");
    }

    private void stubSuperAdmin() {
        when(adminContext.requireRoleWithMfaSatisfied(principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN))
                .thenReturn(superAdmin);
    }

    @Test
    @DisplayName("valid digits-only id sets the pixel, saves the workspace, and audits old->new")
    void validId_setsPixelAndAudits() {
        stubSuperAdmin();
        when(workspaceRepository.findById(BRAND_ID)).thenReturn(Optional.of(workspace));

        BrandDetailDto result = service.updateMetaPixel(principal, request, BRAND_ID, "1234567890123456");

        assertEquals("1234567890123456", workspace.getMetaPixelId());
        assertEquals("1234567890123456", result.metaPixelId());
        verify(workspaceRepository).save(workspace);
        verify(adminAuditLogService)
                .record(
                        eq(principal),
                        eq(request),
                        eq("UPDATE"),
                        eq("BRAND"),
                        eq(BRAND_ID),
                        anyMap(),
                        anyMap(),
                        any());
    }

    @Test
    @DisplayName("explicit null clears an existing pixel id (not rejected, not ignored) and audits")
    void explicitNull_clearsPixelAndAudits() {
        workspace.applyMetaPixelId("1234567890123456");
        stubSuperAdmin();
        when(workspaceRepository.findById(BRAND_ID)).thenReturn(Optional.of(workspace));

        BrandDetailDto result = service.updateMetaPixel(principal, request, BRAND_ID, null);

        assertNull(workspace.getMetaPixelId());
        assertNull(result.metaPixelId());
        verify(workspaceRepository).save(workspace);
        verify(adminAuditLogService)
                .record(
                        eq(principal),
                        eq(request),
                        eq("UPDATE"),
                        eq("BRAND"),
                        eq(BRAND_ID),
                        anyMap(),
                        anyMap(),
                        any());
    }

    @Test
    @DisplayName("setting the SAME value it already has is a no-op: no save, no audit row")
    void unchangedValue_isNoOp() {
        workspace.applyMetaPixelId("1234567890123456");
        stubSuperAdmin();
        when(workspaceRepository.findById(BRAND_ID)).thenReturn(Optional.of(workspace));

        service.updateMetaPixel(principal, request, BRAND_ID, "1234567890123456");

        verify(workspaceRepository, never()).save(any());
        verify(adminAuditLogService, never())
                .record(any(), any(), any(), any(), any(), anyMap(), anyMap(), any());
    }

    @Test
    @DisplayName("both nulls (already null, set to null) is also a no-op")
    void nullToNull_isNoOp() {
        stubSuperAdmin();
        when(workspaceRepository.findById(BRAND_ID)).thenReturn(Optional.of(workspace));

        service.updateMetaPixel(principal, request, BRAND_ID, null);

        verify(workspaceRepository, never()).save(any());
        verify(adminAuditLogService, never())
                .record(any(), any(), any(), any(), any(), anyMap(), anyMap(), any());
    }

    @Test
    @DisplayName("role gate: an unauthorized caller is rejected before the workspace is ever touched")
    void unauthorizedCaller_isRejectedBeforeAnyWrite() {
        when(adminContext.requireRoleWithMfaSatisfied(principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN))
                .thenThrow(new ApiException("FORBIDDEN", "Insufficient role", HttpStatus.FORBIDDEN));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.updateMetaPixel(principal, request, BRAND_ID, "1234567890123456"));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        verify(workspaceRepository, never()).findById(any());
        verify(workspaceRepository, never()).save(any());
        verify(adminAuditLogService, never())
                .record(any(), any(), any(), any(), any(), anyMap(), anyMap(), any());
    }
}
