package com.influora.web;

import com.influora.security.AuthPrincipal;
import com.influora.service.admin.AdminCreatorService;
import com.influora.web.dto.admin.AdminCreatorDtos.AdjustTierRequest;
import com.influora.web.dto.admin.AdminCreatorDtos.CreatorDetailDto;
import com.influora.web.dto.admin.AdminCreatorDtos.PagedCreatorsDto;
import com.influora.web.dto.admin.AdminCreatorDtos.ReinstateRequest;
import com.influora.web.dto.admin.AdminCreatorDtos.ReviewApplicationRequest;
import com.influora.web.dto.admin.AdminCreatorDtos.SuspendRequest;
import com.influora.web.dto.admin.AdminCreatorDtos.UpdateCreatorRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.ResponseEntity;
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
 * Creator CRUD/moderation API (src/admin/TASK_ASSIGNMENTS.md P1: "Creator CRUD API" — Vikram,
 * cycle 5). Mounted at {@code /admin/creators} (full path {@code /api/v1/admin/creators/...} —
 * same {@code server.servlet.context-path=/api/v1} convention as every other {@code
 * Admin*Controller}, see {@code AdminBrandController}'s class javadoc for the path-mismatch note
 * that still applies here unchanged).
 *
 * <p>Returns raw DTOs (unwrapped, no {@link com.influora.common.ApiResponse} envelope) — same
 * deliberate deviation as every other {@code Admin*Controller}, to match {@code creatorApi}'s
 * {@code apiRequest()} client contract in {@code src/admin/services/api-contracts.ts}.
 *
 * <p>Swap-in target for {@code useCreatorDetail.ts}'s commented-out react-query block and {@code
 * CreatorProfile.tsx}'s stubbed {@code handleApproveApplication}/{@code
 * handleRejectApplication}/{@code handleForceInstagramReauth}/{@code handleSuspend}/{@code
 * handleReinstate} handlers (Ananya, cycle 5) — those TODOs name this controller by name.
 *
 * <p>Endpoints: {@code GET /creators} (list with pagination/filters), {@code GET /creators/{id}}
 * (detail read), {@code POST /creators/{id}/review-application} (approve/reject application),
 * {@code POST /creators/{id}/instagram/force-reauth} (revoke Instagram OAuth), {@code POST
 * /creators/{id}/suspend} (suspend account), {@code POST /creators/{id}/reinstate} (reinstate
 * suspended account). See {@code AdminCreatorService}'s class javadoc for the full scope note.
 */
@RestController
@RequestMapping("/admin/creators")
public class AdminCreatorController {

    private final AdminCreatorService adminCreatorService;

    public AdminCreatorController(AdminCreatorService adminCreatorService) {
        this.adminCreatorService = adminCreatorService;
    }

    @GetMapping
    public PagedCreatorsDto list(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam(required = false) String applicationStatus,
            @RequestParam(required = false) Boolean suspended,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return adminCreatorService.list(principal, applicationStatus, suspended, search, page, pageSize);
    }

    /**
     * GET /admin/creators/applications/pending ({@code creatorApi.getPendingApplications}).
     * Paginated list of creators whose application status is PENDING. Declared BEFORE {@code
     * /{id}} in source order for clarity; the two never collide anyway — this is a 2-segment path
     * ({@code applications/pending}) and {@code /{id}} matches only a single segment.
     */
    @GetMapping("/applications/pending")
    public PagedCreatorsDto getPendingApplications(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return adminCreatorService.listPendingApplications(principal, page, pageSize);
    }

    @GetMapping("/{id}")
    public CreatorDetailDto getById(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable String id) {
        return adminCreatorService.getById(principal, id);
    }

    /**
     * PUT /admin/creators/{id} ({@code creatorApi.update}). Edits SAFE profile fields only
     * (name/niche) — the request DTO shape is the allow-list. applicationStatus/isSuspended/tier/
     * money fields are not bindable here and have dedicated endpoints. Returns the full {@code
     * CreatorDetailDto} (CreatorDetail extends Creator, satisfying {@code apiRequest<Creator>}).
     */
    @PutMapping("/{id}")
    public CreatorDetailDto update(
            @AuthenticationPrincipal AuthPrincipal principal,
            HttpServletRequest request,
            @PathVariable String id,
            @Valid @RequestBody UpdateCreatorRequest body) {
        return adminCreatorService.update(principal, request, id, body);
    }

    /**
     * PUT /admin/creators/{id}/tier ({@code creatorApi.adjustTier}). Sets the admin tier override
     * (validated against the CreatorTier enum) and audit-logs old->new tier + reason.
     */
    @PutMapping("/{id}/tier")
    public CreatorDetailDto adjustTier(
            @AuthenticationPrincipal AuthPrincipal principal,
            HttpServletRequest request,
            @PathVariable String id,
            @Valid @RequestBody AdjustTierRequest body) {
        return adminCreatorService.adjustTier(principal, request, id, body);
    }

    @PostMapping("/{id}/review-application")
    public CreatorDetailDto reviewApplication(
            @AuthenticationPrincipal AuthPrincipal principal,
            HttpServletRequest request,
            @PathVariable String id,
            @Valid @RequestBody ReviewApplicationRequest body) {
        return adminCreatorService.reviewApplication(principal, request, id, body.action(), body.reason());
    }

    /**
     * Returns a small JSON body (not empty/204) even though {@code creatorApi.forceInstagramReauth}
     * types this {@code void} on the frontend — {@code apiRequest()} (api-contracts.ts) always calls
     * {@code response.json()} unconditionally on a successful response, which throws on a genuinely
     * empty body. Matches {@code AdminAuthController#logout}'s precedent (also client-typed {@code
     * void} but returns a real JSON body) rather than {@code #verifyMfa}'s {@code
     * ResponseEntity.ok().build()} (empty body — a pre-existing latent gap on that endpoint, not
     * touched here, out of this controller's scope).
     */
    @PostMapping("/{id}/instagram/force-reauth")
    public ResponseEntity<Map<String, Object>> forceInstagramReauth(
            @AuthenticationPrincipal AuthPrincipal principal,
            HttpServletRequest request,
            @PathVariable String id) {
        adminCreatorService.forceInstagramReauth(principal, request, id);
        return ResponseEntity.ok(Map.of("success", true));
    }

    @PostMapping("/{id}/suspend")
    public CreatorDetailDto suspend(
            @AuthenticationPrincipal AuthPrincipal principal,
            HttpServletRequest request,
            @PathVariable String id,
            @Valid @RequestBody SuspendRequest body) {
        return adminCreatorService.suspend(principal, request, id, body.reason());
    }

    @PostMapping("/{id}/reinstate")
    public CreatorDetailDto reinstate(
            @AuthenticationPrincipal AuthPrincipal principal,
            HttpServletRequest request,
            @PathVariable String id,
            @Valid @RequestBody ReinstateRequest body) {
        return adminCreatorService.reinstate(principal, request, id, body.reason());
    }
}
