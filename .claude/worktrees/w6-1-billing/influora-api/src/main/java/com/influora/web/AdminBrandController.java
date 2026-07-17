package com.influora.web;

import com.influora.security.AuthPrincipal;
import com.influora.service.admin.AdminBrandService;
import com.influora.web.dto.admin.AdminBrandDtos.BrandDetailDto;
import com.influora.web.dto.admin.AdminBrandDtos.BrandSummaryDto;
import com.influora.web.dto.admin.AdminBrandDtos.PaginatedBrandResponse;
import com.influora.web.dto.admin.AdminBrandDtos.ReinstateRequest;
import com.influora.web.dto.admin.AdminBrandDtos.SuspendRequest;
import com.influora.web.dto.admin.AdminBrandDtos.VerifyKycRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Brand CRUD/moderation API (src/admin/TASK_ASSIGNMENTS.md P1: "Brand CRUD API" — Vikram, cycle
 * 4). Mounted at {@code /admin/brands} (full path {@code /api/v1/admin/brands/...} given {@code
 * server.servlet.context-path=/api/v1} — same convention/caveat as {@code AdminAuthController}/
 * {@code AdminDashboardController}, see their class javadocs for the {@code /api/admin} vs.
 * {@code /api/v1/admin} path-mismatch note that still applies here unchanged).
 *
 * <p>Returns raw DTOs (unwrapped, no {@link com.influora.common.ApiResponse} envelope) — same
 * deliberate deviation as every other {@code Admin*Controller}, to match {@code brandApi}'s
 * {@code apiRequest()} client contract in {@code src/admin/services/api-contracts.ts}, which
 * treats the entire JSON response body as the payload.
 *
 * <p>Swap-in target for {@code useBrandDetail.ts}'s commented-out react-query block and {@code
 * BrandProfile.tsx}'s stubbed {@code handleApproveKyc}/{@code handleRejectKyc}/{@code
 * handleSuspend}/{@code handleReinstate} handlers (Ananya, cycle 2) — those TODOs name this
 * controller by name.
 *
 * <p>Task #2 (cycle 5): Added GET /brands list endpoint for admin Users page, with pagination,
 * search (companyName, email), and filters (kycStatus, suspended). Returns
 * {@code PaginatedBrandResponse} matching {@code brandApi.list} contract shape in api-contracts.ts.
 * {@code .update}/{@code .overrideBudget} remain unimplemented (no frontend caller yet).
 */
@RestController
@RequestMapping("/admin/brands")
public class AdminBrandController {

    private final AdminBrandService adminBrandService;

    public AdminBrandController(AdminBrandService adminBrandService) {
        this.adminBrandService = adminBrandService;
    }

    @GetMapping
    public PaginatedBrandResponse list(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String kycStatus,
            @RequestParam(required = false) Boolean isSuspended) {
        return adminBrandService.list(principal, page, pageSize, search, kycStatus, isSuspended);
    }

    @GetMapping("/{id}")
    public BrandDetailDto getById(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable String id) {
        return adminBrandService.getById(principal, id);
    }

    @PostMapping("/{id}/verify-kyc")
    public BrandDetailDto verifyKyc(
            @AuthenticationPrincipal AuthPrincipal principal,
            HttpServletRequest request,
            @PathVariable String id,
            @Valid @RequestBody VerifyKycRequest body) {
        return adminBrandService.verifyKyc(principal, request, id, body.action(), body.reason());
    }

    @PostMapping("/{id}/suspend")
    public BrandDetailDto suspend(
            @AuthenticationPrincipal AuthPrincipal principal,
            HttpServletRequest request,
            @PathVariable String id,
            @Valid @RequestBody SuspendRequest body) {
        return adminBrandService.suspend(principal, request, id, body.reason());
    }

    @PostMapping("/{id}/reinstate")
    public BrandDetailDto reinstate(
            @AuthenticationPrincipal AuthPrincipal principal,
            HttpServletRequest request,
            @PathVariable String id,
            @Valid @RequestBody ReinstateRequest body) {
        return adminBrandService.reinstate(principal, request, id, body.reason());
    }
}
