package com.influora.service;

import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.CampaignServiceInvoice;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.Workspace;
import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfWriter;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Service;

/**
 * Renders Doc#2 (creator service invoice, Creator -> Brand) to a PDF byte array — pure OpenPDF,
 * mirrors {@link InvoicePdfService}'s pattern exactly (same font/section/helper conventions).
 * Influora is never a party on this document; it only facilitates issuance (D14-A).
 */
@Service
public class CampaignServiceInvoicePdfService {

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    private static final Font TITLE_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18);
    private static final Font HEADING_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 13);
    private static final Font LABEL_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10);
    private static final Font BODY_FONT = FontFactory.getFont(FontFactory.HELVETICA, 10);

    public byte[] render(
            CampaignServiceInvoice invoice, Campaign campaign, Workspace brandWorkspace, CreatorProfile creator) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Document document = new Document(PageSize.A4, 50, 50, 50, 50);
            PdfWriter.getInstance(document, out);
            document.open();

            addHeader(document, invoice);
            addSupplierDetails(document, creator);
            addCustomerDetails(document, brandWorkspace);
            addInvoiceDetails(document, invoice, campaign);
            addFooter(document, creator);

            document.close();
            return out.toByteArray();
        } catch (DocumentException e) {
            throw new IllegalStateException("Failed to render campaign service invoice PDF", e);
        }
    }

    private void addHeader(Document document, CampaignServiceInvoice invoice) throws DocumentException {
        Paragraph title = new Paragraph("Creator Service Invoice", TITLE_FONT);
        title.setAlignment(Element.ALIGN_CENTER);
        title.setSpacingAfter(4f);
        document.add(title);

        Paragraph subtitle = new Paragraph("Invoice No: " + invoice.getInvoiceNumber(), BODY_FONT);
        subtitle.setAlignment(Element.ALIGN_CENTER);
        subtitle.setSpacingAfter(20f);
        document.add(subtitle);
    }

    private void addSupplierDetails(Document document, CreatorProfile creator) throws DocumentException {
        document.add(sectionHeading("Supplier (Service Provider)"));
        document.add(labeledLine("Name:", nullSafe(creator != null ? creator.getDisplayName() : null)));
        document.add(labeledLine("GSTIN:", nullSafe(creator != null ? creator.getGstin() : null)));
        document.add(labeledLine("PAN:", nullSafe(creator != null ? creator.getPan() : null)));
        document.add(Chunk.NEWLINE);
    }

    private void addCustomerDetails(Document document, Workspace brandWorkspace) throws DocumentException {
        document.add(sectionHeading("Billed To"));
        document.add(labeledLine("Workspace:", nullSafe(brandWorkspace != null ? brandWorkspace.getName() : null)));
        document.add(
                labeledLine(
                        "Billing address:",
                        nullSafe(brandWorkspace != null ? brandWorkspace.getBillingAddress() : null)));
        document.add(labeledLine("GSTIN:", nullSafe(brandWorkspace != null ? brandWorkspace.getGstin() : null)));
        document.add(Chunk.NEWLINE);
    }

    private void addInvoiceDetails(Document document, CampaignServiceInvoice invoice, Campaign campaign)
            throws DocumentException {
        document.add(sectionHeading("Invoice Details"));
        document.add(labeledLine("Campaign:", nullSafe(campaign != null ? campaign.getTitle() : null)));
        document.add(labeledLine("HSN/SAC:", nullSafe(invoice.getHsnSacCode())));
        document.add(labeledLine("Service value:", formatAmount(invoice.getGrossAmount())));

        if (invoice.getTcsAmount() != null && invoice.getTcsAmount().signum() > 0) {
            document.add(
                    labeledLine(
                            "TCS @ 1% (ECO deduction, report-only):", formatAmount(invoice.getTcsAmount())));
        }

        document.add(labeledLine("Status:", String.valueOf(invoice.getStatus())));
        if (invoice.getIssuedAt() != null) {
            document.add(labeledLine("Issued:", TIMESTAMP_FORMAT.format(invoice.getIssuedAt())));
        }
        document.add(Chunk.NEWLINE);
    }

    private void addFooter(Document document, CreatorProfile creator) throws DocumentException {
        Paragraph footer =
                new Paragraph(
                        "Issued by "
                                + nullSafe(creator != null ? creator.getDisplayName() : null)
                                + " via Influora marketplace. For questions, contact support@influora.com",
                        BODY_FONT);
        footer.setAlignment(Element.ALIGN_CENTER);
        footer.setSpacingBefore(30f);
        document.add(footer);
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

    private static String formatAmount(BigDecimal rupees) {
        if (rupees == null) {
            return "N/A";
        }
        return String.format("₹%,.2f", rupees);
    }
}
