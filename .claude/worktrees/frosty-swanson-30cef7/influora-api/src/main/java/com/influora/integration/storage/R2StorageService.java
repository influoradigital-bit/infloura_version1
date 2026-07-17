package com.influora.integration.storage;

import com.influora.config.R2Properties;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
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

    private void requireAvailable() {
        if (!isAvailable()) {
            throw new IllegalStateException(
                    "Cloudflare R2 is not configured. Set R2_ACCOUNT_ID, R2_ACCESS_KEY_ID, R2_SECRET_ACCESS_KEY.");
        }
    }
}
