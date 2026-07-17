# Influora Digital Private Limited — Policy Inventory

> **Legal entity:** Influora Digital Private Limited
> **Business model:** Escrow-protected influencer-marketing marketplace (India) — connects brands and creators, holds funds in escrow (via a licensed payment aggregator), generates contracts, handles TDS on payouts.
> **Owner of this doc:** Swapnil (CEO) + Tejas (CMO, content) | **Prepared:** 2026-07-13
> **Status:** Planning list for approval → then draft as v0 pages.

> ⚠️ **LEGAL DISCLAIMER:** This is a business inventory of the policies the platform needs, with the Indian regulatory basis for each. It is **not legal advice.** Because Influora handles escrow funds, KYC, and tax deduction, every public policy below — especially the money/KYC/tax ones — **must be reviewed and signed off by a qualified Indian lawyer (and a CA for tax)** before publishing. We draft v0 templates; counsel validates.

---

## A. MANDATORY PUBLIC POLICIES (legally required in India for this model)

| # | Policy | Public route | Legal basis (India) | Priority |
|---|--------|-------------|---------------------|----------|
| A1 | **Terms of Service / Terms of Use** | `/terms` | Indian Contract Act 1872; IT Act 2000 | 🔴 P0 |
| A2 | **Privacy Policy** | `/privacy` | **DPDP Act 2023**; IT Act 2000 + SPDI Rules 2011 | 🔴 P0 |
| A3 | **Grievance Redressal Policy + Grievance Officer** | `/grievance` (or in Terms) | **IT Rules 2021** (Intermediary Guidelines); **Consumer Protection (E-Commerce) Rules 2020** — *named Grievance Officer with contact + resolution SLA is mandatory* | 🔴 P0 |
| A4 | **Refund & Cancellation Policy** | `/refund-policy` | Consumer Protection (E-Commerce) Rules 2020 | 🔴 P0 |
| A5 | **Cookie Policy / Consent** | `/cookies` (+ banner) | DPDP Act 2023 (consent); IT Act | 🟠 P1 |
| A6 | **Pricing, Fees & Charges disclosure** | `/pricing` + Terms | Consumer Protection (E-Commerce) Rules 2020 (transparent pricing) | 🔴 P0 |
| A7 | **Company & Contact details** (registered name, CIN, address, email) | Footer + `/contact` | Companies Act 2013; E-Commerce Rules (seller/operator identity) | 🔴 P0 |

---

## B. PLATFORM / MARKETPLACE-SPECIFIC POLICIES (core to how Influora works)

| # | Policy | Public route | Why we need it | Priority |
|---|--------|-------------|----------------|----------|
| B1 | **Escrow & Payments Policy** | `/policies/escrow-payments` | Explains how funds are held/released, that escrow is operated via a **licensed payment aggregator (Razorpay)** — Influora does **not** pool user funds in its own account (RBI PA/PG Guidelines). Release triggers, timelines. | 🔴 P0 |
| B2 | **KYC / AML Policy** | `/policies/kyc` | PMLA; RBI KYC Master Directions; required before payouts. What docs (PAN/Aadhaar/GST), why, retention. | 🔴 P0 |
| B3 | **TDS & Tax Policy** | `/tds` | Income Tax Act — **Section 194-O** (e-commerce operator TDS on participant payments) + 194H/194J context; GST on platform fee. Gross→TDS→Net, certificates. *CA sign-off required.* | 🔴 P0 |
| B4 | **Creator Agreement / Creator Terms** | `/guidelines/creators` | Eligibility, rate cards, delivery obligations, disclosure duty, payout terms, conduct. | 🟠 P1 |
| B5 | **Brand Agreement / Brand Terms** | `/guidelines/brands` | Campaign obligations, fair-pricing, funding escrow, approval SLA, prohibited products. | 🟠 P1 |
| B6 | **Dispute Resolution Policy** | `/policies/disputes` | How escrow disputes are opened, frozen, mediated, resolved; timelines; outcomes. | 🟠 P1 |
| B7 | **Advertising Disclosure Policy (ASCI)** | `/policies/disclosure` | **ASCI Influencer Guidelines** — mandatory #ad/#sponsored/#collab labels; platform's stance and enforcement. | 🟠 P1 |
| B8 | **Content Moderation & Acceptable Use Policy** | `/policies/acceptable-use` | Prohibited content/products, fake engagement, enforcement ladder (warn→suspend→ban). Ties to IT Rules 2021. | 🟠 P1 |
| B9 | **Chargeback & Payment Dispute Policy** | in B1 or `/policies/chargebacks` | How card chargebacks/failed payouts are handled on a money platform. | 🟡 P2 |

---

## C. RECOMMENDED / TRUST POLICIES (not strictly mandatory, strong risk + trust value)

| # | Policy | Public route | Value | Priority |
|---|--------|-------------|-------|----------|
| C1 | **Intellectual Property / Content Rights & Takedown** | `/policies/ip` | Usage rights on creator content; copyright takedown process. High risk area for UGC. | 🟠 P1 |
| C2 | **Data Retention & Deletion Policy** | in Privacy or `/policies/data-retention` | DPDP Act — how long we keep KYC/PII, deletion on request. | 🟠 P1 |
| C3 | **Information Security Policy (public statement)** | `/policies/security` | Encryption (AES-256-GCM already used), access controls; trust signal. | 🟡 P2 |
| C4 | **Anti-Fraud / Fake Engagement Policy** | `/policies/anti-fraud` | Bought followers, fake deliverables, collusion. | 🟡 P2 |
| C5 | **Accessibility Statement** | `/accessibility` | WCAG-AA commitment (we already build to it). | 🟡 P2 |
| C6 | **Shipping / Product-in-Barter Policy** | `/policies/shipping` | Only if brands ship physical products to creators for barter deals. | 🟡 P2 (conditional) |

---

## D. INTERNAL / OPERATIONAL POLICIES (NOT public pages — governance docs)

| # | Policy | Where it lives | Why |
|---|--------|---------------|-----|
| D1 | Internal Data Protection & Breach-Response Policy | `wiki/policies/internal/` | DPDP Act — breach notification duty; who does what. |
| D2 | Employee/Contractor Confidentiality & Acceptable Use | internal | Protect user PII/money data. |
| D3 | Vendor / Sub-processor Register | internal | DPDP — list of processors (Razorpay, Cloudflare R2, Anthropic/Gemini, etc.). |
| D4 | Records Retention & Audit Policy | internal | Tax + payments audit trail. |
| D5 | Business Continuity / Incident Response | internal | Money platform uptime + incident handling. |

---

## MANDATORY-FIRST BUILD ORDER (public pages)

**Wave 1 (P0 — legal minimum to operate honestly):**
A1 Terms · A2 Privacy · A3 Grievance + Officer · A4 Refund · A6 Pricing disclosure · A7 Company details · B1 Escrow/Payments · B2 KYC · B3 TDS

**Wave 2 (P1):**
A5 Cookies · B4 Creator Terms · B5 Brand Terms · B6 Disputes · B7 ASCI Disclosure · B8 Acceptable Use · C1 IP/Takedown · C2 Data Retention

**Wave 3 (P2):**
B9 Chargebacks · C3 Security · C4 Anti-Fraud · C5 Accessibility · C6 Shipping (if applicable)

---

## KEY COMPLIANCE CALLOUTS (do not skip)

1. **Named Grievance Officer is legally mandatory** (IT Rules 2021 + E-Commerce Rules). We need a real name, email, and address published, with a resolution SLA (typically acknowledge ≤48h, resolve ≤1 month). — *Swapnil to appoint.*
2. **Escrow wording must be accurate:** Influora uses a **licensed payment aggregator's** escrow — it must NOT claim to operate its own escrow/hold funds in its own account (RBI PA/PG Guidelines). Legal must vet this language.
3. **TDS Section 194-O** likely applies to Influora as an e-commerce operator (TDS on payments to creators). — *CA must confirm rate/applicability before B3 publishes.*
4. **DPDP Act 2023 consent + Data Fiduciary duties** — Privacy Policy must reflect the new Indian law, not just old IT Act/SPDI language.
5. All published legal pages ship as **v0 templates with a visible "pending legal review" note** until counsel signs off (per CEO-DECISIONS.md), and P0 legal pages are `noindex` until validated.

---

## OWNERSHIP

- **Swapnil (CEO):** appoint Grievance Officer; engage legal counsel + CA; final approval.
- **Tejas (CMO):** own policy content strategy, plain-language brand voice, consistency across pages.
- **Ishaan (writer):** draft v0 policy copy from approved templates.
- **Ananya (FE):** build policy pages (routes, `<Seo noindex>`, clean legal-doc layout via `/impeccable`).
- **Vikram (BE):** wire any backend the Grievance form / data-deletion request needs.
- **Legal counsel + CA (external):** validate all P0 money/KYC/tax/privacy policies before go-live.
