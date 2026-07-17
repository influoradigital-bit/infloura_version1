# CREATOR IMPLEMENTATION — Master Plan

> **Approved by:** Swapnil (CEO)  
> **Architecture by:** Priya (CTO)  
> **Security by:** Kabir  
> **QA by:** Kavya  
> **Date:** 2026-07-07

---

## Creator Journey Overview

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         CREATOR LIFECYCLE                                    │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  1. SIGNUP              2. PROFILE              3. CONNECT                   │
│  ┌──────────┐          ┌──────────┐          ┌──────────┐                   │
│  │ Email/   │    →     │ Bio,     │    →     │ Instagram│                   │
│  │ Phone    │          │ Niche,   │          │ YouTube  │                   │
│  │ OTP      │          │ Rates    │          │ OAuth    │                   │
│  └──────────┘          └──────────┘          └──────────┘                   │
│                                                                              │
│  4. DISCOVERY           5. CAMPAIGNS            6. BIDS                      │
│  ┌──────────┐          ┌──────────┐          ┌──────────┐                   │
│  │ Brand    │    →     │ Browse   │    →     │ Apply/   │                   │
│  │ Finds    │          │ Open     │          │ Counter  │                   │
│  │ Creator  │          │ Campaigns│          │ Negotiate│                   │
│  └──────────┘          └──────────┘          └──────────┘                   │
│                                                                              │
│  7. CONTRACT            8. CHAT                 9. DELIVERABLES              │
│  ┌──────────┐          ┌──────────┐          ┌──────────┐                   │
│  │ Review   │    →     │ AI Chat  │    →     │ Submit   │                   │
│  │ Sign     │          │ + Brand  │          │ Content  │                   │
│  │ Escrow   │          │ Messages │          │ Metrics  │                   │
│  └──────────┘          └──────────┘          └──────────┘                   │
│                                                                              │
│  10. APPROVAL           11. PAYMENT            12. ANALYTICS                 │
│  ┌──────────┐          ┌──────────┐          ┌──────────┐                   │
│  │ Brand    │    →     │ Escrow   │    →     │ Track    │                   │
│  │ Reviews  │          │ Release  │          │ Growth   │                   │
│  │ Approves │          │ to Wallet│          │ Earnings │                   │
│  └──────────┘          └──────────┘          └──────────┘                   │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Spec Files in This Folder

| File | Purpose | Owner |
|------|---------|-------|
| `01_CREATOR_AUTH_SPEC.md` | Signup, login, OTP, password reset | Vikram |
| `02_CREATOR_PROFILE_SPEC.md` | Profile setup, niche, rates, media kit | Vikram + Ananya |
| `03_CREATOR_OAUTH_CONNECT_SPEC.md` | Instagram/YouTube/Facebook OAuth | Vikram + Ananya |
| `04_CREATOR_DISCOVERY_SPEC.md` | How brands find creators, search, filters | Vikram + Ananya |
| `05_CREATOR_CAMPAIGNS_SPEC.md` | Browse campaigns, apply, campaign cards | Vikram + Ananya |
| `06_CREATOR_BIDS_SPEC.md` | Bid submission, counter-offers, negotiation | Vikram + Ananya |
| `07_CREATOR_CONTRACTS_SPEC.md` | Contract review, e-sign, milestones | Vikram + Ananya |
| `08_CREATOR_CHAT_SPEC.md` | AI chat (Meera), brand messaging, notifications | Vikram + Ananya |
| `09_CREATOR_DELIVERABLES_SPEC.md` | Submit content, metrics reporting, proof uploads | Vikram + Ananya |
| `10_CREATOR_PAYMENTS_SPEC.md` | Wallet, escrow release, withdrawals, affiliate earnings | Vikram + Ananya |
| `11_CREATOR_ANALYTICS_SPEC.md` | Growth tracking, AI coach, performance insights | Vikram + Ananya |
| `12_CREATOR_SECURITY_SPEC.md` | All security requirements for creator flows | Kabir |
| `13_CREATOR_QA_SPEC.md` | Test plan for all creator features | Kavya |

---

## Existing Backend Support (Already Built)

From `influora-api` audit:

| Entity | Status | Notes |
|--------|--------|-------|
| `User` (creator type) | ✅ Done | `UserType.CREATOR` |
| `CreatorProfile` | ✅ Done | Basic fields |
| `Collaboration` | ✅ Done | Brand-creator link |
| `Contract` | ✅ Done | PDF generation |
| `PaymentMilestone` | ✅ Done | Milestone tracking |
| `EscrowHold` | ✅ Done | Payment escrow |
| `Wallet` + `WalletTransaction` | ✅ Done | Double-entry ledger |
| `AiConversation` + `AiMessage` | ✅ Done | Meera chat |
| `DeliverableMetric` | ✅ Done | Self-reported metrics |
| `Notification` | ✅ Done | Push/email notifications |

---

## What Needs to Be Built

### Backend (Vikram)

| Feature | Status | Priority |
|---------|--------|----------|
| Creator signup flow | ~80% | P0 |
| Creator profile endpoints | ~70% | P0 |
| Campaign browse/apply | ~60% | P0 |
| Bid submission/counter | ~50% | P0 |
| Creator contract signing | ~80% | P0 |
| Deliverable submission | ~70% | P0 |
| Creator wallet/withdrawal | ~90% | P1 |
| Affiliate earnings tracking | 0% | P1 |
| Creator growth AI endpoints | 0% | P2 |

### Frontend (Ananya)

| Feature | Status | Priority |
|---------|--------|----------|
| Creator signup pages | ~60% | P0 |
| Creator dashboard | ~50% | P0 |
| Profile editor | ~40% | P0 |
| Campaign browser | ~30% | P0 |
| Bid submission UI | ~20% | P0 |
| Contract signing UI | ~60% | P0 |
| Deliverable upload UI | ~50% | P0 |
| Wallet/earnings UI | ~40% | P1 |
| Growth analytics UI | 0% | P2 |

---

## Sprint Schedule (4 Weeks)

### Week 1: Auth + Profile + OAuth
- Creator signup/login (Vikram)
- Profile setup flow (Vikram + Ananya)
- Instagram/YouTube OAuth connect (Vikram + Ananya)
- Security review (Kabir)

### Week 2: Campaigns + Bids
- Campaign browser (Vikram + Ananya)
- Bid submission flow (Vikram + Ananya)
- Counter-offer system (Vikram)
- Negotiation UI (Ananya)

### Week 3: Contracts + Chat + Deliverables
- Contract review/sign (Vikram + Ananya)
- AI chat integration (Vikram)
- Deliverable submission (Vikram + Ananya)
- Metrics reporting (Vikram + Ananya)

### Week 4: Payments + Analytics
- Wallet UI (Ananya)
- Withdrawal flow (Vikram)
- Affiliate earnings (Vikram + Ananya)
- Growth analytics (Vikram + Ananya)
- Full QA pass (Kavya)
- Security audit (Kabir)

---

## Team Responsibilities

| Team Member | Role in Creator Build |
|-------------|----------------------|
| **Swapnil** | Approve specs, final sign-off |
| **Priya** | Architecture, code review |
| **Vikram** | Backend APIs, services |
| **Ananya** | Frontend components, pages |
| **Kabir** | Security review all flows |
| **Kavya** | QA test plans, coverage |
| **Meera** | DevOps, deployment |
| **Arjun** | Task orchestration |
| **Tejas** | Creator-facing copy/UX |
| **Ishaan** | Help docs, onboarding content |
| **Aditya** | Creator profile SEO |
| **Rohan** | Payment flow cost tracking |
| **Tara** | Progress reporting |

---

## Success Criteria

**End of Week 4:**
1. Creator can signup → setup profile → connect Instagram
2. Creator can browse campaigns → submit bid → negotiate
3. Creator can sign contract → chat with brand → submit deliverable
4. Creator can track earnings → request withdrawal
5. All security tests passing (Kabir approved)
6. 80%+ test coverage (Kavya approved)
7. Build green, zero console errors (Meera verified)

---

## Platform Fee / Revenue Model (LOCKED 2026-07-07)

Influora takes a **platform commission from the creator**, deducted automatically at
escrow-release time (creator wallet only ever shows **net** earnings):

- **Default fee: 15%** (stored as `1500` basis points).
- **Admin-configurable** at three levels — global default → plan/tier → per-creator
  override (most specific wins). Ops can run promos, tier perks, or negotiate
  enterprise rates with **no code change**.
- **Hard ceiling 30%**; above that needs a Swapnil-signed flag.
- **Every change is audit-logged** (who, old→new, reason) and **never retroactive** —
  the rate is frozen onto each transaction at release. Fee is double-entry booked to
  Influora's revenue ledger.

Full detail: `10_CREATOR_PAYMENTS_SPEC.md` §1A, §3.5b, §5.0, §7A.

Example: ₹10,000 milestone → ₹1,500 platform fee → **₹8,500 to creator**.

---

## Links to Detail Specs

See files `01_` through `13_` in this folder for complete implementation details.
