package com.influora.web;

import com.influora.common.ApiResponse;
import com.influora.security.AuthPrincipal;
import com.influora.service.BrandPlatformFeeService;
import com.influora.web.dto.brandplatformfee.BrandPlatformFeeDtos.PlatformFeeResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Task B2 (wiki/tech/BRAND_EXECUTION_PLAN.md) — {@code GET /brand/platform-fee}. Read-only global
 * config; authenticated brand workspace member (OWNER/ADMIN/MANAGER) identity resolved via {@link
 * com.influora.service.BrandContextService} inside {@link BrandPlatformFeeService}. Mirrors {@link
 * CreatorPlatformFeeController}'s shape/style.
 */
@RestController
@RequestMapping("/brand/platform-fee")
public class BrandPlatformFeeController {

    private final BrandPlatformFeeService brandPlatformFeeService;

    public BrandPlatformFeeController(BrandPlatformFeeService brandPlatformFeeService) {
        this.brandPlatformFeeService = brandPlatformFeeService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PlatformFeeResponse>> getCurrentFee(
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(brandPlatformFeeService.getCurrentFee(principal)));
    }
}
