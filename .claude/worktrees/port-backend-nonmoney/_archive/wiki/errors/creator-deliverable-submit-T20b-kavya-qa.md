# QA Review: Creator Deliverable Submit UI — Task #20b (Kavya)

**Reviewer:** Kavya Patel (QA Lead)  
**Date:** 2026-07-09 (~21:15 IST)  
**Verdict:** ✅ **APPROVED** — routed to Meera build confirm → Kabir (submit surface carry-forward)  
**Scope:** Ananya Task #20b — upload-then-submit UI wiring vs Vikram Task #20 API  
**Reference:** `TASK_INBOX.md` Task #20b; `wiki/tech/creator/09_CREATOR_DELIVERABLES_SPEC.md` §4.3–4.4; prior backend QA (`creator-deliverable-submit-T20-kavya-qa.md`), upload UI QA (`creator-deliverable-upload-T19b-kavya-qa.md`)  
**Reviewed Files:**
- `src/lib/api.ts` (`creatorDeliverables.submit`, `CreatorDeliverableSubmitPayload`)
- `src/pages/creator-chat.tsx` (`handleSubmitDeliverableForm`, refresh paths)
- `src/components/creator/deal-room/deliverable-submission.tsx` (button copy, error surfacing)
- `influora-api/src/main/java/com/influora/web/CreatorDeliverableController.java` (submit contract cross-check)
- `influora-api/src/main/java/com/influora/web/dto/deliverable/CreatorDeliverableDtos.java` (`SubmitRequest`)

---

## Executive Summary

Creator deliverable **submit UI wiring passes QA**. `DeliverableSubmission` now runs a two-step flow: `upload` → `getStatus` → conditional `submit` when `actions.canSubmit`, then refreshes the deliverables picker/tab (`loadDealDeliverables`) and deal list (`fetchDeals`). Live `creatorDeliverables.submit` targets `POST /creator/deliverables/{id}/submit` with optional `{ finalCaption, hashtags, notes }` matching backend `SubmitRequest`; hashtags are auto-extracted from `finalCaption` (same helper as upload). Mock mode exercises the full path without network.

Button copy honestly reflects the combined flow: **"Upload & submit for review"** / **"Uploading & submitting..."**. Errors surface via `deliverableSubmitError` → destructive Alert; form state is retained on failure for retry.

`npm run build` **PASS** (Vite 6.4.2, 4587 modules, ~1m 10s, exit 0). No linter diagnostics on touched files. No debug logging.

Non-blocking carry-over: deal-header `deliverablesDone`/`deliverablesTotal` remain **0/0** from backend `DealService.toDealResponse` (M-3 from Task #15); tab item statuses refresh correctly via `dealDeliverableStatuses`. Silent success if `canSubmit` is false after upload (unlikely post-upload). Stale gap-banner copy references list API as "not built" though Task #19c shipped.

---

## Build Verification

| Gate | Result | Evidence |
|------|--------|----------|
| `npm run build` | ✅ **PASS** | Vite 6.4.2, 4587 modules, built ~1m 10s, exit 0 |
| ESLint / TS on touched files | ✅ **PASS** | No linter diagnostics |
| `console.log` / debug code | ✅ **PASS** | None in reviewed files |

**Command run (2026-07-09):**
```bash
npm run build
# → ✓ 4587 modules transformed; ✓ built in 1m 10s
```

---

## Task #20b Definition of Done — Verification

| DoD Item | Result | Evidence |
|----------|--------|----------|
| `api.creatorDeliverables.submit` live path | ✅ PASS | `api.ts` L1246–1277 → `POST /creator/deliverables/${id}/submit` |
| Optional `{ finalCaption, hashtags, notes }` | ✅ PASS | `CreatorDeliverableSubmitPayload` L1077–1081; body built L1266–1270 |
| Hashtags auto-extracted from caption | ✅ PASS | `extractHashtags` on `finalCaption` L1262–1264 |
| Mock path in `!isLive()` | ✅ PASS | L1254–1259 returns mock `SUBMITTED` |
| Upload then submit when `canSubmit` | ✅ PASS | `creator-chat.tsx` L819–829 |
| Refresh deliverables list after success | ✅ PASS | `loadDealDeliverables(selectedDeal.id)` L831 |
| Refresh deal counts after success | ✅ PASS | `fetchDeals()` L832 (backend counts still 0 — see L-20b-3) |
| Button copy reflects upload + submit | ✅ PASS | `deliverable-submission.tsx` L131, L282 |
| `npm run build` PASS | ✅ PASS | Executed this review |

---

## API Contract Cross-Check

| Frontend call | Backend route | Match |
|---------------|---------------|-------|
| `creatorDeliverables.submit(id, { finalCaption, notes })` | `POST /creator/deliverables/{id}/submit` — `SubmitRequest(finalCaption, hashtags, notes)` | ✅ |
| Hashtags omitted | Auto-derived from `finalCaption` via `extractHashtags` | ✅ (upload parity) |
| Auth | `role: 'creator'` Bearer token via `http.request` | ✅ |
| Response type | `CreatorDeliverableSubmitResponse` — `deliverableId`, `status`, `message` | ✅ |

```1250:1277:src/lib/api.ts
  submit: async (
    deliverableId: string,
    payload?: CreatorDeliverableSubmitPayload,
  ): Promise<CreatorDeliverableSubmitResponse> => {
    if (!isLive()) {
      return mockOr({
        deliverableId,
        status: 'SUBMITTED',
        message: 'Submitted for brand review',
      });
    }

    const hashtags =
      payload?.hashtags ??
      (payload?.finalCaption ? extractHashtags(payload.finalCaption) : []);

    const body: CreatorDeliverableSubmitPayload = {
      finalCaption: payload?.finalCaption,
      hashtags: hashtags.length > 0 ? hashtags : undefined,
      notes: payload?.notes,
    };

    return http.request<CreatorDeliverableSubmitResponse>(
      'POST',
      `/creator/deliverables/${deliverableId}/submit`,
      { role: 'creator', body },
    );
  },
```

**Note:** Legacy `deliverables.submit` (L970–977) still points to `/deliverables/${id}/submit` with `{ fileUrls }` — **not used** by creator-chat. See L-20b-4.

---

## Upload-Then-Submit Flow Review

```814:841:src/pages/creator-chat.tsx
  const handleSubmitDeliverableForm = async (data: DeliverableSubmissionData) => {
    if (!selectedDeal) return;
    setIsSubmittingDeliverable(true);
    setDeliverableSubmitError(null);
    try {
      await api.creatorDeliverables.upload(data.deliverableId, {
        file: data.file,
        caption: data.caption,
        creatorNotes: data.caption,
      });
      const status = await api.creatorDeliverables.getStatus(data.deliverableId);
      if (status.actions.canSubmit) {
        await api.creatorDeliverables.submit(data.deliverableId, {
          finalCaption: data.caption,
          notes: data.caption,
        });
      }
      await loadDealDeliverables(selectedDeal.id);
      await fetchDeals();
      setShowDeliverableDialog(false);
    } catch (e) {
      setDeliverableSubmitError(
        e instanceof ApiError ? e.message : 'Submit failed. Try again.',
      );
      throw e;
    } finally {
      setIsSubmittingDeliverable(false);
    }
  };
```

| Step | Behavior | Status |
|------|----------|--------|
| 1. Upload multipart | `files` part + caption/hashtags query (Task #19) | ✅ |
| 2. Poll action flags | `getStatus` → `actions.canSubmit` gate | ✅ |
| 3. Submit JSON body | Only when `canSubmit` true | ✅ |
| 4. Refresh picker | `loadDealDeliverables` re-filters uploadable rows | ✅ |
| 5. Refresh deals | `fetchDeals` re-maps `mapDealToChatRoom` | ✅ |
| 6. Error path | `ApiError.message` → Alert; re-throw keeps dialog open | ✅ |
| 7. Loading UX | `isSubmittingDeliverable` disables footer button | ✅ |

**Post-submit picker filter:** Uploadable = `PENDING` / `DRAFT` / `REVISION_REQUESTED` and not `completed`. After submit, status becomes `SUBMITTED`/`RESUBMITTED` → row drops from picker — correct.

**Tab refresh:** `dealDeliverableStatuses` repopulated in `loadDealDeliverables`; `mapDeliverableRowToTabStatus` maps `SUBMITTED`/`RESUBMITTED` → `pending_review` — correct.

---

## UI Copy Review (`deliverable-submission.tsx`)

| Element | Copy | Status |
|---------|------|--------|
| Dialog description (new) | "Upload and submit your deliverable for review" | ✅ |
| Dialog description (revision) | "Submit a revised version…" | ✅ |
| Primary button (idle) | "Upload & submit for review" | ✅ |
| Primary button (loading) | "Uploading & submitting..." | ✅ |
| Error surfacing | `submitError` → destructive Alert L252–256 | ✅ |

---

## Functional Review

### Mock mode (dev)

1. Deal select → `loadDealDeliverables` → mock list + statuses.
2. Submit dialog → upload mock → `getStatus` (mock `canSubmit: true`) → submit mock → refresh.
3. Submitted deliverable removed from picker; tab shows `pending_review` when statuses loaded.

### Live mode

1. List + status from Vikram #19c/#19 endpoints.
2. Upload stores draft → `canSubmit` true when `files_json` populated → submit transitions to `SUBMITTED`.
3. Submit failure after successful upload: error Alert, dialog stays open; retry re-uploads (acceptable until resubmit-only path exists).

### Error handling matrix

| Path | Handling | Status |
|------|----------|--------|
| Upload 4xx/5xx | Error Alert; no submit attempted | ✅ |
| Submit 4xx/5xx (upload OK) | Error Alert; draft remains on server | ✅ |
| `getStatus` failure | Caught; generic error message | ✅ |
| `canSubmit` false after upload | Flow completes as success; no submit | ⚠️ L-20b-1 |
| List refresh failure | Uncaught if after submit success — rare | ✅ acceptable |

---

## Code Quality Checklist

| Check | Status |
|-------|--------|
| TECH-STACK.md: Vite SPA, `api.ts` envelope, `isApiLive()` gating | ✅ |
| No debug logs | ✅ |
| Typed payloads/responses | ✅ |
| Client MIME allowlist unchanged (Task #19b) | ✅ |
| shadcn Dialog/Alert patterns | ✅ |
| Auth via creator JWT (no path-param creator id) | ✅ |

---

## Findings (Non-Blocking)

### L-20b-1: Silent skip when `canSubmit` is false after upload
If `getStatus` returns `canSubmit: false` after a successful upload (race, backend bug, or stale cache), the UI closes the dialog and refreshes without calling submit — user may believe submission completed. Recommend surfacing a warning or treating as error. Unlikely in normal flow (upload → DRAFT + files → `canSubmit` true).

### L-20b-2: `notes` duplicates `caption` on submit (and upload)
`handleSubmitDeliverableForm` sets `notes: data.caption` and upload `creatorNotes: data.caption`. Harmless for backend; same as T19b L-2.

### L-20b-3: Deal progress counts not updated by `fetchDeals` (M-3 carry-forward)
`DealService.toDealResponse` hardcodes `deliverablesDone: 0`, `deliverablesTotal: 0` (L494–495). `fetchDeals` wiring is correct; header progress bar and `DealDeliverablesTab` `done`/`total` props won't reflect real counts until backend aggregates deliverable rows. **Tab `items`** refresh correctly via `dealDeliverableStatuses`.

### L-20b-4: Legacy `deliverables.submit` dead path
`api.ts` L970–977 retains old `/deliverables/{id}/submit` + `fileUrls` contract. Not referenced by creator-chat. Recommend deprecate or remove in a cleanup slice to prevent future misuse.

### L-20b-5: Stale gap-banner copy
Deliverables panel Alert (L1693–1696) still says list endpoint "is not built yet" though Task #19c shipped. Banner only renders on `NOT_IMPLEMENTED` from list call — unlikely in live with current backend. Update copy when touching panel next.

### L-20b-6: Partial failure retry re-uploads
Upload OK + submit fail → retry runs full upload again. Acceptable for sprint; optional follow-up: submit-only retry when draft already has files.

### L-20b-7: Caption 500-char hint not enforced
UI shows `{caption.length}/500` but no `maxLength` or submit guard. Backend `@Size` may reject — low priority.

### L-20b-8: No frontend unit tests
No Vitest coverage for upload-then-submit handler or `canSubmit` gate. Consistent with frontend test debt.

### Security carry-forward (Kabir)
- XSS on caption/notes/hashtags at brand review render — M-2 extension (Task #20 L-20-8).
- Upload prod NO-GO: M-19-2/3/4 unchanged.
- Submit IDOR — backend closed in Task #20; no new frontend vector.

---

## Hostile / Edge-Case Matrix

| Scenario | Expected | Status |
|----------|----------|--------|
| Mock mode full upload+submit | Dialog closes; picker refreshes | ✅ PASS |
| Live upload + submit happy path | `SUBMITTED` status; picker row removed | ✅ PASS (code review; E2E needs live stack) |
| Submit API 409 `INVALID_STATE` | Error Alert; dialog open | ✅ PASS |
| Submit API 400 `NO_CONTENT` | Should not occur after upload; would error | ✅ PASS |
| Upload fails | No submit call; error surfaced | ✅ PASS |
| `canSubmit` false after upload | Silent success | ⚠️ L-20b-1 |
| Live + empty uploadable list | Submit button disabled | ✅ PASS |
| Shipment gate (`hasShipment`) | Submit disabled until received | ✅ PASS (pre-existing) |
| Double dialog close (child + parent) | Harmless | ✅ PASS |

---

## QA Sign-Off

- [x] `npm run build` PASS
- [x] `creatorDeliverables.submit` live contract verified vs Task #20 backend
- [x] Upload-then-submit flow with `canSubmit` gate verified
- [x] Deliverables list + statuses refresh after success
- [x] `fetchDeals` called post-submit (deal count backend debt documented)
- [x] Button copy and error UX verified
- [x] No debug code; linter clean
- [x] TECH-STACK.md alignment
- [ ] Meera build confirm — **NEXT GATE**
- [ ] Kabir submit UI surface review — optional carry-forward with Task #20 security gate

**Kavya verdict: ✅ APPROVED.** Route to Meera build confirm. Kabir may bundle frontend submit surface with Task #20 red-team (no new blockers identified).

---

**Document Control:** Created 2026-07-09 by Kavya (Task #20b). Prior: `creator-deliverable-submit-T20-kavya-qa.md` (backend). Next: Meera build gate.
