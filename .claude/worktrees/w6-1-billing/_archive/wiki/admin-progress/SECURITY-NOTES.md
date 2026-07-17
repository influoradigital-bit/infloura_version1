# Admin Portal — Security Notes (Red-Team Running Checklist)

> Owner: Kabir (Red-Team). Read-only adversarial review, Cycle 1 — 2026-07-09.
> Scope reviewed: `src/admin/services/api-contracts.ts`, `src/admin/types/admin.types.ts`,
> `influora-api/.../config/SecurityConfig.java`. Nothing else in the admin panel exists yet
> (per `wiki/admin-progress/PROGRESS.md` — no `AdminAuthController.java`, no `useAdminAuth.ts`,
> no migrations). This is a design-level review, not a pentest — there's no running code to
> attack yet. **Update convention:** append/check off items as they're addressed; don't delete
> history — mark `[x] RESOLVED — <how>` instead of removing a line.

---

## P0 — Must resolve before `AdminAuthController.java` / route wiring ships

- [x] RESOLVED (Vikram, cycle 2) — see full note appended after item 2 below; summary: added
  `AdminContextService#requireRole`/`#requireRoleWithMfaSatisfied` (manual allow-list, mirrors
  `BrandContextService#requireRole`'s existing precedent) and wired it into both
  `AdminDashboardController` endpoints. Role→permission matrix for future mutating endpoints is
  now documented in `AdminContextService`'s class javadoc; RBAC test cases still needed from Kavya.
- [ ] **No role-based access control enforcement exists anywhere in `influora-api`.**
  `grep -r "hasRole\|hasAuthority\|@PreAuthorize"` across the entire `com.influora` package
  returns **zero matches**. `AdminRole` (`SUPER_ADMIN` / `ADMIN` / `SUPPORT`,
  `admin.types.ts:11-15`) is defined as a TypeScript enum only — there is no server-side
  mechanism anywhere that maps a role to a permission. `SecurityConfig.java`'s
  `authorizeHttpRequests` block only distinguishes `permitAll()` vs. `anyRequest().authenticated()`
  — it never checks *which* authenticated principal is calling. Concretely, once
  `AdminAuthController` exists, a `SUPPORT`-tier admin JWT will pass the exact same filter-chain
  check as a `SUPER_ADMIN` JWT for every single mutating endpoint, including:
  - `brandApi.overrideBudget` → `POST /brands/:id/campaigns/:campaignId/budget-override`
  - `escrowApi.release` / `escrowApi.refund` → `POST /escrow/:id/release`, `/escrow/:id/refund`
  - `financeApi.retryPayout` / `resolveReconciliation`
  - `brandApi.suspend` / `creatorApi.suspend` / `moderationApi.reviewAppeal`
  - `creatorApi.adjustTier`

  **Action for Vikram:** `AdminAuthController.java` and every subsequent `Admin*Controller`
  must enforce role checks per-endpoint (method-level `@PreAuthorize("hasRole('SUPER_ADMIN')")`
  or equivalent), not just "is this a valid admin session." Define the role→permission matrix
  explicitly (suggest: SUPPORT = read + ticket/moderation actions only; ADMIN = + brand/creator
  KYC/tier/suspend; SUPER_ADMIN = + escrow release/refund, budget override, payout retry,
  reconciliation write-off). This matrix does not exist in any doc yet — someone (Priya?) needs
  to author it before Kavya can write RBAC test cases.

- [x] RESOLVED (Vikram, cycle 2) — `SecurityConfig.java`'s `authorizeHttpRequests` now has
  `.requestMatchers("/admin/**").hasAuthority("ROLE_ADMIN")` (placed after the two explicit
  `permitAll()` matchers for `/admin/auth/login` and `/admin/auth/refresh`, before the
  `anyRequest().authenticated()` catch-all). This confirms and enforces the answer to this item's
  own question: `JwtAuthenticationFilter` sets `AuthPrincipal`'s authority to `"ROLE_" +
  userType.name()` from the JWT's `userType` claim, so a brand/creator JWT (`ROLE_BRAND`/
  `ROLE_CREATOR`) is now rejected 403 at the filter-chain layer for any `/admin/**` path, not just
  by the service-layer `AdminContextService` check (which still runs too, as defense-in-depth).
  Path-mismatch note (`/api/admin` vs `/api/v1/admin`) is unchanged/still open — separate issue,
  tracked as its own task item, not a security gap by itself.
- [ ] **`SecurityConfig.java` has no `/api/admin/**` (or whatever the real admin path turns out
  to be) route segmentation at all.** Every admin route will fall through to the generic
  `anyRequest().authenticated()` branch alongside brand/creator routes. That means authorization
  for "is this even an admin, as opposed to a logged-in brand/creator user" depends entirely on
  `JwtAuthenticationFilter` distinguishing admin-issued JWTs from brand/creator JWTs (e.g. via an
  `aud`/role claim) — I did not find that logic in the files reviewed this cycle (out of scope
  files). **Action:** confirm `JwtAuthenticationFilter` rejects brand/creator tokens on admin
  routes before assuming `anyRequest().authenticated()` is sufficient. If it doesn't, a normal
  brand or creator user with a valid session token could hit admin endpoints once they're wired
  up. Also note: `application.yml` sets `server.servlet.context-path: /api/v1`, but
  `api-contracts.ts:52` hardcodes `API_BASE = '/api/admin'` — path mismatch to reconcile with
  Vikram before routes are wired, otherwise the "what does `anyRequest()` even match" question
  can't be answered precisely.

- [ ] **Refresh-token contract contradicts the HttpOnly-cookie design already documented in
  `SecurityConfig.java`.** The comment at `SecurityConfig.java:48-51` states the refresh token is
  "HttpOnly + SameSite=Strict and path-scoped to /auth" — i.e., never readable by JS. But
  `api-contracts.ts:92-96` defines `refreshToken: (refreshToken: string) => apiRequest(...
  body: JSON.stringify({ refreshToken }))` — a client-callable function that takes the refresh
  token as a JS string argument and puts it in a request body. If `AdminAuthController` is built
  to literally satisfy this contract, the refresh token must live somewhere JS-accessible
  (localStorage, most likely, matching the `admin_token` pattern already in this same file at
  line 58) — which defeats the HttpOnly cookie mitigation entirely and reintroduces "stolen
  refresh token via XSS = permanent admin session" as a live risk. **Action:** pick one design
  and make the contract match it — either (a) refresh reads the HttpOnly cookie server-side and
  this client function takes no token argument (preferred, matches the SecurityConfig comment),
  or (b) the SecurityConfig comment is stale and needs updating to reflect that refresh tokens
  are intentionally client-held (not recommended for an admin panel with escrow/refund powers).

- [ ] **MFA is optional, not enforced, even for `SUPER_ADMIN`.** `AdminUser.mfaEnabled` is a
  boolean the user can apparently leave `false` (`admin.types.ts:86`), and
  `AdminLoginRequest.mfaCode` is optional (`admin.types.ts:94`). There is no field anywhere
  indicating MFA is *mandatory* by role. Given this panel can release/refund escrow funds and
  override campaign budgets, a compromised `SUPER_ADMIN` password alone (no second factor) is a
  direct path to fund movement. **Action:** enforce MFA as mandatory at login time (reject login
  or force MFA-enrollment redirect) for at minimum `SUPER_ADMIN` and `ADMIN` roles, in
  `AdminAuthController`, not left as a user preference.

## P1 — Should resolve before Wave-1 sign-off, not necessarily before first commit

- [ ] **`admin_token` stored in `localStorage`** (`api-contracts.ts:58`,
  `localStorage.getItem('admin_token')`). This is a known, already-flagged risk — the
  `SecurityConfig.java:28-35` comment explicitly references "Kabir audit B4" and "the A1
  localStorage-token XSS risk," and mitigates it with a restrictive CSP (`default-src 'none'`).
  Re-flagging here because: (1) that CSP is served on **API** responses per the same comment —
  "the primary CSP belongs on the SPA's own host" — so the actual admin SPA (once
  `AdminLayout.tsx` / the Vite app serving it exists) needs its own equally strict CSP; confirm
  that lands as part of the frontend build, don't assume the API-side CSP covers it. (2) Given
  the refresh-token contract issue above likely means the refresh token *also* ends up in
  localStorage, a single XSS becomes "steal both access and refresh token, indefinite admin
  session" rather than "steal a short-lived access token." Tightening MFA (P0 above) and the SPA
  CSP both reduce blast radius here.

- [ ] **No maker-checker / dual-approval on high-value single-admin actions.** `overrideBudget`,
  `escrow.release`, `escrow.refund`, `finance.retryPayout`, `resolveReconciliation` are all
  single-call, single-admin, free-text-`reason`-only mutations (`api-contracts.ts:180-184,
  326-342, 295-296, 301-305`). No second-approver field exists anywhere in
  `ApprovalWorkflow`/`AuditLogEntry`. Not a blocker for Cycle 1, but recommend a
  `requiresSecondApproval` flag on the highest-blast-radius actions (especially `escrow.refund`
  and `overrideBudget`, which move real money) before this ships to production — first-pass
  minimum is that these must always land in `AuditLogEntry` with `adminId` + `ipAddress`, but
  that's detection, not prevention.

- [ ] **Audit logging is not visibly enforced server-side yet.** `AuditLogEntry` (types.ts
  444-456) and `auditApi` (api-contracts.ts 443-459) exist as read paths, but there's no
  `auditLogger.ts` util yet (still `NOT_STARTED` per `PROGRESS.md`) and no evidence any
  mutating endpoint is *required* to write an audit row — that has to be enforced at the
  controller/service layer, not left to each endpoint author's discretion. Recommend a shared
  interceptor/aspect in `influora-api` that writes to the audit table for every mutating
  `/api/admin/**` request, rather than relying on each of the ~15 mutating admin endpoints
  remembering to call it individually.

- [ ] **Rate limiting scope for admin login unconfirmed.** `AuthRateLimitFilter` exists and
  applies before `UsernamePasswordAuthenticationFilter` (`SecurityConfig.java:156`), and the
  comment at line 121 shows there's already a distinct "tracking" bucket for webhook/click
  endpoints — so bucket-per-route-class is an established pattern here. Recommend a dedicated,
  stricter bucket for admin login specifically (lower attempt threshold + account lockout after N
  failures), given the blast radius of a brute-forced admin account is materially higher than a
  brand/creator account. Not visible in scope reviewed whether this distinction exists yet.

## Notes / non-issues (recorded so they aren't re-litigated next cycle)

- CSRF is correctly disabled given the stated architecture (Bearer header auth, no ambient
  session cookie except the path-scoped HttpOnly refresh cookie) — **contingent on** the
  refresh-token contract issue above being resolved in favor of the cookie-only design. If it's
  resolved the other way (JS-held refresh token), CSRF protection needs to be revisited too.
- Webhook `permitAll()` entries (Razorpay/Shopify/WooCommerce/redemption/conversion) are
  correctly justified by HMAC signature verification inside the controllers per the inline
  comments — not an admin-panel concern, no action needed here.
- `IDOR`-shaped endpoints (`/brands/:id`, `/creators/:id`, etc. with no visible tenant scoping)
  are expected/correct for an admin panel where admins legitimately see all records — the
  control that matters is making sure only genuine admin-role principals reach these routes at
  all (see P0 role-segmentation item above), not per-record scoping.

---

## Revisit triggers

Re-review this file once any of the following land: `AdminAuthController.java`,
`useAdminAuth.ts`, `V34__admin_tables.sql` (role/permission schema), or `JwtAuthenticationFilter`
changes. At that point this becomes a live-code review, not a design review.

---

# Cycle 2 — 2026-07-09

> Live-code review (trigger hit: `V34__admin_tables.sql`, `AdminAuthController.java`,
> `AdminContextService.java` all now exist). Scope this cycle: sensitive-data-at-rest in
> `influora-api/src/main/resources/db/migration/V34__admin_tables.sql` (all 6 admin tables) plus a
> re-check of `SecurityConfig.java`. Ran alongside Vikram's in-progress `@PreAuthorize` RBAC work
> and Priya's path-mismatch fix this same cycle — neither was visible in the files read; this
> section only covers what's on disk as of this review.

## P0

- [ ] **`admin_users.mfa_secret` is stored in plaintext.** `V34__admin_tables.sql:20`
  (`mfa_secret VARCHAR(255)`) has no encryption marker, and confirmed in code:
  `AdminUser.java:120-124` (`stageMfaSecret(String secret)`) assigns the raw TOTP secret straight
  to the field, called from `AdminAuthService.java:165-168` with
  `totpService.generateSecret()`'s output — no encrypt step anywhere in that path. This directly
  contradicts an established pattern already in this same codebase:
  `influora-api/src/main/java/com/influora/integration/meta/oauth/MetaTokenStorage.java` encrypts
  OAuth access tokens with AES-256-GCM before persisting (`encryptedAccessToken`), under a comment
  literally tagged `[SEC: Kabir sign-off gate]` stating "No code path ... may write a plaintext
  access token to the database." A TOTP seed is at least as sensitive as an OAuth token — anyone
  with read access to a DB dump/backup/replica can derive valid 6-digit codes for every
  `SUPER_ADMIN`/`ADMIN` account indefinitely, silently defeating the mandatory-MFA gate
  Vikram just landed in `AdminContextService.requireAdminIdWithMfaSatisfied` (cycle 1 P0 item 4 —
  the enforcement is real, but the credential it depends on is unprotected at rest). **Action:**
  encrypt `mfa_secret` at rest using the same AES-256-GCM converter pattern as
  `MetaTokenStorage`, with its own dedicated key in `application.yml` (do not reuse
  `influora.meta.token-encryption-key`) — before any MFA setup/verify flow runs against a shared
  (staging/prod) DB. `password_hash` (`admin_users.password_hash`, BCrypt strength 12 per
  `SecurityConfig.java:179`, consistent with brand/creator) is fine — not raised here.

- [ ] **`admin_audit_log` has no writer yet — flagging before one is built, not after.**
  Grepped `old_value|new_value|AdminAuditLog` across `influora-api/src/main/java`: zero hits
  outside the migration itself. Matches `PROGRESS.md`'s blocker #3 (`POST /api/admin/audit` not
  wired). The schema (`V34__admin_tables.sql:48-63`) is otherwise well-shaped for forensics —
  `admin_id` + `admin_email` (denormalized, survives the actor being deactivated later),
  `action`, `entity_type`/`entity_id`, `ip_address VARCHAR(45)` (IPv6-safe), `created_at`, and
  useful indexes (`idx_admin_audit_admin_time`, `idx_admin_audit_entity`) — no complaint about the
  table shape. The risk is in `old_value JSON` / `new_value JSON` being unconstrained: whoever
  writes the eventual `AdminAuditLogService` must NOT serialize a full entity diff naively (e.g.
  `objectMapper.valueToTree(entity)`), or the very first time an admin edits an `AdminUser` row
  (role change, MFA reset) or a brand/creator record with payment/PII fields, this table becomes a
  second at-rest exposure surface — one that undermines the encryption fix above by re-exposing
  `mfa_secret`/`password_hash`/`token_hash` values in plaintext JSON the moment they're touched by
  an admin action. **Action:** whoever builds the audit writer (Vikram/Priya per `PROGRESS.md`
  Next Up #4) must use an explicit field allow-list per entity type, not blanket serialization.
  Flag this in the PR description for that endpoint so Kavya's QA checklist includes it.

## P1 / confirmed-good (recorded so they aren't re-litigated)

- **`admin_refresh_tokens.token_hash` — confirmed hashed, not raw.** `V34__admin_tables.sql:30`
  (`token_hash VARCHAR(64)`) + `AdminAuthService.java:110,141,207` (`JwtService.hashToken(...)`,
  unsalted SHA-256 — `JwtService.java:61-69`) — same helper used for brand/creator refresh tokens
  and password-reset tokens (`AuthService.java`), so this is the established, accepted pattern
  repo-wide (no salt needed given these are high-entropy random tokens, not low-entropy secrets —
  brute-forcing the hash requires brute-forcing the token itself). No action.
  - Minor hygiene gap noted in passing: no index on `expires_at`/`revoked`, and grepped
    `AdminRefreshTokenRepository.java` for a cleanup query/`@Scheduled` sweep — none exists. Not
    urgent (mirrors whatever the brand/creator `RefreshToken` table already does or doesn't do),
    but table will grow unbounded under normal admin login/refresh volume; a future cleanup job
    will full-scan without an index. Suggest Meera add `INDEX idx_admin_refresh_expires
    (expires_at)` in a follow-up migration whenever a TTL sweep job is built, not blocking.

- [ ] **`support_ticket_messages.content` / `content_flags.content_preview` are unbounded `TEXT`
  with no length constraint** (`V34__admin_tables.sql:93,107`). Unlike the admin-only tables,
  `support_tickets`/`support_ticket_messages` are reachable by brand/creator users
  (`support_tickets.user_type ENUM('BRAND','CREATOR')`) — a compromised or malicious brand/creator
  account could submit oversized or high-volume messages as a cheap storage-exhaustion vector
  against a table with no per-row cap. Recommend an app-layer length cap (e.g. 10-20k chars) when
  `AdminSupportController`/the ticket-creation endpoint is built (still `NOT_STARTED` per
  `PROGRESS.md`), enforced before insert, not just at the DB layer.

- [ ] **`support_ticket_messages.sender_id` has no FK to either `users` or `admin_users`.** This
  is defensible — it's polymorphic on `sender_type ENUM('USER','ADMIN')`, same shape as
  `content_flags.content_id` — but unlike `content_flags`, which has an inline comment explaining
  why it's intentionally unconstrained, `sender_id` has none. Flagging so it isn't mistaken for an
  oversight later, and because it means there's no DB-level guarantee a given `sender_id` actually
  resolves to a real principal of the claimed type — app layer (whatever service inserts ticket
  messages) must validate this itself, or a bug could let a message render as sent by an admin/user
  that never sent it.

## SecurityConfig.java re-check (per this cycle's ask — reporting current state, not assuming)

As of this review, `/admin/**` route segmentation is **partially** in place, an improvement over
Cycle 1 ("no `/api/admin/**` segmentation at all"):

- `SecurityConfig.java:93-96` now explicitly `permitAll()`s only `POST /admin/auth/login` and
  `POST /admin/auth/refresh` — everything else under `/admin/**` falls through to the generic
  `anyRequest().authenticated()` at line 166-167, same as brand/creator routes.
- The actual "is this really an admin, not just any logged-in user" gate is
  `AdminContextService.requireAdminId(AuthPrincipal)` (`AdminContextService.java:48-53`), checking
  `principal.getUserType() == UserType.ADMIN` — admin JWTs are minted with that `UserType` by
  `AdminAuthService#issueTokens`, reusing the existing `JwtService`/`JwtAuthenticationFilter`
  rather than a parallel pipeline. This resolves Cycle 1's P0 item 2 concern (a valid brand/creator
  JWT cannot pass this check) **provided every `Admin*Controller` actually calls it**. Confirmed
  `AdminDashboardController` does (per Vikram's SHARED_CONTEXT.md note). This is a
  per-controller-author-discipline check, not a framework-enforced one (e.g. not a
  `SecurityConfig`-level `requestMatchers("/admin/**").hasAuthority(...)` or a class-level
  `@PreAuthorize`) — a future `Admin*Controller` that forgets to call `requireAdminId` would fall
  through to bare `anyRequest().authenticated()` and be reachable by any logged-in brand/creator.
  Not flagging as a new P0 (Vikram's javadoc in `AdminContextService.java:32-36` already documents
  this obligation explicitly for every future controller), but worth Kavya adding "calls
  `AdminContextService.requireAdminId` or `requireAdminIdWithMfaSatisfied`" as a standing QA
  checklist item for every new `Admin*Controller` PR, rather than relying on this note being
  remembered.
- **Role→permission matrix (`@PreAuthorize`/`hasRole`/`hasAuthority`) still does not exist
  anywhere** — grepped `com.influora` package-wide, 0 hits outside this filter-chain scaffolding.
  Matches `PROGRESS.md` task #11 (pending, assigned Vikram/Priya this cycle) and
  `AdminContextService.java:34-36`'s own admission that per-action role checks are "NOT satisfied
  by this class alone." Cycle 1 P0 item 1 remains open — re-review once `@PreAuthorize` lands.
- Mandatory MFA for `SUPER_ADMIN`/`ADMIN` (Cycle 1 P0 item 4) is now enforced server-side via
  `AdminContextService.requireAdminIdWithMfaSatisfied` — **resolved**, contingent on the
  plaintext-`mfa_secret` P0 above being fixed (enforcement is only as strong as the secret it
  checks).

---

## Revisit triggers (Cycle 2)

Re-review once: `@PreAuthorize`/role-matrix lands on `Admin*Controller`s, an `AdminAuditLogService`
writer is built, `mfa_secret` encryption lands, or `AdminBrandController`/`AdminCreatorController`/
`ApprovalWorkflowController` (money-moving endpoints) are added.

---

# Cycle 4 — 2026-07-09

> Prep + regression sweep, run in parallel with Vikram building `AdminBrandController.java`
> (KYC-verify/suspend/reinstate — first real writer to `admin_audit_log`). His code was not visible
> this cycle; this section covers (1) tightening `AUDIT-LOG-WRITE-SPEC.md` before he builds against
> it, (2) a fresh repo-wide sweep for RBAC regressions across all `Admin*Controller`s, (3) a
> `SecurityConfig.java` path-matcher spot-check.

## 1. Audit-log spec tightened: IP capture mechanism does not exist in a trustworthy form

Added **Rule 1a** to `AUDIT-LOG-WRITE-SPEC.md` (between Rule 1 and Rule 2). Summary: the only
IP-extraction code anywhere in `influora-api` is `AuthRateLimitFilter#clientIp`
(`influora-api/src/main/java/com/influora/security/AuthRateLimitFilter.java:240-247`), which trusts
a caller-supplied `X-Forwarded-For` header unconditionally — no trusted-proxy allowlist, no
`server.forward-headers-strategy`/`ForwardedHeaderFilter` configured anywhere (confirmed by grep:
zero hits for `forward-headers|trusted-prox|ForwardedHeaderFilter` in `influora-api`, and no infra
doc in `wiki/` describes the proxy/LB topology). That filter's own javadoc already flags this as a
known, tracked limitation for rate-limit bucket keying (`task_568d968e`) — acceptable there because
the worst case is a rate-limit bypass.

For `admin_audit_log.ip_address` the same header-trusting logic is a materially worse problem: any
admin (including one whose session is compromised, or acting maliciously) can set
`X-Forwarded-For` on their own request and have the forensic audit trail record a false IP for
exactly the actions — KYC reject, suspend, reinstate — this table exists to make accountable. A
spoofed forensic record is worse than an absent one.

**Guidance now in the spec for whoever builds `AdminAuditLogService`:** do not reuse
`AuthRateLimitFilter#clientIp` verbatim; default `ip_address` to `HttpServletRequest#getRemoteAddr()`
only (not client-spoofable — degrades to "the LB's IP" behind a reverse proxy, which is a visibility
gap, not a spoofing hole) until infra documents an actual trusted-proxy topology and wires a
`ForwardedHeaderFilter`/equivalent. Not a P0 blocker for Vikram's `AdminBrandController` this cycle —
`getRemoteAddr()` is still strictly better than trusting an attacker-controlled header — but the
writer must not silently copy the rate-limiter's pattern assuming it's already safe. Full text: see
`AUDIT-LOG-WRITE-SPEC.md` Rule 1a.

## 2. Repo-wide RBAC regression sweep — no new admin endpoint found, existing two are clean

Grepped `influora-api/src/main/java/com/influora/web` for all `Admin*Controller`s — still only two
exist on disk: `AdminAuthController.java`, `AdminDashboardController.java`
(`AdminBrandController.java` is Vikram's in-flight parallel work this cycle, not yet on disk — grep
confirms zero hits for `AdminBrandController|admin_audit_log|AdminAuditLog` anywhere under
`influora-api/src/main/java`, expected, not a regression).

Traced every endpoint in both existing controllers back to its service-layer gate
(`AdminContextService.requireAdminId` / `requireRoleWithMfaSatisfied`):

- `AdminAuthController`: `login`/`refresh` are pre-auth by design (permitAll at the filter-chain
  layer). `logout`/`me`/`mfa/setup`/`mfa/verify` correctly call only `adminContext.requireAdminId`
  (not `requireRole`) via `AdminAuthService.java:162,168,174,190` — this is the documented
  self-service exception in `AdminContextService.java:49-52`'s class javadoc (a not-yet-MFA-enrolled
  admin must still be able to reach `mfa/setup`), not a missed role check. No regression.
- `AdminDashboardController`: both `pulse()` and `operations()` call
  `adminContext.requireRoleWithMfaSatisfied(principal, SUPER_ADMIN, ADMIN, SUPPORT)`
  (`AdminDashboardService.java:83,115`) — explicit role allow-list plus MFA gate, matches Cycle 2's
  fix. No regression.

No controller found missing a `requireRole`/`requireRoleWithMfaSatisfied`/`requireAdminId` call this
cycle. (Re-run this sweep next cycle once `AdminBrandController` lands — its KYC-verify/suspend/
reinstate endpoints are exactly the "destructive action on a brand account" shape Cycle 1's suggested
matrix assigns to `ADMIN`+ tier, i.e. expect `requireRoleWithMfaSatisfied(principal, SUPER_ADMIN,
ADMIN)`, SUPPORT excluded — flag if SUPPORT is included or if any endpoint calls bare
`requireAdminId` instead.)

## 3. `SecurityConfig.java` `/admin/**` matcher — confirmed it covers Vikram's future `AdminBrandController` path

`SecurityConfig.java:120-121`: `.requestMatchers("/admin/**").hasAuthority("ROLE_ADMIN")`. Both
existing controllers mount under this prefix (`AdminAuthController` at `/admin/auth`,
`AdminDashboardController` at `/admin/dashboard`, both per their `@RequestMapping` + class javadoc
confirming the `/api/v1` context-path convention, no `/v1` repeated in the annotation). The matcher
is a path-prefix wildcard (`/admin/**`), not an enumerated list, so any future `Admin*Controller`
mounted under `/admin/...` — including a `/admin/brands` (or similar) `AdminBrandController` — is
automatically covered by the same filter-chain gate with no config change needed on Vikram's part.
Nothing to fix here; flagging as confirmed-good so it isn't re-litigated. (The one caveat, unchanged
from Cycle 1/2: this only proves "is this an admin at all" at the filter-chain layer — the
role-tier check for KYC/suspend/reinstate still has to be added explicitly in the new controller's
service layer per item 2 above; the path matcher does not substitute for that.)

## Revisit triggers (Cycle 4)

Re-review once `AdminBrandController.java` (and `AdminAuditLogService`) actually land: confirm (a)
KYC-verify/suspend/reinstate call `requireRoleWithMfaSatisfied` with an `ADMIN`+ allow-list, not bare
`requireAdminId`; (b) the audit writer follows Rule 1a (no `X-Forwarded-For` trust) and Rules 1-5
generally; (c) no raw entity/PII fields leak into `old_value`/`new_value` for the `BRAND` entity type
specifically (`kyc_status`, `is_active` are the allow-listed fields per the spec's table — watch for
scope creep to full-entity dumps under time pressure).

---

# Cycle 5 — 2026-07-09

> Live-code adversarial review of `AdminBrandController.java`/`AdminBrandService.java`/
> `AdminAuditLogService.java`/`V36__workspace_suspension_kyc_audit.sql` now that Vikram's cycle-4
> work has actually landed (not in-flight this time). Re-reviewed as real shipped code, cross-checked
> against `AUDIT-LOG-WRITE-SPEC.md` and Cycle 4's three revisit triggers (a)/(b)/(c) above.

## P1 — should fix, tracked as real findings

- [ ] **Trigger (b) FAILS: `AdminAuditLogService#clientIp` (lines 252-259) reuses the exact
  `X-Forwarded-For`-trusting pattern Rule 1a explicitly told the writer not to copy.** The spec
  (added last cycle, specifically to prep Vikram before he built this) says in so many words: "Do
  NOT import/reuse `AuthRateLimitFilter#clientIp` verbatim for the audit writer... Default to
  `HttpServletRequest#getRemoteAddr()` only." What shipped instead:
  ```java
  private static String clientIp(HttpServletRequest request) {
      String forwarded = request.getHeader("X-Forwarded-For");
      if (forwarded != null && !forwarded.isBlank()) {
          int comma = forwarded.indexOf(',');
          return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
      }
      return request.getRemoteAddr();
  }
  ```
  — functionally identical to the forbidden pattern, and the class's own javadoc (line 251)
  self-describes it as "Same X-Forwarded-For-first, remoteAddr-fallback pattern as
  `AuthRateLimitFilter`," i.e. this was a deliberate reuse, not an oversight that missed the spec.
  No trusted-proxy allowlist or `ForwardedHeaderFilter` exists anywhere in the repo (unchanged from
  cycle 4's check). **Impact:** any admin — including one whose session/credentials are compromised,
  or a malicious insider — can set `X-Forwarded-For` on their own request and have
  `admin_audit_log.ip_address` record whatever IP they choose, for exactly the actions (KYC reject,
  suspend, reinstate) where "was this really done from the admin's usual location" is the first
  question an incident responder asks. This defeats the specific control Rule 1a exists to protect,
  on the first real writer built against the spec. **Action:** change `clientIp()` to
  `request.getRemoteAddr()` only (drop the `X-Forwarded-For` branch entirely) until infra documents
  a trusted-proxy topology, per Rule 1a items 2-3. Small, contained fix — one method body.

- [ ] **DTO/column length mismatch on KYC-reject reason: validation says 2000, storage allows 1000.**
  `VerifyKycRequest.reason` is `@NotBlank @Size(max = 2000)` (`AdminBrandDtos.java:80`), but
  `Workspace.kycRejectionReason` is `@Column(length = 1000)` (`Workspace.java:97`), matching
  `kyc_rejection_reason VARCHAR(1000)` in `V36__workspace_suspension_kyc_audit.sql:27`. A REJECT
  action with a reason 1001-2000 chars long passes bean validation at the controller boundary, then
  hits a DB-level data-truncation error when `workspaceRepository.save(workspace)` runs
  (`AdminBrandService.java:143`) — inside the same `@Transactional` method as the audit-log write
  that follows it (line 145), so the whole transaction rolls back: no KYC state change, no audit
  row, just a raw 500 instead of a clean 400. (`SuspendRequest`/`ReinstateRequest` don't have this
  problem — both are correctly `@Size(max = 500)`, matching `suspended_reason VARCHAR(500)`.)
  **Action:** tighten `VerifyKycRequest.reason` to `@Size(max = 1000)` to match the column (simplest
  fix, no migration needed), or widen the column to 2000 if the longer reason length is actually
  wanted for rejections specifically — pick one, but they must agree.

## P2 — hardening, not urgent

- [ ] **`verifyKyc` has no state-transition guard, unlike `suspend`/`reinstate`.** Both `suspend()`
  and `reinstate()` correctly reject no-op transitions before mutating (`ALREADY_SUSPENDED` /
  `NOT_SUSPENDED`, 409 CONFLICT — `AdminBrandService.java:166-169, 195-197`), but `verifyKyc` has no
  equivalent: re-APPROVEing an already-VERIFIED brand or re-REJECTing an already-REJECTED brand
  silently succeeds, overwrites `kycReviewedAt`/`kycReviewedBy`, and writes a same-to-same audit row
  every time. Not independently exploitable (already requires `SUPER_ADMIN`/`ADMIN`), but it's an
  inconsistency against the pattern the other two mutating methods just established, and no-op
  audit rows make the trail noisier when trying to spot a genuine re-review. Recommend either a
  symmetric guard or an explicit one-line comment that KYC re-review is intentionally idempotent (so
  it isn't mistaken for an oversight next cycle).

## Confirmed-good — closes Cycle 4's three revisit triggers (a)/(c), and this cycle's own checks

- **Trigger (a) PASSES — role check happens before the mutation, no TOCTOU.** All three mutating
  methods call `adminContext.requireRoleWithMfaSatisfied(principal, SUPER_ADMIN, ADMIN)` as the
  *first* statement (`AdminBrandService.java:123-126` verifyKyc, `161-164` suspend, `190-193`
  reinstate) — before `requireBrandWorkspace` even runs, let alone any mutation. `SUPPORT` is
  correctly excluded from all three (destructive/compliance-tier, matches the predicted matrix);
  `getById` correctly includes `SUPPORT` (read-only). Side benefit: because the role gate runs
  first, a `SUPPORT`-tier caller gets 403 before ever learning whether a given `brandId` exists —
  no existence-leak to under-privileged callers via a suspend/reinstate/verify-kyc attempt.
- **Trigger (c) PASSES — no scope creep in the audit snapshots.** `FIELD_ALLOWLIST` for `BRAND`
  (`id`, `name`, `verificationStatus`, `isSuspended`, `suspendedReason`, `kycRejectionReason`,
  `AdminAuditLogService.java:108-115`) matches exactly what `AdminBrandService` actually sends in its
  `oldValueAllowed`/`newValueAllowed` maps for all three mutations — no raw entity dump, no PII/secret
  field anywhere near `old_value`/`new_value`.
- **IDOR: uniform 404 across all 4 endpoints, no differential leak.** `requireBrandWorkspace`
  (`AdminBrandService.java:215-221`) returns the same `BRAND_NOT_FOUND` for "row doesn't exist" and
  "row exists but isn't a BRAND-type workspace," used identically by `getById`/`verifyKyc`/`suspend`/
  `reinstate` — no content/timing difference to fingerprint valid vs. invalid IDs. Per Cycle 1's
  standing note, broad admin visibility across brand IDs is expected/correct for this surface; the
  control that matters is the role gate above, which passes.
- **`reason` IS validated server-side, not just the cycle-2 TS-required field.** All three request
  records use `@NotBlank` + `@Size` (`AdminBrandDtos.java:80,82,84`) — genuinely enforced via
  `@Valid` on the controller methods, not merely a client-side TypeScript contract. (Mod the one
  length-vs-column mismatch flagged above — the validation exists, it's just calibrated wrong for
  one of the three.)
- **Rule 1 (server-derived identity) still holds.** `admin_id`/`admin_email` are re-resolved fresh
  from `admin_users` by id at write time (`AdminAuditLogService.java:189-196`), never trusting
  `principal`'s JWT claims — unchanged from cycle 4's design, confirmed still true in the shipped
  code.
- **Audit write ordering matches the documented intent.** `workspaceRepository.save(workspace)`
  precedes `adminAuditLogService.record(...)` in program order in all three mutating methods, and
  `record()` never throws (Rule 5) — an audit-write failure can't roll back or block the underlying
  action. (Both statements are in the same `@Transactional` boundary and commit atomically regardless
  of Hibernate's internal flush timing, so this is about code-intent/readability, not an exploitable
  ordering gap.)

## Escalation assessment

Nothing this cycle rises to "notify Swapnil immediately." The `X-Forwarded-For` spoofable-IP finding
is the most significant item — it's a direct, self-documented violation of a spec written specifically
to prevent it, on the very code that spec was gating — but its impact is confined to forensic-record
integrity for already-authorized admin actions, not privilege escalation, data exposure, or fund
movement. Routing to Vikram/Kavya as a same-cycle fix (one-method-body change) rather than an
executive escalation. Will re-flag as P0 if it's still unresolved by the time escrow-release/
budget-override endpoints (money-moving, `SUPER_ADMIN`-tier) land, since forensic accountability
matters more once real fund movement is in scope.

## Revisit triggers (Cycle 5)

Re-review once: (a) `clientIp()` fix lands — confirm `X-Forwarded-For` branch is actually removed,
not just deprioritized; (b) the KYC-reason `@Size` fix lands — confirm the DTO max and the
`kyc_rejection_reason` column width agree; (c) the next destructive admin controller ships (escrow
release/refund, budget override, creator suspend) — re-run this same checklist (TOCTOU ordering,
state-transition guards, reason length parity, audit allow-list scope) against it, since this cycle
shows the pattern isn't automatically inherited correctly even when a spec exists.

---

# Cycle 6 — 2026-07-09

> Verification of Cycle 5's two P1 fixes + repo-wide RBAC regression sweep now that
> `AdminCreatorController.java`/`AdminCreatorService.java` and `ApprovalWorkflowController.java`/
> `ApprovalWorkflowService.java` have landed (per `SHARED_CONTEXT.md` Cycle 6 summary). Run in
> parallel with Vikram building `AdminSupportController.java` (not yet on disk this cycle — see
> `wiki/admin-progress/SUPPORT-TICKET-PII-NOTES.md`, written this same cycle as prep).

## Cycle 5 fixes — both confirmed landed as described

- **`clientIp()` fix confirmed.** `AdminAuditLogService#clientIp` (lines 252-259 per Cycle 5's
  citation) — `X-Forwarded-For` branch removed, `request.getRemoteAddr()` only. Matches Vikram's
  SHARED_CONTEXT.md note and Rule 1a. Trigger (a) closes.
- **KYC-reason length fix confirmed, via a different path than Cycle 5 suggested — and it's the
  better choice.** Vikram widened `Workspace.kycRejectionReason` to VARCHAR(2000) (V39 migration)
  rather than tightening `VerifyKycRequest.reason` down to 1000. Cycle 5 flagged both options as
  equally acceptable ("pick one, but they must agree") — widening avoids silently truncating admin
  input the DTO already allowed, which is the safer direction when the two disagree. Trigger (b)
  closes.

## Repo-wide RBAC regression sweep — no new endpoint found missing a role gate

Grepped `influora-api/src/main/java/com/influora/web` for all `Admin*Controller`s plus
`ApprovalWorkflowController` (the one non-`Admin`-prefixed controller that's admin-surface).
Six controllers exist on disk: `AdminAuthController`, `AdminBrandController`,
`AdminCreatorController`, `AdminDashboardController`, `ApprovalWorkflowController`, and (mounted
under `/admin/**` but out of this sweep's scope — no RBAC-relevant mutation) none else. Confirmed
`AdminSupportController` is still not on disk (zero grep hits for
`AdminSupportController|AdminSupportService` anywhere under `influora-api/src/main/java`) — expected,
Vikram's in-flight work this cycle, not a regression.

Traced every endpoint in the two controllers that landed since Cycle 4/5's sweep back to its
service-layer gate:

- **`AdminCreatorController` -> `AdminCreatorService`** (`AdminCreatorService.java`): `getById`
  (line 137-138) correctly includes `SUPPORT` (read-only, matches the matrix). `reviewApplication`
  (151-152), `forceInstagramReauth` (199), `suspend` (224-225), `reinstate` (253-254) all correctly
  call `requireRoleWithMfaSatisfied(SUPER_ADMIN, ADMIN)` — `SUPPORT` excluded from every mutating
  method, matching `AdminBrandController`'s precedent exactly (same shape: destructive/compliance
  action on a creator account gated ADMIN+, read gated at all three tiers). No bare
  `requireAdminId`/`requireAdminIdWithMfaSatisfied` call anywhere in this service — every method
  goes through the role-tier gate, not just the "is this any admin" gate.
- **`ApprovalWorkflowController` -> `ApprovalWorkflowService`** (`ApprovalWorkflowService.java`):
  both `getPendingApprovals` (line 106) and `processApproval` (line 132) call
  `requireRoleWithMfaSatisfied(SUPER_ADMIN, ADMIN)` — no `SUPPORT` on either endpoint. This is
  slightly stricter than `AdminCreatorService`/`AdminBrandService`'s pattern of allowing `SUPPORT`
  on the read endpoint; not flagging as a defect (a queue of pending KYC/suspend-type approvals
  reasonably stays ADMIN+ even for the read, since it previews the same account-mutation detail the
  approve action itself is gated on — consistent with the "SUPPORT excluded from account-level
  actions" principle, just applied one step earlier here), but noting the asymmetry so it isn't
  mistaken for an oversight later. No missing gate either way.
- **`AdminBrandController`/`AdminDashboardController`/`AdminAuthController`**: unchanged since
  Cycle 4/5's sweep, re-confirmed still correct (no drift) — not re-detailing here, see those
  cycles' entries.

**No controller found missing a `requireRole`/`requireRoleWithMfaSatisfied` call this cycle.**
`AdminContextService`'s class javadoc (updated over cycles 2-5) continues to document the
suggested matrix accurately for every endpoint that now exists.

## Revisit triggers (Cycle 6)

Re-review once `AdminSupportController.java`/`AdminSupportService.java` lands: (a) confirm role
gate matches `SUPPORT-TICKET-PII-NOTES.md` section 1's suggested allow-list (SUPPORT included for
read/reply/assign/status, excluded from any account-level escalation path); (b) confirm ticket
message content never reaches `admin_audit_log.old_value`/`new_value`/`reason` (section 3); (c)
re-run this same RBAC regression sweep against it, plus check for the length-cap on
`support_ticket_messages.content` flagged back in Cycle 2. Also re-run the standing TOCTOU/
state-transition/reason-length-parity checklist against it as a "new destructive-ish controller,"
even though tickets are lower blast-radius than brand/creator suspend.

## Cycle 7 — `PlatformFeeAdminController`/`PlatformFeeAdminService` review (highest blast-radius admin surface to date: platform revenue take-rate)

Reviewed `PlatformFeeAdminController.java`, `PlatformFeeAdminService.java`,
`PlatformFeeConfig.java`, `AdminContextService.java`, `AdminAuditLogService.java`,
`PlatformFeeService.java` (creator-side escrow-release consumer), and
V41__platform_fee_config.sql / V42__platform_fee_config_brand_fee_razorpay.sql. Checked against
this doc's own AUDIT-LOG-WRITE-SPEC.md.

**1. RBAC — confirmed SUPER_ADMIN-only, not ADMIN/SUPPORT.** Every method (`getCurrent`,
`update`, `history`) calls `adminContext.requireRoleWithMfaSatisfied(principal,
AdminRole.SUPER_ADMIN)` with a single-role varargs — no `ADMIN` in the allow-list, unlike the
brand/creator moderation surface which is SUPER_ADMIN+ADMIN. `requireRole`'s implementation
(`AdminContextService.java:110-120`) throws 403 `INSUFFICIENT_ROLE` unless the caller's
freshly-loaded `admin_users.role` exactly matches an entry in `allowedRoles`, so an `ADMIN`- or
`SUPPORT`-tier token is correctly rejected. MFA is also enforced (role loaded via
`requireRoleWithMfaSatisfied`, not the MFA-exempt `requireRole`). No gap here — matches the
report.

**2. Server-side bounds validation — present and effectively capped well below 100%, but the
cap depends on a column this controller can never itself set.** DTO-level
`@DecimalMin("0.00")`/`@DecimalMax("100.00")` on `brandFeePercent`/`creatorFeePercent`
(`PlatformFeeConfigDtos.java:52-53`) only blocks negative values and values over literal 100%.
The real sanity cap is `PlatformFeeAdminService.validateRange` (lines 150-164): reuses the
entity's `minFeeBps`/`maxFeeBps`/`allowHighFee` columns (seeded 0 / 3000 bps / `false` in V41),
so in practice every PUT is capped at 30% per leg unless `allow_high_fee` is `true`. Confirmed
`applyAdminFeeUpdate` (`PlatformFeeConfig.java:134-148`) deliberately does **not** accept or
mutate `allowHighFee`/`minFeeBps`/`maxFeeBps`, and `UpdatePlatformFeeConfigRequest` has no field
for it either — so there is currently no code path through this controller that can ever raise
the cap to 100%. That's a reasonable sanity guard as shipped. Flagging as a **watch item, not a
defect**: if `allow_high_fee` is ever flipped true directly in the DB (e.g. by a future admin
tool or a manual ops fix), the effective cap silently jumps from 30% to 100% with no additional
gate — worth a comment on the `allow_high_fee` column itself warning that it removes the sanity
ceiling entirely, not just "the configured max."

**3. Audit logging — compliant with AUDIT-LOG-WRITE-SPEC.md.** `update` builds a before/after
snapshot via `snapshot()` and calls `AdminAuditLogService.record` with action `UPDATE`, entity
type `PLATFORM_FEE_CONFIG`, and `body.reason()` — matches Rule 1 (admin_id/email/ip all
server-derived, never client-supplied), Rule 2 (field-allowlisted snapshot;
`FIELD_ALLOWLIST.get("PLATFORM_FEE_CONFIG")` in `AdminAuditLogService.java:152-159` matches
`snapshot()`'s keys exactly, no raw entity dump), Rule 3 (`UPDATE`/`PLATFORM_FEE_CONFIG` are both
on the allow-lists), and Rule 4 (reason is `@NotBlank @Size(min=10,max=2000)`-validated at the DTO
layer, on top of the writer's own 2000-char truncation). "Who/when/from-what/to-what/why" are all
answerable: who = `admin_audit_log.admin_id`/`admin_email` (re-resolved server-side, not JWT
claim), when = `created_at`, from/to = `old_value`/`new_value` JSON (brand/creator fee %,
Razorpay-absorption flag), why = `reason`. `history()` correctly re-reads this same trail rather
than a parallel versioned table, and is itself gated SUPER_ADMIN-only inside
`AdminAuditLogService.getByEntity`.

One naming/semantic wrinkle worth noting (not a security hole): `config.approvedBy` is
dual-purposed — the V42-seeded value is a literal approver name ("Swapnil Maruti (CEO)"), but
every subsequent `update()` call overwrites it with whatever free-text `reason` the acting admin
typed (`PlatformFeeAdminService.java:103-107`). The original CEO-approval attribution is
overwritten by the *next* change's justification text and does not survive as a distinct field —
though it's not lost, since `admin_audit_log` retains the correct `approvedBy` value in the
`old_value` snapshot of that update's history row. Cosmetic confusion for anyone reading the live
config row in isolation, not a spec violation.

**4. Race condition — real gap, no defense against concurrent PUTs on the singleton row.**
`PlatformFeeConfigRepository` (`PlatformFeeConfigRepository.java`) is a bare `JpaRepository` with
no `@Lock` query, and `PlatformFeeConfig` has no `@Version` column (confirmed by reading the full
entity — `id`/`defaultFeeBps`/`brandFeeBps`/`minFeeBps`/`maxFeeBps`/`allowHighFee`/
`razorpayAbsorbedByPlatform`/`approvedBy`/`effectiveAt`/`updatedAt`/`updatedBy`, no version
field). `update()` does a plain `findById` read, mutates in memory, `save()`s — classic
lost-update window: two SUPER_ADMINs (or one admin double-submitting, e.g. a slow network retry)
PUT-ing concurrently will interleave read-modify-write with no conflict detection. Concretely:
Admin A GETs (brand=10%, creator=15%), Admin B GETs the same state a moment later, A submits
brand=12% (creator=15%, unchanged in A's form) and commits, B — still holding the pre-A snapshot
— submits creator=18% (brand=10%, stale) and commits: B's write silently reverts A's brand-fee
change back to 10% with no error to either admin, and the audit log shows two clean UPDATE
entries that individually look correct but net out to a state neither admin intended or would
recognize. `@Transactional` on `update()` prevents mid-transaction corruption within one request,
but provides no cross-request isolation for this read-then-write pattern (MySQL/InnoDB default
`READ COMMITTED` won't help either). **Fix: add `@Version private long version` to
`PlatformFeeConfig` and let JPA's `OptimisticLockException` on `save()` surface as a 409
`CONFIG_CHANGED_CONCURRENTLY` (client re-fetches and retries) rather than silently overwriting.**
Given this table has exactly one row and is written rarely (SUPER_ADMIN-only, deliberate
CEO-directive-level changes), optimistic locking is sufficient — no need for pessimistic
`SELECT ... FOR UPDATE`.

**5. Cross-config drift vs. the concurrent creator-side escrow-release work — no drift risk
found, single source of truth confirmed.** Grepped the full `influora-api` tree for
`PlatformFeeConfig`/`brandFeeBps`/`defaultFeeBps` references: exactly six files touch this
entity, and there is only one `platform_fee_config` table/row. `PlatformFeeAdminService.update()`
writes `defaultFeeBps` (the creator fee) directly on the same row `PlatformFeeService
.resolveCreatorFeeBps()` reads at escrow release (`PlatformFeeService.java:39-41`) — so an admin
changing "creator fee %" in the panel takes effect on the very next escrow release with no
separate sync step and no second config to drift out of alignment. This is correctly documented
in both classes' javadoc as deliberate. The one real (non-security) gap: `brandFeeBps` is written
by this controller but is not read anywhere else in the codebase yet — brand-side escrow-funding
does not charge it (explicitly called out as out-of-scope in both the controller and migration
headers). This isn't a drift/inconsistency risk today since nothing else reads it to disagree
with, but it is a **product-trust risk**: a SUPER_ADMIN who sets "brand fee = 12%" via
`FeeControlPanel.tsx` and sees it save successfully has no signal from the API that this value is
currently inert. Worth a `TODO`/response field (e.g. `brandFeeEnforced: false`) or a UI banner
when the brand-charging integration ships, so a config change doesn't read as "live" when it
isn't yet.

### Verdict

RBAC (#1), bounds validation (#2), and audit logging (#3) are solid — no P0/P1 here. #4 (missing
optimistic lock on the singleton row) is a real correctness/security gap: a silent lost-update on
the platform's revenue take-rate config, with no error surfaced to either admin and an audit
trail that doesn't make the collision legible either. Recommend blocking on adding `@Version`
before this ships to prod, given the blast radius (revenue take-rate) and that the fix is small.
#5 confirms no drift between the admin config and the creator-side escrow consumer (same row),
but flags the brand-fee field as silently inert pending the not-yet-built funding integration —
product-messaging fix, not a security one.

## Revisit triggers (Cycle 7)

Re-review when: (a) `@Version` lands on `PlatformFeeConfig` — confirm `update()` handles
`OptimisticLockException`/`ObjectOptimisticLockingFailureException` as a clean 409 rather than a
raw 500; (b) the brand-side escrow-funding integration (`EscrowService` extension,
`PlatformFeeService.FeeScope.BRAND` per the controller's javadoc) ships — re-check this cycle's
item #5 for actual drift risk once `brandFeeBps` has a second reader; (c) if `allow_high_fee` is
ever exposed to an API caller (currently DB-only) — re-verify the 100% cap can't be reached
without a second, explicit confirmation step given it would zero out the counterparty's payout.
