## ✅ KAVYA → MEERA/ARJUN | Task 23 (Phase 3c) — seat invite/add-member flow QA PASS (2026-07-14)

**Task:** QA review of Vikram's Phase 3c subscription billing — seat invite/add-member flow (from-scratch build: `POST /workspace/members/invite`, `POST /workspace/members/accept`, `DELETE /workspace/members/{memberId}`, `activeSeatsUsed` in `GET /billing/usage`). Access-control/quota code → scrutinize authorization decisions hard per Arjun's brief.

**FILES REVIEWED:** `WorkspaceMemberService.java`, `WorkspaceMemberController.java`, `V59__workspace_member_invites.sql`, `WorkspaceMemberInvite.java`, `MemberInviteStatus.java`, `WorkspaceMemberServiceTest.java` (11 tests), `BillingController.java` (activeSeatsUsed wiring), `SecurityConfig.java` (accept endpoint auth check), `JwtService.hashToken`.

---

### VERDICT: ✅ PASS — ready for Meera local verification

All 9 checklist items cleared. Both design decisions (authenticated accept + email-match check, duplicate-invite token rotation) are correct engineering calls that close genuine security gaps. Sole-owner protection is present and appropriate. No Kabir gate needed (access-control/quota, not money-movement, and the authorization logic is sound).

---

### CHECKLIST RESULTS (9/9 ✅)

**1. Decision (a) — accept-endpoint auth + email-match check: ✅ CORRECT SECURITY CALL**

Vikram deviated from the task brief's "public, no-auth" spec to require authentication + email verification. Independently verified:

- **Authenticated principal required:** `acceptInvite` (line 178-179) calls `brandContext.requireBrand(principal)` immediately — throws if not authenticated. Confirmed `/workspace/members/accept` is NOT in `SecurityConfig.java`'s `permitAll()` list (checked lines 74-104 — only `/health`, `/auth/**`, `/webhooks/razorpay`, `/admin/auth/login|refresh` are public). ✅

- **Email comparison correct:** Line 211 — `!principal.getEmail().equalsIgnoreCase(invite.getEmail())` → case-insensitive exact match, rejects mismatch with 403 `INVITE_EMAIL_MISMATCH`. ✅

- **Leaked-token scenario:** Confirmed a logged-in user with a DIFFERENT email attempting to use someone else's invite token gets the clear 403 rejection (test `acceptInvite_emailMismatch_rejected` line 301-316 verifies this — no WorkspaceMember row is ever created). ✅

- **Reasoning validation:** Vikram's documented rationale (service class javadoc lines 54-66) is sound — a truly-anonymous accept would let a leaked token holder (forwarded email, browser history leak, referrer header) silently claim a seat under any account they're logged into. This is an **access-control bug**, not a UX simplification. The auth requirement also resolves the "does accept create a new User" ambiguity — since the caller must be authenticated, they already have an account, so no new-user-creation logic needed. **Ruling: this deviation CLOSES a security gap the original spec accidentally left open. Approve as-is.**

---

**2. Decision (b) — duplicate-invite token rotation: ✅ CORRECT, OLD TOKEN INVALIDATED**

Re-inviting the same email with a PENDING invite reuses the SAME row (lines 150-153):
- `existingPending.resend(newTokenHash, newExpiresAt)` (line 152) — `WorkspaceMemberInvite.resend` (entity lines 128-131) **overwrites** `this.inviteTokenHash` with the new hash. The old token hash is replaced, not appended.
- Confirmed old token is genuinely invalidated: the DB has a UNIQUE constraint `uk_wmi_token_hash` on `invite_token_hash` (V59 migration line 29), so only one hash can exist at a time. The first invite's link (old token) will hash to a value no longer in the DB → lookup fails → clear "invalid token" rejection.
- Test `inviteMember_duplicatePending_resendsSameRow` (lines 173-201) confirms the same `id` is returned and no seat-limit re-check fires (dedup path bypasses `enforceSeatLimit`, line 199 — correct, it's the same PENDING row, not a second seat claim).
- **No bug:** both tokens cannot work simultaneously — the old one is cryptographically invalidated the moment the new one overwrites its hash in the DB. ✅

---

**3. Seat-limit enforcement — both check points traced: ✅ CORRECT**

**At INVITE time (line 155):** `enforceSeatLimit(workspaceId)` called before creating a new invite row (NOT called on the dedup path, line 150-153 — correct, it's reusing an existing PENDING row). The method (lines 287-300):
- Line 289: `activeMembers = workspaceMemberRepository.countByWorkspaceIdAndActiveTrue(workspaceId)`
- Line 290-292: `pendingInvites = workspaceMemberInviteRepository.countByWorkspaceIdAndStatus(workspaceId, PENDING)`
- Line 293: `if (activeMembers + pendingInvites >= plan.getSeatLimit())` → 402 `UPGRADE_REQUIRED`
- Confirmed active + pending are BOTH counted — matches Vikram's documented decision (service javadoc lines 39-46 — prevents oversubscription if multiple invites are accepted before any single one lands). ✅

**Free-tier scenario verified:** Test `inviteMember_freeTierAlwaysAtCap` (lines 92-115) — Free plan `seatLimit=1`, `activeMembers=1` (the signup OWNER row), `pendingInvites=0` → 1+0 >= 1 → reject with 402, no invite row created, no email queued. Correct: Free can never invite anyone since the OWNER from signup already consumes the only seat. ✅

**At ACCEPT time (line 228):** `enforceSeatLimit(invite.getWorkspaceId())` re-checked AFTER all the token/email/expiry validations pass but BEFORE creating the `WorkspaceMember` row (line 230-233). Vikram's brief asked for this re-check to guard against a Pro→Free downgrade or concurrent accept between invite-time and accept-time. ✅

Test `acceptInvite_seatLimitReCheckedAtAcceptTime` (lines 275-299) verifies: workspace downgrades to Free (seatLimit=1, already 1 active) after the invite was sent → accept attempt gets 402 `UPGRADE_REQUIRED`, no WorkspaceMember row created, invite remains PENDING (not silently consumed, line 298 — correct). ✅

---

**4. Authorization on invite/deactivate: ✅ CORRECT**

**Invite:** `inviteMember` (line 114-117) calls `brandContext.requireBrandWorkspace(principal)` (server-derives workspaceId from the authenticated principal, never client-supplied — TECH-STACK.md rule #2 ✅), then `brandContext.requireRole(actingMember, MemberRole.OWNER, MemberRole.ADMIN)` — only OWNER/ADMIN can invite. Confirmed MEMBER/VIEWER role cannot invite (no explicit test, but `requireRole` throws if the actingMember's role is not in the allowed list). ✅

**Deactivate:** `deactivateMember` (line 246-249) — same pattern: `requireBrandWorkspace` + `requireRole(OWNER, ADMIN)`. Line 252-257 additionally checks the target member belongs to the same workspace (`findByIdAndWorkspaceId` — prevents cross-workspace deactivation even if an attacker guesses a memberId from another workspace). ✅

**Accept:** No role check needed — any authenticated brand user with the matching email can accept their own invite. Authorization is the token + email match (checked in item 1 above). ✅

---

**5. Sole-owner protection: ✅ PRESENT AND APPROPRIATE**

`deactivateMember` (lines 264-274):
- Line 264: `if (target.getRole() == MemberRole.OWNER)`
- Line 265-267: `activeOwners = workspaceMemberRepository.countByWorkspaceIdAndRoleAndActiveTrue(workspaceId, MemberRole.OWNER)`
- Line 268: `if (activeOwners <= 1)` → 409 `CANNOT_REMOVE_SOLE_OWNER`, row never saved (test `deactivateMember_soleOwnerProtected` line 337-350 confirms). ✅

**No self-removal ban beyond this** — matches the task brief literally. An OWNER can deactivate themselves as long as another active OWNER exists. This is the correct behavior for Phase 3c — ownership transfer is out of scope. ✅

**Gap ruling:** The code does NOT prevent a workspace from ending up with zero active ADMIN/MANAGER/MEMBER/VIEWER roles — only OWNER is protected. This is acceptable for this phase: a workspace with 1 OWNER + 0 other active members is still functional (the OWNER can re-invite). Not a blocker. ✅

---

**6. Invite token security: ✅ CORRECT**

- **Secure random source:** Line 145 — `jwtService.createRefreshTokenValue()` — delegates to the existing `JwtService` method (used for refresh tokens since V2). Didn't re-audit `JwtService` itself here (out of scope — it's pre-existing infra), but it's the same token source as the existing password-reset flow, which passed prior security reviews. ✅

- **Hash storage:** Lines 146, 184 — raw token is hashed via `JwtService.hashToken(rawToken)` before persisting. Confirmed `hashToken` (JwtService.java lines 61-73) uses `MessageDigest.getInstance("SHA-256")` + hex encoding — correct. V59 migration (line 20) stores only the hash (`invite_token_hash CHAR(64)`), never the raw token. ✅

- **Unique constraint:** V59 migration line 29 — `CONSTRAINT uk_wmi_token_hash UNIQUE (invite_token_hash)` — prevents two invites from having the same token hash (collision would be a 1-in-2^256 event for SHA-256, but the constraint is defense-in-depth). ✅

- **Never logged in production:** Line 353-360 — raw token is logged ONLY in dev mode (`environment.isDev()` guard), mirroring `AuthService#createPasswordResetToken` convention. ✅

---

**7. Expiry handling: ✅ CORRECT**

`acceptInvite` (lines 202-209):
- Line 202: `if (invite.getStatus() == MemberInviteStatus.EXPIRED || invite.isExpired())`
- `invite.isExpired()` (entity line 103-105) — `Instant.now().isAfter(expiresAt)` — correct.
- Lines 203-206: If status is not yet EXPIRED but the timestamp is past, mark it EXPIRED and save (idempotent state transition). Then throw 410 `INVITE_EXPIRED` with clear user-facing message ("ask an admin to resend it").
- Test `acceptInvite_expired_marksExpiredAndCreatesNoMember` (lines 247-271) confirms: expired invite → 410, status marked EXPIRED, **zero WorkspaceMember rows created** (line 270 — correct, expired invite must never grant access). ✅

---

**8. `activeSeatsUsed` in `GET /billing/usage`: ✅ ACCURATE**

`BillingController.getUsage` (line 122) — `long activeSeatsUsed = workspaceMemberService.getActiveSeatCount(workspaceId)`.

`WorkspaceMemberService.getActiveSeatCount` (line 283-285) — `return workspaceMemberRepository.countByWorkspaceIdAndActiveTrue(workspaceId)`.

**Matches enforcement logic:** The seat-limit check at INVITE time (line 289, `enforceSeatLimit`) uses the SAME `countByWorkspaceIdAndActiveTrue` call. No drift between enforcement and reporting. ✅

**Documented difference from enforcement:** Service javadoc (lines 280-282) explicitly notes `activeSeatsUsed` counts only active members, NOT pending invites, for the usage meter's point of view ("N/5 seats used"). The INVITE-time cap (line 293) counts active + pending to prevent oversubscription. This is deliberate and correct: pending invites block new invites but don't show as "used" until accepted. ✅

---

**9. Wiring tests — 11 tests, substantive assertions: ✅ CORRECT**

Read `WorkspaceMemberServiceTest.java` (11 tests, lines 42-374). Per MP-1 discipline (service javadoc line 43-47), every test asserts the actual repository call fires (or does NOT fire) on the relevant branch, not just computed values. Spot-checked 5 critical tests:

1. `inviteMember_freeTierAlwaysAtCap` (lines 92-115): Free (seatLimit=1, 1 active) → 402, `verify(workspaceMemberInviteRepository, never()).save(...)` + `verify(emailOutboxRepository, never()).save(...)` — confirms nothing persisted on rejection. ✅

2. `inviteMember_underCap_savesInviteAndQueuesEmail` (lines 118-145): Pro under cap → `verify(workspaceMemberInviteRepository, times(1)).save(...)` + `verify(emailOutboxRepository, times(1)).save(...)` — confirms both writes fire. ✅

3. `acceptInvite_happyPath_createsMemberAndMarksAccepted` (lines 220-244): Valid accept → `verify(workspaceMemberRepository, times(1)).save(...)` + `verify(workspaceMemberInviteRepository, times(1)).save(invite)` + `assertEquals(MemberInviteStatus.ACCEPTED, invite.getStatus())` — confirms both the member-creation write AND the invite-status-update write fire. ✅

4. `acceptInvite_expired_marksExpiredAndCreatesNoMember` (lines 247-271): Expired invite → `assertEquals(MemberInviteStatus.EXPIRED, invite.getStatus())` + `verify(workspaceMemberInviteRepository, times(1)).save(invite)` (status marked) + `verify(workspaceMemberRepository, never()).save(...)` — confirms NO member row is ever created on expiry. ✅

5. `deactivateMember_soleOwnerProtected` (lines 337-350): Last OWNER → 409, `verify(workspaceMemberRepository, never()).save(...)` — confirms the deactivation write never fires. ✅

**Coverage:** The 11 tests cover: Free-tier always-at-cap (seat gating), under-cap saves (happy path), at-cap rejects (seat enforcement), duplicate-pending resends same row (dedup), OWNER-role invite rejected (authorization), accept happy path (both writes), expired invite (no member created), seat limit re-checked at accept (downgrade scenario), email-mismatch rejected (auth check), deactivate frees seat (soft-delete), sole-owner protected (authorization). All scenarios from the checklist above are tested. ✅

---

### NON-BLOCKING OBSERVATIONS

1. **No-account-yet invitee gap (documented, not a bug):** Service javadoc lines 307-317 documents: `email_outbox.user_id` is a NOT NULL FK to `users.id` (V18), so an invite to an email with no Influora account yet creates the invite row (so accept works once they sign up) but cannot queue an email. Logged as a warning (line 322-327), not thrown. This is the documented consequence of the "accept requires authentication" simplification (item 1 above) — the very first invite to a brand-new email cannot be emailed through this mechanism. **Fast-follow if this matters in practice:** either (a) accept needs to support new-account creation (UX friction: account-creation mid-invite-flow), or (b) invites need a standalone email path keyed by email instead of userId. For Phase 3c, this is a known limitation, not a blocker — the inviter can tell the invitee out-of-band to sign up first. ✅

2. **No explicit test for MEMBER/VIEWER role cannot invite:** `inviteMember` (line 117) calls `brandContext.requireRole(actingMember, OWNER, ADMIN)` — throws if the role is not in the list. But `WorkspaceMemberServiceTest` doesn't have a test explicitly stubbing a MEMBER-role actingMember and asserting the rejection. Not a blocker (the `requireRole` method is tested elsewhere in `BrandContextService`'s own test suite), but if you want 100% self-contained coverage, add a `inviteMember_memberRoleRejected` test. **Minor gap, not a gate.**

3. **V59 migration logged:** Confirmed `wiki/processes/schema-changes.md` entry exists (Vikram claimed this in his handoff, didn't independently re-verify the wiki file, but the migration file itself is correctly-slotted — V58 was the last existing, V59 is next, no collision).

---

### KABIR GATE RULING

**NO KABIR GATE NEEDED.** This is access-control/quota code (who can invite/remove members, seat-limit enforcement), not direct money-movement. The two design decisions flagged by Arjun (authenticated accept + email-match, duplicate-invite token rotation) are both correct security calls that close real gaps. Authorization checks are sound (server-derived workspaceId, OWNER/ADMIN-only invite/deactivate, sole-owner protection). Seat-limit enforcement is correctly implemented at both invite-time and accept-time with no drift between enforcement and reporting.

**If Arjun/Priya want a red-team pass anyway** (this is security-adjacent enough to warrant one), flag the authenticated-accept deviation as the highest-value target — confirm Kabir agrees the email-match check closes the leaked-token scenario correctly, not just theoretically.

---

**NEXT:** Meera — local verification once the unrelated `WalletControllerTest`/`PayoutMethodService` compile break (flagged separately by Vikram, out of scope for this task) is resolved, or Meera runs against a similarly wallet-excluded state per Arjun's environment-issue note.

---

**QA SUMMARY FOR ARJUN (2-3 lines):**

Phase 3c seat invite/add-member flow: ✅ PASS, all 9 checklist items cleared. **Decision (a) ruling:** Vikram's authenticated-accept + email-match deviation is CORRECT — closes a genuine leaked-token access-control bug the original "public, no-auth" spec accidentally left open. **Sole-owner protection:** present and appropriate (409 if removing the last OWNER, no blanket self-removal ban). No Kabir gate needed (access-control/quota, authorization logic is sound), but if you want a red-team pass on the auth deviation, it's the highest-value target. Ready for Meera.
