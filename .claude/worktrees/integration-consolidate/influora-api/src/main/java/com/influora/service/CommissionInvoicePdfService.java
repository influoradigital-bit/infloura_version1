package com.influora.service;

import com.influora.config.CompanyTaxProperties;
import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.PlatformCommissionInvoice;
import com.influora.domain.entity.Workspace;
import com.influora.domain.enums.CommissionInvoiceLeg;
import com.influora.service.GstSplitUtil.GstSplit;
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
 * Renders Doc#3 (platform commission invoice, Influora -> Brand or Influora -> Creator) to a PDF
 * byte array — pure OpenPDF, mirrors {@link InvoicePdfService}'s pattern. Influora is always the
 * supplier of record here (its own output supply, standard 18% GST — D14 spec §3).
 */
@Service
public class CommissionInvoicePdfService {

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    private static final Font TITLE_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18);
    private static final Font HEADING_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 13);
    private static final Font LABEL_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10);
    private static final Font BODY_FONT = FontFactory.getFont(FontFactory.HELVETICA, 10);

    private final CompanyTaxProperties companyTaxProperties;

    public CommissionInvoicePdfService(CompanyTaxProperties companyTaxProperties) {
        this.companyTaxProperties = companyTaxProperties;
    }

    /**
     * @param brandWorkspace non-null for {@link CommissionInvoiceLeg#BRAND}, null for {@code CREATOR}
     * @param creator non-null for {@link CommissionInvoiceLeg#CREATOR}, null for {@code BRAND}
     */
    public byte[] render(
            PlatformCommissionInvoice invoice, Campaign campaign, Workspace brandWorkspace, CreatorProfile creator) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Document document = new Document(PageSize.A4, 50, 50, 50, 50);
            PdfWriter.getInstance(document, out);
            document.open();

            addHeader(document, invoice);
            addSupplierDetails(document);
            addCustomerDetails(document, invoice, brandWorkspace, creator);
            addInvoiceDetails(document, invoice, campaign, brandWorkspace, creator);
            addFooter(document);

            document.close();
            return out.toByteArray();
        } catch (DocumentException e) {
            throw new IllegalStateException("Failed to render commission invoice PDF", e);
        }
    }

    private void addHeader(Document document, PlatformCommissionInvoice invoice) throws DocumentException {
        Paragraph title = new Paragraph("Platform Commission Invoice", TITLE_FONT);
        title.setAlignment(Element.ALIGN_CENTER);
        title.setSpacingAfter(4f);
        document.add(title);

        Paragraph subtitle = new Paragraph("Invoice No: " + invoice.getInvoiceNumber(), BODY_FONT);
        subtitle.setAlignment(Element.ALIGN_CENTER);
        subtitle.setSpacingAfter(20f);
        document.add(subtitle);
    }

    private void addSupplierDetails(Document document) throws DocumentException {
        document.add(sectionHeading("Supplier"));
        document.add(labeledLine("Name:", nullSafe(companyTaxProperties.getLegalName())));
        document.add(labeledLine("GSTIN:", nullSafe(companyTaxProperties.getGstin())));
        document.add(labeledLine("Registered address:", nullSafe(companyTaxProperties.getRegisteredAddress())));
        document.add(Chunk.NEWLINE);
    }

    private void addCustomerDetails(
            Document document, PlatformCommissionInvoice invoice, Workspace brandWorkspace, CreatorProfile creator)
            throws DocumentException {
        document.add(sectionHeading("Billed To"));
        if (invoice.getLeg() == CommissionInvoiceLeg.BRAND) {
            document.add(labeledLine("Workspace:", nullSafe(brandWorkspace != null ? brandWorkspace.getName() : null)));
            document.add(
                    labeledLine("GSTIN:", nullSafe(brandWorkspace != null ? brandWorkspace.getGstin() : null)));
            document.add(
                    labeledLine(
                            "Billing address:",
                            nullSafe(brandWorkspace != null ? brandWorkspace.getBillingAddress() : null)));
        } else {
            document.add(labeledLine("Creator:", nullSafe(creator != null ? creator.getDisplayName() : null)));
            document.add(labeledLine("GSTIN:", nullSafe(creator != null ? creator.getGstin() : null)));
        }
        document.add(Chunk.NEWLINE);
    }

    private void addInvoiceDetails(
            Document document,
            PlatformCommissionInvoice invoice,
            Campaign campaign,
            Workspace brandWorkspace,
            CreatorProfile creator)
            throws DocumentException {
        document.add(sectionHeading("Invoice Details"));
        document.add(labeledLine("Campaign:", nullSafe(campaign != null ? campaign.getTitle() : null)));
        document.add(labeledLine("HSN/SAC:", nullSafe(invoice.getHsnSacCode())));
        document.add(
                labeledLine(
                        "Platform commission (" + invoice.getFeeBpsApplied() + " bps):",
                        formatAmount(invoice.getCommissionAmount())));

        String customerGstin =
                invoice.getLeg() == CommissionInvoiceLeg.BRAND
                        ? (brandWorkspace != null ? brandWorkspace.getGstin() : null)
                        : (creator != null ? creator.getGstin() : null);
        GstSplit split =
                GstSplitUtil.compute(companyTaxProperties.getGstin(), customerGstin, invoice.getGstAmount());
        if (split.igst().signum() > 0) {
            document.add(labeledLine("IGST @ 18%:", formatAmount(split.igst())));
        } else {
            document.add(labeledLine("CGST @ 9%:", formatAmount(split.cgst())));
            document.add(labeledLine("SGST @ 9%:", formatAmount(split.sgst())));
        }

        BigDecimal total = invoice.getCommissionAmount().add(invoice.getGstAmount());
        document.add(labeledLine("Total:", formatAmount(total)));
        document.add(labeledLine("Status:", String.valueOf(invoice.getStatus())));
        if (invoice.getIssuedAt() != null) {
            document.add(labeledLine("Issued:", TIMESTAMP_FORMAT.format(invoice.getIssuedAt())));
        }
        document.add(Chunk.NEWLINE);
    }

    private void addFooter(Document document) throws DocumentException {
        Paragraph footer =
                new Paragraph(
                        "Thank you for using Influora. For questions, contact support@influora.com",
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
        return s != null && !s.isBlank() ? s : "N/A";
    }

    private static String formatAmount(BigDecimal rupees) {
        if (rupees == null) {
            return "N/A";
        }
        return String.format("₹%,.2f", rupees);
    }
}
