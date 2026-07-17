# D14 — Marketplace Invoicing & GST/TCS Spec

**Date:** 2026-07-15 · **Status:** `DRAFT — awaiting CFO alignment (Rohan) + CEO/Legal gate`
**Owners:** Priya (CTO — data model, architecture, sequencing) · Rohan (CFO — tax/GST/TCS policy, numbers, sign-off) · Swapnil (CEO + Legal — agent-vs-reseller ruling)
**Companion to:** `DESIGN-DECISIONS-MONEY-PIPELINE-MEERA-2026-07-14.md` (this is the new **D14** referenced there) and `wiki/tech/creator/10_CREATOR_PAYMENTS_SPEC.md`.
**Why this doc exists:** the platform generates only ONE financial document today (subscription invoice). A marketplace needs THREE. This spec turns that gap into a code-anchored data model + a sequenced work assignment — but **no implementation ticket opens until the business gate (§4) is signed.**

**Gate rule:** the entities in §5 are LOCKED against build until §4 D14-A (agent vs reseller) is `DECIDED`, because that ruling decides *whose name is the supplier of record* on Document #2 and #3. Building the schema before that call bakes in the wrong invoice direction.

---

## 1. Current state (code-verified)

| Fact | Anchor |
|---|---|
| Only invoice type that exists is the **subscription** invoice (Influora → Brand) | `influora-api/.../domain/entity/Invoice.java` — keyed on `subscription_id`, `workspace_id`, `period_start/end`; **no `campaign_id`, no creator, no direction field** |
| It is generated from a Razorpay `subscription.charged` webhook | `service/billing/InvoiceService.java` `generateInvoiceFromWebhook(...)` |
| Its PDF is rendered with OpenPDF and stored to R2 | `service/InvoicePdfService.java`; `Invoice.pdfR2Key` |
| Brand-side campaign fee is charged at publish (10% default) into the platform revenue wallet | `service/BrandCampaignFeeService.java` `chargeOnPublish(...)`, `TxnReferenceType.CAMPAIGN`, `WalletTransactionType.PLATFORM_FEE` |
| Creator-side fee is deducted at escrow release (15% default) | `service/PlatformFeeService.java` `split(...)` |
| Campaign money is held per campaign/collaboration/milestone | `domain/entity/EscrowHold.java` — `campaign_id`, `collaboration_id`, `milestone_id`, `amount DECIMAL(14,2)`, `currency` |
| Brand tax identity already captured | `Workspace.getGstin()`, `getBillingAddress()`, `getBillingEmail()` (used in `InvoicePdfService`) |
| **Creator tax identity NOT captured** | no creator GSTIN/PAN field exists → **new work** |

### ⚠️ Money-unit landmine (CTO flag)
`Invoice.amount` is an **`int` in paise** (`InvoicePdfService.formatAmount` divides by 100). Every other money surface in the platform — `EscrowHold.amount`, the ledger, `BrandCampaignFeeService` — is **`DECIMAL(14,2)` in rupees**. **The new invoice entities MUST follow the `DECIMAL(14,2)` rupee convention, not the subscription invoice's paise-int.** Do not copy `Invoice.java`'s money type. This is non-negotiable for ledger reconciliation.

---

## 2. The three documents (direction-corrected)

Swapnil's "two invoices, campaign one is brand→creator" is directionally wrong. **Money and paper move in opposite directions.** Correct model:

| # | Document | Direction (supplier → customer) | Supplier of record | Trigger | Status |
|---|---|---|---|---|---|
| 1 | **Subscription invoice** | Influora → Brand | Influora | `subscription.charged` webhook | ✅ Built |
| 2 | **Creator service invoice** | **Creator → Brand** | Creator (platform templates) | Escrow **release** on a collaboration | ❌ New |
| 3a | **Platform commission invoice (brand leg)** | Influora → Brand | Influora | Campaign **publish** fee charge | ❌ New |
| 3b | **Platform commission invoice (creator leg)** | Influora → Creator | Influora | Escrow **release** fee deduction | ❌ New |

> **Money flow:** Brand → escrow (Influora) → Creator net-of-fee.
> **Invoice flow (#2):** Creator → Brand. The creator is the legal supplier of the service; the brand is the customer. That Influora disburses the cash from escrow does not change who supplies the service.

3a and 3b are the *same commission*, invoiced to whichever party the take-rate is charged to. Whether we issue one combined commission invoice or two depends on the §4 ruling.

---

## 3. Rohan's CFO verdict (already on record, 2026-07-15) — inputs this spec must honor

- Count is **3 documents, not 2**. Do not collapse #2 and #3 — one is the creator's revenue, one is Influora's.
- Invoice #2 flows **Creator → Brand**, issued in the **creator's** name/GSTIN.
- Influora is likely an **Electronic Commerce Operator (ECO)** → **1% TCS under GST §52**, remitted via **GSTR-8** — a withholding + reporting obligation *separate from* invoicing.
- **Creator GST-registration trap (§24(x)):** creators selling *through* an ECO may need GST registration **regardless of the ₹20L threshold** → onboarding blocker, not a footnote.
- Influora's commission (#3) is Influora's own output supply — standard **18% GST**, invoiced by Influora.
- Influora **must generate** #1, #3, and the TCS/GSTR-8 statement; it **facilitates** #2 (issued in the creator's name).

---

## 4. 🚦 BUSINESS GATE — must be signed before ANY code (owners: CEO/CFO/Legal)

| ID | Decision | Owner | Why it blocks code |
|---|---|---|---|
| **D14-A** | **Agent/marketplace vs. principal/reseller?** Pure marketplace (creator = supplier of record on #2, TCS regime) OR reseller (Influora buys from creator, resells to brand → Influora on both invoice legs). | **Swapnil + Legal + Rohan** | Decides whose GSTIN goes on #2/#3. Wrong call = every invoice legally invalid. |
| **D14-B** | **Creator GST onboarding policy:** force GST registration at onboarding, restrict campaigns to GST-registered creators at launch, or support unregistered creators (composition/exempt handling). | **Rohan + Swapnil** | Determines whether the creator-tax-identity fields are mandatory and whether #2 can even be raised. |
| **D14-C** | **Statutory invoice numbering series** — GST requires a continuous, per-financial-year, per-document-type sequential series (not ULIDs). Format + reset policy. | **Rohan** | The `invoice_number` column + generator can't be built without the format ruling. |
| **D14-D** | **TCS mechanics** — confirm ECO status, 1% base (net taxable value), GSTR-8 cadence, and whether we withhold at release or report-only for v1. | **Rohan (+ CA)** | Decides whether release-time math changes and whether a `tcs_collected` ledger leg is needed. |
| **D14-E** | Combined vs. split commission invoice (one #3, or #3a + #3b). | **Rohan** | Entity count in §5. |

**None are CTO calls.** I will not open impl tickets until these are `DECIDED` in the log below.

---

## 5. Proposed data model (LOCKED against build until §4 signed)

Follows existing conventions: **ULID string PKs, `DECIMAL(14,2)` rupees, R2 PDF key, ownership-checked reads** (mirrors `InvoiceService.getInvoicePdf`).

### 5.1 `campaign_service_invoice` (Document #2 — Creator → Brand)
```
id                 CHAR(26)      -- ULID
invoice_number     VARCHAR(32)   -- statutory series, per D14-C (NOT the ULID)
collaboration_id   CHAR(26)      -- FK, the deal this invoices (EscrowHold.collaboration_id)
campaign_id        CHAR(26)      -- FK
escrow_hold_id     CHAR(26)      -- FK -> escrow_holds (the released hold)
creator_user_id    CHAR(26)      -- supplier of record (per D14-A)
brand_workspace_id CHAR(26)      -- customer
gross_amount       DECIMAL(14,2) -- creator's service value (pre platform fee)
currency           CHAR(3)       DEFAULT 'INR'
creator_gstin      VARCHAR(15)   -- nullable until D14-B; new creator-tax-identity
tcs_amount         DECIMAL(14,2) -- per D14-D, nullable if report-only v1
status             VARCHAR(20)   -- ISSUED / PAID (paid == escrow released)
issued_at          TIMESTAMP
pdf_r2_key         VARCHAR(500)
created_at         TIMESTAMP
```
**Trigger:** created inside the **escrow-release** transaction on a collaboration (the same place D6/`EscrowService.release` runs), so the invoice and the money movement are atomic.

### 5.2 `platform_commission_invoice` (Document #3 — Influora → Brand and/or Creator)
```
id, invoice_number, workspace_id (brand) / creator_user_id (counterparty per D14-E),
campaign_id, escrow_hold_id (nullable — brand leg is publish-time, no hold yet),
leg               VARCHAR(10)   -- BRAND / CREATOR
fee_bps_applied   INT           -- snapshot (mirrors D3 snapshot-at-fund principle)
commission_amount DECIMAL(14,2)
gst_amount        DECIMAL(14,2) -- 18% on Influora's fee
ledger_txn_id     CHAR(26)      -- the PLATFORM_FEE posting this invoices (traceability)
status, issued_at, pdf_r2_key, created_at
```
**Trigger (brand leg):** inside `BrandCampaignFeeService.chargeOnPublish` after the `PLATFORM_FEE` posting.
**Trigger (creator leg):** inside `PlatformFeeService` fee deduction at release.

### 5.3 Creator tax identity (new — required by D14-B)
Add `gstin`, `pan`, `tax_registration_status` to the creator profile / bank-account onboarding (`V47/V49` bank-account flow is the natural home). **Mandatory-or-nullable is decided by D14-B, not here.**

### 5.4 Migrations
Per `wiki/tech/adr-flyway-migration-versioning.md`: the codebase already hit a **V40/V41 collision** from concurrent V-numbering. Use the **timestamp format** (`V20260715xxxxxx__...`) for these to avoid a repeat, since multiple agents touch migrations. Three migrations: campaign_service_invoice, platform_commission_invoice, creator_tax_identity.

### 5.5 Rendering & storage — REUSE, don't reinvent
- New `CampaignServiceInvoicePdfService` + `CommissionInvoicePdfService` **mirror `InvoicePdfService`** (pure OpenPDF, `com.lowagie.text.*`) — same helpers, different fields (line items, GSTIN of both parties, TCS/GST breakup, HSN/SAC code if D14 requires).
- Store to R2 under new prefixes `campaign-invoices/`, `commission-invoices/` (mirrors `INVOICE_PDF_KEY_PREFIX`).
- On-demand render + presigned download, ownership-checked — **copy `InvoiceService.getInvoicePdf` verbatim as the pattern** (resolve → verify workspace/creator ownership → render).

---

## 6. Sequencing

- **Wave 0 (business):** D14-A → D14-B → D14-C/D/E signed. **Hard gate.** No code before this.
- **Wave 1 (creator tax identity):** §5.3 fields + onboarding capture. Unblocks #2.
- **Wave 2 (Document #2):** `campaign_service_invoice` entity/repo/service/renderer, wired into escrow release. **Blocked by D6** (approval-gates-payment) — release must actually fire first.
- **Wave 3 (Document #3):** commission invoice, wired into `BrandCampaignFeeService` + `PlatformFeeService`.
- **Wave 4 (compliance):** TCS ledger leg + GSTR-8 export (per D14-D), numbering-series generator (D14-C).

Dependency: **Wave 2 is blocked by D1 (escrow model) + D6 (release trigger)** from the money-pipeline doc — you cannot invoice a release that doesn't fire correctly yet.

---

## 7. 👥 Work assignment (owners + specific tasks)

| Employee | Role | Tasks | Blocked by |
|---|---|---|---|
| **Rohan** | CFO | Sign D14-A…E; supply GST/TCS numbers, numbering-series format, HSN/SAC codes; verify #2/#3 direction + amounts against ledger; final money-correctness sign-off | — (owns the gate) |
| **Swapnil + Legal** | CEO/Legal | Rule D14-A (agent vs reseller) + D14-B (creator GST policy) | — |
| **Vikram** | Backend | §5.1–5.4 entities/repos/migrations (timestamp format); `CampaignServiceInvoiceService` + `CommissionInvoiceService` (mirror `InvoiceService`); PDF renderers (mirror `InvoicePdfService`); wire triggers into `EscrowService.release`, `BrandCampaignFeeService`, `PlatformFeeService`; controllers with ownership checks | Wave 0 gate |
| **Ananya** | Frontend | Brand: commission-invoice list + download in billing settings (extend `useBilling`/`brand-billing-settings.tsx`); Creator: service-invoice list + download in creator earnings; creator tax-identity capture form | Vikram's API contract |
| **Kabir** | Security | Ownership-check review (no cross-workspace/cross-creator leakage — same class as `InvoiceService`'s check); GSTIN/PAN treated as PII; presigned-URL scoping; invoice-number enumeration | Vikram build |
| **Kavya** | QA | Standards + TECH-STACK compliance; money-unit correctness (DECIMAL(14,2), never paise-int); direction correctness on every doc; amounts reconcile to ledger postings | Vikram build |
| **Meera** | DB/DevOps + verify | Apply migrations locally; `mvn` compile/test; verify escrow-release → invoice-row → PDF end-to-end; report to `SHARED_CONTEXT.md` | Kavya pass |
| **Priya (me)** | CTO | This spec; lock entity design after D14-A; review Vikram's impl; sign architecture before Meera's final verify | — |

**Not involved:** Ash (no AI), Nisha/Ishaan/Zara/Aditya/Tejas (no content/marketing/SEO surface).

### Build Progress (2026-07-15)

Status from real build/verify results only. No projection.

| Task | Owner | Status |
|---|---|---|
| §5.1–5.4 entities/enums/repos (5 enums, 6 entities, 5 repos) | Vikram | ✅ DONE |
| 6 Flyway migrations (creator tax identity, campaign-service #2, commission #3 split legs, number sequences, HSN/SAC, subscription-GST backfill) | Vikram | ✅ DONE |
| `CampaignServiceInvoiceService` + `CommissionInvoiceService` (mirror `InvoiceService`) | Vikram | ✅ DONE |
| PDF renderers (`CampaignServiceInvoicePdfService`, `CommissionInvoicePdfService`) | Vikram | ✅ DONE |
| Triggers wired into `EscrowService.release`, `BrandCampaignFeeService`, `PlatformFeeService`; `InvoiceNumberService` + `HsnSacCodeService` + `GstSplitUtil` | Vikram | ✅ DONE |
| `CreatorInvoicingController` + `BrandInvoicingController` with ownership checks | Vikram | ✅ DONE |
| Brand commission-invoice (#2/#3a) list + PDF download (`useBilling`, `brand-billing-settings.tsx`) | Ananya | ✅ DONE |
| Creator service-invoice (#2/#3b) list + PDF download ("Invoices" tab, `creator-wallet.tsx`) | Ananya | ✅ DONE |
| Creator tax-identity capture form (`TaxIdentityForm.tsx`, regexes matched to backend `@Pattern`) | Ananya | ✅ DONE (FE) — ⚠️ blocked backend |
| FE build gates (`tsc --noEmit` + `vite build` clean) | Ananya | ✅ DONE |
| HIGH: `creator_invoice_code` collision fix — extract `CreatorInvoiceCodeService` (SecureRandom + retry, `REQUIRES_NEW`) | Meera (Kavya review) | ✅ DONE |
| Latent FK-collation bug in 4 D14 migrations (charset/collation) | Meera | ✅ DONE |
| `mvn` compile / build green | Meera | ✅ DONE |
| Escrow/invoice-adjacent tests (Escrow 7/7, Release 2/2, Dispute 5/5, BrandCampaignFee 6/6, PlatformFee 6/6, billing.InvoiceService 3/3) | Meera | ✅ DONE |
| Creator GSTIN/PAN submit endpoint (`creatorTaxIdentity.submit()` throws typed `NOT_IMPLEMENTED`; FE shows honest gap banner) | Vikram | ⛔ REMAINING (backend fast-follow) |
| Unit test for `CreatorInvoiceCodeService` collision-retry path (Swapnil CHANGES-REQUESTED — gate before merge) | Kavya/Vikram | ⛔ REMAINING |
| Ownership-check / PII / presigned-URL / enumeration security review | Kabir | ⛔ REMAINING (not yet reported) |
| Money-unit + direction + ledger-reconcile QA sign-off | Kavya | ⛔ REMAINING (not yet reported) |
| End-to-end escrow-release → invoice-row → PDF live verify | Meera | ⛔ REMAINING (not yet reported) |

**Notes:**
- `mvn -o test` overall = BUILD FAILURE (18 failures + 6 errors), but **identical before and after D14 changes** — pre-existing/unrelated: `DatabaseConstraintIntegrationTest` (legacy V54 FK-collation), `CreatorDeliverableServiceTest` (SSRF), `DealServiceTest`, `SubscriptionServiceTest`, `PortfolioServiceTest`. None reference D14 classes/migrations.
- No dedicated unit test yet exists for `CampaignServiceInvoiceService` or `CreatorInvoiceCodeService` (net-new + HIGH-fix code).
- **Merge gate:** the two ⛔ code items (GSTIN/PAN endpoint, collision-retry test) plus Kabir/Kavya/Meera sign-offs are open. Open findings: 3.

---

## 8. Open questions routed to Rohan (CFO alignment — keep in loop)

1. D14-C: exact `invoice_number` format + FY reset (e.g. `INF/2026-27/CMP/000001`)?
2. D14-D: for **v1**, do we *withhold* 1% TCS at release (needs a `tcs_collected` ledger leg + net-adjust to creator payout) or **report-only** (compute + record, disburse full, reconcile at filing)?
3. D14-E: one combined commission invoice per campaign, or split brand-leg (publish) + creator-leg (release)?
4. HSN/SAC code(s) for creator services vs. platform commission — needed on the PDF for a compliant tax invoice?
5. Does the subscription invoice (#1) also need GST treatment aligned to this (currently no GST breakup on it)?

---

## 9. Decision log

| # | Decision | Status | Owner | Decided | Notes |
|---|---|---|---|---|---|
| D14-A | Agent vs reseller | **DECIDED: marketplace/agent** | Swapnil (CEO) | 2026-07-15 | creator = supplier of record on #2; Influora issues commission + 1% TCS as ECO |
| D14-B | Creator GST onboarding policy | **DECIDED: build fields, enforcement = runtime toggle** | Swapnil | 2026-07-15 | schema captures GSTIN/PAN nullable; hard-gating creators pending Legal, not schema-blocking |
| D14-C | Invoice numbering series | **DECIDED: split per-GSTIN** | Rohan | 2026-07-15 | `INF/SUB\|CMB\|CMC/FY/seq`; #2 = per-creator series `<CreatorInvoiceCode>/FY/seq`, reset Apr 1. NOT a global counter |
| D14-D | TCS mechanics | **DECIDED: report-only v1** | Rohan (CA to confirm) | 2026-07-15 | compute+record 1%, disburse full net, true-up at GSTR-8. Platform carries liability at launch volume — CA sign-off pending |
| D14-E | Combined vs split commission invoice | **DECIDED: split** | Rohan | 2026-07-15 | 3a campaign-scoped @publish, 3b hold-scoped @release — different counterparty/txn/timing |

### Rohan's build-flags (folded into Vikram's ticket)
1. **All 3 escrow-release call sites** (`EscrowService` full/milestone/partial ~L315/473/589 → `deductAtRelease`) must fire #2/#3b — not just the main path.
2. **Invoice creation gated on `LedgerPostingResult`** (first-post-wins), never on "endpoint was called" — a retry must not double-issue a statutory invoice number.
3. **Cardinality:** 3a = 1-per-campaign @publish (`Campaign.getBudgetMax()` base); 3b + #2 = 1-per-escrow-hold @release. Do NOT model 3a/3b as a 1:1 pair.
4. **HSN/SAC** = configurable lookup, not hardcoded: #2 = SAC 998397, #3 = SAC 998599 (CA to confirm before live render).
5. **⚠️ Live gap pulled forward:** subscription invoice #1 (`Invoice.java`) has no `invoice_number`, no CGST/SGST/IGST breakup, no HSN — invoices already shipping to real brands are likely non-compliant. Added to Wave 4.

*No product code is modified by this doc — decision framework + assignment only. Companion to the money-pipeline doc; this is D14 there.*
