# Unified Collaboration Timeline System - Architecture Plan

## Executive Summary

Consolidate 4 separate pages (Messages, Deal Rooms, Contracts, Deliverables) into a single unified **Timeline** component that shows all interactions per creator per campaign in chronological order. Each event is tagged by type (message, proposal, contract, deliverable, payment, system) with optional file uploads to Cloudflare R2.

---

## 1. Data Model

### Current Types (Existing in types.ts)
- `Collaboration` — links Brand + Creator + Campaign
- `Contract` — signed agreement
- `ContractDeliverable` — content submission/review
- `Deliverable` — uploaded content tracking
- `Message` — already supports types: 'text', 'image', 'file', 'proposal', 'contract'

### New Unified Type: TimelineEvent

```typescript
export interface TimelineEvent {
  id: string;                          // e.g., "evt-123"
  collaborationId: string;             // Links to Collaboration
  timestamp: Date;
  senderId: string;                    // User ID (or 'system')
  senderType: 'brand' | 'creator' | 'system';
  
  // EVENT TAG -- determines what renders
  tag: 'message' | 'proposal' | 'contract' | 'deliverable' | 'payment' | 'system';
  
  // Content -- varies by tag
  content?: string;                    // For 'message' tag
  attachments?: Attachment[];
  
  // Tag-specific metadata
  metadata?: {
    // For 'proposal' tag:
    proposalId?: string;
    amount?: number;
    deliverables?: number;
    deadline?: string;
    status?: 'pending' | 'accepted' | 'rejected' | 'countered';
    
    // For 'contract' tag:
    contractId?: string;
    contractStatus?: 'generated' | 'brand_signed' | 'creator_signed' | 'active';
    
    // For 'deliverable' tag:
    deliverableId?: string;
    deliverableNumber?: number;        // Reel #1, #2, etc.
    platform?: 'instagram' | 'youtube' | 'tiktok';
    submittedUrl?: string;             // Link to R2 storage
    submittedFilename?: string;
    status?: 'submitted' | 'under_review' | 'revision_requested' | 'approved';
    revisionCount?: number;
    revisionLimit?: number;            // Default 2
    
    // For 'payment' tag:
    amount?: number;
    paymentType?: 'escrow_locked' | 'milestone_released' | 'final_payout';
    
    // For 'system' tag:
    message?: string;                  // Auto-notification text
    severity?: 'info' | 'warning' | 'alert';
  };
  
  status: 'sent' | 'read' | 'delivered';
  archived?: boolean;
}

export interface Attachment {
  name: string;
  url: string;                         // Cloudflare R2 presigned URL
  type: string;                        // 'image/jpeg', 'video/mp4', etc.
  size: number;                        // bytes
  uploadedAt: Date;
}
```

### R2 Upload Path Structure

```
influora-data/
├── contracts/
│   ├── collab-{collaborationId}/
│   │   └── contract-{contractId}.pdf
│
├── deliverables/
│   ├── collab-{collaborationId}/
│   │   └── collab-{collaborationId}_reel-{number}_{version}.mp4
│   │   └── collab-{collaborationId}_story-{number}_{version}.mp4
│   │   └── collab-{collaborationId}_post-{number}_{version}.jpg
│
├── messages/
│   ├── collab-{collaborationId}/
│   │   └── msg-{messageId}_attachment.{ext}
│
└── revisions/
    ├── collab-{collaborationId}/
    │   └── revision-{deliverableId}-v{n}.{ext}
```

**Key:** NOT all files go to R2. Only **deliverables, contracts, and message attachments**. Free-text messages stay in the database (no R2 needed).

---

## 2. Component Architecture

### New Components to Create

#### 2.1 `CollaborationTimeline.tsx` (Main Container)

Props:
```typescript
{
  collaboration: Collaboration;
  events: TimelineEvent[];
  currentUserType: 'brand' | 'creator';
  onEventUpdate?: (event: TimelineEvent) => void;
}
```

Features:
- Scrollable timeline (vertical)
- Tag filter buttons at top: `[All] [Messages] [Proposals] [Contracts] [Deliverables] [Payments]`
- Renders TimelineEvent components based on tag
- Each event is a "card" with timestamp, sender avatar, and tag-specific UI

#### 2.2 `TimelineEvent.tsx` (Event Renderer)

Renders different UIs based on `event.tag`:

| Tag | Component | Renders |
|-----|-----------|---------|
| `message` | Chat bubble | Text + sender avatar, timestamp |
| `proposal` | ProposalCard | Offer/Counter card with amount, deliverables, deadline |
| `contract` | ContractCard | Contract status badge, link to full contract view |
| `deliverable` | DeliverableCard | Thumbnail + platform + status, [Approve] [Revise] [Dispute] buttons |
| `payment` | PaymentCard | Amount + type (locked/released), timestamp |
| `system` | SystemNotification | Info/warning banner with icon |

#### 2.3 `ProposalCard.tsx` (Deal Offer Display)

Shows:
- Brand avatar + "Sent a proposal"
- Amount (₹)
- Deliverables count (3 Reels + 5 Stories)
- Deadline
- Buttons: [Accept] [Counter] [Reject] [Message]

#### 2.4 `ContractCard.tsx` (Contract Status Display)

Shows:
- Contract status: Generated → Brand Signed → Creator Signed → Active
- Amount locked in escrow
- Buttons: [View Full Contract] [Signature Canvas] [Download]

#### 2.5 `DeliverableCard.tsx` (Content Submission Display)

Shows:
- Platform icon (Instagram/YouTube/TikTok)
- Video/Image thumbnail (from R2 URL)
- Status badge (Submitted / Review / Revision / Approved)
- Revision counter (0/2 revisions used)
- Buttons (varies by status):
  - **Submitted**: [Approve] [Request Revision] [Dispute]
  - **Revision**: [Approve] [Request Another Revision] (max 2)
  - **Approved**: [View Video] [Download]
- Revision feedback text if revision was requested

#### 2.6 `PaymentCard.tsx` (Payment Milestone Display)

Shows:
- ₹ amount
- Payment type: "Locked in Escrow" / "Milestone Released (₹32.5K)" / "Final Payout Completed"
- Timestamp
- Status badge (Pending / Completed)

#### 2.7 `ContractPanel.tsx` (Slide-over)

Triggered by [View Full Contract] button from ContractCard:
- Full contract preview (PDF-like styling)
- Contract clauses
- Escrow amount locked
- Signature section (read-only or signature canvas)
- Download PDF button

#### 2.8 `DeliverableReviewPanel.tsx` (Slide-over)

Triggered by [Approve] / [Request Revision] buttons from DeliverableCard:
- Video/Image player or preview (from R2 presigned URL)
- Brand feedback form (if revision requested)
- Upload new version button (creator only)
- Approve / Request Revision buttons
- Revision count display

---

## 3. Integration Points

### 3.1 Where Timeline Integrates

**File:** `brand-campaign-detail.tsx`

Current structure:
- Tabs: "Bids", "Active Collaborators", "Completed", "Analytics"
- Each tab shows separate list

**New structure:**
- Remove tabs → Show collaborations list with timeline panel
- Each collaboration card in the list shows: Avatar + Creator name + Status badge + "Open Timeline" button
- Click "Open Timeline" → Slide-over panel opens showing full CollaborationTimeline for that creator

**Alternative (Single Card Expansion):**
- Keep collaborations inline
- Each collab card is expandable (click to expand)
- Timeline shows inside expanded area

### 3.2 Menu Update

**Current Sidebar (5 items):**
```
Home
Campaigns
Creators
Wallet
Settings
```

**New Sidebar (still 5 items, but nav structure changes):**
```
Home
Campaigns
  ├── Campaigns List
  ├── (Timeline opened from Campaign Detail for each creator)
Creators
Wallet
Settings
```

**Change:** Messages, Deal Rooms, Contracts, Pipeline pages are **removed from the sidebar** and their functionality is **folded into Campaign Detail**.

---

## 4. File Operations (Create/Modify/Delete)

### Create These Files:

```
src/components/brand/timeline/
  ├── collaboration-timeline.tsx          (Main container)
  ├── timeline-event.tsx                  (Event renderer dispatcher)
  ├── event-cards/
  │   ├── proposal-card.tsx               (Offer/Counter display)
  │   ├── contract-card.tsx               (Contract status)
  │   ├── deliverable-card.tsx            (Content submission)
  │   ├── payment-card.tsx                (Payment milestone)
  │   └── system-notification.tsx         (Auto-notifications)
  ├── panels/
  │   ├── contract-panel.tsx              (Full contract view + signature)
  │   └── deliverable-review-panel.tsx    (Content review + video playback)
  └── hooks/
      └── use-timeline-filters.ts         (Tag filter state)
```

### Modify These Files:

| File | Changes |
|------|---------|
| `src/lib/types.ts` | Add `TimelineEvent` interface and `Attachment` type |
| `src/lib/upload.ts` | Add R2 path builder for contracts, deliverables, message attachments |
| `src/pages/brand-campaign-detail.tsx` | Replace tabs UI with collaboration list + timeline panel integration |
| `src/components/brand/brand-layout.tsx` | Remove sidebar items: Messages, Deal Rooms, Contracts, Pipeline |
| `src/App.tsx` | Remove routes: `/brand/messages`, `/brand/deals`, `/brand/contracts`, `/brand/pipeline` |

### Delete These Files:

```
src/pages/brand-messages.tsx
src/pages/brand-deals.tsx
src/pages/brand-contracts.tsx
src/pages/brand-pipeline.tsx
src/components/brand/deals/deal-room-dashboard.tsx
src/components/brand/contracts/contracts-and-deliverables.tsx
src/components/brand/messages/                    (entire directory if exists)
```

---

## 5. R2 Upload Logic

### When Uploads Happen:

| Event | Uploader | File Type | Path |
|-------|----------|-----------|------|
| **Contract Generated** | System (auto) | `.pdf` | `contracts/collab-{id}/contract-{contractId}.pdf` |
| **Creator Submits Deliverable** | Creator | `.mp4`, `.jpg`, etc. | `deliverables/collab-{id}/collab-{id}_reel-{n}_v1.mp4` |
| **Creator Requests Revision** | Creator uploads new version | Same type | `deliverables/collab-{id}/collab-{id}_reel-{n}_v2.mp4` |
| **Message with Attachment** | Brand or Creator | Any | `messages/collab-{id}/msg-{eventId}_attachment.{ext}` |

### Mock vs Real Upload:

**Current (Mock):**
- `uploadToR2()` in `upload.ts` simulates upload with fake progress
- Returns mock presigned URL like `https://r2.mock.example.com/contracts/...`

**Real (Future):**
- Replace with actual Cloudflare R2 presigned-URL endpoint
- Route Handler: `POST /api/upload` → returns real presigned URL
- Client uploads directly to R2 with presigned URL

---

## 6. UI/UX Flow

### Brand's View of One Creator Collaboration:

```
1. Click "Campaigns" → see campaign list
2. Click campaign "Summer Collection"
3. Campaign Detail opens, shows "Active Collaborators" section
4. See: "Sarah Johnson - Content Submitted (Awaiting Approval)"
5. Click "Open Timeline" on Sarah's card
6. Timeline panel slides in from right, shows chronological events:

   [All] [Messages] [Proposals] [Contracts] [Deliverables] [Payments]

   Jan 10  [system] Campaign invite sent
   Jan 11  [message] "Hi Sarah! Interested?" (text bubble)
   Jan 12  [proposal] Offer: ₹50K | 3 Reels | 30 days [Accept] [Counter] [Reject]
   Jan 13  [proposal] Counter: ₹65K | 3 Reels + 5 Stories [Accept] [Reject]
   Jan 13  [proposal] Accepted
   Jan 14  [contract] Contract auto-generated & locked in escrow [View] [Sign]
   Jan 15  [contract] Brand signed (awaiting creator)
   Jan 15  [contract] Creator signed — Contract ACTIVE
   Jan 20  [deliverable] Reel #1 submitted (thumbnail) [Approve] [Revise] [Dispute]
   Jan 21  [deliverable] Reel #1 v1 - Revision requested: "Color grade looks off"
   Jan 22  [deliverable] Reel #1 v2 submitted (thumbnail)
   Jan 22  [deliverable] Reel #1 v2 - APPROVED
   Jan 25  [payment] ₹32.5K released to creator (Milestone 1)
```

---

## 7. Implementation Phases

### Phase 1: Data Model + Components (Week 1)
- Add `TimelineEvent` type to `types.ts`
- Create `collaboration-timeline.tsx` (basic structure)
- Create all 5 event card components (proposal, contract, deliverable, payment, system)
- Add filter buttons (not functional yet)

### Phase 2: Integration into Campaign Detail (Week 1-2)
- Replace tabs UI in `brand-campaign-detail.tsx`
- Embed CollaborationTimeline into slide-over panel
- Wire up mock data from existing campaigns
- Test: click collab, timeline opens with correct events

### Phase 3: R2 Upload Logic (Week 2)
- Build `ContractPanel` and `DeliverableReviewPanel` slide-overs
- Update `upload.ts` with path builders
- Wire deliverable upload button to R2 mock upload
- Display R2 presigned URLs in video/image previews

### Phase 4: Navigation Cleanup (Week 2-3)
- Delete old pages: messages, deals, contracts, pipeline
- Remove routes from `App.tsx`
- Update sidebar in `brand-layout.tsx`
- Test all routes still work

---

## 8. Current to New: Feature Mapping

| Old Page | Feature | Maps To |
|----------|---------|---------|
| **Messages** | Text chat | TimelineEvent tag='message' |
| **Messages** | File attachments | TimelineEvent.attachments (uploaded to R2) |
| **Deal Rooms** | Offer/Counter cards | TimelineEvent tag='proposal' with metadata |
| **Contracts** | Contract preview | ContractPanel slide-over |
| **Contracts** | Signature | ContractPanel signature section |
| **Contracts** | Deliverables list | TimelineEvent tag='deliverable' cards |
| **Contracts** | Approve/Revise buttons | DeliverableCard buttons |
| **Contracts** | Revision form | DeliverableReviewPanel |
| **Contracts** | Escrow display | TimelineEvent tag='payment' + ContractCard |

---

## 9. Testing Checklist

- [ ] Timeline renders chronologically (oldest at bottom, newest at top)
- [ ] Tag filters work: click [Deliverables] → only deliverable events show
- [ ] Proposal card displays all fields (amount, deadline, deliverables)
- [ ] Contract card shows correct status (Generated → Signed → Active)
- [ ] Deliverable card shows: thumbnail, platform, status, revision counter
- [ ] Click [Approve] → approval button disabled, status changes to "Approved"
- [ ] Click [Request Revision] → panel opens, brand can leave feedback
- [ ] Click [View Full Contract] → ContractPanel slides in with contract details
- [ ] Payment card shows milestone releases with correct amounts
- [ ] System notifications appear for auto-generated events (contract generated, etc.)
- [ ] Old pages (Messages, Deals, Contracts) removed from sidebar
- [ ] Clicking old URL routes (e.g., `/brand/messages`) shows 404 or redirects to campaign
- [ ] R2 mock upload shows progress bar during file upload
- [ ] Presigned URLs work: clicking video thumbnail shows video preview

---

## 10. Questions for Clarification

1. **Video Playback:** Should we embed a video player in DeliverableReviewPanel, or just link to R2 URL?
   - Recommendation: Embed via `<video>` tag with R2 presigned URL
   
2. **Contract PDF:** Should contract be downloadable as PDF, or just HTML styled as PDF?
   - Recommendation: HTML styled as PDF (no server-side PDF generation needed for now)

3. **Revision Feedback:** Where should revision feedback text appear?
   - Recommendation: New system event in timeline: `[system] "Revision requested: Color grade looks off"` + show in DeliverableReviewPanel

4. **Message Reactions:** Should we support emoji reactions on messages/events?
   - Recommendation: Skip for now, add later if needed

5. **Timeline Auto-scroll:** Should new events auto-scroll to bottom, or stay where user is?
   - Recommendation: Stay where user is (unless they're at bottom), show badge "1 new event"

---

**Ready to proceed with Phase 1 implementation?**
