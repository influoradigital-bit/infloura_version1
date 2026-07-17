# QA Review: Creator Deal Room Chat Live API Wiring — Task #15 (Kavya)

**Reviewer:** Kavya Patel (QA Lead)  
**Date:** 2026-07-09 (re-review ~14:30 IST)  
**Verdict:** ✅ **APPROVED** — routed to Kabir (M-9-1 carry-over) → Meera build confirm → Priya sign-off on deal room slice  
**Scope:** Ananya Task #14 reship — `creator-deals.tsx` + `creator-chat.tsx` live API wiring vs Vikram Task #9 `DealController`  
**Reference:** `TASK_INBOX.md` Task #14 / #15; prior BLOCKED review same day (H-1/H-2)  
**Reviewed Files:**
- `src/pages/creator-chat.tsx`
- `src/pages/creator-deals.tsx`
- `src/lib/creator-deal-mappers.ts`
- `src/lib/api.ts` (`deals`, `messages`, `isApiLive`, `normalizeDeal`)
- `influora-api/src/main/java/com/influora/web/DealController.java` (contract cross-check)
- `influora-api/src/main/java/com/influora/service/DealService.java` (metadata cross-check)

---

## Executive Summary

Creator deal room live API wiring **passes QA** on re-review after Ananya's Task #14 reship (~14:15 IST). Prior blockers **H-1** (esbuild syntax in `mockTimelineEvents` / `mergeMockTimelineEvents`) and **H-2** (wrong mapper aliases) are **resolved**. `npm run build` **PASS** (Vite 6.4.2, 4587 modules, zero errors). Mapper imports and call sites consistently use `mapDealToChatRoom` and `mapDealMessageToTimelineEvent` from shared `creator-deal-mappers.ts`. `creator-deals.tsx` uses `mapDealToDealsPageRow` with the same mapper module.

Live wiring aligns with `DealController` endpoints. `isApiLive()` gates fetch/load/send mock paths on both pages. Loading, error, and empty states follow the established Alert + Skeleton + retry pattern. Non-blocking metadata gaps (M-2–M-4) and counter-dialog double-submit (M-1) remain pre-prod polish — do not block sprint gate.

---

## Build Verification

| Gate | Result | Evidence |
|------|--------|----------|
| `npm run build` | ✅ **PASS** | Vite 6.4.2, 4587 modules, built ~1m 38s, zero errors (non-blocking `baseUrl` duplicate + chunk-size warnings) |
| Mapper symbols | ✅ **PASS** | `mapDealToChatRoom` / `mapDealMessageToTimelineEvent` imported and used at L43–44, L481, L503, L634; no `mapDealToRoom` / `mapMessageToEvent` anywhere in `src/` |
| Mock timeline syntax | ✅ **PASS** | `mockTimelineEvents` const (L238–385) + `mergeMockTimelineEvents` function (L387–411) valid |

**Command run (2026-07-09 re-QA):**
```bash
npm run build
# → ✓ 4587 modules transformed; ✓ built in 1m 38s
```

---

## Task #14 Definition of Done — Verification

| DoD Item | Result | Evidence |
|----------|--------|----------|
| `api.deals.list('creator')` + mapper | ✅ PASS | `fetchDeals()` L480–481 → `mapDealToChatRoom`; `creator-deals.tsx` L204–205 → `mapDealToDealsPageRow` |
| `api.messages.list` + `markRead` on select | ✅ PASS | `loadMessages()` L502–504; fire-and-forget `markRead` L504; local `unreadCount` zero L505–507 |
| `api.messages.send` | ✅ PASS | `handleSendMessage()` L633–634 → `mapDealMessageToTimelineEvent(sent)` |
| `api.deals.accept` / `.reject` / `.counter` | ✅ PASS | Handlers L645–717; `api.ts` `mockOr` fallback when `!isLive()` (same as `creator-deals.tsx`) |
| Loading / error / empty states | ✅ PASS | `dealsLoading`/`messagesLoading` skeletons; `dealsError`/`messagesError` Alert + retry; empty deal list (L800–817, L960–963) + empty timeline (L1163–1169); `creator-deals.tsx` `EmptyState` + loading skeletons |
| Mock fallback only when `!isApiLive()` | ✅ PASS | `fetchDeals`, `loadMessages`, `handleSendMessage` early-return to mock; initial state uses `isApiLive()` for empty vs mock |

---

## API Contract Cross-Check (DealController #9)

| Frontend call | Backend route | Match |
|---------------|---------------|-------|
| `api.deals.list('creator')` | `GET /deals?status=all` (role from JWT) | ✅ |
| `api.messages.list('creator', dealId)` | `GET /deals/{dealId}/messages` | ✅ |
| `api.messages.send('creator', dealId, text)` | `POST /deals/{dealId}/messages` body `{ content, kind }` | ✅ |
| `api.messages.markRead('creator', dealId)` | `POST /deals/{dealId}/messages/read` | ✅ |
| `api.deals.accept(id)` | `POST /deals/{id}/accept` | ✅ |
| `api.deals.reject(id)` | `POST /deals/{id}/reject` | ✅ |
| `api.deals.counter(id, { amount, message })` | `POST /deals/{id}/counter` body `CounterRequest` | ✅ |

Backend access isolation unchanged from Task #13 APPROVED — creator path uses `CreatorContextService` + `findByIdAndCreatorId`.

---

## Functional Review

### Live mode data flow

1. Mount → `fetchDeals()` if `isApiLive()`.
2. Deal select effect → `loadMessages(roomId)` + fire-and-forget `markRead` + local `unreadCount` zeroing.
3. Send message → API POST → append mapped event to timeline.
4. Accept/counter → refresh deals + reload messages; reject → refresh deals + clear selection + strip `?deal` from URL.

### Mock mode

- `mockDealRooms` + `mergeMockTimelineEvents` + `addPersistedMessage` — **valid implementation** (H-1 resolved).

### Proposal UI vs backend metadata (live mode)

| UI expectation | Backend `persistProposalMessage` | Gap |
|----------------|----------------------------------|-----|
| `metadata.proposalType === 'counter'` → `counter_proposal` card | Not set; all proposals `kind=proposal` | M-2: creator counters render as brand proposal card |
| `metadata.deliverables` as array | `deliverables.size()` number | M-3: deliverable count shows `0` |
| Earnings breakdown fields | Not in metadata | M-4: net earnings display empty/NaN (cosmetic) |
| Action buttons when `status === 'pending'` | `metadata.status = "pending"` | ✅ |

---

## Hostile / Edge-Case Matrix

| Scenario | Expected | Reviewed | Status |
|----------|----------|----------|--------|
| API down on deal list | Empty list + error Alert + retry | Code path present | ✅ |
| API down on messages | Empty timeline + error Alert + retry | Code path present | ✅ |
| Send while prior send in flight | Button disabled via `sendingMessage` | Code present | ✅ |
| Decline clears selection + URL | `setSelectedDeal(null)` + `next.delete('deal')` | Code present | ✅ |
| Mock mode must not hit network for list/load/send | `!isApiLive()` early returns | Code present | ✅ |
| Counter dialog double-submit | Disabled while in flight | Missing on dialog path | M-1 (non-blocking) |
| XSS in message content | Escaped text node (no `dangerouslySetInnerHTML`) | Renders `<p>{event.content}</p>` | Escalate Kabir M-9-1 (pre-prod) |

---

## Findings

### H-1: Build-breaking syntax — **RESOLVED** ✅

`mockTimelineEvents` restored as top-level const; `mergeMockTimelineEvents` function body valid. Verified in re-review.

### H-2: Wrong mapper function names — **RESOLVED** ✅

All call sites use `mapDealToChatRoom` and `mapDealMessageToTimelineEvent`. Grep confirms zero stale aliases in `src/`.

### M-1: Counter dialog missing in-flight guard (NON-BLOCKING)

`handleSendCounter` (L680–697) has no `isSubmitting` state; `CounterProposalForm` path correctly uses `isSubmittingCounter`. Double-submit risk on dialog path only.

### M-2: Creator counter proposals map to brand proposal card (NON-BLOCKING for sprint)

`mapMessageKindToEventType` expects `metadata.proposalType === 'counter'`. Backend `persistProposalMessage` never sets `proposalType`. Live counters display as left-aligned "Brand Proposal". Coordinate with Vikram (metadata) or Ananya (sender-aware card).

### M-3: Deliverable count metadata shape mismatch (LOW)

Backend sends numeric `deliverables`; UI casts to array for `.length`. Shows `0 items`.

### M-4: Earnings breakdown not populated from API (LOW)

Mock-only fields (`platformFee`, `gstOnFee`, `tds`, `netEarnings`). Live proposals should compute client-side via `calculateEarnings()` or omit section.

### L-1: `'use client'` directive in Vite page (LOW)

Unnecessary per `influora-vite-react` skill; harmless.

### L-2: No frontend unit tests for creator-chat API wiring (DEBT)

Consistent with sprint scope; add RTL tests per `KAVYA_QA_TEST_PLAN.md` §17.5.

---

## Escalations

| Item | Owner | Notes |
|------|-------|-------|
| M-9-1 message XSS / unsanitized `DealMessage.content` | Kabir | Carry-over from Task #9; render path live in creator-chat — pre-prod blocker only |
| M-2 proposal metadata | Vikram + Ananya | Backend or frontend alignment |
| M-1 counter dialog guard | Ananya | Optional sprint polish |
| Build re-verify | Meera | Frontend build already PASS; no new backend change in this slice |

---

## QA Sign-off

| Gate | Status |
|------|--------|
| Kavya Task #15 (re-review) | ✅ **APPROVED** |
| Kabir security | ⏸️ M-9-1 pre-prod carry-over (does not block sprint) |
| Meera build | ✅ PASS (confirmed re-QA) |

**Deal room slice gate:** Ananya #14 + Kavya #15 → cleared for Priya sign-off on creator deal room frontend wiring.

---

**Routed to:** Kabir (M-9-1 awareness) → Meera (build confirm) → Priya sign-off
