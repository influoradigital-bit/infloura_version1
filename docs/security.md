# Security

Influora's security model, the controls that implement it, and the known risks that the code itself flags. This complements [authentication.md](authentication.md) and [authorization.md](authorization.md).

---

## Security posture at a glance

- **Stateless JWT auth** (HS256 user tokens; ES256 service/stream tokens via JWKS). No server session; refresh token only in an HttpOnly, SameSite=Strict, path-scoped cookie.
- **Passwords**: BCrypt cost 12, policy enforced (length + character classes + common-password denylist).
- **Multi-tenancy**: every access is scoped to the caller's workspace/creator; foreign ids return generic `NOT_FOUND` (no enumeration oracle).
- **Money integrity**: server-derived amounts only, double-entry ledger, idempotency keys, webhook HMAC verification (constant-time, fail-closed).
- **Secrets**: AES-256-GCM encryption at rest for OAuth/bank secrets; `SecretsStartupValidator` refuses to boot in non-dev with weak/missing/duplicate secrets.
- **Admin**: separate token/cookie, TOTP MFA, lockouts, metadata-only audit logging.

---

## Filter chain & controls

Runtime order (`config/SecurityConfig`, stateless):

1. **`AuthRateLimitFilter`** — in-memory fixed-window limits. IP-keyed for credential/OTP/refresh/OAuth/tracking; user-keyed for write buckets (deliverable 20, contract-sign 10, review/flag 10, dispute 5, discovery-search 60, creator-withdraw 5/hour). Percent-decodes paths to block encoding bypass. Emits `X-RateLimit-*`, 429 + `Retry-After`.
2. **`InternalServiceTokenFilter`** — guards `/internal/**` (Python mesh): service JWT (≤60s TTL, `aud`/`iss` pinned) + HMAC request signature + nonce/timestamp replay protection.
3. **`JwtAuthenticationFilter`** — Bearer → `AuthPrincipal`.
4. **`PlanGateFilter`** — resolves BRAND plan.

**HTTP headers** (`SecurityConfig`): HSTS (`includeSubDomains`, 1y), `frameOptions.deny()`, `Referrer-Policy: no-referrer`, CSP default `default-src 'none'; frame-ancestors 'none'; base-uri 'none'` (CSP on the JSON API is a secondary XSS mitigation; the primary CSP belongs on the SPA host).

**CSRF** is disabled **by design** — auth is a Bearer header plus a SameSite=Strict, path-scoped, HttpOnly refresh cookie (no ambient session cookie). The code warns: do not move auth to a cookie without re-enabling CSRF.

**CORS** (`config/CorsConfig`): origins from `influora.cors.allowed-origins`, `allowCredentials=true` (required for the refresh cookie), exposed headers `X-RateLimit-*`.

**Actuator**: only `/health` exposed, `show-details: never`.

---

## Input & content safety

- **Validation**: Jakarta Validation on DTOs (`@NotBlank`, `@Size`, `@DecimalMin`, `@Min/@Max`, `@Pattern`).
- **Sanitization**: free-text (notes, reasons, messages) passes through `TextSanitizer`.
- **Uploads**: declared-vs-sniffed MIME check (`MediaMimeSniffer`, magic bytes), `MalwareScanService.requireClean`, streamed to R2 with size caps (deliverable 500MB/file, 1GB/batch; proof/cover 10MB, image-only), MD5 digest. Object keys are validated for traversal/ownership (`ProofObjectKeys`).
- **URL handling**: `PostUrlIdentifier` never URL-decodes stored post URLs (they're inert), and rejects encoded brackets/quotes. Shopify shop domains are regex-validated to prevent SSRF.

---

## Secret management

- **Encryption at rest** (AES-256-GCM, IV-prepended): Meta OAuth tokens, admin MFA secrets, creator bank instruments, Shopify tokens, WooCommerce/conversion webhook secrets. Distinct key per subsystem (blast-radius separation).
- **`SecretsStartupValidator`** (`@PostConstruct`, gated on `influora.env`/`APP_ENV`, default `dev`): outside dev it aborts boot unless every required secret is present, ≥32 bytes, not a known dev-default, and not duplicated across roles; JWKS private key must parse to EC; admin MFA key must decode to exactly 32 bytes; both refresh-cookie `secure` flags must be `true`; Razorpay webhook secret must not be a placeholder.
- Committed dev-default secrets in `application.yml` are safe **only** because the validator fails closed in non-dev.

---

## Webhook trust

Store and payment webhooks are public at the URL level and trusted purely by HMAC:

| Webhook | Header | Algorithm | Verify-first |
|---|---|---|---|
| Razorpay | `X-Razorpay-Signature` | HMAC-SHA256 hex | ✓ |
| Shopify | `X-Shopify-Hmac-Sha256` | HMAC-SHA256 base64 | ✓ |
| WooCommerce | `X-WC-Webhook-Signature` | HMAC-SHA256 base64 | ✓ |
| Conversion | `X-Influora-Signature` | HMAC-SHA256 | ✓ |

All use constant-time comparison and fail closed on missing signature/secret. Idempotency keys prevent double-processing.

---

## Known risks & residual issues (from code)

These are surfaced honestly in the codebase; treat them as the security backlog. Consolidated in [known-limitations.md](known-limitations.md).

1. **Access tokens in `localStorage`** (all roles) — any XSS exfiltrates a live Bearer token. The HttpOnly refresh cookie limits durability, not theft. API CSP is the documented secondary mitigation.
2. **No refresh-token reuse detection** — single-use rotation, but replaying a burned token only 401s; no token-family kill or breach alert.
3. **Frontend refresh half-wired** — the cookie is sent (`credentials:'include'`) but the SPA never calls `/auth/refresh`, so sessions break on expiry rather than refreshing.
4. **Defaults**: `require-email-otp-before-register=false` (accounts can exist unverified until login blocks them), `refresh-cookie.secure=false` in base config (guarded outside dev).
5. **Per-instance state** — `AuthRateLimitFilter` and `NonceCache` are in-memory; horizontal scaling needs Redis/edge enforcement or limits/replay-protection are per-node only.
6. **Admin lockout has no in-app recovery** — a locked-out SUPER_ADMIN/ADMIN needs a direct DB update.
7. **Admin controllers return raw DTOs** with two flagged frontend contract mismatches (error path, base path).
8. **TOTP**: no server-side QR; setup returns the plaintext secret once; ±1 step drift (~90s window).
9. **Placeholder Razorpay secret is non-blank**, so `isConfigured()` is true with junk creds — only `isFullyConfigured()`/webhook-fail-closed guard against real API calls with a bad secret.

---

## Data-protection notes

- **PII discipline**: support ticket message bodies and media captions are never logged; audit logs are metadata-only; error bodies from external providers are not logged.
- **Bank instruments**: stored encrypted, only a `display_mask` is ever returned to the client.
- **Media caption** (`media_metrics.caption`, V26): captured strictly for the internal brand-safety pipeline; never in any brand-facing DTO.
