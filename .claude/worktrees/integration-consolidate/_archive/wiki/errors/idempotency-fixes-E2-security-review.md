# Red-Team Security Review: E2 Idempotency Fixes (+ B3 section)

**Reviewer:** Kabir (Offensive Security / Red-Team Lead)
**Date:** 2026-07-07
**Scope:** Load-bearing adversarial review of the E2 idempotency fixes (money + public webhook surface); standard pass on B3 `InstagramMetricsFetcher`.
**Type:** review only — no code changed, Maven not run (Meera owns the build).

## VERDICTS

- **E2 idempotency fixes: REJECTED** — 2 HIGH (one is the fix *introducing* a money-availability regression; one is a public-surface revenue-suppression primitive the fix creates). Route back to Vikram.
- **B3 `InstagramMetricsFetcher`: SIGN-OFF** — 0 CRITICAL/HIGH/MEDIUM, 1 LOW advisory (inherited exception-message logging, same class as B2).

Kavya's functional re-review (`idempotency-fixes-E2-review.md`) is correct on the mechanics it checked (executeOnce wrapping, replay tests, breaking-change closure). These findings are the adversarial layer *underneath* those checks and do not contradict them.

---

## E2 FINDINGS BY SEVERITY

### HIGH-1 — Payout permanently wedged by any validation/gateway failure (denial-of-payout). NEW, introduced by this fix.
**Files:** `service/PayoutService.java:74-107` and `:138-166`; `service/IdempotencyService.java:54-73`, `:87-100`.

The double-*spend* axis is well-defended: the key `"payout:" + milestoneId` (PayoutService.java:77) is **server-derived, not caller-supplied**, and concurrency is arbitrated by the DB `UNIQUE(idempotency_key)` insert inside `executeOnce`, not by the `replayIfPresent` pre-check (which is only an optimization). Two concurrent submits → one wins the insert, the loser replays. TOCTOU-safe. Good.

The problem is the **availability axis this fix introduces.** Every domain validation in `doQueuePayout` runs *inside* the `executeOnce` supplier:
- milestone lookup (`:139-145`), `escrowHoldId == null` (`:147`), hold lookup (`:151-157`), `hold.getStatus() != RELEASED` (`:158`), `hold.workspaceId != workspaceId` (`:164`), then the RazorpayX call (`:181`).

All of these throw `ApiException` — which **extends `RuntimeException`** (`common/ApiException.java:5`). `executeOnce`'s `catch (RuntimeException ex) { markFailedTransactional(key); throw ex; }` (IdempotencyService.java:69-72) marks the key row **FAILED**. FAILED is terminal: `tryReserveTransactional` always fails on the existing row, and `executeOnce` treats any non-`COMPLETED` existing row as `AlreadyInProgressException` (`:59-62`). There is **no code path that clears or retries a FAILED key.**

Consequence — the deterministic key means there is no fresh key to retry with:
- **Self-inflicted, guaranteed reachable:** a brand calls `queuePayout` one step too early (milestone funded but not yet RELEASED) → `MILESTONE_NOT_RELEASED` → key `"payout:"+milestoneId` is now FAILED. After the milestone *is* released, the legitimate payout call → `replayIfPresent` returns null (milestone key never set) → `executeOnce` → `AlreadyInProgressException` → `replayIfPresent` null again → **409 forever.** The creator's real-money payout is permanently blocked with no in-app recovery (requires manual `DELETE FROM idempotency_keys`).
- **Transient gateway failure:** if `initiatePayout` reaches RazorpayX but the response is lost (timeout throws), the key is marked FAILED and `markPayoutQueued` never runs → same permanent wedge, and now our system also can't tell whether the money left.

This is worse than the CRITICAL it replaces on the availability axis: the pre-fix code had no local guard but could always retry; the fix makes a single failed attempt terminal. **Fix:** move all validation (member/milestone/hold/RELEASED/ownership) *before* `executeOnce` so a validation failure never reserves+fails the key; and/or make FAILED keys re-runnable (delete-on-fail or a status that `executeOnce` retries). Currently untested — no test exercises a failing `doQueuePayout` followed by a legitimate retry.

### HIGH-2 — Conversion revenue-suppression via idempotency pre-poisoning on a PUBLIC unauthenticated endpoint. NEW capability introduced by this fix.
**Files:** `service/tracking/ConversionTrackingService.java:157-204`; `web/ConversionWebhookController.java:136-142`; DTO `web/dto/tracking/WebhookDtos.java:78-79`.

`/webhooks/conversion` is **public and unauthenticated** (controller javadoc :25-33) with **no HMAC/signature verification** anywhere in the controller. The derived fallback key is `"conv:" + utmCampaignId + ":" + orderId` (`:201-204`), and both components are attacker-knowable:
- `utmCampaignId` is a ULID but is **literally embedded in the public tracking link a creator posts** — anyone who clicks the link has it.
- `orderId` is the brand's external order id — commonly sequential/enumerable (WooCommerce post IDs, many custom checkouts).

Pre-E2 the endpoint had *no* idempotency, so a real webhook always counted (double-counted on retry). This fix adds a **suppression primitive**: an attacker submits a conversion first for a *predicted* `(utmCampaignId, orderId)` — either keyless (deriving the key, with `orderAmount` = 0) or by supplying `idempotencyKey = "conv:<utm>:<order>"` explicitly — which marks that key COMPLETED. When the brand's **real** webhook later arrives with the same pair, `executeOnce` throws `AlreadyCompletedException` and the service treats it as a clean no-op (`:178-182`). The genuine conversion and its revenue are **never recorded.** Attribution feeds brand-facing analytics and can feed creator/payout decisions.

Critically, the `"conv:"` namespace does **not** prevent this. Kavya's re-review blessed the namespace as collision-safe because "no external caller would randomly choose that exact namespace" — but an *attacker* chooses it deliberately, and a caller-supplied key on a public endpoint can squat any derived key. The namespace only prevents *accidental* collision, not malicious squatting. **Fix:** verify webhook authenticity (per-brand HMAC signature) before trusting any conversion — the real root cause — and/or bind the derived key to a server-minted, non-attacker-predictable value rather than the client-visible `(utmCampaignId, orderId)` pair.

### MEDIUM-1 — No webhook authenticity on `/webhooks/conversion` (and `/redemption`). Systemic root cause of HIGH-2.
**File:** `web/ConversionWebhookController.java` (whole controller).

Neither webhook verifies an HMAC/shared-secret signature. Trust rests entirely on the unguessability of the coupon code / UTM ULID — but the UTM ULID is public (in the posted link), so `/webhooks/conversion` is fully forgeable: an unauthenticated attacker can inflate `conversionCount`/`revenueAttributed` with arbitrary `orderAmount` (unique `orderId` = new key each time) *and* now suppress real conversions (HIGH-2). Pre-existing gap, but E2's dedup upgrades it from "inflation only" to "inflation + suppression." Recommend a signature-verification decision before this endpoint carries any revenue/attribution weight. Rate limiting (`AuthRateLimitFilter`, `tracking` bucket) throttles brute force but does nothing against a single correctly-predicted poisoning request.

### MEDIUM-2 — `idempotency_keys` (V15) is a single flat namespace with no scope partitioning.
**Files:** `service/IdempotencyService.java:87-100`; `domain/entity/IdempotencyKeyRecord.java:24-45`.

`UNIQUE`/`@Id` is on `idempotency_key` alone; the `scope` argument (`"payout.queue"`, `"conversion.record"`, etc.) is **descriptive only, never part of uniqueness** (confirmed, and Kavya's re-review notes the same). Every money/tracking/AI feature shares one flat keyspace: payout keys, conversion derived+explicit keys, redemption keys, and Meera `turn_id` keys all live in one table. Cross-feature collision is prevented **only by prefix discipline**, and any caller-supplied key (conversion explicit key; redemption key) can collide with any other feature's key by construction. This is the structural weakness under HIGH-2 and the payout wedge. **Fix:** composite `UNIQUE(scope, idempotency_key)` (with `scope` server-set, never caller-influenced), or force a server-prefixed key for every public caller.

### LOW-1 — Unbounded caller inputs vs. the 128-char key column → spurious 409 / silent data loss.
**Files:** `web/dto/tracking/WebhookDtos.java:78-79` (no `@Valid` on controller, no `@Size` on record); `domain/entity/IdempotencyKeyRecord.java:34` (`length = 128`); `service/IdempotencyService.java:97-99`.

`ConversionWebhookRequest` has zero validation. A caller-supplied `idempotencyKey` > 128 chars, or an `orderId` long enough that `"conv:"+ulid+":"+orderId` exceeds 128, makes the INSERT throw `DataIntegrityViolationException` — which `tryReserveTransactional` **catches indistinguishably from a duplicate-key violation** and returns `false` → `AlreadyInProgressException` → 409, and the real conversion is **never recorded.** Under a non-strict MySQL mode the column would instead truncate, causing two distinct long keys to collide (false dedup). Bound the fields (`@Size`) and/or distinguish "duplicate" from "other constraint violation" in `tryReserveTransactional`.

### LOW-2 — Payout cross-workspace state oracle + key-poisoning ordering.
**File:** `service/PayoutService.java:158-166`.

`doQueuePayout` checks `hold.getStatus() != RELEASED` (throws `MILESTONE_NOT_RELEASED`) **before** `hold.workspaceId != workspaceId` (throws `MILESTONE_NOT_FOUND`). A member of *any* workspace who learns another workspace's `milestoneId` can distinguish "not released" from "not yours" via the differing error. Worse, because this runs inside `executeOnce`, the probe also reserves+FAILs that milestone's key — the cross-tenant amplifier for HIGH-1. ULID-guarded so practical enumeration is hard, but ownership should be checked before any state is leaked *and* before the key is reserved.

### LOW-3 — Contract concurrent double-sign can double-fire the PDF/email.
**File:** `service/ContractService.java:183-210`.

The already-signed guard is a status-check (not `executeOnce`, no unique-constraint backstop). Two truly-concurrent same-role sign requests can both read `brandSignedAt == null`, both pass the guard, both reach `generateAndDeliverContractPdf` (:208-209) → duplicate contract email to both parties. Narrow window, email-only, no money. The sequential-retry case (the actual E2 finding) is correctly closed.

### LOW-4 — (Out of E2 scope, noted in passing) `/contracts/{id}/sign` trusts `role` from the body.
**Files:** `web/ContractController.java:49-57`; `service/ContractService.java:170-198`.

A brand-authenticated principal can pass `role=CREATOR` and record the **creator's** signature — there is no check binding the principal's identity to the role they sign. Signature-attribution forgery on a legal document. Pre-existing, **not** an E2 regression (the already-signed guard doesn't affect it), but adversarially relevant to this surface — flagging for a separate decision.

---

## PROMPT ATTACK QUESTIONS — DIRECT ANSWERS

- **Payout TOCTOU / uniqueness arbiter:** DB `UNIQUE(idempotency_key)` insert-first-wins, not the in-memory `replayIfPresent` pre-check. Safe against double-spend.
- **Failed-mid-gateway replay → double payout?** No double payout (FAILED key blocks all retries + RazorpayX `reference_id` dedup). But it is **stuck-unsafe** → permanent wedge. See HIGH-1.
- **Caller-supplied payout key / reuse another milestone's key to suppress a legit payout?** Not possible — the payout key is server-derived per-milestone (`"payout:"+milestoneId`), caller controls only the path `milestoneId` (workspace-membership gated). Cross-milestone dedup-suppression via key reuse is **not** reachable here (unlike the conversion endpoint, HIGH-2).
- **Conversion pre-poisoning / namespace squatting / unbounded key:** Yes (HIGH-2), yes (namespace does not stop malicious squatting), and unbounded → LOW-1.
- **Contract no-op replay authz leak?** No — `requireMember` + workspace-scoped `requireContract` (`ContractService.java:170-171`, `requireContract` uses `findByIdAndWorkspaceId` :346-351) run **before** the short-circuit. Safe.
- **Meera replay authz leak?** No — `MeeraInternalController.persistTurnWriteback` resolves the conversation and cross-checks the on-behalf JWT against `conversation.workspaceId` (`:185-186`) **before** the service call; the replay query is conversation-scoped. Safe cross-tenant. One correctness edge (INFO): on a *late* retry after a subsequent turn wrote, `findTopByConversationIdOrderByCreatedAtDesc` (`MeeraSessionService.java:234-242`) returns the newer message, not the retry's own — wrong id/content, same tenant, not a security leak.

---

## B3 — InstagramMetricsFetcher (integration/meta/service/InstagramMetricsFetcher.java)

**SIGN-OFF.** Token handling is clean: `accessToken` is threaded to `InstagramInsightsClient` calls (`:129, :150, :186`) and **never logged**. All five log statements (`:153, :159, :190, :197, :215`) emit only `creatorProfileId`, `mediaItem.id()`, usage %, and caught-exception messages. Captions are never read. No persistence, no DB, no new endpoint. Rate-limit/degradation parity with the reviewed job is faithful.

- **LOW (advisory, inherited):** the media-list/insights catch blocks log `e.getMessage()` from `MetaApiException`/`MetaRateLimitException` (`:160-162, :198-201`). Same class as B2 LOW-1/2 — if the client layer ever puts the token in a request URL that surfaces in a Meta exception message, it would transit these logs. Not a deviation B3 introduces (the client owns exception construction); flagging for consistency with the B2 finding.
- **INFO:** the documented omissions (no pre-profile rate-limit check; no video/Reels metric gating) are behavior choices, not security issues; the class is currently dead code (no caller), so it carries no live attack surface. Matches Kavya's advisory.

No security defects worth gating B3.

---

## ROUTING

- **E2 → back to Vikram.** HIGH-1 (payout wedge) is the load-bearing blocker: move validation before `executeOnce` and make FAILED keys recoverable. HIGH-2 (conversion suppression) needs a webhook-authenticity decision (MEDIUM-1) and/or a non-predictable derived key. MEDIUM-2 (flat key namespace) is the structural fix under both.
- **B3 → Meera build verification.** No changes required.

**Kabir — Red-Team Lead, Sage Digital — 2026-07-07**

---

# RE-REVIEW (LOAD-BEARING): E2 Security Rework — Post-Vikram-Fix, Post-Kavya-Re-QA

**Reviewer:** Kabir (Offensive Security / Red-Team Lead)
**Date:** 2026-07-07 (second pass)
**Scope:** Adversarial verification of the rework that claims to close my HIGH-1 and HIGH-2. Traced the actual code, did not take Kavya's re-QA on faith. Review only, Maven not run (Meera owns the build).

## VERDICT: **SIGN-OFF (conditional — 1 residual HIGH downgraded to accepted-risk with a mandatory tracked follow-up; 2 residual LOW)**

HIGH-1 is **genuinely and fully closed** on both axes. HIGH-2's *squatting* and *lazy-amount* vectors are closed, but the **pre-poisoning primitive survives for fixed/known-price orders** — this is the amount-entropy residual the prompt asked me to flag. It is materially reduced (no longer trivial), does not by itself gate the rework given the compensating rate-limit + the real root-cause fix (HMAC) already scheduled for Wave D1, but it is **not eliminated** and MUST stay tracked. LOW-2 and LOW-3 are closed. Sign-off is contingent on the Wave D1 HMAC work remaining committed before this endpoint carries payout-affecting weight.

---

## HIGH-1 (payout wedge / double-payout) — CLOSED ✅

**Validation no longer poisons the key.** `PayoutService.queuePayout` (`PayoutService.java:98-138`) runs `validateForPayout` (`:153-198`) — milestone lookup, funded, ownership-before-RELEASED, collaboration — **entirely before** `executeOnce` is ever called (`:116` then `:119`). A validation failure throws straight out (`ApiException` at `:160/:164/:172/:178/:182/:190`) without reserving a key. Proven by `PayoutServiceTest.testMilestoneNotFunded` (`:235-252`): `verify(idempotencyService, never()).executeOnce(...)`. A brand calling one step too early can no longer wedge the payout. ✅

**FAILED-reclaim is genuinely atomic / race-safe.** The reclaim is a single status-guarded UPDATE — `IdempotencyKeyRecordRepository.reclaimFailedForRetry` (`:27-34`): `UPDATE ... SET status=:inProgress ... WHERE idempotencyKey=:key AND status=:failed`. Mutual exclusion is enforced by the DB's row lock on that UPDATE + the affected-rows check (`tryReclaimFailedTransactional` returns `updated == 1`, `IdempotencyService.java:160-165`). Two concurrent retries of the same FAILED key: exactly one UPDATE matches `status='FAILED'` and returns 1; the other matches 0 rows (status already moved to IN_PROGRESS) and is thrown `AlreadyInProgressException` (`IdempotencyService.java:87-96`). **Both cannot reclaim; both cannot hit RazorpayX.** This is the correct pattern — it is the DB, not app control flow, arbitrating. Proven by `IdempotencyServiceTest.testTwoConcurrentRetriesOfFailedKeyExactlyOneProceeds` (`:206-236`), and Vikram's `thenAnswer`-fresh-record fix (`:213-214`) is the correct mock — a shared-instance mock would have hidden the race. ✅

**Partial-failure double-payout — RESIDUAL LOW (not a blocker).** The external call is **NOT** the last statement in the supplier: `doQueuePayout` (`PayoutService.java:236-256`) calls `razorpayXClient.initiatePayout` at `:244`, then `milestone.markPayoutQueued` + `milestoneRepository.save` at `:251-252`, inside a `@Transactional` method — so the DB commit lands *after* the gateway side effect. If RazorpayX **succeeds** but the subsequent save/commit throws (DB blip, connection loss), the supplier throws → key marked FAILED → **now reclaimable** → the next legitimate retry re-calls `initiatePayout`. The only thing preventing a genuine double-payout in that window is RazorpayX-side dedup via `reference_id` + `X-Payout-Idempotency`, both set to the deterministic `"payout:"+milestoneId` (`RazorpayXClient.java:72,80`) — identical on retry, so RazorpayX collapses it.
- **Why this is not a NEW regression:** the pre-fix code relied on exactly the same RazorpayX `reference_id` backstop, and FAILED-reclaim does not weaken it. The reclaim makes the *availability* wedge recoverable without introducing a double-spend the gateway wouldn't already dedup.
- **Residual:** the guarantee now hinges *entirely* on RazorpayX reference_id dedup during a specific partial-failure window. Defense-in-depth would move the `markPayoutQueued`/save **before** the gateway call (reserve-then-call) or record an intermediate "gateway attempted" marker so a reclaim can detect an in-flight external effect and *verify* (via `fetchPayout`) rather than blindly re-issue. **Routed to Vikram as LOW hardening, not a gate.**

**Shared-service blast radius — CLEAN.** Enumerated all `executeOnce` callers: `PayoutService`, `ConversionTrackingService`, `RedemptionService` (`:169`), `ContractService` (`:249`), `RequestPaymentExecutor` (`:71`), `CreateCampaignExecutor`, `ConfirmLaunchExecutor`, `MeeraSessionService`. **No caller treated FAILED as "permanently done / do-not-retry."** Every caller's contract is: COMPLETED→replay prior result, IN_PROGRESS/FAILED-lost-race→re-query-or-409. Making FAILED re-runnable is purely additive — it only changes behavior for a key that previously would have 409'd forever. No caller persists a terminal money-effect keyed solely on FAILED. Redemption/RequestPayment both write their own domain ledger row (`coupon_redemptions`, `meera_tool_calls`) inside the supplier and replay from *that*, so a reclaim re-runs `doRedeem`/`doExecute` which itself is idempotent against its own UNIQUE(idempotency_key) domain table. Confirmed `COMPLETED` stays terminal (`IdempotencyServiceTest.testCompletedStaysTerminal:100-114`, `never().reclaimFailedForRetry`). ✅

## HIGH-2 (conversion pre-poisoning) — PARTIALLY CLOSED; amount-entropy residual stands (accepted-risk)

**Namespace squatting — CLOSED ✅ and airtight enough.** Caller-supplied keys starting with `convd:` are rejected 400 (`ConversionTrackingService.java:235-241`) via `startsWith(DERIVED_KEY_PREFIX)` — a prefix check, correct (not `equals`). Case-sensitivity: `convd:` is lowercase; a caller sending `Convd:` / `CONVD:` does NOT match `startsWith("convd:")` and is *accepted as a normal caller key* — but that is **safe**, because it lands in a *different* string than any derived key (which is always lowercase `convd:` + lowercase-hex), so it cannot collide with a derived key. Whitespace variants (` convd:`, `convd :`) likewise don't start with `convd:` and can't collide. There is no Unicode-normalization path (raw `String.startsWith`, no `.trim()`/NFKC before the derived-key comparison, and the derived key is machine-generated), so no normalization-collision surface. The reserved-namespace rejection is enforced and tested (`testCallerSuppliedKeyInReservedNamespaceRejected:227-238`, `verifyNoInteractions`). ✅

**Delimiter NUL→`|` canonicalization — NO COLLISION ✅.** Canonical = `utmCampaignId + "|" + orderPart + "|" + amountPart` (`deriveFallbackKey:307-311`). orderId IS caller-controlled on the public endpoint and CAN contain `|` — so I checked field-boundary ambiguity directly. For a poisoned key to *suppress* a real conversion it must reach **COMPLETED** on the exact key the real webhook derives. To COMPLETE (not FAIL), the attacker's `doRecordConversion` must pass `findById(utmCampaignId)` (`:337-343`, runs *inside* the supplier — a bogus utm 404s → supplier throws → key FAILED, reclaimable, NOT completed). So the attacker's `utmCampaignId` must be a **real ULID** = `|`-free (Crockford base32, `Ulids.java:9-11` via UlidCreator). `amountPart` is `BigDecimal.toPlainString()` = `|`-free. With `|`-free anchors at the first and last `|`, the parse `utm | order | amount` is unambiguous even when the middle `order` contains `|`: first-`|` fixes the utm boundary, last-`|` fixes the amount boundary. Therefore `attackerUtm==realUtm ∧ attackerAmount==realAmount ⇒ attackerOrder==realOrder` — a boundary collision forces field-equality, i.e. it is *the same conversion*, not a distinct one. **The NUL→`|` swap introduced no canonicalization collision.** Vikram's SHARED_CONTEXT reasoning holds. ✅

**Pre-poisoning residual — the amount-entropy hole (HIGH downgraded to ACCEPTED-RISK, mandatory follow-up):** Hashing `orderAmount` into the key only defeats an attacker who poisons with the *wrong* amount (e.g. the `amount=0` lazy case the new test `testPoisonThenRealWebhookStillRecordsRealConversion:200-220` exercises). It does **NOT** defeat an attacker who knows the real amount. For a **fixed-price product / publicly-priced SKU / known total** — extremely common for the Shopify/WooCommerce checkouts this endpoint exists to serve — the attacker knows `orderAmount` exactly. Combined with the public `utmCampaignId` (embedded in the tracking link a creator posts) and an enumerable/guessable `orderId`, the attacker reconstructs the *exact* triple, derives the *exact* `convd:` key, and pre-poisons it by POSTing a keyless webhook **with the correct amount** — which COMPLETES the key. The brand's real webhook then hits `AlreadyCompletedException` (`:276-280`) → silent no-op → **the genuine conversion is suppressed and the attacker's fabricated row (attacker-chosen creator/orderId attribution) is what got recorded.**
- **Net:** the fix reduced the attack from "trivial (any amount, e.g. 0, suppresses)" to "requires knowing/guessing the order amount." For **variable-amount** carts (cart total varies per buyer: quantities, shipping, tax, discounts) this is now genuinely hard — good. For **fixed-price** orders it is *still fully reachable* on a public, unauthenticated, HMAC-less endpoint. This is **partial mitigation**, exactly as the prompt anticipated — I am flagging it as residual, NOT claiming HIGH-2 is eliminated.
- **Why I am not re-REJECTING on it:** (a) the real root cause is the *absence of webhook authenticity* (my original MEDIUM-1), which the code explicitly and correctly defers to Wave D1 (`DERIVED_KEY_PREFIX` javadoc `:143-147`, controller `:52-56`) rather than pretending a derived key can substitute for a signature; (b) `AuthRateLimitFilter` `tracking` bucket throttles the enumeration of `(orderId)` needed to hit an *unknown* order, though it does nothing against a single correctly-guessed request; (c) suppression here corrupts **analytics/attribution**, not escrow/wallet money movement (that path is HIGH-1, separately and fully closed). This is a defensible accepted-risk **only** while the D1 HMAC work stays committed.
- **Mandatory condition on this sign-off:** the Wave D1 per-brand HMAC signature verification for `/webhooks/conversion` (and `/redemption`) MUST land before this endpoint's data is allowed to feed any creator-facing payout/ranking decision. If D1 slips or is descoped, this residual re-escalates to HIGH. **Routed to Arjun/Vikram to keep D1 bound to this endpoint.**

**LOW-1 (unbounded key/orderId vs 128-char column) — CLOSED ✅.** `idempotencyKey` bounded to 128 (`:242-249`, `MAX_IDEMPOTENCY_KEY_LENGTH`), `orderId` to 200 (`:250-255`, `MAX_ORDER_ID_LENGTH`), both rejected 400 *before* reaching the column. Derived key is `convd:` + 64-hex = 70 chars, safely under 128 regardless of orderId length. Tested (`testOverLengthIdempotencyKeyRejected`, `testOverLengthOrderIdRejected`). ✅

## LOW-2 (payout ownership-before-state oracle) — CLOSED ✅
`validateForPayout` checks `hold.getWorkspaceId().equals(workspaceId)` → `MILESTONE_NOT_FOUND` (`:177-179`) **before** the RELEASED check (`:180-185`). An unauthorized caller always sees `MILESTONE_NOT_FOUND` regardless of the hold's real state — no oracle. And because this runs before `executeOnce`, a cross-tenant probe can no longer reserve+FAIL another workspace's key. ✅

## LOW-3 (contract concurrent double-sign double-email) — CLOSED ✅
`recordSignature` now wraps the sign in `executeOnce` keyed `"contract-sign:"+contractId+":"+role` (`ContractService.java:247-259`), so the DB UNIQUE — not the read-then-write status guard — arbitrates two truly-concurrent same-role signs; the loser replays the refreshed contract (`:255-258`) instead of re-firing `generateAndDeliverContractPdf`. The `alreadySignedByThisRole` fast-path (`:269-278`) still handles the sequential-retry case. Note the FAILED-reclaim now also applies to this key — benign here (a failed sign SHOULD be retryable; PDF/email is best-effort/swallowed at `:307`, not re-fired by a signature-record failure). ✅

---

## PROMPT RE-ATTACK QUESTIONS — DIRECT ANSWERS

- **Can a validation failure still poison the payout key?** No — validation is entirely before `executeOnce` (`PayoutService.java:116` before `:119`); `testMilestoneNotFunded` proves `executeOnce` is never invoked on validation failure.
- **Is the FAILED-reclaim atomic / race-safe, or can two retries both hit RazorpayX?** Atomic. Single `UPDATE ... WHERE status='FAILED'` (`reclaimFailedForRetry`), affected-rows guard (`==1`), DB row-lock arbitrates. Exactly one reclaims; the other gets `AlreadyInProgressException`. Both cannot reach the gateway.
- **RazorpayX succeeded but key marked FAILED → reclaim re-calls = double payout?** Reachable in a narrow partial-failure window (gateway call at `:244` is NOT last; save/commit at `:251-252` follows). Backstopped by RazorpayX `reference_id`/`X-Payout-Idempotency` dedup (deterministic, identical on retry). Not a new regression, but the guarantee now leans on that gateway-side dedup — flagged as residual LOW; recommend reserve-then-call or a fetchPayout-verify on reclaim.
- **Does re-runnable-FAILED weaken any other executeOnce caller?** No. Enumerated all 8 callers; none treated FAILED as terminal-done. Additive change only. COMPLETED verified still terminal.
- **Amount-entropy pre-poison residual?** **YES, stands.** Amount-hashing defeats wrong-amount poisoning but NOT known/fixed-price-amount poisoning. Still reachable for fixed-price orders on the public HMAC-less endpoint. Partial mitigation; accepted-risk pending Wave D1 HMAC.
- **Namespace bypass (case/whitespace/unicode/prefix-vs-equals)?** Airtight. `startsWith("convd:")`, case-sensitive-safe (mixed-case caller keys can't collide with the always-lowercase derived key), no normalization surface.
- **orderId containing `|` → canonicalization collision?** NO. utm (ULID) and amount (toPlainString) are both `|`-free and anchor the first/last delimiters; a boundary collision forces field-equality (same conversion). Poisoning also requires COMPLETED, which requires a real ULID utm (findById inside the supplier).
- **Unbounded orderId/key vs 128-char column (LOW-1)?** Fixed — 128/200 bounds, rejected 400 pre-column.

---

## ROUTING (re-review)

- **E2 → SIGN-OFF, conditional.** Cleared to proceed. Two items back to **Vikram** as LOW hardening (non-gating): (1) payout partial-failure — move `markPayoutQueued`/save before the gateway call, or verify via `fetchPayout` on reclaim, so double-payout safety doesn't rest solely on RazorpayX reference_id; (2) nothing else code-level required.
- **To Arjun/product:** the amount-entropy pre-poison residual is an **accepted-risk gated on Wave D1 HMAC landing before `/webhooks/conversion` data feeds any payout/ranking decision.** If D1 slips/descopes, re-escalate to HIGH. MEDIUM-2 (flat `idempotency_keys` namespace, `scope` not in UNIQUE) still stands as the structural item but is not a blocker for this rework.

**Kabir — Red-Team Lead, Sage Digital — 2026-07-07 (load-bearing re-review)**
