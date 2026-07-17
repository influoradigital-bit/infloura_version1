# Influora -- Brand Guide

Everything a brand needs to know about using Influora. Based on Platform Flow V2.2.

> Videos uploaded by creators are stored in Cloudflare and displayed within the platform.

---

## Table of Contents

1. [How It Works (6-Step Flow)](#1-how-it-works)
2. [Onboarding](#2-onboarding)
3. [Creating a Campaign](#3-creating-a-campaign)
4. [Reviewing Creator Bids](#4-reviewing-creator-bids)
5. [Deal Room & Negotiation](#5-deal-room--negotiation)
6. [Contracts](#6-contracts)
7. [Managing Deliverables & Revisions](#7-managing-deliverables--revisions)
8. [Attribution & Performance Tracking](#8-attribution--performance-tracking)
9. [Payments, Escrow & Fees](#9-payments-escrow--fees)
10. [Tax & Compliance](#10-tax--compliance)
11. [Ratings & Trust](#11-ratings--trust)
12. [Usage Rights](#12-usage-rights)
13. [Cancellation & Disputes](#13-cancellation--disputes)
14. [Campaign States](#14-campaign-states)
15. [Build Status -- What Exists Today](#15-build-status)

---

## 1. How It Works

The whole platform in 6 sentences:

> "I post a campaign, creators bid, I pick one, they make content, I approve it, they get paid. Influora handles the payment, tax, and tracking."

```
STEP 1        STEP 2        STEP 3        STEP 4        STEP 5        STEP 6
Brand         Creator       Brand         Creator       Brand         Creator
posts   --->  bids    --->  accepts --->  delivers --->  approves --->  gets
campaign                    bid           content                      paid
```

Everything else (revisions, disputes, cancellations) is edge-case handling, not the main flow.

---

## 2. Onboarding

### Steps (60-90 seconds)

```
Step 1: Email + OTP
Step 2: Company name + category
Step 3: Done -- can create campaigns immediately

GSTIN + PAN required only at first campaign launch, not at signup.
```

### What We Don't Ask At Signup

- No Aadhaar verification
- No DigiLocker
- No selfie/liveness check
- No full KYC upfront

### BUILD STATUS

| Feature | Status |
|---------|--------|
| Email + OTP login | BUILT (UI) |
| Company name + category | BUILT (UI) |
| GSTIN/PAN collection at campaign launch | BUILT (UI only, no real validation) |
| Brand verification badge | BUILT (UI) |

---

## 3. Creating a Campaign

### Campaign Setup Flow

```
Step 1: Basic Info
  - Campaign title, description, category
  - Target platforms (Instagram / YouTube)

Step 2: Budget & Timeline
  - Total budget (e.g. Rs 1,00,000)
  - Per-creator budget range
  - Campaign duration
  - Application deadline

Step 3: Deliverables
  - Platform + content type + quantity
  - Example: 2x Instagram Reels, 4x Stories

Step 4: Attribution (V2.2 spec)
  - Promo code setup (auto-generated per creator)
  - Tracking link with UTM
  - Self-reported impact option
  - Store integrations: COMING SOON (Shopify, WooCommerce, Razorpay)
  - Default: Manual CSV upload for order attribution

Step 5: Creator Requirements
  - Min followers, engagement rate
  - Niche, location, demographics

Step 6: Fund Escrow
  - Pay campaign budget + platform fee upfront
  - Razorpay payment gateway

Step 7: Review & Launch
```

### BUILD STATUS

| Feature | Status |
|---------|--------|
| Campaign creation wizard | BUILT (UI) |
| Budget & timeline settings | BUILT (UI) |
| Deliverable specification | BUILT (UI) |
| Attribution setup (promo codes, UTM links) | NOT BUILT -- spec only |
| Manual CSV upload for attribution | NOT BUILT -- spec only |
| Store integrations (Shopify etc.) | NOT BUILT -- marked Coming Soon in spec |
| Escrow funding via Razorpay | NOT BUILT -- UI simulated, no real Razorpay |
| Campaign state machine (9 states in code) | BUILT (UI) |

---

## 4. Reviewing Creator Bids

When creators bid on your campaign, you see them in the Bids tab.

### What Each Bid Shows

```
Creator Bid Card:
  - Profile picture, name, verified badge
  - Match score (e.g. 95%)
  - Followers count, engagement rate
  - Proposed rate (e.g. Rs 15,000)
  - Delivery timeline (e.g. 2 weeks)
  - Sample portfolio link
```

### Actions Per Bid

| Action | What Happens |
|--------|--------------|
| Accept | Lock escrow, move to contract |
| Counter | Propose different rate/terms |
| Message | Ask questions first |
| Decline | Reject the application |

### Accept Flow

```
[Click Accept]

Confirmation Dialog:
  Amount: Rs 12,000
  Deliverables: 2x Reel, 4x Story
  Warning: "Rs 12,000 will be locked in escrow
            until deliverables are approved."
  [Cancel]  [Confirm & Lock Escrow]
```

### BUILD STATUS

| Feature | Status |
|---------|--------|
| Bid listing with creator profiles | BUILT (UI) |
| Accept / Counter / Message / Decline buttons | BUILT (UI) |
| Accept confirmation with escrow lock dialog | BUILT (UI) |
| Counter offer dialog | BUILT (UI) |
| Match score display | BUILT (UI, hardcoded mock data) |
| Real escrow locking via Razorpay | NOT BUILT -- UI simulated |

---

## 5. Deal Room & Negotiation

Per V2.2 spec: **Single conversation surface per brand-creator-campaign combo.** No separate "chat" and "deal room."

### V2.2 Spec -- Message Types in Deal Room

| Type | Trigger | Status |
|------|---------|--------|
| Text message | User types | BUILT (basic UI) |
| Offer Card | "Send Offer" button | PARTIALLY BUILT -- UI exists but not structured cards |
| Counter Card | "Counter" on existing offer | PARTIALLY BUILT |
| Brief | Auto-pinned at top | NOT BUILT |
| Contract Card | Both accept terms | PARTIALLY BUILT -- contract generates but not inline card |
| Submission Card | Creator uploads content | NOT BUILT as inline card |
| Approval Card | Brand approves | NOT BUILT as inline card |
| Payout Card | Razorpay confirms | NOT BUILT |
| Performance Card | Day 7 performance window | NOT BUILT |
| File/Image upload | Upload | NOT BUILT in deal room |
| Voice note | Hold-to-record mobile | NOT BUILT |

### BUILD STATUS

| Feature | Status |
|---------|--------|
| Deal Room page with negotiation history | BUILT (UI) |
| Real-time chat (WebSocket) | NOT BUILT -- mock data |
| Structured offer/counter cards | NOT BUILT |
| Auto-pinned brief | NOT BUILT |
| Inline contract signing | NOT BUILT |
| Submission/approval/payout cards | NOT BUILT |
| Voice notes | NOT BUILT |
| WhatsApp/email auto-redaction | NOT BUILT |

---

## 6. Contracts

When a bid is accepted, a contract is auto-generated.

### Contract Contents (V2.2)

```
1. Parties (Brand + Creator)
2. Scope of Work (deliverables, platform, format)
3. Compensation (total + payment schedule)
4. Timeline (deadline, revision window)
5. Usage Rights (see Section 12)
6. Revision Policy (max 2 revisions)
7. Digital Signatures (both parties)
```

### Contract Generation Flow

```
Bid Accepted
  -> Step 1: Locking escrow (automated)
  -> Step 2: Generating contract (automated)
  -> Step 3: Setting up signatures (automated)
  -> Step 4: Ready for signing
  -> Navigates to /brand/contracts
```

### BUILD STATUS

| Feature | Status |
|---------|--------|
| Auto-generated contract after acceptance | BUILT (UI) |
| Contract terms display (scope, compensation, timeline) | BUILT (UI) |
| Digital signature canvas (draw signature) | BUILT (UI) |
| Contract statuses (pending, brand_signed, fully_executed) | BUILT (UI) |
| Usage rights clause in contract | BUILT (UI displays, hardcoded) |
| Legal PDF generation | NOT BUILT |
| Real e-signature (DocuSign/DigiLocker) | NOT BUILT -- canvas signature only |

---

## 7. Managing Deliverables & Revisions

### Deliverable Tracking

The Deliverables tab shows each content piece:

```
Deliverable Card:
  - Title, platform, content type
  - Status: Pending / In Progress / Submitted / Approved
  - Due date, submitted date
  - Preview thumbnail (videos stored in Cloudflare)
  - Revisions used: 0/2
  - Actions: [Approve] [Request Revision]
```

### V2.2 Revision Rules (LOCKED)

- **Max 2 revision rounds.** No third revision.
- **No refund** after content delivery.
- **Auto-approval at 72h** if brand takes no action.
- Each revision must be **structured** (specific timestamp, specific change, reference attached):

```
REQUEST REVISION

Issue 1:
  Location:       [0:08 in Reel]
  Change needed:  [Center the product in frame]
  Reference:      [Upload]

Revisions used: 0/2
```

### Escalation

If creator disputes a revision request:
```
Creator clicks "Dispute Revision"
  -> Ops reviews within 48h
  -> Ops sides with brand: creator must revise
  -> Ops sides with creator: brand must approve OR pay 50% kill fee
  -> Compromise: specific adjustments only
```

### BUILD STATUS

| Feature | Status |
|---------|--------|
| Deliverable list with statuses | BUILT (UI) |
| Approve / Request Revision buttons | BUILT (UI) |
| Revision counter (0/2) | BUILT (UI) |
| Preview thumbnails | BUILT (UI, mock images) |
| Video playback from Cloudflare | NOT BUILT -- no real Cloudflare integration |
| Structured revision form (timestamp + specific change) | NOT BUILT -- free text only |
| Auto-approval at 72h | NOT BUILT |
| Dispute revision flow | NOT BUILT |
| Ops mediation interface | NOT BUILT |

---

## 8. Attribution & Performance Tracking

V2.2 defines a 3-layer attribution model:

### Layer 1: Promo Code (Primary)

```
- Auto-generated per creator per campaign: PRIYA10, RAJ15
- Discount: 10-15% (brand configurable)
- New customer only, one-time use
- Tracked via brand's weekly CSV upload (until store integrations live)
```

### Layer 2: Branded Short Link with UTM (Secondary)

```
- Format: yourbrand.in/c/priya
- UTMs baked in: utm_source=influora&utm_medium=influencer&utm_content=priya_001
- Tracks: clicks, conversions (real-time)
- Detects fraud: high clicks + zero conversions
```

### Layer 3: Self-Report (Fallback)

```
- Creator submits screenshots of DMs, comments, story replies
- Brand survey post-campaign (optional)
- Captures word-of-mouth conversions
```

### Data Freshness (shown to brand)

```
Link clicks:        Real-time
Code redemptions:   Weekly (when brand uploads CSV)
Self-report:        On submission
```

### Brand-Side Performance View (V2.2 spec)

```
Campaign Overview:
  Revenue    | Orders  | CAC    | ROAS
  Rs 2,84K   | 312     | Rs 160 | 5.68x

Per Creator:
  @priya_styles   Verified   4.8 stars
  Code redemptions:   42    Rs 38,400 (last CSV: 2d ago)
  Link clicks:        1,240 -> 18 conversions (real-time)
  Self-reported:      "120+ DMs about product"
  Total attributed:   Rs 46,200  (ROAS: 5.78x)
  Value Score:        A+
```

### BUILD STATUS

| Feature | Status |
|---------|--------|
| Promo code generation per creator | NOT BUILT |
| UTM tracking link generation | NOT BUILT |
| Manual CSV upload for attribution | NOT BUILT |
| Self-report submission | NOT BUILT |
| Campaign performance dashboard (Revenue, ROAS, CAC) | NOT BUILT |
| Per-creator attribution breakdown | NOT BUILT |
| Data freshness labeling | NOT BUILT |
| Store integrations (Shopify, WooCommerce) | NOT BUILT -- spec says Coming Soon |

---

## 9. Payments, Escrow & Fees

### Fee Structure (V2.2 LOCKED)

```
BRAND SIDE:
  - Unverified creator deal:  12% platform fee
  - Verified creator deal:    10% platform fee
  - Fee charged ON TOP of agreed creator amount
  - 18% GST applied on platform fee only
  - First 2 deals FREE (Rs 0 platform fee)

CREATOR SIDE:
  - 0% platform fee
  - Receives 100% of agreed amount
  - Minus 1% TDS (govt mandated, u/s 194O)
```

### Example: Verified Creator, Rs 5,000 Deal

```
Brand pays Influora:    Rs 5,590
  Creator amount:       Rs 5,000
  Platform fee (10%):     Rs 500
  GST on fee (18%):        Rs 90

Creator receives:       Rs 4,950
  Agreed amount:        Rs 5,000
  TDS deducted (1%):      -Rs 50
```

### Payment Flow

```
Month 1 (Manual Phase):
  Brand pays via Razorpay -> Influora bank account
    -> Brand approves content
    -> Influora finance processes payout (T+2)
    -> Razorpay Payouts API -> Creator UPI/Bank (instant)
    -> Form 16A + TDS records updated

Month 2+ (Razorpay Route):
  Brand pays via Razorpay Route -> Escrow account
    -> Brand approves
    -> Route API splits:
        Creator (amount minus TDS)
        Influora (10-12% fee + GST)
        Govt (TDS, GST)
    -> Creator gets T+0
```

### Payout Schedule (V2.2)

- Deals under Rs 25K: **Single payout on approval. No advance.**
- Deals over Rs 25K (future): 30% on contract sign, 70% on approval.

### BUILD STATUS

| Feature | Status |
|---------|--------|
| Brand wallet page | BUILT (UI) |
| Escrow funding UI | BUILT (UI, simulated) |
| Fee calculation display (10%/12%) | BUILT (UI shows TDS/escrow references) |
| Razorpay payment gateway | NOT BUILT -- no real integration |
| Razorpay Payouts API | NOT BUILT |
| Razorpay Route (Month 2+) | NOT BUILT |
| Single payout on approval | NOT BUILT -- UI shows 50/50 split which contradicts V2.2 spec |
| First 2 deals free promo | NOT BUILT |

**SPEC vs CODE MISMATCH**: The current UI shows a 50/50 payment split (50% on signing, 50% on approval). V2.2 spec says **single payout on approval for deals under Rs 25K**. The 50/50 split is only for deals over Rs 25K (future). This needs to be corrected.

---

## 10. Tax & Compliance

### Invoice Structure -- Pure Agent (Rule 33 CGST)

```
INFLUORA DIGITAL PVT LTD
GSTIN: XXXXXXXXXXXXX

Description                          Amount
Influencer marketing services        Rs 5,000
(Creator: @handle, Campaign: X)
[Pure Agent -- disbursed to creator]

Platform service fee                   Rs 500
SGST 9%                                 Rs 45
CGST 9%                                 Rs 45
TOTAL                                Rs 5,590
```

GST applies ONLY on the platform fee, not the full amount. Pure Agent model.

### TDS Layering

```
Layer 1: Brand -> Influora
  Brand deducts 2% u/s 194C

Layer 2: Influora -> Creator
  Influora deducts 1% u/s 194O
  Creator receives Form 16A quarterly
```

### BUILD STATUS

| Feature | Status |
|---------|--------|
| GSTIN / PAN collection in onboarding | BUILT (UI fields exist) |
| Pure Agent invoice generation | NOT BUILT |
| TDS deduction system | NOT BUILT -- UI mentions TDS but no real calculation |
| Form 16A generation | NOT BUILT |
| Daily reconciliation | NOT BUILT |

---

## 11. Ratings & Trust

V2.2 specifies **reciprocal star ratings** after every completed deal.

### Brand Rates Creator

```
Overall:           stars (1-5)
Content quality:   stars (1-5)
Communication:     stars (1-5)
On-time delivery:  stars (1-5)
Brief adherence:   stars (1-5)
Public comment:    (optional)
```

### Creator Rates Brand

```
Overall:                stars (1-5)
Brief clarity:          stars (1-5)
Payment speed:          stars (1-5)
Approval responsiveness:stars (1-5)
Respectfulness:         stars (1-5)
Public comment:         (optional)
```

### Rules

- Ratings visible AFTER both parties rate (prevents retaliation)
- Cannot edit after 7 days
- Minimum 3 ratings before public display
- Comments moderated by ops

### BUILD STATUS

| Feature | Status |
|---------|--------|
| Star rating UI component | NOT BUILT |
| Brand rates creator form | NOT BUILT |
| Creator rates brand form | NOT BUILT |
| Rating display on profiles | NOT BUILT -- hardcoded mock ratings shown |
| Double-blind reveal system | NOT BUILT |
| Comment moderation | NOT BUILT |

---

## 12. Usage Rights

### Default Rights (Auto-Applied to All Contracts)

```
Channels:           Creator's own social accounts only
Brand may:          Repost to brand's own social accounts
Paid amplification: NOT ALLOWED without separate agreement
Duration:           6 months from publish date
Geography:          India only
Modifications:      Crop only, no recutting/re-editing
```

### Extended Rights (Campaign-Configurable Add-On)

```
[ ] Allow paid amplification (whitelisting/dark posts)  +30% to creator fee
[ ] Use beyond 6 months                                 +20% per 6 months
[ ] Use outside India                                   +25% to creator fee
[ ] Recutting/re-editing rights                          +15% to creator fee
```

### BUILD STATUS

| Feature | Status |
|---------|--------|
| Usage rights display in contract | BUILT (UI shows hardcoded text) |
| Extended rights configuration during campaign setup | NOT BUILT |
| Rights premium calculation (+30%, +20%, etc.) | NOT BUILT |
| Rights violation detection | NOT BUILT |
| Rights as pinned card in Deal Room | NOT BUILT |

---

## 13. Cancellation & Disputes

### If Brand Cancels (After Bid Accepted)

```
Within 24h:                       Full refund, no kill fee
24h -> before shoot:              25% kill fee to creator
Shoot started/concept submitted:  50% kill fee to creator
Content submitted:                No cancellation -- use revision/dispute
```

### Cancellation UI (V2.2 spec)

```
Cancel Summer Collection deal?

Current state: Content in shoot
Kill fee:      50% (Rs 2,500)
Your refund:   Rs 2,500

Creator @priya will be notified and paid the kill fee.

[Back]             [Confirm Cancel]
```

### Dispute Escalation

```
Unresolved issue
  -> Ops reviews within 48h
  -> Binding decision:
      Side with brand -> creator must revise
      Side with creator -> brand approves OR pays 50% kill fee
      Compromise -> specific adjustments
```

### BUILD STATUS

| Feature | Status |
|---------|--------|
| Decline/reject bid UI | BUILT (UI) |
| Tiered kill fee system (25%/50%) | NOT BUILT |
| Cancellation dialog with kill fee calculation | NOT BUILT |
| Dispute escalation flow | NOT BUILT |
| Ops mediation panel | NOT BUILT |

---

## 14. Campaign States

### V2.2 Spec: 6 States

```
DRAFT -> OPEN -> MATCHED -> IN_PROGRESS -> REVIEW -> COMPLETED

Side-states: CANCELLED | DISPUTED | EXPIRED
```

### Current Code: 9 States

```
DRAFT -> FUNDED -> LIVE -> MATCHED -> NEGOTIATING -> CONTRACTED -> IN_PRODUCTION -> IN_REVIEW -> SETTLED
```

**SPEC vs CODE MISMATCH**: V2.2 spec defines 6 states. The code implements 9 states. The extra states (FUNDED, NEGOTIATING, CONTRACTED) add granularity but diverge from spec.

| V2.2 Spec State | Code State(s) | Notes |
|------------------|---------------|-------|
| DRAFT | DRAFT | Match |
| (none) | FUNDED | Extra in code |
| OPEN | LIVE | Renamed |
| MATCHED | MATCHED + NEGOTIATING + CONTRACTED | Split into 3 |
| IN_PROGRESS | IN_PRODUCTION | Renamed |
| REVIEW | IN_REVIEW | Renamed |
| COMPLETED | SETTLED | Renamed |
| CANCELLED | (exists in code) | Match |
| DISPUTED | NOT BUILT | Missing from code |
| EXPIRED | NOT BUILT | Missing from code |

---

## 15. Build Status -- Full Summary

### BUILT (UI working, mock data)

- Brand onboarding (email + OTP + company info)
- Campaign creation wizard
- Campaign listing and dashboard with stats
- Creator discovery and search with filters
- Bid review (accept/counter/message/decline)
- Deal Room with basic negotiation flow
- Contract auto-generation after acceptance
- Digital signature (canvas-based)
- Contract management page with statuses
- Deliverable tracking with approve/revise
- Brand wallet page with transaction history
- Campaign state machine (9 states)
- Creator profile viewing
- Escrow UI (simulated, no real money)

### NOT BUILT (V2.2 spec features missing)

- Razorpay payment gateway integration
- Razorpay Payouts API (creator payments)
- Razorpay Route (Month 2+ escrow)
- Promo code generation and tracking
- UTM tracking link generation
- Manual CSV upload for attribution
- Campaign performance dashboard (Revenue, ROAS, CAC)
- Reciprocal star rating system (both sides rate)
- Structured revision form (timestamp + specific change)
- Auto-approval at 72h of no brand action
- Usage rights configuration in campaign setup
- Extended rights premium calculation
- Tiered cancellation kill fees (25%/50%)
- Dispute escalation and ops mediation
- Creator ghosting / no-show auto-enforcement
- Strike system (1st/2nd/3rd ghost)
- Pure Agent invoice generation
- TDS deduction and Form 16A
- Notification matrix (in-app, email, WhatsApp, SMS, push)
- Notification settings page
- Deal Room structured cards (Offer, Counter, Contract, Submission, Approval, Payout, Performance)
- Voice notes in Deal Room
- Real-time WebSocket messaging
- Verified vs Unverified creator tracks (10% vs 12% fee)
- Instagram OAuth (Meta Graph API)
- YouTube OAuth integration
- Cloudflare video storage and playback
- Platform policies pages (ToS, Creator Policy, Brand Policy, Community Guidelines, Privacy Policy)
- Enforcement ladder (warning -> suspension -> ban)
- Operational guardrails (Rs 20K deal cap, Rs 2L total cap)
- DISPUTED and EXPIRED campaign side-states

### SPEC vs CODE MISMATCHES

| Area | V2.2 Spec Says | Code Does |
|------|-----------------|-----------|
| Campaign states | 6 states | 9 states |
| Payment schedule (under Rs 25K) | Single payout on approval | Shows 50/50 split |
| Platform fee | 10% verified / 12% unverified | Shows flat 10% |
| Creator fee | 0% | Some UI text mentions platform fees to creators |
| First 2 deals | Free for brands | Not implemented |
| Payout speed | T+3 unverified / T+1 verified | Not differentiated |

---

*Based on: PLATFORM_FLOW_V2_2.md (May 2026)*
*Last compared: May 2026*
