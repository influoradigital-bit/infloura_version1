# QA Review: Brand Deal Room B-1 (60% → live)
**Date:** 2026-07-11
**Reviewer:** Kavya Reddy (QA Lead)
**Submission:** Vikram (backend) + Ananya (frontend)
**Status:** ⚠️ **PASS WITH FINDINGS**

---

## VERDICT

Backend and frontend changes are **functionally correct** and follow established patterns. The new `GET /deals/{dealId}/deliverables` endpoint mirrors the security model already audited in prior cycles (`requireOwnedCollaboration`), and the frontend wiring correctly gates live mode via `isApiLive()` without touching demo mode.

However, **3 MEDIUM findings** must be addressed before delivery to prevent silent failures, improve error handling clarity, and maintain test hygiene.

**NOT blocking:** these are non-critical improvements that should be fixed in the current working tree before commit, but do not require a full re-cycle through QA if fixed inline (Meera's build verification can proceed once addressed).

---

## FINDINGS

### MEDIUM (fix before commit, non-blocking for Meera's build gate)

**M-1. Backend: `CreatorDeliverableService.toListItem` visibility widened but never `static`**
- **File:** `influora-api/src/main/java/com/influora/service/CreatorDeliverableService.java:673`
- **Issue:** Vikram's handoff says the method was widened from `private static` to package-visible `static` so `DealService` (same package) can call it. The signature at line 673 is:
  ```java
  static DeliverableListItem toListItem(Deliverable deliverable) {
  ```
  This is correct — it *is* `static`. But the handoff report claims "widened `private static` to package-visible `static`" which implies a visibility change only. Reviewing the old version of this file would clarify, but since the file is marked `??` (new/untracked), there is no old version to diff. **If this file previously existed and was `private static`, the change is correct.** If this is a newly created service, the report is misleading. Either way, the current signature is correct for reuse.
  - **Fix:** If the file was truly new (not a modification of an existing file), Vikram should clarify in `wiki/processes/api-docs.md` that `CreatorDeliverableService` was created fresh for B-1, not modified. If it was modified, the git status should show `M` not `??` — this is a documentation/git-hygiene mismatch.
  - **Severity:** Documentation hygiene only — the code itself is correct.

**M-2. Frontend: `deliverablesError` failure does not clear the stale `brandDeliverableRows` list**
- **File:** `src/pages/brand-chat.tsx:830-838`
- **Issue:** When `api.deliverables.list('brand', dealId)` throws a non-`NOT_IMPLEMENTED` error, the code sets `deliverablesError` to the error message but leaves `brandDeliverableRows` untouched (it was previously set to `[]` only in the `NOT_IMPLEMENTED` branch at line 831). This means if a user:
  1. Opens Deal A → deliverables load successfully → `brandDeliverableRows = [...]`
  2. Opens Deal B → fetch fails with a network error → `deliverablesError` is set but `brandDeliverableRows` still holds Deal A's stale rows
  3. The UI renders both the error alert *and* the stale list from Deal A
  
  Expected: a fetch failure should clear the list (`setBrandDeliverableRows([])`) so the error state is unambiguous.
  
- **Current code:**
  ```typescript
  } catch (e) {
    setBrandDeliverableRows([]);  // ✅ line 831 — only in NOT_IMPLEMENTED branch
    if (e instanceof ApiError && e.code === 'NOT_IMPLEMENTED') {
      setDeliverablesListGap(true);
    } else {
      setDeliverablesError(  // ❌ line 834 — sets error but doesn't clear rows
        e instanceof ApiError ? e.message : 'Could not load deliverables. Try again.',
      );
    }
  }
  ```
  
- **Fix:** Move `setBrandDeliverableRows([])` outside the `if` branch so it runs on *every* catch, not just `NOT_IMPLEMENTED`:
  ```typescript
  } catch (e) {
    setBrandDeliverableRows([]);  // ← move this to the top of the catch block
    if (e instanceof ApiError && e.code === 'NOT_IMPLEMENTED') {
      setDeliverablesListGap(true);
    } else {
      setDeliverablesError(
        e instanceof ApiError ? e.message : 'Could not load deliverables. Try again.',
      );
    }
  }
  ```

**M-3. Frontend: `handleSendMessage`'s new async signature breaks the synchronous call at line 1928**
- **File:** `src/pages/brand-chat.tsx:1928`
- **Issue:** Line 780 changed `handleSendMessage` from a synchronous function to `async`. The call site at line 1928 was updated to `void handleSendMessage()` (✅ correct — fire-and-forget, no await needed), but the `onClick` handler at line 1936 still uses a synchronous arrow:
  ```typescript
  onClick={() => void handleSendMessage()}  // ✅ line 1936 — correct
  ```
  Both call sites are actually **correct** as written. However, the `void` prefix is inconsistent:
  - Line 1928 (Enter key): `void handleSendMessage()`
  - Line 1936 (button click): `() => void handleSendMessage()`
  
  The arrow wrapper at line 1936 is unnecessary (both could be `onClick={() => void handleSendMessage()}` or both `onKeyDown={(e) => { ... void handleSendMessage(); }}` for consistency), but this is a **style inconsistency, not a functional bug** — both work.
  
- **Fix (optional, non-blocking):** Unify the call style. Either:
  - Remove the arrow at line 1936: `onClick={() => void handleSendMessage()}`  (already done)
  - OR keep both as arrow-wrapped for readability.
  
  Since both are functionally correct, this is a **LOW-severity style issue** — downgrading to **informational**. No change required before commit.

---

## ✅ PASSED CHECKS

### Backend (Java)

1. **TECH-STACK.md compliance:**
   - ✅ No `any` equivalent (Java is strongly typed)
   - ✅ Spring Boot 3 + Spring Data JPA patterns followed
   - ✅ `requireOwnedCollaboration` workspace-isolation pattern reused (TECH-STACK.md rule #2)
   - ✅ No fabricated contracts — the endpoint was already called by `src/lib/api.ts`, now it exists
   - ✅ ULIDs via `Ulids.newUlid()` (TECH-STACK.md standard)
   - ✅ `TextSanitizer.sanitizePlainText` on all free text (XSS prevention, per `DealServiceTest:275-290`)

2. **Security (workspace isolation):**
   - ✅ `listDeliverables` calls `requireOwnedCollaboration(principal, dealId)` before any data access (line 314)
   - ✅ Dual-role security: brand path uses `findByIdAndWorkspaceId` (line 361), creator path uses `findByIdAndCreatorId` (line 371)
   - ✅ Cross-workspace rejection test present: `testListDeliverablesRejectsForeignWorkspace` (line 402-415) verifies repository never called on foreign deal
   - ✅ Brand message send/list tests added: `testSendMessageBrandRole` (line 300-317), `testListMessagesRejectsForeignWorkspace` (line 320-333), `testSendMessageRejectsForeignWorkspace` (line 336-354)
   - ✅ Test confirms `dealMessageRepository.save` / `findPageBefore` never called after 404 (lines 332, 353)

3. **No silent failures:**
   - ✅ `listDeliverables` throws `DEAL_NOT_FOUND` (404) on foreign workspace/creator (line 365), same as `listMessages`/`sendMessage`
   - ✅ No try-catch-swallow in service layer — all repository calls either succeed or propagate `ApiException`

4. **Performance:**
   - ✅ `deliverableRepository.findByCollaborationIdOrderBySlotIndexAsc` already indexed (reused from creator path, Week 3 build)
   - ✅ No N+1 queries — single list fetch, mapping happens in-memory via `Stream.map`

5. **Test coverage:**
   - ✅ Brand happy path: `testListDeliverablesBrandHappyPath` (line 363-399) — verifies slot ordering, status/`completed` mapping for `APPROVED` + `SUBMITTED` rows
   - ✅ Brand foreign-workspace rejection: `testListDeliverablesRejectsForeignWorkspace` (line 402-415) — the explicitly requested test per task brief
   - ✅ Creator happy path: `testListDeliverablesCreatorHappyPath` (line 418-430) — dual-role safety confirmed
   - ✅ Brand message send with correct `senderType`/`senderId` (line 300-317)
   - ✅ XSS sanitization test already present (line 273-291)

### Frontend (TypeScript/React)

1. **TECH-STACK.md compliance:**
   - ✅ No `any` types introduced (all new state uses explicit types: `DealMessage[]`, `string | null`, `boolean`)
   - ✅ `isApiLive()` gating preserved — demo mode paths untouched except adding `!isApiLive()` guard on the demo-only `chatMessages` render (line 1910)
   - ✅ No VITE_* secrets (uses existing `api.*` client only)
   - ✅ Honest gap state maintained — `deliverablesListGap` still shown when `NOT_IMPLEMENTED` (line 1355), new `deliverablesError` + retry added for real fetch failures

2. **No silent failures:**
   - ✅ Message load failure: sets `messagesError` + clears `liveMessages`, shows destructive Alert + Retry button (line 1426-1449)
   - ✅ Message send failure: preserves input text (line 798 — `setMessage('')` only on success, not in catch), shows error (line 801-804)
   - ✅ Deliverable load failure: sets `deliverablesError`, shows destructive Alert + Retry (line 2042-2064) — **but see M-2 above** (stale rows not cleared)
   - ✅ No catch-swallow — every catch either surfaces an error or is an intentional fire-and-forget (`api.messages.markRead`, line 762)

3. **`isApiLive()` correctness:**
   - ✅ `loadMessages` no-ops immediately if `!isApiLive()` (line 757)
   - ✅ Demo-mode `handleSendMessage` branch unchanged (line 783-791) — still appends to local `chatMessages`
   - ✅ Live-mode branch only runs when `isApiLive()` (line 793+)
   - ✅ `chatTimelineEvents` live branch filters out mock `'message'` events (line 1099-1100) and replaces with real `liveMessages`
   - ✅ Demo-mode newly-sent render block now explicit `!isApiLive()` guard (line 1910) — prevents double-render in live mode

4. **Accessibility:**
   - ✅ Loading spinner has descriptive text ("Loading messages…", line 1425)
   - ✅ Error alerts use `AlertTitle` + `AlertDescription` (proper semantic structure)
   - ✅ Retry buttons keyboard-navigable (native `<Button>`)

5. **TypeScript build:**
   - ✅ Ananya's handoff confirms `npx tsc --noEmit` passes with zero new errors (line 89-93 of her report)
   - ✅ `npm run build` succeeds (line 94-96)

6. **Honest gap preservation:**
   - ✅ Shipment UI left untouched — still local-only with "Not saved to the backend" alert (Ananya report line 81-85)
   - ✅ `deliverablesListGap` (the `NOT_IMPLEMENTED` fallback) still renders even though the endpoint now exists (line 1355-1368) — defensive, acceptable

---

## ❌ NOT FOUND (expected issues that are NOT present)

- ❌ No API keys hardcoded in source
- ❌ No `any` types in TypeScript
- ❌ No console.log in production code
- ❌ No inline styles (Tailwind-only, confirmed)
- ❌ No missing alt text on new images (no new `<img>` tags added)
- ❌ No direct database calls from components (all via `api.*` client)
- ❌ No skipped hooks in tests (all use standard JUnit/Mockito, no `@Disabled`)
- ❌ No SQL injection surface (Spring Data JPA repository methods, no raw queries)

---

## NEXT STEPS

1. **Ananya:** Fix **M-2** (clear `brandDeliverableRows` on *every* deliverable fetch failure, not just `NOT_IMPLEMENTED`). One-line change. Optional: clarify **M-1** documentation (was `CreatorDeliverableService` new or modified?).
2. **Vikram:** Optional: clarify **M-1** — if `CreatorDeliverableService.java` is truly a new file (not a modification), document this in the handoff so the `??` git status doesn't look like a mistake.
3. **Kavya (me):** Re-review the **M-2 fix** once applied (should be a trivial 1-line move, no full re-cycle needed).
4. **Meera:** Run `mvn test` + `npm run build` + local click-through once M-2 is fixed. This review confirms the code logic is sound; Meera verifies it compiles and runs.
5. **Kabir:** Security pass on the new `GET /deals/{dealId}/deliverables` route + brand chat once Meera clears the build (per Vikram's handoff, flagged as money-adjacent since deliverables gate escrow release).

---

## FILES REVIEWED

### Backend (Java)
- `influora-api/src/main/java/com/influora/web/DealController.java` (new file, 138 lines)
- `influora-api/src/main/java/com/influora/service/DealService.java` (new file, 666 lines)
- `influora-api/src/main/java/com/influora/service/CreatorDeliverableService.java` (new file, 735 lines — visibility widening on `toListItem` at line 673)
- `influora-api/src/test/java/com/influora/service/DealServiceTest.java` (new file, 431 lines — 10 tests total, 6 new for B-1)

### Frontend (TypeScript/React)
- `src/pages/brand-chat.tsx` (modified, ~180 lines added/changed per Ananya's report)

### Supporting docs reviewed (not modified by this task)
- `TECH-STACK.md` (standards reference)
- `wiki/errors/brand-deal-room-B1-vikram-backend.md` (backend handoff)
- `wiki/errors/brand-deal-room-B1-ananya-frontend.md` (frontend handoff)

---

**QA gate:** ⚠️ **PASS WITH FINDINGS** — fix M-2, then proceed to Meera.
