# Admin Panel Test Suite

**Owner:** Kavya (QA Lead)  
**Created:** 2026-07-09  
**Reference:** `src/admin/TASK_ASSIGNMENTS.md` § Kavya P1 tasks

---

## Test Coverage Plan

### Phase 1 (P0 — Mandatory for Initial Ship)

#### 1. RBAC Permission Matrix Tests
**Location:** `rbac-permissions.test.ts`  
**Purpose:** Verify role-based access control enforcement across all admin endpoints

**Test Cases:**
- SUPER_ADMIN can access ALL endpoints and actions
- ADMIN can access operations (brand/creator CRUD, campaign monitoring, support) but NOT system-level (audit logs, error logs, email queue management)
- SUPPORT can ONLY access support tickets and view-only brand/creator profiles
- Unauthenticated requests return 401
- Authenticated but unauthorized requests return 403
- MFA-required routes reject non-MFA users

**Coverage:** 18 test cases (3 roles × 6 endpoint groups)

---

#### 2. Component Tests
**Location:** `components/`  
**Purpose:** Unit and integration tests for React components

**Priority Components (P0):**
- `PulseDashboard.test.tsx` — CEO dashboard renders all KPI cards, handles loading/error states
- `KpiCard.test.tsx` — Renders value/change correctly, applies correct changeType styling
- `AdminLayout.test.tsx` — Role-based menu rendering, logout flow

**Priority Components (P1):**
- `BrandProfile.test.tsx` — KYC action buttons only visible to ADMIN+
- `CreatorProfile.test.tsx` — Application review actions only visible to ADMIN+
- `CampaignTable.test.tsx` — Pagination, filtering, at-risk highlighting

**Coverage:** 15 component test files minimum

---

#### 3. Controller Tests (Backend)
**Location:** `influora-api/src/test/java/.../admin/`  
**Owner:** Vikram (implementation) + Kavya (review)  
**Purpose:** Verify Spring Boot controller logic and authorization

**Priority Controllers (P0):**
- `AdminAuthControllerTest` — Login, MFA setup/verify, token refresh
- `AdminDashboardControllerTest` — Pulse data, financial summary, operations summary

**Priority Controllers (P1):**
- `AdminBrandControllerTest` — CRUD, KYC actions, suspension
- `AdminCreatorControllerTest` — CRUD, application review, tier adjustment
- `ApprovalWorkflowControllerTest` — Pending approvals, process approval

**Coverage:** 8 controller test classes minimum (per TASK_ASSIGNMENTS.md backend task list)

---

### Phase 2 (P1 — Pre-Launch)

#### 4. Service Layer Tests
- `AdminAnalyticsServiceTest` — GMV calculation, cohort analysis
- `AuditLogServiceTest` — Log creation with mandatory reason field
- `ApprovalWorkflowServiceTest` — State machine transitions

#### 5. Integration Tests
- `AdminAuthIntegrationTest` — Full login → dashboard → action → audit log flow
- `BrandKycWorkflowTest` — End-to-end KYC approval with audit trail

#### 6. Security Tests
- XSS sanitization on all user-input fields (admin notes, ticket replies)
- SQL injection attempts on filter endpoints
- CSRF token validation on all mutations
- IP whitelisting enforcement (coordinate with Kabir)

---

### Phase 3 (P2 — Post-Launch Hardening)

#### 7. Performance Tests
- Dashboard loads <2s under load (100 concurrent admins)
- Pagination on large datasets (10K+ brands/creators)
- WebSocket real-time error updates under burst traffic

#### 8. Accessibility Tests
- Keyboard navigation through all admin flows
- Screen reader compatibility on critical dashboards
- WCAG AA compliance on all admin UI

---

## Test Execution Commands

```bash
# Frontend unit tests
npm run test -- src/admin/__tests__/

# Backend unit tests (admin package only)
mvn test -Dtest="com.influora.admin.*Test"

# Full suite
npm run test && mvn test
```

---

## QA Gates

**Before Kavya QA Approval:**
1. All P0 tests written and passing
2. Test coverage >80% on admin components
3. No TypeScript errors in test files
4. All critical flows have happy path + error path tests

**Before Meera Build Verification:**
1. `npm run build` passes
2. `npm run test` shows 0 failures
3. `mvn test` shows 0 failures in admin package

**Before Kabir Security Review:**
1. All auth/RBAC tests passing
2. Audit log coverage on every mutation
3. IP whitelisting tests documented (pending Kabir's ADR)

---

## Critical Test Scenarios (Must Cover)

### Money-Touching Operations
- [ ] Escrow release requires ADMIN+ role
- [ ] Payout retry logs to audit_logs with reason
- [ ] Budget override records old/new values

### User Management
- [ ] Brand suspension prevents login immediately
- [ ] Creator tier adjustment triggers quality score recalc
- [ ] KYC rejection sends email notification (when email system lands)

### Audit Trail
- [ ] Every admin mutation logs: WHO, WHAT, WHY, WHEN
- [ ] Audit logs are immutable (no UPDATE/DELETE endpoints)
- [ ] Reason field is MANDATORY on all destructive actions

### Role Hierarchy
- [ ] SUPER_ADMIN can create other admin users
- [ ] ADMIN cannot create SUPER_ADMIN
- [ ] SUPPORT cannot modify any user data

---

## Pending Dependencies

- **Email system tests:** Blocked until `EmailQueueController` ships (P1)
- **WebSocket tests:** Blocked until `websocket.ts` lands (P1)
- **Redis cache tests:** Blocked until Meera's cache setup (P1)

---

**Next Steps (Cycle 1):**
1. Scaffold `role-permission-matrix.md` with expected role→permission mappings
2. Create stub test files for P0 controllers/components
3. Flag any spec gaps found during type review to Priya

— Kavya
