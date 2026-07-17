# QA Review: Creator Deliverable Upload UI — Task #19b (Kavya)

**Reviewer:** Kavya Patel (QA Lead)  
**Date:** 2026-07-09 (~18:00 IST)  
**Verdict:** ✅ **APPROVED** — routed to Meera build confirm → Priya sign-off on deliverables slice  
**Scope:** Ananya Task #19b — `creator-chat.tsx` deliverable upload UI wiring vs Vikram Task #19 API  
**Reference:** `TASK_INBOX.md` Task #19b; `wiki/tech/creator/09_CREATOR_DELIVERABLES_SPEC.md` §4.3; prior Task #19 backend QA (`creator-deliverable-T19-kavya-qa.md`)  
**Reviewed Files:**
- `src/pages/creator-chat.tsx`
- `src/lib/api.ts` (`creatorDeliverables`, `uploadMultipart`, `isApiLive`)
- `src/components/creator/deal-room/deliverable-submission.tsx`
- `influora-api/src/main/java/com/influora/web/CreatorDeliverableController.java` (contract cross-check)

---

## Executive Summary

Creator deliverable upload UI wiring **passes QA**. `DeliverableSubmission` is connected to `api.creatorDeliverables.upload` + `getStatus`; deal select triggers `loadDealDeliverables` via `listForDeal`. Live mode **fails closed** when the list endpoint is missing: honest gap banner in the deliverables panel, submit button disabled with explanatory `title`, and `NOT_IMPLEMENTED` surfaced only via `ApiError.code` (no silent mock fallback in prod). `npm run build` **PASS** (Vite 6.4.2, 4587 modules, ~44s, zero errors).

Multipart contract matches backend: `files` form part, optional `thumbnail`, query params `caption` / `creatorNotes` / repeated `hashtags` (Spring `List<String>` binding). Mock mode exercises full picker + upload path against `mockCreatorDeliverableStatuses`.

Non-blocking polish: silent client-side MIME rejection in file picker, `creatorNotes` mirrors `caption` on submit, no frontend unit tests (project-wide debt). Backend list API gap is intentional and documented — upload cannot be E2E-tested in live until Vikram ships `GET /creator/deliverables?collaboration_id=`.

---

## Build Verification

| Gate | Result | Evidence |
|------|--------|----------|
| `npm run build` | ✅ **PASS** | Vite 6.4.2, 4587 modules, built ~44s, exit 0 (non-blocking `baseUrl` duplicate + chunk-size warnings) |
| ESLint / TS on touched files | ✅ **PASS** | No linter diagnostics on `creator-chat.tsx`, `api.ts`, `deliverable-submission.tsx` |
| `console.log` / debug code | ✅ **PASS** | None in `creator-chat.tsx` or `deliverable-submission.tsx` |

**Command run (2026-07-09):**
```bash
npm run build
# → ✓ 4587 modules transformed; ✓ built in 44.20s
```

---

## Task #19b Definition of Done — Verification

| DoD Item | Result | Evidence |
|----------|--------|----------|
| Wire `DeliverableSubmission` → `upload` + `getStatus` | ✅ PASS | `handleSubmitDeliverableForm` L814–836; dialog L1848–1855 |
| Load picker from `listForDeal` | ✅ PASS | `loadDealDeliverables` L759–802; effect L804–807 |
| Refresh deal + deliverables tab after upload | ✅ PASS | `loadDealDeliverables` + `fetchDeals` post-upload L825–826 |
| Multipart `files` part + caption/hashtags query | ✅ PASS | `api.ts` L1213–1230; matches `CreatorDeliverableController.upload` |
| Live mode honest list API gap | ✅ PASS | `listForDeal` throws `NOT_IMPLEMENTED` L1147–1150; catch sets `deliverablesListGap` L796–797; banner L1682–1693; submit disabled L1186–1194 |
| `npm run build` PASS | ✅ PASS | Executed this review |

---

## API Contract Cross-Check

| Frontend call | Backend route | Match |
|---------------|---------------|-------|
| `creatorDeliverables.upload(id, { file, caption, … })` | `POST /creator/deliverables/{id}/upload` — `@RequestPart("files")`, optional `thumbnail`, `@RequestParam` caption/hashtags/creatorNotes | ✅ |
| `creatorDeliverables.getStatus(id)` | `GET /creator/deliverables/{id}/status` | ✅ |
| `creatorDeliverables.listForDeal(dealId)` | `GET /creator/deliverables?collaboration_id=` — **not built**; live throws `NOT_IMPLEMENTED` | ✅ (honest gap) |
| `http.uploadMultipart` | No `Content-Type` header (browser sets boundary); Bearer `creator` token | ✅ |

### Multipart / query alignment

```1213:1230:src/lib/api.ts
    const formData = new FormData();
    formData.append('files', payload.file);
    if (payload.thumbnail) {
      formData.append('thumbnail', payload.thumbnail);
    }

    const hashtags = payload.hashtags ?? (payload.caption ? extractHashtags(payload.caption) : []);

    return http.uploadMultipart<CreatorDeliverableUploadResponse>(
      `/creator/deliverables/${deliverableId}/upload`,
      formData,
      'creator',
      {
        caption: payload.caption,
        creatorNotes: payload.creatorNotes,
        hashtags: hashtags.length > 0 ? hashtags : undefined,
      },
    );
```

- `files` part name matches `@RequestPart("files")`.
- Array hashtags use repeated query keys via `uploadMultipart` L250–251 — correct for Spring `List<String>`.
- Hashtags auto-extracted from caption when not explicitly provided.

---

## Live Mode Gap Handling

```783:801:src/pages/creator-chat.tsx
    setDeliverablesLoading(true);
    setDeliverablesListGap(false);
    setDeliverableSubmitError(null);
    try {
      const items = await api.creatorDeliverables.listForDeal(roomId);
      // ...
    } catch (e) {
      setDealDeliverables([]);
      setDealDeliverableStatuses([]);
      if (e instanceof ApiError && e.code === 'NOT_IMPLEMENTED') {
        setDeliverablesListGap(true);
      }
    } finally {
      setDeliverablesLoading(false);
    }
```

| Behavior | Status | Notes |
|----------|--------|-------|
| Gap banner copy names missing endpoint | ✅ | Alert L1682–1693 references `GET /creator/deliverables` |
| Submit button disabled in live + gap | ✅ | L1186 `(isApiLive() && (deliverablesListGap \|\| dealDeliverables.length === 0))` |
| Tooltip explains why disabled | ✅ | L1191–1194 |
| Mock mode unaffected | ✅ | `!isApiLive()` branch L767–780 uses mock list data |
| No prod mock fallback for list | ✅ | `isLive()` inside `listForDeal` throws before network |

---

## Functional Review

### Mock mode (dev)

1. Deal select → `loadDealDeliverables` → mock rows from `mockCreatorDeliverableStatuses`.
2. Uploadable filter: `PENDING` / `DRAFT` / `REVISION_REQUESTED` and not `completed`.
3. Submit → `upload` mock → `getStatus` → refresh list + `fetchDeals`.
4. Deliverables tab uses `dealDeliverableStatuses` when populated (`mapStatusResponseToTabItem`).

### Live mode (current backend)

1. List call fails with `NOT_IMPLEMENTED` → gap banner + empty picker + submit disabled.
2. Upload API is reachable **if** caller has a deliverable id (blocked at UI until list ships).
3. Upload errors surface via `deliverableSubmitError` → `DeliverableSubmission` Alert L252–255.

### Error handling

| Path | Handling | Status |
|------|----------|--------|
| Upload failure | `ApiError.message` → `setDeliverableSubmitError`; form kept open for retry | ✅ |
| List failure (non-NOT_IMPLEMENTED) | Clears deliverables; no gap banner (generic empty state) | ✅ acceptable |
| Loading state | `deliverablesLoading` disables submit | ✅ |

---

## Code Quality Checklist

| Check | Status |
|-------|--------|
| TECH-STACK.md: Vite SPA, `api.ts` envelope, `isApiLive()` gating | ✅ |
| No debug logs | ✅ |
| Typed API interfaces (`CreatorDeliverableListItem`, `CreatorDeliverableUploadResponse`) | ✅ |
| Client MIME allowlist aligns with backend (`image/*`, `video/mp4`, `video/quicktime`) | ✅ |
| `useReducedMotion` / motion skills | N/A — no new motion in this task |
| shadcn Alert/Dialog patterns | ✅ |

---

## Findings (Non-Blocking)

### L-1: Silent invalid file type in picker
`deliverable-submission.tsx` L85–88 returns early on invalid MIME with no user-visible error. Recommend inline Alert or toast — does not block sprint gate.

### L-2: `creatorNotes` duplicates `caption` on upload
`handleSubmitDeliverableForm` sets both to `data.caption`. Harmless for backend; consider omitting `creatorNotes` or a dedicated notes field later.

### L-3: Live E2E upload blocked until list API
By design. Vikram Task follow-up: `GET /creator/deliverables?collaboration_id=`. UI correctly does not fake ids.

### L-4: Deliverables tab placeholder in live+gap
When `dealDeliverableStatuses` is empty, tab falls back to `deliverablesTotal` placeholder rows (L1015–1023). Acceptable until list API populates real statuses.

### L-5: No frontend unit tests
No Vitest coverage for `loadDealDeliverables` gap logic or upload handler. Consistent with current frontend test debt — flag for future slice.

### L-6: Backend security carry-over (Kabir Task #19)
H-19-1 / M-19-1 reportedly closed by Vikram (~17:30 IST). M-19-2 (rate limit), M-19-3 (heap DoS), M-19-4 (public URLs) remain backend concerns — do not block frontend QA sign-off.

---

## Hostile / Edge-Case Matrix

| Scenario | Expected | Status |
|----------|----------|--------|
| Live mode, list API missing | Gap banner + submit disabled | ✅ PASS |
| Mock mode, deal select | Picker populated from mock statuses | ✅ PASS |
| Upload API 4xx/5xx | Error Alert in dialog, form retained | ✅ PASS |
| Empty uploadable deliverables in live | Submit disabled (`dealDeliverables.length === 0`) | ✅ PASS |
| Shipment not received (`hasShipment`) | Submit disabled (pre-existing gate) | ✅ PASS |
| Invalid file type in drag/drop | Silent ignore | ⚠️ L-1 |
| Upload without deliverable id in live+gap | Dialog unreachable (button disabled) | ✅ PASS |
| Revision handler → upload dialog | `setShowRevisionHandler(true)` never called in codebase | N/A (dead path) |

---

## QA Sign-Off

- [x] `npm run build` PASS
- [x] `creatorDeliverables.upload` / `getStatus` / `listForDeal` contract verified
- [x] Live mode honest list API gap (banner + disabled submit)
- [x] Mock mode full upload path verified (code review)
- [x] No debug code; linter clean on touched files
- [x] TECH-STACK.md alignment (`isApiLive`, envelope client, creator role token)
- [ ] Meera build confirm — **NEXT GATE** (redundant re-run acceptable)
- [ ] Live E2E upload — **blocked on Vikram list API** (expected)

**Kavya verdict: APPROVED.** Route to Meera build confirm, then Priya deliverables slice sign-off. Vikram unblocks live E2E with `GET /creator/deliverables?collaboration_id=`.

---

**Document Control:** Created 2026-07-09 by Kavya (Task #19b). Prior: `creator-deliverable-T19-kavya-qa.md` (backend). Next: Meera build gate.
