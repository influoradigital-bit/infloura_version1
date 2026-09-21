package com.influora.common;

import com.influora.domain.enums.ErrorLogSeverity;
import com.influora.security.AuthPrincipal;
import com.influora.service.ErrorLogService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final ErrorLogService errorLogService;

    public GlobalExceptionHandler(ErrorLogService errorLogService) {
        this.errorLogService = errorLogService;
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiResponse<Void>> handleApi(ApiException ex) {
        return ResponseEntity.status(ex.getStatus())
                .body(ApiResponse.fail(ApiErrorBody.of(ex.getCode(), ex.getMessage())));
    }

    // [SEC: MF-1 follow-up, 2026-07-21] Spring dispatches to the most specific matching handler,
    // so this subclass-specific handler wins over handleApi(ApiException) above for this one type
    // — no @Order needed. Only place requiredAmount/walletBalance/shortfallAmount/currency are
    // ever put on the wire; every other ApiException still serializes via the generic handler
    // above with those four fields omitted (ApiErrorBody's NON_NULL inclusion).
    @ExceptionHandler(InsufficientFundsException.class)
    public ResponseEntity<ApiResponse<Void>> handleInsufficientFunds(InsufficientFundsException ex) {
        return ResponseEntity.status(ex.getStatus())
                .body(
                        ApiResponse.fail(
                                ApiErrorBody.insufficientFunds(
                                        ex.getCode(),
                                        ex.getMessage(),
                                        ex.getRequiredAmount(),
                                        ex.getWalletBalance(),
                                        ex.getShortfallAmount(),
                                        ex.getCurrency())));
    }

    // T-CREATORCONNECT-0902 — same "subclass-specific handler wins, no @Order needed" dispatch
    // as handleInsufficientFunds above. Only place linkedCreatorProfileId is ever put on the wire.
    @ExceptionHandler(CreatorAlreadyOnInfluoraException.class)
    public ResponseEntity<ApiResponse<Void>> handleCreatorAlreadyOnInfluora(
            CreatorAlreadyOnInfluoraException ex) {
        return ResponseEntity.status(ex.getStatus())
                .body(
                        ApiResponse.fail(
                                ApiErrorBody.creatorAlreadyOnInfluora(
                                        ex.getMessage(), ex.getLinkedCreatorProfileId())));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        List<ApiErrorBody.FieldError> fields =
                ex.getBindingResult().getFieldErrors().stream()
                        .map(this::toFieldError)
                        .toList();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.fail(ApiErrorBody.validation("Request validation failed", fields)));
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiResponse<Void>> handleBadCredentials() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.fail(ApiErrorBody.of("INVALID_CREDENTIALS", "Invalid email or password")));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.fail(ApiErrorBody.of("FORBIDDEN", "Access denied")));
    }

    // Kabir H-1 — with spring.servlet.multipart.max-file-size/max-request-size now configured,
    // an over-limit upload throws this instead of falling through to the generic 500 handler.
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleMaxUploadSizeExceeded(MaxUploadSizeExceededException ex) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ApiResponse.fail(ApiErrorBody.of("PAYLOAD_TOO_LARGE", "Upload exceeds the maximum allowed size")));
    }

    // Kabir I-1c — constraint violations (unique/FK) surfaced as a clean 409, not a bare 500.
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        log.error("Data integrity violation", ex);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.fail(ApiErrorBody.of("DATA_INTEGRITY_VIOLATION", "The request could not be completed due to a data conflict")));
    }

    // Kabir I-1c — malformed/unparseable request bodies surfaced as a clean 400, not a bare 500.
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleMessageNotReadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.fail(ApiErrorBody.of("MALFORMED_REQUEST", "Request body is missing or malformed")));
    }

    // Kabir I-1c — optimistic-lock version conflicts surfaced as a clean 409, not a bare 500.
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<Void>> handleOptimisticLockingFailure(OptimisticLockingFailureException ex) {
        log.error("Optimistic locking failure", ex);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.fail(ApiErrorBody.of("CONCURRENT_MODIFICATION", "This record was modified by another request; please retry")));
    }

    /**
     * [EV-181] Pessimistic-lock failures — the {@code SELECT ... FOR UPDATE} side of the same story
     * the handler above covers for {@code @Version} — surfaced as a clean 409 instead of a bare 500.
     *
     * <p>Every money path in this codebase serializes writers with real row locks, not optimistic
     * versions: {@code EscrowService#lockCollaborationEscrowForApproval}, {@code
     * CollaborationRepository#findByIdForUpdate}, {@code EscrowHoldRepository#findByIdForUpdate},
     * {@code DeliverableRepository#findByIdAndWorkspaceId} (F-0580), {@code
     * WalletRepository#findByIdForUpdate}. When a waiter gives up, Hibernate's MySQL dialect maps
     * error 1205 ({@code ER_LOCK_WAIT_TIMEOUT}) to {@code jakarta.persistence.LockTimeoutException}
     * and Spring translates that to {@link CannotAcquireLockException}, a subclass of {@link
     * PessimisticLockingFailureException}; a deadlock victim (1213) arrives the same way. Neither
     * had a handler, so both fell through to the generic {@code Exception} handler below and the
     * caller got a 500 — for a situation that is purely "another request is mid-write on this row",
     * not a server fault.
     *
     * <p>Meera measured that live: the loser of two concurrent deliverable approvals got a 500
     * {@code PessimisticLockingFailureException} rather than the intended 409, because the WINNING
     * approval was itself stalled ~101s behind the FK self-block that {@link AfterCommit} now
     * removes. With that stall gone the loser waits milliseconds, then reads the already-committed
     * APPROVED row and is refused deterministically by {@code BrandDeliverableService#approve}'s
     * own {@code canReview} guard — 409 {@code INVALID_STATE}, the intended answer. This handler is
     * the backstop for the residual case (a genuinely long-running holder, or a deadlock victim):
     * it makes that answer 409 {@code CONCURRENT_MODIFICATION} — retryable, documented, never a 500
     * — on every locking path at once, an approve racing a refund included.
     *
     * <p>{@code CONCURRENT_MODIFICATION} rather than {@code INVALID_STATE} for that residual case
     * on purpose: a lock timeout means we never got to READ the row, so we do not know its state
     * and must not claim one. Both are 409; the two codes are distinguishable by the client, and
     * only this one is safe to auto-retry.
     *
     * <p>Registering the base class covers {@link CannotAcquireLockException} already; it is listed
     * explicitly so the intent survives any future reshuffle of the Spring DAO hierarchy.
     */
    @ExceptionHandler({PessimisticLockingFailureException.class, CannotAcquireLockException.class})
    public ResponseEntity<ApiResponse<Void>> handlePessimisticLockingFailure(PessimisticLockingFailureException ex) {
        log.error("Pessimistic locking failure (row-lock timeout or deadlock victim)", ex);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.fail(ApiErrorBody.of("CONCURRENT_MODIFICATION", "This record was modified by another request; please retry")));
    }

    // No handler mapped the request path (e.g. a typo'd static resource / unknown route) — was
    // falling through to the generic 500 handler instead of a clean 404.
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResourceFound(NoResourceFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.fail(ApiErrorBody.of("NOT_FOUND", "The requested resource was not found")));
    }

    // A required request header was missing (e.g. Idempotency-Key on money/turn endpoints) —
    // a client input error, not a server fault. Was surfacing as 500.
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingRequestHeader(
            MissingRequestHeaderException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(
                        ApiResponse.fail(
                                ApiErrorBody.of(
                                        "MISSING_HEADER",
                                        "Required header '" + ex.getHeaderName() + "' is missing")));
    }

    // A required @RequestParam was missing — a client input error, not a server fault.
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingServletRequestParameter(
            MissingServletRequestParameterException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(
                        ApiResponse.fail(
                                ApiErrorBody.of(
                                        "MISSING_PARAMETER",
                                        "Required parameter '" + ex.getParameterName() + "' is missing")));
    }

    // Route exists but not for this HTTP method (e.g. DELETE on a GET-only endpoint) — 405, not 500.
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException ex) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(
                        ApiResponse.fail(
                                ApiErrorBody.of(
                                        "METHOD_NOT_ALLOWED",
                                        "HTTP method '" + ex.getMethod() + "' is not supported for this endpoint")));
    }

    // A path/query param couldn't be converted to its target type (e.g. non-numeric id) — 400, not 500.
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(
                        ApiResponse.fail(
                                ApiErrorBody.of(
                                        "INVALID_PARAMETER",
                                        "Parameter '" + ex.getName() + "' has an invalid value")));
    }

    // Kabir — a request Content-Type the endpoint doesn't accept (e.g. text/xml against a
    // @RequestBody(consumes = APPLICATION_JSON) handler) was falling through to the generic 500
    // handler instead of a clean 415.
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMediaTypeNotSupported(
            HttpMediaTypeNotSupportedException ex) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(
                        ApiResponse.fail(
                                ApiErrorBody.of(
                                        "UNSUPPORTED_MEDIA_TYPE",
                                        "Content-Type '" + ex.getContentType() + "' is not supported for this endpoint")));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneric(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception", ex);
        // Best-effort persist for the admin error-log console. Runs in its own REQUIRES_NEW
        // transaction and never throws internally — see ErrorLogService. Only this catch-all 500 path
        // is captured; the 4xx/409 handlers above are expected/validation outcomes, not server faults.
        // Kabir L-2 — ErrorLogService.record swallows its own body exceptions, but the REQUIRES_NEW
        // transaction COMMITS at the proxy boundary as this call returns; a commit-time failure there
        // would escape the @ExceptionHandler and degrade our clean JSON 500 into a raw container 500.
        // Wrap it (log-and-swallow) so the handler ALWAYS returns its envelope below.
        try {
            errorLogService.record(
                    ErrorLogSeverity.ERROR,
                    ex,
                    request != null ? request.getRequestURI() : null,
                    request != null ? request.getMethod() : null,
                    HttpStatus.INTERNAL_SERVER_ERROR.value(),
                    currentUserId());
        } catch (Exception logFailure) {
            log.warn("error_log persist failed at commit boundary (swallowed): {}", logFailure.toString());
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail(ApiErrorBody.of("INTERNAL_ERROR", "An unexpected error occurred")));
    }

    /**
     * Best-effort principal id for the error-log row. Returns {@code null} for anonymous/unauthenticated
     * requests or if the security context is unavailable — never throws, since it runs on an
     * already-failing request path.
     */
    private String currentUserId() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof AuthPrincipal principal) {
                return principal.getUserId();
            }
        } catch (Exception ignored) {
            // fall through to null
        }
        return null;
    }

    private ApiErrorBody.FieldError toFieldError(FieldError fe) {
        return new ApiErrorBody.FieldError(fe.getField(), fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "Invalid");
    }
}
