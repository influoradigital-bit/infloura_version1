package com.influora.domain.entity;

import com.influora.domain.enums.ErrorLogSeverity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A single handled server error, captured best-effort by {@code GlobalExceptionHandler} for the
 * admin error-log console ({@code errorApi}, api-contracts.ts lines 654-671).
 *
 * <p>Deliberately has no FK on {@code userId}/{@code resolvedBy} (see V20260718170000 migration):
 * this is an observability sink written from an exception path, and a dangling id must never cause
 * the capturing INSERT to fail.
 */
@Entity
@Table(name = "error_log")
public class ErrorLog {

    @Id
    @Column(name = "id", length = 26)
    private String id;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false)
    private ErrorLogSeverity severity;

    @Column(name = "exception_class", length = 255)
    private String exceptionClass;

    @Column(name = "message", columnDefinition = "TEXT")
    private String message;

    @Column(name = "endpoint", length = 512)
    private String endpoint;

    @Column(name = "http_method", length = 16)
    private String httpMethod;

    @Column(name = "status_code")
    private Integer statusCode;

    @Column(name = "user_id", length = 26)
    private String userId;

    @Column(name = "stack_trace", columnDefinition = "TEXT")
    private String stackTrace;

    @Column(name = "resolved", nullable = false)
    private boolean resolved;

    @Column(name = "resolved_by", length = 26)
    private String resolvedBy;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ErrorLog() {}

    public String getId() {
        return id;
    }

    public ErrorLogSeverity getSeverity() {
        return severity;
    }

    public String getExceptionClass() {
        return exceptionClass;
    }

    public String getMessage() {
        return message;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public String getHttpMethod() {
        return httpMethod;
    }

    public Integer getStatusCode() {
        return statusCode;
    }

    public String getUserId() {
        return userId;
    }

    public String getStackTrace() {
        return stackTrace;
    }

    public boolean isResolved() {
        return resolved;
    }

    public String getResolvedBy() {
        return resolvedBy;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** Marks this error resolved by the given admin. Idempotent-safe; overwrites resolver/time. */
    public void markResolved(String adminId) {
        this.resolved = true;
        this.resolvedBy = adminId;
        this.resolvedAt = Instant.now();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final ErrorLog e = new ErrorLog();

        public Builder id(String id) {
            e.id = id;
            return this;
        }

        public Builder severity(ErrorLogSeverity severity) {
            e.severity = severity;
            return this;
        }

        public Builder exceptionClass(String exceptionClass) {
            e.exceptionClass = exceptionClass;
            return this;
        }

        public Builder message(String message) {
            e.message = message;
            return this;
        }

        public Builder endpoint(String endpoint) {
            e.endpoint = endpoint;
            return this;
        }

        public Builder httpMethod(String httpMethod) {
            e.httpMethod = httpMethod;
            return this;
        }

        public Builder statusCode(Integer statusCode) {
            e.statusCode = statusCode;
            return this;
        }

        public Builder userId(String userId) {
            e.userId = userId;
            return this;
        }

        public Builder stackTrace(String stackTrace) {
            e.stackTrace = stackTrace;
            return this;
        }

        public ErrorLog build() {
            if (e.severity == null) {
                e.severity = ErrorLogSeverity.ERROR;
            }
            e.resolved = false;
            e.createdAt = Instant.now();
            return e;
        }
    }
}
