package com.influora.domain.entity;

import com.influora.domain.enums.MarketplaceInvoiceStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * D14 Doc#2 — Creator service invoice ({@code campaign_service_invoices}). Direction: Creator ->
 * Brand. Per D14-A (marketplace/agent, decided 2026-07-15), the creator is the supplier of record
 * — Influora only facilitates issuance/PDF generation, it never appears as a party on this
 * document. Created inside the SAME transaction as the escrow-release ledger posting that pays
 * the creator (see {@code CampaignServiceInvoiceService#createAtRelease}, wired into all three
 * {@code EscrowService} release call sites), so the invoice and the money movement are atomic.
 *
 * <p><b>Money unit:</b> {@code DECIMAL(14,2)} rupees, matching {@link EscrowHold#getAmount()} and
 * the ledger — deliberately NOT {@link Invoice}'s int-paise convention (CTO flag, D14 spec §1).
 */
@Entity
@Table(name = "campaign_service_invoices")
public class CampaignServiceInvoice {

    @Id
    @Column(length = 26)
    private String id;

    @Column(name = "invoice_number", nullable = false, unique = true, length = 32)
    private String invoiceNumber;

    @Column(name = "collaboration_id", nullable = false, length = 26)
    private String collaborationId;

    @Column(name = "campaign_id", nullable = false, length = 26)
    private String campaignId;

    @Column(name = "escrow_hold_id", nullable = false, unique = true, length = 26)
    private String escrowHoldId;

    @Column(name = "creator_user_id", nullable = false, length = 26)
    private String creatorUserId;

    @Column(name = "brand_workspace_id", nullable = false, length = 26)
    private String brandWorkspaceId;

    @Column(name = "gross_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal grossAmount;

    @Column(nullable = false, length = 3)
    private String currency;

    /** Snapshot at invoice time — the creator's GSTIN may change later; the invoice must not. */
    @Column(name = "creator_gstin", length = 15)
    private String creatorGstin;

    /** D14-D report-only v1: computed + recorded, never withheld from the creator's payout. */
    @Column(name = "tcs_amount", precision = 14, scale = 2)
    private BigDecimal tcsAmount;

    @Column(name = "hsn_sac_code", length = 10)
    private String hsnSacCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MarketplaceInvoiceStatus status;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "pdf_r2_key", length = 500)
    private String pdfR2Key;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected CampaignServiceInvoice() {}

    public String getId() {
        return id;
    }

    public String getInvoiceNumber() {
        return invoiceNumber;
    }

    public String getCollaborationId() {
        return collaborationId;
    }

    public String getCampaignId() {
        return campaignId;
    }

    public String getEscrowHoldId() {
        return escrowHoldId;
    }

    public String getCreatorUserId() {
        return creatorUserId;
    }

    public String getBrandWorkspaceId() {
        return brandWorkspaceId;
    }

    public BigDecimal getGrossAmount() {
        return grossAmount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getCreatorGstin() {
        return creatorGstin;
    }

    public BigDecimal getTcsAmount() {
        return tcsAmount;
    }

    public String getHsnSacCode() {
        return hsnSacCode;
    }

    public MarketplaceInvoiceStatus getStatus() {
        return status;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    public String getPdfR2Key() {
        return pdfR2Key;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setPdfR2Key(String pdfR2Key) {
        this.pdfR2Key = pdfR2Key;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final CampaignServiceInvoice inv = new CampaignServiceInvoice();

        public Builder id(String id) {
            inv.id = id;
            return this;
        }

        public Builder invoiceNumber(String invoiceNumber) {
            inv.invoiceNumber = invoiceNumber;
            return this;
        }

        public Builder collaborationId(String collaborationId) {
            inv.collaborationId = collaborationId;
            return this;
        }

        public Builder campaignId(String campaignId) {
            inv.campaignId = campaignId;
            return this;
        }

        public Builder escrowHoldId(String escrowHoldId) {
            inv.escrowHoldId = escrowHoldId;
            return this;
        }

        public Builder creatorUserId(String creatorUserId) {
            inv.creatorUserId = creatorUserId;
            return this;
        }

        public Builder brandWorkspaceId(String brandWorkspaceId) {
            inv.brandWorkspaceId = brandWorkspaceId;
            return this;
        }

        public Builder grossAmount(BigDecimal grossAmount) {
            inv.grossAmount = grossAmount;
            return this;
        }

        public Builder currency(String currency) {
            inv.currency = currency;
            return this;
        }

        public Builder creatorGstin(String creatorGstin) {
            inv.creatorGstin = creatorGstin;
            return this;
        }

        public Builder tcsAmount(BigDecimal tcsAmount) {
            inv.tcsAmount = tcsAmount;
            return this;
        }

        public Builder hsnSacCode(String hsnSacCode) {
            inv.hsnSacCode = hsnSacCode;
            return this;
        }

        public Builder status(MarketplaceInvoiceStatus status) {
            inv.status = status;
            return this;
        }

        public Builder issuedAt(Instant issuedAt) {
            inv.issuedAt = issuedAt;
            return this;
        }

        public CampaignServiceInvoice build() {
            if (inv.currency == null) {
                inv.currency = "INR";
            }
            inv.createdAt = Instant.now();
            return inv;
        }
    }
}
