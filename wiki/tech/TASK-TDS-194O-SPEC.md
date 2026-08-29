# TASK — TDS withholding on creator payouts (§194-O)

**Status:** ⛔ NOT STARTED — blocked on Swapnil sign-off + CA review
**Author:** Priya Sharma (CTO) · 2026-08-28
**Excluded from:** FIX-WAVE-0828 (see `TASK-FIX-WAVE-0828-PRIYA.md` for why)
**Blocks:** automated creator payouts at scale. Does **not** block money-in or the manual payout rail.

---

## Why this is not a bug fix

An audit row reading "TDS: missing" understates it. There is no withholding logic anywhere in the
automated payout path — this is an unbuilt subsystem, and the cost of getting it wrong is not a
stack trace. Wrong withholding means wrong amounts remitted against real PANs, discovered at
assessment, owed by Influora with interest and penalty. It needs a Chartered Accountant's sign-off
on the rules before an engineer writes the first line, not after.

## Current state (verified in code, 2026-08-28)

| Fact | Evidence |
|---|---|
| Automated payouts withhold nothing | `PayoutService.java:433-562`, `WalletService.doProcessWithdrawal` — full net amount paid |
| Escrow release deducts platform commission only | `PlatformFeeService.deductAtRelease` |
| `Payout.tdsAmount` exists but is never computed | `domain/entity/Payout.java:80-85` (V71 migration) |
| It is admin-typed on the manual rail | `AdminFinanceService.java:179-183` — "TDS is optional (null = not applied)" |
| The codebase says so itself | `web/dto/money/MoneyDtos.java:149-150`; echoed at `src/lib/api.ts:2876-2877` |
| PAN/GSTIN captured but only "for later reconciliation" | `Workspace.java:326`, `WalletTopUpService.java:144` |
| **PAN is stored plaintext** | `CreatorTaxIdentityService.java:26-40` — documented accepted gap |

Nothing exists: no rate selection, no Form 16A, no threshold aggregation, no quarterly return data.

## Scope

### 1. Rules to confirm with the CA before coding — do not infer these from blogs

- §194-O applicability to this marketplace model, and the rate (the statutory rate has changed
  since introduction — confirm the rate effective for the launch financial year, do not hardcode a
  remembered value).
- The **no-PAN / invalid-PAN penal rate** and how PAN validity is established.
- The **annual threshold** below which no withholding applies for an individual creator, and
  whether it resets per financial year.
- Interaction with GST already handled elsewhere in the invoice chain (Doc#1/#2/#3) — TDS is
  computed on a base that must be defined precisely relative to those documents.
- Whether withholding applies at **escrow release** (when the creator's entitlement crystallises)
  or at **payout** (when money leaves). These are different moments in this architecture and the
  answer determines the whole design.

### 2. Engineering work, once the rules are fixed

- **Encrypt PAN at rest first.** `CreatorTaxIdentityService` stores it plaintext today. TDS makes
  PAN load-bearing, which makes the existing gap unacceptable. Reuse `CreatorBankPiiCipher`'s
  established AES-GCM pattern (`CreatorBankAccountService.java:64-68`). This is a prerequisite,
  not a follow-up.
- A withholding calculator with the rate table as **data, not literals**, versioned by effective
  date — rates change by Finance Act and a hardcoded rate becomes silently wrong on 1 April.
- Financial-year aggregation per creator to evaluate the threshold, including payouts already made
  on the manual admin rail (otherwise the threshold is under-counted from day one).
- Withholding posted as a **distinct ledger entry**, not a subtraction. The double-entry ledger is
  the strongest thing in this codebase; TDS must be a first-class posting so the remittance
  liability is queryable and reconcilable, not implied by a smaller payout.
- Backfill/reconciliation for payouts made on the manual rail before this ships.
- Form 16A generation and quarterly return data export.
- Creator-facing disclosure: the creator must see gross, TDS withheld, and net **before** confirming
  a withdrawal, and be able to retrieve the certificate afterwards.

### 3. Tests that must exist

Threshold boundary (just under / exactly at / just over), PAN-present vs. absent rate selection,
financial-year rollover, rate change across an effective date, idempotency under the existing
payout retry paths (`PayoutReconciliationService`, `PayoutOrphanedDebitSweepJob`), and a
reconciliation test proving ledger TDS postings sum to the remittance total.

## Interim posture (in force until this ships)

Money-in live; **automated payouts stay off** (`VITE_PAYOUTS_ENABLED=false` — already the shipped
default at `src/lib/api.ts:105`, `Dockerfile:58`, `publish-images.yml:228`). Creators are paid on
the manual admin rail (`AdminFinanceController.java:103`), where a human enters the withheld amount
and a CA supervises remittance. Volume-limited, but correct — and it is the current default, so no
change is needed to adopt it.

**Do not flip `VITE_PAYOUTS_ENABLED=true` until this task is DONE.** CI already guards the
provisioning half of this (`publish-images.yml:181` fails a build enabling payouts against a
`REPLACE_ME` RazorpayX account), but nothing in CI knows about TDS. That guard is not a substitute
for this ruling.

## Sign-off required

| Who | For what |
|---|---|
| Swapnil (CEO) | Accepting manual-rail-only payouts as the launch posture, and the cost of the CA engagement |
| Chartered Accountant | The rule set in §1 — in writing, before code |
| Priya (CTO) | Architecture review, specifically the ledger-posting design and the rate-table versioning |
