package com.influora.web;

import com.influora.security.AuthPrincipal;
import com.influora.service.admin.AdminSupportService;
import com.influora.web.dto.admin.AdminSupportDtos.AssignRequest;
import com.influora.web.dto.admin.AdminSupportDtos.PagedTicketsDto;
import com.influora.web.dto.admin.AdminSupportDtos.ReplyRequest;
import com.influora.web.dto.admin.AdminSupportDtos.TicketDetailDto;
import com.influora.web.dto.admin.AdminSupportDtos.UpdateStatusRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Support ticket CRUD/triage API (src/admin/TASK_ASSIGNMENTS.md P2: "Support ticket API" —
 * Vikram, cycle 6). Mounted at {@code /admin/support/tickets} (full path {@code
 * /api/v1/admin/support/tickets/...} given {@code server.servlet.context-path=/api/v1} — same
 * convention/caveat as every other {@code Admin*Controller}, see {@code AdminBrandController}'s
 * class javadoc for the path-mismatch note that still applies here unchanged).
 *
 * <p>Path/verb shape matches {@code supportApi} in {@code src/admin/services/api-contracts.ts}
 * (Priya) exactly: {@code list} -> {@code GET /support/tickets}, {@code getById} -> {@code GET
 * /support/tickets/{id}}, {@code reply} -> {@code POST /support/tickets/{id}/reply}, {@code
 * update} -> {@code PUT /support/tickets/{id}} (this cycle's status-change use case — other {@code
 * Partial<SupportTicket>} fields {@code supportApi.update} types are accepted-but-currently-unused
 * request shape, not yet wired to additional mutations), {@code assign} -> {@code POST
 * /support/tickets/{id}/assign}. {@code supportApi.escalate}/{@code .getStats} are NOT built this
 * cycle — no frontend caller needs them yet ({@code src/admin/components/support/} has no {@code
 * TicketList.tsx} as of this cycle), flagged as follow-up scope, not silently forgotten, same
 * pattern {@code AdminBrandController}/{@code AdminCreatorController} document for their own
 * unimplemented endpoints.
 *
 * <p>Returns raw DTOs (unwrapped, no {@link com.influora.common.ApiResponse} envelope) — same
 * deliberate deviation as every other {@code Admin*Controller}, to match {@code apiRequest()}'s
 * client contract (it wraps whatever JSON body it gets into {@code ApiResponse<T>} itself).
 */
@RestController
@RequestMapping("/admin/support/tickets")
public class AdminSupportController {

    private final AdminSupportService adminSupportService;

    public AdminSupportController(AdminSupportService adminSupportService) {
        this.adminSupportService = adminSupportService;
    }

    @GetMapping
    public PagedTicketsDto list(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String priority,
            @RequestParam(required = false) String userType,
            @RequestParam(required = false) String assignedTo,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return adminSupportService.list(
                principal, status, priority, userType, assignedTo, category, search, page, pageSize);
    }

    @GetMapping("/{id}")
    public TicketDetailDto getById(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable String id) {
        return adminSupportService.getById(principal, id);
    }

    @PostMapping("/{id}/reply")
    public TicketDetailDto reply(
            @AuthenticationPrincipal AuthPrincipal principal,
            HttpServletRequest request,
            @PathVariable String id,
            @Valid @RequestBody ReplyRequest body) {
        return adminSupportService.reply(principal, request, id, body.content());
    }

    @PutMapping("/{id}")
    public TicketDetailDto updateStatus(
            @AuthenticationPrincipal AuthPrincipal principal,
            HttpServletRequest request,
            @PathVariable String id,
            @Valid @RequestBody UpdateStatusRequest body) {
        return adminSupportService.updateStatus(principal, request, id, body.status(), body.reason());
    }

    @PostMapping("/{id}/assign")
    public TicketDetailDto assign(
            @AuthenticationPrincipal AuthPrincipal principal,
            HttpServletRequest request,
            @PathVariable String id,
            @RequestBody AssignRequest body) {
        return adminSupportService.assign(principal, request, id, body.assignedTo());
    }
}
