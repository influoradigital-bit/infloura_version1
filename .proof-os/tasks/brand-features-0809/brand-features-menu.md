# Influora — Brand Features & Menus
> Source: `src/components/brand/brand-layout.tsx`, `src/components/brand/command-bar.tsx`, `src/components/brand/dashboard/dashboard-page.tsx`, `src/components/brand/campaigns/campaigns-list.tsx`, `src/components/brand/discover/creator-discovery.tsx`
> Branch: `fix/brand-audit-remediation` · Date: 2026-08-09

---

## 1. Sidebar Navigation

### 1a. MAIN Group

| # | Menu Label | Route | Icon | Description |
|---|-----------|-------|------|-------------|
| 1 | **Home** | `/brand/dashboard` | Home | Action items, pipeline snapshot, wallet balance, TrendSpark nudge |
| 2 | **Meera** | `/brand/meera` | Sparkles | AI brand co-pilot — campaign creation, insights, recommendations |
| 3 | **Campaigns** | `/brand/campaigns` | Megaphone | Create, list, filter, manage all brand campaigns |
| 4 | **Creators** | `/brand/discover` | Users2 | Discover & filter creators; bookmark; send proposals |
| 5 | **Deals** | `/brand/deals` | MessageCircle | Deal Room dashboard — proposals, negotiations, payments, deliverables |
| 6 | **Messages** | `/brand/messages` | MessageSquare | Direct messaging with creators |
| 7 | **Wallet** | `/brand/wallet` | Wallet | Recharge, view balance, escrow, transactions |

### 1b. MANAGE Group

| # | Menu Label | Route | Icon | Description |
|---|-----------|-------|------|-------------|
| 1 | **Pipeline** | `/brand/pipeline` | KanbanSquare | Kanban-style view of all active creator deals |
| 2 | **Contracts** | `/brand/contracts` | FileText | View and manage signed contracts & deliverables |
| 3 | **Analytics** | `/brand/analytics` | BarChart3 | Campaign performance analytics |
| 4 | **Reviews** | `/brand/reviews` | Star | Rate and review creators post-campaign |
| 5 | **Disputes** | `/brand/disputes` | AlertTriangle | Raise and track payment/deliverable disputes |

---

## 2. Avatar Menu (bottom of sidebar)

| Menu Item | Route | Description |
|-----------|-------|-------------|
| **Settings** | `/brand/settings` | Workspace & account settings |
| **Help & Support** | `/brand/help` | Documentation and support |
| **Log out** | — | Signs out, clears token, redirects to `/brand/login` |

---

## 3. Top Header Controls

| Control | Trigger | Description |
|---------|---------|-------------|
| **Search / Command Bar** | Click or `Cmd+K` | Full command palette — quick actions, navigate, search |
| **Notifications Bell** | Click | Popover showing unread notifications with badge count; Mark all read |
| **Mobile Hamburger** | Click (mobile only) | Slides in full nav sheet |
| **Mobile User Menu** | Click avatar (mobile) | Settings, Help, Log out |

---

## 4. Command Bar (⌘K)

### 4a. Quick Actions

| Action | Shortcut | Route |
|--------|---------|-------|
| Create New Campaign | `⌘C` | `/brand/campaigns/new` |
| Find Creators | `⌘F` | `/brand/discover` |
| Recharge Wallet | `⌘W` | `/brand/wallet` |

### 4b. "Go To" Navigation Group (rendered at runtime)

| Item | Description | Route |
|------|-------------|-------|
| Now | Action items & priorities | `/brand/dashboard` |
| Campaigns | Manage campaigns | `/brand/campaigns` |
| Discover | Find creators | `/brand/discover` |
| Deal Rooms | Negotiations | `/brand/chat` ⚠️ |
| Contracts | Agreements & deliverables | `/brand/contracts` |
| Wallet | Balance & transactions | `/brand/wallet` |
| Settings | Workspace settings | `/brand/settings` |

> ⚠️ **Route divergence (live source):** The Command Bar routes "Deal Rooms" to `/brand/chat` while the sidebar routes "Deals" to `/brand/deals`. These are two different routes in the codebase (`command-bar.tsx:62` vs `brand-layout.tsx:100`).

---

## 5. Feature Detail by Section

### 5a. Home / Dashboard (`/brand/dashboard`)

| Feature | Description |
|---------|-------------|
| Action Items | Prioritised list: deliverable review, counter proposal, payment release, sign contract |
| Pipeline Snapshot | Overview of active deal stages |
| Wallet Widget | Available balance (₹), escrow locked (₹), runway days |
| TrendSpark Nudge | AI-powered trend recommendation card |

### 5b. Campaigns (`/brand/campaigns`)

| Feature | Description |
|---------|-------------|
| Campaign List | Grid or List view toggle |
| Search | Filter campaigns by name |
| Status Filter | Filter by Draft / Active / Paused / Completed |
| Sort | Sort by date, budget, progress |
| New Campaign | Button + route `/brand/campaigns/new` |
| Campaign Card Actions | View · Edit · Duplicate · Pause/Resume · Delete · Save as Template |
| Campaign Metrics | Creator count, budget/spend, progress bar, trending |

### 5c. Creators / Discover (`/brand/discover`)

| Feature | Description |
|---------|-------------|
| Search | Search by name or handle |
| Advanced Filters | Platform (Instagram/YouTube/etc.), followers range (slider), location, niche, language (10 Indian languages), price range per post (₹5K–₹2L slider), engagement rate range (0%–15%), verified creators only (toggle) |
| Grid / List Toggle | Switch card view |
| Bookmark Creator | Save creators to a shortlist |
| Send Proposal | Initiate a deal directly from creator card |

### 5d. Deals (`/brand/deals`)

| Feature | Description |
|---------|-------------|
| Deal Room Dashboard | Overview of all active deals |
| Step Progress Tracker | Visual pipeline: Proposal → Contract → Deliverable → Payment |
| Bid / Proposal Card | View and respond to creator proposals |
| Campaign Brief Card | Share brief inside deal room |
| Contract Tab | View, sign, manage contract per deal |
| Deliverables Tab | Review submitted deliverables, approve/reject |
| Payments Tab | Release escrow payment milestone |
| Proposal Form | Create or counter a proposal |
| Shipment Form | Enter/track physical product shipment (if applicable) |

### 5e. Messages (`/brand/messages`)

| Feature | Description |
|---------|-------------|
| Direct Messaging | 1:1 chat with creator |
| Timeline Events | Contract, deliverable, payment, system events inline |

### 5f. Wallet (`/brand/wallet`)

| Feature | Description |
|---------|-------------|
| Available Balance | Real-time ₹ balance |
| Escrow Locked | Funds held in escrow for active deals |
| Recharge | Add funds to wallet |
| Transaction History | List of credits/debits |

### 5g. Pipeline (`/brand/pipeline`)

| Feature | Description |
|---------|-------------|
| Kanban Board | Deals by stage: Negotiation → Contracted → In Progress → Completed |

### 5h. Contracts (`/brand/contracts`)

| Feature | Description |
|---------|-------------|
| Contract List | All signed contracts with status |
| Deliverables View | Per-contract deliverable checklist |

### 5i. Analytics (`/brand/analytics`)

| Feature | Description |
|---------|-------------|
| Campaign Performance | Reach, engagement, spend vs. results |

### 5j. Reviews (`/brand/reviews`)

| Feature | Description |
|---------|-------------|
| Rate Creator | Star rating + comment after campaign completion |
| Review History | Past reviews given |

### 5k. Disputes (`/brand/disputes`)

| Feature | Description |
|---------|-------------|
| Raise Dispute | Flag a payment or deliverable issue |
| Dispute Status | Track open / resolved disputes |

### 5l. Settings (`/brand/settings`)

| Feature | Description |
|---------|-------------|
| Workspace Settings | Brand profile, company details, team members |
| Account Settings | Email, password, notifications preferences |

---

## 6. Summary Count

| Category | Count |
|----------|-------|
| MAIN nav items | 7 |
| MANAGE nav items | 5 |
| Avatar menu items | 3 (Settings, Help, Logout) |
| Header controls | 4 (Search, Notifications, Mobile Hamburger, Mobile Avatar) |
| Command Bar quick actions | 3 |
| **Total nav + control surfaces** | **22** |

---

*Source: read from live branch `fix/brand-audit-remediation`. No mock data included.*
