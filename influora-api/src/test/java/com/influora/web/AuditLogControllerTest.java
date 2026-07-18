package com.influora.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.security.AuthPrincipal;
import com.influora.service.admin.AdminAuditLogService;
import com.influora.web.dto.admin.AdminAuditLogDtos.AuditLogEntryDto;
import com.influora.web.dto.admin.AdminAuditLogDtos.PagedAuditLogDto;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

/**
 * Cycle 7 — AuditLogController unit tests.
 *
 * <p>No {@code @WebMvcTest} harness exists for Admin*Controller classes yet (cycle 6 report — Kavya
 * — confirmed no such infrastructure exists). This test mocks the service layer directly following
 * the same pattern as {@link WalletControllerTest} (plain unit test, not integration). Verifies RBAC
 * enforcement (SUPER_ADMIN only, ADMIN + SUPPORT rejected) and basic delegation to {@link
 * AdminAuditLogService}.
 *
 * <p>Role-gating lives entirely in {@code AdminAuditLogService} (via {@code
 * AdminContextService#requireRoleWithMfaSatisfied}) — {@link AuditLogController} itself has no
 * {@code @PreAuthorize} annotation and carries no role-check logic of its own (see its class
 * javadoc). {@link AuthPrincipal} has no role accessor at all (role lives on {@code AdminUser},
 * resolved server-side by {@code AdminContextService} from {@code principal.getUserId()}), so
 * these tests simulate rejection by stubbing the mocked {@link AdminAuditLogService} to throw the
 * same {@link ApiException} the real service throws for non-SUPER_ADMIN callers, then assert the
 * controller propagates it unchanged.
 *
 * <p>Run: {@code mvn -o test -Dtest=AuditLogControllerTest}
 */
@ExtendWith(MockitoExtension.class)
class AuditLogControllerTest {

  @Mock private AdminAuditLogService adminAuditLogService;
  @Mock private AuthPrincipal superAdminPrincipal;
  @Mock private AuthPrincipal adminPrincipal;
  @Mock private AuthPrincipal supportPrincipal;

  private AuditLogController controller;

  @BeforeEach
  void setUp() {
    controller = new AuditLogController(adminAuditLogService);
  }

  // ============================================
  // GET /audit — list()
  // ============================================

  @Test
  @DisplayName("GET /audit with SUPER_ADMIN delegates to service and returns paged result")
  void testListWithSuperAdminSucceeds() {
    AuditLogEntryDto entry =
        new AuditLogEntryDto(
            "01HAUDITLOG123456ABC",
            "01HADMIN123456789ABC",
            "admin@influora.test",
            "UPDATE",
            "BRAND",
            "brand-xyz",
            null,
            null,
            "Approved KYC",
            "192.168.1.100",
            "SERVER_INTERNAL",
            Instant.parse("2026-07-09T10:00:00Z"));
    PagedAuditLogDto paged = new PagedAuditLogDto(List.of(entry), 1, 1, 50, 1);

    when(adminAuditLogService.list(
            superAdminPrincipal, null, null, null, null, null, 1, 50))
        .thenReturn(paged);

    PagedAuditLogDto result =
        controller.list(superAdminPrincipal, null, null, null, null, null, 1, 50);

    assertNotNull(result);
    assertEquals(1, result.data().size());
    assertEquals("01HAUDITLOG123456ABC", result.data().get(0).id());
    assertEquals(1, result.page());
    assertEquals(50, result.pageSize());
    assertEquals(1, result.total());

    verify(adminAuditLogService)
        .list(superAdminPrincipal, null, null, null, null, null, 1, 50);
  }

  @Test
  @DisplayName("GET /audit with ADMIN role rejects (SUPER_ADMIN only)")
  void testListWithAdminRoleRejects() {
    when(adminAuditLogService.list(adminPrincipal, null, null, null, null, null, 1, 50))
        .thenThrow(
            new ApiException(
                "ROLE_INSUFFICIENT",
                "Access to audit logs requires SUPER_ADMIN role.",
                HttpStatus.FORBIDDEN));

    ApiException ex =
        assertThrows(
            ApiException.class,
            () -> controller.list(adminPrincipal, null, null, null, null, null, 1, 50));

    assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
    assertEquals("ROLE_INSUFFICIENT", ex.getCode());
  }

  @Test
  @DisplayName("GET /audit with SUPPORT role rejects (SUPER_ADMIN only)")
  void testListWithSupportRoleRejects() {
    when(adminAuditLogService.list(supportPrincipal, null, null, null, null, null, 1, 50))
        .thenThrow(
            new ApiException(
                "ROLE_INSUFFICIENT",
                "Access to audit logs requires SUPER_ADMIN role.",
                HttpStatus.FORBIDDEN));

    ApiException ex =
        assertThrows(
            ApiException.class,
            () -> controller.list(supportPrincipal, null, null, null, null, null, 1, 50));

    assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
  }

  @Test
  @DisplayName("GET /audit delegates with filters and pagination params")
  void testListDelegatesWithFilters() {
    PagedAuditLogDto paged = new PagedAuditLogDto(List.of(), 0, 2, 20, 0);

    when(adminAuditLogService.list(
            superAdminPrincipal,
            "01HADMIN123",
            "BRAND",
            "UPDATE",
            "2026-07-01T00:00:00Z",
            "2026-07-09T23:59:59Z",
            2,
            20))
        .thenReturn(paged);

    PagedAuditLogDto result =
        controller.list(
            superAdminPrincipal,
            "01HADMIN123",
            "BRAND",
            "UPDATE",
            "2026-07-01T00:00:00Z",
            "2026-07-09T23:59:59Z",
            2,
            20);

    assertNotNull(result);
    assertEquals(2, result.page());
    assertEquals(20, result.pageSize());

    verify(adminAuditLogService)
        .list(
            superAdminPrincipal,
            "01HADMIN123",
            "BRAND",
            "UPDATE",
            "2026-07-01T00:00:00Z",
            "2026-07-09T23:59:59Z",
            2,
            20);
  }

  // ============================================
  // GET /audit/entity/{entityType}/{entityId} — getByEntity()
  // ============================================

  @Test
  @DisplayName("GET /audit/entity/{entityType}/{entityId} with SUPER_ADMIN delegates to service")
  void testGetByEntityWithSuperAdminSucceeds() {
    AuditLogEntryDto entry1 =
        new AuditLogEntryDto(
            "01HAUDITLOG1",
            "01HADMIN1",
            "admin@influora.test",
            "CREATE",
            "BRAND",
            "brand-abc",
            null,
            null,
            "Created brand",
            "192.168.1.1",
            "SERVER_INTERNAL",
            Instant.parse("2026-07-01T10:00:00Z"));
    AuditLogEntryDto entry2 =
        new AuditLogEntryDto(
            "01HAUDITLOG2",
            "01HADMIN2",
            "admin2@influora.test",
            "UPDATE",
            "BRAND",
            "brand-abc",
            null,
            null,
            "Approved KYC",
            "192.168.1.2",
            "SERVER_INTERNAL",
            Instant.parse("2026-07-09T12:00:00Z"));

    when(adminAuditLogService.getByEntity(superAdminPrincipal, "BRAND", "brand-abc"))
        .thenReturn(List.of(entry1, entry2));

    List<AuditLogEntryDto> result =
        controller.getByEntity(superAdminPrincipal, "BRAND", "brand-abc");

    assertNotNull(result);
    assertEquals(2, result.size());
    assertEquals("01HAUDITLOG1", result.get(0).id());
    assertEquals("CREATE", result.get(0).action());
    assertEquals("01HAUDITLOG2", result.get(1).id());
    assertEquals("UPDATE", result.get(1).action());

    verify(adminAuditLogService).getByEntity(superAdminPrincipal, "BRAND", "brand-abc");
  }

  @Test
  @DisplayName("GET /audit/entity/{entityType}/{entityId} with ADMIN role rejects")
  void testGetByEntityWithAdminRoleRejects() {
    when(adminAuditLogService.getByEntity(adminPrincipal, "CREATOR", "creator-xyz"))
        .thenThrow(
            new ApiException(
                "ROLE_INSUFFICIENT",
                "Access to audit logs requires SUPER_ADMIN role.",
                HttpStatus.FORBIDDEN));

    ApiException ex =
        assertThrows(
            ApiException.class,
            () -> controller.getByEntity(adminPrincipal, "CREATOR", "creator-xyz"));

    assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
  }

  @Test
  @DisplayName("GET /audit/entity/{entityType}/{entityId} with SUPPORT role rejects")
  void testGetByEntityWithSupportRoleRejects() {
    when(adminAuditLogService.getByEntity(supportPrincipal, "CAMPAIGN", "campaign-123"))
        .thenThrow(
            new ApiException(
                "ROLE_INSUFFICIENT",
                "Access to audit logs requires SUPER_ADMIN role.",
                HttpStatus.FORBIDDEN));

    ApiException ex =
        assertThrows(
            ApiException.class,
            () -> controller.getByEntity(supportPrincipal, "CAMPAIGN", "campaign-123"));

    assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
  }

  @Test
  @DisplayName("GET /audit/entity/{entityType}/{entityId} returns empty list when no logs exist")
  void testGetByEntityReturnsEmptyListWhenNoLogs() {
    when(adminAuditLogService.getByEntity(superAdminPrincipal, "DELIVERABLE", "deliverable-xyz"))
        .thenReturn(List.of());

    List<AuditLogEntryDto> result =
        controller.getByEntity(superAdminPrincipal, "DELIVERABLE", "deliverable-xyz");

    assertNotNull(result);
    assertEquals(0, result.size());

    verify(adminAuditLogService).getByEntity(superAdminPrincipal, "DELIVERABLE", "deliverable-xyz");
  }
}
