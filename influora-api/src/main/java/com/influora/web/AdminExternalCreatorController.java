package com.influora.web;

import com.influora.security.AuthPrincipal;
import com.influora.service.admin.AdminCreatorConnectionService;
import com.influora.web.dto.admin.AdminCreatorConnectionDtos.AdminExternalCreatorDto;
import com.influora.web.dto.admin.AdminCreatorConnectionDtos.ImportRequest;
import com.influora.web.dto.admin.AdminCreatorConnectionDtos.ImportResult;
import com.influora.web.dto.admin.AdminCreatorConnectionDtos.PagedConnectionsDto;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin bulk-import / listing surface for {@code external_creators} (T-CREATORCONNECT-0902).
 * Deliberately a separate controller from {@code AdminCreatorConnectionController}: the TASKS.md
 * contract mounts these two endpoints at {@code /admin/external-creators}, a sibling path to
 * {@code /admin/creator-connections}, not nested under it. Same raw-DTO, service-layer-auth
 * discipline as every other {@code Admin*Controller}.
 */
@RestController
@RequestMapping("/admin/external-creators")
public class AdminExternalCreatorController {

    private final AdminCreatorConnectionService adminCreatorConnectionService;

    public AdminExternalCreatorController(AdminCreatorConnectionService adminCreatorConnectionService) {
        this.adminCreatorConnectionService = adminCreatorConnectionService;
    }

    @GetMapping
    public PagedConnectionsDto<AdminExternalCreatorDto> list(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return adminCreatorConnectionService.listExternalCreators(principal, status, q, page, pageSize);
    }

    @PostMapping("/import")
    public ImportResult importHandles(
            @AuthenticationPrincipal AuthPrincipal principal,
            HttpServletRequest request,
            @RequestBody ImportRequest body) {
        return adminCreatorConnectionService.importHandles(principal, request, body.usernames());
    }

    /**
     * Q4.4 (T-CREATORCONNECT-0902, High) — the only way to remove a bad/garbage {@code
     * external_creators} stub before this; guarded in the service against rows still referenced by
     * a {@code creator_connection_requests} row.
     */
    @DeleteMapping("/{id}")
    public void delete(
            @AuthenticationPrincipal AuthPrincipal principal,
            HttpServletRequest request,
            @PathVariable String id) {
        adminCreatorConnectionService.deleteExternalCreator(principal, request, id);
    }
}
