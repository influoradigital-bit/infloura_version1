# Influora -- Creator Guide

Everything a creator needs to know about using Influora. Based on Platform Flow V2.2.

> Videos you upload are stored in Cloudflare and displayed within the platform.

---

## Table of Contents

1. [How It Works (6-Step Flow)](#1-how-it-works)
2. [Onboarding & Verification](#2-onboarding--verification)
3. [Verified vs Unverified Tracks](#3-verified-vs-unverified-tracks)
4. [Finding & Bidding on Campaigns](#4-finding--bidding-on-campaigns)
5. [Managing Your Inbox](#5-managing-your-inbox)
6. [Deal Room & Negotiation](#6-deal-room--negotiation)
7. [Contracts & Signing](#7-contracts--signing)
8. [Submitting Deliverables](#8-submitting-deliverables)
9. [Revisions (Max 2)](#9-revisions)
10. [Getting Paid](#10-getting-paid)
11. [Attribution & Your Performance](#11-attribution--your-performance)
12. [Ratings](#12-ratings)
13. [Usage Rights -- Protect Your Content](#13-usage-rights)
14. [Cancellation & What Happens](#14-cancellation--what-happens)
15. [Ghosting & No-Show Policy](#15-ghosting--no-show-policy)
16. [Platform Policies](#16-platform-policies)
17. [Build Status -- What Exists Today](#17-build-status)

---

## 1. How It Works

The whole platform in 6 sentences:

> "I see open campaigns, I bid, brand picks me, I make content, brand approves, I get paid in my UPI. Influora handles the rest."

```
STEP 1        STEP 2        STEP 3        STEP 4        STEP 5        STEP 6
Brand         You           Brand         You           Brand         You
posts   --->  bid    --->   accepts --->  deliver --->  approves --->  get
campaign                    your bid      content                     paid
```

That is the core loop. Everything else is detail on top of this.

---

## 2. Onboarding & Verification

### Steps (60-90 seconds)

```
Step 1: Phone + OTP
Step 2: Choose verification path:
          Connect Instagram (OAuth)  -> Verified track
          Upload analytics screenshot -> Unverified track
Step 3: Pick niches (max 3)
Step 4: Set rate range per format
Step 5: Done -- can bid on campaigns
```

### What Influora Does NOT Ask

- No Aadhaar verification
- No DigiLocker
- No selfie/liveness check
- No bank details at signup

### What Is Required Later

- **PAN** -- required at first withdrawal (TDS compliance)
- **UPI or bank account** -- required at first withdrawal
- **T&C acceptance** -- 18+, disclose paid promotions, no fake followers

Why minimal upfront: ~30% of signups never reach first payout. KYC at withdrawal saves you time. Those who earn are motivated to complete it.

### BUILD STATUS

| Feature | Status |
|---------|--------|
| Phone + OTP login | BUILT (UI) |
| Niche selection | BUILT (UI) |
| Rate range per format | BUILT (UI) |
| Profile setup (name, bio, location) | BUILT (UI) |
| Social account connection UI | BUILT (UI) |
| Instagram OAuth (Meta Graph API) | NOT BUILT -- UI exists but no real OAuth |
| YouTube OAuth | NOT BUILT |
| Analytics screenshot upload + OCR | NOT BUILT |
| PAN collection at withdrawal | BUILT (UI field exists) |
| UPI/bank at withdrawal | BUILT (UI field exists) |

---

## 3. Verified vs Unverified Tracks

Two paths, two trust levels, two fee structures. Both fully functional.

### TRACK A: Unverified (Manual Upload)

```
How:      Upload screenshot of Instagram/YouTube analytics
Badge:    "Self-reported" (orange/yellow)
Payout:   T+3 days after approval
Brand fee: 12% (brand pays, not you)
Limits:
  - Must re-upload screenshot every 60 days
  - First 3 deals reviewed by Influora ops
  - No Premium Brand tier deals (Month 6+)
```

### TRACK B: Verified (OAuth Connected)

```
How:      Connect Instagram via Meta Graph API OAuth
Badge:    "Verified" (blue tick)
Payout:   T+1 day after approval
Brand fee: 10% (brand pays, not you)
Benefits:
  - Priority placement in brand search
  - Direct invites from brands
  - Real-time analytics auto-synced (every 7 days)
  - Audience demographics auto-pulled
  - No ops review on first deals
```

### Track Comparison

| Feature | Unverified | Verified |
|---------|------------|----------|
| Badge | "Self-reported" | Blue tick |
| Brand fee (you don't pay this) | 12% | 10% |
| Payout speed | T+3 | T+1 |
| Direct invites from brands | No | Yes |
| Search priority | Lower | Higher |
| Audience data | Manual screenshot | API-verified, real-time |
| First 3 deals | Ops reviewed | Auto-approved |
| Screenshot refresh | Every 60 days | N/A (auto-sync) |

### The Upgrade Ladder (Example)

```
Day 1:   You join as Unverified -> upload screenshot -> bid on Rs 3K deal
Day 7:   Brand pays Influora 12% on your deal
Day 14:  You deliver -> get paid T+3
Day 20:  Want better placement -> read about Verified track
Day 21:  Switch IG to Creator account -> OAuth connect
Day 21:  Verified badge -> brand fee drops to 10% -> priority in search
```

The 2% fee difference is real economic pressure -- brands prefer verified creators, and you get more deals after switching.

### Anti-Fraud on Unverified Track

```
1. OCR extraction from screenshot -- flags mismatched stats
2. Cross-check against public IG profile
3. 20% tolerance threshold -> ops review flag
4. Visible "Self-reported" badge -- brands choose their own risk
5. First 3 deals reviewed by ops
6. Fake analytics = permanent ban + payout forfeit on pending deals
7. 60-day re-upload requirement
```

### BUILD STATUS

| Feature | Status |
|---------|--------|
| Verified/Unverified badge display | BUILT (UI shows badges) |
| Instagram OAuth flow | NOT BUILT -- no real Meta Graph API |
| Screenshot upload for analytics | NOT BUILT |
| OCR extraction from screenshots | NOT BUILT |
| Cross-check against public profile | NOT BUILT |
| 60-day re-upload enforcement | NOT BUILT |
| Differentiated payout speeds (T+1 vs T+3) | NOT BUILT |
| Differentiated brand fees (10% vs 12%) | NOT BUILT -- flat 10% in code |

---

## 4. Finding & Bidding on Campaigns

### Browse Marketplace

Open campaigns appear in the Opportunities tab of your inbox.

### What You See Per Campaign

```
Campaign: Summer Collection Launch
  Brand: Nykaa Fashion (Verified)
  Category: Fashion & Lifestyle
  Budget: Rs 15,000 - Rs 25,000
  Deliverables: 2 Reels + 4 Stories
  Deadline: June 15, 2026
  Applications: 45 creators applied
  Match Score: 92%
```

### Submitting Your Bid

```
Your Application:
  Your Rate:             Rs [20,000]
  Estimated Delivery:    [2 weeks]
  Why You're Perfect:    [free text pitch]
  Sample Work:           [attach portfolio]

  [Cancel]  [Submit Application]
```

### Bidding Tips

- Price competitively -- research market rates for your niche
- Personalize your pitch -- show you understand the brand
- Share relevant work -- attach similar content you have made
- Be realistic with timelines
- Mention unique angles you can bring

### BUILD STATUS

| Feature | Status |
|---------|--------|
| Campaign listing / opportunities tab | BUILT (UI) |
| Campaign detail view | BUILT (UI) |
| Application form (rate, message, portfolio) | BUILT (UI) |
| Match score display | BUILT (UI, hardcoded mock) |
| Filtering (niche, budget, platform, duration) | BUILT (UI) |
| Real match score calculation | NOT BUILT |

---

## 5. Managing Your Inbox

Your inbox (`/creator/inbox`) has two sections:

### Proposals Tab -- Brands Reaching Out to You

```
Proposal from Nykaa Fashion:
  Campaign: Summer Collection Launch
  Offer: Rs 45,000
  Deliverables: 2 REEL + 3 STORY
  Status: Fresh (New)
  Escrow: Funded (money is secured)
  Deadline: Respond by May 20

  Actions:
    [View Contract]  - See full terms
    [Accept]         - Accept and start
    [Message]        - Ask questions
    [Counter]        - Propose different rate
    [Decline]        - Reject offer
```

### Opportunities Tab -- Campaigns You Can Apply To

```
Open Campaign: Tech Gadget Review
  Brand: boAt
  Budget: Rs 10,000 - 15,000
  Deliverables: 1 YouTube Video
  Match Score: 78%

  [Apply Now]
```

### BUILD STATUS

| Feature | Status |
|---------|--------|
| Proposals tab with brand offers | BUILT (UI) |
| Opportunities tab with open campaigns | BUILT (UI) |
| Proposal detail dialog | BUILT (UI) |
| Accept / Counter / Message / Decline | BUILT (UI) |
| View Contract button | BUILT (UI) |
| Message dialog with quick questions | BUILT (UI) |
| Counter offer dialog | BUILT (UI) |
| Proposal status badges (Fresh, Viewed, etc.) | BUILT (UI) |

---

## 6. Deal Room & Negotiation

### How Negotiation Works (Example)

```
Day 1: Brand sends offer
  Nykaa Fashion
  Offer: Rs 15,000
  2 Reels + 4 Stories

Day 1: You counter
  Your Counter: Rs 22,000
  "My rate for this scope is higher..."

Day 2: Brand counters back
  Brand Counter: Rs 18,000
  "Best we can do, but we'll give 12-month usage rights"

Day 2: You accept
  ACCEPTED
  Final: Rs 18,000
  Contract generated automatically
```

### V2.2 Spec: Deal Room Should Have

- Text messages (standard chat)
- Structured Offer / Counter cards
- Auto-pinned brief at top
- Contract card (inline sign)
- Submission card (when you upload)
- Approval card (when brand approves)
- Payout card (when payment confirmed)
- Performance card (Day 7 metrics)
- File / image upload
- Voice notes (mobile)

### BUILD STATUS

| Feature | Status |
|---------|--------|
| Deal Room page | BUILT (UI) |
| Negotiation history display | BUILT (UI) |
| Text messaging | BUILT (UI, mock data) |
| Structured offer/counter cards | NOT BUILT |
| Inline contract signing | NOT BUILT |
| Submission/approval/payout cards | NOT BUILT |
| Voice notes | NOT BUILT |
| Real-time WebSocket messaging | NOT BUILT |

---

## 7. Contracts & Signing

When a deal is agreed, a contract auto-generates.

### What the Contract Covers

```
1. Parties (Brand + You)
2. Scope of Work (deliverables, format, platform)
3. Compensation (total amount + payment schedule)
4. Timeline (deadline + revision windows)
5. Usage Rights (what brand can/cannot do with your content)
6. Revision Policy (max 2)
7. Digital Signatures
```

### Signing Process

```
1. Review all contract terms
2. Check compensation and timeline
3. Verify usage rights duration
4. Draw your signature on canvas
5. Click "Sign Contract"
6. Wait for brand to sign (if they haven't already)
7. Contract status: Fully Executed
```

### After Signing

Per V2.2 spec:
- Deals under Rs 25K: **Single payout on approval** (no advance)
- Deals over Rs 25K (future): 30% on signing, 70% on approval

### BUILD STATUS

| Feature | Status |
|---------|--------|
| Auto-generated contract | BUILT (UI) |
| Contract terms display | BUILT (UI) |
| Digital signature canvas | BUILT (UI) |
| Contract statuses | BUILT (UI) |
| Usage rights in contract | BUILT (UI, hardcoded text) |
| Legal PDF download | NOT BUILT |

**SPEC vs CODE MISMATCH**: Current UI shows 50/50 split (50% on signing, 50% on approval). V2.2 spec says single payout on approval for deals under Rs 25K. Needs correction.

---

## 8. Submitting Deliverables

### How Content Submission Works

After contract is signed:
1. Receive product/brief from brand
2. Create content per guidelines
3. Upload video/image to platform (stored in Cloudflare)
4. Add caption and notes for brand
5. Submit for review

### Submission Form

```
Submit: Instagram Reel #1

Upload Content:
  [Drag & Drop Video] or [Browse Files]
  (Videos stored in Cloudflare)

Caption (for brand review):
  "Summer vibes with @nykaa fashion..."

Posting Date: [June 12, 2026]

Notes for Brand:
  "Used trending audio as discussed..."

[Save Draft]  [Submit for Review]
```

### Deliverable Statuses

| Status | Meaning |
|--------|---------|
| Pending | Not started yet |
| In Progress | You are working on it |
| Submitted | Sent to brand for review |
| Revision Requested | Brand wants changes |
| Approved | Brand approved, ready to post |

### BUILD STATUS

| Feature | Status |
|---------|--------|
| Deliverable status tracking | BUILT (UI, on brand side) |
| Content submission form | NOT BUILT -- no real upload flow for creators |
| Cloudflare video upload | NOT BUILT -- no real Cloudflare integration |
| Video playback from Cloudflare | NOT BUILT |
| Draft saving | NOT BUILT |

---

## 9. Revisions

### Rules (V2.2 LOCKED -- Non-Negotiable)

- **Max 2 revision rounds.** No third revision. Ever.
- **No refund** after content delivery.
- **Auto-approval at 72h** if brand takes no action.
- **Each revision must be structured** (no vague "redo it"):

```
Brand's Revision Request:

Issue 1:
  Location:       0:08 in Reel
  Change needed:  Center the product in frame
  Reference:      [attached image]

Revisions used: 1/2
```

### If You Think a Revision Is Unfair

```
You click "Dispute Revision"
  -> Influora ops reviews within 48h
  -> Ops sides with brand: you must revise
  -> Ops sides with you: brand must approve OR pay you 50% kill fee
  -> Compromise: specific adjustments only
```

### BUILD STATUS

| Feature | Status |
|---------|--------|
| Revision counter (0/2) display | BUILT (UI, brand side) |
| Revision request (free text) | BUILT (UI, basic) |
| Structured revision form (timestamp, specific change, reference) | NOT BUILT |
| Auto-approval at 72h | NOT BUILT |
| Dispute revision button | NOT BUILT |
| Ops mediation for disputes | NOT BUILT |

---

## 10. Getting Paid

### Fee Structure (You Pay Nothing)

```
Creator fee:  0%
You receive:  100% of agreed amount
Minus:        1% TDS (government mandated, u/s 194O)
You get:      Form 16A quarterly
```

Example: Rs 5,000 deal -> you receive Rs 4,950 (Rs 50 TDS).

### Payout Speed

| Track | Payout After Approval |
|-------|-----------------------|
| Unverified | T+3 days |
| Verified (OAuth) | T+1 day |

### Payout Method

Month 1 (Manual):
```
Brand approves your content
  -> Influora finance processes payout (T+2)
  -> Razorpay Payouts API -> Your UPI/Bank (instant)
```

Month 2+ (Razorpay Route):
```
Brand approves
  -> Automated split via Route API
  -> You receive T+0 to your UPI/bank
```

### Wallet Features

```
Wallet Overview:
  Available Balance:    Rs 45,000
  Pending (In Escrow):  Rs 27,000
  This Month Earnings:  Rs 72,000
  Total Lifetime:       Rs 3,45,000

Transaction History:
  Jun 15: +Rs 9,000 (Nykaa -- Content Approved)
  Jun 5:  +Rs 9,000 (Nykaa -- Contract Signed)
  Jun 3:  -Rs 25,000 (Withdrawal to Bank)
```

### Withdrawing Money

```
Requirements:
  - PAN number (first time only)
  - UPI or bank account

Withdrawal:
  Amount: Rs [40,000]
  To: HDFC Bank ****4521
  Processing: 2-3 business days
```

### BUILD STATUS

| Feature | Status |
|---------|--------|
| Creator wallet page | BUILT (UI) |
| Balance display (available, pending, total) | BUILT (UI) |
| Transaction history | BUILT (UI, mock data) |
| Withdrawal form | BUILT (UI) |
| PAN / bank details collection | BUILT (UI fields) |
| Promo code earnings tracking | BUILT (UI, mock) |
| Razorpay Payouts integration | NOT BUILT |
| Razorpay Route integration | NOT BUILT |
| Real TDS deduction | NOT BUILT |
| Form 16A generation | NOT BUILT |
| Differentiated payout speeds (T+1 vs T+3) | NOT BUILT |

---

## 11. Attribution & Your Performance

V2.2 gives creators visibility into their impact:

### What You See Per Campaign

```
Brand X -- Summer Collection

Your code: PRIYA10                          [Copy]
  42 redemptions
  Rs 38,400 revenue driven
  Updated 2 days ago

Your link: brandx.in/c/priya               [Copy]
  1,240 clicks (real-time)
  18 purchases

Performance bonus eligible
  Hit 50 redemptions -> unlock Rs 2,000 bonus
  [progress bar] 42/50
```

### Performance Window

After content is approved, there is a **7-day performance window** where attribution is tracked. After 7 days, final performance metrics are locked.

### BUILD STATUS

| Feature | Status |
|---------|--------|
| Promo code display per campaign | NOT BUILT |
| Tracking link display per campaign | NOT BUILT |
| Redemption/click tracking dashboard | NOT BUILT |
| Revenue attributed to your content | NOT BUILT |
| Performance bonus system | NOT BUILT |
| 7-day performance window | NOT BUILT |
| Self-report submission (DM screenshots etc.) | NOT BUILT |

---

## 12. Ratings

After every completed deal, **both sides rate each other**.

### You Rate the Brand

```
Overall:                 stars (1-5)
Brief clarity:           stars (1-5)
Payment speed:           stars (1-5)
Approval responsiveness: stars (1-5)
Respectfulness:          stars (1-5)
Public comment:          (optional)
```

### Brand Rates You

```
Overall:           stars (1-5)
Content quality:   stars (1-5)
Communication:     stars (1-5)
On-time delivery:  stars (1-5)
Brief adherence:   stars (1-5)
Public comment:    (optional)
```

### What Shows on Your Profile

```
@priya_styles
  4.8 stars (47 deals)
  96% completion rate
  On-time delivery: 89%
  Avg response time: 4 hours
  Recent comments: ["Loved working with...", ...]
```

### Rules

- Ratings show AFTER both parties rate (prevents retaliation)
- Cannot edit after 7 days
- Minimum 3 ratings before public display
- Comments moderated by ops

### Why This Matters for You

Creators have been burned by brands for years. Reciprocal rating fixes the power imbalance. You can warn other creators about bad brands, and brands can see your reliability beyond follower count.

### BUILD STATUS

| Feature | Status |
|---------|--------|
| Rating UI component | NOT BUILT |
| Creator rates brand form | NOT BUILT |
| Brand rates creator form | NOT BUILT |
| Public rating display on profiles | NOT BUILT -- hardcoded mock ratings |
| Double-blind reveal | NOT BUILT |
| Completion rate / on-time % | NOT BUILT |

---

## 13. Usage Rights -- Protect Your Content

### Default Rights (Auto-Applied)

Every contract includes these defaults. Brands CANNOT exceed them without paying extra.

```
Your content is licensed:
  Channels:           Your own social accounts only
  Brand may:          Repost to brand's own accounts (organic, NOT paid)
  Paid amplification: NOT ALLOWED (no running your content as ads)
  Duration:           6 months from publish date
  Geography:          India only
  Modifications:      Crop only, no recutting/re-editing
```

### What Brands Can Pay Extra For

```
[ ] Paid amplification (whitelisting/dark posts)   +30% to your fee
[ ] Use beyond 6 months                            +20% per 6 months
[ ] Use outside India                              +25% to your fee
[ ] Recutting/re-editing rights                     +15% to your fee
```

### If a Brand Violates Your Rights

```
Brand runs your content as a paid Meta ad without agreement:
  -> Automatic violation flag
  -> You get notified
  -> Dispute opened
  -> Brand must compensate or take down
```

### Why This Matters

Without explicit rights, brands often run creator content as Meta ads for Rs 50K-1L spend with no additional compensation. Minimal rights clause = legal cover + fair pay.

### BUILD STATUS

| Feature | Status |
|---------|--------|
| Usage rights text in contract | BUILT (hardcoded display) |
| Extended rights checkboxes during campaign setup | NOT BUILT |
| Rights premium calculation (+30%, +20%, etc.) | NOT BUILT |
| Violation detection and flagging | NOT BUILT |
| Rights as pinned card in Deal Room | NOT BUILT |

---

## 14. Cancellation & What Happens

### If Brand Cancels Your Deal

```
Within 24h of acceptance:          Full refund to brand, no kill fee to you
24h -> before you start shooting:  25% kill fee paid to you
Shoot started / concept submitted: 50% kill fee paid to you
Content submitted:                 No cancellation allowed -- revision/dispute
```

Kill fees go **100% to you**. Influora takes 0% fee on cancellations.

### If You Cancel

```
Within 24h:                        No penalty, full refund to brand
24h -> before submission:          Strike on your profile, full refund
After submission:                  Treated as dispute -> ops mediation
```

### BUILD STATUS

| Feature | Status |
|---------|--------|
| Decline/reject in proposal dialog | BUILT (UI) |
| Tiered kill fee system | NOT BUILT |
| Cancellation dialog with fee calculation | NOT BUILT |
| Strike assignment on cancellation | NOT BUILT |

---

## 15. Ghosting & No-Show Policy

### Auto-Enforced Timeline

```
Day 0:    Deal accepted, contract signed
Day 1-2:  Silent? -> Auto-reminder sent
Day 3:    Still no response -> Final warning
Day 5:    No concept submitted -> Brand can auto-cancel
Day 7:    Hard cutoff -- deal auto-cancels
```

### Strike System

```
1st ghost (within 6 months):
  - Strike on profile (public)
  - Brand fully refunded
  - Account stays active

2nd ghost (within 6 months):
  - Strike 2
  - 30-day suspension from new bids
  - Brand fully refunded
  - Appeal allowed once

3rd ghost (within 6 months):
  - Permanent ban from new activity
  - Pending payouts -> 30-day dispute hold
  - Already-paid earnings: unaffected (cannot retroactively claw back)
  - Appeal within 14 days
```

### Appeal Reasons Accepted

- Medical emergency (with proof)
- Bereavement
- Platform technical issues
- Brand non-responsiveness

### Your Public Commitment Score

```
@priya_styles
  Completion rate:   96% (47/49 deals)
  On-time delivery:  89%
  Avg response time: 4 hours
  Strikes:           0
```

### BUILD STATUS

| Feature | Status |
|---------|--------|
| Auto-reminder system | NOT BUILT |
| Auto-cancel at Day 5/7 | NOT BUILT |
| Strike system | NOT BUILT |
| Appeal process | NOT BUILT |
| Public commitment score display | NOT BUILT |
| 30-day suspension enforcement | NOT BUILT |

---

## 16. Platform Policies

V2.2 requires 5 policy documents:

### A. Terms of Service
- 18+, single account, platform's right to suspend, Mumbai jurisdiction

### B. Creator Policy
- MUST disclose paid partnerships (#ad) -- ASCI mandate
- NO buying followers / fake engagement
- NO off-platform deals (6-month exclusivity with brands met on Influora)
- Respond to brand within 48h
- Deliver on deadline

### C. Brand Policy
- Pre-fund deal before campaign goes live
- Approve or revise within 72h (else auto-approve)
- Cannot demand content violating ASCI/IP
- Cannot exceed agreed usage rights

### D. Community Guidelines
- No harassment, hate speech, adult content, illegal products

### E. Privacy Policy (DPDPA 2023)
- Data minimization, right to delete, data localization

### Enforcement Ladder

```
1st violation:  Warning + content takedown
2nd violation:  7-day suspension + payout freeze
3rd violation:  Permanent ban + 30-day dispute window
Severe:         Immediate ban (fraud, fake KYC, harassment)
```

### BUILD STATUS

| Feature | Status |
|---------|--------|
| Terms of Service page | NOT BUILT |
| Creator Policy page | NOT BUILT |
| Brand Policy page | NOT BUILT |
| Community Guidelines page | NOT BUILT |
| Privacy Policy page | NOT BUILT |
| T&C acceptance checkbox during signup | BUILT (UI) |
| Enforcement ladder logic | NOT BUILT |

---

## 17. Build Status -- Full Summary

### BUILT (UI working, mock data)

- Creator login (phone + OTP UI)
- Creator onboarding (profile, niches, rates, social accounts)
- Creator inbox (proposals + opportunities tabs)
- Proposal detail dialog (accept, counter, message, decline, view contract)
- Message brand dialog with quick questions
- Counter offer dialog
- Active collaborations page
- Creator wallet (balance, transactions, withdrawal, promo tracking)
- Basic navigation and layout (mobile + desktop)
- Verified / Unverified badges displayed
- Deal Room basic UI

### NOT BUILT (V2.2 spec features missing)

- Instagram OAuth (Meta Graph API)
- YouTube OAuth
- Analytics screenshot upload + OCR verification
- Verified vs Unverified track differentiation (T+1 vs T+3, 10% vs 12%)
- Real content submission flow (upload to Cloudflare)
- Cloudflare video storage and playback
- Promo code display and tracking per campaign
- UTM tracking link display
- Performance dashboard (redemptions, clicks, revenue attributed)
- Performance bonus system
- 7-day performance window
- Reciprocal star rating system
- Public commitment score
- Razorpay payouts (no real money flow)
- TDS deduction and Form 16A
- Structured revision form (timestamp + specific change)
- Auto-approval at 72h
- Dispute revision flow + ops mediation
- Tiered kill fee system
- Ghosting auto-enforcement and strike system
- Appeal process
- Usage rights configuration and violation detection
- Notification matrix (in-app, email, WhatsApp, SMS, push)
- Deal Room structured cards (Offer, Counter, Contract, Submission, Approval, Payout, Performance)
- Voice notes
- Real-time WebSocket messaging
- Platform policy pages (ToS, Creator Policy, Brand Policy, Community Guidelines, Privacy)
- Enforcement ladder

### SPEC vs CODE MISMATCHES

| Area | V2.2 Spec Says | Code Does |
|------|-----------------|-----------|
| Payment (deals under Rs 25K) | Single payout on approval | Shows 50/50 split |
| Creator fee | 0% | Some UI text ambiguous |
| Brand fee | 10% verified / 12% unverified | Shows flat 10% |
| Payout speed | T+1 verified / T+3 unverified | Not differentiated |
| First 2 deals | Free for brands | Not mentioned in UI |
| Campaign states | 6 states in spec | 9 states in code |

---

*Based on: PLATFORM_FLOW_V2_2.md (May 2026)*
*Last compared: May 2026*
