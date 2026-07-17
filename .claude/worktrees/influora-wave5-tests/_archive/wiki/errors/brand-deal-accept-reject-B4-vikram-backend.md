# B-4 — Brand-initiated deal accept/reject (backend) — Vikram

**Status: code complete, working tree only, NOT verified by build (see blocker below). Do not mark `[x]` in `BRAND_ADMIN_PENDING_WORK.md` until Meera confirms `mvn test` actually compiles and passes — see "Blocking discovery."**

## What changed

### `influora-api/src/main/java/com/influora/service/DealService.java`
- `accept(principal, dealId, idempotencyKey)` — was hard-gated `creatorContext.requireCreator(principal)`. Now role-aware: `UserType role = requireRole(principal)`, collaboration resolved via the existing dual-role `requireOwnedCollaboration` (same helper `counter()` uses), idempotency scope is `principal.getUserId()` for creators / `brandContext.requireBrandWorkspace(principal).getId()` for brands — identical pattern to `counter()`. Cross-workspace/foreign-deal lookups uniformly throw `DEAL_NOT_FOUND` (404), same as `get`/`counter`/`listMessages`.
- `reject(principal, dealId, body)` — same hard-gate removed, same `requireRole` + `requireOwnedCollaboration` treatment. (Reject was never idempotency-wrapped before my change and I left that as-is — only added role-awareness — noting as a pre-existing minor gap below, not something I introduced.)
- `doAccept(collaboration, principal, role)` — signature now takes `role`. Fixed: response used to be hardcoded `toDealResponse(collaboration, principal, UserType.CREATOR)` regardless of who actually accepted, so a brand-side accept would come back with creator-perspective `counterparty`/response shape. Now returns `toDealResponse(collaboration, principal, role)`, and the system-message text says "Creator accepted…" / "Brand accepted…" correctly.
- **New guard** in `doAccept`: a party cannot accept the offer they themselves last put on the table. Looks up the most recent `DealMessage` of `kind=proposal` for the collaboration (`DealMessageRepository.findFirstByCollaborationIdAndKindOrderByCreatedAtDesc`, added below) and compares its `senderType` to the acting role; if they match, throws `ApiException("CANNOT_ACCEPT_OWN_OFFER", ..., 409 CONFLICT)`. If no proposal-kind message exists yet (e.g. a bare `invite()` with no `DealMessage` — see `invitedDeal()` fixture), the guard is skipped (nothing to compare against) and normal `canAccept()` gating still applies.

### `influora-api/src/main/java/com/influora/repository/DealMessageRepository.java`
- Added `Optional<DealMessage> findFirstByCollaborationIdAndKindOrderByCreatedAtDesc(String collaborationId, DealMessageKind kind)` — new Spring Data derived query, needed for the own-offer guard above. No entity changes.

### `influora-api/src/test/java/com/influora/service/DealServiceTest.java`
Added a new "B-4 Brand-initiated deal accept/reject" section (6 tests), mirroring the existing `testBrandCounterUsesWorkspaceScope` / `testAcceptHappyPath` style:
1. `testBrandAcceptHappyPath` — brand accepts when the creator made the last offer; asserts `TERMS_AGREED` + correct save/message calls.
2. `testBrandReject` — brand rejects own-workspace deal; asserts `OkResponse.ok()==true`, `CANCELLED` status, system message persisted.
3. `testBrandAcceptRejectsForeignWorkspace` — cross-workspace accept → `DEAL_NOT_FOUND` 404, idempotency never invoked.
4. `testBrandRejectRejectsForeignWorkspace` — same for reject, `collaborationRepository.save` never called.
5. `testCannotAcceptOwnLastOffer` — brand made the last offer itself, brand tries to accept → `CANNOT_ACCEPT_OWN_OFFER` 409, `collaborationRepository.save` never called.
6. `testBrandAcceptIdempotencyScopedToWorkspace` — asserts a client-supplied `Idempotency-Key` is honored verbatim and dedupe scope is the workspace id, same contract as `counter()`.

Existing tests (`testAcceptRejectsForeignDeal`, `testAcceptHappyPath`) were left untouched and should still pass: Mockito's default answer returns `Optional.empty()` for the new unstubbed repository method, so the own-offer guard is a no-op in those fixtures (no proposal message stubbed) and behavior is unchanged for the creator happy path.

### `src/lib/api.ts`
- `deals.accept(id, role: Role = 'creator')` — added optional `role` param (was hardcoded `'creator'`). Default preserves existing behavior for the one FE call site (`src/pages/creator-deals.tsx:276,283`, both call with just `id`).
- `deals.reject(id, reason?, role: Role = 'creator')` — same change; existing call site `src/pages/creator-deals.tsx:303` calls with just `id`, unaffected.
- `role` here selects which stored JWT (`TOKEN_KEYS.brand` vs `TOKEN_KEYS.creator`) is sent as the `Authorization` header (see `HttpClient.getToken`/`headers`) — it is **not** trusted server-side for authorization; the backend derives `AuthPrincipal.userType` from the JWT itself. So `role: 'brand'` just makes sure the brand's own token goes out; `DealService.requireRole`/`requireOwnedCollaboration` still do the real authorization.

## Contract for Ananya (next cycle — not wired by me)

```ts
api.deals.accept(dealId, 'brand')   // brand accepts creator's current offer
api.deals.reject(dealId, reason, 'brand')  // brand rejects/withdraws
```

Both now behave like `api.deals.counter(id, payload, 'brand')` already does. Expect:
- `200` with a brand-perspective `Deal` (accept) / `{ ok: true }` (reject) on success.
- `404 DEAL_NOT_FOUND` if the deal isn't in the brand's workspace (uniform with every other deal endpoint — don't leak existence).
- `409 DEAL_NOT_ACCEPTABLE` / `DEAL_NOT_REJECTABLE` if collaboration state doesn't allow it.
- **New: `409 CANNOT_ACCEPT_OWN_OFFER`** if the brand itself made the last offer (waiting on the creator). Surface this distinctly in the UI — it's not a generic error, it's "you already made your move, wait for the other side" — the Accept button should probably be disabled/hidden in this state if the deal-room UI can tell who sent the last proposal-kind message (`lastMessage`/message list already exposes `senderType`).

## Product-assumption flag (Priya/CTO to confirm or override)

The "can't accept your own last offer" rule is my interpretation of a sane default, not something specified in the tracker beyond "decide whether a guard is missing." Specifically:
- I keyed it off the most recent `DealMessage` of `kind=proposal` (covers both the initial `createProposal` and every `counter()`), not off `Collaboration` state, since either role can be the last mover.
- If there's no proposal-kind message at all yet (e.g. a plain `invite()` with only free-text `notes`), the guard doesn't block — I treated that as "nothing formal on the table to self-accept."
- This does **not** apply to `reject()` — rejecting/withdrawing your own last offer is a legitimate action (equivalent to canceling it), so I left `reject()` un-guarded. Flagging this asymmetry explicitly in case Priya wants it symmetric.

## Blocking discovery — please read before running anything

While reading `Collaboration.java` to confirm `counter()`'s pattern, I found that **`influora-api/src/main/java/com/influora/domain/entity/Collaboration.java` on disk is the bare 88-line "Initial commit" stub** (only `id`, `campaignId`, `creatorId`, `status`, `source`, `agreedRate`, `currency`, `notes`, timestamps, `invite()`, and 5 getters). It has **no** `canAccept()`, `canReject()`, `canCounter()`, `transitionTo()`, `updateAgreedRate()`, `propose()`, or getters for `agreedRate`/`currency`/`notes`/`updatedAt`/`source` — all of which `DealService.java`'s **existing, already-shipped** `counter()`/`doCounter()`/`accept()`/`reject()` code calls (this predates my change; I confirmed with `git diff`/`git log` that this file is untouched and matches the initial commit exactly). Same gap in `CollaborationRepository.java`: `findByIdAndWorkspaceId`, `findByIdAndCreatorId`, `findByCreatorId`, `findByWorkspaceId` are all referenced by `DealService`/`DealServiceTest` but not declared on the interface (only `existsByCampaignIdAndCreatorId` exists).

This means **`influora-api` almost certainly does not compile right now**, not just for my new code but for the whole Deal Room feature this tracker already marked `[x]`/shipped. This is not something I introduced — I only called the same already-referenced methods, mirroring `counter()` exactly as instructed. This lines up with the already-tracked, unresolved backlog item in `wiki/tech/BRAND_ADMIN_PENDING_WORK.md`:

> **Dangling git stash needs a deliberate decision** — `stash@{0}` contains ~+3174 lines touching `src/lib/api.ts` and several deal-room/contract-panel files, never popped... Owner: Priya/Arjun to inspect and decide pop vs. drop.

I did **not** touch the stash (per explicit instruction — read-only git only: `git status`/`diff`/`log`), and did not attempt to reconstruct `Collaboration.java`/`CollaborationRepository.java` myself — that's a much larger, cross-cutting surface (likely affects `ContractService`, `DeliverableService`, etc. too) than this ticket's scope, and reconstructing entity business logic blind risks conflicting with whatever the real implementation in the stash actually says. This needs the Priya/Arjun stash decision first.

Separately: **`mvn` is not installed in my sandbox**, so I could not compile or run `DealServiceTest` myself either way — Meera's build step is the real gate here and should be expected to fail (or reveal the entity gap) until the stash question is resolved.

**Live concurrent-write incident during this session (additional evidence for the stash item, please read):** mid-task, `influora-api/src/main/java/com/influora/repository/DealMessageRepository.java` — a file I had just read (full content, ~25 lines: `findByCollaborationIdOrderByCreatedAtAsc`, `findPageBefore`, `findFirstByCollaborationIdOrderByCreatedAtDesc`) and edited — transiently disappeared from the working tree entirely (`Read`/`Glob` both reported it absent). `git log`/`git reflog` (read-only checks only — no destructive commands run by me) showed: (a) this file **has never existed in any commit in this repo's history** — it was always working-tree-only, likely part of the same dangling-stash-adjacent uncommitted layer as `Collaboration.java`'s missing methods; (b) the reflog shows five `reset: moving to HEAD` events landed **during this session**, evidently from a concurrent agent process, not me. On re-check moments later the file was back with my edit intact (`git status` now shows it `??` untracked, never committed) and the other three files' diffs were untouched throughout. No data was actually lost this time, but this is a live demonstration of exactly the class of problem the tracker's "Dangling git stash" item already flags — multiple agents apparently running git operations (including at least `reset`, evidenced by reflog) against the same shared working tree while others have in-flight edits. Recommend Arjun/Priya treat this as reinforcing evidence to resolve the stash item soon, and consider whether concurrent agents need isolated worktrees rather than a shared one for anything touching `influora-api`.

## Files touched
- `influora-api/src/main/java/com/influora/service/DealService.java`
- `influora-api/src/main/java/com/influora/repository/DealMessageRepository.java` (untracked — never committed in repo history, see incident note above)
- `influora-api/src/test/java/com/influora/service/DealServiceTest.java`
- `src/lib/api.ts`

## Gaps / follow-ups
- Entity/repository compile gap above — blocks everything, needs Priya/Arjun on the stash.
- `reject()` still isn't idempotency-wrapped (pre-existing, not new in this change) — flagging in case Kabir wants it symmetric with `accept()`/`counter()`.
- No frontend wiring — per instructions, Ananya wires brand-side buttons next cycle using the contract above.
- Kabir review requested per orchestrator instructions (money-adjacent, same class as `counter()`).
