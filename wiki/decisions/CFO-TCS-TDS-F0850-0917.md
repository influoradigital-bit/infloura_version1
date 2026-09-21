# F-0850 — TCS/TDS: what's printed vs deducted vs remitted, CA question list, exposure, fix plan

**Author:** Rohan (CFO) · **Date:** 2026-09-17 · **Ledger:** F-0850 (related: F-0847, CommissionInvoiceService GST)
**Status:** Analysis + recommendation. No code changed. Not tax advice — see §2.

---

## 1. What the code actually does today (file:line, only files I opened)

### 1a. "TCS @ 1%" on the creator service invoice (Doc#2)

- `influora-api/src/main/java/com/influora/service/CampaignServiceInvoiceService.java:207-209`:
  ```java
  // D14-D: TCS is REPORT-ONLY v1 — compute + record the 1% ECO liability, disburse the full
  // net to the creator, true up at GSTR-8. Does NOT change the release payout math above.
  BigDecimal tcsAmount = grossAmount.multiply(new BigDecimal("0.01")).setScale(2, RoundingMode.HALF_UP);
  ```
  The result is stored on the invoice row (`.tcsAmount(tcsAmount)`, line 223) and — per the class's own comment — **never posted to the ledger, never deducted from anything, never remitted anywhere.** There is no `TCS` wallet transaction type, no debit leg, no filing artifact. I grepped the whole `influora-api/src/main/java` tree for `GSTR-8`, `GSTR8`, `194-O`, `194O`, `206AA`, `206AB` and the **only** hits are this file and `CampaignServiceInvoicePdfService.java` (the PDF renderer that prints the line) — confirming there is no downstream accrual, collection, or filing logic anywhere else in the codebase.
- **Printed:** a "TCS @ 1%" line on the PDF invoice, plus a `tcsAmount` value in the DB row.
- **Deducted:** nothing — the creator receives the full net (gross − platform fee), per the comment itself.
- **Remitted:** nothing — no GSTR-8 integration exists in this repo.

### 1b. TDS on creator payouts (manual payout console)

- `influora-api/src/main/java/com/influora/service/admin/AdminFinanceService.java:179-184`: `tdsAmount` is an **admin-typed, optional** parameter to `recordManualPayout`, validated only for range (`0 <= tdsAmount <= amount`) — no computation, no rate, no rule.
- `AdminFinanceService.java:216-226`: the ledger post debits the **full `amount`** from the creator's wallet to the clearing wallet — `tdsAmount` plays no part in what's actually moved:
  ```java
  ledgerService.post(
      wallet.getId(), clearingWallet.getId(), amount, wallet.getCurrency(),
      WalletTransactionType.WITHDRAWAL, TxnReferenceType.MANUAL, payoutId,
      "Manual payout recorded by admin", idempotencyKey, bankReference);
  ```
- `influora-api/src/main/java/com/influora/domain/entity/Payout.java:84-85`: `tdsAmount` is just a `@Column(precision=12,scale=2)` — persisted metadata, nothing computes or enforces it.
- **Printed:** nothing automatic — it only exists if a human admin typed a number into the manual-payout form.
- **Deducted:** nothing systematic — no automatic payout path (the primary rails, e.g. RazorpayX via `PayoutReconciliationService`) touches TDS at all; only the manual-bank-transfer admin console has the field, and it's advisory.
- **Remitted:** nothing — no challan/26Q/Form 16A generation exists anywhere in `influora-api/src/main/java` (confirmed by the same grep above).

### 1c. Related — brand commission invoice GST (F-0847, same family)

- `influora-api/src/main/java/com/influora/service/CommissionInvoiceService.java:50,117,192`: `GST_RATE = 0.18` is applied on top of the platform fee (`feeAmount.multiply(GST_RATE)`) for both the brand leg (line 117) and creator leg (line 192), and the invoice is stamped `MarketplaceInvoiceStatus.PAID` (lines 131, 207).
- `influora-api/src/main/java/com/influora/service/BrandCampaignFeeService.java:181-227`: the actual money movement only debits `fee` (the platform commission itself) via `ledgerService.post(...)` — **no GST amount is ever debited from or credited to any wallet.** The invoice's `gstAmount` field is pure display math with no corresponding ledger leg.
- Same shape as 1a: a tax figure is printed and marked PAID, but nothing moves. This is the ledger F-0847 item, explicitly "waiting on a CA" per your brief — I'm not re-opening it, just noting the pattern is identical to 1a/1b: **compute-and-print without collect-and-remit.**

### 1d. What already exists and is honest about the gap

- `src/content/legal/tds-policy.md:1-3,29-31`: a user-facing TDS policy page already exists, marked **"v0 DRAFT — PENDING INDIAN LEGAL COUNSEL/CA REVIEW. NOT LEGALLY BINDING. Ships `noindex`"**, and explicitly refuses to publish a TDS rate: `[TDS RATE — pending CA confirmation]` (line 29). This is the right pattern — publish nothing numeric until confirmed. **The invoice PDF (1a) does the opposite**: it prints a hard "1%" number with no such caveat, and it's a statutory-numbered document a brand/creator can rely on, not a marketing page.

### 1e. Platform fee/commission baseline (for the exposure formula in §3)

- `influora-api/src/main/java/com/influora/service/BrandCampaignFeeService.java:27-28,84`: brand-side platform fee = 10% (1000 bps) default, 7% (700 bps) on an active Pro plan, charged on `campaign.getBudgetMax()` at publish.
- `influora-api/src/main/java/com/influora/service/PlatformFeeService.java:50-57`: creator-side fee = `grossAmount * feeBps / 10000`, `net = grossAmount - fee`. Rate itself (`PlatformFeeConfig.defaultFeeBps`) is DB-config, not hardcoded in this file — I did not open `PlatformFeeConfig` so I won't cite a number for it here.

---

## 2. Questions for the CA — not advice, not a settled position

I am not a qualified tax advisor and none of the below is tax advice. These are the specific questions a Chartered Accountant needs to answer in writing before Influora computes, withholds, or remits anything automatically. Do not treat any rate below as confirmed law — Finance Act rates change, and `tds-policy.md` is correct to refuse to publish one until confirmed.

1. **Is Influora an "e-commerce operator" under Sec 52 CGST Act, liable to collect TCS on the creator-side supply (the "service" a creator renders to a brand, settled through Influora's ledger)?** If yes: is the correct TCS rate currently 0.5% CGST + 0.5% SGST (net 1%, matching the code's assumption) or 0.5% IGST for inter-state supplies — and is it computed on the **gross** service value or on the **net-of-commission** value? (The code applies 1% to `grossAmount`, i.e. `hold.getAmount()` pre-platform-fee — confirm that base is correct.)
2. **Does Sec 194-O TDS (e-commerce operator on "e-commerce participant" gross sale) apply to creator payouts, and at what rate?** The code comments and the drafted `tds-policy.md` both assume 194-O; your brief flags the rate as "1%, now 0.1% from Oct 2024" — **that must be CA-confirmed, not assumed**, and the CA should also rule out whether Influora's structure (holding brand funds on an internal ledger, not a traditional marketplace "sale") even qualifies as 194-O rather than falling under **194J (professional/technical fees, typically 10%)** or **194C (contract payments, typically 1-2%)** depending on how a creator's deliverable is legally characterized.
3. **What are the current thresholds for each of the above** (194-O has a small-seller/individual threshold; 194J/194C have their own annual thresholds) — below which no TDS applies at all, so the exposure formula in §3 isn't overstated for small creators?
4. **Creators without PAN — does Sec 206AA (generic no-PAN higher-TDS-rate rule) or Sec 206AB (higher rate for non-filers of ITR) apply, and at what rate?** `tds-policy.md:33-38` already tells creators "a significantly higher rate applies" without a number — the CA needs to give the actual higher rate so the engineering build in §4c can branch on PAN-on-file.
5. **Is the platform fee (10%/7%) GST-inclusive or GST-exclusive?** This is the F-0847 question directly — it decides whether `CommissionInvoiceService`'s `GST_RATE.multiply(feeAmount)` (add-on-top, current code) is even the right formula, or whether GST should be backed out of the fee instead.
6. Given TCS (Sec 52) and TDS (Sec 194-O or 194J/194C) can both apply to the same creator-payout flow, **is there any set-off/adjustment between them**, or are they fully independent government remittances?

---

## 3. Money exposure — formula, not a number (I cannot see production volumes)

I have no access to the live database or actual transaction volumes; every number below is a variable to be filled in once real figures and CA-confirmed rates are available. **X_TCS and X_TDS below are unconfirmed** — see §2.

```
Per ₹1,00,000 (1 lakh) of creator payouts released, per period:

  Unremitted TCS liability  = ₹1,00,000 × X_TCS      (X_TCS is unconfirmed; code currently assumes 1%,
                                                        i.e. ₹1,000/lakh, but never actually sets this
                                                        aside — see §1a)

  Unwithheld TDS liability  = ₹1,00,000 × X_TDS      (X_TDS unconfirmed; possible bands per §2.2:
                                                        0.1%–1% if 194-O applies (₹100–₹1,000/lakh),
                                                        or up to 10% if 194J applies (₹10,000/lakh),
                                                        or a 206AA/206AB no-PAN rate on top for
                                                        creators without PAN on file)

  Total monthly exposure    = (Total ₹ of creator payouts released that month / 1,00,000)
                               × (X_TCS + X_TDS)
```

Because neither figure is currently withheld from the creator or reserved by the platform, **this is not a "who pays" allocation question — it is money Influora would owe the government out of its own funds at reconciliation**, on top of whatever penalty/interest applies to a late or missed TCS/TDS obligation (also a CA question, not covered above — add it to the list if the CA rules TCS/TDS applies at all).

Swapnil/the CA can plug the real monthly creator-payout total into the formula above once transaction volume is available; I don't have DB access to supply it myself.

---

## 4. The fix, in order

### 4a. Stop printing today (no CA ruling needed — this is just removing a false statement)
- The "TCS @ 1%" line and `tcsAmount` value on the Doc#2 creator service invoice PDF (driven by `CampaignServiceInvoiceService.java:207-223` and rendered by `CampaignServiceInvoicePdfService.java`) states a specific, confident number for something that is not actually collected or remitted. This is the same shape of problem as the F-0847 GST line, and the fix is the same class of action: stop asserting a false fact on a statutory document.
- Nothing needs CA sign-off to **remove or caveat a false line already proven wrong by the code itself** — that's a documentation-accuracy fix, not a tax-policy decision.

### 4b. What the CA must rule (§2, all six questions) before any of the below is built
- Applicability (1, 2), section (2), threshold (3), no-PAN rate (4), fee tax-inclusivity (5, closes F-0847), TCS/TDS interaction (6).

### 4c. What engineering must build once ruled — in dependency order
1. **Compute**: real TCS/TDS calculators keyed off the CA-confirmed section, rate, and threshold (replacing the hardcoded `0.01` in `CampaignServiceInvoiceService.java:209` and the admin-typed `tdsAmount` in `AdminFinanceService`), including the 206AA/206AB no-PAN branch.
2. **Withhold**: actually reduce the amount that reaches the creator's wallet by the computed TDS (currently `PlatformFeeService.split` only ever subtracts the platform fee, never a tax withholding — `PlatformFeeService.java:50-57` would need a second deduction leg), and set aside the computed TCS as a real ledger liability rather than a display-only invoice field.
3. **Record**: a dedicated ledger/wallet transaction type for each (e.g. `TCS_LIABILITY`, `TDS_WITHHELD`) distinct from `PLATFORM_FEE`, so the amounts are auditable and reconcilable — not folded into the existing platform-fee posting.
4. **Remit**: an actual government-remittance integration or, at minimum, an exportable ledger of what's owed per period (GSTR-8 for TCS, 26Q for TDS) — none of this exists in the codebase today (confirmed by the grep in §1).
5. **Certify**: Form 16A generation for creators (already promised in `tds-policy.md:25,49-51`) and whatever TCS-side certificate GST law requires — currently nothing generates either.
6. Apply the same fix to the F-0847 GST-on-commission gap once the CA answers question 5 — same withhold/record/remit shape, smaller scope (it's the platform's own GST, not a third party's).

---

## 5. Immediate recommendation

**Yes — remove the false "TCS @ 1%" line from the invoice now, pending the CA ruling.**

Reasoning: the line is not a placeholder or an estimate labeled as such — it's a specific "1%" printed on a numbered statutory PDF (Doc#2), handed to both the brand and the creator, that the code's own comment (`CampaignServiceInvoiceService.java:207-208`) admits is "REPORT-ONLY" and changes no actual payout math. A creator or brand reading that invoice has no way to know the 1% is unconfirmed, uncollected, and unremitted — it reads as a completed tax action, not a projection. The project already has the right template for this exact situation sitting one file away: `tds-policy.md` ships `noindex` and literally writes `[TDS RATE — pending CA confirmation]` instead of guessing. The TCS invoice line should get the same treatment — removed, or replaced with neutral language ("TCS treatment pending confirmation") that doesn't assert a number — until Question 1 in §2 is answered. This costs nothing, requires no CA input to do safely (it only removes an unsupported claim, it doesn't add one), and closes the most CA/audit-visible instance of the false-tax-line pattern immediately while 4b/4c run in parallel.
