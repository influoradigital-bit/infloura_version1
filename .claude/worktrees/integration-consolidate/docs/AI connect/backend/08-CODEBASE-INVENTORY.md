# 08 — Backend Codebase Inventory

**Author:** Tara (Operations & Reporting Lead) — read-only reporter
**Date:** 2026-07-05
**Scope:** `influora-api/` (Spring Boot, Java 21, MySQL 8, Flyway, JWT auth)
**Purpose:** Precise inventory of what is ALREADY built in the backend vs. what must still be created for the Meera / AI + brand-workspace work.

> **Method:** Every path below was read or listed directly from disk. Purposes are inferred from filenames + quick reads. Nothing was modified.

---

## 0. Stack at a glance (from `pom.xml`)

| Concern | Present |
|---|---|
| Framework | `spring-boot-starter-web`, `-data-jpa`, `-validation`, `-security` |
| Auth | `jjwt-api` / `jjwt-impl` / `jjwt-jackson` (JWT) |
| DB / migrations | `mysql-connector-j`, `flyway-core`, `flyway-mysql` |
| Storage | `software.amazon.awssdk:s3` v2.29.0 (used against Cloudflare R2) |
| IDs | `ulid-creator` (ULID) |
| Test | `spring-boot-starter-test` only (no test sources — see §7) |
| **Absent deps** | **No Razorpay, no JavaMail/mail starter, no OpenAI/Anthropic, no WebSocket, no Redis, no scheduling/quartz** |

---

## 1. Package tree by layer — `src/main/java/com/influora/`

### Root
| File | Purpose |
|---|---|
| `InfluoraApiApplication.java` | Spring Boot main entry point |

### `common/` (cross-cutting utilities)
| File | Purpose |
|---|---|
| `Ulids.java` | ULID generator — `newUlid()` returns 26-char ULID string (the ID strategy) |
| `ApiResponse.java` | Standard success envelope wrapper |
| `ApiErrorBody.java` | Standard error body shape |
| `ApiException.java` | Custom application exception |
| `GlobalExceptionHandler.java` | `@ControllerAdvice` mapping exceptions to API error bodies |
| `PageMeta.java` | Pagination metadata for list responses |
| `JsonLists.java` | Helper for JSON list (`JSON` column) serialization |
| `SlugUtils.java` | Slug generation / normalization helper |

### `config/`
| File | Purpose |
|---|---|
| `SecurityConfig.java` | Spring Security filter chain — CSRF disabled, stateless, JWT + rate-limit filters (**FILE TRUNCATED ON DISK — see §6 warning**) |
| `CorsConfig.java` | CORS allowed-origins config |
| `JwtProperties.java` | `@ConfigurationProperties(influora.jwt.*)` — access/refresh secrets + expiries |
| `R2Config.java` | Builds S3 client bean pointed at Cloudflare R2 |
| `R2Properties.java` | `@ConfigurationProperties(influora.r2.*)` — accountId, keys, bucket, endpoint, publicUrl |

### `security/`
| File | Purpose |
|---|---|
| `JwtService.java` | Issues / verifies access & refresh JWTs |
| `JwtAuthenticationFilter.java` | Reads Bearer token, populates `AuthPrincipal` |
| `AuthPrincipal.java` | Authenticated-user principal (userId, workspace context) |
| `AuthCookieService.java` | Sets/clears the HttpOnly `influora_refresh` cookie |
| `AuthRateLimitFilter.java` | Per-instance throttle on unauthenticated auth endpoints |

### `web/` (controllers)
| File | Base path (under `/api/v1`) | Endpoints |
|---|---|---|
| `HealthController.java` | `/health` | GET health |
| `AuthController.java` | `/auth` | POST brand/send-email-otp, brand/verify-email, brand/register, brand/login, refresh |
| `UserController.java` | `/users` | GET/PATCH `/me` |
| `WorkspaceController.java` | `/workspaces` | GET `/slug-check` |
| `OnboardingController.java` | `/onboarding/brand` | POST company, complete, kyc |
| `CampaignController.java` | `/campaigns` | GET list, GET `{id}`, POST, PATCH `{id}`, DELETE `{id}`, POST `{id}/duplicate` |
| `CreatorController.java` | `/creators` | GET list (discovery), GET `{id}`, POST `{id}/save`, POST `{id}/invite` |

### `web/dto/`
| File | Purpose |
|---|---|
| `auth/AuthDtos.java`, `BrandRegisterRequest`, `EmailOtpDtos`, `ForgotPasswordRequest`, `LoginRequest`, `RefreshRequest`, `ResetPasswordRequest` | Auth request/response DTOs |
| `campaign/CampaignDtos.java` | Campaign create/update/response DTOs |
| `creator/CreatorDtos.java` | Creator discovery/detail/invite DTOs |
| `onboarding/OnboardingDtos.java` | Brand onboarding (company/kyc/complete) DTOs |
| `user/UserDtos.java` | User profile (`/me`) DTOs |

### `service/`
| File | Purpose |
|---|---|
| `AuthService.java` | Register / login / refresh orchestration |
| `BrandEmailOtpService.java` | Email-OTP challenge generation + verification |
| `BrandContextService.java` | Resolves the caller's brand workspace context |
| `OnboardingService.java` | Brand company/KYC onboarding logic |
| `UserService.java` | User profile read/update |
| `WorkspaceSlugService.java` | Workspace slug availability + generation |
| `CampaignService.java` | Campaign CRUD + duplicate |
| `CampaignMapper.java` / `CampaignValidator.java` | Campaign entity↔DTO mapping / validation |
| `CreatorDiscoveryService.java` | Creator search / discovery |
| `CreatorMapper.java` / `CreatorProfileSpecifications.java` | Creator mapping / JPA Specification filters |

### `domain/entity/`
| Entity | Table | Notes |
|---|---|---|
| `User.java` | `users` | ID `VARCHAR(26)` ULID |
| `Workspace.java` | `workspaces` | brand/agency workspace |
| `WorkspaceMember.java` | `workspace_members` | role: OWNER/ADMIN/MANAGER/MEMBER/VIEWER |
| `Wallet.java` | `wallets` | balance + escrow_balance columns, owner USER/WORKSPACE |
| `RefreshToken.java` | `refresh_tokens` | rotating refresh tokens |
| `PasswordResetToken.java` | `password_reset_tokens` | reset flow |
| `EmailOtpChallenge.java` | `email_otp_challenges` | brand email OTP |
| `Campaign.java` | `campaigns` | ID ULID, JSON platform/objectives fields |
| `Collaboration.java` | `collaborations` | campaign↔creator link + status machine |
| `CreatorProfile.java` | `creator_profiles` | discoverable creator profile |
| `PlatformStat.java` | `platform_stats` | per-platform follower/engagement stats |
| `SavedCreator.java` | `saved_creators` | brand-saved creators shortlist |

### `domain/enums/`
`CampaignStatus`, `CollaborationSource`, `CollaborationStatus`, `MemberRole`, `UserStatus`, `UserType`, `VerificationStatus`, `WalletOwnerType`, `WorkspaceType`.

### `repository/`
Spring Data JPA repos (one per aggregate): `User`, `Workspace`, `WorkspaceMember`, `Wallet`, `RefreshToken`, `PasswordResetToken`, `EmailOtpChallenge`, `Campaign` (+ `CampaignSpecs`), `Collaboration`, `CreatorProfile`, `PlatformStat`, `SavedCreator`.

### `integration/`
| File | Purpose |
|---|---|
| `integration/storage/R2StorageService.java` | Cloudflare R2 (S3-compatible) upload/presign service — **the only external integration present** |

---

## 2. ID strategy (confirmed)

**ULID as `VARCHAR(26)` string PK.** Confirmed in `common/Ulids.java` (wraps `com.github.f4b6a3.ulid.UlidCreator`) and in `Wallet.java` (`@Id @Column(length = 26) private String id`). Every migration table uses `id VARCHAR(26) PRIMARY KEY`. All foreign keys are `VARCHAR(26)`.

---

## 3. Flyway migrations — `src/main/resources/db/migration/`

| Migration | Creates / does |
|---|---|
| `V1__file_uploads.sql` | `file_uploads` (owner_id, purpose, r2_bucket, r2 keys) |
| `V2__core_auth.sql` | `users`, `workspaces`, `workspace_members`, `wallets`, `refresh_tokens`, `password_reset_tokens` |
| `V3__workspace_kyc_docs.sql` | ALTER `workspaces` — adds `kyc_gstin_doc_url`, `kyc_pan_doc_url` |
| `V4__campaigns.sql` | `campaigns` (budget_min/max, JSON platforms/objectives, status enum) |
| `V5__email_otp.sql` | `email_otp_challenges` (otp_hash, expires_at, attempts) |
| `V6__creators_collaborations.sql` | `creator_profiles`, `platform_stats`, `saved_creators`, `collaborations` |
| `V7__seed_discoverable_creators.sql` | Seed demo creator users/profiles (dev/staging; password `Password@123`) |

**Latest applied migration: V7.** Next available number for new work: **V8**.
`spring.jpa.hibernate.ddl-auto=validate` — schema must match entities; all schema changes MUST go through Flyway.

---

## 4. Money / escrow / wallet / contract / notification / AI infrastructure

| Capability | Status |
|---|---|
| **Wallets** | **EXISTS** — `wallets` table (V2) + `Wallet` entity/repo. Has `balance` and `escrow_balance` DECIMAL(14,2) columns, owner USER/WORKSPACE, currency INR. |
| **Wallet ledger / transactions** | **ABSENT** — no `wallet_transactions` table, no ledger entity, no double-entry. `escrow_balance` is just a column; nothing moves money in/out of it. |
| **Escrow logic** | **ABSENT** — column exists, but no escrow service, no hold/release/refund flow. |
| **Payments / Razorpay** | **ABSENT** — no Razorpay dependency, no payment table, no webhook controller, no `PaymentService`. |
| **Payouts** | **ABSENT** — no payout table/service. |
| **Contracts** | **ABSENT** — no `contracts` table, no contract entity/service. `collaborations` has `CONTRACT_PENDING`/`CONTRACTED` statuses but nothing that generates or stores a contract. |
| **Notifications** | **ABSENT** — no `notifications` table, no `NotificationService`, no email/push/in-app delivery. (Email OTP is a bespoke MSG91-style flow, not a general notification system.) |
| **AI / Meera** | **ABSENT entirely** — no AI service, no OpenAI/Anthropic client, no chat/thread/message tables, no agent/tool infrastructure, no `ai_*` anything. |
| **File uploads / storage** | **EXISTS** — `file_uploads` table (V1) + `R2StorageService` (Cloudflare R2). |

---

## 5. Config — `application.yml` keys of interest

| Area | Keys |
|---|---|
| Datasource | `spring.datasource.url/username/password` (MySQL, env-overridable), Hikari pool 10 |
| JPA | `ddl-auto: validate`, `open-in-view: false`, MySQLDialect |
| Flyway | enabled, `baseline-on-migrate: true` |
| Server | port 8080, **context-path `/api/v1`** |
| JWT | `influora.jwt.access-secret / refresh-secret`, access-expiry 900s (15m), refresh-expiry 2,592,000s (30d) |
| Auth | `require-email-verification`, `require-email-otp-before-register`, otp-length 6, refresh-cookie (`influora_refresh`, HttpOnly, SameSite=Strict, path `/api/v1/auth`), auth rate-limit windows |
| Security | `content-security-policy` (default-src 'none'; frame-ancestors 'none') |
| CORS | `influora.cors.allowed-origins` (default localhost:5173) |
| R2 | `influora.r2.*` — accountId, accessKeyId, secretAccessKey, bucketName, endpoint, publicUrl (bound via `R2Properties`) |
| MSG91 | `influora.msg91.*` — enabled, auth-key, sender-id INFLRA, route 4 (**block truncated on disk — see §6**) |

**SecurityConfig routes:** CSRF disabled by design (Bearer-header auth + HttpOnly refresh cookie), stateless sessions, `JwtAuthenticationFilter` + `AuthRateLimitFilter` in the chain. **The exact `requestMatchers` permitAll vs authenticated list could NOT be confirmed — the file is truncated (§6).** From controller shapes, public routes are the `/auth/**` endpoints + `/health`; everything else (`/users`, `/workspaces`, `/onboarding`, `/campaigns`, `/creators`) is authenticated.

`application-dev.yml`: dev profile disables email verification/OTP, sets `com.influora` logging to DEBUG.

---

## 6. ⚠️ Data-integrity finding (must flag to Priya/Vikram)

**Two working-copy files are truncated on disk:**
- `src/main/resources/application.yml` — 61 lines, ends mid-value at `widge` (inside the msg91 block; `widget-*` and any R2 yaml keys after it are cut off).
- `src/main/java/com/influora/config/SecurityConfig.java` — 47 lines, ends mid-statement at `.sessionCreationPolicy(Ses` (the entire authorization rule set + `return http.build()` is missing).

As written, **SecurityConfig.java would not compile** and the app would not start from this exact copy. This is either an incomplete checkout / sync artifact or a genuinely corrupted file. **Before any new backend work, the canonical/full versions of these two files must be restored and confirmed to compile.** The route-authorization inventory in §5 is therefore inferred, not read.

---

## 7. Test setup — `src/test`

**No test sources exist.** `src/test` contains no files. `spring-boot-starter-test` is on the classpath (test scope) but **zero unit or integration tests are written.** Any new capability ships with no existing test scaffolding to extend.

---

## 8. GAP SUMMARY

| Capability | Exists? | Migration / file if exists | What's missing |
|---|---|---|---|
| Auth (JWT + refresh + email OTP) | ✅ Yes | V2/V5; `AuthService`, `JwtService`, `security/*` | — |
| Users / profiles | ✅ Yes | V2; `UserService`, `User` | — |
| Workspaces + members + roles | ✅ Yes | V2; `Workspace`, `WorkspaceMember` | — |
| Brand onboarding + KYC docs | ✅ Yes | V3; `OnboardingService` | — |
| Campaigns (CRUD) | ✅ Yes | V4; `CampaignService` | — |
| Creator discovery + save + invite | ✅ Yes | V6; `CreatorDiscoveryService` | — |
| Collaborations (status machine) | ✅ Partial | V6; `Collaboration` | Only the record + statuses; no logic that drives transitions, contracts, or payment |
| File storage (Cloudflare R2) | ✅ Yes | V1; `R2StorageService` | — |
| Wallet (balance + escrow column) | ✅ Partial | V2; `Wallet` | No ledger; balances never mutate |
| Wallet transactions / ledger | ❌ No | — | `wallet_transactions` table, double-entry ledger, `WalletService` |
| Escrow (hold / release / refund) | ❌ No | — | Escrow service + flow (column exists, unused) |
| Payments (Razorpay) | ❌ No | — | Razorpay dep, order/payment tables, webhook controller, `PaymentService` |
| Payouts | ❌ No | — | Payout table + service |
| Contracts | ❌ No | — | `contracts` table, entity, generation/signature service |
| Notifications | ❌ No | — | `notifications` table, `NotificationService`, email/push/in-app delivery |
| AI / Meera | ❌ No | — | Everything: AI service, LLM client, chat/thread/message tables, tools, permissions enforcement |
| Automated tests | ❌ No | — | Entire `src/test` suite |

---

*Prepared read-only by Tara. No code, config, or schema was changed. Flag §6 (truncated files) and §7 (no tests) before implementation begins.*
