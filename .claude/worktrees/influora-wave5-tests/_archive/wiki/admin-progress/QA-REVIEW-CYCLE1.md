# QA Review: Admin Portal Cycle 1

**Date:** 2026-07-09  
**Reviewer:** Kavya (QA Lead)  
**Status:** CHANGES REQUESTED  
**Scope:** Phase 1 P0 cycle 1 shipped code

---

## Files Reviewed

1. `src/admin/hooks/useAdminAuth.ts` — Priya
2. `src/admin/utils/auditLogger.ts` — Priya
3. `src/admin/components/AdminLayout.tsx` — Ananya
4. `src/admin/components/dashboard/KpiCard.tsx` — Ananya
5. `src/admin/components/dashboard/PulseDashboard.tsx` — Ananya
6. `src/admin/hooks/usePulseData.ts` — Ananya

---

## Overall Verdict

**CHANGES REQUESTED** — 8 issues found (2 CRITICAL, 3 HIGH, 3 MEDIUM).

The code is well-structured and follows TECH-STACK.md conventions, but has critical security gaps in auth state management and accessibility violations that must be fixed before shipping.

---

## CRITICAL Issues (Must fix before any testing)

### 1. useAdminAuth: Missing token validation before API call

**File:** `src/admin/hooks/useAdminAuth.ts`  
**Lines:** 164-172

**Issue:**  
Auth hook checks for `admin_token` in localStorage but does NOT validate token format/expiry before making API call. If token is malformed or expired, the hook will make a network request that's guaranteed to fail, wasting latency and exposing the API endpoint to invalid requests.

**Evidence:**
```typescript
const token = localStorage.getItem('admin_token');
if (!token) {
  setUser(null);
  setError(null);
  setIsLoading(false);
  return;
}

const res = await authApi.getCurrentUser(); // ❌ No token validation
```

**Expected:**  
Add JWT format validation (basic structure check: `header.payload.signature`) before network call. If token is malformed, clear it immediately and skip the API call.

**Security Impact:**  
Medium — doesn't expose secrets but creates unnecessary attack surface.

**Fix Required:** Add token validation helper or decode JWT expiry claim before fetch.

---

### 2. AdminLayout: No keyboard navigation for mobile nav close button

**File:** `src/admin/components/AdminLayout.tsx`  
**Lines:** 76-81

**Issue:**  
Mobile overlay backdrop uses `onClick` to close nav but is marked `aria-hidden="true"`, making it invisible to screen readers. Users relying on keyboard navigation cannot close the mobile nav via the backdrop click — they must find the X button.

**Evidence:**
```tsx
{mobileNavOpen && (
  <div
    className="fixed inset-0 z-40 bg-black/40 lg:hidden"
    onClick={() => setMobileNavOpen(false)}
    aria-hidden="true" // ❌ Hidden from a11y tree but clickable
  />
)}
```

**Expected:**  
Either:
1. Remove `aria-hidden="true"` and add proper `role="button"` + `aria-label="Close navigation"` + `tabIndex={0}` + `onKeyDown` handler for Enter/Space
2. OR rely solely on the X button for close (remove onClick from backdrop)

**Accessibility Impact:**  
WCAG 2.1.1 (Keyboard) violation — interactive element not keyboard-accessible.

**Fix Required:** Add proper button semantics to backdrop OR make it non-interactive.

---

## HIGH Issues (Fix before delivery)

### 3. auditLogger: No retry mechanism for failed audit logs

**File:** `src/admin/utils/auditLogger.ts`  
**Lines:** 99-131

**Issue:**  
Audit logger uses `keepalive: true` for best-effort delivery but does NOT retry on network failure or 5xx errors. If audit endpoint is temporarily down, critical admin actions (SUSPEND, ESCROW_RELEASE, etc.) will proceed without audit trail.

**Evidence:**
```typescript
if (!response.ok) {
  if (import.meta.env?.DEV) {
    console.warn('[auditLogger] failed to persist entry:', error.error, entry);
  }
  return false; // ❌ No retry, no queue
}
```

**Expected:**  
Either:
1. Queue failed audit logs in localStorage and retry on next page load
2. OR at minimum, send failed audit logs to a separate error monitoring service (Sentry, etc.)

**Security Impact:**  
High — audit trail gaps make incident investigation impossible.

**Fix Required:** Add localStorage-backed retry queue or dead-letter logging.

---

### 4. useAdminAuth: ROLE_PERMISSIONS grants ADMIN full FINANCE_RECONCILE

**File:** `src/admin/hooks/useAdminAuth.ts`  
**Lines:** 79-108

**Issue:**  
`ROLE_PERMISSIONS[AdminRole.ADMIN]` includes `Permission.FINANCE_RECONCILE`, but per `role-permission-matrix.md` line 66, "Resolve Reconciliation Mismatch" is **SUPER_ADMIN only** (write-off risk).

**Evidence:**
```typescript
[AdminRole.ADMIN]: [
  // ... other permissions
  Permission.FINANCE_RECONCILE, // ❌ Should be SUPER_ADMIN only
```

**Spec Reference:**  
`src/admin/__tests__/role-permission-matrix.md` line 66:
> | Resolve Reconciliation Mismatch | ✅ | ❌ | ❌ | SUPER_ADMIN only (write-off risk) |

**Expected:**  
Remove `Permission.FINANCE_RECONCILE` from ADMIN role. ADMIN can VIEW reconciliation (via `FINANCE_VIEW`) but cannot RESOLVE mismatches.

**Security Impact:**  
High — grants ADMIN users ability to write off money mismatches without SUPER_ADMIN oversight.

**Fix Required:** Remove from ADMIN role permission array.

---

### 5. KpiCard: Missing aria-live region for delta changes

**File:** `src/admin/components/dashboard/KpiCard.tsx`  
**Lines:** 109-136

**Issue:**  
KPI cards show animated value/delta updates but do NOT announce changes to screen readers. When pulse data refreshes, screen reader users have no indication that numbers changed.

**Evidence:**
```tsx
<motion.div
  initial={shouldReduceMotion ? {} : { opacity: 0, y: 8 }}
  animate={shouldReduceMotion ? {} : { opacity: 1, y: 0 }}
  // ❌ No aria-live region for value updates
>
```

**Expected:**  
Wrap the `value` span in an `aria-live="polite"` region so screen readers announce "GMV changed to 8.4 crore" on data refresh.

**Accessibility Impact:**  
WCAG 4.1.3 (Status Messages) — dynamic content changes not announced.

**Fix Required:** Add `aria-live="polite"` to value container.

---

## MEDIUM Issues (Fix when possible)

### 6. usePulseData: No error handling for mock data timeout

**File:** `src/admin/hooks/usePulseData.ts`  
**Lines:** 74-88

**Issue:**  
Mock data hook uses `setTimeout` but does NOT catch errors from `getMockPulseData()`. If mock data generation throws (e.g., JSON serialization error in redFlags), the error is unhandled and `isLoading` stays true forever.

**Evidence:**
```typescript
const timer = setTimeout(() => {
  if (cancelled) return;
  setData(getMockPulseData()); // ❌ No try/catch
  setIsLoading(false);
}, 300);
```

**Expected:**  
Wrap in try/catch and set `error` state on failure.

**Impact:**  
Medium — only affects mock mode, but creates bad UX (infinite spinner).

**Fix Required:** Add try/catch around `setData(getMockPulseData())`.

---

### 7. PulseDashboard: Hardcoded "CEO Pulse" heading not role-aware

**File:** `src/admin/components/dashboard/PulseDashboard.tsx`  
**Lines:** 58

**Issue:**  
Dashboard always shows "CEO Pulse" heading, but per `role-permission-matrix.md` line 34, SUPPORT role cannot view pulse (financial data restricted). Component does NOT check role before rendering.

**Evidence:**
```tsx
<h2 className="text-xl font-semibold text-foreground">CEO Pulse</h2>
```

**Expected:**  
Either:
1. Check `useAdminAuth().hasPermission(Permission.DASHBOARD_VIEW)` and show empty state for SUPPORT
2. OR move role check to parent route so SUPPORT users never reach this component

**Impact:**  
Medium — misleading UI, but server will block the data request anyway.

**Fix Required:** Add role check or route guard.

---

### 8. AdminLayout: No error boundary around Outlet/children

**File:** `src/admin/components/AdminLayout.tsx`  
**Lines:** 172

**Issue:**  
Layout renders `{children}` (route content) without an error boundary. If a routed page throws (e.g., KpiCard serialization error), the entire admin shell will crash instead of showing a graceful error state.

**Evidence:**
```tsx
<main className="flex-1 px-4 py-6 sm:px-6 lg:px-8">{children}</main>
```

**Expected:**  
Wrap `children` in a React Error Boundary component that shows "Something went wrong" + retry button.

**Impact:**  
Medium — degrades UX on errors but doesn't block happy path.

**Fix Required:** Add `<ErrorBoundary>` wrapper around `{children}`.

---

## Standards Compliance Check

### TypeScript
- ✅ No `any` types used
- ✅ All props properly typed
- ✅ No unused variables or imports
- ✅ Enums used correctly (AdminRole, Permission as const objects)

### Security
- ❌ Token validation missing (CRITICAL #1)
- ❌ Audit retry missing (HIGH #3)
- ✅ No API keys in code
- ✅ No hardcoded credentials
- ⚠️ RBAC matrix has one incorrect permission grant (HIGH #4)

### Performance
- ✅ Animations use `useReducedMotion()` bypass (KpiCard line 84)
- ✅ Loading skeletons implemented (KpiCard line 99-106)
- ⚠️ No lazy loading yet (acceptable for Phase 1 shell)

### Accessibility
- ❌ Mobile nav backdrop not keyboard-accessible (CRITICAL #2)
- ❌ KPI cards missing aria-live (HIGH #5)
- ✅ All buttons have aria-labels
- ✅ Semantic HTML used (nav, main, header)
- ✅ Color contrast meets WCAG AA (delta pills use -foreground tokens)

### Architecture
- ✅ Components follow PascalCase naming
- ✅ Hooks follow camelCase with `use` prefix
- ✅ No direct database calls from components
- ✅ Separation of concerns (data/presentation/types)

---

## Performance Notes

**Good:**
- Framer Motion animations properly bypass reduced motion
- Loading states implemented for async data
- Memoization used in auth hook callbacks

**Flagged for future:**
- PulseDashboard will need react-query staleTime tuning once real API lands
- Consider virtualization if support ticket list exceeds 100 items

---

## Next Steps

1. **Route to Priya** — Fix CRITICAL #1 (token validation), HIGH #3 (audit retry), HIGH #4 (RBAC permission)
2. **Route to Ananya** — Fix CRITICAL #2 (mobile nav a11y), HIGH #5 (aria-live), MEDIUM #6-8
3. **Re-review** — When fixes land, re-run QA checklist on updated files
4. **Backend sync** — Vikram's `AdminAuthController` must enforce the CORRECTED RBAC matrix (not the one in useAdminAuth.ts line 95)

---

## Test Coverage Gap

**CRITICAL:** No test framework configured in `package.json` (no vitest/jest scripts). Cannot verify RBAC permission matrix behavior until test harness is set up.

**Action Required:** Meera to add vitest + @testing-library/react to devDependencies, then Kavya will run `rbac-permission-matrix.test.ts` (written this cycle, see file).

---

**Reviewed by:** Kavya  
**Next Reviewer:** Priya (for CRITICAL/HIGH fixes), then Meera (build verification)

---

## Cycle 3 Re-verification

**Date:** 2026-07-09  
**Reviewer:** Kavya (QA Lead)  
**Scope:** Verification of 2 BLOCKING security fixes per `wiki/decisions/admin-panel-security-priority.md`

---

### Fix 1: MFA Secret Encryption (BLOCKING)

**Original Finding:** Kabir cycle 2 audit found `admin_users.mfa_secret` stored in PLAINTEXT, contradicting existing AES-256-GCM encryption pattern used for `MetaOAuthToken.encrypted_access_token`.

**Files Reviewed:**
- `influora-api/src/main/java/com/influora/service/admin/AdminMfaSecretCipher.java`
- `influora-api/src/main/java/com/influora/domain/entity/AdminUser.java`
- `influora-api/src/main/java/com/influora/service/admin/AdminAuthService.java`
- `influora-api/src/main/resources/db/migration/V35__encrypt_mfa_secret.sql`
- `influora-api/src/main/java/com/influora/config/AdminMfaProperties.java`
- `influora-api/src/main/resources/application.yml` lines 233-243
- `influora-api/.env.example` lines 117-124
- `influora-api/src/main/java/com/influora/InfluoraApiApplication.java` line 35

**Verification Results:**

✅ **Encryption on write verified:**
- `AdminAuthService.setupMfa()` line 176: generates plaintext TOTP secret
- Line 179: builds `otpAuthUri` from plaintext in memory (never persists)
- Line 183: encrypts via `mfaSecretCipher.encrypt(secret)` BEFORE calling `admin.stageMfaSecret()`
- `AdminUser.stageMfaSecret()` line 133 stores ONLY the ciphertext, never plaintext
- Javadoc line 131 explicitly states: "Caller must pass `AdminMfaSecretCipher.encrypt(...)` output, never the plaintext secret"

✅ **Decryption on read verified:**
- `AdminAuthService.login()` line 106: decrypts just-in-time with `mfaSecretCipher.decrypt(admin.getEncryptedMfaSecret())`
- `AdminAuthService.verifyMfa()` line 196: same pattern
- Plaintext only exists in memory during the current request, never logged or persisted

✅ **Real key, not hardcoded:**
- `AdminMfaSecretCipher` constructor line 40-41 reads key from `AdminMfaProperties.getMfaSecretEncryptionKey()`
- `AdminMfaProperties` line 19 configured via `@ConfigurationProperties(prefix = "influora.admin")`
- Registered in `InfluoraApiApplication.java` line 35 via `@EnableConfigurationProperties`
- `application.yml` line 243: sources from env var `${ADMIN_MFA_SECRET_ENCRYPTION_KEY:...}`
- `.env.example` line 124 documents the env var with proper generation instructions
- Startup validation line 44-54: throws `IllegalStateException` if key missing/blank or not exactly 32 bytes
- Dev default `1FTwBvGuJmF6Q07xw3sMPX0CZEdRWxZx9cIC54HVfUU=` is a real random 32-byte base64 value (matches `MetaTokenStorage` pattern)

✅ **Migration sound:**
- `V35__encrypt_mfa_secret.sql` line 31-32: renames `mfa_secret` → `encrypted_mfa_secret`, widens VARCHAR(255) → TEXT
- Migration comment line 22-29 documents data migration NOT NEEDED: verified via `SELECT COUNT(*) FROM admin_users` against persistent dev DB (influora_ai) = 0 rows
- No existing plaintext data to re-encrypt (no admin accounts provisioned yet, per `AdminAuthService` javadoc line 46-48)
- Column rename + type change safe with zero rows

✅ **Follows MetaTokenStorage pattern:**
- Same AES/GCM/NoPadding algorithm (line 32)
- Same 128-bit GCM tag (line 33)
- Same 12-byte IV (line 34)
- Same 32-byte key length (line 35)
- Same IV-prepended-to-ciphertext wire format (lines 67-70 encrypt, 79-83 decrypt)
- Same SecureRandom IV generation (line 62)
- Same Base64 encoding (line 70)
- Distinct key per service (admin MFA vs Meta OAuth), enforced via separate config properties

**VERDICT: APPROVED ✅**

The fix is complete, correct, and follows established patterns. Plaintext MFA secrets never touch the database or logs. Encryption key properly sourced from config with startup validation. Migration is sound (no existing data to migrate). All callsites verified.

---

### Fix 2: Frontend Critical Issues

#### Issue #1 (CRITICAL): useAdminAuth missing token validation

**Original Finding:** Hook checks for `admin_token` in localStorage but does NOT validate token format/expiry before making API call. Wastes latency on guaranteed-to-fail requests.

**File:** `src/admin/hooks/useAdminAuth.ts` lines 100-228

**Verification Results:**

✅ **Token validation added:**
- New helper `isTokenValid()` lines 100-115 validates JWT structure and expiry
- New helper `decodeJwtSegment()` lines 80-90 safely decodes base64url segments (never throws)
- Validation logic line 104: checks 3 parts, each non-empty
- Validation logic line 106-107: decodes payload segment
- Validation logic line 109-111: checks `exp` claim against current time if present
- Integration in `loadUser()` lines 220-228: validates token BEFORE network call
- Lines 223-227: malformed/expired token → clears localStorage immediately, skips API call, resets state

✅ **Error handling proper:**
- `decodeJwtSegment()` catches all exceptions and returns `null` (line 87-88)
- `isTokenValid()` guards against null/non-string input (line 101)
- `isTokenValid()` guards against malformed payload (line 107)
- No risk of unhandled exceptions crashing the hook

✅ **Security comment accurate:**
- Javadoc lines 95-99 explicitly states: "client-side sanity check only... server remains the sole source of truth for signature verification"
- Does NOT attempt cryptographic verification (correct — no access to secret)
- Only validates structural soundness and expiry claim

**VERDICT: APPROVED ✅**

The fix properly validates JWT format and expiry before making network requests. Implementation is defensive (never throws), correctly scoped (structural check only, not signature verification), and addresses the original finding.

---

#### Issue #2 (CRITICAL): AdminLayout mobile nav backdrop not keyboard-accessible

**Original Finding:** Mobile overlay backdrop uses `onClick` to close nav but is marked `aria-hidden="true"`, making it invisible to screen readers. WCAG 2.1.1 Keyboard violation.

**File:** `src/admin/components/AdminLayout.tsx` lines 70-108

**Verification Results:**

✅ **Backdrop is now a proper button:**
- Line 103: `<button type="button">` instead of plain `<div>`
- Line 107: `aria-label="Close navigation"` provides accessible name
- Line 106: `onClick` handler preserved (pointer interaction)
- Backdrop is now keyboard-operable via Enter/Space (native `<button>` semantics)

✅ **Removed aria-hidden:**
- Original code had `aria-hidden="true"` on a clickable div (a11y violation)
- Current code (line 103-108) has NO `aria-hidden` attribute
- Backdrop is now visible to screen readers and in accessibility tree

✅ **Keyboard navigation enhanced:**
- Lines 74-86: new `useEffect` adds document-level Escape handler to close nav
- Lines 88-96: new `useEffect` manages focus:
  - On open: moves focus to close button (line 92)
  - On close: returns focus to menu trigger button (line 94)
- Refs added line 71-72 for focus management
- Full keyboard flow: Tab to menu button → Enter opens → focus on close button → Escape or Enter closes → focus returns to menu button

✅ **WCAG 2.1.1 (Keyboard) compliance:**
- All interactive elements keyboard-accessible (button, not div)
- Focus trap implemented (focus management useEffect)
- Escape key support (standard pattern for modal overlays)
- Focus restoration (returns to trigger on close)

**VERDICT: APPROVED ✅**

The fix transforms the backdrop from an inaccessible clickable div into a proper keyboard-operable button with focus management and Escape key support. Exceeds the minimum fix (goes beyond just making backdrop accessible — adds full keyboard UX with focus trap and restoration).

---

## Final Verdict

**BOTH BLOCKING FIXES APPROVED ✅**

1. **MFA secret encryption:** Complete, correct, follows established patterns. No plaintext secrets in database or logs. Real encryption key with startup validation. Migration sound.

2. **Frontend critical issues:** Both CRITICAL findings (#1 token validation, #2 mobile nav a11y) properly fixed. Token validation defensive and correctly scoped. Mobile nav now fully keyboard-accessible with focus management.

**P1 FEATURE GATE: NOW OPEN 🚀**

Per `wiki/decisions/admin-panel-security-priority.md`, the two BLOCKING conditions have been satisfied:
1. ✅ `mfa_secret` encrypted using AES-256-GCM (Vikram + V35 migration)
2. ✅ Both CRITICAL QA findings closed (useAdminAuth token validation + AdminLayout mobile nav a11y)

**Priya, Vikram, and Ananya are cleared to resume P1 feature work next cycle:**
- `AdminBrandController.java` (Vikram)
- `CreatorProfile.tsx` editing surface (Ananya)
- `CampaignTable.tsx` admin view (Ananya)

---

**Re-verified by:** Kavya (QA Lead)  
**Date:** 2026-07-09  
**Next:** Meera to verify build passes with encrypted MFA implementation, then Arjun to route P1 features
