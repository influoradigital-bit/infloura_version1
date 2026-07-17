package com.influora.service;

import com.influora.common.ApiException;
import com.influora.common.CsvWriter;
import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.Workspace;
import com.influora.repository.CampaignRepository;
import com.influora.security.AuthPrincipal;
import com.influora.web.dto.analytics.AnalyticsDtos.CampaignAnalyticsResponse;
import com.influora.web.dto.analytics.AnalyticsDtos.DeliverableMetricResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A4 — Report Export (E2/E4). Reads from {@link DeliverableMetricService#getCampaignAnalytics}
 * only — no duplicated query logic — and renders either a CSV (RFC-4180, {@link CsvWriter}) or a
 * PDF ({@link ReportPdfService}) of the exact same numbers the on-screen analytics view shows.
 *
 * <p>Delivered directly as {@code application/octet-stream} bytes for now.
 * TODO: switch to R2StorageService presigned URL for large exports.
 */
@Service
public class ReportExportService {

    private final DeliverableMetricService deliverableMetricService;
    private final CampaignRepository campaignRepository;
    private final BrandContextService brandContext;
    private final ReportPdfService reportPdfService;

    public ReportExportService(
            DeliverableMetricService deliverableMetricService,
            CampaignRepository campaignRepository,
            BrandContextService brandContext,
            ReportPdfService reportPdfService) {
        this.deliverableMetricService = deliverableMetricService;
        this.campaignRepository = campaignRepository;
        this.brandContext = brandContext;
        this.reportPdfService = reportPdfService;
    }

    public record ExportedFile(byte[] bytes, String contentType, String filename) {}

    @Transactional(readOnly = true)
    public ExportedFile exportCampaignPerformance(
            AuthPrincipal principal, String campaignId, String format) {
        Workspace workspace = brandContext.requireBrandWorkspace(principal);
        brandContext.requireMember(principal, workspace.getId());

        Campaign campaign =
                campaignRepository
                        .findByIdAndWorkspaceId(campaignId, workspace.getId())
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "CAMPAIGN_NOT_FOUND", "Campaign not found", HttpStatus.NOT_FOUND));

        CampaignAnalyticsResponse analytics =
                deliverableMetricService.getCampaignAnalytics(principal, workspace.getId(), campaignId);

        String normalizedFormat = format == null ? "" : format.trim().toLowerCase(Locale.ROOT);
        return switch (normalizedFormat) {
            case "csv" -> renderCsv(campaign, analytics);
            case "pdf" -> renderPdf(campaign, analytics);
            default ->
                    throw new ApiException(
                            "INVALID_FORMAT", "format must be 'csv' or 'pdf'", HttpStatus.BAD_REQUEST);
        };
    }

    private ExportedFile renderCsv(Campaign campaign, CampaignAnalyticsResponse analytics) {
        List<String> headers =
                List.of(
                        "campaign_id",
                        "deliverable_id",
                        "reach",
                        "impressions",
                        "engagements",
                        "link",
                        "reported_by_creator_id",
                        "reported_at",
                        "source");

        List<List<String>> rows = new ArrayList<>();
        for (DeliverableMetricResponse m : analytics.deliverables()) {
            rows.add(
                    List.of(
                            analytics.campaignId(),
                            nullToEmpty(m.id()),
                            nullToEmpty(m.reach()),
                            nullToEmpty(m.impressions()),
                            nullToEmpty(m.engagements()),
                            nullToEmpty(m.link()),
                            nullToEmpty(m.reportedByCreatorId()),
                            nullToEmpty(m.reportedAt()),
                            nullToEmpty(m.source())));
        }
        // Summary row at the top of the deliverable list, same numbers as the on-screen totals.
        rows.add(0,
                List.of(
                        analytics.campaignId(),
                        "SUMMARY",
                        String.valueOf(analytics.totalReach()),
                        String.valueOf(analytics.totalImpressions()),
                        String.valueOf(analytics.totalEngagements()),
                        "",
                        "",
                        "",
                        analytics.source()));

        String csv = CsvWriter.write(headers, rows);
        byte[] bytes = csv.getBytes(StandardCharsets.UTF_8);
        String filename = "campaign-" + safeFilenamePart(campaign) + "-report.csv";
        return new ExportedFile(bytes, "text/csv", filename);
    }

    private ExportedFile renderPdf(Campaign campaign, CampaignAnalyticsResponse analytics) {
        byte[] bytes = reportPdfService.render(campaign, analytics);
        String filename = "campaign-" + safeFilenamePart(campaign) + "-report.pdf";
        return new ExportedFile(bytes, "application/pdf", filename);
    }

    /** No PII in filenames — campaign id only, never title/brand/creator names. */
    private static String safeFilenamePart(Campaign campaign) {
        return campaign.getId();
    }

    private static String nullToEmpty(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
