package com.influora.web;

import com.influora.common.ApiErrorBody;
import com.influora.common.ApiResponse;
import com.influora.security.AuthPrincipal;
import com.influora.service.admin.AdminEmailService;
import com.influora.web.dto.admin.AdminEmailDtos.EmailQueueItemDto;
import com.influora.web.dto.admin.AdminEmailDtos.EmailStatsDto;
import com.influora.web.dto.admin.AdminEmailDtos.EmailTemplateDto;
import com.influora.web.dto.admin.AdminEmailDtos.PagedEmailQueueDto;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Email-queue console API. {@code emailApi} (src/admin/services/api-contracts.ts, lines 677-706)
 * calls {@code GET /emails/queue}, {@code POST /emails/queue/{id}/retry}, {@code GET
 * /emails/templates}, {@code POST /emails/send-bulk} and {@code GET /emails/stats} against
 * {@code API_BASE = '/api/v1/admin'} — mounted here at {@code /admin/emails}. Backed by the
 * existing {@code email_outbox} table via {@link AdminEmailService}; RBAC (SUPER_ADMIN + MFA) is
 * enforced there.
 */
@RestController
@RequestMapping("/admin/emails")
public class AdminEmailController {

    private final AdminEmailService adminEmailService;

    public AdminEmailController(AdminEmailService adminEmailService) {
        this.adminEmailService = adminEmailService;
    }

    @GetMapping("/queue")
    public PagedEmailQueueDto queue(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "pageSize", defaultValue = "20") int pageSize) {
        return adminEmailService.getQueue(principal, status, page, pageSize);
    }

    @PostMapping("/queue/{id}/retry")
    public EmailQueueItemDto retry(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable("id") String id) {
        return adminEmailService.retry(principal, id);
    }

    @GetMapping("/templates")
    public List<EmailTemplateDto> templates(@AuthenticationPrincipal AuthPrincipal principal) {
        return adminEmailService.getTemplates(principal);
    }

    @GetMapping("/stats")
    public EmailStatsDto stats(@AuthenticationPrincipal AuthPrincipal principal) {
        return adminEmailService.getStats(principal);
    }

    /**
     * DANGEROUS — intentionally NOT wired to enqueue/send mass email this pass. Returns 501 Not
     * Implemented. Still gated SUPER_ADMIN + MFA so the endpoint can't be probed anonymously, then
     * refuses to act.
     *
     * <p>Kabir: bulk send needs abuse controls before enabling — per-send rate limiting, a hard
     * recipient cap, dry-run/preview + explicit confirmation, an audit trail of who sent what to
     * how many, and unsubscribe/consent enforcement. Do NOT flip this to a real enqueue until that
     * review is done.
     */
    @PostMapping("/send-bulk")
    public ResponseEntity<ApiResponse<Void>> sendBulk(
            @AuthenticationPrincipal AuthPrincipal principal) {
        // Enforce the SUPER_ADMIN + MFA gate even though we refuse to act, so this can't be used as
        // an unauthenticated probe of the bulk-send surface.
        adminEmailService.requireBulkSendAuthority(principal);
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                .body(
                        ApiResponse.fail(
                                ApiErrorBody.of(
                                        "BULK_SEND_DISABLED",
                                        "Bulk send is disabled pending per-send controls + rate limiting + Kabir review")));
    }
}
