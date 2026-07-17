package com.influora.common;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

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

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneric(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail(ApiErrorBody.of("INTERNAL_ERROR", "An unexpected error occurred")));
    }

    private ApiErrorBody.FieldError toFieldError(FieldError fe) {
        return new ApiErrorBody.FieldError(fe.getField(), fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "Invalid");
    }
}
