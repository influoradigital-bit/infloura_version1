package com.influora.service;

import com.influora.domain.entity.Campaign;
import com.influora.web.dto.analytics.AnalyticsDtos.CampaignAnalyticsResponse;
import com.influora.web.dto.analytics.AnalyticsDtos.DeliverableMetricResponse;
import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Service;

/**
 * A4 — Report Export (E3). Renders a branded campaign-performance PDF from the same {@link
 * CampaignAnalyticsResponse} the on-screen analytics view reads — one data source, two renderers,
 * per the workflow doc. Follows {@code ContractPdfService}/{@code InvoicePdfService}'s exact pure
 * OpenPDF pattern (helpers duplicated rather than extracted, matching the existing pattern-copy
 * convention in this codebase).
 */
@Service
public class ReportPdfService {

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    private static final Font TITLE_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18);
    private static final Font HEADING_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 13);
    private static final Font LABEL_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10);
    private static final Font BODY_FONT = FontFactory.getFont(FontFactory.HELVETICA, 10);

    public byte[] render(Campaign campaign, CampaignAnalyticsResponse analytics) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Document document = new Document(PageSize.A4, 50, 50, 50, 50);
            PdfWriter.getInstance(document, out);
            document.open();

            addHeader(document, campaign);
            addSummary(document, analytics);
            addDeliverables(document, analytics);
            addFooter(document);

            document.close();
            return out.toByteArray();
        } catch (DocumentException e) {
            throw new IllegalStateException("Failed to render report PDF", e);
        }
    }

    private void addHeader(Document document, Campaign campaign) throws DocumentException {
        Paragraph title = new Paragraph("Influora Campaign Performance Report", TITLE_FONT);
        title.setAlignment(Element.ALIGN_CENTER);
        title.setSpacingAfter(4f);
        document.add(title);

        String campaignTitle = campaign != null ? campaign.getTitle() : "N/A";
        Paragraph subtitle =
                new Paragraph(
                        "Campaign: " + campaignTitle + "   |   Generated: " + TIMESTAMP_FORMAT.format(Instant.now()),
                        BODY_FONT);
        subtitle.setAlignment(Element.ALIGN_CENTER);
        subtitle.setSpacingAfter(20f);
        document.add(subtitle);
    }

    private void addSummary(Document document, CampaignAnalyticsResponse analytics)
            throws DocumentException {
        document.add(sectionHeading("Performance Summary"));
        document.add(labeledLine("Total reach:", String.valueOf(analytics.totalReach())));
        document.add(labeledLine("Total impressions:", String.valueOf(analytics.totalImpressions())));
        document.add(labeledLine("Total engagements:", String.valueOf(analytics.totalEngagements())));
        document.add(
                labeledLine(
                        "Engagement rate:",
                        analytics.derivedEngagementRate() != null
                                ? analytics.derivedEngagementRate() + "%"
                                : "Not available"));
        document.add(
                labeledLine(
                        "Deliverables reported:",
                        analytics.deliverablesReported() + " of " + analytics.deliverablesTotal()));
        document.add(labeledLine("Data source:", analytics.source()));
        document.add(Chunk.NEWLINE);
    }

    private void addDeliverables(Document document, CampaignAnalyticsResponse analytics)
            throws DocumentException {
        document.add(sectionHeading("Deliverable Breakdown"));

        if (analytics.deliverables() == null || analytics.deliverables().isEmpty()) {
            document.add(new Paragraph("No deliverable performance reported yet.", BODY_FONT));
            document.add(Chunk.NEWLINE);
            return;
        }

        PdfPTable table = new PdfPTable(5);
        table.setWidthPercentage(100);
        table.setSpacingBefore(6f);
        table.setSpacingAfter(12f);
        table.setWidths(new float[] {2.5f, 1.3f, 1.3f, 1.3f, 2f});

        for (String col : new String[] {"Deliverable", "Reach", "Impressions", "Engagements", "Reported at"}) {
            PdfPCell header = new PdfPCell(new Phrase(col, LABEL_FONT));
            header.setPadding(6f);
            table.addCell(header);
        }

        for (DeliverableMetricResponse m : analytics.deliverables()) {
            table.addCell(cell(nullSafe(m.milestoneId())));
            table.addCell(cell(m.reach() != null ? String.valueOf(m.reach()) : "-"));
            table.addCell(cell(m.impressions() != null ? String.valueOf(m.impressions()) : "-"));
            table.addCell(cell(m.engagements() != null ? String.valueOf(m.engagements()) : "-"));
            table.addCell(cell(m.reportedAt() != null ? TIMESTAMP_FORMAT.format(m.reportedAt()) : "-"));
        }

        document.add(table);
    }

    private void addFooter(Document document) throws DocumentException {
        Paragraph footer =
                new Paragraph(
                        "Creator-reported data — not platform-verified. Generated by Influora.",
                        BODY_FONT);
        footer.setAlignment(Element.ALIGN_CENTER);
        footer.setSpacingBefore(30f);
        document.add(footer);
    }

    private static PdfPCell cell(String text) {
        PdfPCell cell = new PdfPCell(new Phrase(text, BODY_FONT));
        cell.setPadding(6f);
        return cell;
    }

    private static Paragraph sectionHeading(String text) {
        Paragraph p = new Paragraph(text, HEADING_FONT);
        p.setSpacingBefore(8f);
        p.setSpacingAfter(6f);
        return p;
    }

    private static Paragraph labeledLine(String label, String value) {
        Paragraph p = new Paragraph();
        p.add(new Chunk(label + " ", LABEL_FONT));
        p.add(new Chunk(value, BODY_FONT));
        p.setSpacingAfter(2f);
        return p;
    }

    private static String nullSafe(String s) {
        return s != null ? s : "N/A";
    }
}
