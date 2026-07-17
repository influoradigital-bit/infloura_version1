# Brand Deliverable Review UI — Task #21b (Kabir Red-Team)

**Auditor:** Kabir Singh (Offensive Security / Red-Team Lead)  
**Date:** 2026-07-09 (~18:00 IST)  
**Verdict:** ✅ **PASS WITH FINDINGS** — sprint gate **GO**; inline `brand-chat` live path **pre-prod NO-GO** (H-21b-1)  
**Scope:** Ananya Task #21b — brand approve/revise UI vs Vikram Task #21 API + Task #22 `TextSanitizer`  
**Reference:** Kavya `wiki/errors/creator-deliverable-review-T21b-kavya-qa.md`; backend Kabir `wiki/errors/creator-deliverable-review-T21-kabir-redteam.md`; spec `wiki/tech/creator/09_CREATOR_DELIVERABLES_SPEC.md` §11.4–11.5  
**Reviewed Files:**
- `src/components/brand/deal-room/brand-deliverable-revise-modal.tsx` — revision feedback dialog
- `src/components/brand/timeline/panels/deliverable-review-panel.tsx` — `CollaborationTimeline` review sheet
- `src/components/brand/timeline/event-cards/deliverable-card.tsx` — timeline card + feedback display
- `src/components/brand/timeline/collaboration-timeline.tsx` — post-success overrides
- `src/components/brand/deal-room/deal-deliverables-tab.tsx` — Tools panel list actions
- `src/pages/brand-chat.tsx` — inline timeline review handlers + Tools panel wiring
- `src/lib/api.ts` — `deliverables.approve`, `deliverables.requestRevision`, `deliverables.list`
- `src/lib/brand-deliverable-utils.ts` — status gating helpers
- `src/components/creator/deal-room/revision-handler.tsx` — creator egress cross-check (M-2)
- `influora-api/src/main/java/com/influora/service/BrandDeliverableService.java` — Task #22 `TextSanitizer` on `feedback` (closure cross-check)

---

## Executive Summary

**VERDICT: ✅ PASS WITH FINDINGS**

Brand deliverable review UI correctly delegates authorization, workspace scope, and state transitions to the Task #21 backend. No new **Critical** or **High** security defects on the wired API paths (`CollaborationTimeline` + Tools panel). Client-side `reviewingId` mutex blocks trivial double-submit; feedback validation blocks empty revise payloads before network.

**Closed / PASS (security):**

1. **IDOR via UI — CLOSED** — All live approve/revise calls pass only `deliverableId` + optional `feedback` to `POST /deliverables/{id}/approve|revise`. Backend `findByIdAndWorkspaceId` join-through (Task #21 Kabir **PASS**) blocks cross-tenant writes. UI cannot bypass workspace gate by manipulating request shape.
2. **Client-side status gate bypass — CLOSED (server fail-closed)** — `isBrandReviewableStatus` loose gate on `brand-chat` / `DealDeliverablesTab` is cosmetic only. DevTools direct `api.deliverables.approve(id)` still hits backend `canReview` → `409 INVALID_STATE` on wrong status.
3. **M-2 stored XSS on brand `feedback` egress — CLOSED (Task #22)** — `BrandDeliverableService.requestRevision` now persists `TextSanitizer.sanitizePlainText(feedback)` (`testReviseStripsXssFeedback`). All reviewed UI render paths use React text interpolation (`{revisionFeedback}`, `{brandFeedback}`) — no `dangerouslySetInnerHTML` in deliverable review surfaces. Creator `revision-handler.tsx` L80 safe.
4. **Missing `deliverableId` in live panel — CLOSED** — `deliverable-review-panel.tsx` blocks API call with explicit error when `isApiLive() && !deliverableId` (L77–79, L120–122).
5. **Double-submit / race (client) — CLOSED** — `reviewingId` disables buttons; `isSubmitting` in panel/modal.

**Active findings:**

| ID | Severity | Area | Sprint gate | Prod gate |
|---|---|---|---|---|
| H-21b-1 | **HIGH (pre-prod)** | `brand-chat` inline timeline mock IDs (`del-1`, `del-2`) in live mode | **GO** (use Tools panel / `CollaborationTimeline`) | **NO-GO** inline path |
| M-21-1 | **MEDIUM** | No server rate limit on approve/revise (unchanged Task #21) | Carry-forward | Recommended |
| M-21b-S1 | **MEDIUM** | Inline review errors not visible in chat feed (`reviewError` sheet-only) | Carry-forward | Fix with H-21b-1 |
| L-21b-S1 | LOW | Panel feedback `Textarea` lacks `maxLength={2000}` (modal has cap) | Optional | Align before prod |
| L-21b-S2 | LOW | `collaboration-timeline` mock `deliverableId` pattern (`del_{collabId}_1`) — same class as H-21b-1 when live without real timeline events | Demo OK | Wire live events |
| L-21b-S3 | LOW | `deliverable-review-panel` `<video src={meta?.submittedUrl}>` — trust boundary on URL when live metadata wired | N/A (mock URL today) | Validate/signed URLs |
| L-21b-S4 | LOW | Optimistic `deliverableOverrides` stores raw `result.feedback` client-side — harmless (React egress safe) | — | — |

**M-2 on brand feedback display: CLOSED** for Task #21b scope after Task #22 backend merge. Full M-2 program closure remains on Kabir Task #22 re-review (`TASK_INBOX.md` #22).

---

## 1. Attack Surface Map

| Surface | Component | Live API | Authz boundary | Security posture |
|---|---|---|---|---|
| Campaign collaboration timeline | `CollaborationTimeline` → `DeliverableEventCard` → `DeliverableReviewPanel` | ✅ `approve` / `requestRevision` | Backend workspace join | ✅ PASS — `deliverableId` in metadata; missing-id guard in live mode |
| Brand deal room — Tools panel | `brand-chat` → `DealDeliverablesTab` + `BrandDeliverableReviseModal` | ✅ via shared handlers | Backend workspace join | ✅ PASS when rows are API-backed |
| Brand deal room — inline chat cards | `brand-chat` `mockTimelineEvents` | ✅ calls API | Backend 404 on mock ids | ❌ **H-21b-1** — integrity / phantom actions |
| Creator feedback consumption | `revision-handler.tsx`, creator status API | N/A (egress) | Creator ownership on GET | ✅ PASS — React text render |

---

## 2. Authentication & Authorization

### 2a. API client defaults

```979:991:src/lib/api.ts
  approve: (id: string) =>
    isLive()
      ? http.request<{ status: DeliverableStatus }>('POST', `/deliverables/${id}/approve`)
      : mockOr({ status: 'APPROVED' as DeliverableStatus }),

  requestRevision: (id: string, feedback: string) =>
    isLive()
      ? http.request<{ status: DeliverableStatus }>('POST', `/deliverables/${id}/revise`, {
          body: { feedback },
        })
      : mockOr({ status: 'REVISION_REQUESTED' as DeliverableStatus }),
```

- Default `http.request` role is `brand` — correct for these endpoints.
- No client-supplied `workspaceId` or `userId` in body — identity server-derived (Task #21 **PASS**).

### 2b. Hostile UI probe matrix

| Attack | UI path | Result |
|---|---|---|
| Brand approves foreign workspace `deliverableId` via Tools panel | `handleApproveDeliverable` | **BLOCKED** — `404 DELIVERABLE_NOT_FOUND` (Task #21) |
| Brand revises with XSS payload | `BrandDeliverableReviseModal` → API | **SANITIZED** at persist — `TextSanitizer` strips tags; UI renders escaped text |
| Creator JWT on brand UI routes | N/A — separate app shell | Backend `403 WRONG_USER_TYPE` if called directly |
| Force approve on `DRAFT` via DevTools | `api.deliverables.approve` | **BLOCKED** — `409 INVALID_STATE` |
| Double-click Approve | `reviewingId` mutex | **BLOCKED** — second click disabled |
| Approve with mock `del-2` in live inline timeline | `brand-chat` L1167 | **404** — no state mutation; error hidden from inline feed (**H-21b-1** / **M-21b-S1**) |
| Missing `deliverableId` on live panel | `DeliverableReviewPanel` | **BLOCKED** — error banner, no API call |

**Authorization on UI-triggered mutations: PASS (backend-enforced).**

---

## 3. H-21b-1 — Mock Timeline IDs in Live Mode

### 3a. Evidence

`brand-chat.tsx` always renders module-scoped `mockTimelineEvents` (L926) including deliverable cards with hardcoded ids:

```307:342:src/pages/brand-chat.tsx
    data: {
      id: 'del-1',
      ...
    },
    ...
    data: {
      id: 'del-2',
      ...
      status: 'pending_review',
```

Inline Approve / Request Changes call `handleApproveDeliverable(deliverableId)` / `openReviseModal(deliverableId, …)` with these mock ids even when `isApiLive()` (L1162–1183).

Fallback Tools panel rows for `deal-1` also map from the same mock timeline (`getDeliverablesForDeal`, L376–396) when `api.deliverables.list` is empty or `NOT_IMPLEMENTED`.

### 3b. Security assessment

| Dimension | Assessment |
|---|---|
| IDOR / cross-tenant write | **Not exploitable** — backend returns uniform `404`; no foreign row mutation |
| ID enumeration oracle | **Not exploitable** — same 404 for missing vs foreign id (Task #21) |
| Business logic / integrity | **FAIL** — brand operator believes inline CTA is live; API rejects; no visible error in chat feed |
| False local approval | **Not observed** — `applyReviewStatus` only runs on API success; failed calls do not flip status |

**Classification:** **HIGH (pre-prod integrity)** — aligns with Kavya H-21b-1. Not a sprint pipeline blocker (Tools panel + `CollaborationTimeline` are acceptable demo paths). **Blocks production deploy** of inline `brand-chat` deliverable review until timeline is wired to live deal messages or inline CTAs are disabled when `deliverableId` is not API-backed.

### 3c. Required fix (Ananya / Vikram)

1. Wire inline timeline to `api.messages.list('brand', dealId)` deliverable events with real `metadata.deliverableId`, **or**
2. Gate inline Approve / Request Changes: `disabled` when `isApiLive() && !isApiBackedDeliverableId(id)`, **or**
3. Surface `reviewError` in the chat scroll area (pairs with **M-21b-S1**).

---

## 4. M-2 — XSS on Feedback Display

### 4a. Ingress (brand UI → API)

| Component | Validation | Bounds | Server sanitize |
|---|---|---|---|
| `BrandDeliverableReviseModal` | Required trim (L45–48) | `maxLength={2000}` (L91) | ✅ Task #22 |
| `DeliverableReviewPanel` | Required trim on revise (L115–117) | **None** (L-21b-S1) | ✅ Task #22 |
| `brand-chat` handlers | Delegates to modal/panel | Modal cap only | ✅ Task #22 |

No client-side HTML sanitization before POST — acceptable when backend `TextSanitizer` is authoritative (Task #22 shipped).

### 4b. Egress (stored feedback → DOM)

| Surface | Field | Render | DOM XSS? |
|---|---|---|---|
| `deliverable-review-panel.tsx` L262 | `meta.revisionFeedback` | `<p>{meta.revisionFeedback}</p>` | **No** — React escapes |
| `deliverable-card.tsx` L105 | `meta.revisionFeedback` | `<p>{meta.revisionFeedback}</p>` | **No** |
| `collaboration-timeline.tsx` L241 | `revisionFeedback` override from `result.feedback` | Via card/panel above | **No** |
| `revision-handler.tsx` L80 | `brandFeedback` from `reviewNotes` API | `<p>{brandFeedback}</p>` | **No** |
| `brand-deliverable-revise-modal.tsx` L71 | `deliverableTitle` | JSX text child | **No** |

Repo-wide grep: no `dangerouslySetInnerHTML` in Task #21b deliverable review paths (only `chart.tsx` unrelated).

### 4c. Backend closure (Task #22 cross-check)

```56:62:influora-api/src/main/java/com/influora/service/BrandDeliverableService.java
        deliverable.applyRevision(TextSanitizer.sanitizePlainText(feedback.trim()));
```

`BrandDeliverableServiceTest.testReviseStripsXssFeedback` — `<img onerror=alert(1)>Fix the hook` → persisted `Fix the hook`.

**M-2 on brand feedback ingress + display: CLOSED for Task #21b.** Program-level M-2 ticket (#22) still awaits Kabir full-path re-review to close M-9-1 and remaining ingress surfaces.

---

## 5. Input Validation & Abuse

### 5a. Feedback length

- Modal enforces 2000 chars client-side.
- Panel sends unbounded feedback to API — backend `TEXT` column accepts; `TextSanitizer` strips tags but not length. **L-21b-S1** — align panel with modal + add `@Size(max=2000)` on `ReviseRequest` (Task #21 L-21-1).

### 5b. Rate limiting (M-21-1)

No client throttle beyond `reviewingId`. Server `AuthRateLimitFilter` returns `null` for `/deliverables/**` approve/revise — unchanged from Task #21. Authenticated brand can spam cross-row approve/revise until `409` on each row. **MEDIUM carry-forward** — batch with M-19-2 / M-1 hardening PR.

### 5c. Status gating

- **Strict:** `DeliverableReviewPanel` + `DeliverableEventCard` use `isBrandReviewableApiStatus` (`SUBMITTED`/`RESUBMITTED` only).
- **Loose:** `brand-chat` `isTimelineDeliverableReviewable` uses `isBrandReviewableStatus` — allows mock UI statuses. Security impact: **none** (server fail-closed); UX may show buttons on non-reviewable mock rows.

---

## 6. Component Security Notes

### `brand-deliverable-revise-modal.tsx`

- ✅ Required feedback; `maxLength={2000}`; state reset on close.
- ✅ Parent API errors via `error` prop; re-throw keeps modal open on failure.
- ✅ `deliverableTitle` rendered as text — no HTML injection sink.
- ⚠️ No client sanitize before submit — mitigated by Task #22 backend.

### `deliverable-review-panel.tsx`

- ✅ Live missing `deliverableId` blocked.
- ✅ `actionError` surfaced in-panel (destructive Alert).
- ✅ Approve/revise call correct API methods with mutex `isSubmitting`.
- ⚠️ `meta.revisionFeedback` display safe (React text) but unsanitized mock metadata could show prank strings in demo — not stored XSS.
- ⚠️ `<video src={meta?.submittedUrl}>` — when live URLs arrive, enforce HTTPS + signed CDN URLs (**L-21b-S3**).

### `brand-chat.tsx` review handlers

- ✅ `handleApproveDeliverable` / `handleReviseSubmit` — correct API, `ApiError` handling, no optimistic status on failure.
- ❌ Inline timeline always mock-sourced (**H-21b-1**).
- ⚠️ `reviewError` only in deliverables sheet (L1341–1348) — **M-21b-S1**.

### `deal-deliverables-tab.tsx`

- Presentational; no direct API — parent handlers enforce auth boundary. ✅ PASS.

---

## 7. Test & Build Cross-Check

| Gate | Status | Notes |
|---|---|---|
| Kavya QA | ✅ APPROVED | `wiki/errors/creator-deliverable-review-T21b-kavya-qa.md` |
| Meera `npm run build` | ✅ PASS | 4589 modules (Kavya / TASK_INBOX) |
| Backend XSS regression | ✅ | `testReviseStripsXssFeedback` (Task #22) |
| UI security unit tests | ❌ | No frontend XSS/regression tests for feedback render (acceptable sprint debt) |

---

## Findings Summary

| ID | Severity | Area | Status |
|---|---|---|---|
| — | — | IDOR / workspace scope (UI → Task #21 API) | **CLOSED** — PASS |
| — | — | State machine bypass via UI | **CLOSED** — server fail-closed |
| — | — | M-2 XSS brand `feedback` ingress | **CLOSED** — Task #22 `TextSanitizer` |
| — | — | M-2 XSS feedback display (React egress) | **CLOSED** — no HTML sinks |
| H-21b-1 | **HIGH (pre-prod)** | Inline `brand-chat` mock deliverable IDs in live mode | **OPEN** — prod blocker inline path |
| M-21-1 | **MEDIUM** | No brand approve/revise rate limit | **OPEN** — carry-forward |
| M-21b-S1 | **MEDIUM** | Inline review errors not surfaced in chat feed | **OPEN** — UX/security visibility |
| L-21b-S1 | LOW | Panel feedback lacks `maxLength` | Open |
| L-21b-S2 | LOW | `CollaborationTimeline` demo `deliverableId` in live without real events | Open |
| L-21b-S3 | LOW | Video `src` trust boundary when live metadata wired | Open |
| L-21b-S4 | LOW | Client-side `revisionFeedback` override unsanitized (display safe) | Open — informational |

---

## Go/No-Go Decision

| Sub-scope | Decision |
|---|---|
| Task #21b UI → Task #21 API wiring (`CollaborationTimeline`, Tools panel) | **GO** |
| IDOR / XSS on reviewed surfaces | **GO** |
| Kavya QA Task #21b | **GO** (already APPROVED) |
| Sprint integration / dev demo | **GO** |
| Production — inline `brand-chat` deliverable review | **NO-GO** until **H-21b-1** fixed |
| Production — brand review UI overall | **CONDITIONAL GO** — Tools panel + timeline panel paths; M-21-1 recommended |
| Kabir Task #22 M-2 program closure | **PENDING** separate re-review |

**Pipeline position:** Task #21b security gate **✅ PASS WITH FINDINGS** — route to Priya CTO sign-off. No Critical/High security defects on primary wired paths. Escalation to Priya/Swapnil: **none**.

---

## Kabir Sign-Off

- [x] `brand-deliverable-revise-modal` — feedback validation, bounds, error handling reviewed
- [x] `deliverable-review-panel` — API wiring, missing-id guard, feedback egress reviewed
- [x] `brand-chat` review actions — auth boundary delegated to API; H-21b-1 filed
- [x] M-2 feedback display — React egress safe; backend sanitize verified (Task #22)
- [x] IDOR / privilege escalation — no UI bypass of Task #21 gates
- [x] Rate limiting — M-21-1 carry-forward unchanged
- [x] No Critical findings — pipeline **not blocked**
- [ ] H-21b-1 inline mock IDs in live mode — **pre-prod required before inline prod deploy**
- [ ] M-21-1 brand-deliverable-review rate limit — **pre-prod recommended**

**Kabir verdict: ✅ PASS WITH FINDINGS.** Route to Priya Task #21b CTO sign-off. Ananya fixes **H-21b-1** before brand review prod deploy of inline deal-room timeline.

---

**Document Control:** Created 2026-07-09 by Kabir (Task #21b). Prior: `creator-deliverable-review-T21b-kavya-qa.md`, `creator-deliverable-review-T21-kabir-redteam.md`. Next: Priya CTO sign-off Task #21b.
