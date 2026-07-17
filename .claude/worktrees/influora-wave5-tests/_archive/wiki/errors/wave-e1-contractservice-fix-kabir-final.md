# Wave E1 — ContractService.generate Fix: Kabir Final Adversarial Re-Confirm

**Auditor:** Kabir (Red-Team)
**Date:** 2026-07-07
**Input:** `wiki/errors/wave-e1-kabir-escalation-review.md` (my original HIGH finding), `wiki/errors/wave-e1-contractservice-fix-kavya-qa.md` (Kavya's APPROVED QA verdict)
**Method:** Independent re-trace of live code (not trusting Kavya's or my own prior read) + 3 additional adversarial probes per orchestrator's request. Note: a prior attempt at this exact re-confirm was interrupted mid-run by a process crash and produced no output; this is a fresh full run, not a resumed one.

---

## VERDICT: PASS — fix is genuine, complete, no bypass found. Wave E1 finding CLOSED.

---

## 1. Re-attempted original attack against live code

Read `influora-api/src/main/java/com/influora/service/ContractService.java` in full (current working tree, not a diff). Confirmed line-for-line against Kavya's QA report — no drift since her review.

Traced the exact path a hostile Brand A (member of `WORKSPACE_A` only) would hit calling `POST /contracts` with `{"collaborationId": "COLLAB_B", ...}` where `COLLAB_B` belongs to Brand B's campaign:

1. `ContractController.generate:36-39` — `workspace = brandContext.requireBrandWorkspace(principal)`. This resolves `WORKSPACE_A` from the JWT-authenticated principal only; `ContractGenerateRequest` (`MoneyDtos.java`) has no `workspaceId` field, so Brand A cannot inject `WORKSPACE_B` here even if it wanted to escalate rather than corrupt.
2. `ContractService.generate:97-98` — `brandContext.requireMember`/`requireRole` confirm Brand A is OWNER/ADMIN/MANAGER of `WORKSPACE_A`. Proves nothing about `COLLAB_B`.
3. `ContractService.generate:100-108` — `collaborationRepository.findById("COLLAB_B")` succeeds (it's a real row) and returns Brand B's `Collaboration{campaignId=CAMP_B, creatorId=X}`.
4. **`ContractService.generate:121-128` (the fix) — `campaignRepository.findByIdAndWorkspaceId(CAMP_B, "WORKSPACE_A")`.** `CAMP_B`'s actual `workspace_id` column is `WORKSPACE_B`, not `WORKSPACE_A`, so this query returns empty. `.orElseThrow(...)` fires: `ApiException("COLLABORATION_NOT_FOUND", "Collaboration not found", HttpStatus.NOT_FOUND)`.
5. Method exits via exception. Lines 130-169 (milestone validation, `Contract.builder()`, `contractRepository.save`, `PaymentMilestone` construction, `milestoneRepository.saveAll`) never execute. `@Transactional` (line 95) means even if something above had partially flushed, it rolls back — but nothing did, since the throw happens before any entity is even constructed.

Result: attack is blocked. Brand A receives an identical 404 `COLLABORATION_NOT_FOUND` to what it would get for a nonexistent id. No `Contract`/`PaymentMilestone` row is created, no cross-tenant corruption, no misdirected PDF/email. This matches Kavya's Gate 1/2/3/4 findings exactly — independently re-derived, not copied.

---

## 2. Probe: any OTHER route to contract creation?

Grepped every `contractRepository.save(` call site in the entire `influora-api` tree: exactly 3 hits, all inside `ContractService.java` —
- Line 153: inside `generate` (the guarded path, traced above)
- Line 305: inside `doRecordSignature` (mutates an already-existing `Contract` fetched via `requireContract(contractId, workspaceId)`, which itself is `contractRepository.findByIdAndWorkspaceId` — already workspace-scoped, not a creation path)
- Line 356: inside `generateAndDeliverContractPdf` (sets `pdfR2Key` on an existing signed contract, same pre-scoped object, not a creation path)

No second controller, no admin path, no batch job constructs `Contract` rows anywhere else in the Java codebase.

Checked the Meera AI-chat tool-executor surface (`influora-ai/app/tools/schemas.py`, `influora-api/src/main/java/com/influora/web/MeeraInternalController.java`) since that is the one other place server-side business actions get triggered outside normal brand-dashboard HTTP calls. `MeeraInternalController` exposes exactly 6 endpoints: `/show_creators`, `/calculate_budget`, `/create_campaign`, `/request_payment`, `/confirm_launch`, `/messages`. None of them call `ContractService` — grepped `loop.py` and the executor package for "contract"/"Contract" with zero matches. `request_payment` (the only money-adjacent tool) explicitly only stages a `PENDING_CONFIRM` result and does not touch `Contract`/`PaymentMilestone` entities at all (confirmed by reading its controller wiring and class javadoc at `MeeraInternalController.java:59-61`: "the actual money movement happens on a wholly separate public endpoint the browser calls on human click").

**Verdict: no alternate route exists.** `ContractController.generate` → `ContractService.generate` is the sole path to a `Contract` row's creation, and it is fully gated.

---

## 3. Probe: can `collaboration.getCampaignId()` be null/manipulated to no-op the check?

Traced schema and entity definitions:
- `V6__creators_collaborations.sql:56`: `campaign_id VARCHAR(26) NOT NULL`, plus `fk_collab_campaign FOREIGN KEY (campaign_id) REFERENCES campaigns(id)` (line 70) — a `Collaboration` row physically cannot exist in the database with a null or dangling `campaign_id`; the FK guarantees it references a real `campaigns.id`.
- `Collaboration.java:22`: `@Column(name = "campaign_id", nullable = false, length = 26)` — JPA-level mirror of the same constraint, so even a future ORM-level insert path (bypassing raw SQL) would be rejected by Hibernate/validation before hitting the DB.

Since every `Collaboration` row that `findById` could ever return already satisfies `NOT NULL` + FK-valid `campaign_id`, `collaboration.getCampaignId()` can never be null for a row that exists. There is no code path (migration, test fixture builder, or application code) that constructs a `Collaboration` bypassing the entity's mapped column — grepped `Collaboration.builder\(\)|new Collaboration\(` and confirmed the only construction path is the entity's own static factory (`Collaboration.java:54-63`) which requires `campaignId` as a constructor parameter, not an optional setter.

Even hypothetically, if `campaignId` were somehow null, `campaignRepository.findByIdAndWorkspaceId(null, workspaceId)` would not silently no-op or match everything — a `WHERE id = ? AND workspace_id = ?` query with a null bind parameter matches zero rows under standard SQL null semantics (`id = NULL` is never true), so the check would still correctly throw `COLLABORATION_NOT_FOUND` rather than fail open. This is a "safe by SQL semantics as well as by schema" double-guarantee, not a single point of failure.

**Verdict: no null/manipulation bypass exists**, at both the schema-constraint layer and the (moot, but verified) query-semantics layer.

---

## 4. Probe: timing-attack angle on the enumeration oracle

Kavya's QA already confirmed identical error code/message/HTTP status for both negative branches (Gate 3). Extended this to a timing lens:

- **"Doesn't exist" branch:** one query — `collaborationRepository.findById(req.collaborationId())` against `collaborations.id` (PRIMARY KEY, `V6__creators_collaborations.sql:55`). Returns empty → throws immediately.
- **"Wrong workspace" branch:** two queries — the same `collaborationRepository.findById` (succeeds, real row) PLUS `campaignRepository.findByIdAndWorkspaceId(campaignId, workspaceId)` against `campaigns.id` (also a `VARCHAR(26) PRIMARY KEY`, standard pattern confirmed across every migration table in this schema) combined with an indexed/equality `workspace_id` predicate. Returns empty → throws.

The wrong-workspace branch does run one additional indexed point-lookup query compared to the doesn't-exist branch. This is a structural extra-hop, but:
- Both are single-row PRIMARY KEY index seeks (O(1), not a scan, not proportional to table size or to any attacker-controlled input length).
- Neither branch does any data-dependent looping, hashing, or string comparison whose cost scales with how "close" the guess is (unlike e.g. a naive byte-by-byte HMAC comparison) — the timing difference, if any, is a fixed, constant-ish "one extra indexed round-trip" delta, not a signal that leaks anything about *which* other workspace it belongs to, or scales with any secret value.
- A constant one-extra-query overhead for "exists but is someone else's" vs. "doesn't exist at all" is the same shape of timing profile already accepted codebase-wide for the precedent this fix explicitly mirrors — `CampaignLinkService.createTrackingLink`'s resolve-then-scope pattern (collaboration lookup, then campaign lookup, then compare) has an identical two-query-vs-one-query shape between its "not found" and "found but mismatched" branches, and that pattern was already accepted in the original E1 review as the gold-standard fix template. This fix does not introduce a *new* class of timing signal beyond what the codebase already tolerates elsewhere for the same reason (an unavoidable consequence of "resolve, then check ownership" being fundamentally a two-step process when the check requires a second table).
- Practically: distinguishing "1 DB round-trip" from "2 DB round-trips" over a network-facing HTTP API, under normal jitter (auth middleware, connection pool acquisition, JSON serialization, TLS), is not a realistic exploitable channel to reliably determine "this ID exists in someone else's workspace" versus "doesn't exist" — and even if an attacker somehow did distinguish it, the only information gained is binary existence-of-id, not which workspace, not any campaign/creator detail, not amounts. This is a materially different (and already precedented) risk than a secret-comparison timing leak.

**Verdict: no meaningfully exploitable timing oracle.** The theoretical one-extra-query delta is structurally identical to an already-accepted codebase pattern and leaks nothing beyond binary existence, which the identical error body already discloses is intentionally indistinguishable in every dimension that matters (code/message/status). Not a blocking finding; not worth a synthetic-delay countermeasure given what's actually at stake here (an already-non-secret-shaped 26-char ULID existence bit, not a password or token comparison).

---

## Summary

| Probe | Result |
|---|---|
| Re-run original cross-workspace attack | **Blocked** — 404 `COLLABORATION_NOT_FOUND`, zero repository writes, confirmed by independent trace |
| Alternate contract-creation route (2nd controller / admin / Meera tool) | **None found** — `contractRepository.save` has exactly 3 call sites, all in `ContractService.java`, only 1 is a creation path, and it's the guarded one; Meera AI executor has no contract tool at all |
| `campaignId` null/manipulation edge case | **Not exploitable** — `NOT NULL` + FK constraint at both DB and JPA layers make a null `campaignId` impossible for any persisted row; SQL null semantics would fail closed even hypothetically |
| Timing-attack angle on enumeration oracle | **No meaningful signal** — one extra indexed PK lookup on the mismatch branch, same shape as the already-accepted `CampaignLinkService` precedent, leaks only a non-secret existence bit at most |

No residual gap. This closes Wave E1's genuine HIGH finding (`wiki/errors/wave-e1-kabir-escalation-review.md` item 1, `ContractService.generate`).

---

## Files for orchestrator

- This report: `wiki/errors/wave-e1-contractservice-fix-kabir-final.md`
- Route to Meera for final live-verify (per Kavya's routing note: pure logic change, no schema change — confirm `mvn test` passes in her environment; no live-MySQL/Testcontainers dependency for this specific fix)
- Wave E1 HIGH finding: **CLOSED**
