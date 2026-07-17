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

    @Transactional
    public UploadResponse upload(AuthPrincipal principal, MultipartFile file) {
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

        // [Flag for Priya/Kabir] publicUrl() assumes a public-read bucket base — fine for brand
        // logos, but a genuinely private-KYC-doc access model (signed, expiring, audit-logged
        // reads) is a real gap this endpoint doesn't close. Matches the existing `file_uploads`
        // schema's own `public_url` column naming, and is required here because the frontend
        // persists the returned `url` long-term into DB fields (gstinDocUrl/panDocUrl/selfieUrl) —
        // a short-lived presigned GET would silently rot.
        String url = r2StorageService.publicUrl(key);

        FileUpload record =
                FileUpload.create(
                        Ulids.newUlid(),
                        principal.getUserId(),
                        FileOwnerType.USER,
                        "GENERIC",
                        r2Properties.getBucketName(),
                        key,
                        sniffedMime,
                        size,
                        etag,
                        url);
        fileUploadRepository.save(record);

        return new UploadResponse(url, key);
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
