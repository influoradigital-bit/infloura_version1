# CR-51 — Deliverable materialization is dead: fix plan

> **Owner of this document:** Priya (CTO). **Implementation owner:** Vikram (backend).
> **Status:** plan approved, not implemented. **Gate:** Vikram → **Kabir (mandatory, money path)** → Kavya → Neha.
> **Do not implement step 1 alone.** Read §3 before touching the key.

---

## 1. The defect, in one line

`DealService` writes the agreed deliverable slots under the metadata key **`deliverables`**; `ContractService` reads them under **`deliverableSlots`**. The reader therefore returns `null` on every contract that has ever been generated, and **no `Deliverable` row has ever been materialized from a proposal.**

| Role | Location | Key |
|---|---|---|
| Writer | `DealService.java:953` | `metadata.put("deliverables", deliverables)` |
| Reader | `ContractService.java:425` | `metadata.get("deliverableSlots")` |

**`deliverableSlots` appears nowhere else in `src/main/java`** — only in ContractService's own javadoc, comment, `get()` and error log. Nothing writes it. This was verified by exhaustive grep, not inference.

## 2. Proven live on `47ad258` (2026-07-30), not inferred

Full chain driven against `http://200.141.1.6` with real brand + creator sessions:

campaign → ACTIVE → deal (with `deliverables:[{type:'REEL',qty:1}]`) → creator accept → contract → **both signatures → contract `ACTIVE`** → **escrow FUNDED ₹20,000** → `GET /deals/{id}/deliverables` → **`[]`**.

The proposal metadata *did* carry the slots — `{"amount":15000,"status":"accepted","deliverables":[{"qty":1,"type":"REEL"}],"deliverableCount":1}` — and the payload matched the DTO exactly (`DeliverableSlot(String type, Integer qty)`). The data is written correctly and read from the wrong key.

## 3. ⚠️ Why the one-word fix is the dangerous option

`EscrowService.assertReleaseConditionSatisfied` (`:1060`) **fails open**:

```java
List<Deliverable> linked = deliverableRepository.findByMilestoneId(milestone.getId());
if (linked.isEmpty()) { return; }   // no gate applied
```

Because deliverables never materialize, `linked` is **always** empty, so **the release-condition gate has never fired for any milestone in production.**

Renaming the key flips that gate from *never fires* to *always fires*, for every deal, in one deploy. Deals currently mid-flight with funded escrow and no deliverables would begin throwing `RELEASE_CONDITION_NOT_MET` on release. **That converts a silent hole into a production outage.** This is why step 1 must not ship alone.

## 4. The architectural finding, for the record

Two independently-defensible leniency decisions compose into a hole:

- **ContractService** (`:353-358`) — no slots → materialize zero rows, don't fail contract generation. Rationale: legacy proposals predate structured deliverables.
- **EscrowService** (`:1053-1057`) — no linked deliverables → skip the release gate. Rationale: lump-sum and product-seeding milestones legitimately have none; retroactively blocking them "would be a production-breaking change".

Each is correct alone. Together they mean **a deal with no deliverables funds escrow and releases money with no content gate at all** — and the key mismatch puts *every* deal on that path. The narrow legacy fallback became the only path.

**Third instance of a theme this codebase has already paid for twice:** *a guard that cannot see its own precondition scores "safe".* `clientIp()` compared a spoofed value against itself (CR-38); `canDelete()` queried a column that was NULL on every hold that mattered (CR-39); `assertReleaseConditionSatisfied` queries a table that is empty for every milestone (CR-51). **The B5 fix, which existed to close "content approval was gated on nothing", is itself gated on nothing.**

## 5. The plan — four steps, in this order

### Step 1 — Make the reader and writer agree
Align on **one** key. Prefer changing the **reader** to `deliverables` (the writer's key is already persisted in live `deal_messages` rows; changing the writer would orphan existing data and require a backfill).
- Fix `ContractService:425` and the three stale `deliverableSlots` references around it (javadoc `:353`, `:412`, log `:432`) so the comments stop describing a key that does not exist.
- **Add a test that fails on the mismatch** — construct a proposal via `DealService`, generate a contract, assert ≥1 `Deliverable` row. This is the test whose absence let a pure string mismatch survive: both sides had passing unit tests because each was tested against its own key.

### Step 2 — Narrow the fail-open, do NOT remove it
`assertReleaseConditionSatisfied` must keep failing open for genuinely legacy milestones and start gating new ones. Discriminate on data that already exists — `contract.version`, or the contract/collaboration `created_at` against the deploy timestamp. **Do not gate on "has deliverables"** — that is the circular condition that created this bug.

### Step 3 — Answer the product question (Swapnil/Priya, not Vikram)
Should a **new** escrow-funded deal be permitted to have zero deliverables? Optional-for-legacy is right. Optional-forever means escrow with no content protection, which is the inverse of what escrow is for. If the answer is "no", `deliverables` gains `@NotEmpty` on `CreateDealRequest` — but only for the direct-deal path, and only after step 2 ships.

### Step 4 — Reconcile existing funded deals
Before step 2's gate goes live, count milestones with `status FUNDED|FROZEN` and no linked deliverable. That number is the blast radius. If non-trivial, they need either backfilled deliverable rows or explicit legacy exemption. **Do not deploy step 2 without this count.**

## 6. Verification required before this closes

- The step-1 test, revert-proven (revert the key, watch it fail).
- `mvn -o test` green — current baseline **1533** on `887b3ea`; the `1542` figure on `07586d6` is unverified and must not be used as a baseline.
- **Kabir red-team is mandatory** — money path, and step 2 is exactly the kind of boundary conditional that gets an edge wrong.
- **Neha re-runs the live chain** on the deployed build and sees a non-empty `GET /deals/{id}/deliverables`, then completes submit → approve → release.

## 7. What is NOT yet known

**Whether release actually succeeds today with zero deliverables was NOT tested** — the brand token expired before the call could be made. The fail-open code says it should. **Someone must run it**: `POST /wallet/escrow/release` with `Idempotency-Key` on milestone `01KYSBXN64NRH4QE6MMQ9MWG53` (funded ₹20,000, deal `01KYSBWFVTCEV5AS99E5889AS3`). Until that runs, "money is not stranded" is a code-reading, not a measurement.
