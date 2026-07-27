# Direct-offer wiring — deferred items

**Decision date:** 2026-07-26
**Approved by:** Swapnil (CEO) · Analysis: Priya (CTO)
**Shipped in this pass:** `POST /deals` wired to the discovery proposal modal; moderation gate closed; `exclusivity` removed.

---

## Context

`POST /deals` (`DealController.create` → `DealService.createProposal`) had been live and tested for months with **zero UI callers**. Wiring it surfaced three issues. Two were fixed; the rest are logged here.

Key architectural fact, since the audit got it wrong: **`POST /deals` is not a campaign-free direct offer.** `campaignId` is `@NotBlank`. It and `POST /creators/{id}/invite` are the same door at two fidelities, writing the same `Collaboration` row keyed on `(campaignId, creatorId)` — they 409 `COLLABORATION_EXISTS` against each other. Exactly one fires per submit.

| | invite | create |
|---|---|---|
| status | `INVITED` | `IN_NEGOTIATION` |
| agreedRate | — | `amount` |
| proposal message | no | yes |
| notification | none | `ProposalSentEvent` |

---

## Deferred — do NOT build before Route/escrow

### 1. Offer expiry + budget reservation
`Collaboration.propose` sets `IN_NEGOTIATION` with no expiry on the offer. Today this is safe: manual escrow means ops is in the loop on every release, so an open offer can't move money on its own.

**Trigger to build:** the moment `ESCROW_ENABLED=true`. Once money moves automatically, an open-ended priced offer against a campaign budget is a live liability — two stale offers plus a fresh one can commit more than the budget. Expiry and budget reservation belong to the Route build, not before.

> CEO: *"When we flip ESCROW_ENABLED=true and money moves automatically, then we need offer expiry + budget reservation logic. That's part of the Route build, not this fix."*

### 2. Exclusivity as a real contract clause
Removed from `CreateDealRequest`, from `api.ts`'s `deals.create`, and from the discovery proposal UI. It had exactly one occurrence in the entire backend — the DTO field — and was never persisted or read. The UI control was live and appeared in the review summary, so a brand could tick "no competitor collabs", believe it bought exclusivity, and have no enforcement behind it.

**Returns as:** a contract clause with an enforcement date range and breach notification — a column, a migration, and contract-milestone automation. Not a checkbox. Target: post-Route, post-escrow.

### 3. Revision cap
Same removal, same reason: a live `Select` (1/2/3/5 revisions) bound to state the API never accepted. Deliverables already carry `currentRevision`/`maxRevisions` server-side (`CreatorDeliverableListItem`), so when this returns it should bind to **that** existing model, not a new proposal field.

### 4. `Idempotency-Key` on `POST /deals`
`accept` and `counter` take one; `create` does not. The `(campaignId, creatorId)` unique constraint already prevents a duplicate row, so a double-submit is a 409 — **not corruption**. Explicit CEO call not to gold-plate before launch.

**Trigger to build:** real user reports of confusing "already exists" errors on first click.

---

## Fixed in this pass

- **Moderation bypass (security).** `createProposal` used a bare `creatorProfileRepository.findById`, so a priced offer could reach a creator who had turned discoverability off or been **suspended by moderation** — `invite` blocked both. Now routed through `DealService.requireOfferableProfile`, mirroring `CreatorDiscoveryService.requireDiscoverableProfile`. Covered by two tests in `DealServiceTest`.
- **Terms were collected and discarded.** The 3-step discovery modal already gathered deliverables, budget, deadline and usage rights, then called `creators.invite` and threw them away. Now: `budget > 0` → `deals.create`; otherwise → `creators.invite`.

---

## Deal-room proposal form — FIXED (second pass, same day)

`brand-chat.tsx handleSendProposal` was a stub: `await new Promise(r => setTimeout(r, 1500))` and a comment reading *"In real app: add proposal to mockEvents or call API"*. The brand filled in five steps and the modal closed having sent nothing.

Now wired to **`POST /deals/:id/counter`** — not `create`. By the time a deal room exists the Collaboration exists, so `create` would 409. Same endpoint the counter modal on `brand-campaign-detail` uses, with a fresh `Idempotency-Key` per submit.

Fixed alongside it, all in the same form:

- **Fabricated platform fee.** The cost breakdown hardcoded *"Platform Fee (10%)"* while the platform default is **15%** (`application.yml PLATFORM_FEE_PERCENT`). A brand budgeting off that number under-quoted its own cost by a third. `GET /brand/platform-fee` already existed with **no client** — added `api.wallet.brandPlatformFee()` and the form now shows the real workspace rate.
- **Fabricated add-on pricing.** Usage-rights add-ons advertised `+30%`/`+50%` uplifts and inflated "Total You Pay", but the amount sent to the server was the base budget alone — nothing ever charged them. Price labels and the add-on fee line removed; the add-ons survive as descriptive terms in the proposal message.
- **`exclusivity` + `revisionCap` removed** here too, matching the discovery modal, plus their read-back tiles in `proposal-card.tsx`.
- **Ungated trigger.** "Send Proposal" rendered on every deal, including `COMPLETED`. Now gated on `Collaboration.canCounter()`'s states (`INVITED`/`APPLIED`/`SHORTLISTED`/`IN_NEGOTIATION`), so it can't open a five-step form whose only possible outcome is a 409.

Covered by 3 tests in `src/pages/brand-chat-proposal.test.tsx`.

---

## CounterRequest ↔ CreateDealRequest alignment — DONE (third pass, same day)

The asymmetry flagged above is closed. `CounterRequest` was `(amount, message, deliverables)`; it is now `(amount, message, deliverables, deadline, usageRights)`, matching `CreateDealRequest`.

**Why it mattered.** Every frontend that let a user revise a deadline or usage rights concatenated them into `message` as prose — `creator-chat.tsx` and `brand-chat.tsx` had the *same* workaround, each with a comment explaining the DTO couldn't carry the fields. Terms a party actually negotiated were therefore unreadable to the server and unusable by the contract generator, which is the thing that eventually has to turn agreed terms into a signed document.

Changes:

| Layer | Before | After |
|---|---|---|
| `CounterRequest` | amount, message, deliverables | + `deadline`, `usageRights` |
| `doCounter` | `persistProposalMessage(..., null)` — deadline hardcoded away | passes `body.deadline()` |
| `doCounter` | never touched usage rights | persists via `setUsageRights` + `TextSanitizer`, exactly as `createProposal` does |
| `api.ts deals.counter` | 3-field payload | + `deadline`, `usageRights` |
| `brand-chat` | deadline + usage rights in message prose | real fields |
| `creator-chat` | deadline in message prose | real field |

Blank `usageRights` is treated as "not renegotiating this term", so a price-only counter keeps the previously agreed rights rather than clearing them.

**Bonus fix in the same path — deliverables were being persisted as a bare count.** `persistProposalMessage` did `metadata.put("deliverables", deliverables.size())`, so a proposal for "2 Reels + 1 Story" persisted as the integer `3` and the deal room could never render what was offered. Types and quantities were dropped at the persistence layer on **both** endpoints. Now stores the slots themselves (plus `deliverableCount` for convenience). Verified safe: no reader of that key exists in the API or the SPA.

**Deliberately NOT mapped:** creator-chat's `terms` field stays in the message. Its form asks "Any Changes to Terms?" as free text, so it is not the usage-rights term — mapping it onto `usageRights` would overwrite the deal's actual rights with a sentence like "can we do 3 reels instead".

Still with no home server-side, and still travelling in the message: usage-rights **add-ons** and **custom clauses** (brand side). Those need real columns before they can be modelled.

Backend coverage: `DealServiceTest.testCounterPersistsAlignedTerms` asserts the usage-rights column update, the deadline in metadata, and the deliverable type surviving.

---

## Still open

**`brand-deal-counter-modal.tsx` has zero importers.** Another orphaned component in the same directory. Either it supersedes part of the proposal form or it's dead — needs a look before someone wires the wrong one.

---

## Verification status

- Frontend: `tsc --noEmit` clean, `vite build` clean, suite 218 passing (2 failures pre-existing and unrelated: `creator-disputes` partial-data banner, `trendspark/tagger-sync` drift check).
- Backend: **not compiled.** Maven was unavailable in the authoring environment. Java changes are small and follow existing patterns in their files, but CI is the first real check. The `DealServiceTest` happy-path case was deliberately omitted rather than shipped unrunnable — `toDealResponse` has a wide mock surface and a test that cannot be executed is worse than an absent one. Add it when the suite can run.
