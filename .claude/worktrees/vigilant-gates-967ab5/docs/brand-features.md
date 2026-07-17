# Brand Features - Influora Platform

## Overview

This document outlines all brand-side features for the Influora influencer marketing platform. The platform enables brands to discover creators, negotiate deals, manage contracts, track deliverables, and measure ROI.

---

## Navigation Structure

```
Sidebar Menu:
├── Home (Dashboard)
├── Campaigns
├── Creators (Discover)
├── Deal Room
├── Wallet
└── Settings
```

---

## 1. Dashboard (Home)

**Purpose:** Quick overview of all brand activities

**Components:**
- Pipeline Overview (visual funnel: Outreach → Negotiating → Contracted → In Progress → Review → Settled)
- SLA At Risk alerts (deals needing attention)
- This Month Stats:
  - Completed collaborations
  - Total Reach
  - Amount Spent (INR)
  - Average CPE (Cost Per Engagement)
- Quick Links to main sections

---

## 2. Campaigns

**Purpose:** Manage all campaigns (Open Campaigns + Direct Deals)

### 2.1 Campaign Types

| Type | Description | Entry Point |
|------|-------------|-------------|
| Open Campaign | Brand posts brief, creators bid | Create Campaign button |
| Direct Deal | Brand approaches specific creator | Discover → Send Proposal |

### 2.2 Campaign List View

- Filter tabs: All / Open Campaigns / Direct Deals
- Each card shows:
  - Campaign name
  - Status badge (Draft, Active, Completed)
  - Creator count (for open) or Creator name (for direct)
  - Budget / Spent
  - Deliverables progress
  - ROI metrics

### 2.3 Campaign Detail View

**Tabs:**
- **Overview**: Brief, budget, timeline, target audience
- **Bids** (Open Campaign): List of creator bids with Accept/Message/Decline
- **Active**: Accepted creators with progress
- **Deliverables**: All submissions across creators
- **Analytics**: ROI, reach, engagement, conversions

### 2.4 Create Campaign Form

```
Step 1: Basic Info
├── Campaign Name
├── Description/Brief
├── Category/Niche
└── Target Audience

Step 2: Requirements
├── Deliverables (type + quantity)
│   ├── Instagram Reel
│   ├── Instagram Story
│   ├── Instagram Post
│   ├── YouTube Video
│   ├── YouTube Shorts
│   └── Custom
├── Budget Range (Min - Max INR)
├── Timeline/Deadline
└── Creator Requirements (followers, engagement rate, location)

Step 3: Terms
├── Usage Rights (duration)
├── Exclusivity (Yes/No, duration)
├── Revision Cap
└── Additional Guidelines

Step 4: Tracking
├── UTM Parameters (auto-generated)
├── Promo Code (optional)
└── Affiliate Commission % (optional)

Step 5: Review & Publish
```

---

## 3. Creators (Discover)

**Purpose:** Find and approach creators for direct deals

### 3.1 Creator Discovery

- Search & Filter:
  - Category/Niche
  - Platform (Instagram, YouTube, etc.)
  - Follower range
  - Engagement rate
  - Location
  - Language
  - Price range
- Creator Cards:
  - Profile photo, name, handle
  - Follower count
  - Engagement rate
  - Categories/niches
  - Starting rate
  - "View Profile" / "Send Proposal" buttons

### 3.2 Creator Profile View

- Bio & social links
- Audience demographics
- Content samples
- Past collaborations
- Reviews/ratings
- Performance metrics
- "Send Proposal" CTA

### 3.3 Send Proposal Action

When brand clicks "Send Proposal":
1. Opens Deal Room with that creator
2. Shows Proposal Form panel
3. After sending, proposal card appears in chat

---

## 4. Deal Room

**Purpose:** Unified space for all deal communication, negotiation, contracts, and deliverables

### 4.1 Layout

```
┌─────────────────────────────────────────────────────────┐
│  Deal Room                                              │
├──────────────┬──────────────────────────────────────────┤
│              │  Creator Name          Status Badge      │
│  Deal List   │──────────────────────────────────────────│
│              │                                          │
│  - Creator 1 │         Chat Timeline                    │
│  - Creator 2 │                                          │
│  - Creator 3 │  [Messages, Proposals, Contracts,        │
│              │   Deliverables - all in one stream]      │
│              │                                          │
│              │──────────────────────────────────────────│
│              │  Message Input    [+] [Send]             │
└──────────────┴──────────────────────────────────────────┘
```

### 4.2 Deal List (Left Panel)

- All active conversations
- Each item shows:
  - Creator avatar & name
  - Deal status (Negotiating, Contracted, In Progress, Review)
  - Deal value (INR)
  - Deliverable progress (2/5)
  - Unread count
  - Campaign name (if from campaign)

### 4.3 Chat Timeline (Right Panel)

**Message Types:**

1. **Text Messages**
   - Brand messages (right side, brand color)
   - Creator messages (left side, gray)

2. **Proposal Card** (Brand sends)
   ```
   ┌─────────────────────────────────────┐
   │  PROPOSAL                           │
   ├─────────────────────────────────────┤
   │  Deliverables: 2 Reels, 3 Stories   │
   │  Budget: ₹50,000                    │
   │  Timeline: 2 weeks                  │
   │  Usage Rights: 6 months             │
   │  Exclusivity: 30 days               │
   │                                     │
   │  Custom Clauses:                    │
   │  • Brand approval before posting    │
   │  • Include product in first 5 sec   │
   │                                     │
   │  [View Full Details]                │
   └─────────────────────────────────────┘
   Creator options: Accept / Counter / Decline
   ```

3. **Campaign Brief Card** (For campaign bids)
   ```
   ┌─────────────────────────────────────┐
   │  CAMPAIGN BRIEF                     │
   ├─────────────────────────────────────┤
   │  Summer Fashion 2024                │
   │  Deliverables: 2 Reels, 3 Stories   │
   │  Budget Range: ₹40,000 - ₹60,000    │
   │  Deadline: June 15, 2024            │
   └─────────────────────────────────────┘
   ```

4. **Bid Card** (Creator's response to campaign)
   ```
   ┌─────────────────────────────────────┐
   │  CREATOR BID                        │
   ├─────────────────────────────────────┤
   │  Rate: ₹45,000                      │
   │  Message: "Excited to work on..."   │
   │  Estimated delivery: 10 days        │
   └─────────────────────────────────────┘
   Brand options: Accept / Counter / Decline
   ```

5. **Counter Proposal Card**
   ```
   ┌─────────────────────────────────────┐
   │  COUNTER PROPOSAL          Pending  │
   ├─────────────────────────────────────┤
   │  Budget: ₹55,000 (was ₹50,000)      │
   │  Changed: +1 Revision               │
   │                                     │
   │  Note: "Can we add one more..."     │
   └─────────────────────────────────────┘
   ```

6. **Contract Card** (Auto-generated when accepted)
   ```
   ┌─────────────────────────────────────┐
   │  CONTRACT              CTR-2024-001 │
   ├─────────────────────────────────────┤
   │  Status: Pending Signatures         │
   │                                     │
   │  ☑ Brand Signed                     │
   │  ☐ Creator Signed                   │
   │                                     │
   │  Value: ₹50,000                     │
   │  Escrow: Funded                     │
   │                                     │
   │  [View PDF] [Sign Contract]         │
   └─────────────────────────────────────┘
   ```

7. **Deliverable Card** (Creator submits)
   ```
   ┌─────────────────────────────────────┐
   │  DELIVERABLE         Instagram Reel │
   ├─────────────────────────────────────┤
   │  ┌─────────────────────────────┐    │
   │  │                             │    │
   │  │      [Video Thumbnail]      │    │
   │  │                             │    │
   │  └─────────────────────────────┘    │
   │                                     │
   │  Caption: "Check out this..."       │
   │  Submitted: 2 hours ago             │
   │                                     │
   │  [Approve] [Request Changes]        │
   └─────────────────────────────────────┘
   ```

8. **Payment Card** (System generated)
   ```
   ┌─────────────────────────────────────┐
   │  💰 PAYMENT RELEASED                │
   ├─────────────────────────────────────┤
   │  Amount: ₹25,000                    │
   │  For: Instagram Reel #1             │
   │  Status: Transferred to creator     │
   └─────────────────────────────────────┘
   ```

### 4.4 Proposal Form (Panel/Modal Mix)

When brand clicks "+" or "Send Proposal":

```
┌─────────────────────────────────────┐
│  Create Proposal                    │
├─────────────────────────────────────┤
│  Deliverables                       │
│  ┌────────────┬─────┬─────┐        │
│  │ Type       │ Qty │  x  │        │
│  ├────────────┼─────┼─────┤        │
│  │ Inst Reel  │  2  │  x  │        │
│  │ Inst Story │  3  │  x  │        │
│  └────────────┴─────┴─────┘        │
│  [+ Add Deliverable]                │
│                                     │
│  Budget                             │
│  ┌─────────────────────────────┐   │
│  │ ₹ 50,000                    │   │
│  └─────────────────────────────┘   │
│                                     │
│  Timeline                           │
│  ┌─────────────────────────────┐   │
│  │ 2 weeks from acceptance     │   │
│  └─────────────────────────────┘   │
│                                     │
│  Usage Rights                       │
│  ┌─────────────────────────────┐   │
│  │ 6 months                    │   │
│  └─────────────────────────────┘   │
│                                     │
│  Exclusivity                        │
│  ○ None  ● 30 days  ○ 60 days      │
│                                     │
│  Custom Clauses (highlighted)       │
│  ┌─────────────────────────────┐   │
│  │ • Brand approval required   │   │
│  │ • Product in first 5 sec    │   │
│  │ [+ Add clause]              │   │
│  └─────────────────────────────┘   │
│                                     │
│  Tracking (Optional)                │
│  ├── UTM Link: [Auto-generated]     │
│  ├── Promo Code: [          ]       │
│  └── Affiliate %: [    ]            │
│                                     │
│         [Cancel]  [Send Proposal]   │
└─────────────────────────────────────┘
```

### 4.5 Contract Generation

**Trigger:** When either party accepts a proposal

**Process:**
1. System generates PDF contract from accepted terms
2. Contract card appears in chat
3. Brand signs first (click "Sign Contract")
4. Creator signs second
5. When both sign:
   - Contract status → "Active"
   - Escrow funds locked
   - Deliverable tracking begins

**Contract PDF Contains:**
- Parties (Brand name, Creator name)
- Campaign/Deal details
- All agreed terms from proposal
- Payment terms
- Deliverable schedule
- Usage rights & exclusivity
- Custom clauses
- Signature fields with timestamps
- Legal disclaimers

### 4.6 Deliverable Submission Flow

**Creator Side:**
1. Click "Submit Deliverable" in Deal Room
2. Select deliverable type (from contract)
3. Upload file (video/image)
4. Add caption/notes
5. Submit → Appears as card in chat

**Brand Side:**
1. See deliverable card in chat
2. Review content (preview/download)
3. Track revision count: "Revision 1/2" displayed
4. Options:
   - **Approve** → Payment released, marked complete
   - **Request Changes** → Opens revision request form
5. After 2 revisions:
   - If still not approved: Brand must approve as-is or negotiate new terms in chat

---

## 4.7 Cancellation Interface

**Where:** Deal status menu or "..." options on Deal Room header

**Cancellation Modal:**
```
┌─────────────────────────────────────┐
│  Cancel Deal                        │
├─────────────────────────────────────┤
│  Are you sure you want to cancel?   │
│                                     │
│  Reason (required):                 │
│  [Dropdown: Budget cut / Schedule   │
│   change / Found another creator    │
│   / Other]                          │
│                                     │
│  Kill Fee Breakdown:                │
│                                     │
│  Deal Status: In Progress           │
│  ├─ If cancelled in 24h: 0%        │
│  ├─ If before shoot: 25% fee       │
│  ├─ If shoot started: 50% fee      │
│  └─ After submission: 100% fee     │
│                                     │
│  Your Kill Fee: ₹12,500 (25%)       │
│  Creator Receives: ₹37,500          │
│                                     │
│  [Cancel Deal]  [Back]              │
└─────────────────────────────────────┘
```

**After Confirmation:**
- Deal status → "Cancelled"
- Escrow partially released per kill fee
- Creator notified with reason
- Transaction visible in Wallet
- Note appears in Deal Room chat

---

## 4.8 Fee Transparency UI

**Shown in 2 places:**

### Location 1: Proposal Form (Before Sending)
```
┌─────────────────────────────────────┐
│  Cost Breakdown                     │
├─────────────────────────────────────┤
│  Creator Payout:      ₹50,000       │
│  Platform Fee (10%):  ₹5,000        │
│  GST on Fee (18%):    ₹900          │
│  ─────────────────────────────────  │
│  Total You Pay:       ₹55,900       │
│                                     │
│  Escrow Amount:       ₹55,900       │
│  (Held securely until approval)     │
└─────────────────────────────────────┘
```

### Location 2: Confirmation Step
Same breakdown shown again before final confirmation

### Location 3: Deal Room - Contract Card
```
Value: ₹50,000 (Creator receives)
Your Total: ₹55,900 (Including platform fee & tax)
```

---

## 4.9 Escrow Clarity

**Current Implementation (Month 1):**
- Label: "Funds collected and held securely" (NOT "escrow")
- Reality: Razorpay holds funds temporarily
- Release triggers: Brand approves deliverable
- Holds creator payment pending final approval

**Future (After Month 2):**
- True escrow with dedicated accounts
- Automatic release conditions
- Dispute resolution

---

## 4.10 Usage Rights - Enhanced

**Standard Option (Included):**
- Duration: 6 months from content posting
- Platforms: Social media only
- Repurposing: No (must ask creator for each use)

**Add-on Options (With Price Impact):**

| Add-on | Cost | Description |
|--------|------|-------------|
| Whitelisting/Paid Ads | +30% | Run as paid ads on creator's account |
| Extended Duration | +20% | Extend from 6 to 12 months |
| Perpetual Usage | +50% | Use forever, no time limit |
| Repurposing Rights | +25% | Use in ads, emails, website |
| Exclusive Content | +40% | Creator cannot post elsewhere |

**UI in Proposal Form:**
```
Usage Rights
Base: 6 months social media  ₹50,000
├─ □ Whitelisting/Ads       +₹15,000
├─ □ Extended 12 months     +₹10,000
├─ □ Perpetual              +₹25,000
└─ □ Repurposing Rights     +₹12,500
                            ─────────
Total Budget:               ₹XX,XXX
```

Real-time update of total as checkboxes change.

---

## 4.11 Star Ratings

**Trigger:** After deal completion (all deliverables approved)

**Rating Modal:**
```
┌─────────────────────────────────────┐
│  Rate Your Experience               │
├─────────────────────────────────────┤
│  Creator: Priya Sharma              │
│                                     │
│  Overall Rating:                    │
│  ☆ ☆ ☆ ☆ ☆  (Click to rate)       │
│                                     │
│  Quality:        ☆ ☆ ☆ ☆ ☆         │
│  Communication:  ☆ ☆ ☆ ☆ ☆         │
│  Timeliness:     ☆ ☆ ☆ ☆ ☆         │
│                                     │
│  Comments (optional):               │
│  ┌─────────────────────────────┐   │
│  │ "Great content, very..."    │   │
│  └─────────────────────────────┘   │
│                                     │
│  [Submit Rating] [Skip]             │
└─────────────────────────────────────┘
```

**Rating Impact:**
- Visible on Creator profile (average rating)
- Creator can see brand's rating on their profile
- Required info but not blocking payment release
- Can be submitted up to 30 days after completion

---

## 4.12 Revision Cap - Enhanced

**Displayed on Deliverable Card:**
```
┌─────────────────────────────────────┐
│  Instagram Reel       Revision 1/2  │
├─────────────────────────────────────┤
│  [Video Thumbnail]                  │
│  Caption: "Check out..."            │
│                                     │
│  [Approve] [Request Changes]        │
└─────────────────────────────────────┘
```

**After 2 Revisions:**
- Card shows: "Revision 2/2 (Final)"
- Creator gets warning: "This is your final revision"
- Brand options: Approve or Accept as-is
- If neither: Mark as "Revision Complete" → Move to next deliverable

---

## 4.13 Notifications System

### 8.1 Types (Expanded)

| Event | Channel | Priority | Timing |
|-------|---------|----------|--------|
| New bid on campaign | Push + Email | High | Immediate |
| Proposal received | Push + Email | High | Immediate |
| Proposal accepted | Push + Email | High | Immediate |
| Proposal countered | Push + Email | High | Immediate |
| Proposal declined | Push + Email | Medium | Immediate |
| Contract ready to sign | Push + Email | High | Immediate |
| Contract signed by creator | Push + Email | High | Immediate |
| Deliverable submitted | Push + Email | High | Immediate |
| Deliverable approved | Push | Medium | Immediate |
| Revision requested | Push + Email | Medium | Immediate |
| Payment released | Email | Medium | Immediate |
| Deal cancelled | Push + Email | High | Immediate |
| Rating received | Push | Low | 30 days after completion |
| SLA at risk | Push + Email | Urgent | Every 4 hours until resolved |

### 8.2 Notification Preferences

In Settings → Notifications:

```
Email Notifications:
□ All events (default)
□ High priority only
□ None

Push Notifications:
□ Enabled
□ DND Hours: [9 PM] to [8 AM]
□ Smart notifications (suppress duplicates)

WhatsApp Notifications:
☑ Opt-in for WhatsApp updates
  (Opt-in via WhatsApp link)

Channels Override:
├─ New proposals: Email + Push
├─ Deal cancelled: Push only
└─ Payment: Email only
```

---

## 4.14 Real-Time Updates

**Implementation Approach:**

### For MVP (Now):
- **SSE (Server-Sent Events)** for real-time chat updates
- Browser polling for wallet changes (every 60 seconds)
- Page refresh when switching between sections

### Why SSE:
- Lighter than WebSocket for mobile
- Better battery on phones
- Easier to implement than WebSocket
- Sufficient for MVP feature set

### What Updates Real-Time:
- Messages in Deal Room chat
- Proposal/contract card updates
- Deliverable submissions
- Payment notifications

### Connection Loss Handling:
- Auto-reconnect with exponential backoff
- Queue pending messages
- Sync when connection restored
- Toast notification: "Connection lost - syncing..."

---

## 5. Mobile Layout Considerations

### 5.1 Deal Room Mobile

**Layout:**
- Full-screen deal list when screen < 768px
- Tap a deal → Full-screen chat view
- Back arrow to return to list
- Floating action button: "+" for new proposal/message

**Chat View Mobile:**
```
┌────────────────────────────────┐
│ ← Priya Sharma      ⋮           │ (header)
├────────────────────────────────┤
│                                │
│  Messages & Cards (full width) │
│                                │
├────────────────────────────────┤
│ [Message input......]  [Send] │ (sticky)
└────────────────────────────────┘
```

### 5.2 Proposal Form Mobile

- Opens as full-screen modal
- Step-by-step form (1 section per screen)
- Progress bar: "Step 2/5"
- Swipe or tap to go back/forward
- Bottom action buttons (sticky)

```
┌──────────────────────────────┐
│ Create Proposal   Step 1/5   │ ← [← Cancel]
├──────────────────────────────┤
│ Progress: [████░░░░░░]      │
│                              │
│ Deliverables                 │
│ ┌──────────┬────┬────┐      │
│ │ Type     │Qty │ x  │      │
│ ├──────────┼────┼────┤      │
│ │ Inst Reel│ 2  │ x  │      │
│ └──────────┴────┴────┘      │
│                              │
│ [+ Add Deliverable]          │
│                              │
│           [Next →]           │
└──────────────────────────────┘
```

### 5.3 Contract Signing Mobile

- Full screen PDF viewer
- Scroll to see all terms
- Sign button sticks to bottom
- After signing: "Waiting for creator..." progress

### 5.4 Bottom Navigation (Mobile)

```
┌─────────────────────────────────┐
│                                 │
│  Chat           Contract Approve│
│  [📱]           [✓]             │
│                                 │
│  Campaigns      More            │
│  [📋]           [⋮]             │
└─────────────────────────────────┘
```

---

## 6. Wallet

**Purpose:** Financial overview and transactions

### 6.1 Overview

- Total Balance (INR)
- In Escrow (locked for active deals)
- Available to Withdraw
- This Month: Deposits / Spent

### 6.2 Transaction History

| Date | Description | Type | Amount | Status |
|------|-------------|------|--------|--------|
| Jan 15 | Escrow - Priya Sharma | Lock | -₹50,000 | Completed |
| Jan 20 | Payment - Reel #1 | Release | -₹25,000 | Completed |

### 6.3 Add Funds

- Payment methods: UPI, Net Banking, Cards
- GST invoice generation
- TDS handling

---

## 7. Settings

**Purpose:** Account and platform configuration

### 7.1 Tabs

- **General**: Brand profile, logo, description
- **Team**: Invite team members, roles (Admin, Manager, Viewer)
- **Notifications**: Email/push preferences (see section 4.13)
- **Payments**: Bank details, GST info, TDS certificates
- **Security**: Password, 2FA
- **Integrations**: Connect analytics tools

---

## 8. Analytics & Tracking

### 8.1 Campaign Analytics

Available in Campaign Detail → Analytics tab:

- **Reach Metrics**: Impressions, unique reach
- **Engagement**: Likes, comments, shares, saves
- **Performance**: Engagement rate, CPE, CPM
- **Conversions** (if tracking enabled):
  - Link clicks (UTM tracked)
  - Promo code usage
  - Sales attributed
  - Revenue generated
  - ROI calculation

### 8.2 Tracking Setup

**For each deal, brand can set:**

| Tracking Type | Purpose | Setup |
|---------------|---------|-------|
| UTM Link | Track traffic source | Auto-generated, customizable |
| Promo Code | Track discount usage | Brand creates code |
| Affiliate Link | Track sales | Integration with e-commerce |

**Analytics Dashboard Shows:**
- All tracking data aggregated
- Compare performance across creators
- Export reports (CSV, PDF)

---

## 9. Data Models Reference

### Campaign
```
Campaign {
  id: string
  brandId: string
  name: string
  description: string
  type: "open" | "direct"
  status: "draft" | "active" | "paused" | "completed"
  deliverables: Deliverable[]
  budgetMin: number
  budgetMax: number
  deadline: Date
  usageRights: string
  exclusivity: string
  requirements: CreatorRequirements
  tracking: TrackingConfig
  createdAt: Date
}
```

### Deal
```
Deal {
  id: string
  campaignId: string (optional for direct)
  brandId: string
  creatorId: string
  status: "negotiating" | "contracted" | "in_progress" | "review" | "completed" | "cancelled"
  currentProposal: Proposal
  contract: Contract (when agreed)
  deliverables: DeliverableSubmission[]
  messages: Message[]
  tracking: TrackingConfig
  cancellationReason: string (if cancelled)
  killFeePercentage: number (0 | 25 | 50 | 100)
}
```

### Proposal
```
Proposal {
  id: string
  dealId: string
  senderId: string
  senderType: "brand" | "creator"
  type: "initial" | "counter"
  deliverables: Deliverable[]
  budget: number
  timeline: string
  usageRights: {
    duration: string
    addOns: ["whitelisting" | "extended" | "perpetual" | "repurposing"]
  }
  exclusivity: string
  customClauses: string[]
  revisionCap: number (default 2)
  status: "pending" | "accepted" | "declined" | "countered"
  createdAt: Date
}
```

### Contract
```
Contract {
  id: string
  dealId: string
  acceptedProposalId: string
  pdfUrl: string
  brandSignature: Signature
  creatorSignature: Signature
  status: "pending_signatures" | "brand_signed" | "active" | "completed"
  escrowAmount: number
  escrowStatus: "pending" | "funded" | "partial_released" | "released"
  createdAt: Date
  completedAt: Date
}
```

### Rating
```
Rating {
  id: string
  dealId: string
  raterType: "brand" | "creator"
  rating: number (1-5)
  qualityRating: number (1-5)
  communicationRating: number (1-5)
  timelinessRating: number (1-5)
  comments: string
  createdAt: Date
}
```

---

## 10. Future Enhancements (Not in current scope)

- AI-powered creator recommendations
- Automated content scheduling
- Multi-brand agency dashboard
- Influencer marketplace
- Performance prediction
- Bulk campaign management
