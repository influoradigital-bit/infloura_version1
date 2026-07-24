# Neha — Brand Nav Live UI Check (Orphaned Routes)

**Date:** 2026-07-23
**Tester:** Neha (QA)
**Environment:** Deployed app @ http://200.141.1.6 (brand side)
**Login:** demo.brand@influora.com (successful — lands on `/brand/dashboard`, `brand_token` set)
**Method:** In-app browser MCP. Navigated directly to each route by URL; read heading + content via get_page_text / DOM. No data changed.

## Brand sidebar (linked items — confirmed visible/clickable)
Home, Meera, Campaigns, Creators, Deals, Wallet. Plus a "New Campaign" button (top of sidebar) and a "Brand Account" button (bottom of sidebar).

## Top bar / account controls (confirmed present)
"Open menu" (mobile), Search (Cmd+K palette), Notifications button, User menu button.
Note: the User-menu dropdown items could not be expanded — the headless browser (0x0 viewport) would not open the React portal dropdown on click, so the individual account-menu links (e.g. settings/profile/logout) were not enumerated. Only the top-level trigger buttons above were confirmed.

## Orphaned brand routes

| Route | Renders? | Heading | Notes |
|-------|----------|---------|-------|
| /brand/analytics | real | Analytics Overview | Functional. "Creator performance metrics for your roster." KPI tiles (Total Reach, Engagement Rate, Total Engagements, Follower Growth), a Reach & Engagement trend chart, and a Creator Roster list (Demo Creator). Metrics data returned an authorization message: "This workspace is not authorized to view metrics for that creator" — page renders fine, data/authz is the only gap. No console errors. |
| /brand/contracts | real | Contracts & Deliverables | Functional. "Manage contracts, track deliverables, and review submissions." Status filter tabs (All / Active / Awaiting Signature / Pending Review / Draft). Empty state: "No contracts yet." No console errors. |
| /brand/messages | real | Messages | Functional WITH live data. Shows "1 unread", a conversation from Demo Creator ("hi", 12:46 PM) tied to campaign "QA E2E — Diwali Skincare Reels". No console errors. |
| /brand/disputes | real | Disputes | Functional. "Track disputes opened on your collaborations..." Proper empty state: "No disputes." No console errors. |
| /brand/reviews | real | Reviews | Functional. "Rate creators after completed collaborations and see feedback left about your brand." Two tabs (Rate creators / Reviews about you). Empty state: "No collaborations to review." No console errors. |
| /brand/pipeline | real | Pipeline | Functional Kanban WITH live data. "Track all collaborations across stages." Stage columns: Outreach, Negotiating, Contracted, In Progress, Review, Settled. 1 card in Contracted (Demo Creator — QA E2E — Diwali Skincare Reels). Minor data bug: budget renders as "₹null". No console errors. |

## Verdict
All six orphaned routes render as real, functional pages (no stubs, no errors, no redirects). Two already carry live data (messages, pipeline).

- **Candidates to add to brand nav (real + working):** analytics, contracts, messages, disputes, reviews, pipeline — all six.
- **Stub / broken (keep hidden):** none.

### Minor follow-ups (not blockers to linking)
- **/brand/pipeline** — "₹null" budget for the Contracted card (backend budget value null / formatting bug).
- **/brand/analytics** — metrics blocked by workspace authorization ("not authorized to view metrics for that creator"); page shell is fine but tiles show 0 / no trend data until authz is resolved.
