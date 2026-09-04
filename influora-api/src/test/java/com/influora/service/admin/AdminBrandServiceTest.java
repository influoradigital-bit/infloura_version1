package com.influora.service.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.AdminUser;
import com.influora.domain.entity.User;
import com.influora.domain.entity.Workspace;
import com.influora.domain.entity.WorkspaceMember;
import com.influora.domain.enums.AdminRole;
import com.influora.domain.enums.MemberRole;
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
import com.influora.web.dto.admin.AdminBrandDtos.BrandSummaryDto;
import com.influora.web.dto.admin.AdminBrandDtos.PaginatedBrandResponse;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;

/**
 * Mockito unit tests for F7 — {@code AdminBrandService.list}/{@code getById} exposing BOTH brand
 * phone values ({@code workspacePhone} = {@code workspaces.phone}, {@code ownerPhone} = {@code
 * users.phone_number} of the workspace OWNER). Plain Mockito, no {@code @SpringBootTest}, matching
 * every other {@code Admin*ServiceTest} in this package (see {@code
 * AdminBrandServiceBudgetOverrideTest} class javadoc for why).
 *
 * <p>Covers: both fields populated when present; both honestly {@code null} for a legacy brand
 * with no owner phone and no workspace phone (never a fabricated placeholder); the list path
 * resolves the owner batch-style (one {@code findByWorkspaceIdInAndRoleAndActiveTrue} +
 * one {@code findAllById} for the whole page, never the single-row {@code
 * findFirstByWorkspaceIdAndRoleAndActiveTrue}) so a page of N brands does not turn into an N+1;
 * and the role guard on both reads is unchanged (SUPER_ADMIN/ADMIN/SUPPORT, MFA-gated) — an admin
 * PII read must not be reachable at a weaker tier than the rest of {@code AdminBrandService}.
 */
@ExtendWith(MockitoExtension.class)
class AdminBrandServiceTest {

    private static final String BRAND_A_ID = "01HWXYZBRAND0000000000A";
    private static final String BRAND_B_ID = "01HWXYZBRAND0000000000B";
    private static final String OWNER_A_ID = "01HWXYZOWNER00000000000A";
    private static final String OWNER_B_ID = "01HWXYZOWNER00000000000B";
    private static final String MEMBER_A_ID = "01HWXYZMEMBER0000000000A";
    private static final String MEMBER_B_ID = "01HWXYZMEMBER0000000000B";
    private static final String ADMIN_ID = "01HWXYZADMIN000000000002";

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

    private AdminBrandService service;
    private AdminUser viewingAdmin;

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
        viewingAdmin = AdminUser.create(ADMIN_ID, "ops@influora.ai", "hash", AdminRole.SUPER_ADMIN);
    }

    // ============================================================
    // 1. Both fields populated when present (list path)
    // ============================================================
    @Test
    @DisplayName(
            "list(): workspacePhone (workspaces.phone) and ownerPhone (users.phone_number of the OWNER)"
                    + " both populated when set, and are NOT the same value")
    void testListPopulatesBothPhonesWhenPresent() {
        stubViewRole();

        Workspace brandA = Workspace.newBrand(BRAND_A_ID, "Acme Brand", "acme-brand", "Retail", "SMB");
        brandA.updatePhone("+912233445566"); // workspace business line

        User ownerA = User.newBrand(OWNER_A_ID, "owner-a@acme.test", "hash", "Asha", "Rao", "Asha Rao");
        ownerA.setPhoneNumber("+919876543210"); // owner's own mobile — deliberately different

        stubList(List.of(brandA), List.of(WorkspaceMember.owner(MEMBER_A_ID, BRAND_A_ID, OWNER_A_ID)), List.of(ownerA));

        PaginatedBrandResponse response = service.list(principal, 1, 20, null, null, null);

        assertEquals(1, response.data().size());
        BrandSummaryDto dto = response.data().get(0);
        assertEquals("+912233445566", dto.workspacePhone());
        assertEquals("+919876543210", dto.ownerPhone());
        assertNotEquals(dto.workspacePhone(), dto.ownerPhone());
    }

    // ============================================================
    // 2. Legacy brand: both honestly null, never a fabricated placeholder
    // ============================================================
    @Test
    @DisplayName(
            "list(): legacy brand with no workspace phone and no pre-PHONE-0904 owner phone renders"
                    + " both fields as null, never an empty-string or placeholder value")
    void testListLegacyBrandHasHonestNullsNotPlaceholders() {
        stubViewRole();

        // No updatePhone() call -> workspaces.phone stays null (most workspaces).
        Workspace legacyBrand = Workspace.newBrand(BRAND_B_ID, "Old Co", "old-co", "Manufacturing", "ENTERPRISE");
        // No setPhoneNumber() call -> users.phone_number stays null (registered before PHONE-0904).
        User legacyOwner = User.newBrand(OWNER_B_ID, "owner-b@oldco.test", "hash", "Ravi", "Iyer", "Ravi Iyer");

        stubList(
                List.of(legacyBrand),
                List.of(WorkspaceMember.owner(MEMBER_B_ID, BRAND_B_ID, OWNER_B_ID)),
                List.of(legacyOwner));

        PaginatedBrandResponse response = service.list(principal, 1, 20, null, null, null);

        BrandSummaryDto dto = response.data().get(0);
        assertNull(dto.workspacePhone());
        assertNull(dto.ownerPhone());
    }

    // ============================================================
    // 3. No N+1: owner resolution is batched once per page, not once per row
    // ============================================================
    @Test
    @DisplayName(
            "list(): a page of 2 brands resolves owners in exactly ONE"
                    + " findByWorkspaceIdInAndRoleAndActiveTrue + ONE findAllById call — never the"
                    + " single-row findFirstByWorkspaceIdAndRoleAndActiveTrue (that would be an N+1)")
    void testListResolvesOwnersWithoutNPlusOne() {
        stubViewRole();

        Workspace brandA = Workspace.newBrand(BRAND_A_ID, "Acme Brand", "acme-brand", "Retail", "SMB");
        Workspace brandB = Workspace.newBrand(BRAND_B_ID, "Old Co", "old-co", "Manufacturing", "ENTERPRISE");
        User ownerA = User.newBrand(OWNER_A_ID, "owner-a@acme.test", "hash", "Asha", "Rao", "Asha Rao");
        ownerA.setPhoneNumber("+919876543210");
        User ownerB = User.newBrand(OWNER_B_ID, "owner-b@oldco.test", "hash", "Ravi", "Iyer", "Ravi Iyer");

        stubList(
                List.of(brandA, brandB),
                List.of(
                        WorkspaceMember.owner(MEMBER_A_ID, BRAND_A_ID, OWNER_A_ID),
                        WorkspaceMember.owner(MEMBER_B_ID, BRAND_B_ID, OWNER_B_ID)),
                List.of(ownerA, ownerB));

        PaginatedBrandResponse response = service.list(principal, 1, 20, null, null, null);

        assertEquals(2, response.data().size());
        verify(workspaceMemberRepository, times(1))
                .findByWorkspaceIdInAndRoleAndActiveTrue(anyList(), any(MemberRole.class));
        verify(userRepository, times(1)).findAllById(anyList());
        verify(workspaceMemberRepository, never())
                .findFirstByWorkspaceIdAndRoleAndActiveTrue(any(), any());
    }

    // ============================================================
    // 4. getById(): both fields populated, single-row owner lookup reused for email + ownerPhone
    // ============================================================
    @Test
    @DisplayName(
            "getById(): workspacePhone and ownerPhone both populated on the detail DTO, resolved from"
                    + " ONE owner User row (same lookup email-fallback already needed)")
    void testGetByIdPopulatesBothPhones() {
        stubDetailRole();

        Workspace brand = Workspace.newBrand(BRAND_A_ID, "Acme Brand", "acme-brand", "Retail", "SMB");
        brand.updatePhone("+912233445566");
        User owner = User.newBrand(OWNER_A_ID, "owner-a@acme.test", "hash", "Asha", "Rao", "Asha Rao");
        owner.setPhoneNumber("+919876543210");
        WorkspaceMember ownerMember = WorkspaceMember.owner(MEMBER_A_ID, BRAND_A_ID, OWNER_A_ID);

        when(workspaceRepository.findById(BRAND_A_ID)).thenReturn(Optional.of(brand));
        when(workspaceMemberRepository.findFirstByWorkspaceIdAndRoleAndActiveTrue(BRAND_A_ID, MemberRole.OWNER))
                .thenReturn(Optional.of(ownerMember));
        when(userRepository.findById(OWNER_A_ID)).thenReturn(Optional.of(owner));
        when(campaignRepository.findByWorkspaceId(BRAND_A_ID)).thenReturn(List.of());
        when(collaborationRepository.findByWorkspaceId(BRAND_A_ID)).thenReturn(List.of());
        when(workspaceMemberRepository.findByWorkspaceIdAndActiveTrue(BRAND_A_ID)).thenReturn(List.of());
        when(walletRepository.findByOwnerId(BRAND_A_ID)).thenReturn(Optional.empty());

        BrandDetailDto dto = service.getById(principal, BRAND_A_ID);

        assertEquals("+912233445566", dto.workspacePhone());
        assertEquals("+919876543210", dto.ownerPhone());
    }

    // ============================================================
    // 5. getById(): legacy brand -> honest nulls, not placeholders
    // ============================================================
    @Test
    @DisplayName("getById(): no workspace phone and no owner phone -> both fields null, never fabricated")
    void testGetByIdLegacyBrandHasHonestNulls() {
        stubDetailRole();

        Workspace brand = Workspace.newBrand(BRAND_B_ID, "Old Co", "old-co", "Manufacturing", "ENTERPRISE");
        User owner = User.newBrand(OWNER_B_ID, "owner-b@oldco.test", "hash", "Ravi", "Iyer", "Ravi Iyer");
        WorkspaceMember ownerMember = WorkspaceMember.owner(MEMBER_B_ID, BRAND_B_ID, OWNER_B_ID);

        when(workspaceRepository.findById(BRAND_B_ID)).thenReturn(Optional.of(brand));
        when(workspaceMemberRepository.findFirstByWorkspaceIdAndRoleAndActiveTrue(BRAND_B_ID, MemberRole.OWNER))
                .thenReturn(Optional.of(ownerMember));
        when(userRepository.findById(OWNER_B_ID)).thenReturn(Optional.of(owner));
        when(campaignRepository.findByWorkspaceId(BRAND_B_ID)).thenReturn(List.of());
        when(collaborationRepository.findByWorkspaceId(BRAND_B_ID)).thenReturn(List.of());
        when(workspaceMemberRepository.findByWorkspaceIdAndActiveTrue(BRAND_B_ID)).thenReturn(List.of());
        when(walletRepository.findByOwnerId(BRAND_B_ID)).thenReturn(Optional.empty());

        BrandDetailDto dto = service.getById(principal, BRAND_B_ID);

        assertNull(dto.workspacePhone());
        assertNull(dto.ownerPhone());
    }

    // ============================================================
    // 6. Role guard: list()/getById() still gate at SUPER_ADMIN/ADMIN/SUPPORT (MFA-satisfied) —
    //    the new PII fields must not be reachable at a weaker tier than the rest of the DTO.
    // ============================================================
    @Test
    @DisplayName("list(): role guard denial propagates before any brand/phone data is read")
    void testListRoleGuardDenialBlocksRead() {
        when(adminContext.requireRoleWithMfaSatisfied(
                        principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN, AdminRole.SUPPORT))
                .thenThrow(new ApiException("FORBIDDEN", "Insufficient role", HttpStatus.FORBIDDEN));

        ApiException ex =
                assertThrows(ApiException.class, () -> service.list(principal, 1, 20, null, null, null));
        assertEquals("FORBIDDEN", ex.getCode());
        verify(workspaceRepository, never()).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    @DisplayName("getById(): role guard denial propagates before any brand/phone data is read")
    void testGetByIdRoleGuardDenialBlocksRead() {
        when(adminContext.requireRoleWithMfaSatisfied(
                        principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN, AdminRole.SUPPORT))
                .thenThrow(new ApiException("FORBIDDEN", "Insufficient role", HttpStatus.FORBIDDEN));

        ApiException ex = assertThrows(ApiException.class, () -> service.getById(principal, BRAND_A_ID));
        assertEquals("FORBIDDEN", ex.getCode());
        verify(workspaceRepository, never()).findById(any());
    }

    // ============================================================
    // Helpers
    // ============================================================

    private void stubViewRole() {
        when(adminContext.requireRoleWithMfaSatisfied(
                        principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN, AdminRole.SUPPORT))
                .thenReturn(viewingAdmin);
    }

    private void stubDetailRole() {
        when(adminContext.requireRoleWithMfaSatisfied(
                        principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN, AdminRole.SUPPORT))
                .thenReturn(viewingAdmin);
    }

    @SuppressWarnings("unchecked")
    private void stubList(
            List<Workspace> brands, List<WorkspaceMember> owners, List<User> ownerUsers) {
        Page<Workspace> page = new PageImpl<>(brands);
        when(workspaceRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);
        when(campaignRepository.findByWorkspaceIdIn(anyList())).thenReturn(List.of());
        when(workspaceMemberRepository.findByWorkspaceIdInAndRoleAndActiveTrue(anyList(), any(MemberRole.class)))
                .thenReturn(owners);
        when(userRepository.findAllById(anyList())).thenReturn(ownerUsers);
    }
}
