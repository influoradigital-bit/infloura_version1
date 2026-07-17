# Admin Panel RBAC Permission Matrix

**Owner:** Kavya (QA Lead)  
**Created:** 2026-07-09  
**Source:** Derived from `src/admin/types/admin.types.ts` AdminRole enum + `docs/ADMIN-PANEL-SPEC.md` security architecture  
**Purpose:** Define expected role→permission mappings that `AdminAuthController` and `useAdminAuth.ts` must satisfy

---

## Role Hierarchy

```
SUPER_ADMIN (highest privilege)
    ↓
ADMIN (operational privilege)
    ↓
SUPPORT (read-only + ticket handling)
```

**Inheritance:** Higher roles inherit all permissions of lower roles

---

## Permission Matrix

| Endpoint / Action | SUPER_ADMIN | ADMIN | SUPPORT | Notes |
|-------------------|-------------|-------|---------|-------|
| **Authentication** |
| Login (POST `/admin/auth/login`) | ✅ | ✅ | ✅ | All roles can log in |
| Setup MFA | ✅ | ✅ | ✅ | All users can enable MFA |
| Verify MFA | ✅ | ✅ | ✅ | Required if `mfaEnabled=true` |
| Refresh Token | ✅ | ✅ | ✅ | All authenticated users |
| **Dashboard** |
| View CEO Pulse (`/dashboard/pulse`) | ✅ | ✅ | ❌ | Financial data restricted |
| View Financial Summary | ✅ | ✅ | ❌ | Revenue/GMV restricted |
| View Operations Summary | ✅ | ✅ | ✅ | Support can view queue depth |
| View Marketing Summary | ✅ | ✅ | ❌ | CAC/LTV data restricted |
| **Brand Management** |
| List Brands | ✅ | ✅ | ✅ | Support can view for tickets |
| View Brand Detail | ✅ | ✅ | ✅ | Support view-only |
| Update Brand | ✅ | ✅ | ❌ | ADMIN+ only |
| Approve/Reject KYC | ✅ | ✅ | ❌ | Compliance action |
| Suspend Brand | ✅ | ✅ | ❌ | Destructive action |
| Reinstate Brand | ✅ | ✅ | ❌ | Destructive action |
| Override Campaign Budget | ✅ | ✅ | ❌ | Money-touching |
| **Creator Management** |
| List Creators | ✅ | ✅ | ✅ | Support can view for tickets |
| View Creator Detail | ✅ | ✅ | ✅ | Support view-only |
| Update Creator | ✅ | ✅ | ❌ | ADMIN+ only |
| Approve/Reject Application | ✅ | ✅ | ❌ | Quality gate action |
| Adjust Creator Tier | ✅ | ✅ | ❌ | Platform economics impact |
| Force Instagram Reauth | ✅ | ✅ | ❌ | Integration admin |
| Suspend Creator | ✅ | ✅ | ❌ | Destructive action |
| Reinstate Creator | ✅ | ✅ | ❌ | Destructive action |
| **Campaign Monitoring** |
| List Campaigns | ✅ | ✅ | ✅ | Support can view for tickets |
| View Campaign Detail | ✅ | ✅ | ✅ | Support view-only |
| View At-Risk Campaigns | ✅ | ✅ | ❌ | Operational insight |
| View Hype Ops Dashboard | ✅ | ✅ | ❌ | Platform capacity data |
| **Finance** |
| View Revenue | ✅ | ✅ | ❌ | Financial data |
| View Escrow Summary | ✅ | ✅ | ❌ | Money float visibility |
| View Payout Queue | ✅ | ✅ | ❌ | Payment operations |
| Retry Failed Payout | ✅ | ✅ | ❌ | Money-touching action |
| View Reconciliation | ✅ | ✅ | ❌ | Razorpay matching |
| Resolve Reconciliation Mismatch | ✅ | ❌ | ❌ | SUPER_ADMIN only (write-off risk) |
| Download TDS Report (26Q) | ✅ | ✅ | ❌ | Compliance download |
| **Escrow Operations** |
| View Flagged Escrow | ✅ | ✅ | ❌ | Operational view |
| Release Escrow | ✅ | ✅ | ❌ | Money-touching (requires reason) |
| Hold Escrow | ✅ | ✅ | ❌ | Money-touching (requires reason) |
| Refund to Brand | ✅ | ✅ | ❌ | Money-touching (requires reason) |
| **Support** |
| List Tickets | ✅ | ✅ | ✅ | Primary SUPPORT job |
| View Ticket Detail | ✅ | ✅ | ✅ | All roles can view |
| Update Ticket (status/priority) | ✅ | ✅ | ✅ | Support can triage |
| Reply to Ticket | ✅ | ✅ | ✅ | Support can respond |
| Escalate Ticket | ✅ | ✅ | ✅ | Support can escalate to ADMIN |
| Assign Ticket | ✅ | ✅ | ❌ | ADMIN+ assigns to team |
| View Support Stats | ✅ | ✅ | ✅ | All support can view queue |
| **Moderation** |
| View Content Flags | ✅ | ✅ | ❌ | ADMIN+ only |
| Action Flag (APPROVE/REJECT/REMOVE) | ✅ | ✅ | ❌ | Moderation power |
| View Pending Approvals | ✅ | ✅ | ❌ | Workflow queue |
| Process Approval | ✅ | ✅ | ❌ | Approve/reject workflows |
| View Account Suspensions | ✅ | ✅ | ❌ | ADMIN+ only |
| Review Appeal (REINSTATE/UPHOLD) | ✅ | ✅ | ❌ | Appeal decisions |
| **Audit Logs** |
| List Audit Logs | ✅ | ❌ | ❌ | SUPER_ADMIN only (admin oversight) |
| View Audit by Entity | ✅ | ❌ | ❌ | SUPER_ADMIN only |
| **Error Logs** |
| View Recent Errors | ✅ | ✅ | ❌ | Operational monitoring |
| View Error Detail | ✅ | ✅ | ❌ | Debug access |
| Resolve Error | ✅ | ✅ | ❌ | Mark as fixed |
| View Error Stats | ✅ | ✅ | ❌ | System health |
| **Email Queue** |
| View Email Queue | ✅ | ✅ | ❌ | Operational view |
| Retry Failed Email | ✅ | ✅ | ❌ | Email ops |
| View Email Templates | ✅ | ✅ | ❌ | Template audit |
| Send Bulk Email | ✅ | ❌ | ❌ | SUPER_ADMIN only (blast risk) |
| View Email Stats | ✅ | ✅ | ❌ | Delivery monitoring |
| **Marketing Analytics** |
| View Acquisition Metrics | ✅ | ✅ | ❌ | CAC/attribution data |
| View Growth Metrics | ✅ | ✅ | ❌ | Funnel/retention |
| View Platform Reputation | ✅ | ✅ | ❌ | Quality score |
| View Referrals | ✅ | ✅ | ❌ | Attribution tracking |
| **Admin User Management** |
| Create Admin User | ✅ | ❌ | ❌ | SUPER_ADMIN only |
| Update Admin User | ✅ | ❌ | ❌ | SUPER_ADMIN only |
| Delete Admin User | ✅ | ❌ | ❌ | SUPER_ADMIN only |
| View All Admin Users | ✅ | ❌ | ❌ | SUPER_ADMIN only |

---

## Test Cases (Derived from Matrix)

### Test Suite: `AdminAuthController` Authorization

**Test 1: SUPER_ADMIN Full Access**
- Given: User authenticated with role `SUPER_ADMIN`
- When: Requests any endpoint in the matrix
- Then: All requests return 200/201 (never 403)

**Test 2: ADMIN Operational Access**
- Given: User authenticated with role `ADMIN`
- When: Requests brand/creator CRUD, campaign monitoring, finance (non-reconciliation), support, moderation, error logs, email queue (non-bulk)
- Then: All requests return 200/201
- When: Requests audit logs, reconciliation resolution, bulk email, admin user management
- Then: All requests return 403 Forbidden

**Test 3: SUPPORT Limited Access**
- Given: User authenticated with role `SUPPORT`
- When: Requests support tickets (list/view/update/reply/escalate), brand/creator view-only, campaign view-only, operations summary
- Then: All requests return 200
- When: Requests ANY mutation (KYC, tier adjust, suspend, payout retry, moderation actions) or financial/marketing data
- Then: All requests return 403 Forbidden

**Test 4: Unauthenticated Requests**
- Given: No Authorization header
- When: Requests any admin endpoint
- Then: Returns 401 Unauthorized

**Test 5: MFA Enforcement**
- Given: User authenticated with `mfaEnabled=true` but no MFA code provided
- When: Requests any non-auth endpoint
- Then: Returns 403 with `MFA_REQUIRED` error code

---

## Missing Definitions (QA Gaps Flagged)

### 🔴 CRITICAL GAPS

1. **No AdminRole permission mapping in types**
   - `admin.types.ts` defines `AdminRole` enum (SUPER_ADMIN, ADMIN, SUPPORT) but NO interface defining `role → allowed actions`
   - **Impact:** Vikram's `AdminAuthController` has no type-safe reference for what each role can do
   - **Fix Needed:** Add `AdminPermissions` interface or `RoleCapabilities` type map
   - **Routed to:** Priya (CTO owns type definitions)

2. **No MFA enforcement model**
   - `AdminUser.mfaEnabled: boolean` exists, but no definition of:
     - Which endpoints require MFA verification beyond login?
     - How is MFA status checked on subsequent requests (JWT claim? DB lookup per request?)
     - What happens if MFA is enabled mid-session?
   - **Impact:** Security gap — unclear if money-touching actions require re-verification
   - **Routed to:** Priya (security architecture decision)

3. **No IP whitelist enforcement model**
   - Spec says "IP whitelisting for admin endpoints" but no type for `admin_allowed_ips` table or config
   - **Impact:** Cannot test IP restrictions
   - **Routed to:** Priya + Meera (schema + config)

### 🟡 MEDIUM GAPS

4. **Reason field not enforced at type level**
   - Spec says "mandatory reason field" for destructive actions, but DTOs don't have `reason: string` on:
     - `BrandKycAction` (has optional `reason?`)
     - `CreatorApplicationAction` (has optional `reason?`)
     - Escrow operations (no types exist yet in api-contracts.ts)
   - **Impact:** Backend can accept actions without reason, breaking audit trail
   - **Fix Needed:** Make `reason` REQUIRED on all mutation DTOs
   - **Routed to:** Priya

5. **No email template variable schema**
   - `EmailQueueItem` references `templateId` but no type for `EmailTemplate.variables_schema`
   - **Impact:** Cannot test bulk email parameter validation
   - **Routed to:** Vikram (add when email system lands)

### 🟢 LOW GAPS (Non-Blocking)

6. **No WebSocket event types**
   - Real-time error updates mentioned in spec but `api-contracts.ts` has no WebSocket event types
   - **Fix Needed:** Add `ErrorLogEvent`, `TicketUpdateEvent` types when WebSocket lands (P1)

7. **No pagination defaults**
   - `PaginatedResponse<T>` exists but no type defining default `pageSize` (spec mentions "20" in several places)
   - **Fix Needed:** Add `DEFAULT_PAGE_SIZE` constant or config type

---

## Next Actions

**For Vikram (when building AdminAuthController):**
1. Read this matrix before writing any `@PreAuthorize` annotations
2. Every endpoint MUST have role check (never assume ADMIN+ on unsecured routes)
3. All destructive actions MUST log to `audit_logs` with mandatory `reason`

**For Priya (useAdminAuth.ts hook):**
1. Implement `canPerform(action: AdminAction): boolean` helper based on this matrix
2. Hook should expose `role`, `mfaEnabled`, `canPerform` to components
3. Components should HIDE (not just disable) actions user cannot perform

**For Ananya (component rendering):**
1. Use `canPerform()` from `useAdminAuth` to conditionally render action buttons
2. Never send requests for actions the current role cannot perform
3. Show role-appropriate error messages (not "403 Forbidden" to end users)

---

**Status:** SCAFFOLDED — awaiting Priya type updates + Vikram controller implementation

— Kavya
