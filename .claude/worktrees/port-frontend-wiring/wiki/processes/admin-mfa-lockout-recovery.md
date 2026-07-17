# Admin MFA Lockout Recovery

**Kabir P1-2 Gap 1 (HIGH)** — Recovery path for admins locked out by `mfaEnforceOnLogin=true` enforcement.

## Background

With `influora.admin.mfa-enforce-on-login=true` (the default), any `SUPER_ADMIN` or `ADMIN` row with `mfa_enabled=false` cannot log in — they are rejected with `MFA_ENROLLMENT_REQUIRED` (HTTP 403).

**The Problem:**
- No admin self-registration endpoint exists
- No "reset another admin's MFA" endpoint exists
- Recovery requires direct database access or environment variable override

---

## Pre-Deployment Check (MANDATORY)

**Before deploying with `mfaEnforceOnLogin=true` (the default), run this SQL query:**

```sql
SELECT id, email, role, mfa_enabled 
FROM admin_users 
WHERE role IN ('SUPER_ADMIN', 'ADMIN') 
  AND mfa_enabled = false;
```

**If this query returns ANY rows:**
1. Either enroll those admins in MFA out-of-band (via direct DB update after generating TOTP secret)
2. OR set `ADMIN_MFA_ENFORCE_ON_LOGIN=false` in environment variables (disables enforcement for all admins)

**If you skip this check and deploy with unenrolled admins, they will be permanently locked out via application logic.**

---

## Recovery Options

If an admin is locked out (`MFA_ENROLLMENT_REQUIRED` error), use ONE of these recovery paths:

### Option 1: Temporarily Demote to SUPPORT Role

**Who can use this:** DBA with direct DB access

**Steps:**
1. Connect to production database
2. Update the locked-out admin's role:
   ```sql
   UPDATE admin_users 
   SET role = 'SUPPORT', updated_at = NOW() 
   WHERE email = 'locked-admin@example.com';
   ```
3. Admin can now log in with password only (SUPPORT tier is exempt from MFA enforcement)
4. Admin enrolls MFA via `/admin/auth/mfa/setup` → `/admin/auth/mfa/verify`
5. DBA restores original role:
   ```sql
   UPDATE admin_users 
   SET role = 'SUPER_ADMIN', updated_at = NOW() 
   WHERE email = 'locked-admin@example.com';
   ```

**Risk:** Grants temporary access to admin panel with reduced privileges; audit logs will show role change.

---

### Option 2: Disable MFA Enforcement via Environment Variable

**Who can use this:** DevOps with access to application environment variables

**Steps:**
1. Set environment variable:
   ```bash
   ADMIN_MFA_ENFORCE_ON_LOGIN=false
   ```
2. Restart application (Spring Boot will reload `AdminSecurityProperties`)
3. All admins can now log in with password only
4. Locked-out admin logs in and enrolls MFA
5. **CRITICAL:** Remove the env var override and restart (re-enables enforcement)

**Risk:** Disables MFA enforcement for ALL admins during recovery window; use a tight time window.

---

### Option 3: Enroll MFA Out-of-Band (Recommended)

**Who can use this:** DBA with access to admin's authenticator app (in-person or trusted channel)

**Steps:**
1. Generate TOTP secret server-side (use same library: `TotpService.generateSecret()`)
2. Encrypt secret with `AdminMfaSecretCipher` (requires `ADMIN_MFA_ENCRYPTION_KEY` from env)
3. Update database:
   ```sql
   UPDATE admin_users 
   SET encrypted_mfa_secret = '<AES-256-GCM ciphertext>', 
       mfa_enabled = true, 
       updated_at = NOW() 
   WHERE email = 'locked-admin@example.com';
   ```
4. Admin adds TOTP secret to authenticator app manually (via QR code or text entry)
5. Verify admin can log in with password + MFA code

**Risk:** Lowest risk; does not bypass enforcement or reduce privileges.

---

## Emergency Admin Seeding Script (Out of Scope)

**Gap flagged but not shipped:** No CLI tool exists to create a fresh SUPER_ADMIN with MFA pre-enrolled.

**Future task (P2):** Build `scripts/seed-admin.sh` that:
1. Prompts for email + password
2. Generates TOTP secret
3. Shows QR code for authenticator app enrollment
4. Inserts row into `admin_users` with `mfa_enabled=true`

**Current workaround:** Manual SQL insert + out-of-band MFA enrollment (Option 3 above).

---

## Audit Trail

All recovery operations (role changes, MFA updates) must be logged:
- In `admin_users.updated_at` (automatic via `touch()` method)
- In application logs (if using Option 2, Spring Boot logs config property overrides)
- In external audit system (manual entry for DBA-level SQL operations)

---

## References

- **Kabir audit:** `wiki/reports/2026-07-12/tasks/P1-2-admin-mfa-lockout.md` (Gap 1)
- **Configuration:** `AdminSecurityProperties.java` (javadoc lines 22-32)
- **Enforcement logic:** `AdminAuthService.java` (lines 142-152)
