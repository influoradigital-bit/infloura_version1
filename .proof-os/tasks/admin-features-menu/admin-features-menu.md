# Influora Admin Panel — Features & Menus Reference

**Verified against rendered components** (not just types) by Priya (CTO) — 2026-08-09  
**Branch:** `fix/brand-audit-remediation`  
**Key files read:** `AdminLayout.tsx` · `admin-console.tsx` · `admin.types.ts` · all `src/admin/pages/*.tsx` · all `src/admin/components/**/*.tsx` · `api-contracts.ts` · `useAdminAuth.ts`

---

## 1 · Sidebar Navigation — 12 Menu Items

Source: `src/admin/components/AdminLayout.tsx:59–72`

| # | Label | Route | Icon | What renders there |
|---|-------|-------|------|--------------------|
| 1 | **Dashboard** | `/admin` | LayoutDashboard | CEO Pulse KPI cards + Red Flags |
| 2 | **Users** | `/admin/users` | Users | Brand & creator management, KYC, applications |
| 3 | **Campaigns** | `/admin/campaigns` | Megaphone | All campaigns — status, budget, SLA monitoring |
| 4 | **Finance** | `/admin/finance` | Wallet | Platform fee config only (FeeControlPanel) |
| 5 | **Revenue** | `/admin/revenue` | LineChart | Finance console — escrow, reconciliation, flags, ops |
| 6 | **Support** | `/admin/support` | LifeBuoy | Ticket queue — status, priority, thread view |
| 7 | **Moderation** | `/admin/moderation` | ShieldAlert | Content flag queue + approval workflows |
| 8 | **Disputes** | `/admin/disputes` | Scale | Brand ↔ creator disputes, escrow resolution |
| 9 | **Billing** | `/admin/billing` | CreditCard | MRR/ARR/churn, subscriptions, comp & override |
| 10 | **Audit Log** | `/admin/audit` | ScrollText | All admin mutations — IP, reason, source badge |
| 11 | **Error Log** | `/admin/errors` | AlertOctagon | System error log — severity, stack trace, resolve |
| 12 | **Email Queue** | `/admin/emails` | Mail | Transactional email status and retry view |

---

## 2 · Topbar (always visible)

Source: `AdminLayout.tsx:206–262`

| Element | Behaviour |
|---------|-----------|
| Page title `<h1>` | Current section name — set via `pageTitle` prop per page |
| Notifications bell | **Disabled placeholder** — no unread dot (no source wired yet) |
| Admin avatar + name | First letter of `adminName` as avatar; opens dropdown |
| **Logout** (dropdown) | `useAdminAuth().logout()` → redirects to `/admin/login`; shows "Logging out…" |

---

## 3 · Dashboard `/admin`

Source: `src/admin/components/dashboard/PulseDashboard.tsx:84–188`

### KPI Cards

| Card | Detail |
|------|--------|
| GMV | Total Gross Merchandise Value + WoW % change (`null` baseline = renders "—", never "0") |
| Revenue | Platform revenue + WoW % change |
| Escrow Float | Total funds locked in escrow |
| Brand MAU | Monthly active brands |
| Creator MAU | Monthly active creators |

### Operations KPI Row

| Card | Detail |
|------|--------|
| Active Campaigns | Count of currently running campaigns |
| Campaigns At Risk | Count with `timelineStatus = AT_RISK` or `DELAYED` |
| Review Backlog | Open moderation/approval items |
| Support Queue Depth | Open ticket count |
| Avg Review Time | Average hours for moderation decisions |

### Red Flags Panel

Auto-surfaced alerts — each shows message + severity badge. Entity links are **not** implemented (message text only).

| Flag type | Severity |
|-----------|----------|
| `ESCROW_LOW` | WARNING / CRITICAL |
| `SLA_BREACH` | WARNING / CRITICAL |
| `PAYOUT_DELAY` | WARNING / CRITICAL |
| `REVIEW_BACKLOG` | WARNING / CRITICAL |
| `SUPPORT_AGING` | WARNING / CRITICAL |

---

## 4 · Users `/admin/users`

Source: `src/admin/pages/UsersPage.tsx`

### Tab 1 — Brands

| Feature | Detail |
|---------|--------|
| Search | Brand name or email |
| KYC Status filter | All / Pending / Approved / Rejected |
| Suspended Only toggle | Filters `isSuspended = true` |
| Table columns | Brand (name + email), Industry + Size badge, KYC Status pill, Campaigns count, Total Spend (INR compact), Active/Suspended pill |
| Row click | Opens `/admin/users/brands/:id` — Brand Profile |

### Tab 2 — Creators

| Feature | Detail |
|---------|--------|
| Search | Creator name or email |
| Application Status filter | All / Pending / Approved / Rejected |
| Suspended Only toggle | Filters `isSuspended = true` |
| Table columns | Creator (name + email), Instagram handle, Followers (compact), Tier badge (NANO/MICRO/MID/MACRO), Application status pill, Active/Suspended pill |
| Row click | Opens `/admin/users/creators/:id` — Creator Profile |

### Tab 3 — Pending Applications

| Feature | Detail |
|---------|--------|
| Table columns | Creator (name + email), Followers, Tier, Status, Applied date |
| Row click | Opens Creator Profile — approve/reject action available on PENDING creators |

### Brand Profile Detail `/admin/users/brands/:id`

Source: `src/admin/components/users/BrandProfile.tsx`

| Feature | Detail |
|---------|--------|
| KYC review | Approve or Reject — **mandatory reason** → written to `audit_logs` |
| KYC documents | GST number, PAN, incorporation doc, billing address |
| Team members | Brand team list with roles |
| Campaign history | Campaigns linked to this brand |
| Payment history | Credit/debit records |
| Campaign budget override | Admin can override campaign budget |
| Suspend / Reinstate | Toggle `isSuspended` — reason required |

### Creator Profile Detail `/admin/users/creators/:id`

Source: `src/admin/components/users/CreatorProfile.tsx`

| Feature | Detail |
|---------|--------|
| Application review | Approve or Reject — quality score + **mandatory reason** → audit log |
| Platform stats | Avg reach, avg engagement, posts/reels last 30 days |
| Quality metrics | Deadline adherence %, revision rate, dispute rate, overall score (0–100) |
| Collaboration history | Past campaigns, brand, amount, status |
| Flagged content count | Count of open `ContentFlag` rows (scalar only — not full list) |
| Tier adjustment | Change NANO/MICRO/MID/MACRO — reason → audit log |
| Verify Instagram | Admin Instagram verification action |
| Suspend / Reinstate | Toggle `isSuspended` — reason required |

---

## 5 · Campaigns `/admin/campaigns`

Source: `src/admin/components/campaigns/CampaignTable.tsx:376–468`

| Feature | Detail |
|---------|--------|
| Search | Campaign name |
| Status filter | DRAFT / PENDING_APPROVAL / ACTIVE / PAUSED / COMPLETED / CANCELLED |
| Type filter | STANDARD / HYPE |
| At Risk Only toggle | Surfaces `AT_RISK` + `DELAYED` timeline campaigns |
| Table columns (6) | Campaign, Brand, Status, Budget (INR), Dates, Performance |
| Campaign detail | Brief, deliverable requirements, contracted creators, escrow balance, timeline status |

> ⚠️ No brand-ID dropdown filter in the UI — exists on the type only.

---

## 6 · Finance `/admin/finance`

Source: `src/admin/components/finance/FeeControlPanel.tsx`  
**This route mounts FeeControlPanel only** — escrow and reconciliation are in Revenue, not here.

| Feature | Detail |
|---------|--------|
| **View fee config** | Current brand fee % (approved: 10%), creator fee % (approved: 15%), Razorpay absorbed by platform flag |
| **Update fee config** | SUPER_ADMIN only — edit fees with **mandatory reason** + optimistic concurrency token → audit log |
| Fee change history | Audit trail: old %, new %, changed by, reason, timestamp |

---

## 7 · Revenue `/admin/revenue`

Source: `src/admin/components/finance/FinanceConsole.tsx`

### KPI Strip (4 cards)

| Card | Detail |
|------|--------|
| Total Revenue | Platform revenue in period |
| Escrow Held | Funds locked in escrow |
| Pending Payouts | Creator payouts awaiting processing |
| Flagged Transactions | Escrow rows flagged for review |

### 8 Tabs in FinanceConsole

| Tab | Content |
|-----|---------|
| **Overview** | Revenue snapshots by period — GMV, platform fees, setup fees, total revenue |
| **Escrow** | Escrow summary — total locked, pending release, flagged count, avg release time (hours) |
| **Reconciliation** | Razorpay ID ↔ internal ID: razorpay amount vs internal amount, variance, MATCHED/MISMATCH/PENDING |
| **Payouts** | *(type defined, endpoint removed 2026-08-04 — no UI rendered)* |
| **Flagged Escrow** | Flagged escrow transactions for review |
| **At-Risk Campaigns** | Campaigns with escrow or timeline risk |
| **HYPE Ops** | HYPE-type campaign operations view |
| **Suspensions** | Suspended account list — read-only, appeal status display only *(reinstate not built)* |

> ⚠️ Payout queue endpoint (`GET /admin/finance/payouts`) was removed 2026-08-04 — no payout queue UI exists.  
> ⚠️ Appeal review (PENDING/REVIEWED) shown as read-only status only — workflow not built.

---

## 8 · Support `/admin/support`

Source: `src/admin/components/support/TicketList.tsx:576–656`

| Feature | Detail |
|---------|--------|
| Search | Keyword filter across tickets |
| Status filter | OPEN / IN_PROGRESS / WAITING_USER / RESOLVED / CLOSED |
| Priority filter | LOW / MEDIUM / HIGH / URGENT |
| Table columns (6) | Subject, Requester, Status, Priority, Assigned To, Last Updated |
| Ticket detail | Full message thread (user ↔ admin), related entities (campaign/deliverable/payment/contract) |

> ⚠️ No user-type filter, assigned-to filter, or category filter in the rendered UI — type-only.

---

## 9 · Moderation `/admin/moderation`

Source: `src/admin/components/moderation/FlagQueue.tsx` · `src/admin/components/moderation/ApprovalQueue.tsx`

### Flag Queue

| Feature | Detail |
|---------|--------|
| Content types | DELIVERABLE / PROFILE / MESSAGE |
| Flag sources | AI / USER / ADMIN |
| Status flow | PENDING → ESCALATED → REVIEWED → ACTIONED |
| **Available actions (3)** | **REMOVE / REJECT / ESCALATE** *(APPROVE and WARN buttons not built yet)* |
| Reason required | Every action requires reason text |

### Approval Queue

| Feature | Detail |
|---------|--------|
| Workflow types | CREATOR_APPLICATION / BRAND_KYC / DELIVERABLE_DISPUTE / ESCROW_RELEASE / CONTENT_MODERATION / ACCOUNT_SUSPENSION |
| Workflow type filter | Filter by workflow type |
| Actions | Approve / Reject with notes |

---

## 10 · Disputes `/admin/disputes`

Source: `src/admin/components/disputes/DisputeList.tsx`

| Feature | Detail |
|---------|--------|
| Table columns (6) | Dispute ID, Campaign, Brand, Creator, Status, Created |
| Status filter | OPEN / UNDER_REVIEW / RESOLVED_BRAND / RESOLVED_CREATOR / RESOLVED_SPLIT |
| Campaign-ID filter | Filter by campaign |
| Row click → Resolve modal | Full detail: opened by, reason, collaboration ID |
| **Resolve: Creator wins** | `RESOLVED_CREATOR` — releases frozen escrow in full to creator |
| **Resolve: Brand wins** | `RESOLVED_BRAND` — refunds frozen escrow in full to brand |
| **Resolve: Split** | `RESOLVED_SPLIT` — admin sets creator split % (0–100); brand gets remainder |
| Mandatory reason | Every resolution requires notes → audit log |

---

## 11 · Billing `/admin/billing`

Source: `src/admin/components/billing/BillingConsole.tsx`

| Feature | Detail |
|---------|--------|
| **MRR / ARR / Churn overview** | Monthly recurring revenue (INR), annual recurring revenue (INR), churn % |
| Active Pro count | Count of active paid subscriptions |
| Search subscriptions | Filter by workspace name |
| Status filter | Filter by subscription status |
| Table columns (5) | Workspace, Plan, Status, Current Period, Seats |
| **Comp Pro** | Grant complimentary subscription — workspace, plan, reason (≥10 chars), optional expiry |
| **Override subscription** | Force plan to any tier — workspace, plan, reason, optional expiry |

---

## 12 · Audit Log `/admin/audit`

Source: `src/admin/pages/AuditLogPage.tsx:141–276`

| Feature | Detail |
|---------|--------|
| Entity type filter | Filter by entity type |
| Action filter | Filter by action verb |
| Date range filter | startDate, endDate |
| Table columns (8) | Timestamp, Admin, Action, Entity, Change (old→new), Reason, IP Address, **Source badge** |
| **Source badge** | `SERVER_INTERNAL` (authoritative) vs `CLIENT_REPORTED` (self-reported, unverified) — always displayed |

> ⚠️ No "admin email" filter in the UI.

---

## 13 · Error Log `/admin/errors`

Source: `src/admin/pages/ErrorLogPage.tsx:150–192`

### KPI Cards (3)

| Card | Detail |
|------|--------|
| Total Errors | Count in window |
| Critical Errors | CRITICAL severity count |
| Top Endpoints (24h) | Endpoints generating the most errors |

### Error Table

| Feature | Detail |
|---------|--------|
| Table columns | Severity (ERROR/WARN/CRITICAL), Message, Endpoint, User ID, Resolved flag, Timestamps |
| Stack trace | Expandable per error |
| **Mark resolved** | Admin marks resolved → resolver name + timestamp recorded |

> ⚠️ No severity or resolved/unresolved filter controls — table is unfiltered.

---

## 14 · Email Queue `/admin/emails`

Source: `src/admin/pages/EmailQueuePage.tsx:242–306`

### KPI Cards (4+)

| Card | Detail |
|------|--------|
| Sent 24h | Emails successfully sent in last 24 hours |
| Failed 24h | Failed sends in last 24 hours |
| Pending | Emails awaiting send |
| *(+1 more)* | Additional KPI card |

### Email Table

| Feature | Detail |
|---------|--------|
| Table columns (7) | Recipient, Template, Status (PENDING/SENT/FAILED/RETRYING), Retry count, Scheduled at, Sent at, **Action (retry)** |
| Error message | Failure reason shown for FAILED/RETRYING rows |

---

## 15 · Admin Roles (RBAC)

Source: `src/admin/hooks/useAdminAuth.ts:124–167` — full `ROLE_PERMISSIONS` matrix (not the type file)

| Role | Key Permissions |
|------|----------------|
| `SUPER_ADMIN` | All permissions — fee config changes, billing override, all mutations |
| `ADMIN` | User management (KYC/suspend), campaigns, disputes, moderation, support, escrow view/manage, payout manage, finance view, audit view, error log view, email manage, marketing view |
| `SUPPORT` | Dashboard view, support tickets, campaign view (context), moderation view |

---

## 16 · Auth & Security

Source: `AdminAuthDtos.java` · `AdminLayout.tsx` · migration files

| Feature | Detail |
|---------|--------|
| Login | Email + password → `POST /admin/auth/login` |
| MFA | TOTP code — optional per admin (`mfaEnabled` flag on `AdminUser`) |
| Login lockout | `V20260712130000__admin_login_lockout.sql` |
| MFA lockout | `V20260712140000__admin_mfa_lockout.sql` |
| Refresh token | Cookie-based → `AdminAuthDtos.RefreshRequest` |
| Audit logger init | `initAuditLogger()` on every AdminLayout mount — flushes queued offline events |
| Session logout | `useAdminAuth().logout()` → `/admin/login` |

---

*Verified: Priya (CTO) — 2026-08-09 · PARTIAL → corrected · source: rendered components, not types*
