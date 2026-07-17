package com.influora.common;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiResponse<Void>> handleApi(ApiException ex) {
        return ResponseEntity.status(ex.getStatus())
                .body(ApiResponse.fail(ApiErrorBody.of(ex.getCode(), ex.getMessage())));
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

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneric(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail(ApiErrorBody.of("INTERNAL_ERROR", "An unexpected error occurred")));
    }

    private ApiErrorBody.FieldError toFieldError(FieldError fe) {
        return new ApiErrorBody.FieldError(fe.getField(), fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "Invalid");
    }
}
