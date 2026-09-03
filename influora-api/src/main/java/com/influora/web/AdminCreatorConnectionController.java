package com.influora.web;

import com.influora.security.AuthPrincipal;
import com.influora.service.admin.AdminCreatorConnectionService;
import com.influora.web.dto.admin.AdminCreatorConnectionDtos.AdminConnectionDto;
import com.influora.web.dto.admin.AdminCreatorConnectionDtos.InviteRequest;
import com.influora.web.dto.admin.AdminCreatorConnectionDtos.NotesRequest;
import com.influora.web.dto.admin.AdminCreatorConnectionDtos.PagedConnectionsDto;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin console API for T-CREATORCONNECT-0902 — Meta-sourced creator connection requests +
 * external-creator bulk import. Follows {@code AdminCreatorController}'s discipline exactly: raw
 * DTOs (no {@link com.influora.common.ApiResponse} envelope), admin auth enforced in the service
 * layer (see {@code AdminCreatorConnectionService}), every mutation audit-logged there.
 */
@RestController
@RequestMapping("/admin/creator-connections")
public class AdminCreatorConnectionController {

    private final AdminCreatorConnectionService adminCreatorConnectionService;

    public AdminCreatorConnectionController(AdminCreatorConnectionService adminCreatorConnectionService) {
        this.adminCreatorConnectionService = adminCreatorConnectionService;
    }

    @GetMapping
    public PagedConnectionsDto<AdminConnectionDto> list(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return adminCreatorConnectionService.list(principal, status, search, page, pageSize);
    }

    @GetMapping("/{id}")
    public AdminConnectionDto getById(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable String id) {
        return adminCreatorConnectionService.getById(principal, id);
    }

    @PostMapping("/{id}/contacted")
    public AdminConnectionDto markContacted(
            @AuthenticationPrincipal AuthPrincipal principal,
            HttpServletRequest request,
            @PathVariable String id,
            @RequestBody(required = false) NotesRequest body) {
        return adminCreatorConnectionService.markContacted(
                principal, request, id, body != null ? body.notes() : null);
    }

    @PostMapping("/{id}/decline")
    public AdminConnectionDto decline(
            @AuthenticationPrincipal AuthPrincipal principal,
            HttpServletRequest request,
            @PathVariable String id,
            @RequestBody(required = false) NotesRequest body) {
        return adminCreatorConnectionService.decline(
                principal, request, id, body != null ? body.notes() : null);
    }

    @PostMapping("/{id}/invite")
    public AdminConnectionDto invite(
            @AuthenticationPrincipal AuthPrincipal principal,
            HttpServletRequest request,
            @PathVariable String id,
            @Valid @RequestBody InviteRequest body) {
        return adminCreatorConnectionService.invite(principal, request, id, body.email(), body.notes());
    }
}
