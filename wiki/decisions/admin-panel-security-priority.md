# CEO Decision: Admin Panel MFA Secret Encryption Priority

**Date:** 2026-07-09  
**Decision by:** Swapnil Maruti (CEO)  
**Escalated by:** Arjun (Pipeline COO), Kabir (Security)

---

## Context

Kabir's cycle 2 security audit found that `admin_users.mfa_secret` (TOTP MFA secrets for admin accounts) is stored in **PLAINTEXT** in the new V34 migration, AdminUser entity, and AdminAuthService.

This contradicts the existing AES-256-GCM encryption-at-rest pattern already in use elsewhere in this codebase (MetaTokenStorage.java for OAuth tokens).

**Impact if unaddressed:** If the database is dumped, backed up, or leaked, every admin account's MFA is permanently bypassable. This defeats the mandatory-MFA enforcement for SUPER_ADMIN/ADMIN roles that was just built this cycle.

Also open: 2 critical + 3 high QA findings from Kavya (missing token validation in useAdminAuth.ts, mobile nav keyboard-inaccessible).

---

## Decision

**PAUSE P1 FEATURES. FIX SECURITY FIRST.**

Cycle 3 priorities, in order:

1. **BLOCKING:** Vikram encrypts `mfa_secret` using the existing AES-256-GCM pattern from `MetaTokenStorage.java`. Add encryption key to `application.yml`, update `AdminUserService` with encrypt/decrypt, write migration V35 to backfill existing rows.

2. **BLOCKING:** Close Kavya's 2 CRITICAL findings (missing token validation in `useAdminAuth.ts`, any other auth-path issues).

3. **PARALLEL (once 1+2 closed):** Kavya's 3 HIGH findings (keyboard-inaccessible nav, audit logger no retry, KPI cards missing aria-live) can be worked alongside resumed P1 feature work.

**No new feature PRs merge until the mfa_secret encryption and both critical QA findings are closed.**

---

## Rationale

- The fix is bounded: pattern exists, copy it over. This is not architecture work.
- Plaintext MFA secrets is a ship-blocking defect, not a "nice to have."
- Parallel-tracking security fixes with feature work creates merge conflicts and diffused attention. Close the hole first.

---

## Routing

- **Vikram:** V35 migration + AdminUserService encryption, immediately.
- **Ananya:** Close useAdminAuth.ts token validation CRITICAL.
- **Kavya:** Verify both fixes before any P1 feature work resumes.
- **Priya:** Confirm encryption implementation follows MetaTokenStorage pattern.

---

**Signed:** Swapnil Maruti, CEO
