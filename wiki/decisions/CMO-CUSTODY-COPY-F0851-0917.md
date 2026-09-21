# F-0851: RBI Payment Aggregator Custody Claims — Copy Audit & Replacements

**CMO:** Tejas  
**Date:** 2026-09-17  
**Ledger:** F-0851  
**Business Context (Swapnil):** We don't have RBI Payment Aggregator authorization yet because transaction volume is small. Copy must describe what is true TODAY — funds are secured on the platform, not held by a regulated third party.

---

## Summary

**Total instances found:** 12  
**Legal documents requiring counsel sign-off:** 4  
**Marketing/product pages:** 7  
**Blog content:** 1  

**Most visible instance (seen by most brands):**  
`src/pages/how-it-works-brands.tsx:525` — Large hero section on the How It Works page: *"Payments are held with a licensed, RBI-authorized Payment Aggregator from the moment the contract is signed."*

**Recommended replacement:**  
*"Payments are secured on the platform from the moment the contract is signed and released only when you approve the work."*

---

## All Instances — Marketing & Product Pages

| File:Line | Current Text | Replacement | Legal Doc? |
|-----------|--------------|-------------|------------|
| `src/pages/how-it-works-brands.tsx:525` | "Payments are held with a licensed, RBI-authorized Payment Aggregator from the moment the contract is signed." | "Payments are secured on the platform from the moment the contract is signed and released only when you approve the work." | N |
| `src/pages/how-it-works-brands.tsx:96` | "The full amount is deposited with a licensed, RBI-authorized Payment Aggregator before the creator starts. You are not paying an advance into a DM." | "The full amount is secured on the platform before the creator starts. You are not paying an advance into a DM." | N |
| `src/pages/how-it-works-creators.tsx:89` | "Before you shoot anything, the full amount is deposited with a licensed, RBI-authorized Payment Aggregator. You see 'Payment secured' on the invite." | "Before you shoot anything, the full amount is secured on the platform. You see 'Payment secured' on the invite." | N |
| `src/pages/about.tsx:96` | "Funds are secured with a licensed, RBI-authorized Payment Aggregator before filming starts, and payout follows the milestone written into the contract — not a follow-up message." | "Funds are secured on the platform before filming starts, and payout follows the milestone written into the contract — not a follow-up message." | N |
| `src/pages/features/deal-room.tsx:100` | "The brand deposits the full deal amount with a licensed, RBI-authorized Payment Aggregator before the creator starts producing content." | "The brand deposits the full deal amount into Secure Payments before the creator starts producing content." | N |
| `src/components/site/trust-items.ts:17` | "Payments held by a licensed gateway" | "Funds secured before work starts" | N |
| `src/pages/features/secure-payments.tsx:62` | "Payment protection means your money is held by a neutral third party — Influora — until both sides of a deal have done their part. The brand deposits the deal amount up front; Influora holds it; the creator delivers; the brand approves; the payment releases the payment." | "Secure Payments means your money is protected on the platform until both sides of a deal have done their part. The brand deposits the deal amount up front; Influora secures it; the creator delivers; the brand approves; and only then does the payment release." | N |

---

## Legal Documents (Require Swapnil/Counsel Sign-Off Before Changes)

| File:Line | Current Text | Replacement | Legal Doc? |
|-----------|--------------|-------------|------------|
| `src/content/legal/terms-of-service.md:24` | "Fund the Campaign into **payment protection** through a licensed, RBI-authorized Payment Aggregator" | "Fund the Campaign into **Secure Payments** on the platform" | **Y** |
| `src/content/legal/privacy-policy.md:52` | "**Payment Aggregator** — a licensed, RBI-authorized partner that processes payment protection funding and Payouts. They receive only what's needed to process the transaction." | "**Payment processor** — a licensed partner that processes transactions. They receive only what's needed to process the transaction." | **Y** |
| `src/content/legal/escrow-and-refund-policy.md:4` | "We hold Campaign funds safely in payment protection through a licensed, RBI-authorized Payment Aggregator. Funds move only through payment protection release or a dispute-resolution outcome — there are no informal or on-demand refunds outside that process." | "We hold Campaign funds safely on the platform through Secure Payments. Funds move only when you approve work or through a dispute-resolution outcome — there are no informal or on-demand refunds outside that process." | **Y** |
| `src/content/legal/escrow-and-refund-policy.md:13` | "When a brand funds a Campaign, the money does not go to Influora and does not go directly to the creator. It is held in **payment protection** by a licensed, RBI-authorized Payment Aggregator, in line with RBI's Payment Aggregator / Payment Gateway guidelines. Influora never pools Campaign funds in its own bank account." | "When a brand funds a Campaign, the money does not go directly to the creator. It is held securely on the platform until the work is approved. Your payment is protected and released only when both sides fulfill their obligations." | **Y** |

---

## Blog Content

| File:Line | Current Text | Replacement | Legal Doc? |
|-----------|--------------|-------------|------------|
| `src/content/blog/what-is-payment-protection-in-influencer-marketing.md:17` | "Payment protection in influencer marketing is a payment arrangement where a brand's money is held by a neutral third party — not the brand, not the creator — from the moment a deal is signed until the creator delivers content the brand approves." | "Secure Payments in influencer marketing is a payment arrangement where a brand's money is protected on the platform — not released to the creator — from the moment a deal is signed until the creator delivers content the brand approves." | N |

---

## User-Visible "Escrow" Instances (Banned Word - 2026-09-02 Ruling)

**No user-visible "escrow" instances found in current live copy.**  

The policy file is titled "Payment protection & Refund Policy" in its content (line 0), so the body text is compliant. The **filename** `escrow-and-refund-policy.md` generates a URL slug that may contain "escrow" — this should be verified by Ananya and potentially renamed to `secure-payments-and-refund-policy.md` with appropriate redirect handling.

---

## Brand Voice Notes (For Ananya's Implementation)

**Tone:** Confident, protective, matter-of-fact. We protect your money — that's a product mechanism, not a regulatory credential.

**Vocabulary:**
- ✅ USE: "secured on the platform", "Secure Payments", "protected until you approve", "funds secured before work starts"
- ❌ AVOID: "RBI-authorized", "Payment Aggregator", "licensed gateway", "regulated", "held by a third party", "escrow"

**Why this works:**  
Brands care that their money is safe and released only when they approve work. The mechanism (platform ledger vs licensed PA) is an implementation detail. "Secured on the platform" is honest about what happens today and still sounds protective.

---

## Next Steps

1. **Swapnil reviews legal document changes** (4 instances) — these need counsel/CA sign-off
2. **Tejas approves marketing copy replacements** (7 instances + 1 blog)
3. **Ananya implements approved changes**
4. **Ananya checks URL slug** for `escrow-and-refund-policy.md` — rename to `secure-payments-and-refund-policy.md` if the slug is user-visible
5. **Close F-0851** when all changes are live

---

## Attribution

Authored by: Tejas (CMO)  
Business ruling: Swapnil (CEO)  
Implementation: Ananya (Frontend Developer)  
Legal review required: Swapnil + Counsel
