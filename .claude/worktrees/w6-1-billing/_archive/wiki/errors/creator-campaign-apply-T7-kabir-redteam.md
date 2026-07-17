# Creator Campaign Browse/Apply Review — Task #7 (Kabir Red-Team)

**Auditor:** Kabir Singh (Offensive Security / Red-Team Lead)  
**Date:** 2026-07-09  
**Scope:** `CreatorCampaignController.java`, `CreatorCampaignService.java`, `Collaboration.apply()` factory, supporting specs (`CampaignSpecs.browsableForCreator`, `CreatorCampaignDtos.ApplyRequest`), cross-check against `CreatorDiscoveryService#invite` rate-limit posture and Task #11 `CreatorContextService` isolation  
**Reference Spec:** `wiki/tech/creator/05_CREATOR_CAMPAIGNS_SPEC.md` §7.1–7.3, `wiki/tech/creator/12_CREATOR_SECURITY_SPEC.md`

---

## Executive Summary

**VERDICT: PASS WITH FINDINGS**

Task #7's core security invariants hold:

1. **Invite-only visibility gating is airtight** — private (`isPrivate`) campaigns are excluded from browse at the DB layer, and `requireVisibleCampaign` returns the same `404 CAMPAIGN_NOT_FOUND` for unknown ids, DRAFT rows, and un-invited private campaigns on both `GET /{id}` and `POST /{id}/apply`. No enumeration oracle found for invite-only campaigns.
2. **Creator identity is server-derived** — `apply()` keys `Collaboration.creatorId` off `creatorContext.requireCreatorProfile(principal).getUserId()`; there is no client-supplied creator id on any route. Consistent with Task #11 PASS.
3. **Duplicate-apply race is handled** — `UNIQUE(campaign_id, creator_id)` + `DataIntegrityViolationException` catch mirrors the audited `CreatorDiscoveryService#invite` TOCTOU fix.

**Non-blocking gaps vs spec §7.2/§7.3 (same class as pre-existing `invite` endpoint debt):**

- **M-1 (MEDIUM):** No per-creator apply rate limit (`10 applications/hour` per spec §7.2). `AuthRateLimitFilter` does not bucket `/creator/campaigns/*/apply`.
- **M-2 (MEDIUM):** `ApplyRequest.message` is length-bounded (`@Size(max=2000)`) but not XSS-sanitized before persistence into `Collaboration.notes`. No active render path today (`Collaboration` has no `getNotes()` and no API returns `notes`), but spec §7.3 requires sanitization and Task #9 (DealController) will surface this field.

No Critical or High findings. **Does not block Meera build verify or Ananya Task #8 UI work.** Medium items must land before production deploy of the apply surface.

---

## 1. Invite-Only Campaign Visibility Gating (TODO #1)

### 1a. Browse path — DB-level exclusion

```44:48:influora-api/src/main/java/com/influora/repository/CampaignSpecs.java
    public static Specification<Campaign> browsableForCreator() {
        return (root, query, cb) ->
                cb.and(
                        cb.equal(root.get("status"), CampaignStatus.ACTIVE),
                        cb.isFalse(root.get("isPrivate")));
```

Private campaigns never appear in `GET /creator/campaigns` results. An attacker cannot page through browse to discover invite-only campaign metadata.

### 1b. Detail + apply path — uniform 404 oracle

```191:204:influora-api/src/main/java/com/influora/service/CreatorCampaignService.java
    private Campaign requireVisibleCampaign(String campaignId, String creatorUserId) {
        Campaign campaign =
                campaignRepository
                        .findById(campaignId)
                        .filter(c -> c.getStatus() != CampaignStatus.DRAFT)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "CAMPAIGN_NOT_FOUND", "Campaign not found", HttpStatus.NOT_FOUND));
        if (campaign.isPrivate()
                && !collaborationRepository.existsByCampaignIdAndCreatorId(campaign.getId(), creatorUserId)) {
            throw new ApiException("CAMPAIGN_NOT_FOUND", "Campaign not found", HttpStatus.NOT_FOUND);
        }
        return campaign;
    }
```

**Probed attack matrix (all return identical `404 CAMPAIGN_NOT_FOUND` / same error body for unprivileged caller):**

| Scenario | `GET /{id}` | `POST /{id}/apply` |
|---|---|---|
| Random/nonexistent ULID | 404 | 404 |
| DRAFT campaign (brand never published) | 404 | 404 |
| Private campaign, no `Collaboration` row for caller | 404 | 404 |

**Invited creator path (expected allow):** when `existsByCampaignIdAndCreatorId` is true (brand sent invite via `Collaboration.invite`), private campaign detail is visible and `applicationStatus` reflects `INVITED`. Verified in `CreatorCampaignServiceTest.testGetDetailPrivateCampaignVisibleWhenInvited`.

**Residual (LOW, non-blocking):** Non-private campaigns in `PAUSED`/`COMPLETED` status are visible on `GET /{id}` (200) while absent from browse (browse requires `ACTIVE`). `POST /apply` on a non-ACTIVE public campaign returns `409 CAMPAIGN_NOT_OPEN`, which confirms existence vs a random id's 404. This is a **public-campaign status oracle**, not an invite-only leak — acceptable for non-private campaigns; private ones never reach the status check without a prior collaboration row.

**TODO #1 verdict: PASS — invite-only gating is airtight.**

---

## 2. Rate Limiting on Apply (TODO #2)

### 2a. Spec requirement

`05_CREATOR_CAMPAIGNS_SPEC.md` §7.2: **Max 10 applications per hour per creator.**

### 2a. Current implementation

- `AuthRateLimitFilter.bucketFor()` returns `null` for `/creator/campaigns/**` — requests pass through with no throttle.
- `CreatorCampaignService.apply()` has no per-creator counter, sliding window, or repository-backed quota check.
- **Comparison baseline:** `POST /creators/{creatorId}/invite` (`CreatorDiscoveryService#invite`) has the identical gap — no rate limit, no message length cap on `InviteRequest.message` (only `ApplyRequest` has `@Size(max=2000)`).

### 2b. Abuse scenario

An authenticated creator with a valid JWT can hammer `POST /creator/campaigns/{id}/apply` across many distinct public campaign ids (each succeeds once until `ACTIVE` + open-deadline campaigns are exhausted), creating spam `Collaboration` rows and brand notification noise. Cost is bounded by campaign cardinality, not request rate.

**Severity: MEDIUM** — authenticated abuse / spam vector, not cross-tenant data breach. Matches the documented pre-existing posture on `invite`; does not regress security relative to the parallel brand→creator entry point.

**Recommended fix (before production):**

1. Add a `"creator-apply"` bucket to `AuthRateLimitFilter` keyed by `principal.getUserId()` (not IP — creators may share NAT) with `limit=10`, `windowSeconds=3600`, **or**
2. Add a `CollaborationRepository.countByCreatorIdAndCreatedAtAfter(creatorId, since)` guard in `apply()` returning `429 RATE_LIMITED` — more accurate per spec intent.

**TODO #2 verdict: MEDIUM finding M-1 — documented, non-blocking for sprint gate (same as invite), must fix pre-prod.**

---

## 3. `ApplyRequest.message` XSS / Length Handling (TODO #3)

### 3a. Input validation (length)

```62:63:influora-api/src/main/java/com/influora/web/dto/creatorcampaign/CreatorCampaignDtos.java
    /** Optional creator-authored note attached to the application; everything else is server-derived. */
    public record ApplyRequest(@Size(max = 2000) String message) {}
```

- Controller uses `@Valid @RequestBody(required = false) ApplyRequest body` — oversize payloads should receive `400` via `GlobalExceptionHandler.handleValidation` (`MethodArgumentNotValidException`).
- `spring-boot-starter-validation` is on the classpath (`pom.xml`).
- Null body / null message is allowed (`req != null ? req.message() : null`) — no `@NotBlank` or `@Size(min=100)` despite spec §7.2 "Minimum 100 character cover letter" (see L-1).

### 3b. Persistence path

```75:88:influora-api/src/main/java/com/influora/domain/entity/Collaboration.java
    public static Collaboration apply(
            String id, String campaignId, String creatorUserId, String message, String currency) {
        ...
        c.notes = message;
```

Raw string stored in `collaborations.notes` (`TEXT` column). No HTML encoding, stripping, or allowlist sanitizer anywhere in the apply path.

### 3c. Downstream render surface (today)

- `Collaboration` exposes **no** `getNotes()` — notes cannot leak via accidental Jackson serialization of the entity.
- `CreatorCampaignMapper` / `ApplyResponse` do not include the message — creator apply response returns only `collaborationId`, `status`, `appliedAt`.
- **No brand-facing API in this diff reads `notes` back.** Stored XSS is **latent** until Task #9 (`DealController`) or a brand collaboration list endpoint maps `notes` into a DTO consumed by the SPA.

### 3d. XSS risk assessment

| Layer | Status |
|---|---|
| API length bound | ✅ `@Size(max=2000)` |
| API min-length / spam (spec §7.2) | ❌ Not enforced |
| Server-side XSS sanitization (spec §7.3) | ❌ Not implemented |
| Active render path | ✅ None today — notes not returned |
| React default escaping (Task #8 UI) | N/A until notes field is displayed |

**Severity: MEDIUM (M-2)** — spec violation with a clear future blast radius when `notes` surfaces in brand deal room / chat timeline. Recommend a shared `TextSanitizer.sanitizePlainText(String)` (strip HTML tags / normalize) applied in `Collaboration.apply()` and `Collaboration.invite()` for parity, **before** Task #9 exposes the field.

**TODO #3 verdict: PASS for immediate ship (no active XSS sink); MEDIUM finding M-2 for missing sanitization + LOW L-1 for missing min-length.**

---

## 4. Additional Security Checks (beyond TODO list)

### 4a. Identity / IDOR — PASS

```147:174:influora-api/src/main/java/com/influora/service/CreatorCampaignService.java
    public ApplyResponse apply(AuthPrincipal principal, String campaignId, ApplyRequest req) {
        CreatorProfile creator = creatorContext.requireCreatorProfile(principal);
        Campaign campaign = requireVisibleCampaign(campaignId, creator.getUserId());
        ...
        Collaboration collaboration =
                Collaboration.apply(
                        Ulids.newUlid(),
                        campaign.getId(),
                        creator.getUserId(),
                        req != null ? req.message() : null,
                        campaign.getCurrency());
```

`creator.getUserId()` is the only creator id source. Cross-creator apply is structurally impossible. Aligns with Task #11 PASS.

### 4b. AuthZ surface — PASS

`/creator/campaigns/**` falls through `SecurityConfig.anyRequest().authenticated()`. Brand principals receive `403 WRONG_USER_TYPE` from `CreatorContextService` before any campaign data is touched.

### 4c. Idempotency / race — PASS

Pre-check `existsByCampaignIdAndCreatorId` + `DataIntegrityViolationException` → `409 ALREADY_APPLIED`. Same audited pattern as `CreatorDiscoveryService#invite`.

### 4d. Mass assignment — PASS

`ApplyRequest` is a single optional `message` field. No campaign status, rate, or creator id in the body.

---

## Findings Summary

| ID | Severity | Area | Status |
|---|---|---|---|
| M-1 | **MEDIUM** | No per-creator apply rate limit (spec §7.2: 10/hour); `AuthRateLimitFilter` does not cover `/creator/campaigns/*/apply` | **OPEN — pre-prod** (same posture as `invite`) |
| M-2 | **MEDIUM** | `ApplyRequest.message` persisted raw to `Collaboration.notes` without XSS sanitization (spec §7.3) | **OPEN — fix before Task #9 exposes `notes`** |
| L-1 | LOW | Spec §7.2 min 100-char cover letter not enforced (`@Size(min=100)` / `@NotBlank` absent) | Open |
| L-2 | LOW | No hostile test for oversize `message` → 400 validation path on apply endpoint | Open — Kavya Task #12 scope |
| L-3 | LOW | Non-private non-ACTIVE campaigns visible on `GET /{id}` while hidden from browse — public status oracle only | Accepted |

---

## Go/No-Go Decision

| Gate | Decision |
|---|---|
| Task #7 Kabir security sign-off | **PASS WITH FINDINGS** |
| Block Meera build verify | **NO** |
| Block Ananya Task #8 (campaign UI) | **NO** |
| Block production deploy of apply surface | **YES until M-1 + M-2 resolved** (or explicitly waived by Priya with documented risk acceptance) |

**Follow-up for Vikram:** Implement M-1 (rate limit) and M-2 (sanitizer shared with `invite`) in a small hardening PR before Task #9 merges, or in the same PR if Task #9 is imminent. Kabir will re-check the rate-limit + sanitizer diff on request.
