package com.influora.service.admin;

import com.influora.common.ApiException;
import com.influora.domain.entity.EmailOutbox;
import com.influora.domain.enums.AdminRole;
import com.influora.domain.enums.EmailOutboxStatus;
import com.influora.repository.EmailOutboxRepository;
import com.influora.security.AuthPrincipal;
import com.influora.web.dto.admin.AdminEmailDtos.EmailQueueItemDto;
import com.influora.web.dto.admin.AdminEmailDtos.EmailStatsDto;
import com.influora.web.dto.admin.AdminEmailDtos.EmailTemplateDto;
import com.influora.web.dto.admin.AdminEmailDtos.PagedEmailQueueDto;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin email-queue console ({@code emailApi}, api-contracts.ts 677-706). Reads/acts on the
 * existing {@code email_outbox} table (Domain B, transactional outbox pattern) drained by {@code
 * EmailWorker} — no new queue table was introduced.
 *
 * <p><b>RBAC:</b> every endpoint gates {@code SUPER_ADMIN} only (+ MFA) via {@link
 * AdminContextService#requireRoleWithMfaSatisfied}. Recipient addresses and manual retry / bulk
 * send are a sensitive operations surface, so this is the tightest tier — not ADMIN/SUPPORT.
 *
 * <p><b>Honest gaps (reported, not faked):</b>
 *
 * <ul>
 *   <li>{@code templateName} = {@code templateKey}: there is no server-side template registry
 *       (MSG91 owns display names/subjects), so name mirrors the key and template {@code subject}
 *       is empty. {@link #getTemplates} returns the DISTINCT keys actually present in the outbox —
 *       real data, not a fabricated catalog.
 *   <li>{@code stats.failed24h} is approximate — the outbox has no {@code failed_at} column, so it
 *       counts FAILED rows *created* in the window (see {@link #getStats}).
 *   <li>{@link #sendBulk} is intentionally NOT wired to send — returns 501 pending abuse controls.
 * </ul>
 */
@Service
public class AdminEmailService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final Duration WINDOW_24H = Duration.ofHours(24);

    private final AdminContextService adminContext;
    private final EmailOutboxRepository emailOutboxRepository;

    public AdminEmailService(
            AdminContextService adminContext, EmailOutboxRepository emailOutboxRepository) {
        this.adminContext = adminContext;
        this.emailOutboxRepository = emailOutboxRepository;
    }

    @Transactional(readOnly = true)
    public PagedEmailQueueDto getQueue(AuthPrincipal principal, String status, int page, int pageSize) {
        adminContext.requireRoleWithMfaSatisfied(principal, AdminRole.SUPER_ADMIN);

        int safePage = Math.max(page, 1);
        int safePageSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        PageRequest pageable = PageRequest.of(safePage - 1, safePageSize);

        EmailOutboxStatus statusFilter = parseStatus(status);
        Page<EmailOutbox> result =
                statusFilter == null
                        ? emailOutboxRepository.findAllByOrderByCreatedAtDesc(pageable)
                        : emailOutboxRepository.findByStatusOrderByCreatedAtDesc(statusFilter, pageable);

        List<EmailQueueItemDto> data = result.getContent().stream().map(this::toDto).toList();
        return new PagedEmailQueueDto(
                data, result.getTotalElements(), safePage, safePageSize, result.getTotalPages());
    }

    @Transactional
    public EmailQueueItemDto retry(AuthPrincipal principal, String id) {
        adminContext.requireRoleWithMfaSatisfied(principal, AdminRole.SUPER_ADMIN);
        EmailOutbox outbox = requireOutbox(id);

        // Only a FAILED row can be manually retried — SENT is terminal-success and PENDING is
        // already queued for the worker. Guarding here keeps EmailOutbox.requeue() a pure mutation.
        if (outbox.getStatus() != EmailOutboxStatus.FAILED) {
            throw new ApiException(
                    "EMAIL_NOT_RETRYABLE",
                    "Only a FAILED email can be retried; this one is " + outbox.getStatus().name(),
                    HttpStatus.CONFLICT);
        }

        outbox.requeue();
        emailOutboxRepository.save(outbox);
        return toDto(outbox);
    }

    /**
     * Real distinct template keys from the outbox. {@code name = id = templateKey}; {@code subject}
     * is empty because no server-side subject store exists (MSG91 template owns the subject). Honest
     * over fabricated — returns exactly the templates the system has actually used.
     */
    @Transactional(readOnly = true)
    public List<EmailTemplateDto> getTemplates(AuthPrincipal principal) {
        adminContext.requireRoleWithMfaSatisfied(principal, AdminRole.SUPER_ADMIN);
        return emailOutboxRepository.findDistinctTemplateKeys().stream()
                .map(key -> new EmailTemplateDto(key, key, ""))
                .toList();
    }

    /**
     * {@code sent24h}/{@code pending}/{@code avgDeliveryTime} are real. {@code failed24h} is an
     * approximation: {@code email_outbox} has no {@code failed_at}/{@code updated_at} column, so
     * "failed in the last 24h" can't be measured exactly — this counts FAILED rows *created* in the
     * window. Follow-up: add a {@code failed_at} column, stamped in {@code EmailOutbox.markFailed}.
     */
    @Transactional(readOnly = true)
    public EmailStatsDto getStats(AuthPrincipal principal) {
        adminContext.requireRoleWithMfaSatisfied(principal, AdminRole.SUPER_ADMIN);
        Instant since = Instant.now().minus(WINDOW_24H);

        long sent24h =
                emailOutboxRepository.countByStatusAndSentAtAfter(EmailOutboxStatus.SENT, since);
        long failed24h =
                emailOutboxRepository.countByStatusAndCreatedAtAfter(EmailOutboxStatus.FAILED, since);
        long pending = emailOutboxRepository.countByStatus(EmailOutboxStatus.PENDING);

        List<EmailOutbox> sentInWindow =
                emailOutboxRepository.findByStatusAndSentAtAfter(EmailOutboxStatus.SENT, since);
        double avgDeliveryTime =
                sentInWindow.isEmpty()
                        ? 0.0
                        : sentInWindow.stream()
                                        .filter(e -> e.getSentAt() != null && e.getCreatedAt() != null)
                                        .mapToLong(
                                                e ->
                                                        Duration.between(e.getCreatedAt(), e.getSentAt())
                                                                .toSeconds())
                                        .sum()
                                / (double) sentInWindow.size();

        return new EmailStatsDto(sent24h, failed24h, pending, avgDeliveryTime);
    }

    /**
     * SUPER_ADMIN + MFA gate for the bulk-send surface. The endpoint refuses to act (501) but still
     * enforces authority so it can't be probed anonymously. No send logic lives here on purpose —
     * see {@code AdminEmailController.sendBulk}'s "Kabir: bulk send needs abuse controls" note.
     */
    public void requireBulkSendAuthority(AuthPrincipal principal) {
        adminContext.requireRoleWithMfaSatisfied(principal, AdminRole.SUPER_ADMIN);
    }

    private EmailOutbox requireOutbox(String id) {
        return emailOutboxRepository
                .findById(id)
                .orElseThrow(
                        () ->
                                new ApiException(
                                        "EMAIL_NOT_FOUND", "Email not found", HttpStatus.NOT_FOUND));
    }

    private EmailOutboxStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return EmailOutboxStatus.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ApiException(
                    "INVALID_STATUS",
                    "status must be one of PENDING, SENT, FAILED",
                    HttpStatus.BAD_REQUEST);
        }
    }

    private EmailQueueItemDto toDto(EmailOutbox e) {
        // RETRYING is a derived view state: a PENDING row that has already failed at least once and
        // is waiting on backoff. The outbox enum itself has no RETRYING value (PENDING/SENT/FAILED).
        String status = e.getStatus().name();
        if (e.getStatus() == EmailOutboxStatus.PENDING && e.getRetryCount() > 0) {
            status = "RETRYING";
        }
        Instant scheduledAt = e.getNextRetryAt() != null ? e.getNextRetryAt() : e.getCreatedAt();
        return new EmailQueueItemDto(
                e.getId(),
                e.getToEmail(),
                e.getTemplateKey(),
                e.getTemplateKey(),
                status,
                e.getRetryCount(),
                scheduledAt,
                e.getSentAt(),
                e.getErrorMessage());
    }
}
