package com.influora.common;

import java.time.Instant;

public record ApiResponse<T>(boolean success, T data, ApiErrorBody error, PageMeta meta, Instant timestamp) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null, null, Instant.now());
    }

    public static <T> ApiResponse<T> ok(T data, PageMeta meta) {
        return new ApiResponse<>(true, data, null, meta, Instant.now());
    }

    public static <T> ApiResponse<T> fail(ApiErrorBody error) {
        return new ApiResponse<>(false, null, error, null, Instant.now());
    }
}
