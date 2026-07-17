package com.influora.domain.entity;

import com.influora.domain.enums.InvoiceStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Subscription invoice — billing history + PDF archive.
 * V54__subscription_billing.sql, Task 11/14 subscription billing prep batch 1.
 *
 * <p><b>pdfR2Key:</b> R2 object key for invoice PDF — null until generated. Task 16 builds {@code
 * InvoicePdfService} mirroring {@code ContractPdfService}, stores bytes to R2, and updates this
 * field.
 */
@Entity
@Table(name = "invoices")
public class Invoice {

    @Id
    @Column(length = 26)
    private String id;

    @Column(name = "subscription_id", nullable = false, length = 26)
    private String subscriptionId;

    @Column(name = "workspace_id", nullable = false, length = 26)
    private String workspaceId;

    @Column(name = "razorpay_invoice_id", unique = true, length = 100)
    private String razorpayInvoiceId;

    @Column(nullable = false)
    private int amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InvoiceStatus status;

    @Column(name = "period_start", nullable = false)
    private Instant periodStart;

    @Column(name = "period_end", nullable = false)
    private Instant periodEnd;

    @Column(name = "pdf_r2_key", length = 500)
    private String pdfR2Key;

    /**
     * D14 Wave 9 (2026-07-15) — statutory series (per {@code InvoiceNumberSeriesType#SUBSCRIPTION},
     * {@code INF/SUB/<FY>/<seq>}). Nullable for backward compatibility — pre-D14 invoices were
     * created before this field existed and are NOT retroactively renumbered (a real invoice
     * number, once issued, is immutable; backfilling one after the fact would misrepresent when it
     * was actually issued). Populated going forward by {@code InvoiceService.generateInvoiceFromWebhook}.
     */
    @Column(name = "invoice_number", unique = true, length = 32)
    private String invoiceNumber;

    /**
     * D14 Wave 9 GST breakup — deliberately DECIMAL(14,2) RUPEES (the D14 money-unit convention),
     * NOT the same unit as {@link #amount} (int paise). {@code amount} stays int-paise unchanged
     * (a full type migration is a separate, higher-risk change out of this ticket's scope); these
     * new fields are computed FROM {@code amount} at generation time (amount/100, GST-inclusive)
     * and are the ones a GST-compliant PDF actually renders. Null for every invoice generated
     * before this migration — {@code InvoicePdfService} renders "N/A" for a null breakup rather
     * than fabricating figures for historical rows.
     */
    @Column(name = "base_amount", precision = 14, scale = 2)
    private BigDecimal baseAmount;

    @Column(name = "cgst_amount", precision = 14, scale = 2)
    private BigDecimal cgstAmount;

    @Column(name = "sgst_amount", precision = 14, scale = 2)
    private BigDecimal sgstAmount;

    @Column(name = "igst_amount", precision = 14, scale = 2)
    private BigDecimal igstAmount;

    @Column(name = "hsn_sac_code", length = 10)
    private String hsnSacCode;

    @Column(name = "issued_at")
    private Instant issuedAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Invoice() {}

    public String getId() {
        return id;
    }

    public String getSubscriptionId() {
        return subscriptionId;
    }

    public String getWorkspaceId() {
        return workspaceId;
    }

    public String getRazorpayInvoiceId() {
        return razorpayInvoiceId;
    }

    public int getAmount() {
        return amount;
    }

    public InvoiceStatus getStatus() {
        return status;
    }

    public Instant getPeriodStart() {
        return periodStart;
    }

    public Instant getPeriodEnd() {
        return periodEnd;
    }

    public String getPdfR2Key() {
        return pdfR2Key;
    }

    public String getInvoiceNumber() {
        return invoiceNumber;
    }

    public void setInvoiceNumber(String invoiceNumber) {
        this.invoiceNumber = invoiceNumber;
    }

    public BigDecimal getBaseAmount() {
        return baseAmount;
    }

    public BigDecimal getCgstAmount() {
        return cgstAmount;
    }

    public BigDecimal getSgstAmount() {
        return sgstAmount;
    }

    public BigDecimal getIgstAmount() {
        return igstAmount;
    }

    public String getHsnSacCode() {
        return hsnSacCode;
    }

    /** Null-guarded partial update — sets the D14 GST breakup once, at generation time. */
    public void applyGstBreakup(
            String invoiceNumber,
            BigDecimal baseAmount,
            BigDecimal cgstAmount,
            BigDecimal sgstAmount,
            BigDecimal igstAmount,
            String hsnSacCode) {
        this.invoiceNumber = invoiceNumber;
        this.baseAmount = baseAmount;
        this.cgstAmount = cgstAmount;
        this.sgstAmount = sgstAmount;
        this.igstAmount = igstAmount;
        this.hsnSacCode = hsnSacCode;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    public Instant getPaidAt() {
        return paidAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setStatus(InvoiceStatus status) {
        this.status = status;
    }

    public void setPdfR2Key(String pdfR2Key) {
        this.pdfR2Key = pdfR2Key;
    }

    public void markPaid(Instant paidAt) {
        this.status = InvoiceStatus.PAID;
        this.paidAt = paidAt;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final Invoice i = new Invoice();

        public Builder id(String id) {
            i.id = id;
            return this;
        }

        public Builder subscriptionId(String subscriptionId) {
            i.subscriptionId = subscriptionId;
            return this;
        }

        public Builder workspaceId(String workspaceId) {
            i.workspaceId = workspaceId;
            return this;
        }

        public Builder razorpayInvoiceId(String razorpayInvoiceId) {
            i.razorpayInvoiceId = razorpayInvoiceId;
            return this;
        }

        public Builder amount(int amount) {
            i.amount = amount;
            return this;
        }

        public Builder status(InvoiceStatus status) {
            i.status = status;
            return this;
        }

        public Builder periodStart(Instant periodStart) {
            i.periodStart = periodStart;
            return this;
        }

        public Builder periodEnd(Instant periodEnd) {
            i.periodEnd = periodEnd;
            return this;
        }

        public Builder issuedAt(Instant issuedAt) {
            i.issuedAt = issuedAt;
            return this;
        }

        public Invoice build() {
            i.createdAt = Instant.now();
            return i;
        }
    }
}
