# Admin Portal — Consolidated Test Specification

**Owner:** Kavya (QA Lead)  
**Created:** 2026-07-09  
**Authority:** Formal consolidation per wiki/decisions/admin-pending-tasks-directive.md

---

## Purpose

This document consolidates all test coverage, findings, and quality gates for the admin portal across the 8-cycle build loop (Cycles 1-8). It supersedes ad-hoc cycle QA notes and serves as the authoritative reference for what's tested, what's NOT tested, and what "done" means for admin test coverage.

---

## Current Test Status

### Frontend Tests (Vitest + React Testing Library)

**Total Tests:** 139 passing  
**Test Framework:** Vitest 4.1.10 + @testing-library/react  
**Test Duration:** ~22s  
**Location:** `src/admin/__tests__/`

**Test Files:**
1. `rbac-permission-matrix.test.ts` — 54 CONTRACT tests (awaiting real implementation)
2. `AdminLayout.test.tsx` — Layout rendering, role-based nav, logout flow
3. `BrandProfile.test.tsx` — KYC action buttons visible to ADMIN+ only
4. `FlagQueue.test.tsx` — Content moderation queue rendering

**Coverage by Category:**

| Category | Tests | Status |
|----------|-------|--------|
| RBAC Permission Matrix | 54 | CONTRACT ONLY (no real assertions yet) |
| Component Rendering | 45 | PASSING |
| Role-Based UI Visibility | 22 | PASSING |
| Data Fetching/Error States | 18 | PASSING |
| **TOTAL** | **139** | **ALL PASSING** |

**Coverage Gaps (Known):**
- RBAC tests are structural placeholders, not runtime permission checks
- No KpiCard aria-live tests yet (Cycle 1 HIGH finding #5 — deferred)
- No PulseDashboard role-guard tests (Cycle 1 MEDIUM finding #7 — deferred)
- No error boundary tests (Cycle 1 MEDIUM finding #8 — awaiting component)
- No WebSocket real-time update tests (pending P1 websocket.ts feature)
- No Redis cache integration tests (pending Meera's P1 cache setup)

---

### Backend Tests (JUnit + Spring Boot Test)

**Status:** NO DEDICATED ADMIN BACKEND TEST SUITE EXISTS  
**Location:** `influora-api/src/test/java/com/influora/`

**Reality Check:**  
Despite 8 cycles of admin backend development (`AdminAuthController`, `AdminBrandController`, `AdminCreatorController`, `AdminDashboardController`, `ApprovalWorkflowController`, `AdminSupportController`, `AuditLogController`), there is NO corresponding `src/test/java/com/influora/admin/` directory. Backend testing is done manually via:
1. Meera's `npm run build` + `curl` verification after every QA pass
2. Priya's direct code review during arch/type changes
3. Kabir's adversarial red-team review (5 cycles of SECURITY-NOTES.md)

**Why This Happened:**  
Admin portal was built in a 3-hour cycle sprint cadence prioritizing working features over test-first TDD. Every feature was manually verified by Meera before merging. This is a REAL gap, not a documentation omission.

**What "Fix This Later" Means:**  
A proper backend test suite (controller tests, service layer tests, RBAC enforcement tests, audit log write tests) is a Phase 2 task, NOT a Phase 1 blocker. The admin portal ships with 139 frontend tests and zero backend unit tests. This is accepted technical debt, documented here so no one pretends otherwise.

---

## Test Coverage by Feature

### ✅ TESTED (Frontend)

**Authentication & Authorization**
- [x] Login flow (valid credentials → token stored)
- [x] MFA setup/verify flow
- [x] Token validation before API calls (Cycle 3 CRITICAL fix)
- [x] Logout clears localStorage and redirects
- [x] Role-based menu rendering (SUPER_ADMIN/ADMIN/SUPPORT see correct nav items)
- [x] `hasPermission()` hook returns correct results for each role

**Dashboard**
- [x] PulseDashboard renders all KPI cards
- [x] KpiCard renders value/delta/changeType correctly
- [x] Loading skeletons display during data fetch
- [x] Error states display when API fails
- [x] useReducedMotion() bypasses animations

**Brand Management**
- [x] BrandProfile KYC action buttons only visible to ADMIN+
- [x] SUPPORT users see read-only profile
- [x] Suspension status badge renders correctly

**Creator Management**
- [x] CreatorProfile application review actions only visible to ADMIN+
- [x] Tier adjustment dropdown hidden from SUPPORT

**Content Moderation**
- [x] FlagQueue renders pending flags
- [x] Action buttons (APPROVE/REJECT/REMOVE) only visible to ADMIN+

**Accessibility**
- [x] Mobile nav backdrop is keyboard-accessible (Cycle 3 CRITICAL fix)
- [x] All buttons have aria-labels
- [x] Semantic HTML used (nav, main, header)
- [x] Color contrast meets WCAG AA

---

### ❌ NOT TESTED (Known Gaps)

**Backend (Entire Surface)**
- [ ] No controller tests (`AdminAuthControllerTest`, `AdminBrandControllerTest`, etc.)
- [ ] No service layer tests (`AdminBrandServiceTest`, `AdminAuditLogServiceTest`, etc.)
- [ ] No RBAC enforcement tests (backend `@PreAuthorize` / `requireRole` calls)
- [ ] No audit log write tests (field allow-lists, IP capture, reason validation)
- [ ] No integration tests (end-to-end login → dashboard → action → audit log)
- [ ] No security tests (XSS sanitization, SQL injection, CSRF token validation)

**Frontend (Deferred Features)**
- [ ] KPI cards missing aria-live for screen reader announcements (Cycle 1 HIGH #5)
- [ ] PulseDashboard role-aware empty state for SUPPORT (Cycle 1 MEDIUM #7)
- [ ] AdminLayout error boundary (Cycle 1 MEDIUM #8)
- [ ] WebSocket real-time error updates (pending P1 feature)
- [ ] Email queue UI tests (pending P1 feature)
- [ ] Approval workflow UI tests (ApprovalWorkflow component not built yet)

**Performance**
- [ ] Dashboard load time under concurrent admin load (100 admins)
- [ ] Pagination on large datasets (10K+ brands/creators)
- [ ] WebSocket burst traffic handling

**End-to-End**
- [ ] No Playwright/Cypress tests
- [ ] No full user journey tests (login → verify MFA → suspend brand → check audit log)

---

## What "Done" Means for Admin Test Coverage (Phase 2 Definition)

### Minimum Bar (Phase 1 — Shipped State)
- ✅ 139 frontend tests passing
- ✅ Manual verification by Meera after every QA cycle
- ✅ Kavya QA review of every PR (8 cycles, 2 CRITICAL + 6 HIGH findings fixed)
- ✅ Kabir red-team review (5 cycles, 6 real bugs found, all fixed)

### Target Bar (Phase 2 — Post-Launch Hardening)
- [ ] 80%+ frontend test coverage on admin components
- [ ] All CRITICAL user flows have happy path + error path tests
- [ ] Backend controller test suite exists (`src/test/java/com/influora/admin/`)
- [ ] RBAC tests verify every role × permission combination at RUNTIME (not contracts)
- [ ] Audit log write tests verify field allow-lists, IP capture, reason validation
- [ ] Integration tests cover money-moving flows (escrow release, budget override, payout retry)
- [ ] Security tests for XSS, SQL injection, CSRF on all admin mutations
- [ ] Performance tests confirm <2s dashboard load under 100 concurrent admins

---

## QA Gates (Per Cycle)

### Before Kavya QA Approval
1. All P0 tests written and passing (if new test-bearing feature)
2. No TypeScript errors in test files or components
3. All critical flows have happy path + error path coverage
4. Run full checklist from wiki/admin-progress/QA-REVIEW-CYCLE1.md

### Before Meera Build Verification
1. `npm run build` passes (no TS errors, no bundle failures)
2. `npm run test` shows 0 failures
3. `curl` checks confirm API endpoints respond correctly

### Before Kabir Security Review
1. All auth/RBAC tests passing (or documented as deferred)
2. Audit log coverage on every mutation
3. No plaintext secrets in database (mfa_secret encrypted, password_hash bcrypt'd)

---

## Critical Test Scenarios (Must Cover in Phase 2)

### Money-Touching Operations
- [ ] Escrow release requires ADMIN+ role AND logs to audit_logs with reason
- [ ] Payout retry logs to audit_logs with admin_id, action, reason, IP
- [ ] Budget override records old/new values in audit snapshot

### User Management
- [ ] Brand suspension prevents login immediately (test at DB + auth layer)
- [ ] Creator tier adjustment triggers quality score recalc
- [ ] KYC rejection sends email notification (when email system lands)

### Audit Trail
- [ ] Every admin mutation logs: WHO (admin_id), WHAT (action), WHY (reason), WHEN (created_at)
- [ ] Audit logs are immutable (no UPDATE/DELETE endpoints exist)
- [ ] Reason field is MANDATORY on all destructive actions (test DTO validation)

### Role Hierarchy
- [ ] SUPER_ADMIN can create other admin users
- [ ] ADMIN cannot create SUPER_ADMIN
- [ ] SUPPORT cannot modify any user data (KYC, suspend, tier adjust all 403)

---

## Known Issues Carried Forward (Accepted Tech Debt)

### From Cycle 1 QA Review
1. **KPI cards missing aria-live** (HIGH) — screen reader users don't hear value updates  
   **Status:** Deferred to Phase 2 (low priority, affects minority of users)

2. **usePulseData mock error handling** (MEDIUM) — infinite spinner if mock throws  
   **Status:** Deferred (mock-only bug, not production-affecting)

3. **PulseDashboard not role-aware** (MEDIUM) — SUPPORT sees "CEO Pulse" heading but gets 403  
   **Status:** Deferred (misleading UX but server blocks correctly, no security impact)

4. **AdminLayout missing error boundary** (MEDIUM) — route errors crash entire shell  
   **Status:** Deferred (add when error boundary component exists, not urgent)

### From Backend Manual Testing
5. **No backend unit test suite** — entire admin backend tested manually only  
   **Status:** Accepted Phase 1 gap, Phase 2 task to add proper test suite

6. **RBAC is service-layer convention, not framework-enforced** — every controller must remember to call `requireRoleWithMfaSatisfied`  
   **Status:** Priya flagged as real Phase 2 architectural item, but all 7 shipped controllers verified correct (Kabir Cycle 6 sweep)

7. **Audit log retry queue not covered by tests** — localStorage-backed durability added in Cycle 4, never tested  
   **Status:** Manually verified by Meera (kill server mid-action, reload, check queue processed), but no automated test

---

## Test Execution Commands

```bash
# Frontend unit tests
cd "C:\Users\Sage world\Downloads\New Influora Ai\New Influora"
npm test

# Frontend tests (specific file)
npm test src/admin/__tests__/rbac-permission-matrix.test.ts

# Backend tests (when they exist)
cd influora-api
mvn test -Dtest="com.influora.admin.*Test"

# Full suite (when backend tests exist)
npm run test && cd influora-api && mvn test
```

---

## References

- **Original test plan:** `src/admin/__tests__/README.md`
- **RBAC permission matrix:** `src/admin/__tests__/role-permission-matrix.md`
- **QA review cycles:** `wiki/admin-progress/QA-REVIEW-CYCLE1.md`, `wiki/admin-progress/QA-CYCLE4-FINDINGS.md`
- **Test count reconciliation:** `wiki/admin-progress/test-count-reconciliation.md`
- **Security findings:** `wiki/admin-progress/SECURITY-NOTES.md` (Cycles 1-6)

---

## Single Most Important Open Item

**Backend test suite does not exist.** All 7 admin controllers, 3 service layers, RBAC enforcement, and audit log writes are manually tested only. This is the largest quality gap in the admin portal and must be addressed in Phase 2 before any money-moving features (escrow release, budget override) go to production at scale.

---

**Document Status:** FINAL — consolidates all ad-hoc QA across 8 cycles  
**Next Review:** Phase 2 kick-off, when backend test suite is added  
**Owner:** Kavya (QA Lead)  
**Last Updated:** 2026-07-09
