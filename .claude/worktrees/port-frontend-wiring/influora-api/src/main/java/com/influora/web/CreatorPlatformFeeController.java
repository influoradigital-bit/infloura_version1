package com.influora.web;

import com.influora.common.ApiResponse;
import com.influora.security.AuthPrincipal;
import com.influora.service.CreatorPlatformFeeService;
import com.influora.web.dto.creatorplatformfee.CreatorPlatformFeeDtos.PlatformFeeResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Task #27 (P0-V2, Creator Week 4) — {@code GET /creator/platform-fee}. Read-only global config;
 * authenticated creator identity via {@link com.influora.service.CreatorContextService} inside
 * {@link CreatorPlatformFeeService}.
 */
@RestController
@RequestMapping("/creator/platform-fee")
public class CreatorPlatformFeeController {

    private final CreatorPlatformFeeService creatorPlatformFeeService;

    public CreatorPlatformFeeController(CreatorPlatformFeeService creatorPlatformFeeService) {
        this.creatorPlatformFeeService = creatorPlatformFeeService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PlatformFeeResponse>> getCurrentFee(
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ResponseEntity.ok(
                ApiResponse.ok(creatorPlatformFeeService.getCurrentFee(principal)));
    }
}
