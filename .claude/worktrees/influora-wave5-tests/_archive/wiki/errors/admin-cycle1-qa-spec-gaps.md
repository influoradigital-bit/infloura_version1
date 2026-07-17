# Admin Panel Cycle 1 — QA Spec Gap Review

**Reviewer:** Kavya (QA Lead)  
**Date:** 2026-07-09  
**Task:** Cycle 1 foundation review per admin-portal 3-hour build loop  
**Files Reviewed:** `src/admin/types/admin.types.ts`, `src/admin/services/api-contracts.ts`

---

## Summary

Reviewed foundation files for QA-relevant gaps before real components/controllers land. **Found 3 CRITICAL gaps** that will block testing if not addressed before Vikram starts building controllers.

All other types are well-structured and match the spec. No blocking issues in what's already defined — the gaps are what's MISSING.

---

## CRITICAL Gaps (Must Fix Before Controllers)

### 🔴 GAP #1: No Role→Permission Type Map

**What's Missing:**
- `admin.types.ts` defines `AdminRole` enum (SUPER_ADMIN, ADMIN, SUPPORT)
- But NO type defining what each role can actually DO
- No `AdminPermissions` interface, no `RoleCapabilities` type, nothing

**Why It Matters:**
- Vikram's `AdminAuthController` will have no type-safe reference for `@PreAuthorize` annotations
- Tests cannot programmatically verify "SUPPORT cannot suspend brands" without hardcoding expectations
- Frontend `useAdminAuth.canPerform()` hook has no contract to implement against

**Example of What's Needed:**
```typescript
type AdminAction = 
  | 'brand.update' 
  | 'brand.suspend' 
  | 'kyc.approve' 
  | 'escrow.release' 
  | 'ticket.reply'
  | ... // (enumerate all actions from role-permission-matrix.md)

type RoleCapabilities = {
  [K in AdminRole]: AdminAction[];
};

// OR simpler:
interface AdminPermissions {
  canUpdateBrand: boolean;
  canSuspendBrand: boolean;
  canApproveKyc: boolean;
  canReleaseEscrow: boolean;
  // ... derived from role
}
```

**Owner:** Priya (CTO owns type definitions)  
**Blocks:** Vikram cannot start controllers without this, tests cannot verify RBAC

---

### 🔴 GAP #2: No MFA Enforcement Model

**What's Missing:**
- `AdminUser.mfaEnabled: boolean` exists
- But no definition of HOW MFA is enforced beyond login:
  - Which actions require MFA re-verification? (e.g., escrow release, bulk email)
  - Is MFA status checked via JWT claim or DB lookup per request?
  - What happens if admin enables MFA mid-session?

**Why It Matters:**
- Spec says "Spring Security with JWT + MFA (TOTP)" but no implementation contract
- Cannot write test cases like "escrow release returns 403 MFA_REQUIRED if user has MFA enabled but didn't verify this session"
- Security gap — unclear if money-touching actions need step-up auth

**What's Needed:**
- `AdminLoginResponse` should include `mfaRequired: boolean` or `mfaStatus: 'VERIFIED' | 'REQUIRED' | 'DISABLED'`
- JWT claims should include `mfaVerifiedAt: string | null` timestamp
- New error code `MFA_REQUIRED` in ApiResponse

**Owner:** Priya (security architecture)  
**Blocks:** Cannot test MFA enforcement on sensitive actions

---

### 🔴 GAP #3: Reason Field Not Type-Enforced

**What's Defined:**
- Spec says "All mutations logged to `audit_logs` with **mandatory reason field**"
- But DTOs make `reason` OPTIONAL:
  - `BrandKycAction.reason?: string` (line 179)
  - `CreatorApplicationAction.reason?: string` (line 242)
  - No reason field at all on escrow operations (apis not defined yet)

**Why It Matters:**
- Backend can accept "Brand suspended" action with NO reason
- Audit trail is legally required for compliance — missing reason = bad audit
- Tests cannot enforce "all destructive actions must include reason"

**What's Needed:**
- Change to `reason: string` (REQUIRED) on:
  - `BrandKycAction` (REJECT case)
  - `CreatorApplicationAction` (REJECT case)
  - All escrow operations (release, hold, refund)
  - Brand/Creator suspend/reinstate
  - ModerationAction (already has it, good)

**Owner:** Priya (type definitions)  
**Blocks:** Cannot test audit trail completeness

---

## MEDIUM Gaps (Fix Before Launch, Not Blocking Cycle 1)

### 🟡 GAP #4: No IP Whitelist Model

**What's Missing:**
- Spec says "IP whitelisting for admin endpoints"
- No `admin_allowed_ips` table definition
- No `IpWhitelistConfig` type

**Impact:** Cannot test IP restrictions  
**Owner:** Priya + Meera (schema + config)  
**Timeline:** Before production deploy, not blocking local dev

---

### 🟡 GAP #5: No Email Template Variable Schema

**What's Defined:**
- `EmailQueueItem.templateId: string` exists
- Spec mentions `email_templates.variables_schema` column

**What's Missing:**
- No `EmailTemplate` interface in types
- No type for what variables each template expects

**Impact:** Cannot test bulk email parameter validation  
**Owner:** Vikram (add when email system lands, P1)  
**Timeline:** When email queue API ships

---

## LOW Gaps (Non-Blocking)

### 🟢 GAP #6: No WebSocket Event Types

**What's Missing:**
- Spec mentions "WebSocket for real-time updates" on error logs
- No `ErrorLogEvent` or `TicketUpdateEvent` types

**Impact:** Cannot test real-time subscriptions  
**Owner:** Priya  
**Timeline:** When WebSocket lands (P1)

---

### 🟢 GAP #7: No Pagination Defaults

**What's Defined:**
- `PaginatedResponse<T>` generic wrapper exists

**What's Missing:**
- No type/constant defining default `pageSize`
- API contracts hardcode `pageSize = 20` in several places but no single source of truth

**Impact:** Minor — tests will just use hardcoded 20  
**Owner:** Priya (add `DEFAULT_PAGE_SIZE` constant)  
**Timeline:** Nice-to-have cleanup

---

## What's GOOD (No Gaps)

✅ All entity types are comprehensive (Brand, Creator, Campaign, Ticket, etc.)  
✅ All status enums are complete (KycStatus, TicketStatus, WorkflowType, etc.)  
✅ API contracts match spec exactly (all endpoints from ADMIN-PANEL-SPEC.md covered)  
✅ Error handling pattern is consistent (`ApiResponse<T>` wrapper)  
✅ Filter types are well-structured (BrandFilters, CreatorFilters, etc.)  
✅ No TypeScript `any` types found (strict typing maintained)

---

## Routing

**TO: Priya (CTO)**  
**TASK:** Address CRITICAL gaps #1, #2, #3 before Vikram starts building controllers  
**FILES:** `src/admin/types/admin.types.ts` (add RoleCapabilities, MFA model, make reason required)  
**PRIORITY:** P0 — blocks controller implementation

**TO: Arjun (COO)**  
**TASK:** Update task board — Priya has 3 type updates before Vikram can start AdminAuthController  
**IMPACT:** Slight delay on backend, but frontend (Ananya) can proceed with layout/dashboard UI using existing types

---

## Next Steps (After Priya Updates)

1. Vikram builds `AdminAuthController` using the RoleCapabilities map
2. Ananya builds `useAdminAuth` hook implementing `canPerform()` logic
3. Kavya writes RBAC tests against the permission matrix
4. Meera verifies build after first controllers land

---

**Test Scaffolding Complete:**
- `src/admin/__tests__/README.md` — full test plan (RBAC, component, controller coverage)
- `src/admin/__tests__/role-permission-matrix.md` — expected role→permission mappings

**Status:** CYCLE 1 QA SCAFFOLDING DONE — blocked on Priya type updates for real implementation to proceed

— Kavya Reddy, QA Lead
