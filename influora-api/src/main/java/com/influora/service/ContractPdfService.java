package com.influora.service;

import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.Contract;
import com.influora.domain.entity.PaymentMilestone;
import com.influora.domain.entity.User;
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
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Renders a signed {@link Contract} to a PDF byte array (parties, milestone terms, amounts,
 * signature timestamps) using pure OpenPDF — no HTML/CSS template layer (Flying Saucer was
 * optional per the approved-deps.md entry and is intentionally omitted here to keep the
 * dependency surface minimal).
 *
 * <p>This service only builds bytes; storing them to R2 and updating {@code Contract.pdfR2Key}
 * is the caller's job ({@code ContractService}), keeping this class a pure, easily-testable
 * renderer.
 */
@Service
public class ContractPdfService {

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    private static final Font TITLE_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18);
    private static final Font HEADING_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 13);
    private static final Font LABEL_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10);
    private static final Font BODY_FONT = FontFactory.getFont(FontFactory.HELVETICA, 10);

    /**
     * Renders the contract PDF. {@code brandName}/{@code creatorName} are resolved by the caller
     * (workspace owner + collaboration creator) since this service has no repository access.
     */
    public byte[] render(
            Contract contract,
            Campaign campaign,
            List<PaymentMilestone> milestones,
            String brandName,
            String creatorName) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Document document = new Document(PageSize.A4, 50, 50, 50, 50);
            PdfWriter.getInstance(document, out);
            document.open();

            addHeader(document, contract, campaign);
            addParties(document, brandName, creatorName);
            addTerms(document, contract);
            addMilestones(document, milestones);
            addSignatures(document, contract, brandName, creatorName);

            document.close();
            return out.toByteArray();
        } catch (DocumentException e) {
            throw new IllegalStateException("Failed to render contract PDF", e);
        }
    }

    private void addHeader(Document document, Contract contract, Campaign campaign)
            throws DocumentException {
        Paragraph title = new Paragraph("Influora Collaboration Contract", TITLE_FONT);
        title.setAlignment(Element.ALIGN_CENTER);
        title.setSpacingAfter(4f);
        document.add(title);

        String campaignTitle = campaign != null ? campaign.getTitle() : "N/A";
        Paragraph subtitle =
                new Paragraph(
                        "Campaign: " + campaignTitle + "   |   Contract ID: " + contract.getId(),
                        BODY_FONT);
        subtitle.setAlignment(Element.ALIGN_CENTER);
        subtitle.setSpacingAfter(20f);
        document.add(subtitle);
    }

    private void addParties(Document document, String brandName, String creatorName)
            throws DocumentException {
        document.add(sectionHeading("Parties"));
        document.add(labeledLine("Brand:", nullSafe(brandName)));
        document.add(labeledLine("Creator:", nullSafe(creatorName)));
        document.add(Chunk.NEWLINE);
    }

    private void addTerms(Document document, Contract contract) throws DocumentException {
        document.add(sectionHeading("Terms"));
        document.add(
                labeledLine(
                        "Total amount:", contract.getTotalAmount() + " " + contract.getCurrency()));
        document.add(labeledLine("Status:", String.valueOf(contract.getStatus())));
        document.add(
                labeledLine(
                        "Effective date:",
                        contract.getEffectiveDate() != null
                                ? contract.getEffectiveDate().toString()
                                : "Not set"));
        document.add(
                labeledLine(
                        "Expiration date:",
                        contract.getExpirationDate() != null
                                ? contract.getExpirationDate().toString()
                                : "Not set"));
        document.add(Chunk.NEWLINE);
    }

    private void addMilestones(Document document, List<PaymentMilestone> milestones)
            throws DocumentException {
        document.add(sectionHeading("Payment Milestones"));

        if (milestones == null || milestones.isEmpty()) {
            document.add(new Paragraph("No milestones on this contract.", BODY_FONT));
            document.add(Chunk.NEWLINE);
            return;
        }

        PdfPTable table = new PdfPTable(5);
        table.setWidthPercentage(100);
        table.setSpacingBefore(6f);
        table.setSpacingAfter(12f);
        table.setWidths(new float[] {0.6f, 3f, 1.5f, 1.5f, 1.5f});

        for (String col : new String[] {"#", "Description", "Amount", "Due date", "Status"}) {
            PdfPCell header = new PdfPCell(new Phrase(col, LABEL_FONT));
            header.setPadding(6f);
            table.addCell(header);
        }

        for (PaymentMilestone m : milestones) {
            table.addCell(cell(String.valueOf(m.getSequenceNo())));
            table.addCell(cell(nullSafe(m.getDescription())));
            table.addCell(cell(m.getAmount() + " " + m.getCurrency()));
            table.addCell(cell(m.getDueDate() != null ? m.getDueDate().toString() : "-"));
            table.addCell(cell(String.valueOf(m.getStatus())));
        }

        document.add(table);
    }

    private void addSignatures(
            Document document, Contract contract, String brandName, String creatorName)
            throws DocumentException {
        document.add(sectionHeading("Signatures"));
        document.add(
                labeledLine(
                        "Brand (" + nullSafe(brandName) + "):",
                        contract.getBrandSignedAt() != null
                                ? TIMESTAMP_FORMAT.format(contract.getBrandSignedAt())
                                : "Not yet signed"));
        document.add(
                labeledLine(
                        "Creator (" + nullSafe(creatorName) + "):",
                        contract.getCreatorSignedAt() != null
                                ? TIMESTAMP_FORMAT.format(contract.getCreatorSignedAt())
                                : "Not yet signed"));
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

    /** Convenience overload used where the caller only has a {@link User} display name handy. */
    public static String displayNameOf(User user) {
        if (user == null) {
            return "N/A";
        }
        return user.getDisplayName() != null ? user.getDisplayName() : user.getEmail();
    }
}
