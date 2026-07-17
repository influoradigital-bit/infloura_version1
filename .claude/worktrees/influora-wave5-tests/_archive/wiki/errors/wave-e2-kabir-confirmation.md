# Wave E2 Idempotency Audit — Kabir Adversarial Confirmation

Scope: adversarially confirm/refute the 4 "genuine findings" in Vikram's `wiki/errors/wave-e2-idempotency-audit.md`, plus spot-check 2 backlog/non-issue items. Every claim below was re-traced against current file state, not taken on the auditor's word — same discipline as D1/D4 first-pass reviews.

---

## Finding 1 — `CreatorDiscoveryService.invite` duplicate Collaboration rows

**Verdict: NOT GENUINE AS STATED — the report's premise is factually wrong. A real but much smaller finding exists underneath it.**

The report claims "no DB unique constraint on `(campaign_id, creator_id)` on the `Collaboration` entity." This is false. Traced `influora-api/src/main/resources/db/migration/V6__creators_collaborations.sql:67`:

```sql
UNIQUE KEY uq_campaign_creator (campaign_id, creator_id),
```

Confirmed no later migration drops or alters this constraint (grepped `uq_campaign_creator` and `ALTER TABLE collaborations` codebase-wide — zero hits). Two genuinely concurrent `invite` calls for the same `(campaignId, creator.userId)` pair CANNOT both insert — the database arbitrates it, exactly the discipline Wave A-D established elsewhere (coupon codes, affiliate earnings, etc.).

**What is real:** `CreatorDiscoveryService.invite` (`influora-api/src/main/java/com/influora/service/CreatorDiscoveryService.java:173-206`) does check-then-insert (`existsByCampaignIdAndCreatorId` at line 187, `collaborationRepository.save` at line 201) with no idempotency-key guard and no try/catch around the save. The losing concurrent request's `save()` will throw `DataIntegrityViolationException` on the unique-key violation. `GlobalExceptionHandler` (`influora-api/src/main/java/com/influora/common/GlobalExceptionHandler.java`) has no handler for `DataIntegrityViolationException` — it falls through to the generic `@ExceptionHandler(Exception.class)` at line 44, returning a bare `500 INTERNAL_ERROR` instead of the friendly `409 COLLABORATION_EXISTS` the sequential path already returns at line 189-192.

**Correct classification: this is the SAME class of bug as Finding 3 (AuthService.brandRegister)** — a TOCTOU race that the DB constraint already prevents from corrupting data, but which surfaces as an ugly unhandled 500 for the loser instead of the existing friendly 409. Not HIGH, not a duplicate-row risk. Downgrade to LOW, same tier as Finding 3, and fix both with the same mechanism (see recommendation below).

No connection to the E1 Collaboration-workspace-propagation escalation materializes here — that concern was about `workspace_id` resolution on `findById` reads, not about insert-path uniqueness. This entity's write path is sound.

---

## Finding 2 — `MeeraController.sendTurn` double AI-credit charge

**Verdict: GENUINE. Confirmed real and exploitable.**

Traced the full call chain: `MeeraController.sendTurn` (`influora-api/src/main/java/com/influora/web/MeeraController.java:76-99`) → `MeeraSessionService.sendTurn` (`influora-api/src/main/java/com/influora/service/meera/MeeraSessionService.java:99-169`). No `Idempotency-Key` header is read anywhere in the controller method, no idempotency key parameter exists in the service signature, and `IdempotencyService` (injected into this same class for `persistAssistantWriteback`, see below) is never invoked from `sendTurn`.

Concretely, on a browser retry (double-click, network timeout + auto-retry, back-button resubmit):
1. `creditService.tryConsume(workspaceId, TURN_CREDIT_COST)` (line 112) runs twice → two decrements against `AICreditService.tryConsume`'s capped balance (`influora-api/src/main/java/com/influora/service/meera/AICreditService.java:73-103`, atomic `tryDecrement` — the decrement itself is race-safe, but nothing stops it from being called twice for what the user considers one action).
2. Two `AiMessage` USER rows persisted (line 114-122) for the same logical turn.
3. Two placeholder `AiMessage` ASSISTANT rows persisted (line 148-161).

This is a genuinely exploitable double-charge on a capped, monetizable resource (AI credits), on a plain POST with no idempotency protection at all — the weakest form of protection (none) on a chat-send endpoint that is inherently retry-shaped (SSE/stream setup, exactly the kind of call a flaky mobile connection retries).

**Notably, this class was already fixed once in the same file**: `persistAssistantWriteback` (lines 189-249) has a detailed javadoc block documenting "E2 audit finding #16, MEDIUM — fixed," wrapping itself in `IdempotencyService.executeOnce` keyed on a `turn_id`-derived `Idempotency-Key` header forwarded by the Python callback. `sendTurn` is the sibling, human-facing entry point that received no equivalent treatment. The fix pattern is proven and sitting in the same class — this makes the gap look like an oversight (fixed the machine-to-machine callback, missed the human-facing endpoint that actually initiates the turn and does the credit spend) rather than a considered decision.

---

## Finding 3 — `AuthService.brandRegister` TOCTOU → unhandled 500

**Verdict: GENUINE. Confirmed real and exploitable.**

Traced `AuthService.brandRegister` (`influora-api/src/main/java/com/influora/service/AuthService.java:78-115`). Line 80 checks `userRepository.existsByEmailIgnoreCase(req.email())`, throws friendly `409 EMAIL_ALREADY_EXISTS` if true; otherwise proceeds to build and `save()` the `User` at line 111 with no re-check and no try/catch. Confirmed `users.email` has a genuine DB-level `UNIQUE` constraint (`V2__core_auth.sql:5`: `email VARCHAR(255) UNIQUE`), so two concurrent registrations with the same email cannot both succeed — but the loser's `userRepository.save(user)` throws `DataIntegrityViolationException`, which (same as Finding 1) has no dedicated handler in `GlobalExceptionHandler` and falls through to the generic `500 INTERNAL_ERROR` handler at line 44-48.

This is a real, easily-triggered UX/API-contract bug (double-click "Sign Up," or any client that fires a duplicate request on network ambiguity) that leaks a raw 500 instead of the semantically correct 409 the sequential path already knows how to produce. No data corruption, no security exposure — but it is a genuine defect matching the report's description exactly.

---

## Finding 4 — `CampaignController.create` / `duplicate` duplicate DRAFT rows

**Verdict: GENUINE, correctly scoped as LOW/MEDIUM.**

Traced `CampaignService.create` (`influora-api/src/main/java/com/influora/service/CampaignService.java:97-`) and `duplicate` (lines 212-225). Neither reads an idempotency key, neither has any natural-key/unique-constraint protection (a campaign has no unique title-per-workspace constraint, nor should it), and both simply `campaignRepository.save(...)` a freshly-`Ulids.newUlid()`-keyed row. A client retry (double-click "Create Campaign," or a retried duplicate-button click) creates a genuine second DRAFT/copy row with no error at all — worse than Findings 1/3 in one sense (silent duplication, not even a 500) but lower severity than either in blast radius: no money movement, no credit consumption, easily identified and deleted by the brand, and campaign creation is a low-frequency, deliberate, single-user action (not a hot path prone to network-retry storms the way a webhook or SSE-backed chat send is).

Confirms the report's own LOW/MEDIUM rating is correct — do not over-fix this relative to Findings 2/3.

---

## Backlog spot-check 1 — `PayoutService.confirmExecuted` "intentional no-op"

**Verdict: Backlog classification CONFIRMED CORRECT, with one clarifying trace the report didn't fully spell out.**

Read `PayoutService.confirmExecuted` (`influora-api/src/main/java/com/influora/service/PayoutService.java:263-270`) and its only caller, `RazorpayWebhookController.receive` (`influora-api/src/main/java/com/influora/integration/razorpay/RazorpayWebhookController.java:46-68`, dispatch at line 58-59 for `payout.processed`/`payout.reversed` events).

The method body is genuinely empty — no `payouts` table exists in this schema slice (confirmed: grepped all V1-V30 migrations, no `CREATE TABLE payouts`), so there is nothing to double-write. A no-op function is idempotent by construction regardless of how many times Razorpay redelivers the webhook (which it will, per standard webhook-retry semantics) — calling nothing twice is still nothing. This is not a deferred idempotency risk; it's a genuinely inert stub with a real target (the javadoc correctly names the future `payouts` table lookup + PENDING→PROCESSED flip as the eventual real implementation). Auditor's call to backlog this rather than flag it is sound.

**One thing worth flagging forward, not as an E2 finding but as a note for whoever builds the real `payouts` table later**: when that table exists, `confirmExecuted` will need its OWN idempotency guard (the webhook redelivery is real and will still happen) — `RazorpayWebhookController` has no request-level replay protection today (no `event.id`/`X-Razorpay-Event-Id` dedup), it relies entirely on `confirmFunded`/`confirmExecuted` being no-ops or state-machine-idempotent downstream. Confirmed `escrowService.confirmFunded` (the sibling handler, `order.paid`/`payment.captured`) is state-machine-idempotent by design (moves PENDING→FUNDED once; a second delivery would need to be checked — not in this task's scope, flagging only because I was already in this file). Not blocking E2, but log as a named follow-up for whoever implements the payouts table.

## Backlog spot-check 2 — `EscrowController.fund` multi-key note

**Verdict: Backlog classification CONFIRMED CORRECT.**

Read `EscrowController` in full (`influora-api/src/main/java/com/influora/web/EscrowController.java`). `/fund` (lines 48-69) is the only endpoint requiring a caller-supplied `Idempotency-Key` header, and correctly so — it's the only one of the four where the "same logical action" isn't already pinned to a single mutable resource with its own state machine. Traced the other three:

- `/release` → `EscrowService.release` (`influora-api/src/main/java/com/influora/service/EscrowService.java:237-296`): checked `hold.getStatus() == EscrowStatus.RELEASED` → `return toStatusResponse(hold)` **idempotent no-op** at line 268-270, before the ledger post. Retries land on an inert read.
- `/refund` → `EscrowService.refund` (lines 299-`): identical pattern, `EscrowStatus.REFUNDED` short-circuit at line 308-310.
- `/payout` → `PayoutService.queuePayout`: derives its own deterministic key (`"payout:" + milestoneId`, line 101) and runs it through `IdempotencyService.executeOnce` — this is the method whose javadoc documents a **prior E2 CRITICAL fix** (no local guard existed before, relied solely on RazorpayX's own dedup) and a **prior E2 HIGH-1 fix** (validation moved outside `executeOnce` so a validation failure can't wedge the key). Read the current code: validation (`validateForPayout`) now runs before `executeOnce` is called (line 116-123), matching the javadoc exactly — this fix is real and already landed, not aspirational documentation.

So the "multi-key" shape is deliberate and sound: `/fund` uses caller-supplied header keys because it's the entry point with no natural terminal-state marker yet to short-circuit on; the other three each derive their own key from already-persisted, already-terminal state. This is the same discipline pattern used throughout Wave A-D (coupon redemption, affiliate earnings settlement). No gap found. Auditor's non-blocking note was appropriately non-blocking.

---

## Summary verdict table

| # | Finding | Verdict | Severity (corrected) |
|---|---|---|---|
| 1 | `CreatorDiscoveryService.invite` duplicate rows | **NOT GENUINE as stated** (unique constraint exists, V6:67) — real underlying bug is unhandled 500 on race, same shape as #3 | Downgrade HIGH → LOW |
| 2 | `MeeraController.sendTurn` double credit charge | **GENUINE** | MEDIUM (confirmed) |
| 3 | `AuthService.brandRegister` TOCTOU → raw 500 | **GENUINE** | MEDIUM (confirmed) |
| 4 | `CampaignController.create`/`duplicate` dupe rows | **GENUINE** | LOW/MEDIUM (confirmed) |
| Backlog 1 | `PayoutService.confirmExecuted` no-op | **Correctly backlogged**, one forward-looking note logged for future `payouts` table work | N/A |
| Backlog 2 | `EscrowController.fund` multi-key shape | **Correctly backlogged**, no gap | N/A |

## Fix priority order for Vikram

Money/credits first, cosmetic-but-real UX bugs after, no HIGH/CRITICAL survives adversarial review this round:

1. **MEDIUM — `MeeraSessionService.sendTurn`** (highest priority of the confirmed set: real double-charge on a capped, monetizable resource). Fix: require an `Idempotency-Key` header on `POST /meera/sessions/{conversationId}/messages` (mirrors `EscrowController.fund`'s pattern) or derive one from `(conversationId, content-hash)` if header plumbing to the frontend isn't ready this wave; wrap the credit-consume + message-persist body in `IdempotencyService.executeOnce`, same mechanism already proven in `persistAssistantWriteback` in the same file. On replay, return the previously-persisted `TurnResult` rather than re-consuming credit or re-inserting messages.

2. **MEDIUM (tie) — `AuthService.brandRegister` and the newly-reclassified `CreatorDiscoveryService.invite`** (both are the identical bug pattern: TOCTOU race where a real DB unique constraint already prevents corruption, but the losing request gets a raw 500 instead of the correct 409). Fix both together with one shared mechanism: either (a) wrap the final `save()` in a try/catch for `DataIntegrityViolationException` and translate to the appropriate `ApiException` (`EMAIL_ALREADY_EXISTS` / `COLLABORATION_EXISTS`) at each call site, or (b) add a `@ExceptionHandler(DataIntegrityViolationException.class)` to `GlobalExceptionHandler` that maps common unique-constraint violations to 409 generically — prefer (a) for precise error codes/messages, since (b) can't easily distinguish which constraint fired without parsing the DB error string.

3. **LOW — `CampaignController.create` / `duplicate`**. Lowest priority, no money/credit/security impact. If addressed this wave: add `Idempotency-Key` support consistent with the pattern above, or accept as a known low-blast-radius gap and defer — either call is defensible given the severity.

No further HIGH/CRITICAL items survive this pass. Finding 1 in particular should NOT be dispatched to Vikram at its original HIGH severity — the report's central premise (missing unique constraint) is disproven by the migration file itself, and routing it as HIGH would waste a fix cycle chasing a non-existent duplicate-row bug instead of the much smaller error-handling gap that's actually there.
