package com.influora.domain.entity;

import com.influora.domain.enums.InvoiceNumberSeriesType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * D14-C (2026-07-15, Rohan) statutory invoice numbering — a continuous, per-financial-year,
 * per-document-type sequential series, reset every Indian FY (Apr 1). One row per {@code
 * (series_type, fiscal_year, creator_invoice_code)} combination; {@code creator_invoice_code} is
 * NULL for the three platform-wide series ({@code SUBSCRIPTION}/{@code COMMISSION_BRAND}/{@code
 * COMMISSION_CREATOR}) and non-null for {@code CAMPAIGN_SERVICE} (one series PER creator).
 *
 * <p>{@code next_seq} is advanced under a pessimistic row lock ({@link
 * com.influora.repository.InvoiceNumberSequenceRepository#findForUpdate}) inside the same
 * transaction as the invoice row + ledger posting it numbers — see {@code InvoiceNumberService}.
 */
@Entity
@Table(name = "invoice_number_sequences")
public class InvoiceNumberSequence {

    @Id
    @Column(length = 26)
    private String id;

    @Enumerated(EnumType.STRING)
    @Column(name = "series_type", nullable = false, length = 20)
    private InvoiceNumberSeriesType seriesType;

    @Column(name = "fiscal_year", nullable = false, length = 7)
    private String fiscalYear;

    @Column(name = "creator_invoice_code", length = 12)
    private String creatorInvoiceCode;

    @Column(name = "next_seq", nullable = false)
    private int nextSeq;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected InvoiceNumberSequence() {}

    public String getId() {
        return id;
    }

    public InvoiceNumberSeriesType getSeriesType() {
        return seriesType;
    }

    public String getFiscalYear() {
        return fiscalYear;
    }

    public String getCreatorInvoiceCode() {
        return creatorInvoiceCode;
    }

    public int getNextSeq() {
        return nextSeq;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** Returns the sequence number to use for this call, then advances {@code next_seq}. */
    public int consumeNext() {
        int assigned = nextSeq;
        nextSeq = nextSeq + 1;
        updatedAt = Instant.now();
        return assigned;
    }

    public static InvoiceNumberSequence newSeries(
            String id, InvoiceNumberSeriesType seriesType, String fiscalYear, String creatorInvoiceCode) {
        InvoiceNumberSequence s = new InvoiceNumberSequence();
        s.id = id;
        s.seriesType = seriesType;
        s.fiscalYear = fiscalYear;
        s.creatorInvoiceCode = creatorInvoiceCode;
        s.nextSeq = 1;
        Instant now = Instant.now();
        s.createdAt = now;
        s.updatedAt = now;
        return s;
    }
}
