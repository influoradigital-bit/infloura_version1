# 12 — Tara Run Report: V8 Wallet-Transactions Ledger Build

**Date:** 2026-07-05
**Scope:** V8 double-entry wallet ledger (foundational money-rails slice; gates V9 escrow, V10 contracts, all Meera AI money tool-calls)
**Scoped by:** Priya (CTO), per `00-BACKEND-BLUEPRINT-INDEX.md` and `01-DATA-MODEL.md`
**Reporter:** Tara (read-only; no code touched in producing this report)

---

## What Shipped

Vikram built the V8 slice end to end:

| File | Path | Verified |
|---|---|---|
| Migration | `src/main/resources/db/migration/V8__wallet_transactions.sql` | present |
| Entity | `src/main/java/com/influora/domain/entity/WalletTransaction.java` | present |
| Enum | `src/main/java/com/influora/domain/enums/TxnDirection.java` | present |
| Enum | `src/main/java/com/influora/domain/enums/WalletTransactionType.java` | present |
| Enum | `src/main/java/com/influora/domain/enums/TransactionStatus.java` | present |
| Enum | `src/main/java/com/influora/domain/enums/TxnReferenceType.java` | present |
| Repository | `src/main/java/com/influora/repository/WalletTransactionRepository.java` | present |
| Ledger service | `src/main/java/com/influora/service/WalletLedgerService.java` | present |

Plumbing added to existing files:
- `src/main/java/com/influora/domain/entity/Wallet.java` — `applyBalanceDelta()` (confirmed at line 105)
- `src/main/java/com/influora/repository/WalletRepository.java` — `findByIdForUpdate` pessimistic lock (confirmed at line 15)

Migration sequence in `src/main/resources/db/migration/` runs cleanly V1→V8 with no gaps or filename collisions.

## Review Gates

**Gate 1 — Kavya (QA), structural review: PASS**
Verified column/entity mapping, enum STRING usage, naming conventions, builder completeness, and the lock annotation against `01-DATA-MODEL.md` §0. No findings.

**Gate 2 — Kabir (Red-Team), adversarial review: FAIL → fixed**
- **HIGH** — idempotency check race under concurrency. Two concurrent requests with the same idempotency key could both pass the pre-check SELECT, take wallet locks, and only collide at the DB unique constraint — surfacing as an uncaught 500 instead of a clean idempotent response.
- **MEDIUM** — no currency-match enforcement between debit/credit wallets.

Vikram's fix, confirmed in `WalletLedgerService.java`:
- `DataIntegrityViolationException` import and catch block at line 165, wrapping the save() path with catch-and-refetch-existing-posting logic, returning `409 LEDGER_POSTING_CONFLICT` (line 175) on a genuine race instead of an uncaught 500.
- Explicit `CURRENCY_MISMATCH` (400) check present at lines 108 and 114.

Priya spot-checked both fixes present in the file — confirmed by this report's own read of the same lines.

**Gate 3 — Meera (DevOps), real build: FAIL (veto) → fixed → PASS**
First `mvn compile` (portable Maven, zip install, no admin rights) returned BUILD FAILURE with 3 compiler errors — none in the new ledger code, all pre-existing:
- `JwtAuthenticationFilter.java:46` — malformed 3-arg call against the 2-arg `FilterChain.doFilter` signature.
- `CampaignService.java:121` and `:175` — `TargetAudienceDto`/`List` type mismatch (passing a single object into a list-typed JSON helper).

Meera issued a VETO: blocked, not done.

Vikram's fixes, confirmed in source:
- `JwtAuthenticationFilter.java:46` now calls `chain.doFilter(request, response)` — matches the 2-arg `FilterChain` signature.
- `CampaignService.java:121` and `:175` now call `JsonLists.toJsonObject(req.targetAudience())` instead of `JsonLists.toJson(...)`, since `targetAudience` is a single object, not a list. Both call sites confirmed changed.

**Gate 4 — Priya, independent re-verification: PASS**
Re-ran `mvn compile` independently: BUILD SUCCESS, 91 source files compiled, exit code 0. Only remaining diagnostic is one pre-existing unchecked-operations warning in `CreatorDiscoveryService.java` (untouched, out of scope for this loop). Tara independently re-counted Java sources under `src/main/java` at time of this report: **91 files**, matching Priya's figure exactly.

## Current Build Status

**GREEN.** `mvn compile` succeeds cleanly. All 3 pre-existing compiler errors and both security findings on the new ledger code are resolved and verified in source. No new compiler warnings introduced by the V8 work.

Caveats:
- No live MySQL datasource was available in this environment. The V8 migration SQL was verified at code/spec level against `01-DATA-MODEL.md` only — it has not been executed against a live database, so runtime DDL correctness (constraint names, index behavior, actual double-entry balance invariants under load) is unverified.
- The repository has no automated tests (confirmed by Tara's own earlier inventory in `08-CODEBASE-INVENTORY.md`). This build had zero test coverage to run; all verification above is compile-level and manual code review, not test-driven.

## Explicitly Still Open / Unbuilt

Per the blueprint's ~140-file manifest (`10-VIKRAM-FILE-MANIFEST.md`), only V8 is done. Remaining and untouched:
- **V9** — escrow_holds
- **V10** — contracts / milestones
- **V11–V13** — brand_profiles, AI conversations, campaign_intents
- **V14** — credits / tool-call idempotency
- The entire Python AI/Meera service (separate from the Java backend covered here)

---

**Ready for Priya sign-off: YES** — V8 ledger code is complete, both security findings are fixed and verified in source, and the real build is green (91/91 files, exit code 0) after Meera's veto was cleared. Sign-off is scoped to V8 only; it does not cover live-DB migration execution (no DB available) or test coverage (none exists in the repo), and does not imply readiness of V9–V14 or the AI service, which remain unbuilt.
