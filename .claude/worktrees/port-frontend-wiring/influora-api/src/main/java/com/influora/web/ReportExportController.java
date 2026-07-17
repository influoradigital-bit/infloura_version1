package com.influora.web;

import com.influora.domain.enums.PlanFeature;
import com.influora.security.AuthPrincipal;
import com.influora.security.RequiresPlan;
import com.influora.service.ReportExportService;
import com.influora.service.ReportExportService.ExportedFile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A4 — Report Export (E4). Campaign performance export as CSV or PDF. Pro-gated via {@link
 * RequiresPlan} (feature {@link PlanFeature#EXPORT}), enforced live by {@code
 * PlanGateInterceptor} (already wired — see {@code PlanGateFilter}).
 *
 * <p>Delivered directly as bytes for now (skip R2 presigned-URL delivery to save time).
 * TODO: switch to R2StorageService presigned URL for large exports.
 */
@RestController
@RequestMapping("/campaigns")
public class ReportExportController {

    private final ReportExportService reportExportService;

    public ReportExportController(ReportExportService reportExportService) {
        this.reportExportService = reportExportService;
    }

    @RequiresPlan(feature = PlanFeature.EXPORT)
    @GetMapping("/{campaignId}/export")
    public ResponseEntity<byte[]> export(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String campaignId,
            @RequestParam String format) {
        ExportedFile file = reportExportService.exportCampaignPerformance(principal, campaignId, format);
        // Delivered as application/octet-stream per the build brief (skip R2 presigned URL for
        // now) — file.contentType() is still tracked on ExportedFile for when this switches to a
        // presigned-URL response with a real Content-Type on the stored object.
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + file.filename() + "\"")
                .body(file.bytes());
    }
}
