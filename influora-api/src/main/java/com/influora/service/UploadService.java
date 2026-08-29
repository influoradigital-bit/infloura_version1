package com.influora.service;

import com.influora.common.ApiException;
import com.influora.common.LimitedInputStream;
import com.influora.common.MediaMimeSniffer;
import com.influora.common.Ulids;
import com.influora.config.R2Properties;
import com.influora.domain.entity.FileUpload;
import com.influora.domain.enums.FileOwnerType;
import com.influora.integration.storage.R2StorageService;
import com.influora.repository.FileUploadRepository;
import com.influora.security.AuthPrincipal;
import com.influora.service.security.MalwareScanService;
import com.influora.web.dto.upload.UploadDtos.UploadResponse;
import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * N2 (Wave 6) — generic {@code POST /uploads}, previously entirely unmapped (src/lib/api.ts's
 * {@code uploads.upload()} helper had nothing on the other end). Backs both brand logo uploads and
 * creator KYC/selfie uploads (brand-kyc-prompt.tsx, and the deferred creator KYC step) — same
 * generic client-side helper for both.
 *
 * <p>D6 (2026-07-15, Priya) — persists metadata into the previously-orphaned {@code file_uploads}
 * table (V1__file_uploads.sql; now mapped by {@link FileUpload}/{@link FileUploadRepository})
 * instead of a second parallel store.
 *
 * <p>Same magic-byte-sniff / malware-scan / stream-to-R2 discipline as {@code
 * PortfolioService#uploadCover} (Kabir M-K6-C3-3/C3-4), extended to also accept PDF (GST/PAN
 * documents are commonly scanned as PDF, not just images).
 */
@Service
public class UploadService {

    /** Generic cap for logos/KYC docs/selfies — same as PortfolioService's cover-image cap. Not for video. */
    static final long MAX_BYTES = 10_485_760L;

    /**
     * [F-0390 D4] Server-side allowlist of upload {@code purpose} values that MUST route through
     * {@link #uploadPrivate} — every identity/KYC document this generic upload endpoint accepts.
     * The three real KYC document uploads in this codebase: creator selfie ({@code
     * CreatorOnboardingService#submitKyc} -> {@code CreatorProfile.selfieUrl}) and brand GSTIN/PAN
     * docs ({@code OnboardingService#submitBrandKyc} -> {@code Workspace.kycGstinDocUrl}/{@code
     * .kycPanDocUrl}). Deliberately an allowlist, not a denylist — an unrecognized purpose is
     * rejected (see {@link #uploadForPurpose}) rather than assumed safe to publish.
     */
    static final Set<String> KYC_UPLOAD_PURPOSES =
            Set.of("creator_kyc_selfie", "brand_kyc_gstin_doc", "brand_kyc_pan_doc");

    private final R2StorageService r2StorageService;
    private final R2Properties r2Properties;
    private final MalwareScanService malwareScanService;
    private final FileUploadRepository fileUploadRepository;

    public UploadService(
            R2StorageService r2StorageService,
            R2Properties r2Properties,
            MalwareScanService malwareScanService,
            FileUploadRepository fileUploadRepository) {
        this.r2StorageService = r2StorageService;
        this.r2Properties = r2Properties;
        this.malwareScanService = malwareScanService;
        this.fileUploadRepository = fileUploadRepository;
    }

    /**
     * [F-0390 D4] Purpose-aware entrypoint — {@code UploadController} routes {@code POST /uploads}
     * through this method (not {@link #upload(AuthPrincipal, MultipartFile)} directly) so every
     * future KYC purpose is enforced at one call site instead of trusting each caller to remember.
     * The SERVER decides public vs. private from {@code purpose} against {@link
     * #KYC_UPLOAD_PURPOSES} — never the client. {@code purpose} absent/blank preserves today's
     * exact public-upload behavior (backward compatible with every existing caller — brand logo,
     * portfolio cover, etc. — that never sends it and doesn't need to). A non-blank, unrecognized
     * purpose is REJECTED rather than silently defaulting to public: a typo or a malicious client
     * must never be able to downgrade a KYC upload's privacy by mis-naming the purpose.
     */
    @Transactional
    public UploadResponse uploadForPurpose(AuthPrincipal principal, MultipartFile file, String purpose) {
        String normalized = purpose == null ? null : purpose.trim().toLowerCase();
        if (normalized == null || normalized.isBlank()) {
            return upload(principal, file);
        }
        if (KYC_UPLOAD_PURPOSES.contains(normalized)) {
            return uploadPrivate(principal, file);
        }
        throw new ApiException(
                "INVALID_UPLOAD_PURPOSE", "Unknown upload purpose: " + normalized, HttpStatus.BAD_REQUEST);
    }

    @Transactional
    public UploadResponse upload(AuthPrincipal principal, MultipartFile file) {
        UploadedObject stored = validateAndStore(principal, file);

        // publicUrl() assumes a public-read bucket base — correct for this method's remaining
        // callers (brand logo, portfolio cover, etc.): genuinely public assets. [F-0390 D3/D4] KYC
        // documents no longer reach this branch — UploadController routes them through {@link
        // #uploadForPurpose} -> {@link #uploadPrivate} instead, based on the server-validated
        // `purpose` allowlist, never a client choice.
        String url = r2StorageService.publicUrl(stored.key());
        persistMetadata(principal, stored, url);
        return new UploadResponse(url, stored.key());
    }

    /**
     * [F-0390 D3/D4] Private-asset counterpart to {@link #upload} — same validation/malware-scan/
     * R2-stream pipeline (via {@link #validateAndStore}), but never calls {@link
     * R2StorageService#publicUrl}: the persisted {@code file_uploads.public_url} column, and the
     * {@code key} this returns, are both the bare R2 object key (never a permanent public URL). The
     * {@code url} field returned here is a short-lived presigned GET (same {@link
     * R2StorageService#presignGet} the established pattern already uses — see {@code
     * CreatorDeliverableService#uploadProof}/{@code PortfolioService#resolveCoverUrl}) for
     * immediate client-side preview ONLY; callers must persist the {@code key}, not this {@code
     * url}, anywhere they store a long-lived reference (mirrors what those two established callers
     * already do).
     *
     * <p><b>Wired</b> (D4): {@link #uploadForPurpose} routes every {@link #KYC_UPLOAD_PURPOSES}
     * purpose here. The FE change needed to actually send a private KYC upload through {@code POST
     * /uploads} — and to persist the response's {@code key} (not {@code url}) into {@code
     * gstinDocUrl}/{@code panDocUrl}/{@code selfieUrl} — is reported separately (frontend files are
     * outside this task's scope); the write-side onboarding paths ({@code
     * OnboardingService#submitBrandKyc}, {@code CreatorOnboardingService#submitKyc}) already persist
     * whatever string they're given verbatim, so they needed no code change for this. Read-side key
     * resolution (tolerating both a legacy full URL and a new bare key, mirroring {@code
     * PortfolioService#resolveCoverUrl}/{@code toCoverObjectKey}) is implemented in {@code
     * OnboardingService#resolveKycDocUrl}/{@code CreatorOnboardingService#resolveKycDocUrl} — see
     * those methods' javadoc for why nothing currently calls them (no existing response DTO exposes
     * these fields back to a client today; confirmed by an exhaustive repo search, reported rather
     * than fabricated a consumer).
     *
     * <p>{@code R2StorageService#presignPut} is separately noted (F-0390 audit) as dead code with
     * zero callers — NOT wired up here per the brief; this method still uses the existing
     * server-side {@code putStream} upload path, not a client-direct presigned PUT.
     */
    @Transactional
    public UploadResponse uploadPrivate(AuthPrincipal principal, MultipartFile file) {
        UploadedObject stored = validateAndStore(principal, file);
        String previewUrl =
                r2StorageService.isAvailable() ? r2StorageService.presignGet(stored.key()).uploadUrl() : null;
        // Never persist a permanent public URL for a private asset — public_url stays null/absent
        // (or could store the bare key too; null is clearer that this row is not publicly served).
        persistMetadata(principal, stored, null);
        return new UploadResponse(previewUrl, stored.key());
    }

    private record UploadedObject(String key, String mime, long size, String etag) {}

    private UploadedObject validateAndStore(AuthPrincipal principal, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ApiException("INVALID_FILE", "A file is required", HttpStatus.BAD_REQUEST);
        }
        if (!r2StorageService.isAvailable()) {
            throw new ApiException(
                    "STORAGE_UNAVAILABLE", "File storage is not configured", HttpStatus.SERVICE_UNAVAILABLE);
        }
        if (file.getSize() > MAX_BYTES) {
            throw new ApiException(
                    "FILE_TOO_LARGE",
                    "File exceeds maximum size of " + MAX_BYTES + " bytes",
                    HttpStatus.BAD_REQUEST);
        }

        String sniffedMime = sniffMime(file);
        validateMime(file, sniffedMime);

        // Kabir M-K6-C3-3 — malware gate before anything touches R2. NoOpMalwareScanService
        // (accepted-risk stub) outside prod; ClamAvMalwareScanService (S7) in prod.
        malwareScanService.requireClean(file, "generic-upload");

        String key =
                "uploads/"
                        + principal.getUserType().name().toLowerCase()
                        + "/"
                        + principal.getUserId()
                        + "/"
                        + Ulids.newUlid()
                        + extensionFor(sniffedMime);

        long size = file.getSize();
        String etag;
        try (InputStream raw = file.getInputStream();
                LimitedInputStream limited = new LimitedInputStream(raw, MAX_BYTES)) {
            etag = r2StorageService.putStream(key, limited, size, sniffedMime);
        } catch (IOException e) {
            throw new ApiException("UPLOAD_FAILED", "Failed to upload file", HttpStatus.INTERNAL_SERVER_ERROR);
        } catch (IllegalArgumentException e) {
            throw new ApiException("FILE_TOO_LARGE", e.getMessage(), HttpStatus.BAD_REQUEST);
        }

        return new UploadedObject(key, sniffedMime, size, etag);
    }

    private void persistMetadata(AuthPrincipal principal, UploadedObject stored, String publicUrl) {
        FileUpload record =
                FileUpload.create(
                        Ulids.newUlid(),
                        principal.getUserId(),
                        FileOwnerType.USER,
                        "GENERIC",
                        r2Properties.getBucketName(),
                        stored.key(),
                        stored.mime(),
                        stored.size(),
                        stored.etag(),
                        publicUrl);
        fileUploadRepository.save(record);
    }

    private static String sniffMime(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            String sniffed = MediaMimeSniffer.detectMimeType(in);
            if (sniffed != null) {
                return sniffed;
            }
        } catch (IOException e) {
            throw new ApiException("INVALID_FILE_TYPE", "Unable to read file content", HttpStatus.BAD_REQUEST);
        }
        try (InputStream in = file.getInputStream()) {
            byte[] header = in.readNBytes(4);
            if (header.length == 4
                    && header[0] == '%'
                    && header[1] == 'P'
                    && header[2] == 'D'
                    && header[3] == 'F') {
                return "application/pdf";
            }
        } catch (IOException e) {
            throw new ApiException("INVALID_FILE_TYPE", "Unable to read file content", HttpStatus.BAD_REQUEST);
        }
        return null;
    }

    private static void validateMime(MultipartFile file, String sniffedMime) {
        String declared = file.getContentType();
        if (sniffedMime == null) {
            throw new ApiException(
                    "INVALID_FILE_TYPE",
                    "Unsupported file type — only images and PDF documents are allowed",
                    HttpStatus.BAD_REQUEST);
        }
        if ("application/pdf".equals(sniffedMime)) {
            if (declared == null || !declared.equals("application/pdf")) {
                throw new ApiException(
                        "INVALID_FILE_TYPE",
                        "Declared content type does not match file content",
                        HttpStatus.BAD_REQUEST);
            }
            return;
        }
        if (declared == null
                || !declared.startsWith("image/")
                || !MediaMimeSniffer.mimeTypesCompatible(declared, sniffedMime)) {
            throw new ApiException(
                    "INVALID_FILE_TYPE",
                    "Declared content type does not match file content",
                    HttpStatus.BAD_REQUEST);
        }
    }

    private static String extensionFor(String mime) {
        return switch (mime) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/gif" -> ".gif";
            case "image/webp" -> ".webp";
            case "application/pdf" -> ".pdf";
            default -> "";
        };
    }
}
