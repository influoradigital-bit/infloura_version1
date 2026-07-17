# Feature: Authentication

**Business Purpose** — Lets brands, creators, and admins create accounts and prove identity so every other feature can be tenant-scoped and authorized. Escrow, campaigns, and payouts all rest on a trustworthy identity layer, so auth is the platform's foundation.

**Who uses it** — Everyone: brands and creators (self-service register/login), admins (login + MFA), and the frontend/session layer.

## User Roles
Brand, Creator, Admin (via `UserType`). Guests use only the public register/login/OTP endpoints.

## Permissions
- Register/login/OTP/reset are **public** (rate-limited).
- `/auth/logout`, `/auth/refresh` require a valid session (cookie/JWT).
- Admin auth is separate (`/admin/auth`), MFA-gated for SUPER_ADMIN/ADMIN.

## Business Flow
```
Register (email+password) → optional email OTP → account created (brand: +workspace+wallet; creator: +profile+wallet)
Login → BCrypt verify → checks verified/suspended → issue access JWT + set refresh cookie
Refresh → rotate refresh token → new access JWT
Logout → revoke all refresh tokens
```

## Frontend
- **Pages**: `pages/brand-login`, `brand-register`, `brand-forgot-password`, `creator-login`, `creator-register`, `admin-login`.
- **Components**: `shared/auth-login-shell`, `shared/demo-access-panel`, `shared/login-scene-3d`.
- **State/session**: `lib/auth-session.ts` (persist/clear access token + metadata in localStorage), guards in `App.tsx`.
- **API**: `api.auth.*` in `lib/api.ts`.

## Backend
- **Controllers**: `web/AuthController` (`/auth`), `web/AdminAuthController` (`/admin/auth`), `web/JwksController` (`/.well-known/jwks.json`).
- **Services**: `service/AuthService`, `service/BrandEmailOtpService`, `service/admin/AdminAuthService`, `service/admin/AdminMfaSecretCipher`.
- **Security**: `security/JwtService`, `JwtAuthenticationFilter`, `AuthCookieService`, `AdminAuthCookieService`, `TotpService`, `AuthRateLimitFilter`, `SpringJwksKeyService`.
- **Validation/DTOs**: `web/dto/auth/*` (`@Pattern` OTP, password fields), `common/PasswordPolicy`.

## Database
`users`, `refresh_tokens`, `admin_users`, `admin_refresh_tokens`, `password_reset_tokens`, `email_otp_challenges` (V2, V5, V34, V35). Tokens store SHA-256 hashes; passwords BCrypt-12. See [../database.md](../database.md).

## APIs
See [../api.md](../api.md) Authentication section. Key: `POST /auth/{role}/{register,login}`, `/auth/refresh`, `/auth/logout`, `/auth/forgot-password`, `/auth/reset-password`, `/admin/auth/login`, `/admin/auth/mfa/{setup,verify}`.

## AI
Not involved.

## Notifications
`AuthOtpEvent` (email OTP), `PasswordResetEvent`, `UserCreatedEvent` — routed through `NotificationService` (OTP/reset are email-only). Delivery via MSG91.

## Dependencies
- **Depends on**: MSG91 (email OTP/reset), the workspace/wallet creation path (brand register), `SecretsStartupValidator` (JWT/cookie secrets).
- **Depended on by**: literally every authenticated feature.

## Connected Files
`AuthController`, `AdminAuthController`, `AuthService`, `BrandEmailOtpService`, `JwtService`, `JwtAuthenticationFilter`, `AuthCookieService`, `TotpService`, `AuthRateLimitFilter`, `SpringJwksKeyService`, `common/PasswordPolicy`; frontend `App.tsx`, `lib/api.ts`, `lib/auth-session.ts`, auth pages.

## Execution Flow
```
Login form → api.auth.login → POST /auth/brand/login → AuthRateLimitFilter → AuthController
  → AuthService.login (BCrypt, verified/suspended checks) → issueTokens (JwtService)
  → Set-Cookie refresh + {accessToken} → persistBrandSession → redirect dashboard
```

## Error Handling
`EMAIL_ALREADY_EXISTS` (409), `INVALID_CREDENTIALS` (401), `WRONG_USER_TYPE` (403), `EMAIL_NOT_VERIFIED` (403), `WEAK_PASSWORD` (400), `INVALID_OTP` (400), `INVALID_REFRESH_TOKEN` (401), `MFA_REQUIRED`/`MFA_ENROLLMENT_REQUIRED`, rate-limit 429. Anti-enumeration on OTP send and forgot-password (uniform responses).

## Security
BCrypt-12; HS256 access tokens (short TTL); refresh token opaque, hashed, HttpOnly SameSite=Strict cookie; single-use rotation; admin TOTP MFA with lockouts and AES-GCM-encrypted secret. Risks: access token in localStorage, no refresh-reuse detection, frontend refresh half-wired, admin lockout no in-app recovery. See [../security.md](../security.md).

## Performance
Rate limiting is in-memory/per-instance (needs Redis at scale). BCrypt-12 is intentionally CPU-costly on login/register.

## Testing
Backend integration tests (Testcontainers) cover auth flows. Regression risks: token rotation correctness, OTP attempt/expiry limits, cookie flags in prod.

## Production Readiness
- **Health**: 8/10 · **Completion**: ~90%
- **Known issues**: localStorage token XSS exposure; frontend `/auth/refresh` not called (sessions break on expiry); admin lockout recovery is DB-only.
- **Missing**: refresh-reuse/token-family revocation; frontend auto-refresh; SMS OTP.
- **Tech debt**: `require-email-otp-before-register` off by default; cookie `secure` default false (guarded outside dev).
- **Last verified**: 2026-07-15
