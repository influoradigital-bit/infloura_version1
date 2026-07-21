# Authorization

How Influora decides what an authenticated caller may do. Authorization is layered: **user role** (JWT), **workspace membership role** (service layer), **subscription plan entitlement** (interceptors), and **service-mesh identity** (Python). It is deliberately enforced in the **service layer and interceptors**, not via URL `hasRole()` rules.

---

## 1. User roles

`domain/enums/UserType`: `BRAND`, `CREATOR`, `ADMIN`. The `JwtAuthenticationFilter` maps this to a single Spring authority `ROLE_BRAND` / `ROLE_CREATOR` / `ROLE_ADMIN` on the `AuthPrincipal` (which also carries `workspaceId`).

`domain/enums/AdminRole`: `SUPER_ADMIN`, `ADMIN`, `SUPPORT` — kept out of the JWT and re-read from `admin_users` on each privileged call (defense against stale-role tokens).

Controllers receive `@AuthenticationPrincipal AuthPrincipal`; the service layer branches on `principal.getUserType()` and rejects the wrong type with `WRONG_USER_TYPE` (403).

---

## 2. Workspace membership roles

`domain/enums/MemberRole`: `OWNER`, `ADMIN`, `MANAGER`, `MEMBER`, `VIEWER`. A brand user acts within a workspace via a `WorkspaceMember` row.

Enforcement is in the service layer through `BrandContextService`:

- `requireBrandWorkspace(principal)` — resolves the caller's workspace (else 403).
- `requireRole(actingMember, MemberRole.OWNER, MemberRole.ADMIN)` — gates privileged operations.

Examples (`service/WorkspaceMemberService.java`): inviting/deactivating members and managing invites require OWNER/ADMIN; `OWNER` can only be assigned at signup (a second OWNER invite → `INVALID_ROLE`); deactivating the sole owner is blocked (`CANNOT_REMOVE_SOLE_OWNER`). Money-adjacent operations (`/wallet/topup`, `/wallet/escrow/fund`) require OWNER/ADMIN; contract generation/duplication allows OWNER/ADMIN/MANAGER.

---

## 3. Subscription plan gating

Three cooperating mechanisms under `security/`:

### PlanGateFilter
An `OncePerRequestFilter` (registered after `JwtAuthenticationFilter`). For authenticated **BRAND** principals only, it resolves the workspace's active `Plan` and publishes `RESOLVED_PLAN` / `RESOLVED_WORKSPACE_ID` request attributes. Resolution failure is **swallowed** (attributes left unset) because it runs before MVC dispatch, so throwing would bypass the exception handler. CREATOR/ADMIN/anonymous pass through untouched.

### @RequiresPlan + PlanGateInterceptor
`security/RequiresPlan.java` is a method annotation carrying a `PlanFeature` (`EXPORT`, `CAMPAIGN_TEMPLATES`). `PlanGateInterceptor` **fails closed**: a missing resolved plan → 403 `PLAN_NOT_RESOLVED`; a disabled feature → **402 `UPGRADE_REQUIRED`** (`PAYMENT_REQUIRED`). Registered globally via `config/PlanGateWebConfig`.

Used by: campaign-template save (`POST /campaign-templates`, `CAMPAIGN_TEMPLATES`) and report export (`GET /campaigns/{id}/export`, `EXPORT`).

### AnalyticsUsageCapInterceptor
Enforces the Free-tier creator-analytics deep-dive cap (`Plan.creatorAnalyticsMonthlyLimit`: Free=1, Pro=unlimited) on `/analytics/creators/{creatorId}/**`. It deduplicates per distinct `creatorId` via `UsageCounterService.recordCreatorLookup` (the 4 sub-endpoints for one creator count as **one** deep-dive), checks the limit before recording, and returns 402 `UPGRADE_REQUIRED` when exceeded. Creator-self analytics (`/creator/analytics/me/**`) is deliberately **not** capped (creators have no workspace plan).

Plan matrix (from seed data):

| Feature | FREE | PRO |
|---|---|---|
| AI credits/mo | 100 | 400 |
| Seats | 1 | 5 |
| Tracked creators | 5 | unlimited |
| Creator-analytics deep-dives/mo | 1 | unlimited |
| Export / Templates | ✗ | ✓ |
| Brand publish fee | 10% (global) | 7% |

---

## 4. Service-to-service (Python → Spring)

`/internal/meera/*` is guarded by a **dual-credential mesh gate** plus an on-behalf human check. Two independent halves:

**Half 1 — `InternalServiceTokenFilter`** (self-guards on `/internal/`):
- `X-Meera-Service-Token`: HS256 JWT, `aud=influora-internal`, `iss=meera-python`, must carry `iat`+`exp`, and TTL ≤ 60s (rejects long-lived forged tokens).
- `X-Meera-Signature` + `X-Meera-Timestamp` + `X-Meera-Nonce` verified by `InternalRequestVerifier`: HMAC-SHA256 over `METHOD + path + sha256hex(body) + timestamp + nonce`, distinct HMAC key, lowercase hex, **constant-time compare**. Rejects clock skew > 30s and replayed nonces (`NonceCache`, 60s TTL, in-memory).
- On success sets `InternalPrincipal` (`ROLE_INTERNAL_SERVICE`); failures write an `auth.internal.rejected` audit row (reason only, no token bytes).

**Half 2 — `OnBehalfAuthResolver`** (called explicitly by each internal controller method, not a filter): re-validates the forwarded human access JWT (`X-Onbehalf-Authorization`) with the same `JwtService` parser, and enforces `token.workspaceId == body.workspace_id` (else 403 `ON_BEHALF_WORKSPACE_MISMATCH`). For C-tier money-adjacent tools it additionally requires the on-behalf user to hold OWNER/ADMIN in the workspace (else 403 `ON_BEHALF_INSUFFICIENT_ROLE`).

Net effect: a stolen service token cannot pick a victim workspace, and the AI can only act as a real, appropriately-privileged human. See [ai.md](ai.md).

**`POST /internal/meera/context`** (Platform-AI Phase 1, Wave 1/2 — Priya's A2 ruling): server-sources Meera's per-turn brand context (profile, credit state, campaign-template digest, past-campaign summary) instead of trusting a client-supplied `brand` body on `/chat`. Same dual-credential mesh gate as every other `/internal/meera/*` route — no new auth. `workspace_id` and `audience` ride **inside the signed JSON body** (not a query param or header), so the audience selector is covered by the HMAC signature and cannot be flipped after signing; Half 2's `token.workspaceId == body.workspace_id` cross-check applies identically. `audience` is currently hardcoded to `"BRAND"` server-side (`MeeraContextService`) — `"CREATOR"` 400s (`AUDIENCE_NOT_SUPPORTED`), a structural guard until the Phase 3 creator-audience allow-list ships. Response fields are a strict allow-list (`MeeraContextDtos.ContextResponse`, a Java `record` with explicit `@JsonProperty`s, no reflection/map-spread) — never wallet/escrow/KYC/PII, `product_catalog` filtered to name/price/currency only. `influora-ai/app/routes/chat.py` calls this endpoint at conversation start and ignores any client-supplied `brand`/`prompt_version` fields entirely; on a fetch failure it degrades to an empty brand block rather than failing the turn.

**`create_campaign` tool `template_id`** (Priya's A3 ruling): the Meera↔Spring `create_campaign` contract gained an optional `template_id` field. When present, the executor (`CreateCampaignExecutor`) re-validates visibility via `CampaignTemplateService.requireVisible` (SYSTEM template, or this workspace's own CUSTOM template — 404s cross-workspace, no existence leak) and copies `requirements`/`hashtags`/`target_audience`/`brand_guidelines` from the template row into the draft; `campaign_type` is **derived from the template row** (may be `STANDARD`, a value the AI-facing tool schema deliberately never exposes) rather than trusted from the AI's input. Budget stays `null` either way — money rails are unaffected by this change.

---

## 5. Admin RBAC

Admin authority is `ROLE_ADMIN` in the JWT, but capability is by `AdminRole` re-read from the DB, plus MFA. Rough matrix (enforced server-side in `service/admin/*`; the frontend `useAdminAuth` matrix is UX-only):

| Capability | SUPER_ADMIN | ADMIN | SUPPORT |
|---|---|---|---|
| Everything | ✓ | — | — |
| Ops (users, campaigns, disputes, moderation, support) | ✓ | ✓ | read-heavy |
| Finance write-off / reconciliation | ✓ | ✗ | ✗ |
| Fee config, comp subscriptions | ✓ | ✗ | ✗ |
| Assign support tickets | ✓ | ✓ | ✗ |

MFA is enforced on login for SUPER_ADMIN/ADMIN and re-checked (`requireRoleWithMfaSatisfied`) inside sensitive services (dispute resolve, fee config, billing).

---

## 6. Public vs authenticated endpoints

`config/SecurityConfig` `permitAll`: `/health`, `POST /auth/**` (+ specific OTP/verify paths), `GET /workspaces/slug-check`, `POST /webhooks/razorpay` (trust = HMAC), `GET /.well-known/jwks.json`. Everything else is `.anyRequest().authenticated()`. There are **no `hasRole()` rules** in the chain — role/plan/MFA/tenant checks live in filters, interceptors, and services.

Store webhooks (`/webhooks/shopify`, `/webhooks/woocommerce`, `/webhooks/redemption`, `/webhooks/conversion`) are public at the URL level and trusted purely by HMAC signature verification.

---

## Failure codes

`WRONG_USER_TYPE` (403), `FORBIDDEN` (403), `PLAN_NOT_RESOLVED` (403), `UPGRADE_REQUIRED` (402), `ON_BEHALF_WORKSPACE_MISMATCH` / `ON_BEHALF_INSUFFICIENT_ROLE` (403), `INVALID_ROLE` (400), `CANNOT_REMOVE_SOLE_OWNER` (409), `MFA_ENROLLMENT_REQUIRED` (403).
