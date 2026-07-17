package com.influora.domain.entity;

import com.influora.domain.enums.HsnSacAppliesTo;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Configurable HSN/SAC lookup (Rohan build-flag #4) — the code rendered on a tax invoice is never
 * hardcoded in Java; it is read from this admin-editable table, keyed by {@link HsnSacAppliesTo}.
 */
@Entity
@Table(name = "hsn_sac_codes")
public class HsnSacCode {

    @Id
    @Column(length = 26)
    private String id;

    @Column(name = "code_type", nullable = false, length = 20)
    private String codeType;

    @Column(nullable = false, length = 10)
    private String code;

    @Column(nullable = false, length = 200)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "applies_to", nullable = false, length = 50)
    private HsnSacAppliesTo appliesTo;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected HsnSacCode() {}

    public String getId() {
        return id;
    }

    public String getCodeType() {
        return codeType;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public HsnSacAppliesTo getAppliesTo() {
        return appliesTo;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
