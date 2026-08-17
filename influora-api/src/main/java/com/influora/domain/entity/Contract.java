package com.influora.domain.entity;

import com.influora.domain.enums.ContractStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** The signed agreement per collaboration — total amount, payment schedule, signatures, terms. */
@Entity
@Table(name = "contracts")
public class Contract {

    @Id
    @Column(length = 26)
    private String id;

    @Column(name = "collaboration_id", nullable = false, length = 26)
    private String collaborationId;

    @Column(name = "workspace_id", nullable = false, length = 26)
    private String workspaceId;

    @Column(nullable = false)
    private int version;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ContractStatus status;

    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "pdf_r2_key", length = 500)
    private String pdfR2Key;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "terms", columnDefinition = "json")
    private String termsJson;

    @Column(name = "brand_signed_at")
    private Instant brandSignedAt;

    // [F-0292, signature-name-discarded-server-side] The typed full name each party signed
    // with -- the e-sign UI (contracts-and-deliverables.tsx) tells the user typing this name
    // and clicking "Sign Contract" is the legally binding act under the IT Act 2000, but the
    // server previously had nowhere to keep it (ContractSignRequest had no `name` field), so it
    // was discarded on every signature. Nullable: pre-existing signed contracts have no name on
    // file and must not be fabricated one.
    @Column(name = "brand_signer_name", length = 255)
    private String brandSignerName;

    @Column(name = "creator_signed_at")
    private Instant creatorSignedAt;

    @Column(name = "creator_signer_name", length = 255)
    private String creatorSignerName;

    @Column(name = "effective_date")
    private LocalDate effectiveDate;

    @Column(name = "expiration_date")
    private LocalDate expirationDate;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Contract() {}

    public String getId() {
        return id;
    }

    public String getCollaborationId() {
        return collaborationId;
    }

    public String getWorkspaceId() {
        return workspaceId;
    }

    public int getVersion() {
        return version;
    }

    public ContractStatus getStatus() {
        return status;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getPdfR2Key() {
        return pdfR2Key;
    }

    public void setPdfR2Key(String pdfR2Key) {
        this.pdfR2Key = pdfR2Key;
        touch();
    }

    public String getTermsJson() {
        return termsJson;
    }

    public Instant getBrandSignedAt() {
        return brandSignedAt;
    }

    public String getBrandSignerName() {
        return brandSignerName;
    }

    public Instant getCreatorSignedAt() {
        return creatorSignedAt;
    }

    public String getCreatorSignerName() {
        return creatorSignerName;
    }

    public LocalDate getEffectiveDate() {
        return effectiveDate;
    }

    public LocalDate getExpirationDate() {
        return expirationDate;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /**
     * @param signerName the typed full name the brand signed with (F-0292). {@code
     *     brandSignedAt} is always stamped with the server's own {@code Instant.now()} --
     *     never a client-supplied timestamp, which is not evidence of when a signature actually
     *     happened. {@code signerName} is only written when non-blank, so a caller that cannot
     *     supply one (defensive callers, not any real path today) does not clobber a
     *     previously-recorded name with null.
     */
    public void recordBrandSignature(String signerName) {
        this.brandSignedAt = Instant.now();
        if (signerName != null && !signerName.isBlank()) {
            this.brandSignerName = signerName;
        }
        advanceIfFullySigned();
        touch();
    }

    /**
     * @param signerName the typed full name recorded for the creator's signature (F-0292) --
     *     either the creator's own typed name (creator-authenticated sign) or the name supplied
     *     by an elevated brand member relaying the creator's out-of-band assent (see {@link
     *     com.influora.service.ContractService#recordSignature}). Same non-blank write guard as
     *     {@link #recordBrandSignature(String)}.
     */
    public void recordCreatorSignature(String signerName) {
        this.creatorSignedAt = Instant.now();
        if (signerName != null && !signerName.isBlank()) {
            this.creatorSignerName = signerName;
        }
        advanceIfFullySigned();
        touch();
    }

    private void advanceIfFullySigned() {
        if (this.brandSignedAt != null && this.creatorSignedAt != null) {
            this.status = ContractStatus.ACTIVE;
        } else {
            this.status = ContractStatus.PENDING_SIGNATURES;
        }
    }

    public void setStatus(ContractStatus status) {
        this.status = status;
        touch();
    }

    public void touch() {
        this.updatedAt = Instant.now();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final Contract c = new Contract();

        public Builder id(String id) {
            c.id = id;
            return this;
        }

        public Builder collaborationId(String collaborationId) {
            c.collaborationId = collaborationId;
            return this;
        }

        public Builder workspaceId(String workspaceId) {
            c.workspaceId = workspaceId;
            return this;
        }

        public Builder version(int version) {
            c.version = version;
            return this;
        }

        public Builder status(ContractStatus status) {
            c.status = status;
            return this;
        }

        public Builder totalAmount(BigDecimal totalAmount) {
            c.totalAmount = totalAmount;
            return this;
        }

        public Builder currency(String currency) {
            c.currency = currency;
            return this;
        }

        public Builder pdfR2Key(String pdfR2Key) {
            c.pdfR2Key = pdfR2Key;
            return this;
        }

        public Builder termsJson(String termsJson) {
            c.termsJson = termsJson;
            return this;
        }

        public Builder effectiveDate(LocalDate effectiveDate) {
            c.effectiveDate = effectiveDate;
            return this;
        }

        public Builder expirationDate(LocalDate expirationDate) {
            c.expirationDate = expirationDate;
            return this;
        }

        public Contract build() {
            if (c.currency == null) {
                c.currency = "INR";
            }
            if (c.status == null) {
                c.status = ContractStatus.DRAFT;
            }
            if (c.version == 0) {
                c.version = 1;
            }
            Instant now = Instant.now();
            c.createdAt = now;
            c.updatedAt = now;
            return c;
        }
    }
}
