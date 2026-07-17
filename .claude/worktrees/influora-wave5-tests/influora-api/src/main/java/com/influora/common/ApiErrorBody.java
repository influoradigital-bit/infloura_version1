package com.influora.common;

import java.util.List;

public record ApiErrorBody(
        String code,
        String message,
        String field,
        List<FieldError> fields) {

    public record FieldError(String field, String message) {}

    public static ApiErrorBody of(String code, String message) {
        return new ApiErrorBody(code, message, null, null);
    }

    public static ApiErrorBody validation(String message, List<FieldError> fields) {
        return new ApiErrorBody("VALIDATION_ERROR", message, null, fields);
    }
}
