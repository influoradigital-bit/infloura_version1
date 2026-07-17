# P3-19 — Creator deliverable-submit path drift (confirm-then-fix)

**Owner:** Vikram (BE) + Ananya (FE) · **Reviewers:** Kavya → Meera · **Priority:** P3 (core flow — 404 risk) · **Depends on:** —
**Status:** ✅ DONE — confirmed REAL, fixed (frontend-only)

## Goal
Creator alignment audit (2026-07-12) flagged **DRIFT** on deliverable submit: FE may call a generic path while the backend only mounts a creator-scoped one → **404 on a revenue-critical step** (creator submitting proof of work). **Confirm first**, then align.

## Suspected drift (verify — may be false positive)
- FE `src/lib/api.ts` `deliverables.submit` (~L862) → `POST /deliverables/:id/submit`
- BE `CreatorDeliverableController.java:75` → `POST /creator/deliverables/:id/submit`

## Steps
1. **CONFIRM:** read the exact FE path construction and the controller `@RequestMapping`. Check whether a second/generic controller also maps `/deliverables/:id/submit`. If both resolve → **close as no-op with evidence.**
2. If real: align — point the FE at `/creator/deliverables/:id/submit` (or add the generic route to the controller if there's a design reason). Keep it consistent with the other creator-deliverable routes (upload/status already `/creator/...`).
3. Verify a creator submit round-trips (or covered by a controller test).

## Acceptance criteria
- [x] Drift confirmed real OR documented false-positive
- [x] If real: FE submit hits the live route (no 404); path consistent with sibling deliverable routes
- [ ] Kavya QA · Meera verify

## Completion log

**Finding: REAL drift, confirmed and fixed.**

- Backend: `influora-api/src/main/java/com/influora/web/CreatorDeliverableController.java` — class-level `@RequestMapping("/creator/deliverables")` (L35), method-level `@PostMapping("/{deliverableId}/submit")` (L75) → resolves to `/creator/deliverables/{id}/submit`. No other controller mounts a generic `/deliverables/{id}/submit` (checked `BrandDeliverableController.java`, `DeliverableMetricController.java` — neither maps `/submit`).
- Backend context-path: `influora-api/src/main/resources/application.yml:25` sets `server.servlet.context-path: /api/v1`, so the live route is `/api/v1/creator/deliverables/{id}/submit`.
- Frontend (before fix): `src/lib/api.ts` `deliverables.submit` (was L862-869) called `POST /deliverables/${id}/submit`, resolving via `API_BASE_URL` (`.../api/v1`) to `/api/v1/deliverables/{id}/submit` — **would 404** against the real backend route. Confirmed no base-path aliasing covers the gap; the two resolved paths genuinely differ (`creator/` segment missing on the FE side).
- Additional finding: `deliverables.submit` (and the sibling `deliverables.list`) has **zero call sites** anywhere in `src/` (grepped `deliverables.submit(` across the whole tree — no hits). The actual creator submission UI, `src/components/creator/deal-room/deliverable-submission.tsx`, doesn't call the API layer at all yet (only `console.log`s on submit) — so today there's no live 404 in production, but the contract was wrong and would break the moment the UI wires it up. `deliverables.approve` / `deliverables.requestRevision` were checked too and correctly match `BrandDeliverableController`'s `@RequestMapping("/deliverables")` + `/{id}/approve` and `/{id}/revise` — left untouched (out of scope, not drifted).

**Fix (frontend-only, no backend change needed):**
- `src/lib/api.ts`: `deliverables.submit` now calls `POST /creator/deliverables/${id}/submit` (matches `CreatorDeliverableController`, consistent with sibling creator-deliverable routes already using `/creator/...`, e.g. `creatorDeliverables.listForDeal`).
- `src/lib/__tests__/api-contract.test.ts`: removed `/deliverables/{}/submit` from `KNOWN_PHANTOM_PATHS` (the fabricated-contract baseline can only shrink) now that the real path resolves.

**Verification:**
- `npx tsc --noEmit` — clean, no errors.
- `npm run build` (`tsc --noEmit && vite build`) — succeeds, 3942 modules transformed, no new errors.
- `npx vitest run src/lib/__tests__/api-contract.test.ts` — 2/3 pass. The 1 remaining failure (`/notifications/{}/read` unmatched) is **pre-existing and unrelated** — it concerns `NotificationController`/notifications routes, not deliverables, and reproduces identically before and after this change (confirmed via `git stash`/`git stash pop` A-B comparison). Not touched, since it's outside this ticket's scope and looks like another in-flight agent's WIP.
- Backend (`influora-api`) untouched — no Java files modified, so `mvn test` was not re-run (no risk introduced there).

**Handoff:** → Kavya (QA) → Meera (verify).
