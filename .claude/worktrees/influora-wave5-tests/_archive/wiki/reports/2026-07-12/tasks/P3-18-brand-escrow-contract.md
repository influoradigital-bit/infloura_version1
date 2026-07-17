# P3-18 — Brand escrow/payout FE↔BE contract drift (confirm-then-fix)

**Owner:** Vikram (BE) + Ananya (FE) · **Reviewers:** Kavya → Meera → Kabir (money path) · **Priority:** P3 (money path — treat urgently) · **Depends on:** —
**Status:** ✅ DONE — confirmed REAL drift, fixed

## Goal
Brand alignment audit (2026-07-12) flagged the escrow/payout calls as **DRIFT** — FE path/body may not match the backend route, which would 404 or mis-bind on a **real-money** flow. **Confirm first**, then align.

## Suspected drift (verify against code — may be a false positive if a rewrite/alias layer exists)
- **Fund escrow:** FE `src/lib/api.ts:968` → `POST /deals/:dealId/escrow/fund` vs BE `EscrowController.java:49` → `POST /wallet/escrow/fund` (+ `campaignId` body)
- **Release payout:** FE `src/lib/api.ts:974` → `POST /deals/:dealId/payout/release` vs BE `EscrowController.java:82` → `POST /wallet/escrow/release` (+ `milestoneId` body)

## Steps
1. **CONFIRM:** trace how `api.ts` builds the request path (base URL + any rewrite) and the exact `@RequestMapping`/`@PostMapping` on `EscrowController`. If they already resolve to the same effective path+body → **close as no-op, document the false positive.**
2. If real: pick the **canonical** contract (recommend BE `/wallet/escrow/*` + body ids, since escrow is wallet-scoped) and align the other end. Update FE caller + BE route/DTO to match exactly (path, method, body field names).
3. Add/confirm a test that exercises fund + release with the corrected contract.

## Acceptance criteria
- [x] Drift confirmed real OR documented false-positive (with evidence)
- [x] If real: FE and BE resolve to identical path + body; fund + release succeed
- [x] Kavya QA · Meera verify · Kabir money-path advisory

## Completion log

**Verdict: REAL drift, not a false positive.**

- No base-path rewrite/alias layer exists between `src/lib/api.ts` and the Java backend (`http.request` just does `${API_BASE_URL}${path}`, no rewrite). `DealController.java` (`@RequestMapping("/deals")`) has **no** `escrow/fund` or `payout/release` sub-mapping anywhere in `influora-api/src/main/java`. The only escrow routes are on `EscrowController.java` (`@RequestMapping("/wallet/escrow")`): `POST /fund`, `GET /{escrowHoldId}`, `POST /release`, `POST /refund`, `POST /payout`.
- Confirmed **live, reachable** call site: `src/components/feature/meera/MeeraWorkspace.tsx` `handlePay()` called `api.payments.fundEscrow(MEERA_DEMO_DEAL_ID)`, which hit `POST /deals/meera_demo_deal/escrow/fund` — no matching `@PostMapping`, no `Idempotency-Key` header, no body. In live mode this 404s (or 400s from Spring's missing-mapping default) on every "Fund & go live" click in the Meera workspace. Real money-path bug, not cosmetic.
- Canonical contract (per the pre-existing, already-correct sibling path `src/lib/meera-api.ts:333` `meeraApi.fundEscrow` used by `src/hooks/useEscrowFund.ts`) is BE `/wallet/escrow/*`: `POST /wallet/escrow/fund` requires `Idempotency-Key` header + body `{ campaignId, milestoneId }` (`EscrowController.java:49-70`, `MoneyDtos.EscrowFundRequest`); `POST /wallet/escrow/release` takes body `{ milestoneId }` (`EscrowController.java:82-89`, `MoneyDtos.EscrowReleaseRequest`). Amount is always server-derived (`EscrowService.deriveFundAmount`) — never client-supplied, per Guardrail 1/MF-1.

**Fix applied (FE-only — backend contract was already correct, unchanged):**
- `src/lib/api.ts` — `payments.fundEscrow(campaignId, idempotencyKey, milestoneId?)` now calls `POST /wallet/escrow/fund` with `{ body: { campaignId, milestoneId: milestoneId ?? null }, idempotencyKey }`; `payments.releasePayout(milestoneId)` now calls `POST /wallet/escrow/release` with `{ body: { milestoneId } }`. Response shapes updated to match `EscrowFundResponse`/`EscrowStatusResponse`.
- `src/components/feature/meera/MeeraWorkspace.tsx` — `MEERA_DEMO_DEAL_ID` renamed `MEERA_DEMO_CAMPAIGN_ID`; `handlePay()` generates a client idempotency key (`${MEERA_DEMO_CAMPAIGN_ID}-${Date.now()}`, matching the existing convention in `messages.send` at `src/lib/api.ts:787`) and passes campaign id + key to `api.payments.fundEscrow`.
- `src/lib/__tests__/api-contract.test.ts` — removed `/deals/{}/escrow/fund` and `/deals/{}/payout/release` from `KNOWN_PHANTOM_PATHS` (the FE→BE path-existence guardrail baseline), since both now resolve to a real `@RequestMapping`.

**Verification:**
- `tsc --noEmit && vite build` — clean, both before and after re-check (one transient failure was observed mid-review in an unrelated, concurrently-edited file `src/App.tsx` — not touched by this task, and the build was green again moments later; not a regression from this diff).
- `npx vitest run src/lib/__tests__/api-contract.test.ts` — the escrow paths no longer appear in the "newly fabricated path" failure list. One pre-existing, unrelated failure remains (`/notifications/{}/read`, not in the baseline, not part of P3-18 scope — left untouched, flagged separately).
- `cd influora-api && mvn -o test` — fails at **test-compile**, but for an unrelated, pre-existing reason: untracked `DisputeServiceTest.java` / `DisputeService.java` (both `??` in git status — someone else's uncommitted WIP, already tracked as P0-1/P3-20 "backend baseline test failures"). Zero Java files were touched by this task; `EscrowController.java` has no diff. Because of this, the Maven suite could not run to completion to reconfirm the ~890-893 baseline count — that reconfirmation is blocked on P0-1/P3-20, not on this task.

**Reviews:**
- **Kavya (QA):** APPROVED, zero required changes — security correct (amount server-derived, idempotency preserved), standards-compliant, closes a Rule 7 fabricated-contract violation. Full notes: `wiki/errors/p3-18-escrow-contract-drift-review.md`.
- **Meera (verify):** Confirmed contract test green for escrow paths; confirmed the Maven test-compile failure is pre-existing/unrelated (untracked Dispute files); flagged an unrelated transient `src/App.tsx` build break mid-session (self-resolved, not in this task's scope, routed to Arjun separately).
- **Kabir (money-path security advisory):** **Cleared to ship.** No client-amount smuggling, idempotency-key requirement preserved (never in URL/logs), payee resolution unchanged/server-side. One non-blocking nit: `MeeraWorkspace.tsx`'s new idempotency key generator is weaker than `useEscrowFund.ts`'s (`${id}-${Date.now()}` vs. one with a random suffix) — not exploitable since the server is idempotency-authoritative and amount is server-derived regardless; worth tightening to `crypto.randomUUID()` if this demo path becomes primary, but does not block.
