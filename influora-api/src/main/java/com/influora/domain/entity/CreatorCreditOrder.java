package com.influora.domain.entity;

import com.influora.domain.enums.CreatorCreditOrderStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * T-CREATOR-CREDITS-V2 (SPEC.md §5.4) — a Razorpay checkout order for a creator credit pack.
 * Mirrors {@link WalletTopUp}: a PENDING row is created alongside a Razorpay order, and only
 * {@code CreatorCreditOrderService#confirmPaid} — never the client's order-creation response —
 * can move it to CREDITED.
 */
@Entity
@Table(name = "creator_credit_orders")
public class CreatorCreditOrder {

    @Id
    @Column(length = 26)
    private String id;

    @Column(name = "creator_user_id", nullable = false, length = 26)
    private String creatorUserId;

    @Column(name = "pack_id", nullable = false, length = 26)
    private String packId;

    @Column(name = "pack_code", nullable = false, length = 32)
    private String packCode;

    @Column(nullable = false)
    private int credits;

    @Column(name = "amount_paise", nullable = false)
    private int amountPaise;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private CreatorCreditOrderStatus status;

    @Column(name = "razorpay_order_id", length = 64)
    private String razorpayOrderId;

    @Column(name = "razorpay_payment_id", length = 64)
    private String razorpayPaymentId;

    @Column(name = "idempotency_key", nullable = false, length = 64)
    private String idempotencyKey;

    @Column(name = "grant_id", length = 26)
    private String grantId;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "credited_at")
    private Instant creditedAt;

    @Column(name = "invoice_number", length = 32)
    private String invoiceNumber;

    @Column(name = "taxable_paise")
    private Integer taxablePaise;

    @Column(name = "cgst_paise")
    private Integer cgstPaise;

    @Column(name = "sgst_paise")
    private Integer sgstPaise;

    @Column(name = "igst_paise")
    private Integer igstPaise;

    @Column(name = "hsn_sac_code", length = 10)
    private String hsnSacCode;

    @Column(name = "place_of_supply_state", length = 2)
    private String placeOfSupplyState;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CreatorCreditOrder() {}

    public static CreatorCreditOrder newPending(
            String id,
            String creatorUserId,
            CreatorCreditPack pack,
            String idempotencyKey) {
        CreatorCreditOrder o = new CreatorCreditOrder();
        o.id = id;
        o.creatorUserId = creatorUserId;
        o.packId = pack.getId();
        o.packCode = pack.getCode();
        o.credits = pack.getCredits();
        o.amountPaise = pack.getPricePaise();
        o.currency = "INR";
        o.status = CreatorCreditOrderStatus.PENDING;
        o.idempotencyKey = idempotencyKey;
        Instant now = Instant.now();
        o.createdAt = now;
        o.updatedAt = now;
        return o;
    }

    public String getId() {
        return id;
    }

    public String getCreatorUserId() {
        return creatorUserId;
    }

    public String getPackId() {
        return packId;
    }

    public String getPackCode() {
        return packCode;
    }

    public int getCredits() {
        return credits;
    }

    public int getAmountPaise() {
        return amountPaise;
    }

    public String getCurrency() {
        return currency;
    }

    public CreatorCreditOrderStatus getStatus() {
        return status;
    }

    public String getRazorpayOrderId() {
        return razorpayOrderId;
    }

    public void setRazorpayOrderId(String razorpayOrderId) {
        this.razorpayOrderId = razorpayOrderId;
        touch();
    }

    public String getRazorpayPaymentId() {
        return razorpayPaymentId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getGrantId() {
        return grantId;
    }

    public Instant getPaidAt() {
        return paidAt;
    }

    public Instant getCreditedAt() {
        return creditedAt;
    }

    public String getInvoiceNumber() {
        return invoiceNumber;
    }

    public Integer getTaxablePaise() {
        return taxablePaise;
    }

    public Integer getCgstPaise() {
        return cgstPaise;
    }

    public Integer getSgstPaise() {
        return sgstPaise;
    }

    public Integer getIgstPaise() {
        return igstPaise;
    }

    public String getHsnSacCode() {
        return hsnSacCode;
    }

    public String getPlaceOfSupplyState() {
        return placeOfSupplyState;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** Marks CREDITED — the single crediting transition, called only from {@code confirmPaid}. */
    public void markCredited(String razorpayPaymentId, String grantId, Instant paidAt) {
        this.razorpayPaymentId = razorpayPaymentId;
        this.grantId = grantId;
        this.paidAt = paidAt;
        this.creditedAt = Instant.now();
        this.status = CreatorCreditOrderStatus.CREDITED;
        touch();
    }

    /** Best-effort invoice assignment — never blocks/undoes the credit if it fails (see caller). */
    public void applyInvoice(
            String invoiceNumber,
            int taxablePaise,
            int cgstPaise,
            int sgstPaise,
            int igstPaise,
            String hsnSacCode,
            String placeOfSupplyState) {
        this.invoiceNumber = invoiceNumber;
        this.taxablePaise = taxablePaise;
        this.cgstPaise = cgstPaise;
        this.sgstPaise = sgstPaise;
        this.igstPaise = igstPaise;
        this.hsnSacCode = hsnSacCode;
        this.placeOfSupplyState = placeOfSupplyState;
        touch();
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }
}
