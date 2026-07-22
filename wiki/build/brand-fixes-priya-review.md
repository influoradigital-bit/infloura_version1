# Brand-break fixes — CTO review + FE↔BE contract reconciliation (Priya)

Date: 2026-07-22 · Reviewer: Priya (CTO) · Method: read the actual changed code in the **main
working tree** (sibling `.claude/worktrees/*` copies ignored — known grep-leak hazard).
Inputs: `wiki/build/brand-fixes-backend.md` (Vikram), `wiki/build/brand-fixes-frontend.md` (Ananya),
`wiki/reports/brand-feature-audit.md`.

## Verdict summary

| Fix | Verdict | Note |
|-----|---------|------|
| #1 Meera outcome digest | **PASS** | one-liner present at `chat.py:137`; wiring test end-to-end |
| #2 Contract brand-sign | **PASS** | server-derived `role="BRAND"` is safe; escrow gate + relay path intact |
| #3 Deliverable safety review | **PASS** (code) · **Kabir gate REQUIRED** | contract reconciled field-for-field; info-barrier judgment is Vikram's, not yet red-teamed |
| #4 Content-performance media | **CHANGES-REQUIRED** | NON_NULL omission vs FE `!== null` / required-number → renders literal `"undefined"` |

**Gate decision: PROCEED #1/#2/#3 to Meera (build) → Kavya (QA) → Kabir (#3 info-barrier).
LOOP #4 back to Ananya** for a small, well-scoped FE null-handling fix (below) — it must land before
this panel ships live, but it does not block the other three and touches no money/security surface.

---

## #1 — Meera outcome digest — PASS

Verified concretely (not from the report): `influora-ai/app/routes/chat.py:137` now contains
`"outcome_digest": context_data.get("outcome_digest"),` inside the `_fetch_brand_context`
`brand_fields` dict — the exact seam the audit flagged as the single dead wire. `assembler.py`
already read `brand.get("outcome_digest")`, so this closes the loop. Vikram's new wiring test
(`test_fetch_brand_context_carries_outcome_digest_into_assembled_block_b`) drives the real seam
(Spring response → `_fetch_brand_context` → `build_block_b`) rather than feeding the assembler a dict
directly — which is precisely the gap that let this slip every prior gate. Sound. (Live pytest run is
Meera's to re-run; report cites 16 passed.)

---

## #2 — Contract brand-sign — PASS (safe)

Server-deriving `role="BRAND"` from the principal is **correct and safe**. Traced every branch in
`ContractController.sign` (`:78-107`) and `ContractService.recordSignature` (`:432-472`):

- **A CREATOR principal can never sign as BRAND.** `sign` routes `UserType.CREATOR` to
  `recordSignatureForCreator` (`ContractController:83-84`), which records CREATOR only and ignores the
  body. The `role="BRAND"` default is only ever reached *after* `requireBrandWorkspace(principal)`
  (`:86`), which throws unless the principal is BRAND. The default is applied to an
  already-BRAND-authenticated identity — not forgeable.
- **A BRAND principal can only sign as CREATOR via the gated relay path.** Explicit `role=CREATOR`
  still routes to `recordSignature`'s CREATOR branch (`ContractService:440-446`), which requires an
  elevated `MemberRole` (OWNER/ADMIN/MANAGER). Unchanged, Kabir E2 LOW-4 reviewed. Garbage role →
  `INVALID_SIGNER_ROLE` 400 (`:447-450`) still the safety net.
- **Both-signed still gates `ContractReadyForEscrowEvent`.** `doRecordSignature:533` gates
  `promptEscrowFundingIfNeeded` (which publishes the event, `:568-573`) *and* PDF generation on
  `brandSignedAt != null && creatorSignedAt != null`. The fix changed how the brand's signature is
  *recorded*, not when the escrow event fires. Idempotency (`executeOnce`, per-contract-per-role key)
  intact.
- `MoneyDtos.ContractSignRequest` (`:209`) is now `record ContractSignRequest(String role)` — no
  `@NotBlank`. Correct; the body is optional at the controller (`@RequestBody(required = false)`).

No regression to the money path.

---

## #3 & #4 — CONTRACT RECONCILIATION (field-by-field)

### #3 DeliverableSafetyReview — contract CLEAN, PASS

Compared `DeliverableSafetyDtos.java` (BE) against `src/lib/api.ts:1517-1535` (FE) and the consumer
`DeliverableSafetyReviewCard.tsx`:

| Concern | Backend | Frontend | Match |
|---|---|---|---|
| Route | `@RequestMapping("/deliverables")` + `GET /{deliverableId}/safety-review` | `GET /deliverables/${id}/safety-review` | ✅ |
| Verdict enum | `SafetyVerdict{PASS,REVIEW,FAIL}` (Jackson → name) | `'PASS'\|'REVIEW'\|'FAIL'` | ✅ exact |
| Check status enum | `SafetyCheckStatus{PASS,WARNING,FAIL}` | `'PASS'\|'FAIL'\|'WARNING'` | ✅ exact (order irrelevant) |
| `SafetyCheck` fields | `id, label, status, detail` (camelCase) | `id, label, status, detail?` | ✅ |
| `detail` nullability | nullable + `@JsonInclude(NON_NULL)` → omitted | `detail?: string` **optional** | ✅ absence tolerated |
| Response fields | `overallVerdict, checks, score, computedAt` | same, `score?`/`computedAt?` **optional** | ✅ |
| `score`/`computedAt` null | nullable + NON_NULL → omitted | optional; card never reads them | ✅ harmless |

The **critical enum-casing check passes**: no `warn`/`review`/lowercase drift — Java enum names
serialize verbatim to the exact FE literals. The card keys `VERDICT_CONFIG[review.overallVerdict]` and
`CHECK_STATUS_CONFIG[check.status]`; because the literals match exactly there is no `undefined`-lookup
crash. `check.id` (the 10 fixed GARM ids) is the load-bearing key and is server-authoritative in live
mode. **Why #3 survives NON_NULL where #4 doesn't:** every FE nullable/derived field here is typed
**optional (`?`)**, so an omitted key (`undefined`) is valid — and the card never guards with
`!== null`.

**Non-blocking nit (mock only):** `src/lib/api.ts:1611-1613` still ships the 3 illustrative mock
check ids (`disclosure`, `brand_mention`, `garm_risk`) that Vikram's doc and Ananya's own plan said
to reconcile to the 10 GARM ids. This is `!isLive()` mock data — cosmetic in demo mode, zero live
impact. Fold into #4's FE pass or a fast-follow.

### #4 ContentPerformanceItem — CHANGES-REQUIRED (BLOCKING before live)

Field **names** all match (camelCase both sides) and the route matches
(`@RequestMapping("/analytics/creators")` + `GET /{creatorId}/media` ↔
`GET /analytics/creators/${creatorId}/media`). **The break is nullability**, and it is exactly the
class of mismatch tsc cannot see:

`AnalyticsDtos.ContentPerformanceResponse` (`:166-181`) carries `@JsonInclude(JsonInclude.Include
.NON_NULL)`, and `reach`/`impressions` are `Long` (nullable) while `engagementRate` is a nullable
`BigDecimal` (null whenever `reach` is null/zero — `AnalyticsService.engagementRate():362-369`).
**With NON_NULL, a null field is OMITTED from the JSON, never sent as `null`.** The FE contract
assumes the opposite:

- `src/lib/api.ts:2677-2679` types `reach: number` and `impressions: number` as **required
  non-null**. When Meta didn't report reach/impressions (common for fresh posts / limited insights),
  the key is absent → `item.reach` is `undefined` → `ContentPerformancePanel.tsx:122,126`
  `formatCompact(undefined)` → `String(undefined)` renders the literal text **`"undefined"`**.
- `ContentPerformancePanel.tsx:132` guards the rate with `item.engagementRate !== null`. The backend
  never sends `null` (it omits), so the value is `undefined`; `undefined !== null` is **true** →
  renders **`"undefined%"`** on every post lacking reach. The guard never fires for its intended case.

This is a definite live-display defect (not a rare edge), advisory-only surface, no money/security
impact — but it must be fixed before the panel is shown live.

**Exact fix (frontend — idiomatic; this is how the other NON_NULL analytics DTOs are already
consumed):**

1. `src/lib/api.ts:2677-2678` — make the wire-nullable stats nullable:
   ```ts
   reach: number | null;
   impressions: number | null;
   ```
   (`engagementRate: number | null` at `:2679` is already correct — the type is fine; the *consumer
   guard* is the bug.)
2. `src/components/analytics/ContentPerformancePanel.tsx:17-19` — make `formatCompact` total over the
   wire shape:
   ```ts
   function formatCompact(n: number | null | undefined): string {
     if (n == null) return '—';
     return n >= 1000 ? `${(n / 1000).toFixed(1)}K` : String(n);
   }
   ```
3. `src/components/analytics/ContentPerformancePanel.tsx:132` — use loose null check so an omitted
   (`undefined`) field is treated as "no rate":
   ```ts
   {item.engagementRate != null ? `${item.engagementRate}%` : '—'}
   ```

(Backend-side alternative — dropping `@JsonInclude(NON_NULL)` on this one record so nulls serialize
explicitly — is **not** sufficient on its own: `reach`/`impressions` would then arrive as JSON `null`
which still violates the FE's required-`number` typing. The FE fix is required either way and is the
consistent choice, so keep the backend as-is.)

---

## General checks (item 4)

- **Advisory #3 truly cannot block approve/reject — confirmed.** `DeliverableSafetyReviewService` is
  a pure `@Transactional(readOnly = true)` GET; nothing in `CreatorDeliverableController#submit` or
  `BrandDeliverableService.approve/requestRevision/reject` calls or awaits it. FE-side,
  `DeliverableSafetyReviewCard` renders `null` when `review` is null and has no button-disable logic;
  Ananya's doc confirms `DeliverableViewer`'s `canApprove`/`canRequestRevision` and handlers are
  untouched. All failure modes (`DELIVERABLE_NOT_FOUND` 404, `SAFETY_REVIEW_NO_CONTENT` 404,
  `SAFETY_REVIEW_UNAVAILABLE` 503) degrade to `review: null` in the hook — never a 500, never a
  blocking banner.
- **Info-barrier / money path — no regression from these four.** #2 preserves the both-signed→escrow
  gate and changes no debit path. #4 is read-only analytics behind
  `MetricsAuthorizationService.resolveAuthorizedCreatorProfileId` (`AnalyticsService:307-308`) — same
  IDOR-closed authz as `/metrics`,`/scores`,`/demographics`.
- **#3 info-barrier — NOT self-cleared; Kabir gate REQUIRED.** Vikram explicitly (and correctly)
  flagged that forwarding a creator-authored deliverable caption to the GARM classifier is a security
  judgment that has not been independently red-teamed. Mitigations in place are sound as *defense in
  depth* — `SensitiveTextRedactor.redact` before the call
  (`DeliverableSafetyReviewService:125`), output consumed as enum/numeric structured data only (never
  echoed back as free text), same classifier + untrusted-input handling already used for polled Meta
  captions. My architectural read: because the deliverable belongs to the brand's own commissioned
  campaign, this does not cross the brand/creator barrier the way a cross-tenant read would — but that
  is a judgment for Kabir to confirm, not for me or Vikram to self-approve. **This must be Kabir's
  explicit sign-off before #3 ships live.**

---

## Gate

- **#1, #2, #3 → PROCEED** to Meera (local build/test) → Kavya (QA) → Kabir (#3 info-barrier
  red-team, blocking-before-live).
- **#4 → LOOP to Ananya** for the 3-point FE null-handling fix above (small). Re-joins the pipeline
  after; does not hold #1/#2/#3.
- No Swapnil escalation — no cost/stack/security-breach trigger. TECH-STACK.md unaffected.
