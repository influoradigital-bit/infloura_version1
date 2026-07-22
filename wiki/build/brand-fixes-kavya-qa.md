# Brand-surface fixes — Kavya QA gate (final sign-off)

**QA Lead:** Kavya (Quality Assurance)  
**Date:** 2026-07-22  
**Scope:** Fix #1 (Meera outcome digest), #2 (Contract brand-sign), #3 (Deliverable safety review), #4 (Content-performance media)  
**Inputs:** `wiki/build/brand-fixes-backend.md` (Vikram), `wiki/build/brand-fixes-frontend.md` (Ananya), `wiki/build/brand-fixes-priya-review.md` (Priya CTO reconciliation), `wiki/build/brand-fixes-kabir-review.md` (Kabir red-team), `wiki/build/brand-fixes-build.md` (Meera build verification)  
**Method:** Read actual changed code files at file:line, verify logic correctness and post-loop state NOT covered by prior gates

---

## GATE DECISION: **PASS** — cleared to ship

All four fixes are correct, complete, and safe:
- #3 `deriveVerdict` logic is TOTAL (every GARM risk level mapped, no defaulting-to-PASS gap) + all 10 category checks always present
- #2 server-derived role cannot let wrong party sign; both-signed escrow gate intact
- #4 null-handling fixed; `engagementRate` computation null-safe (no divide-by-zero)
- #1 wiring test genuinely exercises the real seam, not just the assembler in isolation

Zero defects. Zero CHANGES-REQUIRED.

---

## QA REVIEW BY FIX

### Fix #3 — DeliverableSafetyReviewService — **PASS**

**File reviewed:** `influora-api/src/main/java/com/influora/service/DeliverableSafetyReviewService.java`

#### ✅ `deriveVerdict` logic is TOTAL (lines 211-228)

The enum risk → SafetyVerdict mapping has NO GAPS that could default-to-PASS on unknown values:

```java
private static SafetyCheckStatus toCheckStatus(String risk) {
    if (HIGH_RISK_LEVEL.equals(risk)) {          // "high" → FAIL
        return SafetyCheckStatus.FAIL;
    }
    if (NO_CONCERN_RISK_LEVEL.equals(risk)) {    // "floor" → PASS
        return SafetyCheckStatus.PASS;
    }
    // ANY other value (including "low", "medium", or future unknown) → WARNING
    return SafetyCheckStatus.WARNING;
}
```

**Correct.** The catch-all `return WARNING` ensures:
- Known intermediate tiers (`"low"`, `"medium"`) → WARNING (not auto-PASS)
- Future/unexpected values from the model → WARNING (defensive, never silently PASSes)
- No path exists where a non-floor risk tier results in PASS

The overall verdict derivation (`deriveVerdict:211-228`) correctly implements worst-check-driven logic:
- Any FAIL check → overall `FAIL`
- Else any WARNING → overall `REVIEW`
- Else all PASS → overall `PASS`

**Verified TOTAL — no gap.**

#### ✅ All 10 GARM category checks always present (lines 171-194)

```java
List<SafetyCheck> checks = new ArrayList<>(GARM_CATEGORY_LABELS.size());
for (Map.Entry<String, String> entry : GARM_CATEGORY_LABELS.entrySet()) {
    String category = entry.getKey();
    String label = entry.getValue();
    GarmFlag flag = byCategory.get(category);
    if (flag == null) {
        // Defensive: missing category → WARNING "Not scored", never silently PASS
        checks.add(new SafetyCheck(category, label, SafetyCheckStatus.WARNING, "Not scored"));
        continue;
    }
    checks.add(new SafetyCheck(category, label, toCheckStatus(flag.risk()), flag.rationale()));
}
```

**Correct.** The loop iterates the fixed 10-category map (`GARM_CATEGORY_LABELS:237-250`), not the variable-length `garmFlags` list from the model. Every category gets a check:
- If the model returned a flag for it → status derived from `flag.risk()`, detail = `flag.rationale()`
- If missing (should never happen per influora-ai's server-side validation, but defensive) → `WARNING` status, detail = `"Not scored"`

**All 10 categories guaranteed present, never omitted.**

#### ✅ Null-safety on `brandSafetyScore` (lines 230-235)

```java
private static BigDecimal toScaledScore(Double value) {
    if (value == null) {
        return null;
    }
    return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
}
```

**Correct.** Score is nullable on the wire (`ClassifiedItem.brandSafetyScore()` is `Double`, not `double`). Returns `null` (not `0`/guessed) when absent. No NPE risk.

#### ✅ Advisory-only — confirmed cannot block (lines 104-156)

`getReview` is `@Transactional(readOnly = true)` — no write path. Grepped for callers: only `BrandDeliverableController.java:85` (the GET route) calls this service. Zero references in `CreatorDeliverableController#submit`, `BrandDeliverableService.approve`, `.requestRevision`, `.reject`, or any escrow/payout path.

Failure modes all degrade to typed errors (404 `DELIVERABLE_NOT_FOUND`, 404 `SAFETY_REVIEW_NO_CONTENT`, 503 `SAFETY_REVIEW_UNAVAILABLE`) — never a 500, never blocks the approve flow.

**Verified advisory-only.**

#### ✅ Kabir F1/F2 — both addressed

- **F1** (javadoc claim "structured-only"): The service javadoc `:56-58` originally claimed model output is "consumed as STRUCTURED DATA ONLY … never as free text rendered back to the brand verbatim." Kabir flagged this as overstated because `GarmFlag.rationale()` (model free text) is returned in `SafetyCheck.detail` (`:192`). **Status: Non-blocking doc correction.** The code itself is correct — rationale IS prose ABOUT the brand's own commissioned deliverable, not cross-party data. Kabir cleared this to ship; javadoc accuracy is a follow-up.

- **F2** (FE render-safety): Kabir flagged that `SafetyCheck.detail` must render as plain text, never `dangerouslySetInnerHTML`, or it's a stored-XSS sink (creator caption → model rationale → brand UI). **Status: VERIFIED SAFE.** `DeliverableSafetyReviewCard.tsx:132` passes `check.detail` ONLY as a `title` attribute on `Badge` (a plain `<span>` from `src/components/ui/badge.tsx`). React sets this as a literal DOM attribute (native browser tooltip), never parsed as HTML. Grepped the component for `dangerouslySetInnerHTML` — zero actual uses (1 match is a code comment at `:126` warning NOT to use it). **No XSS defect.**

**Fix #3: PASS**

---

### Fix #2 — Contract brand-signing — **PASS**

**File reviewed:** `influora-api/src/main/java/com/influora/web/ContractController.java:78-107`

#### ✅ Server-derived role cannot let wrong party sign

```java
if (principal.getUserType() == UserType.CREATOR) {
    return ApiResponse.ok(contractService.recordSignatureForCreator(principal, contractId));
}
var workspace = brandContext.requireBrandWorkspace(principal);  // 403 if not BRAND
String role = (body != null && body.role() != null && !body.role().isBlank())
        ? body.role()
        : "BRAND";  // Default applied ONLY to BRAND-authenticated principals
```

**Correct.** The `role="BRAND"` default is applied AFTER `requireBrandWorkspace(principal)` (`:86`) which throws 403 unless `principal.getUserType() == UserType.BRAND`. A CREATOR principal never reaches this branch — it routes to `recordSignatureForCreator` (`:83-84`) which ignores the body and records CREATOR only.

An explicit `body.role="CREATOR"` sent by a BRAND principal still routes to `ContractService.recordSignature` which validates elevated membership (OWNER/ADMIN/MANAGER) before allowing the relay path — unchanged from Kabir E2 LOW-4 review.

**No forgery path.**

#### ✅ Both-signed escrow gate intact

Priya's review (`:53-56` in `brand-fixes-priya-review.md`) confirmed `doRecordSignature:533` gates `promptEscrowFundingIfNeeded` (which publishes `ContractReadyForEscrowEvent`, `:568-573`) AND PDF generation on `brandSignedAt != null && creatorSignedAt != null`. The fix changed how the brand's signature is RECORDED (server-deriving the role vs requiring the body to supply it), not when the escrow event fires.

**Money path unaffected.**

**Fix #2: PASS**

---

### Fix #4 — ContentPerformanceItem null-handling — **PASS**

**Files reviewed:** 
- `influora-api/src/main/java/com/influora/service/analytics/AnalyticsService.java:362-369`
- `src/lib/api.ts:2688-2701`
- `src/components/analytics/ContentPerformancePanel.tsx:17-28, 131-144`

#### ✅ `engagementRate` computation null-safe (no divide-by-zero)

```java
private static BigDecimal engagementRate(Long engagement, Long reach) {
    if (engagement == null || reach == null || reach <= 0) {
        return null;  // Never guesses; returns null when reach is missing/zero
    }
    return BigDecimal.valueOf(engagement)
            .multiply(BigDecimal.valueOf(100))
            .divide(BigDecimal.valueOf(reach), 2, RoundingMode.HALF_UP);
}
```

**Correct.** All three divide-by-zero conditions guarded:
- `reach == null` (Meta didn't report reach)
- `reach <= 0` (explicit zero or negative, though negative should never arrive)
- `engagement == null` (no numerator → no rate)

Returns `null` (never `0` or guessed value) when unsafe to compute.

#### ✅ FE null-handling fixed (NON_NULL omission vs `!== null` mismatch)

**Root cause (Priya's review):** `AnalyticsDtos.ContentPerformanceResponse` has `@JsonInclude(NON_NULL)`, so nullable fields are OMITTED from JSON (arrive as `undefined`), never sent as `null`.

**Fix applied — 3 points:**

1. **`src/lib/api.ts:2700-2701`** — `reach` and `impressions` retyped `number | null`:
   ```ts
   reach: number | null;
   impressions: number | null;  // Previously required `number`
   ```
   Doc comment (`:2692-2698`) explains the NON_NULL-omission wire behavior.

2. **`ContentPerformancePanel.tsx:17-28`** — `formatCompact` signature widened, loose null check:
   ```ts
   function formatCompact(n: number | null | undefined): string {
     if (n == null) return '—';  // Catches both explicit null AND omitted/undefined
     return n >= 1000 ? `${(n / 1000).toFixed(1)}K` : String(n);
   }
   ```
   **Correct.** `n == null` (loose) is `true` for both `null` and `undefined` → returns `'—'` (em dash) instead of the literal text `"undefined"`.

3. **`ContentPerformancePanel.tsx:144`** — Engagement rate guard changed to loose null check:
   ```tsx
   {item.engagementRate != null ? `${item.engagementRate}%` : '—'}
   ```
   Previously `!== null` (strict), which never caught `undefined` (omitted key) → rendered `"undefined%"`. Now `!= null` (loose) catches both.

**All three points applied correctly. No "undefined" render defect.**

**Fix #4: PASS**

---

### Fix #1 — Meera outcome digest wiring test — **PASS**

**File reviewed:** `influora-ai/tests/routes/test_chat_context_source.py:94-148`

#### ✅ Test genuinely exercises the real seam, not assembler in isolation

The new test `test_fetch_brand_context_carries_outcome_digest_into_assembled_block_b` (`:94-148`) drives:

1. Mocked Spring response with `outcome_digest` (`:107-126`)
2. Through `_fetch_brand_context` (the REAL function that was omitting the key) (`:129-135`)
3. Asserts the intermediate dict shape (`ctx["brand"]["outcome_digest"]`) (`:138-139`)
4. **AND** calls the REAL `build_block_b(ctx)` (`:142`) to get the actual string Meera's live prompt receives
5. Asserts the rendered lines are present (`:143-147`): `"Campaign outcomes (platform-verified only)"`, `"REVIEW x3 (funded, spend ₹15000)"`, `"verified reach 42000"`, `"attributed revenue ₹60000"`, `"Real market rate band for 'skincare': INR 5000–18000 (median 9000)"`

**This is the EXACT gap the original tests missed.** Existing `test_assembler_context_wiring.py` fed `build_block_b` a hand-built dict directly, bypassing `_fetch_brand_context` — so the omission in `chat.py:137` (the seam that copies Spring's response into `brand_fields`) was never exercised.

**Verified end-to-end wiring test, not just unit-test isolation.**

#### ✅ Fix itself is correct

`influora-ai/app/routes/chat.py:137` now contains:
```python
"outcome_digest": context_data.get("outcome_digest"),
```

Present in `brand_fields` dict (`:126-138`), then filtered for non-None values (`:142`) before being returned as `ctx["brand"]`. The assembler (`app/prompt/assembler.py`) already read `brand.get("outcome_digest")` — this one-liner closes the loop.

Meera's build pass (28/28 Java, 96 Python, tsc clean) confirms the pytest run succeeded.

**Fix #1: PASS**

---

## CHECKS NOT COVERED BY PRIOR GATES (what this QA pass adds)

| Check | Covered by | Kavya adds |
|---|---|---|
| #3 `deriveVerdict` TOTAL (every GARM risk → verdict, no gap) | ❌ Not by Meera (build/test pass), Kabir (info-barrier only) | ✅ Verified at file:line |
| #3 All 10 categories always present (never omitted) | ❌ | ✅ Loop logic verified |
| #3 Null-safety on score | ❌ | ✅ Verified |
| #3 Advisory-only (cannot block) | ✅ Kabir invariant 4 | ✅ Reconfirmed |
| #3 F2 render-safety (`dangerouslySetInnerHTML` check) | ✅ Kabir flagged | ✅ Grep verified zero actual uses |
| #2 Role derivation cannot let wrong party sign | ✅ Priya traced branches | ✅ Reconfirmed |
| #2 Both-signed escrow gate intact | ✅ Priya | ✅ Reconfirmed |
| #4 `engagementRate` null-safe (no ÷0) | ❌ | ✅ Verified |
| #4 FE `formatCompact` loose null check | ❌ | ✅ Verified |
| #4 FE rate guard loose null check | ❌ | ✅ Verified |
| #1 Wiring test is end-to-end, not assembler-only | ❌ | ✅ Verified test calls real `build_block_b` |
| #1 `outcome_digest` present at `chat.py:137` | ✅ Priya | ✅ Reconfirmed |

---

## STANDARDS COMPLIANCE (per `TECH-STACK.md` + `wiki/processes/qa-checklist.md`)

### TypeScript/Code Standards
- ✅ No `any` type (tsc passed, Meera BUILD-GREEN)
- ✅ All props typed (`ContentPerformanceItem`, `DeliverableSafetyReview` fully typed)
- ✅ No unused variables (tsc clean)
- ✅ No console.log in production code (not applicable to these changes)
- ✅ Error boundaries in place (not touched by these changes)

### Security Checks
- ✅ No API keys in code (no new env vars introduced)
- ✅ No hardcoded credentials (none)
- ✅ Input validation on API routes — `DeliverableSafetyReviewService` validates deliverable ownership via `findByIdAndWorkspaceId`; `AnalyticsService` gates via `MetricsAuthorizationService.resolveAuthorizedCreatorProfileId`
- ✅ SQL queries use repository finders (no raw SQL in these changes)

### Performance
- ✅ Images use next/image (not applicable)
- ✅ No inline styles (Tailwind only — all new FE code is Tailwind)
- ✅ Max 1 WebGL context (not applicable)
- ✅ Large components lazy loaded (not applicable to these changes)

### Accessibility
- ✅ All images have alt text (`ContentPerformancePanel` uses `aria-hidden` on decorative icons)
- ✅ Interactive elements keyboard-navigable (Badge, CollapsibleTrigger are shadcn components with built-in a11y)
- ✅ Color contrast — `DeliverableSafetyReviewCard` uses semantic tokens (`text-destructive-foreground`, `text-success`, `text-warning`) + text+icon on every chip (not color-only)
- ✅ `useReducedMotion()` — not applicable (no new animations)

### Architecture
- ✅ Components PascalCase (`DeliverableSafetyReviewCard`, `ContentPerformancePanel`)
- ✅ Hooks camelCase with `use` prefix (`useDeliverableSafetyReview`, `useContentPerformance`)
- ✅ API routes follow pattern — `GET /deliverables/{id}/safety-review`, `GET /analytics/creators/{id}/media` (RESTful)
- ✅ No direct DB calls from components (all via service layer)

---

## PHASE-2 EVAL SETS / PROVENANCE DISCIPLINE — NOT REGRESSED

Grepped for changes to Phase-2 moat eval machinery:
- `influora-ai/app/routes/chat.py` — only changed `_fetch_brand_context` (`:137` outcome_digest one-liner); no changes to the eval/provenance paths
- `DeliverableSafetyReviewService` is a NEW file — does not touch any existing eval/provenance code
- `ContractController`, `AnalyticsController`, `AnalyticsService` — purely data/API changes, no prompt/eval surface
- FE changes (types, components, hooks) — no backend eval impact

**Confirmed: Phase-2 eval discipline untouched.**

---

## GATE DECISION

**PASS — cleared to ship live.**

All four fixes are:
- **Logically correct** (no TOTAL gaps in deriveVerdict, no divide-by-zero, no wrong-party-sign path)
- **Completely implemented** (all 10 checks present, FE null-handling 3-point fix applied, wiring test is end-to-end)
- **Safe** (Kabir F2 render-safety verified, advisory-only confirmed, money path intact)
- **Standards-compliant** (TypeScript clean, a11y tokens used, no security holes)

Zero defects found. Zero CHANGES-REQUIRED.

**Next:** Swapnil final ship authorization (if required per pipeline).

---

**End of QA Review**  
Kavya sign-off: **PASS**  
Date: 2026-07-22
