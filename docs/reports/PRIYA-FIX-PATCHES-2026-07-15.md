# 🏗️ Priya — Fix Patches for Remaining Partial/Broken Items
**Date:** 2026-07-15 · **Author:** Priya (CTO) · **Target branch:** `integration/consolidate` (trunk) · **Apply via:** Vikram/Ananya → Kavya QA → Meera build → Kabir (PII) → Priya sign-off

> **READ FIRST — verification status.** This environment cannot compile, run tests, or boot the app (no Docker, no Maven/Node build, no network to the private repo), and the checked-out tree is the stale `feature/d14-invoicing`. Every patch below is **written against the trunk's real signatures but is UNVERIFIED** — it has not been compiled or booted. Nothing here may be marked "Aligned" on the dashboard until Meera's `mvn`/`npm` build is green **and** a real Spring boot loads the context. I will not paint a row green on unbuilt code — that is the exact over-claim this dashboard was corrected for. Apply, compile, fix compile nits (I flag the likely ones), then re-verify.

Confidence legend: 🟢 high (self-contained, signatures confirmed) · 🟡 medium (needs a compile pass / enum confirmation) · 🔴 blocked on a decision.

---

## P2 · Moderation `ESCALATE` — 🟢 implement the stubbed branch

**Root cause:** `AdminModerationService.java:158-163` throws `NOT_IMPLEMENTED`. `ContentFlagStatus` has only `PENDING/REVIEWED/ACTIONED` — no escalated state.

**1) `domain/enums/ContentFlagStatus.java`** — add a value:
```java
public enum ContentFlagStatus {
    PENDING,
    REVIEWED,
    ACTIONED,
    ESCALATED   // NEW — raised to senior review queue
}
```

**2) `domain/entity/ContentFlag.java`** — add, mirroring `markReviewed`:
```java
/** Raise this flag to the senior-review queue. Mirrors {@link #markReviewed(String)}. */
public void markEscalated(String adminId) {
    this.status = ContentFlagStatus.ESCALATED;
    this.reviewedBy = adminId;      // confirm field name matches markReviewed's body
    this.reviewedAt = java.time.Instant.now();
    touch();
}
```

**3) `service/admin/AdminModerationService.java`** — replace the `ESCALATE` case:
```java
case "ESCALATE":
    flag.markEscalated(adminId);
    adminAuditLogService.record(
            principal, request, "ESCALATE", "CONTENT_FLAG", flag.getId(),
            java.util.Map.of("id", flag.getId(), "status", flag.getStatus().name()),
            java.util.Map.of("id", flag.getId(), "status", "ESCALATED"),
            reasonOrDefault(reason, "Escalated flag to senior review"));
    break;   // falls through to contentFlagRepository.save(flag) + toDto
```

**Compile nits to expect:** confirm `ContentFlag`'s reviewer field names (`reviewedBy`/`reviewedAt`) against `markReviewed`'s actual body; if `ContentFlagStatus` is persisted as an ordinal anywhere, add the enum at the **end** (it is) so existing rows keep their ordinals. **Acceptance:** `action=ESCALATE` → 200, status `ESCALATED`, audit row written; unit test on the branch.

---

## P3 · Creator tax identity (GSTIN/PAN) — 🟢 wire the endpoint (schema already exists)

**Root cause:** `CreatorProfile` already has `gstin`/`pan`/`taxRegistrationStatus` columns + `applyTaxIdentity(gstin, pan, status, creatorInvoiceCode)` (CreatorProfile.java:368). There is simply **no HTTP endpoint** — `src/lib/api.ts:1852` rejects `NOT_IMPLEMENTED`. No new table needed.

**1) `web/dto/creator/CreatorProfileDtos.java`** — add a validated request record:
```java
public record CreatorTaxIdentityRequest(
        @jakarta.validation.constraints.Pattern(
            regexp = "^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$",
            message = "Invalid GSTIN") String gstin,
        @jakarta.validation.constraints.Pattern(
            regexp = "^[A-Z]{5}[0-9]{4}[A-Z]$",
            message = "Invalid PAN") String pan) {}
```

**2) `service/CreatorProfileService.java`** — add (mirror how `patchMyProfile` loads the profile):
```java
@org.springframework.transaction.annotation.Transactional
public CreatorProfileSelfResponse submitTaxIdentity(
        AuthPrincipal principal, CreatorTaxIdentityRequest req) {
    CreatorProfile profile = requireMyProfile(principal);   // same loader patchMyProfile uses
    // GSTIN present ⇒ business is GST-registered; else keep PAN-only.
    CreatorTaxRegistrationStatus status = (req.gstin() != null && !req.gstin().isBlank())
            ? CreatorTaxRegistrationStatus.REGISTERED     // confirm exact enum constant
            : CreatorTaxRegistrationStatus.UNREGISTERED;
    profile.applyTaxIdentity(req.gstin(), req.pan(), status, null);
    creatorProfileRepository.save(profile);
    return getMyProfile(principal);
}
```

**3) `web/MeCreatorProfileController.java`** — add the endpoint:
```java
@org.springframework.web.bind.annotation.PostMapping("/tax-identity")
public ResponseEntity<ApiResponse<CreatorProfileSelfResponse>> submitTaxIdentity(
        @AuthenticationPrincipal AuthPrincipal principal,
        @Valid @RequestBody CreatorProfileDtos.CreatorTaxIdentityRequest body) {
    return ResponseEntity.ok(ApiResponse.ok(creatorProfileService.submitTaxIdentity(principal, body)));
}
```

**4) `src/lib/api.ts:1852`** — replace the stub:
```ts
export const creatorTaxIdentity = {
  submit: (body: CreatorTaxIdentitySubmission) =>
    isLive()
      ? http.request<void>('POST', '/me/creator-profile/tax-identity', { body })
      : mockOr(undefined),
};
```

**Compile nits / review:** confirm the exact `CreatorTaxRegistrationStatus` constant names (I saw `UNREGISTERED`; verify `REGISTERED`/`PENDING`), and the profile loader name (`requireMyProfile`) that `patchMyProfile` uses. **Kabir (PII):** `gstin`/`pan` are stored plaintext today (same as the existing KYC columns). Encrypting them (AES-256-GCM `AttributeConverter`) + access-scoping reads is a **separate hardening task** — do NOT bolt it on in this wiring patch without a migration plan; track it with V1-1 (the KYC/R2 exposure). **Acceptance:** creator submits valid GSTIN/PAN → persisted + status set; invalid → 422; FE shows real success.

---

## B1 · Admin WebSocket — 🟢 Phase 1: stop the dead-socket noise

**Root cause:** client dials `/api/v1/admin/ws`; no backend WS exists. `useAdminSocket` calls `socket.connect()` unconditionally.

**`src/admin/hooks/useAdminSocket.ts`** — gate the connection behind a flag (default OFF):
```ts
/** Backend /admin/ws is not implemented yet — keep the socket dark unless explicitly enabled. */
const adminWsEnabled = () =>
  ((import.meta as any).env?.VITE_ADMIN_WS_ENABLED ?? 'false') === 'true';

// inside the useEffect, replace `if (!enabled) return;` with:
useEffect(() => {
    if (!enabled || !adminWsEnabled()) return;   // no backend WS → don't dial, no retry storm
    const socket = getAdminSocket();
    const offStatus = socket.onStatusChange(setStatus);
    setStatus(socket.getStatus());
    const off = socket.on(event, (payload) => handlerRef.current(payload));
    socket.connect();
    return () => { off(); offStatus(); };
}, [event, enabled]);
```
Apply the same `!adminWsEnabled()` guard to the status-only hook below it. REST `usePulseData` continues to drive the panel. **Acceptance:** no failed-WS console errors / reconnect loop; pulse still updates via REST.

**Phase 2 (Vikram, only if product wants live push):** Spring STOMP endpoint at `/admin/ws`, JWT via handshake interceptor (**token in the handshake, never the query string** — Kabir), broadcasting the pulse DTO. Then set `VITE_ADMIN_WS_ENABLED=true`.

---

## B2 · Orphaned `src/app/brand` — 🟢 quarantine then remove (Vikram runs; device can't delete)

```bash
# 1) prove nothing imports them (expect zero hits):
git grep -nE "app/brand" -- 'src/**' ':!src/app/brand/**'
# 2) remove ONLY the brand subfolders — NEVER the parent src/app/ (holds the live Tailwind entry):
git rm -r src/app/brand app/brand
# 3) Meera: npm run build must stay green; src/pages/brand-*.tsx routes unaffected.
```
**Do not** touch `src/app/globals.css` / `src/app/layout` or the top-level Vite entry. **Acceptance:** folders gone, `npm run build` green, no route regressions.

---

## P4 · Portfolio analytics + sync — 🟡 needs a new table (compile in-repo)

Current analytics return honest zeros (`PortfolioService.java:209-224`); `syncPlatforms` is a stub (:180). Real fix:

**Migration `influora-api/.../db/migration/V20260715210000__portfolio_view_event.sql`:**
```sql
CREATE TABLE portfolio_view_event (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  creator_id    BIGINT NOT NULL,
  event_type    VARCHAR(24) NOT NULL,          -- PAGE_VIEW | LINK_CLICK | MEDIA_KIT_DOWNLOAD
  viewer_hash   VARCHAR(64) NULL,              -- anonymized dedupe key (no raw IP)
  created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_pve_creator_type_time (creator_id, event_type, created_at)
);
```
Then: `PortfolioViewEvent` entity + `PortfolioViewEventRepository` (count-by-creator-and-type-since); `analytics()` reads real counts; public `getPublic()` records a `PAGE_VIEW`; the media-kit route records `MEDIA_KIT_DOWNLOAD`. `syncPlatforms` → call the existing `MetaOAuthService`/Graph client to refresh follower/engagement snapshots, guarded by connection state. **🟡 Uncompiled** — I've read the service shape but not every DTO; Vikram wires the entity/repo and compiles. **Acceptance:** analytics DB-backed; sync does a real Meta refresh or returns "no platform connected"; no hardcoded metric constants.

---

## P1 · Admin dashboard KPIs (revenue + deltas) — 🔴 blocked on Rohan + needs a snapshot table

`AdminDashboardStatsCache.java:82-87` returns `revenue = BigDecimal.ZERO` and all `*Change` deltas `= 0.0`, each an explicit `TODO(blocker)`.

**This is not purely a code task — two blockers:**
1. **Revenue formula → escalate to Rohan (CFO).** Revenue = platform-fee take, not GMV. Rohan must define the exact formula (gross platform fee − refunds/reversals, over what window) sourced from the fee ledger. I will not guess a money formula.
2. **Period-over-period deltas need history.** A single live query can't produce WoW deltas — we need a daily KPI snapshot.

**Migration `V20260715220000__kpi_daily_snapshot.sql`:**
```sql
CREATE TABLE kpi_daily_snapshot (
  snapshot_date    DATE PRIMARY KEY,
  gmv              DECIMAL(18,2) NOT NULL DEFAULT 0,
  revenue          DECIMAL(18,2) NOT NULL DEFAULT 0,
  active_campaigns INT NOT NULL DEFAULT 0,
  created_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```
**`job/KpiSnapshotJob.java` (scaffold):**
```java
@Component
public class KpiSnapshotJob {
    @Scheduled(cron = "0 5 0 * * *", zone = "Asia/Kolkata")   // 00:05 IST daily
    @SchedulerLock(name = "kpiDailySnapshot", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void snapshot() { /* write today's gmv/revenue/active_campaigns to kpi_daily_snapshot */ }
}
```
Then `AdminDashboardStatsCache` reads `revenue` from Rohan's formula, and deltas = today vs `kpi_daily_snapshot(today − period)`. **Until the first snapshot exists, return `null` (renders "—"), never `0`.** **Acceptance:** revenue reconciles with Rohan's formula; deltas from snapshots; one `@SchedulerLock`ed job. **Status: 🔴 open — awaiting Rohan's revenue formula before Vikram implements.**

---

## Verification gate (before any row goes green)

1. **Meera:** `mvn -q -pl influora-api test` compiles + unit-green; `npm run build` green; `pytest` green (AI svc untouched here).
2. **Boot:** load a real Spring context on a Docker-capable box (closes the standing V0-1 boot gate) and hit each new/changed endpoint with `curl`.
3. **Kavya:** QA each diff against TECH-STACK conventions.
4. **Kabir:** P3 PII (GSTIN/PAN handling) + B1 Phase-2 WS auth if built.
5. **Priya:** sign-off → only then flip the dashboard rows to Aligned.

## Handoff
`Priya → Vikram/Ananya | Fix patches for 6 remaining items | PRIYA-FIX-PATCHES-2026-07-15.md | STATUS: code written, UNVERIFIED (no build/boot here) | NEXT: apply on trunk, Meera build+boot, then verify | BLOCKED: P1 revenue formula → Rohan`
