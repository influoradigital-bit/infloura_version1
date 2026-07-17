package com.influora.domain.entity;

import com.influora.domain.enums.CommissionInvoiceLeg;
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
 * D14 Doc#3 — platform commission invoice ({@code platform_commission_invoices}). Direction:
 * Influora -> Brand (leg {@link CommissionInvoiceLeg#BRAND}, Doc#3a) or Influora -> Creator (leg
 * {@link CommissionInvoiceLeg#CREATOR}, Doc#3b). Influora's own output supply — standard 18% GST,
 * invoiced by Influora (D14 spec §3).
 *
 * <p>Per D14-E (split, decided 2026-07-15): 3a and 3b are NOT a 1:1 pair.
 *
 * <ul>
 *   <li>3a — campaign-scoped, one per campaign, created inside {@code
 *       BrandCampaignFeeService#chargeOnPublish} after the {@code PLATFORM_FEE} posting.
 *       {@code escrowHoldId} is null (publish happens before any hold funds).
 *   <li>3b — escrow-hold-scoped, one per released hold, created inside {@code
 *       PlatformFeeService#deductAtRelease} after its {@code PLATFORM_FEE} posting.
 * </ul>
 */
@Entity
@Table(name = "platform_commission_invoices")
public class PlatformCommissionInvoice {

    @Id
    @Column(length = 26)
    private String id;

    @Column(name = "invoice_number", nullable = false, unique = true, length = 32)
    private String invoiceNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private CommissionInvoiceLeg leg;

    @Column(name = "campaign_id", nullable = false, length = 26)
    private String campaignId;

    /** Null for the BRAND leg (publish-time, no hold yet); set for the CREATOR leg. */
    @Column(name = "escrow_hold_id", length = 26)
    private String escrowHoldId;

    /** Set for the BRAND leg (the customer), null for the CREATOR leg. */
    @Column(name = "counterparty_workspace_id", length = 26)
    private String counterpartyWorkspaceId;

    /** Set for the CREATOR leg (the customer), null for the BRAND leg. */
    @Column(name = "counterparty_user_id", length = 26)
    private String counterpartyUserId;

    @Column(name = "fee_bps_applied", nullable = false)
    private int feeBpsApplied;

    @Column(name = "commission_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal commissionAmount;

    /** 18% on Influora's own commission — Influora's output supply, standard rate. */
    @Column(name = "gst_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal gstAmount;

    @Column(name = "hsn_sac_code", length = 10)
    private String hsnSacCode;

    /** The {@code PLATFORM_FEE} ledger posting this invoice documents — traceability. */
    @Column(name = "ledger_txn_id", nullable = false, length = 26)
    private String ledgerTxnId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MarketplaceInvoiceStatus status;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "pdf_r2_key", length = 500)
    private String pdfR2Key;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PlatformCommissionInvoice() {}

    public String getId() {
        return id;
    }

    public String getInvoiceNumber() {
        return invoiceNumber;
    }

    public CommissionInvoiceLeg getLeg() {
        return leg;
    }

    public String getCampaignId() {
        return campaignId;
    }

    public String getEscrowHoldId() {
        return escrowHoldId;
    }

    public String getCounterpartyWorkspaceId() {
        return counterpartyWorkspaceId;
    }

    public String getCounterpartyUserId() {
        return counterpartyUserId;
    }

    public int getFeeBpsApplied() {
        return feeBpsApplied;
    }

    public BigDecimal getCommissionAmount() {
        return commissionAmount;
    }

    public BigDecimal getGstAmount() {
        return gstAmount;
    }

    public String getHsnSacCode() {
        return hsnSacCode;
    }

    public String getLedgerTxnId() {
        return ledgerTxnId;
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
        private final PlatformCommissionInvoice inv = new PlatformCommissionInvoice();

        public Builder id(String id) {
            inv.id = id;
            return this;
        }

        public Builder invoiceNumber(String invoiceNumber) {
            inv.invoiceNumber = invoiceNumber;
            return this;
        }

        public Builder leg(CommissionInvoiceLeg leg) {
            inv.leg = leg;
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

        public Builder counterpartyWorkspaceId(String counterpartyWorkspaceId) {
            inv.counterpartyWorkspaceId = counterpartyWorkspaceId;
            return this;
        }

        public Builder counterpartyUserId(String counterpartyUserId) {
            inv.counterpartyUserId = counterpartyUserId;
            return this;
        }

        public Builder feeBpsApplied(int feeBpsApplied) {
            inv.feeBpsApplied = feeBpsApplied;
            return this;
        }

        public Builder commissionAmount(BigDecimal commissionAmount) {
            inv.commissionAmount = commissionAmount;
            return this;
        }

        public Builder gstAmount(BigDecimal gstAmount) {
            inv.gstAmount = gstAmount;
            return this;
        }

        public Builder hsnSacCode(String hsnSacCode) {
            inv.hsnSacCode = hsnSacCode;
            return this;
        }

        public Builder ledgerTxnId(String ledgerTxnId) {
            inv.ledgerTxnId = ledgerTxnId;
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

        public PlatformCommissionInvoice build() {
            inv.createdAt = Instant.now();
            return inv;
        }
    }
}
