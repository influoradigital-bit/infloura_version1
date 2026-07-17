# Brand Features Implementation Plan

## Current State

**Already Built:**
- Dashboard with pipeline overview
- Campaign list and detail pages
- Creator discovery page
- Basic Deal Room (chat page)
- Wallet page
- Settings page
- Sidebar navigation (6 items)

**Needs Work:**
- Proposal form in Deal Room
- Campaign Brief + Bid cards for campaign flow
- Contract PDF generation and signing
- Deliverable submission and approval
- Tracking setup (UTM, promo codes)
- Analytics integration

---

## Implementation Phases

### Phase 1: Deal Room - Proposal Flow
**Priority: HIGH**

**Tasks:**
1. Create Proposal Form component (panel/modal mix)
   - Deliverables selector (type + quantity)
   - Budget input (INR)
   - Timeline picker
   - Usage rights dropdown
   - Exclusivity options
   - Custom clauses with highlight styling
   - Tracking fields (UTM auto-gen, promo code, affiliate %)

2. Create Proposal Card component
   - Display all proposal terms
   - Status badge (Pending, Accepted, Declined, Countered)
   - Action buttons for recipient (Accept / Counter / Decline)

3. Create Counter Proposal Card component
   - Show changes highlighted
   - Original vs new values
   - Note/message field

4. Wire "Send Proposal" from Discover page to Deal Room

**Files to create/modify:**
- `src/components/brand/deal-room/proposal-form.tsx` (new)
- `src/components/brand/deal-room/proposal-card.tsx` (new)
- `src/components/brand/deal-room/counter-proposal-card.tsx` (new)
- `src/pages/brand-chat.tsx` (modify - add proposal form trigger)
- `src/components/brand/discover/creator-discovery.tsx` (modify - link to deal room)

---

### Phase 2: Campaign Integration
**Priority: HIGH**

**Tasks:**
1. Create Campaign Brief Card component
   - Shows campaign terms in chat
   - For deals originating from campaign bids

2. Create Bid Card component
   - Creator's bid details
   - Brand action buttons (Accept / Counter / Decline)

3. Update Campaign Detail page
   - Clicking on bid opens Deal Room with that creator
   - Show Campaign Brief + Bid as first messages

4. Update Campaigns list
   - Add filter: All / Open Campaigns / Direct Deals
   - Direct deals (from Deal Room proposals) appear here too

**Files to create/modify:**
- `src/components/brand/deal-room/campaign-brief-card.tsx` (new)
- `src/components/brand/deal-room/bid-card.tsx` (new)
- `src/pages/brand-campaign-detail.tsx` (modify)
- `src/pages/brand-campaigns.tsx` (modify - add filters)

---

### Phase 3: Contract System
**Priority: HIGH**

**Tasks:**
1. Create Contract Card component
   - Contract ID, status
   - Signature status (Brand / Creator)
   - Value and escrow status
   - View PDF / Sign buttons

2. Create Contract PDF generator
   - Use react-pdf or similar
   - Template with all contract terms
   - Signature placeholders

3. Create Contract Signing flow
   - Sign button triggers signature
   - Update status in real-time
   - Lock escrow when both sign

4. Create Contract Detail Sheet/Modal
   - Full PDF preview
   - Download option
   - Signature history

**Files to create/modify:**
- `src/components/brand/deal-room/contract-card.tsx` (new)
- `src/lib/contract-generator.ts` (new)
- `src/components/brand/deal-room/contract-detail-sheet.tsx` (new)
- `src/pages/brand-chat.tsx` (modify - integrate contract flow)

---

### Phase 4: Deliverable Management
**Priority: MEDIUM**

**Tasks:**
1. Create Deliverable Card component (enhanced)
   - Video/image preview
   - Caption/notes
   - Status (Submitted, Approved, Changes Requested)
   - Revision count
   - Action buttons (Approve / Request Changes)

2. Create Revision Request form
   - Notes field
   - Specific feedback
   - Track revision count vs cap

3. Create Deliverable Approval flow
   - Approve triggers payment release
   - Updates deal progress

4. Create Deliverable List view (in Campaign Detail)
   - All submissions across creators
   - Filter by status
   - Bulk actions

**Files to create/modify:**
- `src/components/brand/deal-room/deliverable-card.tsx` (enhance)
- `src/components/brand/deal-room/revision-request-form.tsx` (new)
- `src/pages/brand-campaign-detail.tsx` (modify - deliverables tab)

---

### Phase 5: Tracking & Analytics
**Priority: MEDIUM**

**Tasks:**
1. Add Tracking section to Proposal Form
   - UTM link generator
   - Promo code field
   - Affiliate % field

2. Create Analytics Tab in Campaign Detail
   - Reach metrics
   - Engagement metrics
   - Conversion tracking (if enabled)
   - ROI calculation

3. Create Tracking Dashboard
   - Aggregate data across campaigns
   - Compare creator performance
   - Export reports

**Files to create/modify:**
- `src/components/brand/deal-room/proposal-form.tsx` (modify)
- `src/components/brand/campaigns/campaign-analytics.tsx` (new)
- `src/lib/tracking-utils.ts` (new)

---

### Phase 6: Advanced Features
**Priority: MEDIUM**

**Tasks:**
1. Create Cancellation interface
   - Reason dropdown
   - Kill fee breakdown modal
   - Transaction in wallet

2. Implement Fee Transparency UI
   - Cost breakdown in proposal form
   - Real-time calculation with add-ons
   - Confirmation step review

3. Create Star Ratings system
   - Post-completion rating modal
   - Store ratings in database
   - Display on creator profile

4. Enhanced Revision Management
   - Track revision count (1/2, 2/2)
   - Handle overflow (3rd revision handling)

5. Usage Rights Add-ons
   - Checkbox add-ons with pricing
   - Real-time total update
   - Add-on details modal

**Files to create/modify:**
- `src/components/brand/deal-room/cancellation-modal.tsx` (new)
- `src/components/brand/deal-room/fee-breakdown.tsx` (new)
- `src/components/brand/deal-room/rating-modal.tsx` (new)
- `src/components/brand/deal-room/proposal-form.tsx` (modify - add fee calc + add-ons)

---

### Phase 7: Real-Time & Notifications
**Priority: MEDIUM**

**Tasks:**
1. Implement SSE (Server-Sent Events)
   - Real-time chat message updates
   - Contract signature updates
   - Deliverable submission notifications

2. Create Notification System
   - Toast notifications (13+ event types)
   - Notification bell in header
   - Notification preferences in Settings

3. Mobile-specific features
   - Deal Room mobile layout (full-screen switches)
   - Bottom navigation for mobile
   - Touch-friendly buttons

**Files to create/modify:**
- `src/lib/sse-client.ts` (new)
- `src/components/common/notifications.tsx` (new)
- `src/pages/brand-settings.tsx` (modify - add notification prefs)
- `src/components/brand/brand-layout.tsx` (modify - add notification bell)

---

### Phase 8: Analytics & Reports
**Priority: LOW**

**Tasks:**
1. Tracking data collection
   - UTM parameter tracking
   - Promo code usage logging
   - Affiliate link tracking

2. Analytics Dashboard enhancements
   - Real-time metrics
   - Custom date ranges
   - Performance comparison
   - Export to CSV/PDF

**Files to create/modify:**
- `src/lib/analytics.ts` (new)
- `src/components/brand/campaigns/campaign-analytics.tsx` (enhance)

---

## File Structure (Complete)

```
src/
├── components/
│   └── brand/
│       └── deal-room/
│           ├── proposal-form.tsx (new)
│           ├── proposal-card.tsx (new)
│           ├── counter-proposal-card.tsx (new)
│           ├── campaign-brief-card.tsx (new)
│           ├── bid-card.tsx (new)
│           ├── contract-card.tsx (new)
│           ├── contract-detail-sheet.tsx (new)
│           ├── deliverable-card.tsx (enhance)
│           ├── revision-request-form.tsx (new)
│           ├── payment-card.tsx (new)
│           ├── cancellation-modal.tsx (new)
│           ├── fee-breakdown.tsx (new)
│           ├── rating-modal.tsx (new)
│           └── usage-rights-addon.tsx (new)
│       └── campaigns/
│           └── campaign-analytics.tsx (enhance)
│       └── discover/
│           └── creator-discovery.tsx (modify)
│   └── common/
│       ├── notifications.tsx (new)
│       └── sse-provider.tsx (new)
├── pages/
│   ├── brand-chat.tsx (modify - main deal room)
│   ├── brand-campaigns.tsx (modify - filters, direct deals)
│   ├── brand-campaign-detail.tsx (modify - bid to deal room)
│   └── brand-settings.tsx (modify - notification prefs)
├── lib/
│   ├── contract-generator.ts (new)
│   ├── tracking-utils.ts (new)
│   ├── sse-client.ts (new)
│   ├── analytics.ts (new)
│   └── fee-calculator.ts (new)
└── docs/
    ├── brand-features.md (created)
    └── brand-implementation-plan.md (created)
```

---

## Implementation Order (Updated)

| Order | Phase | Effort | Dependencies |
|-------|-------|--------|--------------|
| 1 | Phase 1: Proposal Flow | 3 days | None |
| 2 | Phase 3: Contract System | 3 days | Phase 1 |
| 3 | Phase 2: Campaign Integration | 2 days | Phase 1 |
| 4 | Phase 4: Deliverables | 2 days | Phase 3 |
| 5 | Phase 6: Advanced Features | 2 days | Phase 1,3,4 |
| 6 | Phase 5: Tracking | 1.5 days | Phase 4 |
| 7 | Phase 7: Real-Time & Mobile | 2.5 days | Phase 1,5 |
| 8 | Phase 8: Analytics | 1.5 days | Phase 5,6 |

**Total Estimated: 17-18 days**

---

## Key Dependencies & Decisions

### Technology Stack
- **PDF Generation**: @react-pdf/renderer or pdfkit
- **Real-time Updates**: SSE (lightweight) → WebSocket (future)
- **File Storage**: Vercel Blob for deliverables
- **Notifications**: React Toast + backend email/SMS

### Data Flow

```
Brand Creates Proposal
    ↓
Stored in DB with Deal
    ↓
Creator sees Proposal Card in Deal Room
    ↓
Creator Accept/Counter/Decline
    ↓
If Accept → Contract Auto-generated from proposal
            Contract Card appears
    ↓
Both sign Contract
    ↓
Escrow locked, deliverables tracking begins
    ↓
Creator submits → Deliverable Card
    ↓
Brand Approve/Request Changes
    ↓
Approve → Payment released
    ↓
Both rate each other
    ↓
Deal complete, analytics calculated
```

---

## Testing Plan (By Phase)

### Phase 1 Testing
- Proposal form validation
- Proposal card rendering
- Accept/Counter/Decline flows
- UTM generation

### Phase 3 Testing
- Contract PDF generation
- Signature flow
- Escrow locking
- Contract updates in real-time

### Phase 2 Testing
- Campaign brief + bid cards
- Bid acceptance flow
- Deal Room appearance

### Phase 4 Testing
- Deliverable upload
- Revision tracking (1/2, 2/2)
- Overflow handling
- Payment release

### Phase 6 Testing
- Cancellation flow + kill fees
- Fee breakdown accuracy
- Add-ons price calculation
- Rating submission

### Phase 7 Testing
- SSE connection & reconnection
- Notification delivery
- Mobile layouts & touch targets

### Phase 8 Testing
- Tracking data accuracy
- Analytics aggregation
- Report generation

---

## Questions Before Starting Implementation

1. **Contract PDF**: Should signature be a simple button click or drawn signature?
2. **Kill Fee Calculation**: Auto-detect deal stage or manual override?
3. **Revision Overflow**: On 3rd revision request, auto-approve or block?
4. **Real-time Strategy**: Start with polling (simple) and migrate to SSE later?
5. **File Upload**: Max file size for deliverables? Video + image support?
6. **Notifications**: Push notifications on web (service workers) or just in-app toast?

---

## Current Status

**Documentation Complete**. Ready for Phase 1 implementation when approved.
