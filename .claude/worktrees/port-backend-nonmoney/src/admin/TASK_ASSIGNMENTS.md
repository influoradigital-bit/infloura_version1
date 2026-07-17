# 🎯 ADMIN PANEL — TASK ASSIGNMENTS

> **Project:** Influora Admin Panel  
> **Assigned by:** Swapnil (CEO)  
> **Coordinated by:** Priya (CTO) + Arjun (COO)  
> **Date:** July 9, 2026  
> **Spec Reference:** `docs/ADMIN-PANEL-SPEC.md`

---

## 📁 FOLDER STRUCTURE

```
src/admin/
├── components/
│   ├── dashboard/      # CEO Pulse, KPI widgets
│   ├── users/          # Brand + Creator profile management
│   ├── campaigns/      # Campaign monitoring
│   ├── finance/        # Revenue, escrow, TDS dashboards
│   ├── support/        # Ticket system
│   └── moderation/     # Content flags, approvals
├── pages/              # Route-level pages
├── hooks/              # Custom React hooks
├── services/           # API client services
├── types/              # TypeScript interfaces
└── utils/              # Helper functions
```

---

## 👥 TEAM ASSIGNMENTS

### 🔧 PRIYA (CTO) — Architecture Lead
**Owns:** Technical foundation, security, API contracts

| Task | Deliverable | Priority |
|------|-------------|----------|
| Define API contracts | `src/admin/services/api-contracts.ts` | P0 |
| Auth + RBAC setup | `src/admin/hooks/useAdminAuth.ts` | P0 |
| Audit logging utility | `src/admin/utils/auditLogger.ts` | P0 |
| Type definitions | `src/admin/types/admin.types.ts` | P0 |
| WebSocket config | `src/admin/services/websocket.ts` | P1 |

**Review:** All PRs must pass Priya before merge.

---

### ⚙️ ARJUN (COO) — Pipeline Orchestrator
**Owns:** Task routing, progress tracking, blockers

| Task | Deliverable | Priority |
|------|-------------|----------|
| Create task tracking board | This file + `SHARED_CONTEXT.md` | P0 |
| Daily standup summaries | `wiki/admin-standups/` | P1 |
| Coordinate cross-team deps | Slack/SHARED_CONTEXT.md | Ongoing |
| QA handoff coordination | Route to Kavya | Ongoing |

---

### 💻 ANANYA (Frontend Dev) — UI Components
**Owns:** React components, layouts, forms

| Task | Deliverable | Priority |
|------|-------------|----------|
| Admin layout shell | `src/admin/components/AdminLayout.tsx` | P0 |
| CEO Pulse dashboard | `src/admin/components/dashboard/PulseDashboard.tsx` | P0 |
| KPI widget cards | `src/admin/components/dashboard/KpiCard.tsx` | P0 |
| Brand profile view | `src/admin/components/users/BrandProfile.tsx` | P1 |
| Creator profile view | `src/admin/components/users/CreatorProfile.tsx` | P1 |
| Campaign table | `src/admin/components/campaigns/CampaignTable.tsx` | P1 |
| Support ticket list | `src/admin/components/support/TicketList.tsx` | P2 |
| Content flag queue | `src/admin/components/moderation/FlagQueue.tsx` | P2 |

**Tech:** React + TypeScript + Tailwind + shadcn/ui  
**Review:** → Kavya (QA) → Meera (build verify)

---

### 🔌 VIKRAM (Backend Dev) — API Endpoints
**Owns:** Spring Boot controllers, services, repositories

| Task | Deliverable | Priority |
|------|-------------|----------|
| Admin auth endpoints | `AdminAuthController.java` | P0 |
| Dashboard stats API | `AdminDashboardController.java` | P0 |
| Brand CRUD API | `AdminBrandController.java` | P1 |
| Creator CRUD API | `AdminCreatorController.java` | P1 |
| Approval workflow API | `ApprovalWorkflowController.java` | P1 |
| Support ticket API | `AdminSupportController.java` | P2 |
| Audit log API | `AuditLogController.java` | P2 |

**Tech:** Spring Boot 3 + MySQL + Flyway migrations  
**Review:** → Kavya (QA) → Meera (build verify)

---

### ✅ KAVYA (QA Lead) — Quality Gate
**Owns:** Code review, testing, standards compliance

| Task | Deliverable | Priority |
|------|-------------|----------|
| Review all admin PRs | PR comments | Ongoing |
| Write test specs | `src/admin/__tests__/` | P1 |
| Security review | Checklist per ADMIN-PANEL-SPEC.md | P1 |
| RBAC test cases | Role permission matrix tests | P1 |

**Rule:** No code merges without Kavya's approval.

---

### 🚀 MEERA (DevOps) — Build & Deploy
**Owns:** Local verification, CI/CD, database migrations

| Task | Deliverable | Priority |
|------|-------------|----------|
| Admin migrations | `V14__admin_tables.sql` | P0 |
| Build verification | `npm run build` + `npm run test` | Ongoing |
| Redis cache setup | Docker config | P1 |
| Staging deploy | Admin panel on staging | P2 |

**Verify:** Every feature branch must pass `npm run build` before QA.

---

### 💰 ROHAN (CFO) — Finance Module Advisor
**Owns:** Requirements validation for finance features

| Task | Deliverable | Priority |
|------|-------------|----------|
| Validate revenue dashboard | Review Ananya's implementation | P1 |
| TDS report requirements | Spec for Vikram | P1 |
| Reconciliation logic | Review API contracts | P1 |

---

### 📣 TEJAS (CMO) — Marketing Module Advisor
**Owns:** Requirements validation for marketing features

| Task | Deliverable | Priority |
|------|-------------|----------|
| Validate acquisition dashboard | Review Ananya's implementation | P2 |
| Referral tracking requirements | Spec for Vikram | P2 |
| Platform reputation score | Formula definition | P2 |

---

## 📋 PHASE 1 SPRINT (Weeks 1-2)

### Must Complete:
- [ ] **Priya:** Type definitions + API contracts
- [ ] **Meera:** Database migrations `V14__admin_tables.sql`
- [ ] **Vikram:** Auth endpoints + Dashboard stats API
- [ ] **Ananya:** Admin layout + Pulse dashboard + KPI cards
- [ ] **Kavya:** Review all Phase 1 PRs

### Blocked Until Phase 1:
- Brand/Creator profile management
- Approval workflows
- Finance dashboards

---

## 🔄 DAILY WORKFLOW

```
Morning:
  Arjun reads this file → Updates SHARED_CONTEXT.md with today's focus
  
During Day:
  Devs work on assigned tasks
  PRs go to Kavya for review
  Approved PRs go to Meera for build verify
  
Evening:
  Arjun updates task status
  Blockers escalated to Priya or Swapnil
```

---

## 📡 HANDOFF FORMAT

When completing a task, update `SHARED_CONTEXT.md`:

```
FROM: [Your Name]
TO: [Next Person]
TASK: [What you did]
FILES: [Paths to files created/modified]
STATUS: DONE | BLOCKED | IN_PROGRESS
NEXT: [What the next person should do]
```

---

## ⚠️ ESCALATION RULES

| Issue | Escalate To |
|-------|-------------|
| Technical architecture question | Priya |
| Task priority conflict | Arjun |
| Security concern | Kavya → Priya |
| Budget/scope concern | Rohan → Swapnil |
| Blocked >4 hours | Arjun |

---

**CEO Directive:** Build Phase 1 in 2 weeks. Daily progress in `SHARED_CONTEXT.md`.

— Swapnil Maruti
