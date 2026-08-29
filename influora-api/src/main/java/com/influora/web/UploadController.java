package com.influora.web;

import com.influora.common.ApiResponse;
import com.influora.security.AuthPrincipal;
import com.influora.service.UploadService;
import com.influora.web.dto.upload.UploadDtos.UploadResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * N2 (Wave 6) — generic {@code POST /uploads}. No route existed at all before this pass; matches
 * src/lib/api.ts's {@code uploads.upload(file, role)} exactly (multipart field name {@code file},
 * either brand or creator bearer token — this endpoint itself doesn't branch on role, the caller's
 * JWT already carries {@code userType}).
 *
 * <p><b>[F-0390 D4]</b> {@code purpose} is a new, OPTIONAL multipart form field (plain string part,
 * same {@code @RequestParam(required = false)} convention {@code CreatorDeliverableController}
 * already uses for its {@code caption}/{@code creatorNotes} form fields — not {@code @RequestPart},
 * which is for parts needing message-converter deserialization). Omitted/blank preserves today's
 * exact behavior (public upload) for every existing caller (brand logo, portfolio cover, etc.) that
 * doesn't send it. {@link UploadService#uploadForPurpose} — not the client — decides public vs.
 * private storage from this value against a server-side allowlist; an unrecognized non-blank value
 * is REJECTED (400 {@code INVALID_UPLOAD_PURPOSE}), never silently treated as public.
 */
@RestController
@RequestMapping("/uploads")
public class UploadController {

    private final UploadService uploadService;

    public UploadController(UploadService uploadService) {
        this.uploadService = uploadService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<UploadResponse>> upload(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "purpose", required = false) String purpose) {
        UploadResponse response = uploadService.uploadForPurpose(principal, file, purpose);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }
}
