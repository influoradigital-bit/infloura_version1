package com.influora.service.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.AdminUser;
import com.influora.domain.entity.ErrorLog;
import com.influora.domain.enums.AdminRole;
import com.influora.domain.enums.ErrorLogSeverity;
import com.influora.repository.ErrorLogRepository;
import com.influora.repository.ErrorLogRepository.EndpointCount;
import com.influora.security.AuthPrincipal;
import com.influora.web.dto.admin.AdminErrorLogDtos.ErrorLogEntryDto;
import com.influora.web.dto.admin.AdminErrorLogDtos.ErrorStatsDto;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;

/**
 * Unit tests for {@link AdminErrorLogService} — the read/triage side of the admin error-log console
 * ({@code errorApi}). The backend shipped (commit 9d22e4c) with no test coverage; this fills it,
 * proving the SUPER_ADMIN-only gate fires on every method, the recent-limit is clamped, a missing
 * id 404s, resolve stamps the acting admin and persists, and stats maps every aggregate + projection.
 *
 * <p>NOT covered (declared): the repository queries themselves against real MySQL (repo is mocked);
 * that {@code critical24h} is honest-0 is a production-data property, not this service's logic.
 */
@ExtendWith(MockitoExtension.class)
class AdminErrorLogServiceTest {

    @Mock private AdminContextService adminContext;
    @Mock private ErrorLogRepository errorLogRepository;
    @Mock private AuthPrincipal principal;

    private AdminErrorLogService service() {
        return new AdminErrorLogService(adminContext, errorLogRepository);
    }

    private static ErrorLog error(String id, ErrorLogSeverity severity, String endpoint) {
        return ErrorLog.builder()
                .id(id)
                .severity(severity)
                .message("boom")
                .endpoint(endpoint)
                .userId("u1")
                .stackTrace("java.lang.RuntimeException: boom")
                .build();
    }

    @Test
    @DisplayName("recent: gates SUPER_ADMIN and maps each ErrorLog to its DTO")
    void getRecent_gatesAndMaps() {
        ErrorLog e1 = error("e1", ErrorLogSeverity.ERROR, "/api/v1/campaigns");
        when(errorLogRepository.findAllByOrderByCreatedAtDesc(any())).thenReturn(List.of(e1));

        List<ErrorLogEntryDto> rows = service().getRecent(principal, 50);

        verify(adminContext).requireRoleWithMfaSatisfied(principal, AdminRole.SUPER_ADMIN);
        assertEquals(1, rows.size());
        ErrorLogEntryDto d = rows.get(0);
        assertEquals("e1", d.id());
        assertEquals("ERROR", d.severity());
        assertEquals("/api/v1/campaigns", d.endpoint());
        assertFalse(d.resolved());
        assertEquals(e1.getCreatedAt(), d.createdAt());
    }

    @Test
    @DisplayName("recent: limit is clamped into [1, 200]")
    void getRecent_clampsLimit() {
        when(errorLogRepository.findAllByOrderByCreatedAtDesc(any())).thenReturn(List.of());
        service().getRecent(principal, 9999); // over cap
        service().getRecent(principal, 0); // under floor

        ArgumentCaptor<Pageable> cap = ArgumentCaptor.forClass(Pageable.class);
        verify(errorLogRepository, times(2)).findAllByOrderByCreatedAtDesc(cap.capture());
        assertEquals(200, cap.getAllValues().get(0).getPageSize());
        assertEquals(1, cap.getAllValues().get(1).getPageSize());
    }

    @Test
    @DisplayName("getById: unknown id -> 404 ApiException")
    void getById_notFound() {
        when(errorLogRepository.findById("missing")).thenReturn(Optional.empty());

        ApiException ex =
                assertThrows(ApiException.class, () -> service().getById(principal, "missing"));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        verify(adminContext).requireRoleWithMfaSatisfied(principal, AdminRole.SUPER_ADMIN);
    }

    @Test
    @DisplayName("resolve: stamps the acting admin, persists, returns resolved DTO")
    void resolve_marksAndSaves() {
        AdminUser admin =
                AdminUser.create("admin-1", "admin@influora.ai", "hash", AdminRole.SUPER_ADMIN);
        when(adminContext.requireRoleWithMfaSatisfied(principal, AdminRole.SUPER_ADMIN))
                .thenReturn(admin);
        ErrorLog e1 = error("e1", ErrorLogSeverity.ERROR, "/api/v1/x");
        when(errorLogRepository.findById("e1")).thenReturn(Optional.of(e1));

        ErrorLogEntryDto d = service().resolve(principal, "e1");

        verify(errorLogRepository).save(e1);
        assertTrue(d.resolved());
        assertEquals("admin-1", d.resolvedBy());
    }

    @Test
    @DisplayName("stats: gates and maps every aggregate + top-endpoint projection")
    void getStats_aggregates() {
        EndpointCount ec = mock(EndpointCount.class);
        when(ec.getEndpoint()).thenReturn("/api/v1/deals");
        when(ec.getCnt()).thenReturn(7L);
        when(errorLogRepository.countByCreatedAtAfter(any())).thenReturn(10L);
        when(errorLogRepository.countBySeverityAndCreatedAtAfter(
                        eq(ErrorLogSeverity.CRITICAL), any()))
                .thenReturn(0L);
        when(errorLogRepository.countByResolvedFalse()).thenReturn(3L);
        when(errorLogRepository.topEndpointsSince(any(), any())).thenReturn(List.of(ec));

        ErrorStatsDto stats = service().getStats(principal);

        verify(adminContext).requireRoleWithMfaSatisfied(principal, AdminRole.SUPER_ADMIN);
        assertEquals(10L, stats.total24h());
        assertEquals(0L, stats.critical24h());
        assertEquals(3L, stats.unresolved());
        assertEquals(1, stats.topEndpoints().size());
        assertEquals("/api/v1/deals", stats.topEndpoints().get(0).endpoint());
        assertEquals(7L, stats.topEndpoints().get(0).count());
    }

    @Test
    @DisplayName("unauthorized (non-SUPER_ADMIN) caller is blocked before any read")
    void unauthorized_readsNothing() {
        when(adminContext.requireRoleWithMfaSatisfied(principal, AdminRole.SUPER_ADMIN))
                .thenThrow(new ApiException("INSUFFICIENT_ROLE", "nope", HttpStatus.FORBIDDEN));

        assertThrows(ApiException.class, () -> service().getRecent(principal, 50));

        verify(errorLogRepository, never()).findAllByOrderByCreatedAtDesc(any());
    }
}
