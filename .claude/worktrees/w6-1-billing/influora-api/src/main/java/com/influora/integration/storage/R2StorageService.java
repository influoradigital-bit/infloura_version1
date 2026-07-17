package com.influora.integration.storage;

import com.influora.config.R2Properties;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;

/**
 * Cloudflare R2 (S3-compatible) — deliverable videos and other media.
 * Spec: BACKEND-API-SPEC.md §16, BACKEND-STACK.md §3.
 */
@Service
public class R2StorageService {

    private final R2Properties props;
    private final Optional<S3Client> s3Client;
    private final Optional<S3Presigner> presigner;

    public R2StorageService(
            R2Properties props,
            @Autowired(required = false) S3Client s3Client,
            @Autowired(required = false) S3Presigner presigner) {
        this.props = props;
        this.s3Client = Optional.ofNullable(s3Client);
        this.presigner = Optional.ofNullable(presigner);
    }

    public boolean isAvailable() {
        return props.isConfigured() && s3Client.isPresent() && presigner.isPresent();
    }

    public String publicUrl(String objectKey) {
        String base = props.getPublicUrl();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/" + objectKey;
    }

    public PresignResult presignPut(String objectKey, String contentType, long contentLength) {
        requireAvailable();
        if (contentLength > props.getMaxVideoBytes()) {
            throw new IllegalArgumentException(
                    "File exceeds maximum size of " + props.getMaxVideoBytes() + " bytes");
        }

        PutObjectRequest putObject =
                PutObjectRequest.builder()
                        .bucket(props.getBucketName())
                        .key(objectKey)
                        .contentType(contentType)
                        .contentLength(contentLength)
                        .build();

        PresignedPutObjectRequest presigned =
                presigner
                        .orElseThrow()
                        .presignPutObject(
                                PutObjectPresignRequest.builder()
                                        .signatureDuration(Duration.ofSeconds(props.getPresignExpirySeconds()))
                                        .putObjectRequest(putObject)
                                        .build());

        Instant expiresAt = Instant.now().plusSeconds(props.getPresignExpirySeconds());

        return new PresignResult(
                presigned.url().toString(),
                objectKey,
                props.getBucketName(),
                expiresAt,
                props.getMaxVideoBytes());
    }

    public record PresignResult(
            String uploadUrl, String key, String bucket, Instant expiresAt, long maxSize) {}

    /**
     * Direct server-side PUT of already-in-memory bytes (e.g. a generated contract PDF). Unlike
     * {@link #presignPut}, this is not a client-upload flow — the caller already has the object
     * bytes and just needs them written to R2 synchronously.
     */
    public void putBytes(String objectKey, byte[] bytes, String contentType) {
        requireAvailable();
        PutObjectRequest putObject =
                PutObjectRequest.builder()
                        .bucket(props.getBucketName())
                        .key(objectKey)
                        .contentType(contentType)
                        .contentLength((long) bytes.length)
                        .build();
        s3Client.orElseThrow().putObject(putObject, RequestBody.fromBytes(bytes));
    }

    /**
     * Stream an object to R2 without buffering the full payload in heap (Kabir M-19-3). Callers
     * should wrap {@code input} with a size-capped stream (e.g. {@code LimitedInputStream}) and
     * pass the declared {@code contentLength}.
     *
     * <p>Returns the S3-compatible ETag (D6/N2 — {@code UploadService} persists it on the {@code
     * file_uploads} row). Pre-existing callers that predate this return value (e.g. {@code
     * PortfolioService#streamCoverToR2}) simply don't capture it — source-compatible change.
     */
    public String putStream(String objectKey, InputStream input, long contentLength, String contentType) {
        requireAvailable();
        if (contentLength < 0) {
            throw new IllegalArgumentException("contentLength must be >= 0");
        }
        if (contentLength > props.getMaxVideoBytes()) {
            throw new IllegalArgumentException(
                    "File exceeds maximum size of " + props.getMaxVideoBytes() + " bytes");
        }
        PutObjectRequest putObject =
                PutObjectRequest.builder()
                        .bucket(props.getBucketName())
                        .key(objectKey)
                        .contentType(contentType)
                        .contentLength(contentLength)
                        .build();
        PutObjectResponse response =
                s3Client.orElseThrow().putObject(putObject, RequestBody.fromInputStream(input, contentLength));
        return response.eTag();
    }

    /**
     * Presigned, time-limited GET download URL for a stored object (e.g. the secure contract PDF
     * download link emailed to brand + creator — MSG91 has no attachment support, so we only ever
     * send a link, never the file itself).
     */
    public PresignResult presignGet(String objectKey) {
        requireAvailable();
        GetObjectRequest getObject =
                GetObjectRequest.builder().bucket(props.getBucketName()).key(objectKey).build();

        PresignedGetObjectRequest presigned =
                presigner
                        .orElseThrow()
                        .presignGetObject(
                                GetObjectPresignRequest.builder()
                                        .signatureDuration(Duration.ofSeconds(props.getPresignExpirySeconds()))
                                        .getObjectRequest(getObject)
                                        .build());

        Instant expiresAt = Instant.now().plusSeconds(props.getPresignExpirySeconds());
        return new PresignResult(
                presigned.url().toString(), objectKey, props.getBucketName(), expiresAt, 0L);
    }

    /**
     * Deletes an object from R2 (e.g. an orphaned deliverable). Idempotent by nature — an S3
     * delete of a missing key is a no-op; the caller already wraps this in try/catch.
     */
    public void deleteObject(String objectKey) {
        requireAvailable();
        s3Client
                .orElseThrow()
                .deleteObject(
                        DeleteObjectRequest.builder().bucket(props.getBucketName()).key(objectKey).build());
    }

    private void requireAvailable() {
        if (!isAvailable()) {
            throw new IllegalStateException(
                    "Cloudflare R2 is not configured. Set R2_ACCOUNT_ID, R2_ACCESS_KEY_ID, R2_SECRET_ACCESS_KEY.");
        }
    }
}
