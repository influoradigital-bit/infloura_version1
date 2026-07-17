# Influora Backend Stack

**Locked decisions:** MySQL 8 + Cloudflare R2 (videos & files) + Spring Boot 3  
**API contract:** `docs/BACKEND-API-SPEC.md` · **Frontend client:** `src/lib/api.ts`

---

## 1. Overview

| Concern | Choice |
|---------|--------|
| API | Spring Boot 3.3+, Java 21, port **8080**, prefix **`/api/v1`** |
| Database | **MySQL 8.0+** — metadata, users, campaigns, deals, wallet |
| Object storage | **Cloudflare R2** — avatars, logos, KYC PDFs, **deliverable videos** |
| Migrations | Flyway (`influora-api/src/main/resources/db/migration/`) |
| IDs | ULID (26 chars) in DB and API |
| Email OTP | **MSG91 Email** (`token-auth` + template `otpman`) — `docs/MSG91-EMAIL-OTP.md` |
| Creator SMS OTP | **MSG91 SMS** (`auth-key`, `sender-id`, `route`) — separate from email |

MySQL holds **references** to R2 objects (`r2_key`, public `url`). File bytes never go through the JVM except small direct uploads (&lt; 10 MB, §16.3).

---

## 2. MySQL 8

### Local development (Docker)

```bash
docker run -d --name influora-mysql \
  -e MYSQL_ROOT_PASSWORD=root \
  -e MYSQL_DATABASE=influora \
  -e MYSQL_USER=influora \
  -e MYSQL_PASSWORD=influora \
  -p 3306:3306 \
  mysql:8.0
```

### Spring configuration

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/influora?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC
    username: influora
    password: influora
  jpa:
    hibernate:
      ddl-auto: validate   # Flyway owns schema
    properties:
      hibernate:
        dialect: org.hibernate.dialect.MySQLDialect
  flyway:
    enabled: true
    locations: classpath:db/migration
```

### JSON columns

Use MySQL `JSON` type for: `campaigns.platforms`, `content_types`, `objectives`, `requirements`, `hashtags`, `target_audience` (see spec §2). Hibernate maps with `@JdbcTypeCode(SqlTypes.JSON)`.

### Charset

Database and tables: `utf8mb4` / `utf8mb4_unicode_ci` (emoji in chat, creator bios).

---

## 3. Cloudflare R2 (videos & files)

R2 is **S3-compatible**. The backend uses the **AWS SDK for Java v2** (`S3Client`) with a custom endpoint.

### Setup checklist (Cloudflare dashboard)

1. Create bucket `influora-dev` / `influora-prod`.
2. **R2 → Manage R2 API tokens** → create token with Object Read & Write.
3. Optional: **Custom domain** for public reads (`r2.influora.com` → bucket).
4. CORS on bucket (for browser presigned PUT):

```json
[
  {
    "AllowedOrigins": ["http://localhost:5173", "https://app.influora.com"],
    "AllowedMethods": ["GET", "PUT", "HEAD"],
    "AllowedHeaders": ["*"],
    "ExposeHeaders": ["ETag"],
    "MaxAgeSeconds": 3600
  }
]
```

### Environment variables

| Variable | Description |
|----------|-------------|
| `R2_ACCOUNT_ID` | Cloudflare account ID |
| `R2_ACCESS_KEY_ID` | R2 API token access key |
| `R2_SECRET_ACCESS_KEY` | R2 API token secret |
| `R2_BUCKET_NAME` | e.g. `influora-prod` |
| `R2_ENDPOINT` | `https://{accountId}.r2.cloudflarestorage.com` |
| `R2_PUBLIC_URL` | Public base URL, e.g. `https://r2.influora.com` |
| `R2_PRESIGN_EXPIRY_SECONDS` | Default `900` |
| `R2_MAX_VIDEO_BYTES` | Default `524288000` (500 MB) |

### Upload flows (spec §16)

| Flow | Use case | Implementation |
|------|----------|----------------|
| **Presign + confirm** | Deliverable **videos**, large assets | `POST /uploads/presign` → client PUT to R2 → `POST /uploads/:fileId/confirm` |
| **Direct multipart** | Avatar, logo, small PDF (&lt; 10 MB) | `POST /uploads` → server streams to R2 |

**Video deliverable path (required):**

1. Brand/creator requests presign with `purpose: "deliverable"`, `mimeType: "video/mp4"`.
2. Backend validates size ≤ 500 MB, writes `file_uploads` row (`status=PENDING`), returns `uploadUrl` + `key`.
3. Client uploads directly to R2 (no Spring memory spike).
4. Client calls confirm with `etag`; backend marks `READY`, sets `url` = `{R2_PUBLIC_URL}/{key}`.
5. Optional: queue thumbnail generation → `thumbnails/{fileId}.jpg` on R2.

### Object key convention

```
avatars/users/{userId}/{ulid}.webp
logos/workspaces/{workspaceId}/{ulid}.png
deliverables/{workspaceId}/{collaborationId}/{ulid}.mp4
thumbnails/deliverables/{fileId}.jpg
documents/kyc/{workspaceId}/{ulid}.pdf
contracts/{collaborationId}/{contractId}.pdf
```

### `file_uploads` table (add in Flyway)

```sql
CREATE TABLE file_uploads (
  id            VARCHAR(26) PRIMARY KEY,
  owner_id      VARCHAR(26) NOT NULL,
  owner_type    ENUM('USER','WORKSPACE') NOT NULL,
  purpose       VARCHAR(32) NOT NULL,
  r2_bucket     VARCHAR(128) NOT NULL,
  r2_key        VARCHAR(512) NOT NULL,
  mime_type     VARCHAR(128) NOT NULL,
  size_bytes    BIGINT NOT NULL,
  etag          VARCHAR(128),
  public_url    VARCHAR(1024),
  thumbnail_key VARCHAR(512),
  status        ENUM('PENDING','READY','FAILED','DELETED') DEFAULT 'PENDING',
  created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_owner (owner_id, owner_type),
  INDEX idx_status (status)
);
```

---

## 4. Spring Boot module layout

```
influora-api/
├── pom.xml
├── .env.example
└── src/main/
    ├── java/com/influora/
    │   ├── InfluoraApiApplication.java
    │   ├── config/
    │   │   ├── R2Properties.java
    │   │   └── R2Config.java          # S3Client bean
    │   ├── integration/storage/
    │   │   └── R2StorageService.java  # presign, put, delete, public URL
    │   └── web/
    │       └── UploadController.java  # §16 (phase 1)
    └── resources/
        ├── application.yml
        ├── application-dev.yml
        └── db/migration/
```

### Dependencies (Maven)

- `spring-boot-starter-web`
- `spring-boot-starter-data-jpa`
- `spring-boot-starter-security` (later)
- `mysql-connector-j`
- `flyway-core` + `flyway-mysql`
- `software.amazon.awssdk:s3` (R2)
- `ulid-creator` or equivalent

---

## 5. What changed from the initial plan

| Before | Now |
|--------|-----|
| PostgreSQL optional | **MySQL 8 only** |
| Local disk upload stub | **R2 required**; presigned upload for videos |
| Node / Drizzle reference in spec §1 | **Spring Boot + JPA + Flyway** |

---

## 6. Next implementation steps

1. Run MySQL locally (Docker above).
2. Configure R2 credentials in `influora-api/.env` (from `.env.example`).
3. Apply Flyway `V1__core_schema.sql` (from spec §2 + `file_uploads`).
4. Implement `R2StorageService` + `POST /uploads/presign` and `POST /uploads/:fileId/confirm`.
5. Wire brand auth + campaigns (no local file storage).

---

*See also: `BACKEND-API-SPEC.md` §1, §16, §21*
