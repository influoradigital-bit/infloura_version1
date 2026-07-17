# Feature: Uploads & Storage

**Business Purpose** — Handles all binary content: creator deliverable media (video/image), proof screenshots, portfolio covers, and generated PDFs (contracts/invoices). Media is stored on **Cloudflare R2**; MySQL keeps only R2 keys + metadata. Downloads are always short-lived presigned URLs.

**Who uses it** — Creators (upload media/proof/cover), brands (view via presigned URLs), the system (PDF generation, cleanup job).

## User Roles
Creator (upload), Brand (download review media), System (PDFs, cleanup).

## Permissions
Uploads are scoped to the owning creator/collaboration; object keys are ownership/traversal-validated.

## Business Flow
```
Creator uploads deliverable files (multipart) → MIME sniff + malware scan → stream to R2 → keys in files_json
Download → presigned GET (15 min) resolved from stored key
PDFs (contract/invoice) → putBytes to R2 → pdf_r2_key → presigned GET
Cleanup job → delete superseded/abandoned keys (dry-run default, escrow-guarded)
```

## Frontend
- **Real transport**: `http.upload` in `src/lib/api.ts` (FormData, field `file`).
- **Mock**: `src/lib/upload.ts` (fake progress/URLs — not the real path).
- **Uploaders**: `creator/deal-room/{deliverable-submission,revision-handler}`, brand `DeliverableViewer`.

## Backend
- **Service**: `integration/storage/R2StorageService` (`presignGet`, `putStream`, `putBytes`, `deleteObject`).
- **Config**: `config/{R2Config,R2Properties}`.
- **Guards**: `MediaMimeSniffer`, `MalwareScanService`, `common/ProofObjectKeys`, `LimitedInputStream`.
- Uploads are multipart methods on domain controllers (deliverables, portfolio) — **no generic UploadController**.

## Database
`deliverables.files_json` (V37, active media metadata), `deliverable_metrics.proof_screenshot_r2_key` (V19), contract/invoice `pdf_r2_key`, creator cover key. `file_uploads` (V1) is orphaned. See [../database.md](../database.md).

## APIs
`POST /creator/deliverables/{id}/upload|proof`, `POST /me/portfolio/cover`, contract/invoice PDF download URLs. (Frontend `uploads.upload` → `POST /uploads` has **no backend controller**.)

## AI
Not involved.

## Notifications
None specific.

## Dependencies
- **Depends on**: Cloudflare R2.
- **Depended on by**: deliverables, portfolio, contracts, invoicing.

## Connected Files
`R2StorageService`, `R2Config`, `R2Properties`, `MediaMimeSniffer`, `MalwareScanService`, `ProofObjectKeys`; frontend `lib/api.ts` upload, uploader components.

## Execution Flow
```
Upload: POST .../upload (multipart) → CreatorDeliverableService → size cap + MIME sniff + malware scan
  → putStream (LimitedInputStream + DigestInputStream MD5) → R2 key stored in files_json
Download: resolve key → presignGet (900s) → client GET (403 retry on expiry)
```

## Error Handling
`STORAGE_UNAVAILABLE` (503, R2 not configured), `FILE_TOO_LARGE`. Key schemes validated for traversal/ownership.

## Security
Media never in MySQL; downloads via short-lived presigned GET; magic-byte MIME check; malware scan; ownership-bound proof keys; stored URLs never URL-decoded.

## Performance
Streaming uploads (never buffer whole file); presigned downloads offload to Cloudflare; cleanup dry-run default.

## Testing
`MultipartConfigTest` (500MB/1GB limits), storage tests. Regression risks: size caps, key validation, presign resolution.

## Production Readiness
- **Health**: 7/10 · **Completion**: ~78%
- **Known issues**: `presignPut` is dead code (no client-PUT flow); `file_uploads` table orphaned; `src/lib/upload.ts` is mock; `POST /uploads` has no controller. See [../known-limitations.md](../known-limitations.md).
- **Last verified**: 2026-07-15
