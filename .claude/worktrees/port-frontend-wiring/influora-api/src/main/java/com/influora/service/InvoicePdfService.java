package com.influora.service;

import com.influora.domain.entity.Invoice;
import com.influora.domain.entity.Plan;
import com.influora.domain.entity.Subscription;
import com.influora.domain.entity.Workspace;
import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Service;
import com.lowagie.text.pdf.PdfWriter;

/**
 * Renders a subscription {@link Invoice} to a PDF byte array (invoice #, dates, period, plan,
 * amount, workspace) using pure OpenPDF — mirrors {@link ContractPdfService}'s pattern.
 *
 * <p>Task 16/14 subscription billing prep batch 1 (subscription-billing-plan.md §1.7, rev. 3).
 * Audit-confirmed reuse: {@code ContractPdfService} exists, uses pure OpenPDF ({@code
 * com.lowagie.text.*}), and is a real, reusable pattern. The helpers ({@code sectionHeading()},
 * {@code labeledLine()}, {@code nullSafe()}) are {@code private static} in that service, so they're
 * duplicated here rather than extracted into a shared util (following the existing pattern-copy
 * approach, not refactoring until a third caller needs them).
 *
 * <p>This service only builds bytes; storing them to R2 and updating {@link Invoice#pdfR2Key} is
 * the caller's job (likely {@code SubscriptionService} or {@code InvoiceService}), keeping this
 * class a pure, easily-testable renderer.
 */
@Service
public class InvoicePdfService {

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd MMM yyyy").withZone(ZoneOffset.UTC);

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    private static final Font TITLE_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18);
    private static final Font HEADING_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 13);
    private static final Font LABEL_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10);
    private static final Font BODY_FONT = FontFactory.getFont(FontFactory.HELVETICA, 10);

    /**
     * Renders the invoice PDF. {@code subscription}/{@code plan}/{@code workspace} are resolved by
     * the caller since this service has no repository access.
     */
    public byte[] render(
            Invoice invoice, Subscription subscription, Plan plan, Workspace workspace) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Document document = new Document(PageSize.A4, 50, 50, 50, 50);
            PdfWriter.getInstance(document, out);
            document.open();

            addHeader(document, invoice);
            addWorkspaceDetails(document, workspace);
            addInvoiceDetails(document, invoice, subscription, plan);
            addFooter(document);

            document.close();
            return out.toByteArray();
        } catch (DocumentException e) {
            throw new IllegalStateException("Failed to render invoice PDF", e);
        }
    }

    private void addHeader(Document document, Invoice invoice) throws DocumentException {
        Paragraph title = new Paragraph("Influora Subscription Invoice", TITLE_FONT);
        title.setAlignment(Element.ALIGN_CENTER);
        title.setSpacingAfter(4f);
        document.add(title);

        // D14 Wave 9 — invoices issued before the statutory-numbering retrofit have no
        // invoice_number; fall back to the internal id rather than rendering "N/A" as the headline.
        String displayNumber = invoice.getInvoiceNumber() != null ? invoice.getInvoiceNumber() : invoice.getId();
        Paragraph subtitle = new Paragraph("Invoice No: " + displayNumber, BODY_FONT);
        subtitle.setAlignment(Element.ALIGN_CENTER);
        subtitle.setSpacingAfter(20f);
        document.add(subtitle);
    }

    private void addWorkspaceDetails(Document document, Workspace workspace)
            throws DocumentException {
        document.add(sectionHeading("Billed To"));
        document.add(labeledLine("Workspace:", nullSafe(workspace.getName())));
        document.add(labeledLine("Billing email:", nullSafe(workspace.getBillingEmail())));
        document.add(labeledLine("Billing address:", nullSafe(workspace.getBillingAddress())));
        document.add(labeledLine("GSTIN:", nullSafe(workspace.getGstin())));
        document.add(Chunk.NEWLINE);
    }

    private void addInvoiceDetails(
            Document document, Invoice invoice, Subscription subscription, Plan plan)
            throws DocumentException {
        document.add(sectionHeading("Invoice Details"));
        document.add(labeledLine("Plan:", nullSafe(plan.getName())));
        document.add(
                labeledLine(
                        "Billing period:",
                        DATE_FORMAT.format(invoice.getPeriodStart())
                                + " — "
                                + DATE_FORMAT.format(invoice.getPeriodEnd())));
        // D14 Wave 9 — GST breakup, null-safe for pre-retrofit invoices (rendered "N/A" rather than
        // fabricating figures for a historical row that never had one computed).
        if (invoice.getBaseAmount() != null) {
            document.add(labeledLine("Base amount:", formatRupees(invoice.getBaseAmount())));
            if (invoice.getIgstAmount() != null && invoice.getIgstAmount().signum() > 0) {
                document.add(labeledLine("IGST @ 18%:", formatRupees(invoice.getIgstAmount())));
            } else {
                document.add(labeledLine("CGST @ 9%:", formatRupees(invoice.getCgstAmount())));
                document.add(labeledLine("SGST @ 9%:", formatRupees(invoice.getSgstAmount())));
            }
            if (invoice.getHsnSacCode() != null) {
                document.add(labeledLine("HSN/SAC:", invoice.getHsnSacCode()));
            }
        }
        document.add(
                labeledLine(
                        "Amount:",
                        formatAmount(invoice.getAmount())));
        document.add(labeledLine("Status:", String.valueOf(invoice.getStatus())));

        if (invoice.getIssuedAt() != null) {
            document.add(
                    labeledLine("Issued:", TIMESTAMP_FORMAT.format(invoice.getIssuedAt())));
        }
        if (invoice.getPaidAt() != null) {
            document.add(labeledLine("Paid:", TIMESTAMP_FORMAT.format(invoice.getPaidAt())));
        }

        document.add(Chunk.NEWLINE);
    }

    private void addFooter(Document document) throws DocumentException {
        Paragraph footer =
                new Paragraph(
                        "Thank you for your business. For questions, contact support@influora.com",
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

    /**
     * Formats amount in INR cents (e.g. 499900 → "₹4,999.00"). Indian rupee symbol + 2 decimal
     * places, following the existing money-display pattern from {@code ContractPdfService}.
     */
    private static String formatAmount(int amountInrCents) {
        double rupees = amountInrCents / 100.0;
        return String.format("₹%,.2f", rupees);
    }

    /** D14 Wave 9 — GST-breakup fields are already {@code DECIMAL(14,2)} rupees, unlike {@link #formatAmount}. */
    private static String formatRupees(BigDecimal rupees) {
        if (rupees == null) {
            return "N/A";
        }
        return String.format("₹%,.2f", rupees);
    }
}
