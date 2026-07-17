# Wave E Task E2 — Plan-Wide Idempotency-Key Audit

**Auditor:** Vikram (Backend) · **Date:** 2026-07-07 · **Type:** read-only audit, no code changed.

**Note on file history:** an earlier pass of this exact audit was already written, and Kabir
already produced an adversarial confirmation of its 4 "genuine findings" at
`wiki/errors/wave-e2-kabir-confirmation.md` (referenced from SHARED_CONTEXT.md). This document
restores that original audit's findings faithfully (reconstructed from Kabir's confirmation, which
quotes it extensively, after the file was accidentally overwritten mid-session) so the paper trail
matches what was actually reviewed. Verdicts/severities below are the **Kabir-corrected** ones —
see "Kabir verdict" column — not my original (pre-adversarial-review) claims, since re-asserting an
already-disproven premise (Finding 1) would be dishonest bookkeeping.

An earlier, separate E2 pass (`wiki/errors/idempotency-audit-E2.md`, fully fixed and signed off —
see that file and `idempotency-fixes-E2-review.md` / `idempotency-fixes-E2-security-review.md`)
already covered the controller-level `web/*Controller.java` mutation sweep (payout, conversion
webhook, contract signature, Meera write-back). This document is a **second, later pass** that went
looking for gaps that sweep didn't have reason to catch — TOCTOU races around DB unique constraints
that ARE present but whose failure path leaks a raw 500, and a genuinely missed sibling-method gap
(`sendTurn` vs. the already-fixed `persistAssistantWriteback`).

---

## Findings (as adversarially confirmed by Kabir)

| # | Finding | My original claim | Kabir verdict | Corrected severity |
|---|---|---|---|---|
| 1 | `CreatorDiscoveryService.invite` (`influora-api/src/main/java/com/influora/service/CreatorDiscoveryService.java:173-206`) — concurrent invite of the same creator to the same campaign | Claimed no DB unique constraint exists on `(campaign_id, creator_id)`, so concurrent invites could create duplicate `Collaboration` rows | **DISPROVEN.** `V6__creators_collaborations.sql:67` has `UNIQUE KEY uq_campaign_creator (campaign_id, creator_id)`, confirmed never dropped in any later migration. The DB already prevents the duplicate-row scenario. Real (smaller) bug: `invite` does check-then-insert (`existsByCampaignIdAndCreatorId` then `save`) with no try/catch — the losing concurrent request's `save()` throws `DataIntegrityViolationException`, which `GlobalExceptionHandler` has no specific handler for, so it falls through to a raw `500` instead of the friendly `409 COLLABORATION_EXISTS` the sequential path already returns. | **Downgraded HIGH → LOW** (same bug class as #3) |
| 2 | `MeeraController.sendTurn` (`web/MeeraController.java:76-99`) → `MeeraSessionService.sendTurn` (`service/meera/MeeraSessionService.java:99-169`) | No idempotency-key handling anywhere in the human-facing chat-send path; a retry (double-click, network timeout+auto-retry, back-button resubmit) double-decrements `AICreditService.tryConsume` and inserts duplicate `AiMessage` USER/ASSISTANT rows | **CONFIRMED GENUINE.** Traced the full chain — no `Idempotency-Key` header read, no key param on the service method, `IdempotencyService` (already injected into this class for the sibling `persistAssistantWriteback`) is never invoked from `sendTurn`. Notably, `persistAssistantWriteback` (same file, lines 189-249) already has this exact fix from the earlier E2 pass (`executeOnce` keyed on `turn_id`) — `sendTurn` is the human-facing sibling that received no equivalent treatment, looking like an oversight (fixed the machine callback, missed the endpoint that actually spends the credit). | **MEDIUM (confirmed) — highest fix priority** |
| 3 | `AuthService.brandRegister` (`service/AuthService.java:78-115`) | Concurrent registration with the same email can produce an unhandled error | **CONFIRMED GENUINE.** `users.email` has a real `UNIQUE` constraint (`V2__core_auth.sql:5`) so no duplicate account can be created, but the check-then-insert shape (`existsByEmailIgnoreCase` at line 80, unguarded `save()` at line 111) means the losing concurrent request's `DataIntegrityViolationException` falls through to a raw `500` instead of the already-implemented `409 EMAIL_ALREADY_EXISTS`. | **MEDIUM (confirmed)** |
| 4 | `CampaignController.create` / `duplicate` (`service/CampaignService.java:97-, 212-225`) | No idempotency key, no natural-key protection; a retry creates a genuine second DRAFT/copy row, silently (not even a 500) | **CONFIRMED GENUINE**, correctly scoped low. No money/credit impact, low-frequency deliberate human action (not a hot retry path like a webhook or SSE chat send), easily spotted/deleted by the brand. | **LOW/MEDIUM (confirmed), lowest priority** |

## Backlog / non-issues (spot-checked by Kabir, both confirmed correct)

- **`PayoutService.confirmExecuted`** (`service/PayoutService.java:263-270`) — genuinely empty no-op, no `payouts` table exists yet in any migration (V1-V30 checked). Idempotent by construction; calling nothing twice is still nothing. **Forward-looking note (not an E2 task):** whoever builds the real `payouts` table must add its own replay guard then — `RazorpayWebhookController` has no `event.id`/`X-Razorpay-Event-Id` dedup today, relying entirely on downstream handlers being no-ops or state-machine-idempotent.
- **`EscrowController`'s multi-key shape** (`/fund` needs a caller `Idempotency-Key` header; `/release`, `/refund`, `/payout` each derive their own key from already-persisted terminal state) — confirmed deliberate and sound, not an inconsistency. `/fund` is the only endpoint with no natural terminal-state marker to short-circuit on yet.

---

## Fix priority (per Kabir's routing, executed this pass)

No HIGH/CRITICAL survives adversarial review. Order below is money/credits first, then TOCTOU-500 cosmetic-but-real bugs:

1. **MEDIUM — `MeeraSessionService.sendTurn` double AI-credit charge.** Fix: wrap the credit-consume + message-persist body in `IdempotencyService.executeOnce`, mirroring the already-proven `persistAssistantWriteback` pattern in the same file. Replay returns the previously-persisted `TurnResult` instead of re-consuming credit or re-inserting messages.
2. **MEDIUM (tie) — `AuthService.brandRegister` + `CreatorDiscoveryService.invite` TOCTOU → raw 500.** Fix: catch `DataIntegrityViolationException` at each `save()` call site and translate to the existing friendly `ApiException` (`EMAIL_ALREADY_EXISTS` / `COLLABORATION_EXISTS`) — precise per-call-site translation preferred over a generic `GlobalExceptionHandler` mapping, since the latter can't easily distinguish which constraint fired without parsing the DB error string.
3. **LOW — `CampaignController.create` / `duplicate` duplicate DRAFT rows.** Deferred this wave — no money/credit/security impact, defensible to accept as a known low-blast-radius gap.

See `wiki/errors/wave-e2-kabir-confirmation.md` for the full adversarial trace and citations backing each verdict above.
