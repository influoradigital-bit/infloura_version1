package com.influora.service.admin;

import com.influora.common.ApiException;
import com.influora.domain.entity.ErrorLog;
import com.influora.domain.enums.AdminRole;
import com.influora.domain.enums.ErrorLogSeverity;
import com.influora.repository.ErrorLogRepository;
import com.influora.security.AuthPrincipal;
import com.influora.web.dto.admin.AdminErrorLogDtos.EndpointCountDto;
import com.influora.web.dto.admin.AdminErrorLogDtos.ErrorLogEntryDto;
import com.influora.web.dto.admin.AdminErrorLogDtos.ErrorStatsDto;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read/triage side of the admin error-log console ({@code errorApi}, api-contracts.ts 654-671).
 * The write side is {@link com.influora.service.ErrorLogService}, driven by {@code
 * GlobalExceptionHandler}.
 *
 * <p><b>RBAC:</b> every method gates {@code SUPER_ADMIN} only via {@link
 * AdminContextService#requireRoleWithMfaSatisfied}. Error logs can contain endpoint paths,
 * principal ids and stack traces (a sensitive operations surface), so this is deliberately the
 * tightest tier — not ADMIN/SUPPORT.
 */
@Service
public class AdminErrorLogService {

    private static final int MAX_LIMIT = 200;
    private static final int TOP_ENDPOINTS = 10;
    private static final Duration WINDOW_24H = Duration.ofHours(24);

    private final AdminContextService adminContext;
    private final ErrorLogRepository errorLogRepository;

    public AdminErrorLogService(
            AdminContextService adminContext, ErrorLogRepository errorLogRepository) {
        this.adminContext = adminContext;
        this.errorLogRepository = errorLogRepository;
    }

    @Transactional(readOnly = true)
    public List<ErrorLogEntryDto> getRecent(AuthPrincipal principal, int limit) {
        adminContext.requireRoleWithMfaSatisfied(principal, AdminRole.SUPER_ADMIN);
        int safeLimit = Math.min(Math.max(limit, 1), MAX_LIMIT);
        return errorLogRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, safeLimit)).stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public ErrorLogEntryDto getById(AuthPrincipal principal, String id) {
        adminContext.requireRoleWithMfaSatisfied(principal, AdminRole.SUPER_ADMIN);
        return toDto(requireError(id));
    }

    @Transactional
    public ErrorLogEntryDto resolve(AuthPrincipal principal, String id) {
        var admin = adminContext.requireRoleWithMfaSatisfied(principal, AdminRole.SUPER_ADMIN);
        ErrorLog error = requireError(id);
        error.markResolved(admin.getId());
        errorLogRepository.save(error);
        return toDto(error);
    }

    /**
     * {@code total24h}, {@code unresolved} and {@code topEndpoints} are real aggregates over
     * {@code error_log}. {@code critical24h} is an honest {@code 0} today: the only producer —
     * {@code GlobalExceptionHandler}'s catch-all 500 handler — always writes {@link
     * ErrorLogSeverity#ERROR}, so no CRITICAL row can exist yet. The count is computed correctly
     * (it will start returning real numbers the moment a CRITICAL classifier is wired) rather than
     * hard-coded — see {@code ErrorLogSeverity} javadoc for the gap.
     */
    @Transactional(readOnly = true)
    public ErrorStatsDto getStats(AuthPrincipal principal) {
        adminContext.requireRoleWithMfaSatisfied(principal, AdminRole.SUPER_ADMIN);
        Instant since = Instant.now().minus(WINDOW_24H);

        long total24h = errorLogRepository.countByCreatedAtAfter(since);
        long critical24h =
                errorLogRepository.countBySeverityAndCreatedAtAfter(ErrorLogSeverity.CRITICAL, since);
        long unresolved = errorLogRepository.countByResolvedFalse();

        List<EndpointCountDto> topEndpoints =
                errorLogRepository.topEndpointsSince(since, PageRequest.of(0, TOP_ENDPOINTS)).stream()
                        .map(ec -> new EndpointCountDto(ec.getEndpoint(), ec.getCnt()))
                        .toList();

        return new ErrorStatsDto(total24h, critical24h, unresolved, topEndpoints);
    }

    private ErrorLog requireError(String id) {
        return errorLogRepository
                .findById(id)
                .orElseThrow(
                        () -> new ApiException("ERROR_NOT_FOUND", "Error not found", HttpStatus.NOT_FOUND));
    }

    private ErrorLogEntryDto toDto(ErrorLog e) {
        return new ErrorLogEntryDto(
                e.getId(),
                e.getSeverity().name(),
                e.getMessage(),
                e.getStackTrace(),
                e.getEndpoint(),
                e.getUserId(),
                e.isResolved(),
                e.getResolvedBy(),
                e.getResolvedAt(),
                e.getCreatedAt());
    }
}
