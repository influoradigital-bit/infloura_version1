# Feature: Deliverables

**Business Purpose** — Models the content a creator owes a brand inside a collaboration: upload → brand review → go live → platform verification of performance. Deliverable approval and verification are the triggers that justify releasing escrowed money.

**Who uses it** — Creators (upload/submit/report), Brands (review/approve/revise), and the verification job.

## User Roles
Creator (upload, submit, report metrics, mark posted, upload proof), Brand (view, approve, request revision).

## Permissions
Service-layer scoping (creator via `CreatorContextService`, brand via `findByIdAndWorkspaceId`). Foreign scope → 404.

## Business Flow
```
PENDING → upload (DRAFT, v++) → submit (SUBMITTED / RESUBMITTED)
  → brand approve (APPROVED) or revise (REVISION_REQUESTED, loop) → mark posted (POSTED)
  → report metrics (METRICS_REPORTED) → verification job (VERIFIED)
```
`DeliverableStatus` (10): PENDING, DRAFT, SUBMITTED, REVISION_REQUESTED, RESUBMITTED, APPROVED, REJECTED (unreached), POSTED, METRICS_REPORTED, VERIFIED. `DeliverableType`: Instagram/YouTube/Facebook/TikTok variants.

## Frontend
- **Brand**: `components/brand/deliverables/DeliverableViewer`, `hooks/brand/useDeliverableDetail`, deal-deliverables-tab.
- **Creator**: `components/creator/deal-room/{deliverable-submission,metrics-report-form,revision-handler}`.

## Backend
- **Controllers**: `BrandDeliverableController` (`/deliverables`), `CreatorDeliverableController` (`/creator/deliverables`), `DeliverableMetricController`.
- **Services**: `CreatorDeliverableService`, `BrandDeliverableService`, `DeliverableMetricService`, `service/verification/DeliverableVerificationService`, `PostUrlIdentifier`.

## Database
`deliverables` (V37, `files_json` holds R2 keys, `slot_index` unique per collab), `deliverable_metrics` (V19, +V20260713120000 verification: `source`, `platform_media_id`, `verified_at`). See [../database.md](../database.md).

## APIs
Creator: `GET /creator/deliverables`, `POST .../upload|submit|metrics|proof|mark-posted`, `GET .../status`. Brand: `GET /deliverables/{id}`, `POST .../approve|revise`. Legacy: `PUT /deliverables/{milestoneId}/metrics`.

## AI
Not directly; verification uses Meta Instagram insights (not the LLM).

## Notifications
`DeliverableSubmittedEvent` → `brand.deliverable_ready`.

## Dependencies
- **Depends on**: collaborations/contracts/milestones, R2 (media), Meta (verification).
- **Depended on by**: escrow release (approval/verification), analytics (campaign aggregate), disputes.

## Connected Files
`BrandDeliverableController`, `CreatorDeliverableController`, `DeliverableMetricController`, `CreatorDeliverableService`, `BrandDeliverableService`, `DeliverableVerificationService`, `PostUrlIdentifier`, `domain/entity/{Deliverable,DeliverableMetric}`, `job/DeliverableVerificationJob`, `job/DeliverableCleanupJob`.

## Execution Flow
```
Upload: POST /creator/deliverables/{id}/upload (multipart) → CreatorDeliverableService
  → size caps (500MB/file, 1GB/batch) + MIME sniff + malware scan → stream to R2 (keys in files_json) → DRAFT, v++
Verify: DeliverableVerificationJob (every 6h :30) → candidates POSTED/METRICS_REPORTED with postUrl
  → PostUrlIdentifier → Instagram insights match by shortcode → persistVerified (source=PLATFORM_VERIFIED)
```

## Error Handling
`INVALID_STATE` (409, wrong-state approve/revise), `FILE_TOO_LARGE`, `STORAGE_UNAVAILABLE` (503). Verification is **fail-closed** (never throws) with 11 outcomes (`VERIFIED` + 10 `FALLBACK_*`, e.g. `FALLBACK_YOUTUBE_UNSUPPORTED`). Metric honesty: a self-report can never overwrite `PLATFORM_VERIFIED` data.

## Security
Ownership-bound proof keys (`proof/{userId}/{deliverableId}/...`); MIME magic-byte check; malware scan; stored post URLs never URL-decoded (inert).

## Performance
Streaming uploads (never buffers whole file); verification job offset schedule; cleanup job dry-run default, escrow-guarded.

## Testing
`MultipartConfigTest` locks upload limits; verification service tests. Regression risks: state gates, verified-overwrite rule, cleanup guards.

## Production Readiness
- **Health**: 8/10 · **Completion**: ~80%
- **Known issues**: no YouTube verification; `REJECTED` status never entered; per-post `media_metrics` polling not wired.
- **Last verified**: 2026-07-15
