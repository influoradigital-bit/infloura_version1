# Creator Deliverable Metrics — Task #24 (Kabir Red-Team)

**Auditor:** Kabir Singh (Offensive Security / Red-Team Lead)  
**Date:** 2026-07-09 (~18:00 IST)  
**Scope:** `CreatorDeliverableService.reportMetrics()`, `CreatorDeliverableController.reportMetrics()`, `Deliverable.applyMetricsReport()`, `DeliverableMetric.applyReport()`, `MetricsReportRequest` / `MetricsPayload`, `DeliverableRepository.findByIdAndCreatorUserId`, cross-check against Task #19–#21 IDOR posture, Task #19 M-19-2 (creator-deliverable-write rate limit), Kavya L-24-4 (proof key validation), legacy `DeliverableMetricService.submit()` dual-path (L-24-9)  
**Reference Spec:** `wiki/tech/creator/09_CREATOR_DELIVERABLES_SPEC.md` §4.6, §5.2; `wiki/tech/creator/12_CREATOR_SECURITY_SPEC.md` §6.1; Kavya `wiki/errors/creator-deliverable-metrics-T24-kavya-qa.md`  
**Reviewed Files:**
- `influora-api/src/main/java/com/influora/service/CreatorDeliverableService.java` — `reportMetrics`, `canReportMetrics`, `validateNonNegativeMetrics`, `firstNonBlank`, `requireOwnedDeliverable`
- `influora-api/src/main/java/com/influora/web/CreatorDeliverableController.java` — `POST /{deliverableId}/metrics`
- `influora-api/src/main/java/com/influora/domain/entity/Deliverable.java` — `applyMetricsReport()`
- `influora-api/src/main/java/com/influora/domain/entity/DeliverableMetric.java` — `applyReport()`, `proof_screenshot_r2_key`
- `influora-api/src/main/java/com/influora/web/dto/deliverable/CreatorDeliverableDtos.java` — `MetricsReportRequest`, `MetricsPayload`, `MetricsReportResponse`
- `influora-api/src/main/java/com/influora/repository/DeliverableRepository.java` — `findByIdAndCreatorUserId`
- `influora-api/src/main/java/com/influora/repository/DeliverableMetricRepository.java` — `findByMilestoneId`
- `influora-api/src/main/java/com/influora/service/DeliverableMetricService.java` — legacy milestone-keyed path (dual-path comparison)
- `influora-api/src/main/java/com/influora/security/AuthRateLimitFilter.java` — `bucketFor()` (no `/creator/deliverables/**` bucket)
- `influora-api/src/test/java/com/influora/service/CreatorDeliverableServiceTest.java` (+6 metrics)
- `influora-api/src/test/java/com/influora/web/CreatorDeliverableControllerTest.java` (+1 metrics)

---

## Executive Summary

**VERDICT: ✅ PASS WITH FINDINGS**

Task #24's metrics surface inherits Tasks #19–#21 access-isolation architecture and adds correct state-machine fail-closed behavior on the deliverable journey path. No Critical or High findings. No IDOR regression.

**Closed / PASS:**

1. **IDOR — CLOSED** — `reportMetrics` resolves ownership exclusively via `findByIdAndCreatorUserId(deliverableId, principal.getUserId())` after `CreatorContextService.requireCreatorProfile`. Foreign deliverable probes return uniform `404 DELIVERABLE_NOT_FOUND` (`testReportMetricsForeignDeliverable`). No path-param or body-supplied creator id is trusted. `milestone_id` is read from the owned deliverable row only — attacker cannot pivot to another creator's milestone without owning a deliverable linked to it.
2. **State transition abuse — CLOSED** — `canReportMetrics` allows only `APPROVED`, `POSTED`, and `METRICS_REPORTED` (re-report). Pre-approval states (`DRAFT`, `SUBMITTED`, `REVISION_REQUESTED`, etc.) and terminal states (`VERIFIED`, `REJECTED`) fail closed with `409 INVALID_STATE`. `milestone_id` null/blank → `409 MILESTONE_NOT_LINKED`. No client-controlled status field.
3. **Cross-tenant enumeration — no new vector** — metrics shares the same scoped query as upload/submit/status/list; foreign id → `404`, not `403`.
4. **Metric inflation (privilege) — CLOSED** — inflated numbers are self-declared by design; response hard-codes `verificationStatus: PENDING`. No automatic payout, milestone release, or verified-analytics promotion is triggered by this endpoint. Abuse is a business-integrity concern for brand verification workflows, not a privilege-escalation vector on this slice.
5. **Negative values — CLOSED** — `validateNonNegativeMetrics` rejects any negative integer field with `400 INVALID_METRIC_VALUE`.

**Carry-forward (pre-prod, non-blocking sprint gate):**

- **M-19-2 (MEDIUM, extended):** No per-creator rate limit on deliverable mutations. `AuthRateLimitFilter.bucketFor()` returns `null` for `/creator/deliverables/**` — metrics `POST` is unthrottled alongside upload and submit. Spec §6.1 file-upload limit (10/min) not enforced; metrics re-report spam should share a `"creator-deliverable-write"` bucket when M-19-2 lands.
- **M-24-1 (MEDIUM, new — Kavya L-24-4):** `proofScreenshots` values are persisted to `deliverable_metrics.proof_screenshot_r2_key` without format validation, length bounds, or ownership binding to a creator proof-upload session (§4.7 not shipped). Cross-creator R2 key reference is possible until proof-upload issues scoped keys and the service verifies `reported_by_creator_id` owns the key prefix.

**New LOW (non-blocking):**

- **L-24-S1:** No upper-bound sanity check on metric magnitudes (`Integer.MAX_VALUE` accepted) — acceptable for self-declared data but enables absurd campaign aggregates pre-verification.
- **L-24-S2:** All-zero metrics payload allowed — persists null aggregates; low product integrity risk.
- **L-24-S3:** `MetricsReportRequest` lacks `@Valid` / `@Size` — unbounded `proofScreenshots` list strings until DB `VARCHAR(500)` constraint rejects.
- **L-24-S4:** Dual metrics reporting paths (`POST /creator/deliverables/{id}/metrics` vs legacy `PUT /deliverables/{milestoneId}/metrics`) — different state gates and foreign-probe error shapes (`404` vs `403`); architecture watch (Kavya L-24-9).
- **L-24-S5:** Concurrent re-report TOCTOU — no `@Version` on `DeliverableMetric`; parallel POSTs last-write-wins (annoyance, not privilege escalation).

Meera scoped gate **29/29** already **PASS**. **Does not block** Priya Task #24 integration sign-off or Ananya Task #24b UI wire. **Blocks production display of proof screenshots from stored keys** until M-24-1 ownership binding ships with §4.7 proof upload.

---

## 1. IDOR — `reportMetrics`

### 1a. Gate chain (unchanged from Tasks #19–#21)

```183:186:influora-api/src/main/java/com/influora/service/CreatorDeliverableService.java
    public MetricsReportResponse reportMetrics(
            AuthPrincipal principal, String deliverableId, MetricsReportRequest request) {
        creatorContext.requireCreatorProfile(principal);
        Deliverable deliverable = requireOwnedDeliverable(principal, deliverableId);
```

```289:298:influora-api/src/main/java/com/influora/service/CreatorDeliverableService.java
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

Ownership join-through: `deliverables.collaboration_id` → `collaborations.creator_id` (user id). Consistent with Task #10 H-1 / Task #19 PASS / Task #21 brand mirror.

### 1b. Milestone pivot probe

Metric persistence keys off `deliverable.getMilestoneId()` from the **already-owned** row:

```200:206:influora-api/src/main/java/com/influora/service/CreatorDeliverableService.java
        String milestoneId = deliverable.getMilestoneId();
        if (milestoneId == null || milestoneId.isBlank()) {
            throw new ApiException(
                    "MILESTONE_NOT_LINKED",
                    "Deliverable is not linked to a payment milestone",
                    HttpStatus.CONFLICT);
        }
```

Attacker cannot supply a foreign `milestoneId` in the request body — the DTO has no such field. Upsert uses `findByMilestoneId(milestoneId)` where `milestoneId` is server-owned on the deliverable row.

### 1c. IDOR exploit matrix (metrics path)

| Attack | Result |
|---|---|
| Creator A reports metrics on Creator B's `deliverableId` | **BLOCKED** — `404 DELIVERABLE_NOT_FOUND` |
| Brand JWT on metrics POST | **BLOCKED** — `403 WRONG_USER_TYPE` at `requireCreatorProfile` |
| Unauthenticated POST | **BLOCKED** — `SecurityConfig` `anyRequest().authenticated()` for `/creator/**` |
| Spoof creator id in `MetricsReportRequest` body | **N/A** — record has no identity field |
| Supply foreign `milestoneId` in body | **N/A** — field not in DTO; milestone from owned deliverable only |
| List foreign collaboration deliverables then report metrics | **BLOCKED** — list gated by `findByIdAndCreatorId`; metrics re-checks per-row |

**IDOR on metrics report: CLOSED. No regression from Tasks #19–#21.**

---

## 2. State Transition Abuse

### 2a. `canReportMetrics` + ordering

```188:209:influora-api/src/main/java/com/influora/service/CreatorDeliverableService.java
        if (!canReportMetrics(deliverable.getStatus())) {
            throw new ApiException(
                    "INVALID_STATE",
                    "Metrics can only be reported once the deliverable is approved or posted",
                    HttpStatus.CONFLICT);
        }

        if (request == null || request.metrics() == null) {
            throw new ApiException(
                    "INVALID_REQUEST", "metrics payload is required", HttpStatus.BAD_REQUEST);
        }

        String milestoneId = deliverable.getMilestoneId();
        // ...
        MetricsPayload metrics = request.metrics();
        validateNonNegativeMetrics(metrics);
```

```310:314:influora-api/src/main/java/com/influora/service/CreatorDeliverableService.java
    private static boolean canReportMetrics(DeliverableStatus status) {
        return status == DeliverableStatus.APPROVED
                || status == DeliverableStatus.POSTED
                || status == DeliverableStatus.METRICS_REPORTED;
    }
```

Fail-fast order: **profile → ownership → state → payload → milestone link → validation → persist → transition → respond**. Correct.

### 2b. State machine probe matrix

| From status | Report result | To status |
|---|---|---|
| `APPROVED` | ✅ Allowed | `METRICS_REPORTED` |
| `POSTED` | ✅ Allowed | `METRICS_REPORTED` |
| `METRICS_REPORTED` | ✅ Allowed (overwrite) | `METRICS_REPORTED` |
| `DRAFT` / `SUBMITTED` / `REVISION_REQUESTED` / `RESUBMITTED` | ❌ `409 INVALID_STATE` | — |
| `VERIFIED` / `REJECTED` | ❌ `409 INVALID_STATE` | — |
| Any + null `milestone_id` | ❌ `409 MILESTONE_NOT_LINKED` | — |

`applyMetricsReport()` only sets status — no client-controlled fields:

```259:263:influora-api/src/main/java/com/influora/domain/entity/Deliverable.java
    public void applyMetricsReport() {
        this.status = DeliverableStatus.METRICS_REPORTED;
        touch();
    }
```

**State transition abuse: CLOSED.**

---

## 3. Metric Inflation Abuse

### 3a. Threat model (self-declared by design)

`DeliverableMetric` entity javadoc is explicit: all values are creator-entered, never platform-verified. Task #24 correctly returns `verificationStatus: PENDING` and does not auto-promote metrics to verified or trigger milestone release.

### 3b. Inflation vectors probed

| Vector | Impact | Severity |
|---|---|---|
| Set `likes`/`reach` to `Integer.MAX_VALUE` | Inflated aggregates in `deliverable_metrics`; echoed in HTTP response | **LOW (L-24-S1)** — expected pre-verification |
| All-zero payload | Null DB aggregates, `engagementRate: null` | **LOW (L-24-S2)** |
| Re-report on `METRICS_REPORTED` | Overwrites same milestone row; refreshes `reported_at` | **By design** — creator correction flow |
| Rapid re-report spam | DB write churn on `deliverable_metrics` + deliverable row | **MEDIUM (M-19-2)** — rate limit absent |
| Forge `engagementRate` in response | **Not possible** — server-computed from payload |

Aggregation uses `long` intermediates from `int` fields — no integer overflow on engagements sum (max `7 × Integer.MAX_VALUE` fits in `long`).

```211:217:influora-api/src/main/java/com/influora/service/CreatorDeliverableService.java
        long reach = intOrZero(metrics.reach());
        long impressions = metrics.impressions() != null ? metrics.impressions() : intOrZero(metrics.views());
        long engagements =
                intOrZero(metrics.likes())
                        + intOrZero(metrics.comments())
                        + intOrZero(metrics.shares())
                        + intOrZero(metrics.saves());
```

**Privilege escalation via metric inflation: CLOSED.** Business-integrity risk remains for brand verification — out of scope for this API slice.

---

## 4. Proof Screenshot Key Validation (L-24-4 → M-24-1)

### 4a. Current behavior

```219:239:influora-api/src/main/java/com/influora/service/CreatorDeliverableService.java
        String proofKey = firstNonBlank(request.proofScreenshots());
        // ...
        metric.applyReport(
                reach > 0 ? reach : null,
                impressions > 0 ? impressions : null,
                engagements > 0 ? engagements : null,
                deliverable.getPostUrl(),
                proofKey,
                principal.getUserId());
```

```339:344:influora-api/src/main/java/com/influora/service/CreatorDeliverableService.java
    private static String firstNonBlank(List<String> values) {
        if (values == null) {
            return null;
        }
        return values.stream().filter(v -> v != null && !v.isBlank()).findFirst().orElse(null);
    }
```

No validation that:
- Key matches expected R2 prefix pattern (`deliverables/...` or future `proof/...` scoped namespace)
- Key was issued by a §4.7 proof-upload endpoint for this creator/deliverable
- Key length ≤ 500 (`VARCHAR(500)` column)
- Key does not contain path traversal segments (`../`)

### 4b. Attack scenarios

| Scenario | Preconditions | Impact |
|---|---|---|
| **Cross-creator key reference** | Attacker guesses/obtains victim's R2 object key | Victim's screenshot displayed on attacker's metric row when brand views proof — misleading evidence, not data exfil of write access |
| **Arbitrary string injection** | Any authenticated creator on metrics-eligible deliverable | Junk stored in `proof_screenshot_r2_key`; presign/download may 404 or leak bucket layout if key format predictable |
| **Oversized key DoS** | String > 500 chars in `proofScreenshots[0]` | `DataIntegrityViolationException` on save — 500 from DB layer; annoyance not auth bypass |

Legacy `DeliverableMetricService.submit()` has the same gap on `req.proofScreenshotR2Key()` — not introduced by Task #24.

### 4c. Required fix (pre-prod, when proof display ships)

1. §4.7 proof-upload endpoint issues scoped keys: `proof/{creatorUserId}/{deliverableId}/{ulid}.png`
2. `reportMetrics` validates key prefix matches `principal.getUserId()` + deliverable id (or looks up key in a `proof_uploads` staging table)
3. `@Size(max = 500)` on proof key strings; reject `..` and absolute URLs

**M-24-1: OPEN — MEDIUM. Sprint gate not blocked; prod proof display NO-GO until closed.**

---

## 5. Rate Limiting (M-19-2 Extended)

### 5a. `AuthRateLimitFilter` posture

```168:220:influora-api/src/main/java/com/influora/security/AuthRateLimitFilter.java
    private String bucketFor(HttpServletRequest request) {
        // ...
        if (path.startsWith("/admin/auth/")) {
            return "sensitive";
        }
        return null;
    }
```

`POST /api/v1/creator/deliverables/{id}/metrics` is **not** matched by any bucket. Authenticated creators can hammer metrics re-reports without throttle.

### 5b. Abuse shape

- **DB write amplification:** Each POST upserts `deliverable_metrics` + updates `deliverables.status` (idempotent on status) inside one `@Transactional` boundary.
- **Campaign analytics pollution:** Repeated overwrites on same milestone row — bounded to one row per milestone, not unbounded inserts.
- **Cost:** Lower than upload (no R2); comparable to submit.

Spec §6.1 does not list a dedicated metrics limit; M-19-2's proposed `"creator-deliverable-write"` bucket (10/min per creator, shared with upload + submit) is the correct remediation.

**M-19-2: OPEN — MEDIUM (extended to metrics POST). Same sprint carry-forward as Tasks #19–#20.**

---

## 6. Dual-Path Architecture Watch (L-24-S4)

Two creator-facing metrics write paths share `deliverable_metrics` (milestone-keyed):

| Path | Gate | Foreign probe |
|---|---|---|
| `POST /creator/deliverables/{id}/metrics` (Task #24) | Deliverable `APPROVED`/`POSTED`/`METRICS_REPORTED` | `404 DELIVERABLE_NOT_FOUND` |
| `PUT /deliverables/{milestoneId}/metrics` (legacy) | Milestone `FUNDED`/`RELEASED` | `403 FORBIDDEN` (foreign creator) |

A creator could report via the legacy path when milestone is `FUNDED` but deliverable is still `SUBMITTED` — bypassing the Week 3 deliverable status gate. Both paths upsert the same milestone row. Not an IDOR (both check creator ownership), but state-machine divergence is a Priya architecture item.

**L-24-S4: LOW — flag for Priya; deprecate or align legacy path before prod.**

---

## 7. Input Validation & Injection

| Field | Validation | SQLi | XSS | Notes |
|---|---|---|---|---|
| `metrics.*` (integers) | Non-negative only | N/A (parameterized JPA) | N/A (numeric) | No upper bound (L-24-S1) |
| `proofScreenshots[]` | None | N/A | N/A at persistence | Stored raw; M-24-1 |
| `reportedDaysAfterPosting` | Accepted, discarded | N/A | N/A | Not persisted — no impact |
| `deliverable.postUrl` → `link` | From deliverable row, not request | N/A | N/A | Server-owned — good |

No mass-assignment surface — DTO is a Java record with fixed fields. Controller is thin delegation:

```83:89:influora-api/src/main/java/com/influora/web/CreatorDeliverableController.java
    @PostMapping("/{deliverableId}/metrics")
    public ResponseEntity<ApiResponse<MetricsReportResponse>> reportMetrics(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String deliverableId,
            @RequestBody MetricsReportRequest body) {
        return ResponseEntity.ok(
                ApiResponse.ok(creatorDeliverableService.reportMetrics(principal, deliverableId, body)));
    }
```

---

## 8. Hostile Test Coverage (Metrics Slice)

| Scenario | Test | Status |
|---|---|---|
| Happy path POSTED → METRICS_REPORTED | `testReportMetricsFromPosted` | ✅ |
| APPROVED state allowed | `testReportMetricsFromApproved` | ✅ |
| DRAFT → INVALID_STATE | `testReportMetricsInvalidState` | ✅ |
| Negative likes → INVALID_METRIC_VALUE | `testReportMetricsNegativeValues` | ✅ |
| Missing milestone → MILESTONE_NOT_LINKED | `testReportMetricsMissingMilestone` | ✅ |
| Foreign deliverable → 404 | `testReportMetricsForeignDeliverable` | ✅ |
| Controller delegation | `testReportMetrics` (controller) | ✅ |
| Proof key persistence / validation | — | ❌ M-24-1 |
| METRICS_REPORTED re-report overwrite | — | ❌ L-24-S5 |
| VERIFIED / REJECTED rejection | — | ❌ Low gap |
| Integer.MAX_VALUE payload | — | ❌ L-24-S1 |
| Rate-limit exhaustion | — | ❌ M-19-2 |

Meera gate **29/29 PASS** — sufficient for sprint integration; proof-key hostile tests required before prod proof display.

---

## Findings Summary

| ID | Severity | Area | Status |
|---|---|---|---|
| — | — | IDOR on metrics (`findByIdAndCreatorUserId`) | **CLOSED** — PASS |
| — | — | State transition abuse (`canReportMetrics` + milestone gate) | **CLOSED** — PASS |
| — | — | Cross-tenant enumeration | **CLOSED** — no new vector |
| — | — | Privilege escalation via metric inflation | **CLOSED** — self-declared + PENDING by design |
| M-19-2 | **MEDIUM (extended)** | No per-creator rate limit on `/creator/deliverables/**` (upload + submit + **metrics**) | **OPEN** — carry-forward from Task #19 |
| M-24-1 | **MEDIUM** | Unvalidated `proofScreenshots` → `proof_screenshot_r2_key` (Kavya L-24-4) | **OPEN** — prod proof display NO-GO |
| L-24-S1 | LOW | No upper-bound sanity on metric magnitudes | Open |
| L-24-S2 | LOW | All-zero metrics payload allowed | Open |
| L-24-S3 | LOW | `MetricsReportRequest` missing `@Valid` / `@Size` | Open |
| L-24-S4 | LOW | Dual metrics paths — divergent state gates (Kavya L-24-9) | Open — Priya watch |
| L-24-S5 | LOW | Concurrent re-report TOCTOU (no optimistic lock) | Open |

---

## Go/No-Go Decision

| Sub-scope | Decision |
|---|---|
| Task #24 IDOR / state machine / negative-value gate | **GO** |
| Meera scoped unit-test gate (29/29) | **GO** (already PASS) |
| Ananya Task #24b metrics UI wire | **GO** |
| Kavya QA Task #24 | **GO** (already APPROVED) |
| Sprint integration / dev deploy | **GO** |
| Priya Task #24 integration sign-off | **GO** |
| Production display of proof screenshots from stored keys | **NO-GO** until M-24-1 + §4.7 proof upload |
| Production deploy of deliverable upload | **NO-GO** — M-19-2/3/4 unchanged (Task #19) |

**Pipeline position:** Task #24 security gate **✅ PASS WITH FINDINGS** — cleared for Priya sign-off on metrics API integration. Vikram batches M-19-2 rate limit + M-24-1 proof-key validation with §4.7 proof-upload slice.

---

## Kabir Sign-Off

- [x] IDOR on metrics re-verified — uniform `404`, collaboration join-through unchanged from Tasks #19–#21
- [x] State transition abuse probed — fail-closed on pre-approval and terminal states; milestone link required
- [x] Metric inflation probed — self-declared + `PENDING`; no payout/verification bypass
- [x] Proof screenshot key validation (L-24-4) — **M-24-1 OPEN**; cross-creator key reference risk until §4.7
- [x] Rate limiting — **M-19-2 extended** to metrics POST; unthrottled (same class as upload/submit)
- [x] No Critical or High findings — pipeline **not blocked**
- [ ] M-24-1 proof-key ownership binding — **pre-prod required before proof display**
- [ ] M-19-2 creator-deliverable-write bucket — **pre-prod required before upload prod** (metrics included)

**Kabir verdict: ✅ PASS WITH FINDINGS.** Route to Priya for Task #24 integration sign-off. Escalation to Priya/Swapnil: **none** (no Critical/High).

---

**Document Control:** Created 2026-07-09 by Kabir (Task #24). Carry-forward: M-19-2 rate limit (extended), M-24-1 proof-key validation. Next: Priya Task #24 sign-off; Vikram §4.7 proof-upload + M-24-1 binding.
