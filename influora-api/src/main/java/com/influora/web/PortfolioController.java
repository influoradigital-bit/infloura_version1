package com.influora.web;

import com.influora.common.ApiResponse;
import com.influora.security.AuthPrincipal;
import com.influora.service.portfolio.PortfolioService;
import com.influora.web.dto.portfolio.PortfolioDtos.CoverUploadResponse;
import com.influora.web.dto.portfolio.PortfolioDtos.PortfolioAnalyticsResponse;
import com.influora.web.dto.portfolio.PortfolioDtos.PortfolioContactRequest;
import com.influora.web.dto.portfolio.PortfolioDtos.PortfolioContactResponse;
import com.influora.web.dto.portfolio.PortfolioDtos.PortfolioPageResponse;
import com.influora.web.dto.portfolio.PortfolioDtos.PortfolioPatchRequest;
import com.influora.web.dto.portfolio.PortfolioDtos.SyncPlatformsResponse;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class PortfolioController {

    private static final Logger log = LoggerFactory.getLogger(PortfolioController.class);

    private final PortfolioService portfolioService;

    public PortfolioController(PortfolioService portfolioService) {
        this.portfolioService = portfolioService;
    }

    @GetMapping("/portfolio/{username}")
    public ResponseEntity<ApiResponse<PortfolioPageResponse>> getPublic(@PathVariable String username) {
        PortfolioPageResponse page = portfolioService.getPublic(username);
        // Record the view only after the page assembled successfully (so a 404 for an unknown/
        // hidden handle is never counted). Isolated in its own transaction inside the service and
        // caught here: a view-counter write must NEVER turn a good page into a 500. See
        // PortfolioService#recordPublicView.
        try {
            portfolioService.recordPublicView(username);
        } catch (RuntimeException ex) {
            log.warn("Failed to record portfolio view for username={}", username, ex);
        }
        return ResponseEntity.ok(ApiResponse.ok(page));
    }

    @PostMapping("/portfolio/{username}/contact")
    public ResponseEntity<ApiResponse<PortfolioContactResponse>> contact(
            @PathVariable String username, @Valid @RequestBody PortfolioContactRequest body) {
        return ResponseEntity.ok(
                ApiResponse.ok(
                        portfolioService.contact(username, body.name(), body.email(), body.message())));
    }

    @GetMapping("/me/portfolio")
    public ResponseEntity<ApiResponse<PortfolioPageResponse>> getMine(
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(portfolioService.getMine(principal)));
    }

    @PatchMapping("/me/portfolio")
    public ResponseEntity<ApiResponse<PortfolioPageResponse>> updateMine(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody PortfolioPatchRequest body) {
        return ResponseEntity.ok(ApiResponse.ok(portfolioService.updateMine(principal, body)));
    }

    @PostMapping("/me/portfolio/sync")
    public ResponseEntity<ApiResponse<SyncPlatformsResponse>> syncPlatforms(
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(portfolioService.syncPlatforms(principal)));
    }

    @PostMapping("/me/portfolio/cover")
    public ResponseEntity<ApiResponse<CoverUploadResponse>> uploadCover(
            @AuthenticationPrincipal AuthPrincipal principal, @RequestPart("file") MultipartFile file) {
        String url = portfolioService.uploadCover(principal, file);
        return ResponseEntity.ok(ApiResponse.ok(new CoverUploadResponse(url)));
    }

    @GetMapping("/me/portfolio/analytics")
    public ResponseEntity<ApiResponse<PortfolioAnalyticsResponse>> analytics(
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(portfolioService.analytics(principal)));
    }
}
