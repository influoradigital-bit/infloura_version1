# QA Review: T-CREATORCONNECT-0902 Backend Half

**Date:** 2026-09-02  
**Reviewer:** Kavya (QA Lead)  
**Files:** Migration V20260902120000, entities/repos/DTOs/services/controllers for external_creators + creator_connection_requests, Meta clients, email templates, hook call sites, application.yml, deploy configs  

---

## FINDINGS

**NONE - all checks pass.**

---

## VERIFICATION RESULTS

### Contract Parity ✅
- **ExternalCreatorResponse** (Java) matches **ExternalCreator** (TS in api.ts) field-for-field, same nullability
- **ConnectionRequestResponse** (Java) matches **ConnectionRequest** (TS) field-for-field, same nullability
- **AdminConnectionDto** (Java) matches **AdminConnection** (TS in admin.types.ts) field-for-field, same nullability
- All paths (/creators/external, /creators/external/lookup, /creators/external/{id}/connect, /creators/external/connection-requests, /admin/creator-connections/*) match contract exactly
- Status enum strings (UNVERIFIED/INVITED/JOINED, PENDING/CONTACTED/JOINED/DECLINED) match TS types exactly

### Security ✅
**Brand endpoints (ExternalCreatorController):**
- Line 313 ExternalCreatorService.connect: `brandContextService.requireBrandWorkspace(principal)` enforced
- Line 213 lookup: requireBrandWorkspace enforced
- Lines 330-332: `TextSanitizer.sanitizePlainText(rawMessage)` + capped at 1000
- Line 317: externalCreatorId must exist or 404 NOT_FOUND (no IDOR)
- Lines 324-327: 409 CREATOR_ALREADY_ON_INFLUORA if already JOINED with linkedCreatorProfileId in error details

**Admin endpoints (AdminCreatorConnectionController + AdminCreatorConnectionService):**
- Lines 105-106, 124-125, 137, 164, 191: `adminContext.requireRoleWithMfaSatisfied(principal, SUPER_ADMIN, ADMIN[, SUPPORT])` on every method
- Lines 145-153, 172-179, 216-233: `adminAuditLogService.record` on every mutation (markContacted, decline, invite)
- Lines 141, 168, 207: `sanitizeNotes(rawNotes)` → TextSanitizer + substring(0,1000)
- InviteRequest DTO: `@NotBlank @Email` on email field, `@Size(max=1000)` on notes (AdminCreatorConnectionDtosTest line 43 proves bean validation rejects blank/malformed email)

### Honesty ✅
- Lines 219-224 ExternalCreatorService.lookup: Meta unconfigured → 503 `INSTAGRAM_LOOKUP_UNAVAILABLE` "Instagram lookup isn't connected yet", never mock
- Lines 226-231: No usable token → 503 same code, never mock
- All nullable metrics (followers, mediaCount, engagementRate) stay null when Meta returns nothing (F-0259/F-0260 discipline)
- Marketplace client unreachable unless `influora.meta.creator-marketplace.enabled=true` (application.yml L282, MetaApiProperties.creatorMarketplace.enabled checked at ExternalCreatorService line 137)

### JOINED Hook ✅
**ExternalCreatorLinkService.onCreatorIdentified:**
- Line 63: `@Transactional` ✅
- Lines 76-83: Matches `ig_account_id` exact first, then `ig_username` case-insensitive fallback ✅
- Lines 89-101: Already-linked-to-different-profile guard (handle collision protection) ✅
- Line 106: `external.markJoined(creatorProfileId)` sets JOINED status + linked_creator_profile_id + joined_at ✅
- Lines 109-112: Finds every PENDING/CONTACTED request via `findByExternalCreatorIdAndStatusIn` ✅
- Lines 123-134: For each request, `request.markJoined()` + `eventPublisher.publishEvent(ConnectedCreatorJoinedEvent)` — exactly one event per request ✅
- Lines 135-143: try/catch swallows exceptions, never fails caller's own write (same discipline as AdminAuditLogService) ✅

**NotificationListener:**
- `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)` on ConnectedCreatorJoinedEvent handler ✅
- Lines checking `request.getJoinedNotifiedAt() != null` → skip (idempotent on re-fire) ✅
- Calls `notificationService.notify(...)` with template key `brand.connected_creator_joined` + link `/brand/campaigns/new?creatorId=` ✅
- `request.markJoinedNotified()` + save stamps joined_notified_at ✅

**Admin email (CreatorConnectionRequestedEvent handler):**
- Lines checking `adminNotificationEmail.isBlank()` → log.warn + return (skip when blank, never throws) ✅
- Goes direct via Msg91EmailClient (no User row for EmailOutbox, same pattern as WorkspaceMemberService.sendInviteEmailDirect) ✅

### Migration ✅
- Dialect: MySQL 8.0 with `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci` matches V20260721130000__creator_captions.sql and V20260724120000__shipments.sql exactly
- VARCHAR(26) for ULIDs, all column names/lengths match entity mappings
- Unique keys: `uk_external_creators_username(ig_username)`, `uk_external_creators_ig_account(ig_account_id)`, `uk_ccr_workspace_creator(workspace_id, external_creator_id)` as specified
- Foreign key `fk_ccr_external_creator` present

### Email Templates ✅
- EmailTemplateRegistry: `admin.creator_connection_requested`, `creator.join_invitation`, `brand.connected_creator_joined` all present

### Deploy Config ✅
- deploy/utho/generate-env.sh: `ADMIN_NOTIFICATION_EMAIL=` (blank, not a literal placeholder like REPLACE_ME — correct per contract)
- deploy/utho/docker-compose.utho.yml + deploy/hostinger/docker-compose.hostinger.yml: both have `ADMIN_NOTIFICATION_EMAIL` and `META_CREATOR_MARKETPLACE_ENABLED` in env lists
- application.yml L282: `influora.meta.creator-marketplace.enabled: ${META_CREATOR_MARKETPLACE_ENABLED:false}` (placeholder present — memory: env names without one bind to nothing)
- application.yml L379: `influora.admin.notification-email: ${ADMIN_NOTIFICATION_EMAIL:}` (placeholder present)

### Tests ✅
```
Tests run: 20, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS

- ExternalCreatorServiceTest: 4 tests (connect idempotent-returns-unchanged; connect on JOINED→409; lookup Meta-unconfigured→503)
- ExternalCreatorLinkServiceTest: 4 tests (id-match + username-fallback; flips PENDING+CONTACTED to JOINED; one event per request; no-match no-op)
- AdminCreatorConnectionDtosTest: 4 tests (InviteRequest rejects blank/null/malformed email via bean validation)
- EmailTemplateRegistryTest: 8 tests (all 3 new keys registered + contract validation)
```

### Deviations (from Vikram's handoff) — VERDICT ON EACH:

1. **DECLINED reopened on re-connect** (ExternalCreatorService.java:342-349): Schema's `uk_ccr_workspace_creator` unique key forces one row per (workspace, external_creator). Contract only specified idempotency for non-DECLINED; DECLINED must either be reopened or violate the constraint. **ACCEPT** — reopening is the only viable implementation.

2. **Token refresh passes igUsername=null** (MetaTokenStorage refresh-path callers): No username re-resolution at refresh time. id-only match suffices since the creator was already linked at initial connect. **ACCEPT** — contract didn't require username re-resolution on every refresh, and ig_account_id is the stable key.

3. **Admin email CTA links to list page** (AdminCreatorConnectionService.java:sendAdminNotificationEmail — admin_url): Links to `/admin/creator-connections` (the console list) not a deep-link to `/admin/creator-connections/{id}`. Contract specified `admin_url` but didn't define the target. **ACCEPT** — list page is a valid admin landing, detail deep-link is a nice-to-have.

---

## ANANYA P2 FIX VERIFIED ✅
CreatorConnectionsPage.tsx:216 now has `maxLength={1000}` on the admin notes Textarea (was missing in FE review, fixed between reviews).

---

## VERDICT

**PASS** — no findings. Contract parity verified field-by-field against both TASKS.md and Ananya's TS types. Security enforced (brand workspace scoping, admin role guards, audit logging, sanitization, email validation). Honesty verified (503 when Meta unavailable, no mocks, no coercion). JOINED hook is transactional, matches correctly, flips requests, publishes one event per request, listener is AFTER_COMMIT and idempotent. All 3 email templates present. Migration matches MySQL 8.0 dialect. Tests pass 20/20. The 3 deviations are all acceptable.

Ready for Meera's build + manual verification.
