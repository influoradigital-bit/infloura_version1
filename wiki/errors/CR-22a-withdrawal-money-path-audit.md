# CR-22a — Deal-withdrawal money path: adversarial audit + state-model proposal

**Author:** Kabir (Red-Team / offensive security)
**Date:** 2026-07-28
**Branch audited:** `cr-08-deal-lifecycle-sse` (working tree, not a sibling worktree)
**Scope:** analysis only. **No code was written or edited.** No git state was changed.
**Input finding:** `wiki/errors/CREATOR-BUG-TRACKER.md` §10.1 (Priya's CR-22 ruling)

All line references below are to files under
`C:\Users\Sage world\Downloads\New Influora Ai\New Influora\` on this branch.

---

## 0. Verdict in one paragraph

The §10.1 finding is **confirmed on every checkable fact and understated in its conclusion.**
`canReject()` really does admit `CONTRACTED` / `IN_PROGRESS` / `REVIEW_PENDING`, `reject()` really
does transition straight to `CANCELLED` and touch nothing else, and no UI reaches it in those
states today. But the phrase "strand the money" is the wrong diagnosis, and it points CR-22b at
the wrong fix. The money is not stranded — it sits in the platform clearing wallet with a live,
fully-actionable `FUNDED` escrow hold, and after the transition the **brand retains a unilateral
100% refund option with no collaboration-status gate on it**. The real defect is one level up:
**`CollaborationStatus.CANCELLED` is not enforced by anything downstream of the status column.**
Contracts can still be generated and fully signed, escrow can be funded *for the first time*,
deliverables can still be submitted and approved, and an approval still releases real money — all
on a collaboration whose status reads `CANCELLED`. `reject()` is not a withdrawal. It is a label
change on one row. Putting a button on it does not strand money; it produces deals that are
simultaneously cancelled and executable.

---

## 1. The central claim — confirmed, with the mechanism corrected

### 1.1 What was verified true

**`canReject()` is as described.** `influora-api/src/main/java/com/influora/domain/entity/Collaboration.java:196-200`:

```java
public boolean canReject() {
    return status != CollaborationStatus.COMPLETED
            && status != CollaborationStatus.CANCELLED
            && status != CollaborationStatus.DISPUTED;
}
```

`CollaborationStatus` has 13 values (`domain/enums/CollaborationStatus.java`). This blocks 3, so it
permits **10**, including `TERMS_AGREED`, `CONTRACT_PENDING`, `CONTRACTED`, `IN_PROGRESS`,
`REVIEW_PENDING`, `REVISION_REQUESTED`. Compare `canAccept()` (`:185-190`), which is a
4-value allowlist. `canReject()` is a denylist over a growing enum — a 14th status is rejectable by
default, which is the wrong default for a terminal verb.

**`reject()` does exactly what §10.1 says and nothing more.** `service/DealService.java:270-304`.
The full body: `requireRole` → `requireOwnedCollaboration` → `canReject()` guard →
`transitionTo(CANCELLED)` → `save` → `appendSystemMessage("Brand rejected: …")` →
`settleLatestProposal(…, "rejected")` → two SSE publishes → `OkResponse.success()`. No
`EscrowService` call, no `ContractService` call, no `DeliverableRepository` call. The three
repositories that *would* be needed are already injected into this class
(`contractRepository`, `escrowHoldRepository`, `deliverableRepository`, `:82-84`) and are used
only for read-only display in `toDealResponse` (`:969-976`).

**Both parties can call it.** `requireRole` (`:763-773`) admits `BRAND` or `CREATOR`;
`requireOwnedCollaboration` (`:730-742`) scopes to the caller's workspace or creator id. The
B-4 javadoc at `:265-269` is accurate about what it changed.

**Nothing reaches it post-contract today.** All four frontend call sites verified:

| Call site | Gate | Reachable statuses |
|---|---|---|
| `src/components/brand/deals/deal-room-dashboard.tsx:416` | button rendered under `selectedDeal.status === 'proposed'` (`:659`, `:676`); `mapDealStatus` (`:81-96`) maps only `INVITED`/`APPLIED`/`SHORTLISTED` to `'proposed'` | pre-contract only |
| `src/pages/brand-campaign-detail.tsx:674` | bids list is `BID_STAGE_STATUSES` (`:59`, `:575`) = `INVITED`/`APPLIED`/`SHORTLISTED`/`IN_NEGOTIATION` | pre-contract only |
| `src/pages/creator-chat.tsx:1322` | proposal-card scoped; the CTO ruling is recorded in-file at `:2061-2069` | pre-contract only |
| `src/pages/creator-deals.tsx:391` | `new` bucket only (CR-27 ruling) | pre-contract only |

Backend tests confirm the gap is untested rather than pinned: `DealServiceTest` exercises reject
for auth (`:477`, `:512`) and SSE ordering (`:751`), never for a post-contract status.

**`reject()` is the only writer of `CANCELLED` in the codebase.** `grep transitionTo(` across
`src/main/java` returns exactly four call sites: `DealService:280` (CANCELLED), `:572`
(TERMS_AGREED), `:658` (IN_NEGOTIATION), and `DisputeService:145` (DISPUTED), plus
`CollaborationLifecycleService:192/215`. **There is no second endpoint with the same gap.** The
state model has exactly one entry point to fix, which is the good news in this document.

### 1.2 Where the claim's mechanism is wrong

"Strand the money" implies the funds become unreachable. They do not.

`LedgerEscrowBackend.fund` (`service/escrow/LedgerEscrowBackend.java:50+`) posts brand wallet →
**platform clearing wallet**. The `EscrowHold` row stays `FUNDED`. Three paths still move it after
the collaboration is `CANCELLED`, and **none of them reads `Collaboration.status`**:

1. **`EscrowService.refund`** (`service/EscrowService.java:549-596`). Requires brand `OWNER`/`ADMIN`,
   requires `FUNDED`, blocks only on `DISPUTED` (`assertEscrowNotBlockedByDispute`, `:1083-1091`).
   The brand refunds itself in full.
2. **`EscrowService.release` / `releaseInternal`** (`:411-546`). Same: dispute-gated only. Also
   reachable indirectly via `tryReleaseOnApproval` (`:438-462`) from `BrandDeliverableService.approve`.
3. **`DisputeService.openDispute`** (`service/DisputeService.java:107-149`). Has **no
   collaboration-status gate at all** — it will take a `CANCELLED` collaboration to `DISPUTED`
   provided `hasFundedUnreleasedEscrow` is true. So the creator's escape hatch technically survives
   a reject. See §3.2 for why that escape hatch is itself broken.

So the accurate statement, and the one CR-22b should design against:

> `reject()` does not strand money. It silently converts a two-party escrow into a **unilateral
> brand option**. After the transition the brand may refund 100% of a hold on a deal where the
> creator has already delivered and had work approved, and the only deal-level record is a system
> message reading "Brand rejected: …". Nothing in the timeline, the contract, or the escrow row
> records that a signed contract and a funded hold existed at the moment of cancellation.

---

## 2. Real blast radius — what `CANCELLED` actually means today

### 2.1 The finding §10.1 missed: nothing enforces `CANCELLED`

A grep for `CollaborationStatus` checks across the services that run *after* a deal is contracted
returns almost nothing:

- `ContractService.java` — **no `CollaborationStatus` check anywhere.** `generate` (`:134-208`)
  checks workspace ownership, takes a `PESSIMISTIC_WRITE` row lock (`:182-189`), and checks for a
  pre-existing non-CANCELLED *contract* — it never looks at the collaboration's status.
  `recordSignature` / `recordSignatureForCreator` / `doRecordSignature` (`:505-615`) likewise.
- `CreatorDeliverableService.java`, `BrandDeliverableService.java`, `ShipmentService.java` — zero
  `CollaborationStatus` references.
- `EscrowService.java` — one, and it is `DISPUTED` only (`:1084`).
- `ReviewService.java:115` — checks `COMPLETED`, not `CANCELLED`.
- `CollaborationLifecycleService.java:52-53` — the only place `CANCELLED` is honoured, as a
  `FROZEN` set that prevents *status* nudges. It stops the label from moving. It stops nothing else.

Concretely, after `POST /deals/{id}/reject` on a `CONTRACT_PENDING` deal:

1. The half-signed contract can still be fully signed — `doRecordSignature` has no gate — and
   `Contract.recordCreatorSignature()` → `advanceIfFullySigned` sets `ContractStatus.ACTIVE`
   (`domain/entity/Contract.java:142-153`).
2. `onContractFullySigned` then no-ops because the collaboration is `FROZEN`
   (`CollaborationLifecycleService:179-181`). Result: **an ACTIVE contract on a CANCELLED
   collaboration.**
3. `EscrowService.initiateFund`'s only contract-awareness gate is
   `assertContractActiveForMilestone` (`:276-296`), which inspects the *contract's* two signature
   timestamps — not the collaboration. It now passes. **Escrow can be funded for the first time on
   a cancelled deal.**
4. Deliverables can be submitted and approved; approval fires `tryReleaseOnApproval` and real money
   leaves the clearing wallet for the creator's wallet.

None of that requires a race or a crafted request. It is the ordinary happy path, run after a
cancellation that the rest of the system does not observe.

### 2.2 Per-status financial and contractual state

`CONTRACT_PENDING` is the correct cut line: it is the first status at which a durable artifact
exists that cancellation would have to reconcile.

| Status permitted by `canReject()` | Contract row | Escrow | Deliverables | Consequence of `→ CANCELLED` today |
|---|---|---|---|---|
| `INVITED`, `APPLIED`, `SHORTLISTED`, `IN_NEGOTIATION` | none | none | none | **Correct and complete.** This is the only case `reject()` actually models. |
| `TERMS_AGREED` | none yet (this is `generate`'s legal predecessor, `CollaborationLifecycleService:56-62`) | cannot be funded — `assertContractActiveForMilestone` requires both signatures | none | Benign *for money*. But `generate` has no status gate, so a contract can still be created afterwards on the cancelled deal. |
| `CONTRACT_PENDING` | exists, DRAFT or half-signed | not fundable yet | none | Orphaned contract row, still signable to ACTIVE. Cancellation is silent to both parties' contract view. |
| `CONTRACTED` | **ACTIVE, both signatures** | fundable; often `FUNDED` | none yet | The §10.1 headline case. Legally executed agreement voided by a single unauthenticated-by-role POST; contract row untouched; hold untouched and brand-refundable. |
| `IN_PROGRESS` | ACTIVE | `FUNDED` (this status is *reached by* `onEscrowFunded`, `:140-148`) | creator working | Same, plus the creator is actively producing against a deal that no longer exists to the system. |
| `REVIEW_PENDING` / `REVISION_REQUESTED` | ACTIVE | `FUNDED` | **submitted, unapproved** | Worst case. Work delivered, money held, release condition not yet satisfiable — and after cancellation the brand can refund the hold in full. Creator's only lever is `openDispute`, which is undiscoverable (a `CANCELLED` deal renders as "rejected" in the brand room, `deal-room-dashboard.tsx:88-90`) and, per §3.2, does not settle. |

### 2.3 Where the money physically is

Worth stating because it constrains every proposal below. `EscrowHold` is a **claim record**, not a
custody account. `LedgerEscrowBackend` (the only `EscrowBackend` implementation, and unconditionally
registered — see its class javadoc) moves rupees between internal `Wallet` rows via
`WalletLedgerService.post`: brand wallet → platform clearing wallet on fund, clearing → creator on
release, clearing → brand on refund. **Razorpay is not in the loop for any of these three
movements** since the 2026-07-26 double-charge fix (`EscrowService.java:214-223` — funding now debits
the already-topped-up wallet directly, no gateway order). Every disposition proposed in §4 is
therefore a ledger posting between internal wallets, expressible with primitives that exist today.
Nothing in this proposal waits on Razorpay Route, and Route is correctly **not assumed** anywhere in it.

---

## 3. Adjacent holes

### 3.1 `reject()` is the only deal mutation with no concurrency arbiter — and the races are live

`accept()` and `counter()` both route through `IdempotencyService.executeOnce` with a scoped key
(`DealService:250-262`, `:333-347`). `ContractService.generate` takes a `PESSIMISTIC_WRITE` lock on
the collaboration row before its check-then-write (`:182-189`, with an explicit Kabir MEDIUM-1
comment explaining exactly this class of race). `ContractService.recordSignature` uses
`executeOnce` keyed per contract per role (`:533-536`).

`reject()` does **none** of these. It reads the collaboration through a plain non-locking
`findByIdAndWorkspaceId` / `findByIdAndCreatorId`, and `Collaboration` has **no `@Version` column**
(entity read in full — there is none). Consequences:

- **reject ↔ contract signature.** T1 (`reject`) and T2 (`doRecordSignature` → `onContractFullySigned`)
  both read `status = CONTRACT_PENDING` before either commits. T1 writes `CANCELLED`; T2's
  `advance()` sees a non-frozen status from its own snapshot and writes `CONTRACTED`. Last committed
  write wins, nondeterministically. Outcome is either a `CANCELLED` deal with an ACTIVE contract, or
  a `CONTRACTED` deal whose timeline carries "Brand rejected: …" and a proposal card settled
  `rejected`. `generate`'s row lock does not help — `reject()` never asks for it.
- **reject ↔ escrow funding.** `initiateFund` → `applyFunding` → `notifyEscrowFunded` →
  `onEscrowFunded`. The money movement itself never reads `Collaboration.status`, so it completes
  regardless; only the status nudge is frozen. A reject concurrent with (or preceding) a fund
  produces a `CANCELLED` collaboration with money that just arrived in the clearing wallet.
  Deterministic, not a race.

`reject()` also takes no `Idempotency-Key` (contrast `DealController:90` for accept, `:106` for
counter). A retried reject after success returns 409 `DEAL_NOT_REJECTABLE` rather than a replayed
200 — harmless today, since the guard short-circuits before both side effects, but it is a UX wart
and it is the same missing-arbiter root cause as the races above.

### 3.2 CRITICAL, and independent of CR-22 — dispute settlement cannot reach normally-funded holds

This one is reachable **today**, with no `reject()` involved, and it invalidates "route withdrawal
to DISPUTED" as an answer.

`EscrowService.initiateFund` builds the hold **without a `collaborationId`**
(`:201-211` — `id`, `workspaceId`, `campaignId`, `milestoneId`, `amount`, `currency`, `status`,
`idempotencyKey`, and nothing else). The column is nullable by design
(`resources/db/migration/V9__escrow_holds.sql:4` — "null for campaign-level pool"). The only caller
of `EscrowHold.bindCollaboration` in the entire codebase is
`service/meera/tool/ConfirmLaunchExecutor.java:501` — i.e. Meera-launched campaigns bind it,
`POST /wallet/escrow/fund` does not.

Now compare the two lookup helpers:

- `findFundedHoldsForCollaboration` (`:1020-1045`) queries by `collaborationId` **and falls back to
  the milestone → collaboration link.** So `freezeUnreleasedForDispute` (`:646-668`) and
  `hasFundedUnreleasedEscrow` (`:674-677`) both find the hold. The dispute opens, the hold is
  `FROZEN`.
- `requireFrozenHoldsForCollaboration` (`:978-986`) queries
  `findByCollaborationIdAndStatus(collaborationId, FROZEN)` **with no milestone fallback.** For a
  hold whose `collaboration_id` is NULL it returns an empty list.

`adminReleaseForDispute` (`:685-718`), `adminRefundForDispute` (`:724+`) and `adminSplitForDispute`
(`:771+`) all iterate that empty list. `DisputeService.resolveDispute` (`:195-330`) then marks the
dispute `RESOLVED_*` and writes an `admin_audit_log` entry asserting the escrow was settled — for
zero holds. The hold stays `FROZEN` forever, and `FROZEN` is releasable/refundable by no other path
(`requireStatus(hold, FUNDED, …)` at `:509` and `:569`).

**That is a genuine, permanent strand of real money, live on `main` today.** It also means any
CR-22a design that routes post-contract withdrawal into `DISPUTED` would be routing into a
settlement path that silently does nothing. It needs its own ticket ahead of CR-22b.

### 3.3 Privilege inversion on `reject()`

`reject()` requires only `requireBrandWorkspace` (`BrandContextService:43-65`), which resolves a
workspace and performs **no `MemberRole` check**. Every adjacent money/contract verb does:

| Verb | Role required |
|---|---|
| `ContractService.generate` | `OWNER` / `ADMIN` / `MANAGER` (`:137`) |
| `ContractService.recordSignature` on behalf of CREATOR | `OWNER` / `ADMIN` / `MANAGER` (`:520`) |
| `EscrowService.initiateFund` | `OWNER` / `ADMIN` (`:152-153`) |
| `EscrowService.release` | `OWNER` / `ADMIN` (`:414`) |
| `EscrowService.refund` | `OWNER` / `ADMIN` (`:552`) |
| **`DealService.reject`** | **any active member, including `VIEWER`** |

So the lowest-privileged workspace member holds the single highest-impact lifecycle verb: a `VIEWER`
can cancel a contracted, escrow-funded deal but cannot fund it, release it, refund it, or generate
its contract. This is exactly the shape of the role-gate class of bug recorded in
`project_brand_role_gates` and fixed for campaign-delete in `9767463`; `reject` was not covered.

Note also the asymmetry between the parties: `reject()` is symmetric, but the **remedies are not**.
Only the brand can refund or release. A creator who withdraws from a `CONTRACTED` deal leaves the
money entirely under brand control with no creator-side lever at all except `openDispute`.

### 3.4 `ContractStatus.CANCELLED` is dead code

Declared in `domain/enums/ContractStatus.java`, read exactly once as a query filter
(`ContractService.java:202-203`, "a CANCELLED contract does not block a fresh one"), and **written
by nothing**. There is no `voidContract` / `cancelContract` primitive anywhere. Whatever CR-22b
designs, the contract-voiding leg does not exist yet and will have to be built.

---

## 4. Proposed state model — recommendation

**Recommendation: split the verb. Narrow `canReject()` to pre-contract, and build post-contract
withdrawal as a separate, two-party, escrow-aware termination. Do not widen `reject()`, and do not
route it to `DISPUTED`.**

### 4.1 The blocking change (CR-22a proper — one method)

Narrow `canReject()` to an allowlist over the pre-contract negotiation states:

```
INVITED, APPLIED, SHORTLISTED, IN_NEGOTIATION, TERMS_AGREED
```

Everything at `CONTRACT_PENDING` or beyond returns the existing 409 `DEAL_NOT_REJECTABLE`. This
restores the invariant the rest of the codebase already silently assumes — **`CANCELLED` means
nothing was ever executed** — which is precisely why no contract-voiding, escrow-reconciling or
deliverable-teardown logic exists anywhere to be called.

Why `TERMS_AGREED` stays in: no `Contract` row exists yet (it is `generate`'s legal predecessor,
`CollaborationLifecycleService:56-62`) and escrow cannot be funded (`assertContractActiveForMilestone`
requires both signatures). There is genuinely nothing to reconcile. `CONTRACT_PENDING` is the first
status with a durable artifact, so that is the cut.

Note this also converts a denylist into an allowlist over a growing enum — a future 14th status is
then non-rejectable by default, which is the correct default for a terminal verb.

### 4.2 The enforcement change (must ship with 4.1, or 4.1 is cosmetic)

Narrowing `canReject()` closes the one route *in*. It does not make `CANCELLED` mean anything.
The same guard must be added at the four places that currently ignore collaboration status
(§2.1): `ContractService.generate`, `ContractService.doRecordSignature`,
`EscrowService.initiateFund`, and the deliverable submit/approve paths. Without them, the state is
still decorative — it is just harder to reach.

### 4.3 The post-contract model (this is CR-22b's real surface)

Post-contract withdrawal gets its **own endpoint, its own status, and a counterparty**:

`POST /deals/{id}/termination` opens a *proposed* termination carrying the proposer's escrow
disposition — refund all / release all / split at N%.

- **Counterparty accepts** → in one transaction: the compensating escrow movement runs (the
  existing `refund` / `release` / split primitives, which already exist), the `Contract` moves to
  `ContractStatus.CANCELLED` (the enum value that exists and has never been written — §3.4),
  outstanding deliverables are closed out, and the collaboration lands in a **new terminal
  `TERMINATED`**, distinct from both `CANCELLED` and `COMPLETED`.
- **Counterparty declines, or it lapses** → it escalates to `DISPUTED` through the existing
  `openDispute` path, with the existing freeze, and an admin adjudicates.

The essential property: **a post-contract withdrawal is a dialogue, not a button.** Money that both
parties have committed to cannot be redirected by one party's POST, in either direction.

### 4.4 Why not the two alternatives

**Why not route post-contract reject to `DISPUTED`.** Three reasons, in ascending severity.
(i) `DISPUTED` means "an admin must adjudicate", so every ordinary post-signature change of mind
becomes a support ticket — it does not scale. (ii) It hands either party a unilateral freeze on the
counterparty's money merely by asserting a withdrawal, which is a new abuse primitive, not a fix.
(iii) Decisively: per §3.2 the dispute settlement path **cannot currently settle** the holds this
would produce. Routing withdrawal into DISPUTED today routes it into a black hole.

**Why not keep `CANCELLED` and bolt a compensating escrow release onto `reject()`.** It is the
cheapest patch and it is the wrong shape. `CANCELLED` already means "never executed" everywhere
else in the model — reusing it for "executed, then unwound" is exactly the overload that produced
this bug, and it would leave `CANCELLED` deals in two incompatible financial states that no
downstream reader can distinguish. It also still leaves one party choosing the disposition
unilaterally: whatever default you pick (refund-all or release-all) systematically robs one side.

**The legitimate cheap interim,** if CR-22b cannot be scheduled: ship §4.1 + §4.2 and ship *nothing*
post-contract, directing both parties to "Open dispute". That is strictly safer than today and is
one method plus four guards. Its cost is that every post-contract withdrawal becomes an admin
ticket — and it **cannot ship until §3.2 is fixed**, or those tickets will resolve without moving
the money.

### 4.5 Razorpay Route is not a dependency

Stated explicitly because the repo's prior finding
(`project_razorpay_route_zero_rework_false`) is directly relevant. Escrow here is a **pooled
platform clearing wallet**, not per-payment transfers (§2.3). Every disposition above — refund,
release, split — is already a `WalletLedgerService.post` between internal wallets, and all three
primitives exist and are tested. **Nothing in this proposal is gated on Route, creator KYC-linked
accounts, or per-payment transfers.** If Route later replaces `LedgerEscrowBackend`, the state model
above is unchanged; only the `EscrowBackend` implementation swaps, which is what that interface is
for.

---

## 5. Severity ranking

### Blocks CR-22b's design

| # | Sev | Finding | Evidence |
|---|---|---|---|
| **1** | **CRITICAL** | **`CANCELLED` is not enforced downstream.** Contract generation, contract signing, first-time escrow funding, deliverable submission and approval (which releases money) all proceed on a `CANCELLED` collaboration. Any withdrawal design is meaningless until the state is enforced. **This is the actual defect; §10.1's finding is a symptom of it.** | `ContractService.java:134-208`, `:505-615` (no status gate); `EscrowService.java:276-296` (gates on contract signatures, not collaboration); zero `CollaborationStatus` refs in the deliverable/shipment services; `CollaborationLifecycleService.java:52-53` is the only honouring of `CANCELLED` and it only freezes the label |
| **2** | **CRITICAL** | **Dispute settlement cannot reach holds funded through `POST /wallet/escrow/fund`.** They freeze but never settle; the dispute is marked resolved and audit-logged as settled anyway. Permanent strand of real money, **live today, no `reject()` required.** Blocks any design routing withdrawal to `DISPUTED`. Needs its own ticket, ahead of CR-22b. | `EscrowService.java:201-211` (hold built with no `collaborationId`), `:978-986` (frozen lookup, no milestone fallback) vs `:1020-1045` (funded lookup, has it); only `ConfirmLaunchExecutor.java:501` ever binds it; `DisputeService.java:239-259`, `:290-327` |
| **3** | **HIGH** | **`canReject()` admits 6 post-contract statuses** — the reported finding. Real, currently unreachable from the UI, becomes exploitable the instant CR-22b ships a control. Fix is §4.1, one method. | `Collaboration.java:196-200`; `DealService.java:274-279` |
| **4** | **HIGH** | **No compensating escrow or contract action on the `CANCELLED` transition.** After cancellation the brand retains an ungated unilateral 100% refund against a creator who may already have delivered and been approved. This is the money consequence, and it is a design gap requiring a product decision — not a coding slip. | `DealService.java:280-303`; `EscrowService.java:549-596` (refund: no collaboration-status gate), `:477-546` (release: same) |

### Does not block CR-22b — fix separately

| # | Sev | Finding | Evidence |
|---|---|---|---|
| **5** | **MEDIUM** | **Privilege inversion:** any active brand workspace member including `VIEWER` can cancel a contracted, funded deal; funding, release, refund and contract generation all require `OWNER`/`ADMIN`(/`MANAGER`). Same class as the campaign-delete gate fixed in `9767463`; `reject` was not covered. | `BrandContextService.java:43-65` (no role check) vs `EscrowService.java:152-153`, `:414`, `:552`; `ContractService.java:137`, `:520` |
| **6** | **MEDIUM** | **`reject()` has no idempotency arbiter and takes no row lock**, unlike every sibling mutation. `Collaboration` has no `@Version`. reject ↔ contract-sign is a last-writer-wins lost update; reject ↔ escrow-fund lets money land on an already-cancelled deal deterministically. §4.1 narrows the window substantially but does not close it. | `DealService.java:270-281` vs `:250-262`, `:333-347`; `ContractService.java:182-189`, `:533-536`; `Collaboration.java` (no `@Version`) |
| **7** | **LOW** | **`ContractStatus.CANCELLED` is dead** — declared, read once as a filter, never written. No contract-voiding primitive exists for any terminal path to call; CR-22b will have to build one. | `domain/enums/ContractStatus.java`; sole reference `ContractService.java:202-203` |
| **8** | **LOW** | **`reject()` returns 409 on retry** instead of an idempotent 200, unlike accept/counter. UX only. | `DealController.java:94-100` (no `Idempotency-Key`); `DealService.java:274-279` |

### Explicitly checked and clean

- **No other endpoint transitions to `CANCELLED`.** Only `DealService:280`. There is exactly one
  entry point to fix.
- **No UI reaches `reject()` post-contract on this branch.** All four call sites verified (§1.1).
  §10.1's "nothing reaches it today" is correct.
- The `[C1]` comment at `DealService.java:288-292` is honest about what it does and does not
  address — it is accurate, not misleading. It correctly scopes itself to proposal-card metadata
  and says so.

---

## 6. Suggested sequencing

1. **Fix §5 #2 first.** It is live, it is money, and it is independent of CR-22 entirely.
2. **Then CR-22a = §4.1 + §4.2** (narrow `canReject()`, add the four downstream guards). Small,
   and it makes `CANCELLED` mean something for the first time.
3. **Then §5 #5 and #6** — role gate on `reject()`, and route it through `IdempotencyService`
   with `findByIdForUpdate` like its siblings.
4. **Only then unblock CR-22b** against the §4.3 model.

Steps 1–3 are backend-only and need no design input. CR-22b needs a product decision on the
default escrow disposition offered in a termination proposal and on the lapse timeout — that is
the one genuine product call in this document.

---

*Analysis only. No code was modified. No git operations were performed.*
