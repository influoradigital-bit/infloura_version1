package com.influora.web.dto.upload;

public final class UploadDtos {

    private UploadDtos() {}

    /** POST /uploads response — matches src/lib/api.ts `uploads.upload` exactly: `{ url, key }`. */
    public record UploadResponse(String url, String key) {}
}
