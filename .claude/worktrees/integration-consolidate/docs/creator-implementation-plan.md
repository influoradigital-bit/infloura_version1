# Creator Implementation Plan

## Overview

Implementation roadmap for Creator-side features to match the Brand Deal Room experience. The creator side mirrors the brand experience but in reverse - responding to proposals instead of sending them.

---

## Phase Breakdown

### Phase 1: Creator Deal Room (Chat & Timeline)
**Priority: CRITICAL | Effort: 3 days | Dependencies: None**

**Why First:** Creators need a unified space like brands have - all negotiations, contracts, deliverables in one place.

**Tasks:**
1. Create `/creator/chat` page (mirror of `/brand/chat`)
   - Left panel: Deal list (showing active deals)
   - Right panel: Chat timeline for selected deal
   - Message input at bottom

2. Create Creator Deal List Component
   - Show: Brand name, campaign name, amount, deliverables progress
   - Status badges: Negotiating, Contracted, In Progress, Review, Completed
   - Sort & filter options

3. Integrate existing proposal cards into timeline
   - Use same ProposalCard component (already built)
   - Shows brand's proposal in chat context

4. Implement message display in timeline
   - Messages from brand and creator
   - Timestamp, sender info, message content

**Files to Create/Modify:**
- `src/pages/creator-chat.tsx` (new - main page)
- `src/components/creator/deal-room/deal-list.tsx` (new)
- `src/components/creator/deal-room/chat-timeline.tsx` (new)
- `src/components/creator/deal-room/message-bubble.tsx` (new)
- Mock data for creator deals

**Reuse From Brand:**
- ProposalCard component
- ContractCard component
- Message rendering logic

---

### Phase 2: Counter-Proposal Form
**Priority: HIGH | Effort: 2 days | Dependencies: Phase 1**

**Why Second:** Enables creators to negotiate proposals with earnings breakdown.

**Tasks:**
1. Create counter-proposal form component
   - 5-step form (similar to brand proposal form)
   - Step 1: Review deliverables
   - Step 2: Counter amount with earnings breakdown
   - Step 3: Timeline/deadline
   - Step 4: Terms & clauses review
   - Step 5: Message to brand

2. Earnings breakdown utility
   - Show: Gross - Platform Fee (10%) - GST (18% of fee) - TDS (10%) = Net
   - Real-time update as amount changes

3. Counter-proposal card for timeline
   - Shows creator's counter with amount + terms
   - Display next to brand's original proposal

4. Update Inbox to link to counter form
   - Current "Counter" dialog → Opens new form

**Files to Create/Modify:**
- `src/components/creator/deal-room/counter-proposal-form.tsx` (new)
- `src/components/creator/deal-room/earnings-breakdown.tsx` (new)
- `src/components/creator/deal-room/counter-proposal-card.tsx` (new)
- `src/lib/earnings-calculator.ts` (new utility)
- `src/pages/creator-inbox.tsx` (modify - link to new form)

---

### Phase 3: Contract Signing Flow
**Priority: HIGH | Effort: 1.5 days | Dependencies: Phase 1**

**Why Third:** Creators need to sign contracts - critical for payment flow.

**Tasks:**
1. Create contract signing modal for creator
   - Display contract PDF
   - Show signature progress (Generated → Brand Signed → Creator Signs → Active)
   - "Sign Now" button
   - Signature confirmation

2. Enhance ContractPanel for creator context
   - Show "Awaiting Your Signature" when creator's turn
   - Show "Brand Signed, Awaiting You" if brand already signed
   - Use same PDF generator as brand (reuse utility)

3. Contract card in chat timeline
   - Show current signature status
   - "Sign Now" button prominently displayed
   - Message: "Please review and sign the contract"

**Files to Create/Modify:**
- `src/components/creator/deal-room/creator-contract-signing.tsx` (new)
- `src/components/creator/deal-room/contract-signature-modal.tsx` (new)
- Reuse: `src/lib/contract-generator.ts` (from brand)
- `src/pages/creator-chat.tsx` (modify - add contract handling)

---

### Phase 4: Deliverable Submission Flow
**Priority: HIGH | Effort: 1.5 days | Dependencies: Phase 1**

**Why Fourth:** Once contract is signed, creators need to submit deliverables.

**Tasks:**
1. Create deliverable submission form
   - File upload (video, image, documents)
   - Caption/notes
   - Select which deliverable from contract
   - Preview before submit

2. Create DeliverableCard for timeline
   - Show: Thumbnail, caption, submission time
   - Status: Submitted, Approved, Revision Requested
   - Brand feedback if revisions requested
   - Revision counter (1/2, 2/2)

3. Revision management
   - Track revision count
   - Show brand's feedback/requirements
   - Upload revised file
   - After 2: Show message "Final revision - brand must approve or request new terms"

4. Payment confirmation when approved
   - Show payment released notification
   - Amount transferred to wallet

5. Update Active page
   - Show deliverables to submit
   - Submit button opens form in Deal Room context

**Files to Create/Modify:**
- `src/components/creator/deal-room/deliverable-submission.tsx` (new)
- `src/components/creator/deal-room/deliverable-card.tsx` (new)
- `src/components/creator/deal-room/revision-handler.tsx` (new)
- `src/lib/file-upload.ts` (new utility for Vercel Blob)
- `src/pages/creator-active.tsx` (modify - link to Deal Room)

---

### Phase 5: Campaign Bidding Enhancement
**Priority: MEDIUM | Effort: 1.5 days | Dependencies: Phase 1**

**Why Fifth:** Allows creators to bid on open campaigns in structured way.

**Tasks:**
1. Create campaign bid submission form
   - 5-step form
   - Step 1: Review campaign details
   - Step 2: Your proposed rate with earnings breakdown
   - Step 3: Pitch message
   - Step 4: Portfolio samples (attach 2-3 works)
   - Step 5: Confirm terms & deadline

2. Create bid card for timeline
   - If bid accepted → becomes proposal card

3. Campaign opportunity card improvements
   - Show budget range
   - Show application count
   - Better "Submit Bid" button

4. Bid status tracking
   - Pending (awaiting brand review)
   - Shortlisted (brand interested)
   - Accepted (moved to deal)
   - Declined (not selected)

**Files to Create/Modify:**
- `src/components/creator/deal-room/campaign-bid-form.tsx` (new)
- `src/components/creator/deal-room/bid-card.tsx` (new)
- `src/pages/creator-inbox.tsx` (modify - enhance campaign card)

---

### Phase 6: Inbox Improvements
**Priority: MEDIUM | Effort: 1 day | Dependencies: All above**

**Why Sixth:** Polish the main entry point for new opportunities.

**Tasks:**
1. Update Inbox layout
   - Clear categorization: [All] [Direct Proposals] [Campaigns] [Archived]
   - Better opportunity cards with more info
   - Status indicators (New, Countered, Expires Soon)

2. Add filters & sorting
   - Filter by: Status, Amount Range, Type
   - Sort by: Newest, Amount (High-Low), Expiring Soon

3. Link Inbox items to Deal Room
   - "View Details" → Opens in Deal Room
   - "Accept" → Creates deal and opens Deal Room
   - "Counter" → Opens counter form in Deal Room

4. Search functionality
   - Search by brand name, campaign name, deliverable type

**Files to Create/Modify:**
- `src/pages/creator-inbox.tsx` (enhance)
- `src/components/creator/opportunity-card.tsx` (enhance)
- `src/components/creator/inbox-filters.tsx` (new)

---

### Phase 7: Profile & Rating System
**Priority: MEDIUM | Effort: 1.5 days | Dependencies: None (parallel)**

**Why Seventh:** Enables reputation building and brand discovery.

**Tasks:**
1. Enhance creator profile page
   - Show public profile view
   - Edit mode for creator info
   - Portfolio section with previous work
   - Rating display from brands
   - Verified badge

2. Rating modal after deal completion
   - Trigger when creator rates brand
   - Same structure as brand rating creator
   - Quality, Communication, Timeliness ratings

3. Update profile card across platform
   - In Inbox: Brand profile preview
   - In Deal Room: Click brand name to see profile

**Files to Create/Modify:**
- `src/pages/creator-profile.tsx` (enhance)
- `src/components/creator/profile-edit.tsx` (enhance)
- `src/components/creator/rating-modal.tsx` (new)
- `src/components/creator/profile-preview.tsx` (new)

---

### Phase 8: Wallet & Earnings
**Priority: MEDIUM | Effort: 1 day | Dependencies: Phase 4**

**Why Eighth:** Already mostly built, just needs connection to deliverable approvals.

**Tasks:**
1. Update wallet to show transaction ledger
   - Each deal payment with status
   - TDS breakdown visible
   - Net earnings calculation

2. Connect to payment releases
   - When brand approves deliverable → funds released
   - Show in wallet immediately

3. Add withdrawal UI
   - Request payout
   - Select payment method (bank/UPI)
   - Minimum ₹500, Max ₹5L per transaction

**Files to Modify:**
- `src/pages/creator-wallet.tsx` (enhance)
- `src/lib/earnings-calculator.ts` (create if not exist)

---

### Phase 9: Notifications & Polish
**Priority: LOW | Effort: 1.5 days | Dependencies: All above**

**Why Last:** Non-critical improvements after core features work.

**Tasks:**
1. Notification system (13+ event types)
   - New proposal received
   - Proposal accepted/declined/countered
   - Contract ready to sign
   - Brand signed contract (your turn)
   - Deliverable feedback received
   - Approval & payment released
   - Rating received

2. Mobile responsiveness
   - Deal Room on mobile
   - Forms on mobile
   - Touch-friendly buttons

3. Loading states & error handling
   - Skeleton loaders
   - Error boundaries
   - Retry mechanisms

**Files to Create/Modify:**
- `src/components/common/notifications.tsx` (new or enhance)
- All components (add mobile styles)

---

## File Structure (Creator)

```
src/
├── components/
│   └── creator/
│       ├── creator-layout.tsx (existing)
│       └── deal-room/
│           ├── deal-list.tsx (new)
│           ├── chat-timeline.tsx (new)
│           ├── message-bubble.tsx (new)
│           ├── counter-proposal-form.tsx (new)
│           ├── earnings-breakdown.tsx (new)
│           ├── counter-proposal-card.tsx (new)
│           ├── creator-contract-signing.tsx (new)
│           ├── contract-signature-modal.tsx (new)
│           ├── deliverable-submission.tsx (new)
│           ├── deliverable-card.tsx (new)
│           ├── revision-handler.tsx (new)
│           ├── campaign-bid-form.tsx (new)
│           ├── bid-card.tsx (new)
│           ├── profile-preview.tsx (new)
│           └── rating-modal.tsx (new)
│       ├── inbox-filters.tsx (new)
│       ├── opportunity-card.tsx (enhance)
│       └── profile-edit.tsx (enhance)
├── pages/
│   ├── creator-chat.tsx (new - main deal room)
│   ├── creator-inbox.tsx (enhance)
│   ├── creator-active.tsx (existing)
│   ├── creator-profile.tsx (enhance)
│   ├── creator-wallet.tsx (enhance)
│   └── creator-settings.tsx (existing)
├── lib/
│   ├── earnings-calculator.ts (new)
│   ├── file-upload.ts (new)
│   └── contract-generator.ts (reuse from brand)
└── docs/
    └── creator-features.md (created)
```

---

## Implementation Order

| Order | Phase | Effort | Timeline |
|-------|-------|--------|----------|
| 1 | Phase 1: Creator Deal Room | 3 days | Day 1-3 |
| 2 | Phase 2: Counter-Proposal Form | 2 days | Day 4-5 |
| 3 | Phase 3: Contract Signing | 1.5 days | Day 6 |
| 4 | Phase 4: Deliverable Submission | 1.5 days | Day 7-8 |
| 5 | Phase 5: Campaign Bidding | 1.5 days | Day 8-9 |
| 6 | Phase 6: Inbox Improvements | 1 day | Day 9-10 |
| 7 | Phase 7: Profile & Ratings | 1.5 days | Day 10-11 |
| 8 | Phase 8: Wallet & Earnings | 1 day | Day 11-12 |
| 9 | Phase 9: Notifications & Polish | 1.5 days | Day 12-13 |

**Total Estimated: 14-15 days**

---

## Key Reuse Opportunities

To speed up implementation, reuse from Brand side:
- **ProposalCard** component (already built)
- **ContractCard** component (already built)
- **contract-generator.ts** utility (PDF generation)
- **formatINR** utility (currency formatting)
- **Message rendering logic** (from brand chat)

---

## Comparison with Brand Implementation

| Feature | Brand | Creator | Status |
|---------|-------|---------|--------|
| Deal Room Chat | ✅ Done | 🔄 Phase 1 | Share layout |
| Proposal Form | ✅ Done | 🔄 Phase 2 (Counter) | Mirror approach |
| Contract Signing | ✅ Done | 🔄 Phase 3 | Reuse logic |
| Deliverables | ✅ Done | 🔄 Phase 4 | Submit vs Review |
| Wallet/Earnings | ✅ Done | 🔄 Phase 8 | Earnings breakdown |
| Profile | ✅ Done | 🔄 Phase 7 | Creator focus |

---

## Questions Before Implementation

1. **File Storage**: Use Vercel Blob for creator deliverables? (Same as brand)
2. **Real-time Updates**: SSE for creator chat updates too?
3. **Earnings Calculation**: TDS always 10%? GST always 18%?
4. **Contract PDF**: Use same generator or customize for creator context?
5. **Notifications**: Email, Push, WhatsApp? Or just in-app?

---

## Success Criteria

Phase completion when:
- ✅ All components render without errors
- ✅ Creator can view proposals in Deal Room
- ✅ Creator can counter-propose with earnings breakdown
- ✅ Creator can sign contracts
- ✅ Creator can submit & revise deliverables
- ✅ Creator earnings update when deliverables approved
- ✅ Mobile responsive layouts working
- ✅ All actions tested in browser
