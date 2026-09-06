# Priya (CTO) — Ananya Wave 2 review

**Date:** 2026-09-04 · **Reviewer:** Priya, fresh context · **Branch:** `fix/f0390-money-flags-build-pipeline`
**Method:** read every changed hunk against the real backend source; ran vitest under BOTH configs;
ran `mvn -o -Dtest=ContractServiceTest,ContractControllerTest test`; ran `npx tsc --noEmit`;
did an independent revert probe on F-0419.

**Scope note:** the brief said "16 findings"; the six assignments list **19** IDs. All 19 are graded below.

---

## VERDICT: CHANGES REQUIRED — do not merge as-is

Two hard blockers and two genuinely unfixed findings.

| # | Blocker | Where |
|---|---------|-------|
| B1 | `npm run test:live` (and therefore `npm run test:all`) now **exits 1** — an unhandled rejection in a new test file. Verified: live suite exits 0 with that file moved aside, exits 1 with it present. | `src/lib/__tests__/api-client-resilience.live.test.ts:176-180` |
| B2 | F-0411 has **no fix and no test at all** — zero occurrences of the string `F-0411` anywhere under `src/`. It was reported as done. | `src/components/brand/discover/creator-discovery.tsx:154,442` |

Plus F-0435 closed 1 of the 3 drifts it names.

---

## Test evidence I ran myself

```
vitest (default config)      7 files, 25 tests   PASS   exit 0
vitest (vitest.live.config)  2 files, 11 tests   PASS   exit 1  <-- B1
mvn -o -Dtest=ContractServiceTest,ContractControllerTest test
                             43 tests            PASS   BUILD SUCCESS
npx tsc --noEmit                                 clean  exit 0
```

Note for whoever runs this next: `*.live.test.ts` is **excluded** from `npm test`
(`vitest.config.ts:29-30`). Passing `npx vitest run <file>` on a `.live.test.ts` silently runs
nothing — the run reports "7 passed" and skips the two files that carry F-0408/F-0431/F-0435/F-0438/
F-0439/F-0464/F-0466. They must be run with `--config vitest.live.config.ts`. If Wave 2's own
green-run was reported from `npm test` alone, seven of the eight api.ts findings were never
actually exercised.

---

## B1 — the live suite is red (root cause)

```ts
// api-client-resilience.live.test.ts:176-180
const promise = api.creatorCampaigns.browse();
await vi.advanceTimersByTimeAsync(5 * 60 * 1000);   // <-- promise rejects HERE, unhandled
await expect(promise).rejects.toMatchObject({ code: 'TIMEOUT' });  // handler attached too late
```

The rejection handler is attached one statement after the timers are flushed, so the
`ApiError(TIMEOUT)` thrown at `src/lib/api.ts:631` lands with no handler. Vitest reports
`Errors 1` and exits 1 even though all 8 assertions pass. Fix:

```ts
const assertion = expect(api.creatorCampaigns.browse()).rejects.toMatchObject({ code: 'TIMEOUT' });
await vi.advanceTimersByTimeAsync(5 * 60 * 1000);
await assertion;
```

The product code is fine — this is purely the test. But it is a shipping CI gate, so it blocks.

---

## Revert probe (mine, F-0419)

I restored the pre-fix default in `brand-analytics.tsx` — `useState<string>('')` plus
`setSelectedCreatorId((prev) => prev || derived[0]?.id)` in `refreshRoster` — and re-ran
`brand-analytics.aggregate.test.tsx`:

```
× shows the SUM of every creator's Total Reach by default
  → expected "spy" to be called 2 times, but got 1 times
  → expected 'Total Reach:1000' to be 'Total Reach:3000'
```

Exactly the finding's symptom (first creator's 1000 presented as the account total), and the test
goes red on it. The gate is real, not self-greening. File restored byte-for-byte; probe markers
verified gone (`grep -c "PRIYA REVERT PROBE"` → 0).

---

## Per-finding verdicts

### Assignment 1 — `src/lib/api.ts` cluster (8)

The brief asked whether each sub-finding has its **own** fix and test. Answer: **5 of 8 have their
own code change in this wave. 3 (F-0408, F-0431, and the discovery half of F-0435) were already
fixed in commit `1792c37` before this wave began** — Wave 2 contributed only regression pins for
those. The test file header at `creator-discovery-dto-fidelity.live.test.ts:9-10` discloses this
honestly, which I credit. But they should not be counted as Wave 2 fixes.

| ID | Verdict | Citation |
|----|---------|----------|
| **F-0408** | **FIXED** (pre-wave code + this wave's pin) | Both halves close. Client sends `sortBy` — `src/lib/api.ts:1767`. The two lying orders are no longer *advertised*: `CreatorSortOrder` is `followers\|engagement\|price_low\|price_high` (`src/lib/api.ts:1725`) and "Relevance" was removed from the Sort menu (`creator-discovery.tsx:1183-1198`, rationale at `:477-484`). Test: `creator-discovery-dto-fidelity.live.test.ts:84`. Residual (not blocking): the server still maps `rating`→`engagementRate` and `relevance`→`totalFollowers` (`CreatorDiscoveryService.java:1073-1074`) — dead branches now that nothing sends them. |
| **F-0431** | **FIXED** (pre-wave code + this wave's pin) | All six params reach the query: `src/lib/api.ts:1759-1767`. `categories` is legitimately folded into `verticals` — I verified `mergeCategoryFilters` unions and de-dupes both (`CreatorDiscoveryService.java:1030-1034`), so the prose claim is true, not a hand-wave. Test asserts all six on the wire: `creator-discovery-dto-fidelity.live.test.ts:84-91`. |
| **F-0435** | **NOT FIXED** | Only 1 of the 3 named drifts closed. ✔ dead `city` fallback removed (`src/lib/api.ts:1780-1787`, tested at `:94`). ✘ `PortfolioItem.metrics` is still declared (`src/lib/types.ts:677-682`) with **no counterpart** in `CreatorDtos.PortfolioItemResponse` (`influora-api/.../creator/CreatorDtos.java:49-55`). ✘ `description` is still hard-nulled by the mapper (`CreatorMapper.java:79-80`). No test covers either. Low blast radius (nothing renders `metrics` today) but the finding text names all three and only one was addressed. |
| **F-0438** | **FIXED** | `redirectToLogin` at `src/lib/api.ts:232-238`, called on the terminal-401 path at `:602` after `clearToken`. Login paths mirror `App.tsx`'s guards (`:206-209`); self-redirect loop guarded (`:235`); SSR-safe (`:233`). 4 tests incl. the negative (refresh-succeeds) and loop cases — `api-client-resilience.live.test.ts:82-156`. |
| **F-0439** | **FIXED** | `fetchWithTimeout` at `src/lib/api.ts:618-641`; `REQUEST_TIMEOUT_MS = 30_000` at `:368`; wired into `request` (`:664`), `requestWithMeta` (`:746`), `requestOrNull` (`:802`), `downloadBlob` (`:878`). `AbortError` → `ApiError('TIMEOUT')` so it lands in every caller's existing `instanceof ApiError` branch. The upload exclusion (`:843-849`) is the correct call, not laziness — a fixed 30s ceiling would kill legitimate slow mobile uploads. Product code sound; its **test file is B1**. |
| **F-0442** | **FIXED** | `id:` now parsed (`src/lib/api.ts:2718`, before the `deal-message`-only filter at `:2719` — spec-correct, a heartbeat can carry an id), stored (`:2595`), sent on reconnect (`:2520`). Server half verified real: `DealController.java:145` reads `Last-Event-ID`, `DealMessageStreamRegistry#replayMissedEvents:187-196` replays. I also checked the CORS trap myself — `CorsConfig.java:25` is `setAllowedHeaders(List.of("*"))`, so the non-simple header survives preflight. Tests at `deal-message-stream.test.ts:257,276`. Weakness: the tests assert the *header is sent*, not that replayed messages arrive — narrower than the finding's stated `missed_by`. Acceptable given the server leg has its own coverage. |
| **F-0464** | **FIXED** (type), with a real residual | `engagementRate: number \| null` at `src/lib/api.ts:3604`, matching the nullable, never-initialised `CreatorProfile.engagementRate` column. Test at `creator-discovery-dto-fidelity.live.test.ts:128`. **Residual:** the fix's own comment promises an honest "not yet scored", but the one render site was not updated — `src/pages/creator-profile.tsx:545` renders `{profile.engagementRate}%`, so a never-synced creator now sees a bare **"%"** in a 3xl-bold tile labelled "Engagement Rate". `tsc` cannot catch this (JSX accepts `null` as a child), which is precisely why the type change needed a render-site pass. Not fabrication (the finding's stated harm is avoided) but it is a visible defect. **Open a follow-up.** |
| **F-0466** | **FIXED** (transport), with a scope gap | `field`/`fields` added to the `ApiError` ctor (`src/lib/api.ts:323-338`) and carried at all three envelope-error choke points (`:688-690`, `:766-768`, `:823-825`). Wire shape confirmed against `ApiErrorBody.java:20-21,31,37-38` — the server genuinely sends both. Tests at `api-client-resilience.live.test.ts:204,228`. **Gap:** `grep -rn "err\.field\|\.fields\b" src/ --include=*.tsx` outside `api.ts` returns **nothing**. Zero forms consume it, so the finding's second clause ("every field error can only surface as a generic message") is still literally true for the user, and the stated `missed_by` ("a test asserting a server field error lands on that field in the form") was not written. The enabler is correct and I'll take it — but this closed the plumbing, not the user-facing symptom. |

### Assignment 2 — `creator-discovery.tsx` (4)

The brief asked whether the wired filters actually reach the request rather than existing as UI
state. I traced the call site, not the test.

| ID | Verdict | Citation |
|----|---------|----------|
| **F-0405** | **FIXED** | Every control is a query param at the real call site — `creator-discovery.tsx:687-705` (`languages`, `minEngagementRate`/`maxEngagementRate`, `isVerified`, `sortBy` all passed, each with an "untouched → `undefined`" sentinel so a default slider isn't sent as a bound). The client-side predicate block is now explicitly mock-mode-only (`:814-815`, `if (liveApi) return apiCreators;`) — it does not double-filter the server's page. All 10 inputs are in the `useCallback` deps (`:730-741`), so a filter change actually re-fetches. Test: `creator-discovery-server-filters.test.tsx:84`. |
| **F-0409** | **FIXED** | Client price predicate now mirrors the server's `rateOverlap` OR-isNull — `creator-discovery.tsx:871-876` (`c.averageRate == null \|\| (…)`). Wave 2's own contribution here is the fixture that makes it testable: the unpriced creator at `:348-371`. Test: `creator-discovery-server-filters.test.tsx:164`. |
| **F-0410** | **FIXED** | `setApiTotal(result.meta.total)` at `:710`; display reads `Showing N of {apiTotal} creators` at `:1338-1340` with an honest fallback when the envelope omits it. I checked the server actually supplies it: `PageMeta.java:3` declares `long total` (primitive — always serialized), populated at `CreatorDiscoveryService.java:221` and returned at `CreatorController.java:78`. So the fallback branch is unreachable in live mode, which is what we want. Test: `creator-discovery-server-filters.test.tsx:139`. |
| **F-0411** | **NOT FIXED** | No code change, no test, no acknowledgement. `INDIAN_CITIES` is still a module-level literal (`creator-discovery.tsx:154`) consumed at `:1049`; `languages` still a module-level literal (`:442`). `grep -rn "creators/search\|availableFilters\|SearchFiltersMeta" src/` → **0 hits**, and `grep -rn "F-0411" src/` → **0 hits**. The backend endpoint is alive and unconsumed: `CreatorController.java:81` (`GET /creators/search`) → `CreatorDiscoveryService.java:238` → `buildAvailableFacets():772`. **B2.** |

### Assignment 3 — `brand-analytics.tsx` + `brand-creator-analytics.tsx` (2)

The brief asked whether live mode can still show fabricated or misattributed data. I treated this
as the trust question it is and probed it directly.

| ID | Verdict | Citation |
|----|---------|----------|
| **F-0419** | **FIXED** — not cosmetic | The page no longer picks a creator; it computes a real aggregate. `aggregateMetricsAcrossRoster` at `brand-analytics.tsx:49-138` fans out one real `GET /analytics/creators/{id}/metrics` per roster creator via `Promise.allSettled`, sums the additive metrics, **averages** the rate-shaped ones, and merges `trendData` by date. Default view is the aggregate (`:222`). Three honesty properties I specifically checked and confirmed: (a) a failed creator is **excluded from the sum, not zeroed** (`:59-63`) — a zero would have silently deflated the account total; (b) the partial state is **disclosed** ("Couldn't load N of M creators — totals below are partial", `:353-357`); (c) the per-creator drill-down is now labelled `"… only — not your full roster"` (`:352`) and its menu items read `"{name} only"` (`:368`), so the two views cannot be confused. Roster source is real deals, not fixtures (`:174-183`); `demoCreators` is gated on `live` at `:220`. My revert probe (above) confirms the test catches the original defect. **Follow-up, not blocking:** N parallel requests per date-range change, unbounded by roster size — fine at today's scale, needs a concurrency cap or a real aggregate endpoint before a brand with a large roster hits it. |
| **F-0441** | **FIXED** | `brand-creator-analytics.tsx:79` — `isApiLive() ? undefined : demoCreators.find(...)`. `isApiLive()` is the genuine build-time mode check (`src/lib/api.ts:60-62`), the same one `brand-analytics.tsx` uses. I traced every consumer of `creator` on the page: displayName (`:104,109`), verified badge (`:111`), location (`:114`) — all three are `creator?.`-guarded, so in live mode the fabricated identity is structurally unreachable, not merely hidden. Header degrades to the raw `creatorId` — ugly, honest, correct. Tests at `brand-creator-analytics.live-demo-gate.test.tsx:93,102,108` cover all three surfaces including the id-collision case. |

### Assignment 4 — `creator-chat.tsx` (2)

| ID | Verdict | Citation |
|----|---------|----------|
| **F-0639** | **FIXED** | `creator-chat.tsx:1851` splits the guard on `dealRooms.length > 0`; stale-link copy at `:1856-1863`, genuine zero-deals copy at `:1865-1870`. Tests at `creator-chat-unmatched-deal-id.test.tsx:96,117` assert **both** directions, which is the right shape — one-sided would have let "Deal not found" leak onto a genuinely empty account. Nit: the copy says "Your other deals are still here" but this branch replaces the whole pane and offers no control to reach them. Add a link. |
| **F-0349** | **FIXED** (code pre-wave, commit `42acea9`; Wave 2 added the pin) | `creator-chat.tsx:2902-2919` — the 0-slot branch no longer delegates to `DealDeliverablesTab`'s "…once the creator submits content" and instead names the real unblocker (contract generation). Test: `creator-chat-deliverables-zero-slots.test.tsx:90`. Out of scope but worth logging: the same misleading string still renders for the **brand** on a 0-slot deal (`brand-chat.tsx:2816,2828` → `deal-deliverables-tab.tsx:56`) — that is open F-0278's territory, not this one's. |

### Assignment 5 — backend `MoneyDtos.java` / `ContractService.java` (1)

| ID | Verdict | Citation |
|----|---------|----------|
| **F-0632** | **FIXED** | `ContractResponse` gained `campaignTitle` + `brandWorkspaceName` as trailing components (`MoneyDtos.java:265-266`) — additive, so no positional break for existing callers; `@JsonInclude(NON_NULL)` on the record (`:246`) means unresolvable → field omitted, never an empty-string placeholder. Resolvers are genuinely best-effort and cannot throw: `ContractService.java:1519-1527` (contract → collaboration → campaign → title) and `:1535-1538` (contract → workspace → name), both `.orElse(null)`. Wired at `toResponse:1509-1510`, which every list/get path goes through (`:1425-1428`). `mvn -o -Dtest=ContractServiceTest,ContractControllerTest test` → **43 tests, 0 failures, BUILD SUCCESS**, including the resolution test (`ContractServiceTest.java:1269`) and the missing-collaboration guard (`:1304`). **Follow-up, not blocking:** `toResponseWithMilestones` now costs 3 queries per contract (milestones + collaboration + campaign) plus 1 workspace — an N+1 on `GET /contracts/unsigned` (`ContractController.java:60`) and on `list` (`ContractService.java:1348,1372`). Harmless at a creator's handful of pending contracts; batch it before any list here grows. |

### Assignment 6 — `creator-dashboard.tsx` (2)

The brief asked specifically whether the frontend uses the **real** field name and handles absence
without crashing or fabricating. Both checks pass; a third one I added does not.

| ID | Verdict | Citation |
|----|---------|----------|
| **F-0637** | **FIXED** — real names, verified against the Java record | `creator-dashboard.tsx:329-333` reads `campaignTitle` then falls back to `brandWorkspaceName`. Both spellings match `MoneyDtos.ContractResponse` components exactly (`MoneyDtos.java:265-266`); Jackson serializes record components by name, so the wire names are these. Not guessed, not stale. **Absence handled correctly:** `contractIdentityLabel` returns `null` when both are missing/blank (`:331-332`), and the `<p>` is conditionally omitted rather than filled (`:606-610`) — no "Unknown brand", no empty element. Tests cover the two-same-amount case with a fallback (`creator-dashboard.contract-identity.test.tsx:180`) and the both-absent case (`:216`). **Required follow-up (open a finding):** the shared DTO type was **not** widened. `ContractApiRecord` (`src/lib/api.ts:2893-2916`) still declares neither field; the page reaches them through a local intersection type + `as` cast (`creator-dashboard.tsx:322-333`). That means `tsc` cannot police this FE↔BE binding at all — a backend rename would compile clean and silently return the row to being nameless. This is the exact `dto-drift` class the rest of this wave was fixing; "outside this file's edit boundary" is a task-scoping reason, not an engineering one. |
| **F-0638** | **FIXED (defensive) — but the premise is unreachable** | The guard is correct: `linkedDealId` at `:342-347` returns `null` for a missing/empty id, and the row then renders as a non-clickable `<div>` (`:632-641`) instead of an `<a href=".../?deal=undefined">`. Tests at `creator-dashboard.contract-identity.test.tsx:128,160` cover both directions. **However**, the trigger cannot occur against the real backend: `Contract.collaboration_id` is `@Column(nullable = false)` (`influora-api/.../domain/entity/Contract.java:25`), `ContractResponse.collaborationId` therefore always serializes, and `/contracts/unsigned` is the only producer for this list. The finding as written ("produces a broken link") describes a state the live system cannot reach. I am not downgrading it to FALSE FINDING — cheap defensive guards on interpolated hrefs are correct engineering — but it should be logged as *hardening*, not as a live-bug closure, and the fix reaches the value through `contract.collaborationId as string \| null \| undefined` (`:343`), a cast that openly contradicts `ContractApiRecord.collaborationId: string` (`src/lib/api.ts:2895`). Same root cause as F-0637's follow-up: fix the shared type and both casts disappear. |

---

## Summary

| Verdict | Count | IDs |
|---------|-------|-----|
| FIXED | 17 | F-0408, F-0431, F-0438, F-0439, F-0442, F-0464, F-0466, F-0405, F-0409, F-0410, F-0419, F-0441, F-0639, F-0349, F-0632, F-0637, F-0638 |
| NOT FIXED | 2 | **F-0435** (1 of 3 drifts closed), **F-0411** (no fix, no test) |
| FALSE FINDING | 0 | — |
| BLOCKED | 0 | — |

## Required before merge

1. **B1** — fix the unhandled rejection at `api-client-resilience.live.test.ts:176-180`; `npm run test:all` must exit 0.
2. **B2 / F-0411** — either fix it (consume `GET /creators/search` facets) or return it to the queue explicitly. Do not report it as done.
3. **F-0435** — close the `metrics` and `description` halves, or split them into their own finding and say so.

## Follow-ups to open (not merge-blocking)

- Widen `ContractApiRecord` (`src/lib/api.ts:2893`) with `campaignTitle`/`brandWorkspaceName`, and make `collaborationId` honest. Removes two `as` casts and restores `tsc` as the FE↔BE guard. *(F-0637/F-0638 root cause.)*
- `creator-profile.tsx:545` renders a bare `%` for a null `engagementRate`. *(F-0464 residual.)*
- No form consumes `ApiError.field`/`.fields` yet; the field-error UX is unchanged. *(F-0466 scope gap.)*
- `aggregateMetricsAcrossRoster` fans out unbounded parallel requests. *(F-0419 scale.)*
- `toResponseWithMilestones` is now 3–4 queries per contract row. *(F-0632 N+1.)*
- Dead server sort branches for `rating`/`relevance` (`CreatorDiscoveryService.java:1073-1074`). *(F-0408 residual.)*
- "Deal not found" pane offers no route back to the deal list. *(F-0639 nit.)*

## Process note

Seven of eight api.ts findings live in `*.live.test.ts`, which `npm test` **excludes**
(`vitest.config.ts:29-30`). Any wave report claiming green from `npm test` alone has not exercised
them. Whatever gate script covers this task must invoke `npm run test:all`, not `npm test` — and
that gate would have caught B1 on the first run.
