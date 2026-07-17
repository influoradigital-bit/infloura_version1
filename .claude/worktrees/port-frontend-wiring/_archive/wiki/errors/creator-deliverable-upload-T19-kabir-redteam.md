# Creator Deliverable Upload — Task #19 (Kabir Red-Team)

**Auditor:** Kabir Singh (Offensive Security / Red-Team Lead)  
**Date:** 2026-07-09  
**Scope:** `CreatorDeliverableController.java`, `CreatorDeliverableService.java`, `DeliverableRepository.findByIdAndCreatorUserId`, `R2StorageService.putBytes` / `publicUrl`, `R2Properties.maxVideoBytes`, `CreatorDeliverableServiceTest` (7), `CreatorDeliverableControllerTest` (2), `V37__deliverables.sql`  
**Reference Spec:** `wiki/tech/creator/09_CREATOR_DELIVERABLES_SPEC.md` §4.3, §7.1; `wiki/tech/creator/12_CREATOR_SECURITY_SPEC.md` §5.1, §6.1; Task #11 `wiki/errors/creator-context-service-T11-kabir-redteam.md` (GO pattern)

---

## Executive Summary

**VERDICT: ✅ PASS** (H-19-1 + M-19-1 closure re-verified 2026-07-09 ~18:00 IST)

Task #19's primary upload-security invariants hold:

1. **No IDOR** — both routes resolve deliverable scope exclusively via `DeliverableRepository.findByIdAndCreatorUserId(deliverableId, principal.getUserId())` (collaboration join-through on `collaborations.creator_id`). Foreign deliverable probes return uniform `404 DELIVERABLE_NOT_FOUND`. Matches audited `ContractRepository.findByIdAndCreatorId` / Task #11 pattern.
2. **R2 path traversal closed** — object keys are server-composed (`deliverables/{id}/v{n}/{ulid}-{safeName}`); `sanitizeFileName()` strips directory segments and non-alphanumeric chars from the basename. User input never controls bucket prefix or parent path segments.
3. **MIME allowlist + content sniffing** — `validateMime()` enforces declared `image/`/`video/` prefix, magic-byte detection via `MediaMimeSniffer.detectMimeType()`, and family compatibility before R2 write; ZIP payload with `Content-Type: video/mp4` rejected (`testUploadMimeSpoofRejected`).
4. **Size limits aligned servlet ↔ service** — `spring.servlet.multipart` **500MB/file, 1GB/request** in `application.yml` (env-overridable); service caps 500MB/file (`r2Properties.getMaxVideoBytes()`) + 1GB batch (`MAX_TOTAL_BYTES`) enforced before `putBytes`. `MultipartConfigTest` asserts servlet limits.

**Closed on re-review (2026-07-09):**

- **H-19-1 (HIGH):** ✅ **CLOSED** — `application.yml` L21–27; `application-dev.yml` does not override; `MultipartConfigTest` verifies 500MB/1GB.
- **M-19-1 (MEDIUM):** ✅ **CLOSED** — `MediaMimeSniffer` + `validateMime()` content-byte validation per spec §5.1; `MediaMimeSnifferTest` (4) + `testUploadMimeSpoofRejected`.

**Remaining pre-prod gaps (unchanged):**

- **M-19-2 (MEDIUM):** No per-creator upload rate limit — `12_CREATOR_SECURITY_SPEC.md` §6.1 specifies **10 uploads/minute**; `AuthRateLimitFilter` returns `null` for `/creator/deliverables/**`.
- **M-19-3 (MEDIUM):** `file.getBytes()` buffers entire upload in heap (up to 1GB batch) — memory-exhaustion DoS vector for authenticated creators.
- **M-19-4 (MEDIUM):** Draft deliverables stored at **permanent public R2 URLs** via `publicUrl()` — spec §7.1 requires signed URLs with expiration. ULID keys are unguessable but URLs leak to any party receiving API responses (creator client, logs, future brand review surface).

No Critical findings. **H-19-1 + M-19-1 closure: PASS.** Meera scoped unit-test gate **15/15**. **Production deploy of deliverable upload still NO-GO** until M-19-2 + M-19-3 + M-19-4 are addressed.

---

## 1. IDOR — `findByIdAndCreatorUserId`

### 1a. Repository scoping

```16:20:influora-api/src/main/java/com/influora/repository/DeliverableRepository.java
  @Query(
      "SELECT d FROM Deliverable d WHERE d.id = :id AND d.collaborationId IN "
          + "(SELECT co.id FROM Collaboration co WHERE co.creatorId = :creatorUserId)")
  Optional<Deliverable> findByIdAndCreatorUserId(
      @Param("id") String id, @Param("creatorUserId") String creatorUserId);
```

- Ownership is one hop: `deliverables.collaboration_id` → `collaborations.creator_id` (user id, not profile id).
- Mirrors `ContractRepository.findByIdAndCreatorId` and `CollaborationRepository.findByIdAndCreatorId` — consistent with Task #10 H-1 fix pattern.

### 1b. Service enforcement

```129:137:influora-api/src/main/java/com/influora/service/CreatorDeliverableService.java
    private Deliverable requireOwnedDeliverable(AuthPrincipal principal, String deliverableId) {
        return deliverableRepository
                .findByIdAndCreatorUserId(deliverableId, principal.getUserId())
                .orElseThrow(
                        () ->
                                new ApiException(
                                        "DELIVERABLE_NOT_FOUND",
                                        "Deliverable not found",
                                        HttpStatus.NOT_FOUND));
    }
```

- `principal.getUserId()` is the **only** owner key — no path-param creator id, no body field.
- `creatorContext.requireCreatorProfile(principal)` runs first (creator-only + profile existence).

### 1c. IDOR exploit matrix

| Attack | Result |
|---|---|
| Creator A uploads to Creator B's `deliverableId` | **BLOCKED** — `404 DELIVERABLE_NOT_FOUND` (`testUploadForeignDeliverable`) |
| Creator A reads Creator B's status | **BLOCKED** — `404 DELIVERABLE_NOT_FOUND` (`testGetStatusForeign`) |
| Brand JWT on upload/status | **BLOCKED** — `403 WRONG_USER_TYPE` at `requireCreatorProfile` |
| Unauthenticated request | **BLOCKED** — `SecurityConfig` `anyRequest().authenticated()` |
| Spoof `creator_profile_id` on deliverable row | **N/A** — auth uses collaboration join, not `creator_profile_id` column |

**IDOR: CLOSED.**

---

## 2. File MIME Allowlist

### 2a. Implementation

```39:40:influora-api/src/main/java/com/influora/service/CreatorDeliverableService.java
    private static final Set<String> ALLOWED_MIME_PREFIXES = Set.of("image/", "video/");
```

```214:239:influora-api/src/main/java/com/influora/service/CreatorDeliverableService.java
    private static void validateMime(MultipartFile file) {
        String declared = file.getContentType();
        if (declared == null || ALLOWED_MIME_PREFIXES.stream().noneMatch(declared::startsWith)) {
            throw new ApiException(
                    "INVALID_FILE_TYPE",
                    "Only image and video files are allowed",
                    HttpStatus.BAD_REQUEST);
        }
        try {
            String sniffed = MediaMimeSniffer.detectMimeType(file.getInputStream());
            if (!MediaMimeSniffer.isAllowedMediaMime(sniffed)) {
                throw new ApiException(
                        "INVALID_FILE_TYPE",
                        "File content does not match an allowed image or video type",
                        HttpStatus.BAD_REQUEST);
            }
            if (!MediaMimeSniffer.mimeTypesCompatible(declared, sniffed)) {
                throw new ApiException(
                        "INVALID_FILE_TYPE",
                        "Declared content type does not match file content",
                        HttpStatus.BAD_REQUEST);
            }
        } catch (IOException e) {
            throw new ApiException(
                    "INVALID_FILE_TYPE", "Unable to read file content", HttpStatus.BAD_REQUEST);
        }
    }
```

- Applied to every content file and optional thumbnail before R2 write.
- Unit tests: `application/x-msdownload` → `INVALID_FILE_TYPE`; ZIP + `video/mp4` header → `INVALID_FILE_TYPE` (`testUploadMimeSpoofRejected`).

### 2b. Gaps vs spec

| Check | Spec (`12_CREATOR_SECURITY_SPEC.md` §5.1) | Implemented | Gap |
|---|---|---|---|
| MIME from content bytes | `detectMimeType(inputStream)` | `MediaMimeSniffer.detectMimeType()` + family cross-check | ✅ **CLOSED** (M-19-1) |
| Explicit extension whitelist | jpg, png, gif, mp4, mov | Prefix `image/` / `video/` only | Accepts `image/svg+xml`, `video/x-ms-wmv`, etc. |
| Virus scan | Required | Not implemented | Deferred (spec §7.1) — track for prod |
| EXIF strip | Required for images | Not implemented | Deferred — track for prod |

**Re-attack (MIME spoof):** Attacker sends `Content-Type: video/mp4` with a ZIP payload. `validateMime()` reads magic bytes → `sniffed == null` → `INVALID_FILE_TYPE`; `testUploadMimeSpoofRejected` asserts no `save()`. Declared `image/svg+xml` with non-image bytes also rejected (sniffed null). Polyglot files with valid leading ftyp/JPEG magic remain an accepted residual (virus scan deferred per spec).

**MIME allowlist: CLOSED — content-byte validation enforced (M-19-1).**

---

## 3. Size Limits

### 3a. Service layer (authoritative when reached)

```86:95:influora-api/src/main/java/com/influora/service/CreatorDeliverableService.java
        long totalBytes = files.stream().mapToLong(MultipartFile::getSize).sum();
        if (thumbnail != null && !thumbnail.isEmpty()) {
            totalBytes += thumbnail.getSize();
        }
        if (totalBytes > MAX_TOTAL_BYTES) {
            throw new ApiException(
                    "FILE_TOO_LARGE",
                    "Total upload size exceeds 1 GB limit",
                    HttpStatus.BAD_REQUEST);
        }
```

```176:180:influora-api/src/main/java/com/influora/service/CreatorDeliverableService.java
        if (file.getSize() > r2Properties.getMaxVideoBytes()) {
            throw new ApiException(
                    "FILE_TOO_LARGE",
                    "File exceeds maximum size of " + r2Properties.getMaxVideoBytes() + " bytes",
                    HttpStatus.BAD_REQUEST);
        }
```

| Limit | Value | Spec (`09_CREATOR_DELIVERABLES_SPEC.md` §7.1) | Test |
|---|---|---|---|
| Per file | 524_288_000 (500MB) via `R2Properties` | 500MB | ❌ no unit test (L-19-1) |
| Batch total | 1_073_741_824 (1GB) | 1GB | ❌ no unit test (L-19-1) |
| Thumbnail | Same 500MB cap | — | ❌ |

### 3b. Servlet layer — **H-19-1** ✅ CLOSED

```21:27:influora-api/src/main/resources/application.yml
  servlet:
    # Kabir H-19-1 — align servlet multipart limits with CreatorDeliverableService caps
    multipart:
      max-file-size: ${INFLUORA_MULTIPART_MAX_FILE:500MB}
      max-request-size: ${INFLUORA_MULTIPART_MAX_REQUEST:1GB}
```

- `application-dev.yml` does **not** override multipart limits — inherits base `application.yml`.
- `MultipartConfigTest` asserts `DataSize.ofMegabytes(500)` / `DataSize.ofGigabytes(1)` via `ConfigDataApplicationContextInitializer`.
- `max-request-size` (1 GiB = 1_073_741_824 bytes) aligns with `MAX_TOTAL_BYTES` in `CreatorDeliverableService`.
- **Deploy checklist:** ensure reverse-proxy body limits (nginx/ALB) match 500MB/1GB — not enforced in-repo.

**Size limits: CLOSED — servlet + service caps aligned (H-19-1).**

---

## 4. R2 Path Traversal

### 4a. Key construction

```187:190:influora-api/src/main/java/com/influora/service/CreatorDeliverableService.java
        String key =
                String.format(
                        "deliverables/%s/v%d/%s-%s",
                        deliverable.getId(), version, Ulids.newUlid(), safeName);
```

- `deliverable.getId()` — DB-resolved owned row, not raw path param.
- `version` — server-derived integer.
- `Ulids.newUlid()` — server-generated segment.
- Only `safeName` derives from user filename.

### 4b. Filename sanitization

```223:232:influora-api/src/main/java/com/influora/service/CreatorDeliverableService.java
    private static String sanitizeFileName(String original) {
        if (original == null || original.isBlank()) {
            return "upload.bin";
        }
        String name = original.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
```

**Probed attack matrix:**

| Filename input | `safeName` | R2 key prefix |
|---|---|---|
| `../../../etc/passwd` | `passwd` (after strip) → `passwd` | `deliverables/{id}/v{n}/{ulid}-passwd` |
| `..\\..\\evil.mp4` | `evil.mp4` | `deliverables/{id}/v{n}/{ulid}-evil.mp4` |
| `deliverables/other-id/overwrite.mp4` | `overwrite.mp4` | `deliverables/{id}/v{n}/{ulid}-overwrite.mp4` |
| Null / blank | `upload.bin` | safe default |

`R2StorageService.putBytes(objectKey, ...)` passes key directly to S3 SDK — no path normalization bypass because attacker cannot inject `../` into key segments.

**R2 path traversal: CLOSED.**

---

## 5. Additional Attack Surfaces

### 5a. Upload rate limit (M-19-2)

- `AuthRateLimitFilter.bucketFor()` — no bucket for `/creator/deliverables/**`.
- Spec §6.1: **10 file uploads / minute**.
- Abuse: authenticated creator hammers `POST .../upload` with small files → R2 write amplification + DB churn. Bounded by R2 cost, not request rate.

### 5b. In-memory buffering (M-19-3)

- `thumbnail.getBytes()` and `file.getBytes()` load full object into heap per file.
- Once H-19-1 enables 1GB batches, a single request can allocate ~1GB JVM heap.
- Recommend streaming upload to R2 (`RequestBody.fromInputStream`) with size-checked `InputStream` wrapper.

### 5c. Public URL exposure (M-19-4)

```199:199:influora-api/src/main/java/com/influora/service/CreatorDeliverableService.java
        String url = r2StorageService.publicUrl(key);
```

- Returns permanent CDN URL in API response and `files_json`.
- Draft content (pre-brand-review) is world-readable to anyone holding the URL.
- Spec §7.1: signed URLs with expiration. `R2StorageService.presignGet()` exists for contracts — not used here.

### 5d. State machine gating

```140:144:influora-api/src/main/java/com/influora/service/CreatorDeliverableService.java
    private static boolean canUploadNewVersion(DeliverableStatus status) {
        return status == DeliverableStatus.PENDING
                || status == DeliverableStatus.DRAFT
                || status == DeliverableStatus.REVISION_REQUESTED;
    }
```

- `SUBMITTED` / `APPROVED` / `POSTED` uploads rejected with `409 INVALID_STATE` — tested.
- Prevents overwriting content under brand review.

### 5e. Caption / notes ingress

- `caption`, `hashtags`, `creatorNotes` stored without sanitization — same class as Task #9 M-9-1. Low immediate risk (JSON API, no HTML render in backend); brand review UI must escape on display.

---

## 6. Test Coverage Assessment

| Area | Covered | Gap |
|---|---|---|
| Happy-path upload + DRAFT status | ✅ | |
| Foreign deliverable 404 (upload + status) | ✅ | |
| Invalid MIME rejection | ✅ | |
| Invalid state (SUBMITTED) | ✅ | |
| Version increment on revision | ✅ | |
| Controller delegation (201 upload, 200 status) | ✅ | |
| Per-file 500MB rejection | ❌ | L-19-1 |
| Batch 1GB rejection | ❌ | L-19-1 |
| Path traversal filename | ❌ | L-19-2 |
| Brand 403 on upload | ❌ | L-19-3 |
| R2 key shape assertion | ❌ | L-19-2 |

Meera scoped verify target: **9/9 PASS** (`CreatorDeliverableServiceTest` 7/7 + `CreatorDeliverableControllerTest` 2/2).

---

## Findings Summary

| ID | Severity | Area | Status |
|---|---|---|---|
| H-19-1 | **HIGH** | Missing `spring.servlet.multipart` config — 1MB/10MB defaults block spec uploads; service caps unreachable | **CLOSED** — `application.yml` 500MB/1GB + `MultipartConfigTest`; Kabir re-review **VERIFIED** 2026-07-09 |
| M-19-1 | **MEDIUM** | MIME validation header-only — no magic-byte sniffing per spec §5.1 | **CLOSED** — `MediaMimeSniffer` + `validateMime()` + spoof test; Kabir re-review **VERIFIED** 2026-07-09 |
| M-19-2 | **MEDIUM** | No upload rate limit (10/min per spec §6.1) | Open — must fix before prod |
| M-19-3 | **MEDIUM** | Full in-memory `getBytes()` buffering — heap DoS when multipart fixed | Open — fix with streaming before prod |
| M-19-4 | **MEDIUM** | Permanent public R2 URLs for draft deliverables vs spec signed URLs | Open — presigned GET before brand review surface |
| L-19-1 | LOW | Missing hostile unit tests (size limits, batch cap) | Open — non-blocking |
| L-19-2 | LOW | Missing path-traversal filename + R2 key assertion tests | Open — non-blocking |
| L-19-3 | LOW | Missing brand 403 controller test | Open — non-blocking |
| L-19-4 | LOW | `image/svg+xml` allowed via `image/` prefix — XSS if served inline | Open — restrict to jpg/png/gif/mp4/mov |
| L-19-5 | LOW | No max file-count cap on multipart batch | Open — non-blocking |

---

## Go/No-Go Decision

| Sub-scope | Decision |
|---|---|
| Task #19 IDOR / path traversal / MIME allowlist logic | **GO** |
| Meera scoped unit-test gate (9/9) | **GO** |
| Ananya upload UI wiring (dev against API) | **GO** — servlet limits no longer block spec uploads |
| Kavya QA Task #19 | **GO** |
| Production deploy of deliverable upload | **NO-GO** — M-19-2 (rate limit) + M-19-3 (streaming) + M-19-4 (signed URLs) remain |

**Pipeline position:** Task #19 security gate **✅ PASS** — H-19-1/M-19-1 closure re-sign-off complete (2026-07-09). Cleared for Meera build verify, Ananya UI, and integration testing. **Blocked for production** until M-19-2, M-19-3, M-19-4 land.

---

## 7. H-19-1 / M-19-1 Closure Re-Review (2026-07-09 ~18:00 IST)

**Auditor:** Kabir Singh  
**Trigger:** Vikram H-19-1 multipart config + M-19-1 `MediaMimeSniffer` shipped; Meera scoped **15/15 PASS** after fix.

### 7a. H-19-1 — Servlet multipart limits

**Fix landed:** `application.yml` `spring.servlet.multipart` with env-overridable `INFLUORA_MULTIPART_MAX_FILE` (default 500MB) and `INFLUORA_MULTIPART_MAX_REQUEST` (default 1GB).

**Re-attack (spec-compliant upload > 1MB):** Previously blocked at Tomcat with `MaxUploadSizeExceededException` before service layer. Servlet now accepts up to 500MB/file and 1GB/request; service-layer `FILE_TOO_LARGE` guards remain authoritative for business caps.

**Verdict: CLOSED.** `MultipartConfigTest.multipartLimitsMatchDeliverableSpec` binds config to spec.

### 7b. M-19-1 — Magic-byte MIME validation

**Fix landed:**

```214:239:influora-api/src/main/java/com/influora/service/CreatorDeliverableService.java
    private static void validateMime(MultipartFile file) {
        String declared = file.getContentType();
        if (declared == null || ALLOWED_MIME_PREFIXES.stream().noneMatch(declared::startsWith)) {
            throw new ApiException(...);
        }
        try {
            String sniffed = MediaMimeSniffer.detectMimeType(file.getInputStream());
            if (!MediaMimeSniffer.isAllowedMediaMime(sniffed)) {
                throw new ApiException(...);
            }
            if (!MediaMimeSniffer.mimeTypesCompatible(declared, sniffed)) {
                throw new ApiException(...);
            }
        } catch (IOException e) {
            throw new ApiException(...);
        }
    }
```

`MediaMimeSniffer` recognizes JPEG, PNG, GIF, WebP, MP4/MOV (ftyp), WebM from leading bytes. Family compatibility allows `video/mp4` declared + `video/quicktime` sniffed (same family) but rejects cross-family spoof.

**Re-attack matrix:**

| Attack | Result |
|---|---|
| ZIP bytes + `Content-Type: video/mp4` | **BLOCKED** — sniffed null (`testUploadMimeSpoofRejected`) |
| `image/svg+xml` + non-image bytes | **BLOCKED** — sniffed null |
| `video/mp4` declared + valid ftyp bytes | **ALLOWED** — expected |
| `image/jpeg` declared + PNG bytes | **ALLOWED** — same `image` family (subtype cross-match accepted for UX) |

**Verdict: CLOSED.** Meets `12_CREATOR_SECURITY_SPEC.md` §5.1 content-byte requirement.

---

## Recommended Fixes (Vikram) — remaining

1. ~~**H-19-1:**~~ ✅ CLOSED 2026-07-09
2. ~~**M-19-1:**~~ ✅ CLOSED 2026-07-09
3. **M-19-2:** Add `"creator-upload"` bucket to `AuthRateLimitFilter` keyed by `principal.getUserId()`, limit 10/window 60s.
4. **M-19-3:** Stream to R2 via `RequestBody.fromInputStream` with `LimitedInputStream` size guard.
5. **M-19-4:** Use `presignGet()` for draft file URLs (short TTL); reserve `publicUrl()` for post-approval assets only.
