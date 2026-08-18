# CEO Ruling: Application History Display Decisions

**Date:** 2026-08-18  
**Authority:** Swapnil Maruti (CEO, Sage Digital)  
**Scope:** F-0287, F-0302, F-0303, F-0327, and the Section 4/7 ordering conflict  
**Binding on:** All agents. This ruling supersedes the standing Kabir R5 arbitration where explicitly stated.

---

## Decision 1: F-0287 / F-0303 — May a creator be shown the word "Rejected"?

### Ruling: UPHOLD THE EXISTING ARBITRATION (with clarification)

**What renders on screen:**
- **Event label:** `Closed` (not "Rejected")
- **Event icon:** Archive (neutral, not a red X)
- **Description:** `This application was closed by the brand` (with reason appended if provided)
- **Badge/bucket:** `Closed` in outline/muted styling

**Reasoning from the creator's perspective:**

The requirements document asks for "Application Rejected" with the explicit word. Kabir's R5 finding says never surface "Rejected" because it presents "brand-internal triage as a decision the brand hasn't finalized."

Kabir's reasoning is slightly wrong in its justification — if the brand clicked decline, that IS finalized, not internal triage. However, his conclusion is RIGHT for a different reason: **the word "Rejected" carries unnecessary emotional weight that harms creator retention without adding clarity.**

A creator needs to know three things:
1. This application is no longer active
2. The brand made this decision (not the creator, not the system)
3. Why (if the brand provided a reason)

The current implementation delivers all three. The label "Closed" combined with the description "This application was closed by the brand: [reason]" communicates exactly what happened. The creator understands unambiguously. The word "Rejected" adds nothing except a sharper sting.

**What to tell the customer:**

The requirements document asks for "Rejected" wording. We are not following that literally because:
1. "Closed" plus the description achieves the same clarity
2. "Rejected" is an emotionally loaded term that platform UX conventions (LinkedIn, Upwork, Fiverr) have moved away from
3. The creator still sees WHO closed it and WHY — the information is complete

If the customer insists on "Rejected," they may override this ruling, but we recommend against it.

---

## Decision 2: F-0327 — What does a creator see when a brand accepts?

### Ruling: CHANGE THE CARD BADGE FOR TERMS_AGREED

**Current behavior (WRONG):**
- Timeline event shows: `Application Accepted`
- Card badge shows: `In negotiation`

**Required behavior:**
- Timeline event shows: `Application Accepted` (no change)
- Card badge shows: `Accepted`

**What renders on screen:**
- Badge text: `Accepted`
- Badge styling: Success (green, same as "Active" and "Completed")

**Reasoning from the creator's perspective:**

When a creator looks at their application list and sees "In negotiation" for an application the brand just accepted, they experience confusion and anxiety. "Wait, did they accept or are we still negotiating?" The requirements document is explicit: "the creator immediately sees the accepted status."

The status `TERMS_AGREED` is a technical state meaning "both parties agreed, contract pending." But the creator's mental model is simpler: "Did the brand say yes or not?" If yes, show "Accepted."

**Implementation note:**

In `src/lib/application-status.ts`, change:
```
TERMS_AGREED: 'In negotiation',
```
to:
```
TERMS_AGREED: 'Accepted',
```

And update `STATUS_BUCKETS` to map `TERMS_AGREED` to a bucket that gets success styling (or create a new bucket if needed). The exact implementation is for Priya/Arjun to determine, but the user-facing string must be "Accepted."

---

## Decision 3: F-0302 — APPLICATION_ACCEPTED fires for either party

### Ruling: VARY THE LABEL BY ACTOR

**Current behavior:**
- Both brand-accepts and creator-accepts produce event type `APPLICATION_ACCEPTED`
- Both display as "Application Accepted"
- Actor shows "Brand" or "You"

**Problem:**
When a creator accepts a brand's counter-offer, they see "Application Accepted" with actor "You." This reads as "You accepted your own application" — nonsensical. The creator accepted the TERMS, not the application.

**Required behavior:**

The underlying event type `APPLICATION_ACCEPTED` stays unchanged in the database (no schema migration). The UI label varies based on actor:

| Actor | Label to display |
|-------|------------------|
| BRAND | Application Accepted |
| CREATOR | Proposal Accepted |

**What renders on screen:**
- When brand accepts: `Application Accepted` (actor: brand name)
- When creator accepts: `Proposal Accepted` (actor: You)

**Reasoning from the creator's perspective:**

The brand accepting the creator's pitch is "accepting the application." The creator accepting the brand's counter-offer is "accepting the proposal." These are semantically different acts and should be labeled accordingly. The description field already says "[Actor] accepted the proposal" — the label should match.

**Implementation note:**

In `ApplicationHistoryTimeline.tsx`, the `eventTypeLabel` function should check both `eventType` and `actorType`. If `eventType === 'APPLICATION_ACCEPTED'` and `actorType === 'CREATOR'`, return `'Proposal Accepted'`. Otherwise return `'Application Accepted'`.

---

## Decision 4: Section 4 vs Section 7 ordering — Deliverables vs Escrow first

### Ruling: SECTION 7 IS CORRECT. NO CHANGE NEEDED.

**The conflict:**
- Section 4 of the requirements doc lists: "... Deliverables, Fund Escrow, Deliver, Pay"
- Section 7 of the requirements doc orders: "Negotiate, Contract, Fund Escrow, Deliver, Pay"

**Correct order (what the product does and must continue to do):**
1. Negotiate
2. Contract
3. **Fund Escrow** (money is locked)
4. **Deliver** (creator does the work)
5. Pay

**Reasoning from both perspectives:**

This is not a display preference — it is how money-protected creator marketplaces work. The escrow-before-delivery model protects the creator:

1. Brand funds escrow (money is locked in platform custody)
2. Creator delivers content (knowing the money exists)
3. Brand approves deliverable
4. Creator gets paid (escrow releases)

If deliverables came before escrow, a creator would do work with no guarantee of payment. That is the model this platform exists to replace.

Section 4's ordering is simply an error in the requirements document — an inconsistency the document author did not catch. Section 7 and the shipped stepper are correct.

**What to tell the customer:**

Your requirements document contradicts itself on this point. We followed Section 7 (and the UI mockup showing the stepper), which matches how escrow-protected creator payments work. Section 4's ordering would leave creators unprotected. No change is warranted.

---

## Summary of Changes Required

| ID | Decision | Action |
|----|----------|--------|
| F-0287/F-0303 | "Rejected" wording | **No change.** Existing "Closed" + description is correct. |
| F-0327 | Card badge for accepted | **Change.** `TERMS_AGREED` must display as "Accepted", not "In negotiation". |
| F-0302 | Accept event label | **Change.** Label varies: "Application Accepted" for brand, "Proposal Accepted" for creator. |
| Ordering | Section 4 vs 7 | **No change.** Section 7 / escrow-before-delivery is correct. |

---

## Overruled Arbitrations

**Kabir R5 (application-status.ts header comment):** PARTIALLY UPHELD.

The conclusion (no "Rejected" label) stands. The stated reasoning ("brand-internal triage as a decision the brand hasn't finalized") is incorrect — a brand's decline IS finalized. The correct reasoning is emotional impact on creator retention without informational benefit.

The arbitration header comment should be updated to reflect this ruling's reasoning if the team prefers accurate documentation, but no code change is needed for F-0287/F-0303.

---

**Signed:** Swapnil Maruti, CEO  
**Date:** 2026-08-18

---

## Decision 5: Filter bucket for TERMS_AGREED

### Ruling: MOVE TERMS_AGREED TO THE `active` BUCKET

**Current behavior (creates mismatch with Decision 2):**
- Card badge: `Accepted` (per Decision 2)
- Filter bucket: `in_negotiation` (appears under "In negotiation" tab)

**Required behavior:**
- Card badge: `Accepted` (unchanged from Decision 2)
- Filter bucket: `active` (appears under "Active" tab)

**What changes in `src/lib/application-status.ts`:**

In `STATUS_BUCKETS`, change:
```
TERMS_AGREED: 'in_negotiation',
```
to:
```
TERMS_AGREED: 'active',
```

No change to `APPLICATION_BUCKETS` (the tab list stays the same six tabs in the same order).

### Reasoning from the creator's perspective

When a creator receives the notification "Your application was accepted!" and opens their applications list, where do they look?

They scan the tabs: Applied, Shortlisted, In negotiation, Active, Completed, Closed.

- **"Applied"** — no, that's behind me
- **"Shortlisted"** — no, I got past that
- **"In negotiation"** — this implies we're still haggling; we're not, they said yes
- **"Active"** — yes, this is where accepted work belongs; I'm now working with this brand

A badge reading "Accepted" inside a tab called "In negotiation" is a contradiction the creator will notice and find confusing. It undermines the very reassurance Decision 2 was meant to provide.

### The technical objection and why it does not outweigh the UX concern

TERMS_AGREED is genuinely pre-contract. `DealService.doAccept` transitions to TERMS_AGREED and no contract row exists at that point. Every backend mapper (DashboardService, AdminCampaignService, CreatorApplicationMapper) agrees it is structurally "negotiating" in lifecycle terms.

However:

1. **CONTRACT_PENDING is already in the `active` bucket**, and it is also pre-signed (the contract exists but both parties have not yet signed). So "Active" already includes states where the contract is not finalized. The bucket boundary is not "contract exists and is signed."

2. **The creator's mental model is simpler than our lifecycle model.** The creator does not distinguish "accepted but no contract row" from "contract row exists but unsigned." Both feel like "the deal is happening, paperwork in progress." The meaningful boundary to a creator is: "Have they said yes?" After that, the creator considers themselves active.

3. **The alternative is worse.** Leaving TERMS_AGREED under "In negotiation" with an "Accepted" badge actively misleads the creator into thinking they're still waiting for a decision. The badge/bucket mismatch creates cognitive dissonance rather than resolving it.

### What this does NOT change

- **The DealStage mapping in `deal-stage.ts`** stays as-is. That maps TERMS_AGREED to `'negotiating'`, which is correct for its purpose (determining whether further negotiation is possible). The application-status buckets serve a different purpose: where a creator looks to find their work.

- **The backend mappers** stay as-is. They are correct for their contexts. The FE bucket is a UI concern, not a system-of-record concern.

### What I would need to be more confident

This ruling is based on reasoning about creator behavior, not observed creator behavior. If we had support ticket data or session recordings showing where creators actually look for accepted applications, that would be stronger than inference. I am confident enough to rule, but if the change produces confusion (creators expecting contract details in the Active tab and finding none), we should revisit.

---

## Decision 6: APPLICATION_VIEWED on the campaign-detail Bids tab

### The situation (verified)

There are two brand surfaces where a brand reviews creator applications:

1. **Deal Room Dashboard** (`/brand/deals/:id`): Opening a specific deal calls `GET /deals/{id}`, which records `APPLICATION_VIEWED` via `DealService.get()` (line 170-185). This is idempotent (first view only) and working correctly.

2. **Campaign-detail Bids tab** (`/brand/campaigns/:id` -> Bids): Renders ALL applicants inline from a single `api.deals.list('brand', 'all')` call (line 619). Accept/Counter/Reject actions call their respective endpoints directly on the bid ID without ever calling `GET /deals/{id}`. No view event is recorded.

The requirements document (Section 5) says the creator must see "Viewed by Brand + date/time" as the minimum brand-activity signal.

### The tension

The obvious fix — record a view for each bid rendered on the Bids tab — is **wrong**. The page renders every applicant at once, so this would stamp "Brand viewed your application" onto applications the brand may have scrolled straight past without reading. That writes a false claim into an immutable, append-only ledger the creator reads and is meant to trust.

The existing test file (`deal-room-dashboard-view-recorded.test.tsx`) already articulates this concern in its header comment (lines 9-14):

> "Rendering the list is not 'opening' any one deal, so a list-level fetch must not be mistaken for a per-deal view: recording a view for an application the brand never actually opened would put a false claim in the creator's audit trail, which is worse than recording nothing."

### Ruling: OPTION (C) — RECORD VIEW ON ACTION, NOT ON RENDER

**What changes:**

When a brand takes an ACTION on a specific bid from the Bids tab (Accept, Counter, or Reject), record an `APPLICATION_VIEWED` event immediately before recording the action event — but only if no view event already exists for that application.

**Implementation:**

In `DealService.java`, at the top of `doAccept()`, `doReject()`, and `doCounter()`, add:

```java
if (principal.getUserType() == UserType.BRAND) {
    applicationHistoryService.recordViewIfAbsent(
        collaboration.getCampaignId(),
        collaboration.getId(),
        principal.getUserId(),
        "Brand reviewed the application",
        collaboration.getStatus());
}
```

This is the same pattern already used in `get()`. The idempotency check in `recordViewIfAbsent` means:
- If the brand already opened this deal in the Deal Room, no duplicate view is recorded
- If the brand is acting directly from the Bids tab, a view is recorded just before the action

**What does NOT change:**

- List rendering on the Bids tab does NOT record views
- The Deal Room path continues to record views on open

### Reasoning from the creator's perspective

1. **A view must mean something.** If "Viewed by Brand" appears every time the brand opens ANY campaign with pending applications, it becomes noise. The creator cannot distinguish "they read my pitch" from "my pitch appeared on a list they scrolled past." A signal that lies is worse than no signal.

2. **An action proves engagement.** If the brand clicks Accept, Counter, or Reject on a specific bid, they have definitively engaged with that one application. This is not internal workflow leaking — it is a real, per-application interaction. Recording a view at that moment is honest.

3. **The creator's timeline reads naturally.** With this change:
   - Brand opens deal in Deal Room -> creator sees "Viewed by Brand" -> later "Application Accepted"
   - Brand acts from Bids tab without opening -> creator sees "Viewed by Brand" -> immediately "Application Accepted"
   
   Both paths produce a coherent timeline. The creator never sees an Accept/Reject appear from nowhere with no prior brand activity.

4. **Absence of view is still meaningful.** If an application sits at "Campaign Applied" with no view for days, that means the brand has neither opened it in the Deal Room nor taken any action on it. This is accurate — and more useful than a false "Viewed" that fires the moment the brand opens the campaign detail page.

### What to tell the customer

The requirements document asks for "Viewed by Brand + date/time" as the minimum brand-activity signal. This is implemented, with one design choice:

**The "Viewed" event reflects genuine per-application engagement, not page-level rendering.** If a brand opens a campaign detail page that lists 50 applicants, we do not record 50 views — that would be a lie. A view is recorded when the brand either:
1. Opens that specific application in the Deal Room, OR
2. Takes an action (accept, counter, reject) on that specific application

This ensures the creator can trust the signal. "Viewed by Brand" means the brand actually looked at YOUR pitch, not that your pitch appeared somewhere on a list they may have scrolled past.

If the customer wants "page-appeared" tracking for analytics purposes, that is a separate metric and should NOT be surfaced as "Viewed" in the creator's timeline.

### What I would need to be more confident

This ruling is based on reasoning about what a creator SHOULD be able to infer, not data on what creators actually infer. If we had:
- Support tickets from creators asking "why does it say the brand viewed my application but they never responded?"
- Session recordings showing how creators interpret the timeline

...that would validate or challenge this reasoning. I am confident enough to rule because the principle — don't lie to people about who looked at their work — is sound regardless of observed behavior.

---

**Signed:** Swapnil Maruti, CEO  
**Date:** 2026-08-18
