# Creator Features Documentation

## Overview

This document outlines the complete Creator experience in the Influora platform, mirroring the Brand Deal Room with proposal negotiation, contract signing, and deliverable management.

---

## 1. Navigation & Layout

### 1.1 Creator Sidebar Navigation

```
[IN] Influora
├─ + New Opportunity (button)
├─ Inbox (with unread count)
├─ Active (with earnings today)
├─ Deal Room (with unread messages)
├─ Wallet (with balance)
├─ Profile
└─ Settings

Footer:
└─ Creator Account (@handle)
```

**Differences from Brand:**
- No "Discover" (creators don't discover brands)
- "Inbox" shows incoming proposals + opportunities
- "Deal Room" mirrors brand's unified chat experience
- "Active" shows only ongoing collaborations (simplified from current)

---

## 2. Inbox (Incoming Opportunities)

### 2.1 Purpose
Central hub where creators receive:
- Direct proposals from brands
- Campaign opportunity invitations
- Counter-proposals from brands

### 2.2 Layout

```
┌────────────────────────────────────────────────────┐
│ Search Opportunities          [Filters] [Sort]     │
├────────────────────────────────────────────────────┤
│                                                    │
│ Category Tabs:                                     │
│ [All] [Proposals] [Campaigns] [Archived]           │
│                                                    │
│ Opportunity Cards (List):                          │
│ ┌──────────────────────────────────────────────┐  │
│ │ 🔵 Brand Name: Summer Fashion Campaign       │  │
│ │ Type: Direct Proposal                        │  │
│ │ Budget: ₹45,000                              │  │
│ │ Deliverables: 2 Reels, 3 Stories             │  │
│ │ Expires In: 2 days                           │  │
│ │                                              │  │
│ │ [View Details]  [Accept]  [Counter]          │  │
│ └──────────────────────────────────────────────┘  │
│                                                    │
│ ┌──────────────────────────────────────────────┐  │
│ │ 🟠 Tech Review Campaign (Open Bidding)       │  │
│ │ Type: Campaign Opportunity                   │  │
│ │ Budget Range: ₹20K - ₹40K                    │  │
│ │ Deadline: 5 days                             │  │
│ │ Applications: 12 creators applied             │  │
│ │                                              │  │
│ │ [View Campaign]  [Submit Bid]                │  │
│ └──────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────┘
```

### 2.3 Opportunity Card Details

| Field | Content |
|-------|---------|
| Status Badge | "New", "Countered", "Expires Soon" |
| Brand Name | With brand logo/avatar |
| Opportunity Type | "Direct Proposal" / "Campaign" |
| Amount | For proposals: ₹X, For campaigns: Range (₹X-₹Y) |
| Deliverables | List + count (e.g., "2 Reels, 3 Stories") |
| Timeline | Deadline or "Expires in 2 days" |
| Your Earnings | Net amount after TDS + platform fee |
| Actions | View, Accept, Counter, Decline |

### 2.4 Quick Actions
- **View Details**: Opens proposal/campaign details sheet
- **Accept**: Direct proposal accepted, creates deal
- **Counter**: Opens counter-proposal form
- **Decline**: Rejects opportunity with optional reason
- **Submit Bid**: For campaigns, opens bid submission form

---

## 3. Deal Room (Unified Chat & Collaboration)

### 3.1 Purpose
Centralized space where creators manage active deals with brands, mirroring the Brand Deal Room exactly.

### 3.2 Layout (Mirror of Brand Deal Room)

```
┌──────────────────────┬─────────────────────────────┐
│   Active Deals       │   Deal Chat Timeline        │
│   (Left Panel)       │   (Right Panel)             │
├──────────────────────┼─────────────────────────────┤
│                      │                             │
│ Search deals...      │ Summer Fashion Campaign     │
│ [Sort] [Filter]      │ with Alex Brands            │
│                      │                             │
│ ┌────────────────┐   │ [← Back] [⋮]               │
│ │ Alex Brands    │   │                             │
│ │ Summer Fashion │   │ ─────────────────────────── │
│ │ ₹50,000        │   │ [Brand Message]             │
│ │ 2/3 delivs ▓▓░│   │ "Here's our proposal:"      │
│ │ In Progress    │   │                             │
│ └────────────────┘   │ [Proposal Card]             │
│                      │ Amount: ₹50,000             │
│ ┌────────────────┐   │ Deliverables: 2 Reels      │
│ │ Tech Team      │   │ [Accept] [Counter] [Decline]
│ │ Tech Review    │   │                             │
│ │ ₹25,000        │   │ [Your Counter Proposal]     │
│ │ 1/2 delivs ▓░░│   │ Amount: ₹50,000             │
│ │ Negotiating    │   │ Status: Accepted            │
│ └────────────────┘   │                             │
│                      │ [Contract Card]             │
│                      │ CTR-2024-001                │
│                      │ Status: Awaiting Your Sign  │
│                      │ [Sign Now] [Download PDF]   │
│                      │                             │
│                      │ [Deliverable Card]          │
│                      │ Instagram Reel #1           │
│                      │ Status: Submitted           │
│                      │ [Approve] [Request Changes] │
│                      │                             │
│ ┌────────────────┐   │ ─────────────────────────── │
│ │ + New Chat     │   │ [Type message...]      [►] │
│ └────────────────┘   │                             │
│                      │                             │
└──────────────────────┴─────────────────────────────┘
```

### 3.3 Deal Cards in Left Panel

Each deal shows:
- Brand name + logo
- Campaign/Project name
- Amount ₹X
- Deliverables progress (X/Y completed)
- Current status (Negotiating, Contracted, In Progress, Review, Completed)
- Unread message indicator

### 3.4 Chat Timeline (Right Panel)

Shows all events in chronological order:
1. **Messages** (from both parties)
2. **Proposal Cards** (sent by brand)
3. **Your Counter-Proposals** (sent by you)
4. **Contract Card** (when agreement reached)
5. **Deliverable Cards** (when you submit)
6. **Payment Events** (when brand approves)

### 3.5 Deal Room Actions

**Available in Chat Timeline:**
- View incoming proposals with full details
- Send counter-proposals with modified terms
- Accept final proposal to trigger contract generation
- View contract and sign
- Submit deliverables
- See approval/revision requests
- Track payment releases

---

## 4. Proposal Response & Counter-Proposal

### 4.1 Viewing Incoming Proposal

**Proposal Card Shows:**
- Amount: ₹X
- Deliverables: List (Type, Quantity)
- Deadline: Date
- Usage Rights: 6 months, Perpetual, etc.
- Custom Clauses: Highlighted special terms
- Brand's Message: Any context

**Your Earnings Breakdown:**
```
Proposed Amount:        ₹50,000
Platform Fee (10%):    -₹5,000
GST on Fee (18%):      -₹900
TDS (10%):             -₹5,000
─────────────────────────────
Your Earnings:          ₹39,100
```

### 4.2 Counter-Proposal Form

**5-Step Form (Similar to Brand):**

**Step 1: Deliverables**
- Review proposed deliverables
- Option to request different format/quantity

**Step 2: Budget**
- Show brand's proposed amount
- Enter your requested amount
- Real-time earnings breakdown update
- See platform fees and TDS impact

**Step 3: Timeline**
- Review deadline
- Request extension if needed
- Show calendar for visual selection

**Step 4: Terms**
- Usage rights checkboxes (can accept/decline)
- Revision cap confirmation
- Custom clause review
- Add your own clauses (e.g., "Additional ₹2K per extra revision")

**Step 5: Message**
- Add negotiation message
- Explain counter terms to brand

### 4.3 Quick Actions on Proposal
- **Accept** - Direct accept, triggers contract
- **Counter** - Open counter-proposal form
- **Decline** - Reject with optional reason
- **Ask Questions** - Chat message without deciding

---

## 5. Contract Signing

### 5.1 Contract Card in Timeline

```
┌──────────────────────────────────────────┐
│ Contract Generated                       │
│ CTR-2024-001                             │
│                                          │
│ Status: Awaiting Your Signature          │
│                                          │
│ Parties: Alex Brands & You               │
│ Value: ₹50,000                           │
│ Deliverables: 2 Reels, 3 Stories         │
│ Terms: 2 revisions, 6 months usage       │
│                                          │
│ Signature Progress:                      │
│ ✓ Contract Generated                     │
│ □ Brand Signature (Pending)              │
│ □ Your Signature (Pending)               │
│ ○ Active (Awaiting both)                 │
│                                          │
│ [View Full Contract] [Sign Now]          │
└──────────────────────────────────────────┘
```

### 5.2 Signing Flow

**Step 1: Review Contract**
- Full PDF preview
- All terms locked from accepted proposal
- Scroll through to verify

**Step 2: Sign**
- Click "Sign Now" button
- Confirmation modal: "Ready to sign this contract?"
- Review amount one more time

**Step 3: Signature Applied**
- Timestamp recorded
- Status updates to "Brand Signed, Awaiting Your Signature" (if brand hasn't signed yet)
- Or "Active" if brand already signed

**Step 4: Escrow Confirmation**
- When both signed: "✓ Contract Active"
- "Escrow Amount ₹50,000 locked in secure account"
- "You'll receive payment upon deliverable approval"

### 5.3 Contract Card Actions
- **View Full Contract** - Download/view PDF
- **Sign Now** - Apply your digital signature
- **Download PDF** - Save for records

---

## 6. Deliverable Management

### 6.1 Submitting Deliverable

**From Deal Room:**
1. Click "Submit Deliverable" in chat
2. Select which deliverable from contract
3. Upload file(s) (video, image, documents)
4. Add caption/notes
5. Confirm and submit

**Deliverable Card Shows:**
```
┌──────────────────────────────────────────┐
│ Your Submission                          │
│ Instagram Reel #1                        │
│                                          │
│ [Video Thumbnail/Preview]                │
│                                          │
│ Caption: "Check out this amazing..."     │
│ Submitted: Today at 2:30 PM              │
│ Status: Submitted                        │
│                                          │
│ Awaiting Brand Review...                 │
│                                          │
│ [Download] [Replace] [Cancel Submission] │
└──────────────────────────────────────────┘
```

### 6.2 Revision Management

**When Brand Requests Changes:**

```
┌──────────────────────────────────────────┐
│ Brand Requested Changes                  │
│ Instagram Reel #1          Revision 1/2  │
│                                          │
│ Feedback: "Can you add more dynamic      │
│ transitions and make it 15sec instead    │
│ of 20sec?"                               │
│                                          │
│ [View Original] [Upload Revised]         │
│                                          │
│ Revision Deadline: 2 days remaining      │
└──────────────────────────────────────────┘
```

**Revision Tracking:**
- Revision 1/2: First resubmission allowed
- Revision 2/2: Second & final revision
- After 2: Brand must approve as-is or negotiate new terms

### 6.3 Approval & Payment

**When Brand Approves:**

```
┌──────────────────────────────────────────┐
│ Brand Approved! ✓                        │
│ Instagram Reel #1                        │
│                                          │
│ [Video Thumbnail/Preview]                │
│                                          │
│ Approved: Today at 5:00 PM               │
│ Status: Approved                         │
│                                          │
│ Payment Released:                        │
│ ₹25,000 → Your Wallet                    │
│                                          │
│ [View in Wallet]                         │
└──────────────────────────────────────────┘
```

---

## 7. Campaign Bidding

### 7.1 Campaign Opportunity Card

Shows in Inbox:
- Campaign name & brand
- Brief description
- Budget range: ₹20K - ₹40K
- Required deliverables: 2 Reels, 1 Carousel
- Timeline: 30 days
- How many creators applied: "234 applications"
- Your fit score (if applicable)

### 7.2 Bid Submission Form

**5-Step Bid Form:**

**Step 1: Review Campaign**
- Campaign details
- Requirements
- Timeline
- Usage rights (predefined by campaign)

**Step 2: Your Rate**
- Proposed amount (within range or custom)
- Your earnings breakdown:
  ```
  Your Rate:           ₹30,000
  Platform Fee (10%):  -₹3,000
  GST on Fee (18%):    -₹540
  TDS (10%):           -₹3,000
  ─────────────────────────────
  You Earn:            ₹23,460
  ```

**Step 3: Your Pitch**
- Message explaining why you're perfect
- Relevant experience/previous work
- Your unique angle

**Step 4: Portfolio**
- Attach 2-3 sample works (videos/images)
- Show relevant content you've created

**Step 5: Terms Confirmation**
- Confirm you can meet deadline
- Confirm delivery format/specifications
- Accept platform terms

### 7.3 Bid Status

After submission:
- **Pending**: Awaiting brand review
- **Shortlisted**: Brand interested, may ask questions
- **Accepted**: Congratulations, deal created!
- **Declined**: Brand selected another creator
- **Withdrawn**: You can withdraw bid anytime

---

## 8. Wallet & Earnings

### 8.1 Wallet Overview

```
┌────────────────────────────────────────┐
│ Your Balance                           │
│ ₹1,25,340                              │
│ Available to Withdraw                  │
│                                        │
│ This Month                             │
│ Earned: ₹2,50,000                      │
│ Platform Fee: -₹25,000                 │
│ TDS: -₹25,000                          │
│ Net: ₹2,00,000                         │
└────────────────────────────────────────┘
```

### 8.2 Earnings Breakdown

Shows:
- Gross Amount (total before fees)
- Platform Fee (10%)
- GST on Platform Fee (18% of fee)
- TDS (10% withholding for tax)
- Net Earnings (what you actually receive)

### 8.3 Transaction History

| Date | Deal | Amount | Status | Action |
|------|------|--------|--------|--------|
| 2024-01-20 | Summer Fashion | ₹50,000 | Approved | View |
| 2024-01-15 | Tech Review | ₹25,000 | Pending | - |
| 2024-01-10 | Beauty Campaign | ₹40,000 | Paid | View |

### 8.4 Payment Methods

- Bank account (primary)
- UPI (optional)
- Razorpay settlements
- TDS certificate generation for taxes

### 8.5 Withdraw Funds

- Minimum withdrawal: ₹500
- Maximum per transaction: ₹5,00,000
- Processing time: 2-3 business days
- Fee: ₹0 (no withdrawal fee)

---

## 9. Profile

### 9.1 Creator Profile Page

**Public Profile Shows:**
- Profile picture
- Name & handle (@)
- Bio/About (500 chars)
- Follower counts (Instagram, YouTube, TikTok, etc.)
- Average rating from brands (1-5 stars)
- Number of completed deals
- Content categories (Fashion, Tech, Beauty, Lifestyle, etc.)
- Previous campaigns (portfolio)
- Rate card (starting from ₹X)

**Private Settings:**
- Edit all above information
- Upload new profile picture
- Add portfolio videos/images
- Manage verified badges
- Request verification

### 9.2 Rating & Reviews

**Brands Rate Creators On:**
- Quality (content quality 1-5)
- Communication (responsiveness 1-5)
- Timeliness (deadline adherence 1-5)
- Overall rating (1-5)
- Optional comment

**Your Profile Shows:**
- Average rating across all deals
- Total reviews received
- Distribution (5-star %, 4-star %, etc.)

---

## 10. Settings

### 10.1 Tabs

- **General**: Profile info, bio, categories, rates
- **Availability**: Working hours, content creation capacity (deals/month)
- **Preferences**: Content types you prefer/avoid
- **Notifications**: Email, push, WhatsApp preferences
- **Payments**: Bank details, GST registration, TDS info
- **Security**: Password, 2FA, linked accounts
- **Integrations**: Connect Instagram, YouTube, TikTok APIs (for analytics)

### 10.2 Notification Preferences

Similar to brand, with event types:
- New proposal received
- Proposal countered/accepted/declined
- Contract ready to sign
- Contract signed by brand
- Deliverable feedback received
- Payment released
- Rating received
- Campaign opportunity matching your profile

---

## 11. Key Differences from Brand Experience

| Feature | Brand | Creator |
|---------|-------|---------|
| **Sidebar** | Dashboard, Campaigns, Discover, Deal Room, Wallet, Settings | Inbox, Active, Deal Room, Wallet, Profile, Settings |
| **Deal Creation** | Brand initiates proposals | Creator responds to proposals |
| **Pricing** | Brand sets budget | Creator can counter-propose |
| **Deliverables** | Brand defines and reviews | Creator submits and revises |
| **Earnings** | Pays out money | Receives money after TDS/fees |
| **Active Page** | Shows campaign stats | Shows ongoing collaborations |
| **Inbox** | Shows bids on campaigns | Shows incoming proposals |
| **Counter Flow** | Can counter based on bid | Sends counter-proposal form |

---

## 12. Data Models Reference

### Creator
```
Creator {
  id: string
  email: string
  name: string
  handle: string
  bio: string
  profilePicture: string
  socialLinks: SocialLink[]
  rating: number (1-5)
  reviewCount: number
  totalDealsCompleted: number
  categories: string[]
  startingRate: number
  verified: boolean
  createdAt: Date
}
```

### CreatorProposal (Incoming)
```
CreatorProposal {
  id: string
  creatorId: string
  brandId: string
  dealId: string
  type: "direct_proposal" | "campaign_bid"
  proposal: Proposal (from brand)
  status: "new" | "accepted" | "countered" | "declined" | "expired"
  createdAt: Date
  expiresAt: Date
}
```

### CreatorCounter
```
CreatorCounter {
  id: string
  dealId: string
  creatorId: string
  proposedAmount: number
  message: string
  status: "pending" | "accepted" | "declined"
  createdAt: Date
}
```

### CreatorDeliverable
```
CreatorDeliverable {
  id: string
  dealId: string
  deliverableId: string
  submission: {
    fileUrl: string
    fileType: string
    fileSize: number
    caption: string
  }
  revisionNumber: number (1-2)
  status: "submitted" | "approved" | "revision_requested" | "rejected"
  brandFeedback: string
  submittedAt: Date
}
```

---

## 13. Future Enhancements

- AI-powered opportunity matching
- Auto-generated rate recommendations
- Performance analytics dashboard
- Portfolio showcase with analytics
- AI writing assistant for bids/messages
- Automated TDS certificate generation
- Multi-creator team management
- Content scheduling and calendar integration
