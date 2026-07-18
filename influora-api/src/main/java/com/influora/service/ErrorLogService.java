package com.influora.service;

import com.influora.common.Ulids;
import com.influora.domain.entity.ErrorLog;
import com.influora.domain.enums.ErrorLogSeverity;
import com.influora.repository.ErrorLogRepository;
import com.influora.security.ErrorLogRedactor;
import java.io.PrintWriter;
import java.io.StringWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Best-effort writer that persists handled server errors into {@code error_log} for the admin
 * error-log console. Called by {@code GlobalExceptionHandler}'s catch-all 500 handler.
 *
 * <p><b>Two safety properties, both load-bearing:</b>
 *
 * <ol>
 *   <li><b>{@code REQUIRES_NEW}:</b> the request whose exception we're capturing has almost
 *       certainly already had its own transaction marked rollback-only. Writing the log row on a
 *       brand-new, independent transaction is the only way it can actually commit — reusing the
 *       caller's poisoned transaction would roll the INSERT back with it.
 *   <li><b>Never throw:</b> {@link #record} swallows every exception. A failure to log an error
 *       must never turn into a second error that breaks the response the handler is trying to
 *       return. Worst case we drop one observability row and log a warning.
 * </ol>
 */
@Service
public class ErrorLogService {

    private static final Logger log = LoggerFactory.getLogger(ErrorLogService.class);

    private static final int MAX_MESSAGE = 2000;
    private static final int MAX_STACK = 8000;

    private final ErrorLogRepository errorLogRepository;

    public ErrorLogService(ErrorLogRepository errorLogRepository) {
        this.errorLogRepository = errorLogRepository;
    }

    /**
     * Persists a captured server error. Best-effort: any failure is logged and swallowed so the
     * caller (an exception handler) is never disrupted.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(
            ErrorLogSeverity severity,
            Throwable ex,
            String endpoint,
            String httpMethod,
            Integer statusCode,
            String userId) {
        try {
            ErrorLog entry =
                    ErrorLog.builder()
                            .id(Ulids.newUlid())
                            .severity(severity != null ? severity : ErrorLogSeverity.ERROR)
                            .exceptionClass(ex != null ? ex.getClass().getName() : null)
                            // Kabir M-1 — scrub secrets/PII (JWTs, Bearer tokens, password/token/
                            // apikey key-values, emails, card-like digit runs) BEFORE persisting, so
                            // a SUPER_ADMIN reading the console never sees them. Redact first, then
                            // truncate, so a secret straddling the length cap can't survive half-scrubbed.
                            .message(
                                    truncate(
                                            ErrorLogRedactor.redact(ex != null ? ex.getMessage() : null),
                                            MAX_MESSAGE))
                            .endpoint(endpoint)
                            .httpMethod(httpMethod)
                            .statusCode(statusCode)
                            .userId(userId)
                            .stackTrace(truncate(ErrorLogRedactor.redact(stackToString(ex)), MAX_STACK))
                            .build();
            errorLogRepository.save(entry);
        } catch (Exception loggingFailure) {
            // Never propagate — see class javadoc. One dropped row is acceptable; a thrown
            // exception here is not.
            log.warn("Failed to persist error_log row (swallowed): {}", loggingFailure.toString());
        }
    }

    private static String stackToString(Throwable ex) {
        if (ex == null) {
            return null;
        }
        StringWriter sw = new StringWriter();
        ex.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
