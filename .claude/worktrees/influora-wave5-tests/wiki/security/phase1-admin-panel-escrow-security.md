# Kabir — Red-Team Security Gate: Phase 1 Admin Panel (Brands/Creators/Disputes + Escrow Resolve)

**Date:** 2026-07-13
**Scope:** Nested admin router; GET /admin/brands, /admin/creators, /admin/disputes; POST /admin/disputes/:id/resolve (escrow release/refund/split); frontend DisputesPage/DisputeList/UsersPage + hooks + api-contracts.
**Reviewer:** Kabir (Red-Team)
**Risk class:** High (moves real escrow/money)

---

## Findings

### [SEVERITY: High] Lost-update race on `Dispute.resolve()` — no row lock / no optimistic version
**Where:** `influora-api/src/main/java/com/influora/service/DisputeService.java:165-246` (`resolveDispute`), `influora-api/src/main/java/com/influora/domain/entity/Dispute.java` (no `@Version`, `resolve()` only checks in-memory `status.isActive()`)

**Issue:** `resolveDispute()` loads the `Dispute` via a plain `disputeRepository.findById(disputeId)` (no `SELECT ... FOR UPDATE`, no `@Version` column on the entity). The escrow-side money movement IS safe — `EscrowService.adminReleaseForDispute/adminRefundForDispute/adminSplitForDispute` all acquire a pessimistic lock per hold via `requireHoldForUpdate` (`findByIdForUpdate`, `@Lock(PESSIMISTIC_WRITE)`) and re-check `status == FROZEN` after locking, so a hold can never be paid out twice. But two concurrent calls to `POST /admin/disputes/:id/resolve` (double-click, two admin tabs, or a deliberately-raced request from a compromised/malicious admin session) can both read the dispute row while it is still `OPEN`/`UNDER_REVIEW` (both pass `status.isActive()` in memory), then both call `dispute.resolve(...)` + `disputeRepository.save(dispute)` with **different resolutions**. The escrow layer correctly executes only the first mover's fund movement (second call's hold loop finds no `FROZEN` holds left, settles nothing) — but nothing stops the second call's stale in-memory `Dispute` object from being saved afterward, blind-overwriting the first call's committed `status`/`resolvedByAdminId`/`resolutionNotes`/`resolvedAt`.

**Impact:** The persisted dispute record can end up describing a resolution that never happened to the money — e.g. audit trail says "RESOLVED_BRAND (refunded)" while the ledger shows the funds actually went to the creator via the first, silently-clobbered call. For an escrow/fintech system this is a genuine compliance and forensic-integrity gap: a corrupt or careless admin action can make the official dispute record and the actual money movement diverge, which is exactly the scenario an auditor or a dispute over a dispute would need to reconstruct correctly.

**Fix:** Add `@Version` to `Dispute` (JPA optimistic locking) so the second `save()` throws `OptimisticLockException` on a lost update, or — matching the precedent already used for `EscrowHold` — add a `findByIdForUpdate` pessimistic-read variant to `DisputeRepository` and use it at the top of `resolveDispute()` so the whole method (status check + escrow settlement + save) is serialized per dispute. Map the resulting `OptimisticLockException`/lock-timeout into a clean 409 `DISPUTE_ALREADY_RESOLVED` in `GlobalExceptionHandler` rather than falling through to the generic 500 handler.

---

### [SEVERITY: High] Dispute resolution can be silently un-audited when no escrow settles
**Where:** `influora-api/src/main/java/com/influora/service/DisputeService.java:220-244`; `influora-api/src/main/java/com/influora/service/admin/AdminAuditLogService.java:114-124` (`ALLOWED_ENTITY_TYPES` has no `DISPUTE` entry)

**Issue:** The only audit-log writes inside `resolveDispute()` happen inside `for (EscrowStatusResponse settlement : settlements) { adminAuditLogService.record(...) }` — i.e. one row is written **per settled escrow hold**. If `settlements` comes back empty (which happens whenever there is no `FROZEN` hold left to move — e.g. the race above already consumed it, or a dispute is resolved after its escrow was already resolved through another path), **zero** audit rows are written for that resolve call. There is also no unconditional "a dispute was resolved" audit entry independent of escrow outcome — `AdminAuditLogService.ALLOWED_ENTITY_TYPES` doesn't even include a `DISPUTE` entity type, only `ESCROW`.

**Impact:** A compliance-critical action (an admin deciding RESOLVED_BRAND/RESOLVED_CREATOR/RESOLVED_SPLIT, with their identity, reasoning, and timestamp) can complete successfully with **no trace at all** in `admin_audit_log`. Combined with the race above, this is the exact combination that would let a bad-faith or erroneous resolution disappear from the audit trail entirely.

**Fix:** Add an unconditional audit write for `entityType="DISPUTE"` at the top level of `resolveDispute()` (before or after the settlement loop, but not conditioned on `settlements` being non-empty), recording old status → new status, `resolvedByAdminId`, and `notes`. Add `"DISPUTE"` to `ALLOWED_ENTITY_TYPES` and a field allow-list (`id`, `status`, `resolvedByAdminId`) in `AdminAuditLogService`.

---

### [SEVERITY: Low] Malformed `resolution` enum / concurrent-resolve failure surfaces as a generic 500, not a clean error
**Where:** `influora-api/src/main/java/com/influora/common/GlobalExceptionHandler.java:44-48`; `DisputeService.java:220` (`dispute.resolve()` throws bare `IllegalStateException` on an already-terminal dispute)

**Issue:** `GlobalExceptionHandler` only special-cases `ApiException`/`MethodArgumentNotValidException`/`BadCredentialsException`/`AccessDeniedException`; everything else (an unparseable `DisputeStatus` enum value in the JSON body → `HttpMessageNotReadableException`, or a second sequential resolve attempt hitting `Dispute.resolve()`'s `IllegalStateException`) falls into the catch-all and returns HTTP 500 `INTERNAL_ERROR`. No information is leaked (message is generic), so this is not exploitable, but it's a real audit/observability gap — genuine client errors and legitimate "already resolved" conflicts get logged and surfaced identically to real server bugs.

**Fix:** Add explicit handlers for `HttpMessageNotReadableException` (→ 400) and either catch `IllegalStateException` in `DisputeService` and rethrow as `ApiException("DISPUTE_ALREADY_RESOLVED", ..., HttpStatus.CONFLICT)`, or add a dedicated handler in `GlobalExceptionHandler`.

---

### [SEVERITY: Low] Admin route gating is presence-of-token only on the client (defense-in-depth gap, not exploitable)
**Where:** `src/App.tsx:96` (`AdminProtectedRoute` — `const isAuthenticated = localStorage.getItem('admin_token')`)

**Issue:** The nested `/admin/*` router's only client-side gate is "does `admin_token` exist in localStorage" — no role check, no expiry check, no MFA-satisfied check client-side. This is fine as a UX gate because every sensitive read/write is independently re-checked server-side (`AdminContextService.requireRoleWithMfaSatisfied`, confirmed present on every mutating/privileged method in `AdminBrandService`, `AdminCreatorService`, `DisputeService`) — a SUPPORT-tier admin can navigate to `/admin/disputes` and see the UI shell, but `GET /admin/disputes` and `POST .../resolve` both correctly 403 server-side (`AdminRole.SUPER_ADMIN, AdminRole.ADMIN` only, SUPPORT excluded). Also `admin_token` in `localStorage` (not an httpOnly cookie) is standard XSS-token-theft exposure — pre-existing pattern across the whole app, not introduced by this build.

**Fix (non-blocking, log for later sprint):** Client-side role gating for nicer UX (hide Disputes nav / show a permission-denied state instead of a raw fetch failure) is a nice-to-have, not required for this gate since the server is authoritative. Migrating admin token storage off localStorage is a larger, pre-existing architectural item — flag for its own sprint, out of scope for this specific gate.

---

## What checked out clean (no finding)

- **RBAC / MFA enforcement on every new endpoint:** Every mutating and privileged-read method in `AdminBrandService`, `AdminCreatorService`, and `DisputeService` calls `AdminContextService.requireRoleWithMfaSatisfied(...)` with an explicit role allow-list at the service layer — not just relying on the router/`AdminProtectedRoute` wrapper. Reads (list/getById) allow SUPER_ADMIN/ADMIN/SUPPORT; brand/creator KYC/suspend/reinstate/reviewApplication/force-reauth and all dispute list/resolve endpoints correctly restrict to SUPER_ADMIN/ADMIN only, matching `src/admin/__tests__/role-permission-matrix.md`.
- **IDOR on brand/creator/dispute IDs:** No cross-tenant leak found. Brand/creator detail lookups (`requireBrandWorkspace`/`requireCreatorProfile`) 404 cleanly on a non-existent or wrong-type id. Dispute resolve operates globally by design (this is a global-admin surface, not a tenant-scoped one) and is correctly gated to SUPER_ADMIN/ADMIN only — not an IDOR.
- **SQL/JPQL injection:** `AdminBrandService.list`/`AdminCreatorService.list` build filters via the JPA Criteria `Specification` API (`CreatorProfileSpecs.withFilters`), never string concatenation. `DisputeRepository.findFiltered`'s JPQL uses `@Param`-bound placeholders throughout, including the nested `IN (SELECT ...)` subqueries — no raw value concatenation anywhere. No injection surface found.
- **Escrow money-movement server-side authority:** `adminSplitForDispute` validates `creatorSplitPercent` is present and in `[0,100]` in the service itself (not just the DTO's `@DecimalMin/@DecimalMax`) — defense in depth, can't be bypassed by a direct API call that skips bean validation some other way. Escrow amounts are always derived from the persisted `EscrowHold.amount`, never client-supplied. Each hold-level release/refund/split uses a per-hold pessimistic lock (`findByIdForUpdate`) and re-checks status after acquiring the lock, so a hold can never be double-paid even under concurrent resolve calls (see High finding above for the separate `Dispute`-row-level gap this doesn't cover).
- **Input validation on resolve requests:** `ResolveDisputeRequest` requires a non-null terminal `DisputeStatus`; `DisputeService.resolveDispute` explicitly rejects a non-resolved-type value (e.g. `OPEN`/`UNDER_REVIEW`) even if it slipped past validation. Negative/out-of-range split percentages are rejected both client- and server-side.
- **Frontend trust boundary:** `DisputeResolveModal` (`src/admin/components/disputes/DisputeList.tsx`) explicitly documents and treats its own 0–100 split-percent check and required-notes check as a UX affordance only — `useDisputeResolve.ts`/`api-contracts.ts` surface whatever the server actually returns (including a 400 from `EscrowService.adminSplitForDispute`) rather than assuming client validation is sufficient. No client-only gate found.
- **Tenant/workspace scoping on list endpoints:** `GET /admin/brands`/`/admin/creators`/`/admin/disputes` intentionally return global data — this is correct for a platform-wide admin console (SUPER_ADMIN/ADMIN/SUPPORT oversight), not a leak, since access itself is gated by admin role.
- **Audit trail field hygiene:** `AdminAuditLogService` re-filters every snapshot against a per-entity-type allow-list server-side (defense in depth even if a caller forgets to pre-filter) and never logs secrets; IP address is taken from `getRemoteAddr()`, deliberately not trusting a spoofable `X-Forwarded-For` for the forensic record.

---

## Verdict: **FAIL — BLOCKED**

**Blockers (must fix before Priya sign-off):**
1. High — Dispute resolve lost-update race (`DisputeService.java`, `Dispute.java`) — add `@Version` or pessimistic lock.
2. High — Audit log can be silently skipped on a dispute resolution (`DisputeService.java`, `AdminAuditLogService.java`) — add unconditional DISPUTE audit entry.

**Non-blocking, log for this sprint:**
3. Low — Generic 500 instead of clean 4xx on malformed enum / already-resolved conflict.
4. Low — Client-side admin route gate is presence-of-token only (server is authoritative; UX-only gap).

Route fixes to **Vikram** (backend, items 1-3). Re-test required after fixes before final PASS.
