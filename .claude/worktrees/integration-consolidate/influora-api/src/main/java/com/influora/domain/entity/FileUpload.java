package com.influora.domain.entity;

import com.influora.domain.enums.FileOwnerType;
import com.influora.domain.enums.FileUploadStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * D6 (2026-07-15, Priya) — {@code file_uploads} (V1__file_uploads.sql) previously had a migration
 * and nothing else: no entity, no repository, no writer anywhere in the codebase. Decision was to
 * map it rather than leave it dead — this is now the metadata/persistence store backing
 * {@code POST /uploads} (N2, Wave 6), not a second parallel table.
 *
 * <p>Bytes live on Cloudflare R2 ({@link com.influora.integration.storage.R2StorageService}); this
 * row is the metadata record (owner, key, mime, size, scan/derived status).
 */
@Entity
@Table(name = "file_uploads")
public class FileUpload {

    @Id
    @Column(length = 26)
    private String id;

    @Column(name = "owner_id", nullable = false, length = 26)
    private String ownerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "owner_type", nullable = false)
    private FileOwnerType ownerType;

    @Column(nullable = false, length = 32)
    private String purpose;

    @Column(name = "r2_bucket", nullable = false, length = 128)
    private String r2Bucket;

    @Column(name = "r2_key", nullable = false, length = 512)
    private String r2Key;

    @Column(name = "mime_type", nullable = false, length = 128)
    private String mimeType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(length = 128)
    private String etag;

    @Column(name = "public_url", length = 1024)
    private String publicUrl;

    @Column(name = "thumbnail_key", length = 512)
    private String thumbnailKey;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private FileUploadStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected FileUpload() {}

    public static FileUpload create(
            String id,
            String ownerId,
            FileOwnerType ownerType,
            String purpose,
            String r2Bucket,
            String r2Key,
            String mimeType,
            long sizeBytes,
            String etag,
            String publicUrl) {
        FileUpload f = new FileUpload();
        f.id = id;
        f.ownerId = ownerId;
        f.ownerType = ownerType;
        f.purpose = purpose;
        f.r2Bucket = r2Bucket;
        f.r2Key = r2Key;
        f.mimeType = mimeType;
        f.sizeBytes = sizeBytes;
        f.etag = etag;
        f.publicUrl = publicUrl;
        f.status = FileUploadStatus.READY;
        Instant now = Instant.now();
        f.createdAt = now;
        f.updatedAt = now;
        return f;
    }

    public String getId() {
        return id;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public FileOwnerType getOwnerType() {
        return ownerType;
    }

    public String getPurpose() {
        return purpose;
    }

    public String getR2Bucket() {
        return r2Bucket;
    }

    public String getR2Key() {
        return r2Key;
    }

    public String getMimeType() {
        return mimeType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public String getEtag() {
        return etag;
    }

    public String getPublicUrl() {
        return publicUrl;
    }

    public String getThumbnailKey() {
        return thumbnailKey;
    }

    public FileUploadStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void markFailed() {
        this.status = FileUploadStatus.FAILED;
        this.updatedAt = Instant.now();
    }
}
