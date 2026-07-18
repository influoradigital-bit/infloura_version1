package com.influora.domain.entity;

import com.influora.domain.enums.AdminAuditLogSource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Append-only admin-action audit row ({@code admin_audit_log}, V34__admin_tables.sql). The ONLY
 * writer is {@code AdminAuditLogService} — see that class and
 * {@code wiki/admin-progress/AUDIT-LOG-WRITE-SPEC.md} (Kabir) for the field-provenance and
 * allow-list rules this entity's data must already satisfy by the time it reaches {@link
 * #builder()}. In particular: {@link #oldValueJson}/{@link #newValueJson} must already be a
 * field-allow-listed snapshot (never a raw entity dump), and {@link #adminId}/{@link
 * #adminEmail}/{@link #ipAddress} must already be server-derived (never client-supplied) —
 * this entity does not re-enforce those rules itself, the service layer does, before ever
 * constructing a builder.
 *
 * <p>No setters exist beyond the builder — rows are insert-only by construction, matching the
 * {@code AuditLogEntry} (V15 {@code audit_log}) precedent in this codebase.
 */
@Entity
@Table(name = "admin_audit_log")
public class AdminAuditLog {

    @Id
    @Column(length = 26)
    private String id;

    @Column(name = "admin_id", nullable = false, length = 26)
    private String adminId;

    @Column(name = "admin_email", nullable = false)
    private String adminEmail;

    @Column(nullable = false, length = 100)
    private String action;

    @Column(name = "entity_type", nullable = false, length = 50)
    private String entityType;

    @Column(name = "entity_id", nullable = false, length = 26)
    private String entityId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "old_value", columnDefinition = "json")
    private String oldValueJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "new_value", columnDefinition = "json")
    private String newValueJson;

    @Column(columnDefinition = "TEXT")
    private String reason;

    @Column(name = "ip_address", nullable = false, length = 45)
    private String ipAddress;

    /** Provenance discriminator (V20260718140000__admin_audit_log_source.sql) — see
     * {@link AdminAuditLogSource} javadoc for why this exists. */
    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20)
    private AdminAuditLogSource source;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AdminAuditLog() {}

    public String getId() {
        return id;
    }

    public String getAdminId() {
        return adminId;
    }

    public String getAdminEmail() {
        return adminEmail;
    }

    public String getAction() {
        return action;
    }

    public String getEntityType() {
        return entityType;
    }

    public String getEntityId() {
        return entityId;
    }

    public String getOldValueJson() {
        return oldValueJson;
    }

    public String getNewValueJson() {
        return newValueJson;
    }

    public String getReason() {
        return reason;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public AdminAuditLogSource getSource() {
        return source;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final AdminAuditLog e = new AdminAuditLog();

        public Builder id(String id) {
            e.id = id;
            return this;
        }

        public Builder adminId(String adminId) {
            e.adminId = adminId;
            return this;
        }

        public Builder adminEmail(String adminEmail) {
            e.adminEmail = adminEmail;
            return this;
        }

        public Builder action(String action) {
            e.action = action;
            return this;
        }

        public Builder entityType(String entityType) {
            e.entityType = entityType;
            return this;
        }

        public Builder entityId(String entityId) {
            e.entityId = entityId;
            return this;
        }

        public Builder oldValueJson(String oldValueJson) {
            e.oldValueJson = oldValueJson;
            return this;
        }

        public Builder newValueJson(String newValueJson) {
            e.newValueJson = newValueJson;
            return this;
        }

        public Builder reason(String reason) {
            e.reason = reason;
            return this;
        }

        public Builder ipAddress(String ipAddress) {
            e.ipAddress = ipAddress;
            return this;
        }

        public Builder source(AdminAuditLogSource source) {
            e.source = source;
            return this;
        }

        public AdminAuditLog build() {
            e.createdAt = Instant.now();
            return e;
        }
    }
}
