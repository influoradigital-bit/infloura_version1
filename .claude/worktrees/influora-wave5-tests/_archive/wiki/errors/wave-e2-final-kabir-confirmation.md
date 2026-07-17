# Wave E2 — Final Adversarial Re-Confirm (Kabir)

**Date:** 2026-07-07
**Reviewer:** Kabir (Red-Team Lead)
**Scope:** Adversarial re-attack of the 3 IMPLEMENTATIONS Kavya QA-approved in `wiki/errors/wave-e2-fixes-QA.md`, with primary focus on `MeeraSessionService.sendTurn` (the genuine replay-correctness bug).

**VERDICT: ✅ CLEAN — ALL 3 FIXES HOLD. WAVE E2 CLOSED.**

---

## Probe 1 — 5-arg `executeOnce` overload: true no-op for all other callers

Traced every call site of `IdempotencyService.executeOnce` codewide (18 files reference `executeOnce`, 1 is the service itself). The **only** caller using the new 5-arg form is `MeeraSessionService.sendTurn` (`MeeraSessionService.java:158-163`). Every other caller uses the original 4-arg signature:

- `AffiliateSettlementJob.java:220`
- `AffiliateEarningsService.java:165`
- `ContractService.java:269` (contract signature dedup)
- `PayoutService.java:119`
- `ConfirmLaunchExecutor.java:134`
- `WooCommerceWebhookController.java:201`
- `CreateCampaignExecutor.java:94`
- `RequestPaymentExecutor.java:71`
- `ShopifyWebhookController.java:164`
- `ConversionTrackingService.java:268`
- `RedemptionService.java:299`
- `MeeraSessionService.persistAssistantWriteback` (line 380, the sibling method — still 4-arg)

Verified the delegation itself (`IdempotencyService.java:80-82`):

```java
public <T> T executeOnce(String idempotencyKey, String workspaceId, String scope, Supplier<T> action) {
    return executeOnce(idempotencyKey, workspaceId, scope, action, null);
}
```

and the consumption site (`runAndFinalize`, lines 126-136):

```java
String digest = resultRef == null ? null : resultRef.apply(result);
markCompletedTransactional(idempotencyKey, digest);
```

When `resultRef == null`, `digest` is `null`, and `markCompletedTransactional(key, null)` calls `record.markCompleted(null)` — **exactly the pre-existing behavior** every one of the 13 other callers already had before this fix (the column was written as `null` since V15, per Kavya's git-history check, independently re-confirmed: `IdempotencyKeyRecord.markCompleted` unconditionally assigns `this.resultDigest = resultDigest` with no branching, so passing `null` is byte-identical to the old `markCompleted(null)` call). **Confirmed true no-op** — Payout, Escrow (via ConfirmLaunchExecutor/RequestPaymentExecutor), webhooks (Shopify/WooCommerce), tool executors, and the sibling write-back method are all unaffected by this overload's introduction. No shared mutable state, no behavior branch keyed on which overload was used elsewhere.

---

## Probe 2 — New race window between message inserts and `result_digest` write

Traced the full lifecycle:

1. `sendTurn` → `idempotencyService.executeOnce(key, ws, scope, () -> doSendTurn(...), resultRef)`.
2. `tryReserveTransactional` runs FIRST, in its own `REQUIRES_NEW` transaction, and **commits immediately** (independent transaction) before `action.get()` (i.e., `doSendTurn`) ever runs. The row exists with `status=IN_PROGRESS` and `result_digest=NULL` from this point.
3. `doSendTurn` runs in the **caller's ambient transaction** (`@Transactional` on `doSendTurn` itself — since it's invoked via `this.doSendTurn(...)` inside the lambda, self-invocation means this `@Transactional` is actually a no-op wrapper per the class's own documented AOP caveat, but the point stands: it runs after the reservation commit).
4. Only after `action.get()` returns does `runAndFinalize` call `markCompletedTransactional(key, digest)` — another **independent `REQUIRES_NEW` transaction** that flips status to `COMPLETED` and writes `result_digest` atomically in the same UPDATE-equivalent flush.

**Attacked window:** what if a concurrent replay attempt (same key) arrives between step 2 (reserved, IN_PROGRESS, digest NULL) and step 4 (COMPLETED, digest written)?

- `executeOnce` first calls `tryReserveTransactional` → fails (row already exists) → falls to the `existing` branch (`IdempotencyService.java:104-120`).
- `existing.getStatus()` is read **fresh** via `repository.findByIdempotencyKey(idempotencyKey)` — a new query, not a cached reference — so it correctly sees `IN_PROGRESS` (not `COMPLETED`) during this window.
- `IN_PROGRESS` falls through to the final `throw new AlreadyInProgressException(idempotencyKey)` (line 120) — the concurrent caller gets a `409 IDEMPOTENCY_KEY_IN_PROGRESS`, **never** reaches `replaySendTurn`, **never** reads `result_digest` at all.
- `findResultRef` (and thus `replaySendTurn`) is only ever invoked from the `AlreadyCompletedException` catch block in `sendTurn` (line 164-165), which is only thrown when `existing.getStatus() == COMPLETED` (line 108) — by which point `markCompletedTransactional` has already committed both the status flip AND the digest write in the same transaction/flush. There is no intermediate state where status reads `COMPLETED` but `result_digest` is still null, because both fields are set together in `markCompleted(String)` (`IdempotencyKeyRecord.java:86-90`) inside one `REQUIRES_NEW` transaction — no partial-write window between them.

**No new race introduced.** The status field is the sole gate for whether `result_digest` is even consulted, and status/digest are written atomically together. A concurrent replay during the true in-flight window correctly gets `IN_PROGRESS` → 409, not a stale-read of `result_digest`.

---

## Probe 3 — `findResultRef`/message-fetch-by-PK failure modes

- `findResultRef` (`IdempotencyService.java:143-148`): returns `null` if the key doesn't exist or the row completed without a `resultRef` (i.e., digest is null). This can only happen for `sendTurn` if `AlreadyCompletedException` fired for a key that was completed via some **other** call path that didn't set a digest — traced: `SEND_TURN_IDEMPOTENCY_SCOPE` is a dedicated scope string (`"meera.messages.sendTurn"`) used nowhere else in the codebase (grepped), so no other caller can complete a key in this scope without going through `sendTurn`'s own resultRef-populated path. Not reachable in practice.
- `replaySendTurn` (`MeeraSessionService.java:260-320`) handles the `resultRef == null` / malformed-split case explicitly: `ids == null || ids.length != 2` → throws `ApiException("MESSAGE_NOT_FOUND", ..., 409 CONFLICT)` (lines 274-279). **Fails clean, not silently.**
- If a message row were ever deleted post-completion (no delete path exists today — confirmed via grep: `AiMessageRepository` has zero `delete*` methods, only `findBy*`/`save` inherited from `JpaRepository`; no cascade-delete or admin tooling touches `ai_messages`), `messageRepository.findById(userMessageId)` / `findById(assistantMessageId)` each independently `.orElseThrow(...)` → `ApiException("MESSAGE_NOT_FOUND", ..., 409 CONFLICT)` (lines 283-300). **No garbage/null propagation possible** — every failure mode along this path throws a typed 409, never returns a partially-populated or null `TurnResult`.

**Confirmed: fails closed and clean in every checked scenario, not exploitable, no data-corruption path.**

---

## Probe 4 — Re-confirm `AuthService.brandRegister` and `CreatorDiscoveryService.invite` (regression check)

Re-read both files directly against current working tree (not diff-only):

- `AuthService.java:93-132` — `brandRegister` still does the upfront `existsByEmailIgnoreCase` check (line 95, sequential-duplicate path → `409 EMAIL_ALREADY_EXISTS`), then wraps `walletRepository.save`/`userRepository.save`/`workspaceRepository.save` in try/catch, catching `DataIntegrityViolationException` → same `409 EMAIL_ALREADY_EXISTS` (lines 129-132). Import present (`org.springframework.dao.DataIntegrityViolationException`, line 31). Unchanged from Kavya's QA'd version, no regression.
- `CreatorDiscoveryService.java:177-221` — `invite` still does the upfront `existsByCampaignIdAndCreatorId` check (lines 200-206 → `409 COLLABORATION_EXISTS`), then wraps `collaborationRepository.save(collaboration)` in try/catch → same `409 COLLABORATION_EXISTS` (lines 214-221). Import present (line 30). Unchanged, no regression.

Both fixes are still exactly as QA'd — no drift.

---

## Independent Full-Suite Re-Run

Ran the suite myself (not copied from Kavya's report):

```
"/c/Users/Sage world/.m2/wrapper/dists/apache-maven-3.9.6-bin/3311e1d4/apache-maven-3.9.6/bin/mvn.cmd" -o -f influora-api test
```

**Result:** `Tests run: 581, Failures: 0, Errors: 1` — the 1 error is the same pre-existing `DatabaseConstraintIntegrationTest » IllegalState Could not find a valid Docker environment` (Wave E3 Testcontainers, no Docker daemon in this sandbox, unrelated to E2). Exact match to Kavya's reported count. Zero regressions.

---

## Final Verdict

**All 3 Wave E2 fixes are sound. No new vulnerabilities or races introduced by the `sendTurn` rework. Wave E2 is fully CLOSED** through Vikram (fix) → Kavya (QA APPROVED) → Kabir (adversarial re-confirm PASS, this document).

**NEXT:** Route to Meera for final live-verify (`mvn test`, confirm 581/581 + known Docker error only; no schema change in this batch, no new migration).
