# QA Review: Task 23 (Phase 3c) — Seat Invite/Add-Member Flow
**Date:** 2026-07-14  
**Reviewer:** Kavya (QA Lead)  
**Status:** ✅ PASS

---

## SUMMARY

Phase 3c subscription billing — seat invite/add-member flow (from-scratch build: `POST /workspace/members/invite`, `POST /workspace/members/accept`, `DELETE /workspace/members/{memberId}`, `activeSeatsUsed` in `GET /billing/usage`).

**VERDICT:** All 9 checklist items cleared. Both design decisions (authenticated accept + email-match check, duplicate-invite token rotation) are correct engineering calls that close genuine security gaps. Sole-owner protection is present and appropriate. No Kabir gate needed (access-control/quota, not money-movement, and the authorization logic is sound).

---

## FILES REVIEWED

- `WorkspaceMemberService.java` — invite/accept/deactivate logic, seat-limit enforcement
- `WorkspaceMemberController.java` — 3 endpoints
- `V59__workspace_member_invites.sql` — migration with token-hash unique constraint
- `WorkspaceMemberInvite.java` — entity + token-rotation logic
- `MemberInviteStatus.java` — enum (PENDING/ACCEPTED/EXPIRED/REVOKED)
- `WorkspaceMemberServiceTest.java` — 11 wiring tests
- `BillingController.java` — activeSeatsUsed wiring
- `SecurityConfig.java` — accept endpoint auth check
- `JwtService.hashToken` — SHA-256 token hashing

---

## CHECKLIST RESULTS (9/9 ✅)

### 1. Decision (a) — Accept-Endpoint Auth + Email-Match Check: ✅ CORRECT SECURITY CALL

Vikram deviated from the task brief's "public, no-auth" spec to require authentication + email verification.

**Independently verified:**
- `acceptInvite` (line 178-179) calls `brandContext.requireBrand(principal)` immediately — throws if not authenticated
- `/workspace/members/accept` is NOT in `SecurityConfig.java`'s `permitAll()` list (checked lines 74-104)
- Email comparison (line 211): `!principal.getEmail().equalsIgnoreCase(invite.getEmail())` → case-insensitive exact match, rejects mismatch with 403 `INVITE_EMAIL_MISMATCH`
- Leaked-token scenario: user with DIFFERENT email attempting to use someone else's token gets clear 403 rejection (test `acceptInvite_emailMismatch_rejected` verifies — no WorkspaceMember row created)

**Ruling:** This deviation CLOSES a security gap the original spec accidentally left open. A truly-anonymous accept would let a leaked token holder (forwarded email, browser history leak, referrer header) silently claim a seat under any account. **Approve as-is.**

---

### 2. Decision (b) — Duplicate-Invite Token Rotation: ✅ CORRECT, OLD TOKEN INVALIDATED

Re-inviting the same email with a PENDING invite reuses the SAME row (lines 150-153):
- `existingPending.resend(newTokenHash, newExpiresAt)` **overwrites** `this.inviteTokenHash`
- DB has UNIQUE constraint `uk_wmi_token_hash` on `invite_token_hash` (V59 line 29)
- Old token is cryptographically invalidated the moment the new one overwrites its hash
- Test `inviteMember_duplicatePending_resendsSameRow` confirms same `id` returned, no seat-limit re-check

**No bug:** Both tokens cannot work simultaneously.

---

### 3. Seat-Limit Enforcement — Both Check Points: ✅ CORRECT

**At INVITE time (line 155):**
- `enforceSeatLimit(workspaceId)` before creating new invite row
- Counts: `activeMembers + pendingInvites >= plan.getSeatLimit()` → 402
- Test `inviteMember_freeTierAlwaysAtCap`: Free (seatLimit=1, 1 active OWNER) → reject, no invite created

**At ACCEPT time (line 228):**
- Re-checked BEFORE creating WorkspaceMember row
- Guards against Pro→Free downgrade or concurrent accept
- Test `acceptInvite_seatLimitReCheckedAtAcceptTime`: downgrade after invite sent → 402, no member created, invite stays PENDING

---

### 4. Authorization on Invite/Deactivate: ✅ CORRECT

**Invite:** `requireBrandWorkspace(principal)` (server-derives workspaceId, TECH-STACK.md rule #2) + `requireRole(OWNER, ADMIN)`

**Deactivate:** Same pattern + `findByIdAndWorkspaceId` (prevents cross-workspace deactivation)

**Accept:** Token + email match is the authorization (no role check needed)

---

### 5. Sole-Owner Protection: ✅ PRESENT AND APPROPRIATE

`deactivateMember` (lines 264-274):
- If removing an OWNER: `countByWorkspaceIdAndRoleAndActiveTrue(workspaceId, OWNER)`
- If `activeOwners <= 1` → 409 `CANNOT_REMOVE_SOLE_OWNER`
- Test `deactivateMember_soleOwnerProtected` confirms

**Gap ruling:** Code does NOT prevent workspace with zero ADMIN/MANAGER/MEMBER/VIEWER — only OWNER protected. Acceptable: workspace with 1 OWNER + 0 others is still functional.

---

### 6. Invite Token Security: ✅ CORRECT

- **Secure random:** `jwtService.createRefreshTokenValue()` (same as password-reset, passed prior reviews)
- **Hash storage:** `JwtService.hashToken` uses SHA-256 + hex encoding
- **Unique constraint:** V59 migration line 29 — `uk_wmi_token_hash`
- **Never logged in production:** Line 353-360 — dev-mode guard

---

### 7. Expiry Handling: ✅ CORRECT

- `acceptInvite` (lines 202-209): checks `invite.isExpired()` (Instant.now().isAfter(expiresAt))
- Marks invite EXPIRED, throws 410 with clear message
- Test `acceptInvite_expired_marksExpiredAndCreatesNoMember`: expired → 410, status marked, zero WorkspaceMember rows created

---

### 8. `activeSeatsUsed` in GET /billing/usage: ✅ ACCURATE

- `BillingController.getUsage` line 122: `workspaceMemberService.getActiveSeatCount(workspaceId)`
- Uses same `countByWorkspaceIdAndActiveTrue` as enforcement logic (no drift)
- Documented: counts only active members, NOT pending invites (for "N/5 seats used" meter)

---

### 9. Wiring Tests — 11 Tests, Substantive Assertions: ✅ CORRECT

Spot-checked 5 critical tests:
1. `inviteMember_freeTierAlwaysAtCap`: 402, `verify(never()).save(...)`
2. `inviteMember_underCap_savesInviteAndQueuesEmail`: both writes verified
3. `acceptInvite_happyPath_createsMemberAndMarksAccepted`: both writes + status check
4. `acceptInvite_expired_marksExpiredAndCreatesNoMember`: status marked, NO member created
5. `deactivateMember_soleOwnerProtected`: 409, deactivation write never fires

**Coverage:** Free-tier always-at-cap, under-cap saves, at-cap rejects, duplicate-pending resends, OWNER-role invite rejected, accept happy path, expired invite, seat limit re-checked at accept, email-mismatch rejected, deactivate frees seat, sole-owner protected.

---

## NON-BLOCKING OBSERVATIONS

1. **No-account-yet invitee gap (documented, not a bug):** Invite to email with no Influora account creates invite row but cannot queue email (`email_outbox.user_id` is NOT NULL FK). Logged as warning. Fast-follow if needed: (a) accept supports new-account creation, or (b) standalone email path keyed by email.

2. **No explicit test for MEMBER/VIEWER role cannot invite:** `requireRole` throws if not OWNER/ADMIN, but no explicit test. Minor gap, not a gate.

3. **V59 migration logged:** Correctly-slotted (V58 was last, V59 is next).

---

## KABIR GATE RULING

**NO KABIR GATE NEEDED.** Access-control/quota code, not money-movement. Authorization checks are sound. Seat-limit enforcement correct at both invite-time and accept-time with no drift.

**If Arjun/Priya want a red-team pass anyway** (security-adjacent), flag the authenticated-accept deviation as highest-value target.

---

## NEXT

Meera — local verification once unrelated `WalletControllerTest`/`PayoutMethodService` compile break is resolved, or run against wallet-excluded state per Arjun's environment-issue note.

---

## QA SUMMARY FOR ARJUN

Phase 3c seat invite/add-member flow: ✅ PASS, all 9 checklist items cleared. **Decision (a):** authenticated-accept + email-match is CORRECT — closes leaked-token access-control bug. **Sole-owner protection:** present and appropriate. No Kabir gate needed. Ready for Meera.
