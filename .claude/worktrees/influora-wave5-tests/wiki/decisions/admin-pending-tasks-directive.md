# CEO Directive: Admin Backlog Prioritization

**Date:** 2026-07-09  
**From:** Swapnil Maruti (CEO)  
**To:** Priya (CTO), Arjun (Eng Lead), Kavya (QA Lead), Vikram (Backend)  
**Re:** Priya's pending-tasks audit — admin-only scope

---

## Executive Summary

After reviewing Priya's audit (`wiki/tech/PENDING_TASKS_REPORT.md`), I am issuing clear routing for all admin-surface items. Brand/Creator sections are out of scope this round.

---

## IMMEDIATE ACTION (Unblocked — Start Now)

### 1. Dockerfiles for influora-api and Vite frontend (P0)
**Owner:** Vikram  
**Status:** UNBLOCKED — pure engineering, no credentials needed  
**Instruction:** Write production-ready Dockerfiles for both services. Follow the staging checklist (`wiki/admin-progress/STAGING-DEPLOY-CHECKLIST.md`). This work runs in parallel with me sourcing cloud credentials. Do NOT wait for credentials to start.

### 2. Kavya's formal test-spec doc + security checklist (P1)
**Owner:** Kavya  
**Status:** UNBLOCKED — documentation artifact  
**Instruction:** Produce the formal test specification and security checklist that the audit flagged as missing. Ad-hoc review has been happening — now formalize it. This is a writing task, not a code task.

### 3. Dedicated admin backend test suite (P1)
**Owner:** Vikram (with Kavya oversight)  
**Status:** UNBLOCKED — no dependency  
**Instruction:** The 795 passing tests cover admin paths incidentally. Build a deliberate, organized `admin/` test package that explicitly exercises every admin controller and service. Kavya reviews the coverage map.

### 4. Arjun's daily standup docs (P2)
**Owner:** Arjun  
**Status:** UNBLOCKED — low priority  
**Decision:** DEFER. This is process documentation with minimal value. Do not spend engineering time on it this sprint. If needed later, revisit.

---

## BLOCKED ON CEO (Pending My Decision)

### 5. Platform fee configuration + PlatformFeeAdminController (P0)
**Blocked on:** My approval of Rohan's proposed fee percentages  
**Rohan's proposal:** 10% brand fee / 15% creator fee, Option A (Sage absorbs Razorpay costs)  

**MY RULING: APPROVED.**

Fee structure effective immediately for build:
- Brand fee: **10%** of contract value
- Creator fee: **15%** of payout  
- Platform absorbs payment processor fees (Razorpay)

**Instruction to Vikram:** Unblocked. Build `PlatformFeeConfig` entity, admin UI, and `PlatformFeeAdminController`. Rohan validates the math before QA.

### 6. Staging deploy (P0) — credentials portion
**Blocked on:** Cloud credentials + reachable Docker daemon  
**Status:** Still blocked on me. I will source credentials this week. Once Vikram delivers Dockerfiles (#1 above), staging deploy is ready for my input.

### 7. Admin dispute-resolution console (P0)
**Blocked on:** Dispute/refund policy  
**Status:** DEFERRED to Phase 2.  

Rationale: This requires business policy decisions (who arbitrates, refund percentages, timeframes, escalation paths) that need more thought than a quick ruling. We ship Phase 1 without dispute UI. Disputes handled manually via direct DB + Slack until the policy is formalized.

---

## CONFIRMED PHASE 2 DEFERRAL

### 8. Rohan/Tejas advisory items
- TDS specification
- Revenue dashboard validation
- Reconciliation workflows
- Referral tracking
- Reputation-score formula

**Status:** REAFFIRMED PHASE 2. These are CFO/CMO business inputs, not engineering work. No change from prior session.

---

## Summary Matrix

| Item | Priority | Status | Owner | Action |
|------|----------|--------|-------|--------|
| Dockerfiles | P0 | UNBLOCKED | Vikram | Start immediately |
| Fee config + controller | P0 | UNBLOCKED (just approved) | Vikram | Build after Dockerfiles |
| Staging deploy (creds) | P0 | BLOCKED on CEO | Swapnil | I source credentials |
| Dispute console | P0 | DEFERRED | - | Phase 2 |
| Test-spec doc | P1 | UNBLOCKED | Kavya | Start immediately |
| Admin test suite | P1 | UNBLOCKED | Vikram/Kavya | Start after Dockerfiles |
| Standup docs | P2 | DEFERRED | - | Low value, skip |
| Advisory items | P2 | PHASE 2 | Rohan/Tejas | No action |

---

## Routing

Arjun: Break down #1, #2, #3, and #5 into subtasks and route to Vikram/Kavya. Track in SHARED_CONTEXT.md.

Priya: Confirm this directive aligns with technical priorities. Flag any conflicts.

Vikram: Priority order is (1) Dockerfiles, (2) Fee controller, (3) Admin test suite. Do not context-switch between them.

Kavya: Start the test-spec doc in parallel with Vikram's Dockerfile work.

---

**This directive is binding. Execute.**

— Swapnil Maruti, CEO
