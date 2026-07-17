# Quick Reference Guide

## Documentation Files

### 📖 For Understanding the Project
1. **PROJECT-STATUS.md** - Start here! Overview of what's built and what's planned
2. **brand-vs-creator-comparison.md** - See how both sides work together

### 📋 For Brand Features (COMPLETED)
1. **brand-features.md** - Complete spec of all brand features
2. **brand-implementation-plan.md** - How we built it

### 📋 For Creator Features (TO BUILD)
1. **creator-features.md** - Complete spec of all creator features (mirrors brand)
2. **creator-implementation-plan.md** - How to build 9 phases in ~15 days

---

## Brand Deal Room Flow (Implemented)

```
Brand → Discover → Send Proposal → Deal Room Chat
                        ↓
                   Creator sees proposal card
                   Creator: Accept/Counter/Decline
                        ↓
                   If counter: Brand sees counter-proposal
                   Brand accepts counter
                        ↓
                   Contract auto-generated
                   Both sign contract
                        ↓
                   Creator submits deliverable
                   Brand reviews/approves
                        ↓
                   Payment released to creator wallet
                   Both rate each other
```

---

## Creator Deal Room Flow (TO BUILD - Phase 1-4)

```
Creator ← Inbox (sees proposals) → Deal Room Chat
           Brand's proposal card
                ↓
         Creator: Accept/Counter/Decline
         If counter: Counter form (earnings breakdown)
                ↓
         Brand sees counter-proposal
         Brand accepts counter
                ↓
         Contract auto-generated
         Creator signs (Brand already signed)
                ↓
         Creator submits deliverable from Deal Room
         Brand reviews/approves
                ↓
         Payment released to creator wallet
         Both rate each other
```

---

## Key Components Location

### Brand (Ready to Use)
- `/src/pages/brand-chat.tsx` - Deal Room page
- `/src/components/brand/deal-room/proposal-form.tsx` - Proposal form
- `/src/components/brand/deal-room/proposal-card.tsx` - Proposal card
- `/src/components/brand/timeline/panels/contract-panel.tsx` - Contract signing
- `/src/lib/contract-generator.ts` - PDF generation

### Creator (Need to Build)
- `/src/pages/creator-chat.tsx` - Deal Room page (NEW - Phase 1)
- `/src/components/creator/deal-room/counter-proposal-form.tsx` - Counter form (NEW - Phase 2)
- `/src/components/creator/deal-room/creator-contract-signing.tsx` - Signing (NEW - Phase 3)

---

## Phase Roadmap for Creator (9 Phases, 14-15 days)

| # | Phase | Days | Priority | Critical Path |
|---|-------|------|----------|---|
| 1 | Deal Room Chat | 3 | 🔴 CRITICAL | ✅ Must do first |
| 2 | Counter-Proposal Form | 2 | 🔴 HIGH | ✅ Enables negotiation |
| 3 | Contract Signing | 1.5 | 🔴 HIGH | ✅ Enables agreement |
| 4 | Deliverable Submission | 1.5 | 🔴 HIGH | ✅ Completes workflow |
| 5 | Campaign Bidding | 1.5 | 🟡 MEDIUM | ⭕ Parallel path |
| 6 | Inbox Improvements | 1 | 🟡 MEDIUM | ⭕ UI Polish |
| 7 | Profile & Ratings | 1.5 | 🟡 MEDIUM | ⭕ Reputation |
| 8 | Wallet & Earnings | 1 | 🟡 MEDIUM | ⭕ Money tracking |
| 9 | Notifications & Polish | 1.5 | 🟢 LOW | ⭕ Final touches |

---

## Earnings Calculation

Creator needs to see **earnings breakdown** in 3 places:
1. **Inbox** - When viewing proposal: "You'll earn ₹X after fees"
2. **Counter-Proposal Form** - Real-time as they change the amount
3. **Deal Room** - In proposal/counter cards: "Your earnings: ₹X"

**Formula:**
```
Proposed Amount:        ₹100,000
Platform Fee (10%):    -₹10,000
GST on Fee (18%):      -₹1,800
TDS (10%):             -₹10,000
───────────────────────────────
Creator Receives:       ₹78,200
```

---

## What to Reuse from Brand

✅ **Components:**
- ProposalCard component (exact same component)
- ContractCard component (exact same)
- Message rendering logic (can adapt)

✅ **Utilities:**
- `formatINR()` function - for currency display
- `contract-generator.ts` - for PDF generation (reuse logic)

✅ **Styles:**
- Card components and styling
- Button & form styling
- Color tokens and spacing

✅ **Layouts:**
- Left panel + Right panel structure (same as brand Deal Room)
- Message bubble styling
- Sheet/modal components

---

## Database Changes Needed

### New Tables for Creator
- None! Can reuse existing Deal table

### New Columns
- `Deal.creatorCounterAmount` (what creator proposes if different)
- `Deal.creatorCounterMessage` (message with counter)
- `Deal.creatorSignature` (timestamp + hash when creator signs)
- `CreatorDeliverable.revisionNumber` (1-2, tracks revision count)
- `CreatorDeliverable.brandFeedback` (message if changes requested)

### Existing Tables to Enhance
- `Deal` - Add creator-specific fields above
- Message, Contract, Deliverable - No changes needed

---

## Testing Checklist

### Phase 1: Deal Room (Creator)
- [ ] Deal list shows all creator's active deals
- [ ] Chat timeline displays messages from both
- [ ] Proposal cards show up in timeline
- [ ] Can scroll through messages
- [ ] Mobile layout works

### Phase 2: Counter-Proposal
- [ ] Counter form opens from Deal Room
- [ ] Earnings update real-time as amount changes
- [ ] Counter proposal sends and appears in chat
- [ ] Brand can see creator's counter

### Phase 3: Contract Signing
- [ ] Contract card shows in timeline when brand sends it
- [ ] "Sign Now" button appears
- [ ] Signature recorded successfully
- [ ] Status updates to "Active" after both sign

### Phase 4: Deliverables
- [ ] File upload works
- [ ] Deliverable card appears in timeline
- [ ] Brand can approve/request changes
- [ ] Revisions tracked (1/2, 2/2)
- [ ] Payment shows in wallet on approval

---

## Recommended Reading Order

1. Read **PROJECT-STATUS.md** (5 min) - Get overview
2. Read **brand-vs-creator-comparison.md** (15 min) - Understand both flows
3. Read **creator-features.md** (30 min) - Detailed spec
4. Read **creator-implementation-plan.md** (20 min) - Implementation steps
5. Review existing **brand-chat.tsx** (20 min) - Reference for structure

**Total: ~90 minutes to understand everything**

---

## Key Decisions Made

✅ **Architecture:** SSE for real-time (not WebSocket) - lighter for mobile
✅ **Storage:** Vercel Blob for deliverables and PDFs
✅ **Design:** Mirror layout (left list + right chat)
✅ **Payment:** Escrow model with auto-release on approval
✅ **Fees:** 10% platform fee + 18% GST + 10% TDS (standard)
✅ **Revisions:** Max 2 revisions per deliverable

---

## Questions to Answer Before Starting

- [ ] Confirm 9 phases priorities with team
- [ ] Confirm earnings formula with finance
- [ ] Confirm TDS withholding is always 10%
- [ ] Confirm payment settlement timeline
- [ ] Confirm GST is always 18%
- [ ] Confirm file size limits for deliverables
- [ ] Confirm allowed file types

---

## Success = Feature Parity

When done, creator side should have **everything brand side has:**
- Unified Deal Room chat
- View proposals/contracts in timeline
- Negotiate with earning transparency
- Sign contracts
- Submit & revise deliverables
- Track payments
- Rate partners
- Manage profile & earnings

**Same experience, opposite perspective!**
