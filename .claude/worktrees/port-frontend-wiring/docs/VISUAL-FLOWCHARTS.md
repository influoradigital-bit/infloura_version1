# Visual Flowcharts & Diagrams

## Full Deal Lifecycle - Timeline View

```
BRAND SIDE                          CREATOR SIDE                    SHARED DEAL ROOM

Day 1
─────────────────────────────────────────────────────────────────────────
Brand Dashboard                     Creator Inbox
       ↓                                  ↓
Brand finds creator         Creator sees proposal:
& clicks "Send Proposal"     "Summer Fashion - ₹45K"
       │                          │
       └──────────────────────────┤
                                  ↓
                    [PROPOSAL CARD in Deal Room Chat]
                    Amount: ₹45,000
                    Deliverables: 2 Reels, 3 Stories
                    Your Earnings: ₹35,100 (after fees)
                    [Accept] [Counter] [Decline]

Day 2
─────────────────────────────────────────────────────────────────────────
Brand waiting              Creator clicks "Counter"
for response                     │
(sees "Awaiting Creator")        ↓
                    [COUNTER-PROPOSAL FORM]
                    5 Steps:
                    1. Review deliverables ✓
                    2. Counter Amount: ₹50,000 (earn ₹38,800)
                    3. Timeline: OK
                    4. Terms: Accept
                    5. Message: "Great, let me increase the rate"
                         │
                         ↓
                    [COUNTER CARD in chat]
                    Amount: ₹50,000
                    Status: "Pending"

Day 2
─────────────────────────────────────────────────────────────────────────
Brand sees counter              Creator waiting
"Creator wants ₹50K"           (sees "Awaiting Brand")
     │                              │
     ├─[Accept]──────────────────────┤
              │                       │
              ↓                       ↓
     Brand clicks accept       Both see status:
                              "Agreement Reached"
                              
                    [CONTRACT CARD - Auto Generated]
                    CTR-2024-001
                    Value: ₹50,000
                    Status: "Generated"
                    
                    Signature Progress:
                    ✓ Generated
                    □ Brand Signature
                    □ Creator Signature
                    
                    [View Contract] [Sign Now]

Day 3
─────────────────────────────────────────────────────────────────────────
Brand reviews          Creator sees contract
contract PDF               PDF ready
     │                         │
     ├─[Sign Now]──────────────┤
     │   │                      │
     │   ├─Signature recorded   │
     │   └─Status shows:        │
     │     "Brand Signed"       │
     │     "Awaiting Creator"   │
     │                          │
     └──────────────────────────┤
                                ↓
                    Creator reviews contract
                         │
                         ├─[Sign Now]
                         │   │
                         │   ├─Signature recorded
                         │   └─Status: "Active"
                         │   └─Escrow ₹50K locked
                    
            Contract Active ✓
            Both see: "✓ Contract Signed"
                      "Escrow ₹50,000 locked"

Day 4-10
─────────────────────────────────────────────────────────────────────────
Brand waiting for      Creator submitting
deliverables           deliverables
(sees progress: 0/5)   
                       [SUBMIT DELIVERABLE]
                       ├─Select: Instagram Reel #1
                       ├─Upload: video.mp4 (45MB)
                       ├─Caption: "Check out..."
                       └─[Submit]
                                │
                                ↓
                    [DELIVERABLE CARD in chat]
                    Instagram Reel #1
                    Status: "Submitted"
                    Awaiting Brand Review...
                    
                    [Download] [Replace]

Brand sees submission        Creator waiting
     │                           │
     ├─[Approve]─────────────────┤
     │   │                        │
     │   ├─Payment released       │
     │   └─₹10K → Creator wallet  │
     │                            │
     │                            ↓
     │                    [PAYMENT NOTIFICATION]
     │                    ✓ Approved!
     │                    ₹10,000 → Your Wallet
     │                    
     │                    (Repeat for other deliverables)
     │
     └─Progress: 1/5 ✓

(Repeat for all 5 deliverables)

Day 11
─────────────────────────────────────────────────────────────────────────
All approved ✓                  All submitted ✓
                                
Brand sees:                     Creator sees:
"Deal Complete"                 "Deal Complete"
Progress: 5/5 ✓                 Earned: ₹50K gross
                                Received: ₹38,100 net

            [RATING MODALS APPEAR]
            ┌─────────────────────────────┐
            │ Rate Your Experience        │
            │                             │
            │ Quality:    ★★★★★          │
            │ Communication: ★★★★★       │
            │ Timeliness:    ★★★★★       │
            │                             │
            │ [Submit] [Skip]             │
            └─────────────────────────────┘

            Deal status: "Completed"
            Both can see: 
            - 5-star rating from other party
            - Profile updated with new rating
            - Earnings finalized
```

---

## Architecture Layers

```
┌─────────────────────────────────────────────────────┐
│                    UI Layer                         │
│  ┌──────────────────┬──────────────────┐            │
│  │  Brand UI        │  Creator UI       │            │
│  │  /brand/*        │  /creator/*       │            │
│  │  (built)         │  (to build)       │            │
│  └──────────────────┴──────────────────┘            │
├─────────────────────────────────────────────────────┤
│              Shared Components Layer                │
│  ┌────────────────────────────────────────────────┐ │
│  │ ProposalCard | ContractCard | MessageBubble   │ │
│  │ Button | Input | Sheet | Dialog | Badge       │ │
│  └────────────────────────────────────────────────┘ │
├─────────────────────────────────────────────────────┤
│              Business Logic Layer                   │
│  ┌────────────────────────────────────────────────┐ │
│  │ formatINR() | calculateFees() | signContract()│ │
│  │ generatePDF() | uploadFile() | submitDeliverable()
│  └────────────────────────────────────────────────┘ │
├─────────────────────────────────────────────────────┤
│              API / Database Layer                   │
│  ┌────────────────────────────────────────────────┐ │
│  │ Deal | Proposal | Contract | Message | Users  │ │
│  │ Deliverable | Transaction | Rating            │ │
│  └────────────────────────────────────────────────┘ │
├─────────────────────────────────────────────────────┤
│           External Services Layer                   │
│  ┌────────────────────────────────────────────────┐ │
│  │ Vercel Blob (Files) | Razorpay (Payments)     │ │
│  │ Email/SMS (Notifications)                      │ │
│  └────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────┘
```

---

## Data Flow Diagram

```
Brand Side                        Shared Database              Creator Side
──────────────────────────────────────────────────────────────────────────

Brand Creates Proposal
        │
        ├─→ POST /api/deals/propose
                    │
                    ├─→ Create Deal
                    ├─→ Create Proposal
                    ├─→ Store FeeBreakdown
                    │
                    └─→ Return dealId
        │
        └─ Show in Brand Deal Room Chat

Creator receives in Inbox
        │
        ├─ GET /api/proposals?creatorId=X
        │       │
        │       ├─ Query Deal table
        │       ├─ Query Proposal table
        │       ├─ Calculate earnings
        │       └─ Return proposals
        │
        └─ Show in Creator Inbox

Creator clicks Counter
        │
        ├─→ POST /api/deals/{dealId}/counter
                    │
                    ├─→ Create CreatorCounter
                    ├─→ Update Deal.status
                    │
                    └─→ Return counterId
        │
        └─ Show in Deal Room Timeline

Brand accepts counter
        │
        ├─→ POST /api/deals/{dealId}/accept
                    │
                    ├─→ Update Deal.status = "agreed"
                    ├─→ Create Contract (auto-generated)
                    ├─→ Lock Escrow
                    │
                    └─→ Return contractId
        │
        └─ Show Contract Card in both Deal Rooms

Brand signs contract
        │
        ├─→ POST /api/contracts/{contractId}/sign
                    │
                    ├─→ Record Brand Signature
                    ├─→ Update Contract.brandSignedAt
                    ├─→ Update Contract.status
                    │
                    └─→ Return signature confirmation
        │
        └─ Show signature status in both rooms

Creator signs contract
        │
        ├─→ POST /api/contracts/{contractId}/sign
                    │
                    ├─→ Record Creator Signature
                    ├─→ Update Contract.creatorSignedAt
                    ├─→ Update Contract.status = "active"
                    │
                    └─→ Return signature confirmation
        │
        └─ Show "Contract Active" in both rooms

Creator submits deliverable
        │
        ├─→ POST /api/deliverables
                    │
                    ├─→ Upload file to Vercel Blob
                    ├─→ Create Deliverable record
                    ├─→ Update Deal progress
                    │
                    └─→ Return deliverableId
        │
        └─ Show Deliverable Card in both Deal Rooms

Brand approves deliverable
        │
        ├─→ POST /api/deliverables/{id}/approve
                    │
                    ├─→ Update Deliverable.status = "approved"
                    ├─→ Update Escrow: Release payment
                    ├─→ Create Transaction record
                    ├─→ Update CreatorWallet
                    │
                    └─→ Return approval confirmation
        │
        └─ Show payment in Creator Wallet
           Show approval in Deal Room
```

---

## State Transitions

### Deal States
```
       [Draft/Proposal]
            │
            ├─→ Creator Accepts ──→ [Contracted]
            ├─→ Creator Counters ──→ [Negotiating] ──→ [Contracted]
            └─→ Creator Declines ──→ [Declined]

       [Contracted]
            │
            ├─→ Both Sign ──→ [In Progress]
            └─→ Expires ──→ [Expired]

       [In Progress]
            │
            ├─→ All Deliverables Approved ──→ [Completed]
            └─→ Brand Cancels ──→ [Cancelled]
```

### Proposal States
```
       [New]
        │
        ├─→ Creator Accept ──→ [Accepted]
        ├─→ Creator Counter ──→ [Countered]
        ├─→ Creator Decline ──→ [Declined]
        └─→ Expires ──→ [Expired]
```

### Contract States
```
       [Generated]
            │
            ├─→ Brand Signs ──→ [Brand Signed]
            │                      │
            │                      └─→ Creator Signs ──→ [Active]
            │
            └─→ Expires ──→ [Expired]
```

### Deliverable States
```
       [Not Started]
            │
            ├─→ Creator Submits ──→ [Submitted]
                                       │
                                       ├─→ Brand Approves ──→ [Approved]
                                       │
                                       └─→ Brand Requests ──→ [Revision Requested]
                                           Changes              │
                                                              (1/2)
                                                                │
                                                         Creator Revises
                                                                │
                                                         [Revision Submitted]
                                                                │
                                                    (Repeat max 2 times)
```

---

## Feature Comparison Matrix

```
┌──────────────────────┬──────────────┬──────────────┬────────────────┐
│ Feature              │ Brand Built  │ Creator Plan │ Shared Code    │
├──────────────────────┼──────────────┼──────────────┼────────────────┤
│ Deal Room Chat       │ ✅ Done      │ ❌ Phase 1   │ Layout pattern │
│ Proposal Form        │ ✅ Done      │ ⭕ Counter   │ Form structure │
│ Proposal Card        │ ✅ Done      │ ✅ Reuse     │ Same component │
│ Contract Gen & PDF   │ ✅ Done      │ ✅ Reuse     │ Same utility   │
│ Contract Signing     │ ✅ Done      │ ❌ Phase 3   │ Logic reuse    │
│ Deliverable Upload   │ ✅ Review    │ ❌ Phase 4   │ Form logic     │
│ Deliverable Card     │ ✅ Done      │ ❌ Phase 4   │ Display logic  │
│ Wallet               │ ✅ Done      │ ⭕ Enhanced  │ Same UI        │
│ Profile              │ ✅ Done      │ ⭕ Creator   │ Layout pattern │
│ Rating System        │ ✅ Done      │ ⭕ Phase 7   │ Same modal     │
│ Notifications        │ ✅ Base      │ ❌ Phase 9   │ Event types    │
│ Inbox                │ ✅ Bids      │ ⭕ Phase 6   │ Card pattern   │
└──────────────────────┴──────────────┴──────────────┴────────────────┘

Legend:
✅ Done / Reuse
❌ Need to Build
⭕ Need to Enhance
```

---

## Technical Stack

```
Frontend
├── React 18+ (JSX)
├── TypeScript (Type safety)
├── Next.js 16 (App Router)
├── TailwindCSS v4 (Styling)
├── Shadcn/UI (Components)
├── Lucide React (Icons)
├── React Query/SWR (Data fetching)
└── Zustand (State management)

Backend (Serverless)
├── Next.js API Routes
├── TypeScript
├── Database: PostgreSQL (Supabase/Neon/Aurora)
├── File Storage: Vercel Blob
├── Payment: Razorpay
└── Email: Nodemailer/Postmark

Development
├── Bash (CLI)
├── Git (Version control)
├── ESLint (Code quality)
├── TypeScript (Type checking)
└── Vercel (Deployment)

Testing
├── Unit tests (Jest)
├── E2E tests (Playwright/Cypress)
└── Manual browser testing
```

---

## Component Hierarchy

```
BrandLayout
├── CreatorLayout
├── Sidebar
│   ├── NavLinks (Home, Campaigns, Discover, Deal Room, Wallet, Settings)
│   ├── NotificationBell
│   └── UserMenu
│
├── Pages/
│   ├── brand-dashboard
│   ├── brand-campaigns
│   ├── brand-discover
│   ├── brand-chat (Deal Room)
│   │   ├── DealList (Left)
│   │   │   └── DealCard[] (with status)
│   │   │
│   │   └── ChatTimeline (Right)
│   │       ├── MessageBubble[]
│   │       ├── ProposalCard
│   │       ├── CounterProposalCard
│   │       ├── ContractCard
│   │       │   └── ContractPanel (Sheet)
│   │       │       ├── ContractDetails
│   │       │       ├── SignatureProgress
│   │       │       └── Actions (Download PDF, Sign Now)
│   │       │
│   │       ├── DeliverableCard
│   │       │   └── DeliverableSubmission (Form)
│   │       │
│   │       └── MessageInput
│   │
│   ├── brand-wallet
│   ├── brand-settings
│   └── ...
```

---

## Performance Considerations

```
Optimization Areas:
├── Message Pagination
│   └── Load 50 recent messages, lazy-load older
│
├── Image Optimization
│   └── Use Next.js Image component
│
├── Code Splitting
│   └── Dynamic imports for heavy components
│
├── Caching
│   ├── SWR for data fetching (with revalidation)
│   ├── Browser cache for static assets
│   └── CDN cache for deliverable files
│
└── Database
    ├── Index on Deal.creatorId, Deal.brandId
    ├── Index on Message.dealId
    └── Pagination queries (limit 50)
```

---

## Security Checklist

```
✅ Row-Level Security (RLS)
   ├── Brands can only see their own deals
   └── Creators can only see their own deals

✅ File Upload Security
   ├── Validate file types
   ├── Limit file size (max 500MB)
   └── Scan for viruses (optional, future)

✅ Authentication
   ├── Email verification
   ├── Password hashing (bcrypt)
   └── Session management

✅ API Security
   ├── CORS configured
   ├── Rate limiting on endpoints
   └── Input validation on all requests

✅ Payment Security
   ├── Escrow model (funds locked)
   ├── PCI-DSS via Razorpay
   └── No direct credit card handling

✅ Data Privacy
   ├── Encrypt sensitive fields
   ├── Audit logs for all actions
   └── GDPR compliance for EU users
```
