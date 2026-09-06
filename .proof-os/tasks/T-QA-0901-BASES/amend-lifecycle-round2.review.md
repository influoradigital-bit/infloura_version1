# Priya (CTO) — ROUND 2 fresh-context review: F-0642, F-0644, F-0645, F-0652, F-0653, F-0654

Date: 2026-09-04
Reviewer: Priya (CTO), fresh context. Second attempt; my round-1 rejections re-derived from code, not
from the submission.
Branch: `fix/f0390-money-flags-build-pipeline` @ `143ca1e` + uncommitted working tree.

## How this review was run

- Every file read from disk via `git diff` on the working tree. No claim in a javadoc or a report
  accepted without checking the code it describes.
- Tests run by me on a full **`mvn -o -B clean test`** (defeats the stale-`.class` false-green):
  `-Dtest='ContractAmendmentSupersessionTest,DeliverableMetricServiceTest,DealServiceTest,BrandDeliverableServiceTest,EscrowControllerTest,ContractCancelAmendTest,EscrowServiceIdempotencyTest'`
  → **Tests run: 134, Failures: 0, Errors: 0. BUILD SUCCESS (30.3s).**
  Individual methods confirmed present in `target/surefire-reports/*.xml`, not just the aggregate.
- **Three revert-probes of my own**, on the lifecycle fix (§5). All three genuinely fail pre-fix.

**Headline: 4 FIXED, 1 NOT FIXED. The one NOT FIXED (F-0652) now ships a live production
regression — every brand "release payment" button returns HTTP 400.** Block the wave on it.

---

## 1. F-0653 — the two files must agree on "current contract" → **FIXED**

This is the claim that was false last time, so I compared both resolution paths line by line rather
than reading the comment that says they agree.

| Reader | Input | Resolution |
|---|---|---|
| `service/DealService.java:2031` | `contractRepository.findByCollaborationIdOrderByVersionDescCreatedAtDesc(collaboration.getId())` | `ContractService.resolveCurrentContract(contracts)` — **`DealService.java:2038`** |
| `service/DeliverableMetricService.java:211` | `contractRepository.findByCollaborationIdOrderByVersionDescCreatedAtDesc(id)` | `ContractService.resolveCurrentContract(...)` — **`DeliverableMetricService.java:209`** |

Same repository method, same sort, same static callee. Not "two implementations that agree today" —
**literally one method with two call sites**. The private duplicate that used to live in
`DealService` is gone from the file (confirmed in the diff: the `ContractStatus` import was dropped
along with it, and `grep` finds no second `resolveCurrentContract` body anywhere in `src/main`).

The single definition is at **`service/ContractService.java:619-632`**: newest row wins unless it is
`DRAFT`/`PENDING_SIGNATURES`, in which case the most recent still-`ACTIVE` version is preferred.

I also checked for a **third** silent definition. `findByCollaborationIdOrderByVersionDescCreatedAtDesc`
has exactly one other production caller — `CollaborationReviveService.java:192-196` — and it only asks
`.isEmpty()`, it never picks a "current". So there are exactly two resolvers and they are the same code.

**Verdict: FIXED.** `service/ContractService.java:619`; callers `service/DealService.java:2038` and
`service/DeliverableMetricService.java:209`.

---

## 2. F-0644 — the demonstrated regression (reach 3300 → 300 on a *drafted* amendment) → **FIXED**

Round 1's gate asserted `totalReach == 300` on a `(v1 ACTIVE with a RELEASED milestone, v2 DRAFT)`
fixture — i.e. it locked the erasure in. The corrected test is
`DeliverableMetricServiceTest.testAnalyticsKeepsFundedActivePredecessorCurrentWhileAmendmentIsUnsignedDraft`
(**`:189-253`**), same fixture, and now asserts:

```java
assertEquals(2,     response.deliverablesTotal());     // :245
assertEquals(3000L, response.totalReach());            // :248  (1000 + 2000, incl. the RELEASED one)
```

While the amendment is an unsigned draft, the funded ACTIVE predecessor stays current and **both** its
milestones — including the RELEASED one whose money already moved — keep counting. The draft's own
milestone (reach 300) is excluded. The 3300 → 300 erasure is gone, and the number it was replaced by
is the correct one, not a differently-wrong one.

The filter is still genuinely contract-keyed, not a status proxy: `DeliverableMetricService.java:216-217`
filters `m.getContractId()` against `currentContractIds`, and `:222-228` filters metrics through the
milestone ids derived from that same set.

**Verdict: FIXED.** `service/DeliverableMetricService.java:206-217`; gate at
`DeliverableMetricServiceTest.java:245,248`. Proven non-tautological by probe B/C (§5).

---

## 3. F-0654 — a signed amendment genuinely retires its predecessor → **FIXED**

Round 1's defect was that the post-signature test hand-built v1 as `CANCELLED`, a state `amend()`
provably cannot produce for an ACTIVE predecessor. I checked specifically whether the new test drives
the real signature flow or just re-poses a fixture.

**It drives the real flow.** `ContractAmendmentSupersessionTest.testSigningAmendmentToCompletionRetiresActivePredecessor`
(**`:231-282`**):

- calls the real `contractService.amend(...)` (`:196-197`) to produce the amendment — not a builder;
- captures the amendment actually saved by production code (`:199-208`);
- asserts the precondition it depends on — predecessor still ACTIVE, amendment DRAFT (`:212-213`);
- then calls the real **`contractService.recordSignature(...)`** (`:253`) and
  **`contractService.recordSignatureForCreator(...)`** (`:259`) — the two calls a real e-sign UI makes,
  through `IdempotencyService.executeOnce` (stubbed to actually invoke the supplier, `:152-159`);
- asserts the predecessor is now `COMPLETED` (`:263-267`) and that **exactly one ACTIVE contract
  survives** (`:269-271`) — the finding's own `missed_by` bar, met verbatim.

The mirror-image guard (`:291-310`) proves a half-signed amendment does **not** retire the predecessor,
so the transition is scoped to the moment both signatures land, not fired eagerly.

Production side: **`service/ContractService.java:1133`** calls `retirePredecessorIfSuperseded(contract)`
inside the `brandSignedAt != null && creatorSignedAt != null` branch of `doRecordSignature`, ahead of
the best-effort side effects and — correctly — *not* wrapped in a try/catch, so it rolls back with the
signature. The method itself is **`:684-694`**; it is the third-ever `Contract#setStatus` call site and
the first that writes anything other than `CANCELLED`.

`DealServiceTest.testGetSurfacesNewestContractOnceAmendmentIsSigned` (**`:2531-2573`**) has been
corrected too: its predecessor is now `COMPLETED` (`:2553`) — the status production code actually
writes — instead of the impossible `CANCELLED`.

**Verdict: FIXED.** `service/ContractService.java:684-694` + `:1133`; proven by
`ContractAmendmentSupersessionTest.java:263-271` and my probe A (§5).

---

## 4. F-0645 — funded/current split after amend → **FIXED (for the finding as written)**

The unsigned-amendment window was already honest in round 1. Round 1's blocker was that the break
returned permanently at signature because nothing retired the ACTIVE predecessor — that is exactly what
F-0654 has now closed, and the whole post-signature state is under a real-flow test rather than an
unreachable fixture. Both halves of the finding's `missed_by` ("preserved **or** explicitly surfaced")
are now satisfied by preservation, with a single-ACTIVE invariant behind it.

**Verdict: FIXED.** `service/ContractService.java:619-632` (window) + `:1133` (window close);
`service/DealService.java:2038`.

**Residual, now a separate finding, not a re-block —** see N-2 below: `escrowFunded` is still computed
collaboration-scoped (`DealService.java:2045-2047`), so after a signed amendment the response says
"current = v2, escrowFunded = true" while the FUNDED hold is bound to the retired v1's milestone. That
is a *different* symptom from the one F-0645 names (the contract-version link is no longer broken; the
escrow-to-milestone binding is), and it needs a product decision (carry escrow forward vs. surface a
`fundedContractId`), not a patch. `DealServiceTest.java:2567` stubs `hasEscrowForCollaboration → true`
in exactly this state and asserts nothing about it.

---

## 5. F-0642 — the test must assert on the response DTO, not the internal record → **FIXED**

Round 1's only new assertion was on `EscrowService.ReleaseOutcome` — the internal service record, the
exact "log line with a different name" shape.

The new test is `BrandDeliverableServiceTest.testApproveReturnsPaymentHeldReasonWhenMilestoneNotFunded`
(**`:221-251`**). It stubs `escrowService.tryReleaseOnApproval(...)` to return a held outcome and then
asserts on the value that comes back from `service.approve(...)`:

```java
assertEquals(DeliverableStatus.APPROVED, response.status());
assertEquals(false,                      response.paymentReleased());
assertEquals("MILESTONE_NOT_FUNDED",     response.paymentHeldReason());   // :250
```

`response` is `ReviewResponse` — the Jackson-serialised record at
`web/dto/deliverable/BrandDeliverableDtos.java:29-30`, i.e. the actual wire field `src/lib/api.ts:3031`
types and `DeliverableViewer.tsx:277-280` renders. The mapping under test is
`BrandDeliverableService.java:241-242`. A refactor that drops or renames the DTO field now fails a
backend test, which is the exact residual I recorded in round 1.

Confirmed the method actually ran (surefire report, `time="0.02"`), not skipped.

**Verdict: FIXED.** `BrandDeliverableServiceTest.java:250`, asserting
`web/dto/deliverable/BrandDeliverableDtos.java:29-30` via `service/BrandDeliverableService.java:241-242`.

---

## 6. F-0652 — Idempotency-Key on /release and /refund → **NOT FIXED. BLOCK. This one now breaks production.**

The header is genuinely required and genuinely forwarded — round 1's "dead code" complaint is answered.
`web/EscrowController.java:110` and `:155` declare `@RequestHeader("Idempotency-Key")`, and the key
reaches the idempotency-aware overloads at **`:131`**, **`:134-135`** and **`:165`**, matching `/fund`'s
shape at `:71,87`. `EscrowControllerTest` (14/14 pass) verifies the 4-arg overloads are called and the
3-arg ones never are.

**But nothing sends the header, and the header is mandatory. Both live brand release paths now 400.**

`@RequestHeader("Idempotency-Key")` defaults to `required = true`. Spring therefore throws
`MissingRequestHeaderException` **before the controller method body runs**, and
`common/GlobalExceptionHandler.java:142-151` turns that into
**HTTP 400 `MISSING_HEADER` — "Required header 'Idempotency-Key' is missing"**.

The only client wrapper is `payments.releasePayout` at **`src/lib/api.ts:3629-3640`**, and it passes
**no `idempotencyKey`**:

```ts
return http.request<...>('POST', '/wallet/escrow/release', {
  body,                       // <-- no idempotencyKey; http.request only sets the header when given one
});
```

`ApiClient#headers` (`src/lib/api.ts`, `private headers(role, extra?)`) adds `Idempotency-Key` **only**
from that `extra` argument (`:556`) — there is no default and no interceptor. So both live callers break:

| Caller | Line | Result after this change |
|---|---|---|
| brand deal room, manual release (F-0224) | `src/components/brand/deal-room/deal-payments-tab.tsx:110` | **400 MISSING_HEADER** |
| brand wallet, release a milestone-less hold (F-0489, added in this same tree) | `src/pages/brand-wallet.tsx:533` | **400 MISSING_HEADER** |

This is worse than the finding it closes. F-0652's symptom was "a duplicated release request has no
replay key". The state after this change is "**no** release request works at all." `/fund` is not a
precedent for the required-header choice: its wrapper *does* pass a key
(`api.ts:3596-3606`), which is precisely why `/fund` works today.

Two secondary defects in the same fix:

- **The "missing header" tests assert a code path no HTTP request can reach.**
  `testReleaseRejectsWhenIdempotencyKeyMissing` / `testRefundRejectsWhenIdempotencyKeyMissing` call
  `controller.release(principal, null, ...)` as a plain Java method and assert the error code
  `IDEMPOTENCY_KEY_REQUIRED`. Over HTTP a real client gets `MISSING_HEADER` from the global handler
  instead — the in-body null check at `:112-117` / `:157-162` is unreachable in production. The tests
  are green against a scenario the wire cannot produce. (`/fund:73-78` has the same inherited shape;
  copying it does not make it reached.)
- **`testRefundDuplicateRequestsForwardSameKeyToService`** proves the *controller forwards* the key
  twice; it does not prove a duplicate request produces a single effect — the service is a Mockito
  mock. The real dedup evidence remains `EscrowServiceIdempotencyTest` (3/3 pass, and I re-confirmed in
  round 1 that its second-snapshot assertion genuinely proves the write path did not re-run). The
  chain controller → service is sound; the claim "a real request reaches the idempotency-aware method"
  is proven at unit level only, and is currently false end-to-end because the request 400s first.

**To actually close it, one of:**
1. pass a key from the client — `payments.releasePayout(..., idempotencyKey)` threaded through both
   callers (the `fundEscrow`/`withdraw` pattern, `api.ts:3252,3297,3596`) — and keep the header required; or
2. make it `required = false` on both routes until the client sends it, so the in-body 400 with the
   real `IDEMPOTENCY_KEY_REQUIRED` code is what clients actually receive.

Option 1 is correct; option 2 alone still leaves every release failing, so it must be paired with (1)
if the intent is a hard requirement.

**Verdict: NOT FIXED.** `web/EscrowController.java:110,155` (required header) vs
`src/lib/api.ts:3629-3640` (no key sent) → `common/GlobalExceptionHandler.java:145-150` (400).
Live callers: `src/components/brand/deal-room/deal-payments-tab.tsx:110`, `src/pages/brand-wallet.tsx:533`.

---

## 7. My own revert-probes (the lifecycle fix — F-0644/F-0653/F-0654)

I chose the lifecycle fix as instructed, and probed **three** separate things, because a single probe
on this fix could not distinguish "the shared resolver works" from "the analytics filter is gated".
`ContractService.java` backed up first (md5 `5d27eac5603c790fc9d6e9123c0af1b9`), every probe run under
`mvn -o -B clean test`.

**Probe A — disable the F-0654 retirement** (`ContractService.java:1133` commented out):
```
[ERROR] ContractAmendmentSupersessionTest.testSigningAmendmentToCompletionRetiresActivePredecessor:263
        F-0654: the superseded predecessor must be retired out of ACTIVE once the amendment
        that replaces it is genuinely, fully signed ==> expected: <COMPLETED> but was: <ACTIVE>
[ERROR] Tests run: 77, Failures: 1 — BUILD FAILURE
```
`DealServiceTest` (68) and `DeliverableMetricServiceTest` (7) stayed green — the probe is precisely
targeted, and the F-0654 gate is real.

**Probe B — revert `resolveCurrentContract` to the naive `contracts.get(0)`** (`:619`):
```
[ERROR] DealServiceTest.testGetKeepsFundedActiveContractCurrentWhileAmendmentDraftIsUnsigned:2505
        expected: <01HCONTRACTACTIVE1234> but was: <01HCONTRACTDRAFT12345>
[ERROR] DeliverableMetricServiceTest.testAnalyticsKeepsFundedActivePredecessorCurrentWhileAmendmentIsUnsignedDraft:217
        DealService's resolution must prefer the still-ACTIVE predecessor over the unsigned draft
[ERROR] Tests run: 77, Failures: 2 — BUILD FAILURE
```
**Both** files' gates fail from **one** reverted method — direct proof they share the definition rather
than merely agreeing.

**Probe C — probe B, plus the metric test's cross-check assertion neutralised** (`if (false)` on
`DeliverableMetricServiceTest:217`), to check the analytics path itself is gated and not just a static
call made from the test:
```
[ERROR] DeliverableMetricServiceTest.testAnalyticsKeepsFundedActivePredecessorCurrentWhileAmendmentIsUnsignedDraft:245
        expected: <2> but was: <1>
```
It is. With the naive resolver, `getCampaignAnalytics` drops back to 1 milestone (the draft's) —
the 3300→300 erasure — and the assertion catches it through the service, not through the test's own
static call.

Both files restored and verified byte-identical (`ContractService.java` md5 back to
`5d27eac5603c790fc9d6e9123c0af1b9`; `grep -c PROBE_` = 0 in both). Post-restore run re-confirmed
134/134 green.

---

## Verdicts

| Finding | Verdict | Citation |
|---|---|---|
| **F-0642** heldReason must reach a brand-visible field | **FIXED** — asserts `ReviewResponse.paymentHeldReason()`, the serialised DTO field, not `ReleaseOutcome` | `BrandDeliverableServiceTest.java:250` → `service/BrandDeliverableService.java:241-242` → `web/dto/deliverable/BrandDeliverableDtos.java:29-30` |
| **F-0644** analytics double-count / draft-amendment erasure | **FIXED** — reach 3300→300 regression gone; a drafted amendment now yields the correct 3000, RELEASED milestone retained | `service/DeliverableMetricService.java:206-217`; gate `DeliverableMetricServiceTest.java:245,248`; probe C |
| **F-0645** funded/current split after amend | **FIXED** for the finding as written — honest during the draft window and no longer re-broken at signature. Escrow-to-milestone binding residual recorded as N-2. | `service/ContractService.java:619-632` + `:1133`; `service/DealService.java:2038` |
| **F-0652** request-level idempotency on release/refund | **NOT FIXED — BLOCK.** Mechanism is now reachable, but the header is `required=true` and **no client sends it**: both live release callers now get HTTP 400 `MISSING_HEADER`. The "missing key" tests assert an error code no HTTP request can produce. | `web/EscrowController.java:110,155` vs `src/lib/api.ts:3629-3640`; `common/GlobalExceptionHandler.java:145-150`; callers `deal-payments-tab.tsx:110`, `brand-wallet.tsx:533` |
| **F-0653** conflicting current-contract definitions | **FIXED** — one static method, two call sites, identical input; no third resolver in `src/main`. Probe B breaks both files from one edit. | `service/ContractService.java:619`; `service/DealService.java:2038`; `service/DeliverableMetricService.java:209` |
| **F-0654** terminal status never reached | **FIXED** — real `amend` → real `recordSignature` → real `recordSignatureForCreator` flow; predecessor → `COMPLETED`; exactly one ACTIVE survives | `service/ContractService.java:684-694`, `:1133`; `ContractAmendmentSupersessionTest.java:263-271`; probe A |

## Gate for this wave

- **BLOCK on F-0652.** Do not ship `EscrowController.java` as it stands — it takes a working release
  path and makes it return 400 for every caller. Fix by threading an `idempotencyKey` through
  `payments.releasePayout` and both callers, then re-run. This is a live money-path outage, not a
  cosmetic gap.
- **F-0642 / F-0644 / F-0645 / F-0653 / F-0654 clear.** The lifecycle fix is coherent, shares one
  definition, is driven by the real signature flow, and survives all three of my revert-probes. Sign-off
  on these five.
- Open the residuals below as new ledger entries; none of them blocks the five cleared findings.

## New findings to record (not blocking the five cleared items)

- **N-1 (`dropped-field`, MEDIUM) — post-signature analytics still discard a paid, delivered milestone.**
  Once an amendment is signed and the predecessor is retired to `COMPLETED`, `getCampaignAnalytics`
  scopes to the amendment's milestones only, so a `RELEASED` milestone on the retired version (money
  already moved, metrics already reported) drops out of campaign totals permanently. Locked in by
  `DeliverableMetricServiceTest.java:312` (`assertEquals(300L, response.totalReach())`, discarding a
  RELEASED 2000). Narrower than the round-1 regression — it no longer fires on a mere draft — but it
  still makes an amended campaign under-report its own delivered work. For an *aggregate*, the right
  rule is the union of every non-`CANCELLED` version's milestones, de-duplicated, not "the current
  version only". `service/DeliverableMetricService.java:216-217`.
- **N-2 (`stale-binding-after-amend`, MEDIUM) — `escrowFunded` is still collaboration-scoped.**
  `DealService.java:2045-2047` computes it via `hasEscrowForCollaboration`, so after a signed amendment
  the response reads "current = v2, escrowFunded = true" while the FUNDED hold sits on the retired v1's
  milestone. Needs a ruling (carry escrow forward on amendment-signature, or add an explicit
  `fundedContractId`), not a patch. `DealServiceTest.java:2567` sets up this exact state and asserts
  nothing about it.
- **N-3 (`n-plus-one`, LOW) — unchanged from round 1.** `DeliverableMetricService.java:206-214` issues
  one `contractRepository` query **per collaboration** inside a stream, on a read path. 200
  collaborations = 200 queries. Batch it with an `IN`-shaped repository method.
- **N-4 (`comment-lies`, LOW) — a false javadoc in the new test.**
  `ContractAmendmentSupersessionTest.java:166-171` says the helper "Drafts and fully signs the ORIGINAL
  contract (version 1) via the real generate -> recordSignature -> recordSignatureForCreator path".
  It does not — `:173-181` hand-builds the original with `.status(ContractStatus.ACTIVE)`. The
  load-bearing half (the *amendment's* signature flow) is genuinely real, so this does not change the
  F-0654 verdict, but the comment overstates what the test does and should be corrected before someone
  cites it.
- **N-5 (`newly-reachable-enum`, LOW) — `COMPLETED` renders as "Signed".**
  `ContractStatus.COMPLETED` had zero writers before this wave and now has one. The FE maps it at
  `src/components/brand/contracts/contracts-and-deliverables.tsx:408-409` to the same `'signed'` UI
  status as `ACTIVE`, so a retired predecessor lists as "Signed" beside the new active contract. No
  crash, no regression (the branch predates this wave), but the contract list is now ambiguous about
  which version is live. Worth a distinct "Superseded" label — and it is the concrete argument for the
  dedicated `SUPERSEDED` status the fix's own javadoc flags at `ContractService.java:664-671`.
