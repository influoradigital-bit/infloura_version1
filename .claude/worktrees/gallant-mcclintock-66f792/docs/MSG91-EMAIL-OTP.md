# MSG91 — Email OTP & notifications (Influora)

**Locked for Influora backend.** Creator **phone** OTP and brand **email** OTP both go through MSG91, but they use **different credentials and APIs**.

| Channel | MSG91 surface | Credential |
|---------|---------------|------------|
| Creator SMS / widget OTP | SMS API + widget | `msg91.auth-key`, `sender-id`, `route`, `widget-id` |
| Brand **email OTP** (verify email, resend) | **Email API v5** (template) or **SMTP** | `msg91.email.token-auth` — **not** the SMS `auth-key` |
| Transactional mail (password reset link, etc.) | Email template with `magic_link` variable | `transactional-template-id` when set; else inline HTML fallback |

> Per platform note: **OTP email is sent via MSG91 Email (SMTP or Email API v5), not the SMS HTTP send endpoint.** SMS `auth-key` is only for phone/widget flows.

---

## Spring configuration (`application.yml`)

Maps to environment variables (see `influora-api/.env.example`). **Never commit real keys** — set in `.env` or deployment secrets.

```yaml
influora:
  msg91:
    enabled: ${MSG91_ENABLED:true}
    auth-key: ${MSG91_AUTH_KEY:}              # SMS + widget only
    sender-id: ${MSG91_SENDER_ID:INFLRA}
    route: ${MSG91_ROUTE:4}
    widget-id: ${MSG91_WIDGET_ID:}
    token-auth: ${MSG91_TOKEN_AUTH:}        # Email API v5
    email:
      domain: ${MSG91_EMAIL_DOMAIN:}
      from-email: ${MSG91_FROM_EMAIL:}
      from-name: ${MSG91_FROM_NAME:Influora}
      template-id: ${MSG91_EMAIL_TEMPLATE_ID:otpman}
      transactional-template-id: ${MSG91_EMAIL_TRANSACTIONAL_TEMPLATE_ID:}
      template-otp-variable: ${MSG91_EMAIL_TEMPLATE_OTP_VARIABLE:otp}
      transactional-link-variable: ${MSG91_EMAIL_TRANSACTIONAL_LINK_VARIABLE:magic_link}
      company-name: ${MSG91_EMAIL_COMPANY_NAME:Influora}
    template:
      otp: ${MSG91_OTP_TEMPLATE_ID:}
      welcome: ${MSG91_WELCOME_TEMPLATE_ID:}
```

---

## Brand email OTP flow

### 1. When OTP is sent

| Trigger | Endpoint (planned) | Notes |
|---------|-------------------|--------|
| Brand register | `POST /auth/brand/register` | After user row created → generate OTP → MSG91 email |
| Resend | `POST /auth/resend-verification` (§32.2) | Max 3/hour per email |
| Optional | `POST /auth/brand/send-email-otp` | Explicit resend if UI needs it |

### 2. Generate & store

- 6-digit numeric OTP, **5 min** TTL (`OTP_EXPIRY_SECONDS=300`, same as creator SMS).
- Store **hashed** OTP in MySQL (`email_otp_challenges`: `user_id`, `otp_hash`, `expires_at`, `attempts`).
- Invalidate previous unused OTPs for same user on new send.

### 3. Send via MSG91 Email

**Preferred (production):** Email API v5 with dashboard template `otpman` (or `MSG91_EMAIL_TEMPLATE_ID`).

- Header: `authkey` or token per MSG91 Email v5 docs → use **`token-auth`** (`MSG91_TOKEN_AUTH`).
- Body includes template id + variables: `{ "otp": "482910" }` (name from `MSG91_EMAIL_TEMPLATE_OTP_VARIABLE`, default `otp`).
- `from`: `MSG91_FROM_EMAIL` on domain `MSG91_EMAIL_DOMAIN`.
- `from_name`: `MSG91_FROM_NAME` / `MSG91_EMAIL_COMPANY_NAME` in template.

**Fallback:** If `transactional-template-id` blank and template send fails → inline HTML email via MSG91 SMTP relay (same domain/from).

**Not used for email OTP:** SMS `POST` send with `auth-key` only.

### 4. Verify

**Canonical (OTP):**

```
POST /auth/brand/verify-email
Content-Type: application/json
{ "email": "ananya@brandco.com", "otp": "482910" }
```

Response `200`: `{ "success": true, "data": { "emailVerified": true } }`  
Side effects: `users.email_verified = true`, `status → ACTIVE` if was `PENDING_VERIFICATION`.

**Legacy / optional:** `GET /auth/verify-email?token=` for magic-link emails using `transactional-template-id` + `magic_link` variable (`MSG91_EMAIL_TRANSACTIONAL_LINK_VARIABLE`).

### 5. Login gate

- Production: `influora.auth.require-email-verification=true` → `403 EMAIL_NOT_VERIFIED` until step 4 succeeds.
- Register still returns JWT; user can call verify endpoint while authenticated.

---

## Creator phone OTP (unchanged channel)

- `POST /auth/creator/send-otp` / `verify-otp` (§4.3–4.4).
- Uses `msg91.auth-key`, `sender-id`, `route`, optional `widget-id` — **not** `token-auth`.

---

## Dashboard checklist

1. **Email → API Keys** → copy `token-auth` → `MSG91_TOKEN_AUTH`.
2. **Email → Templates** → create/confirm template `otpman` with `{{otp}}` (or your `template-otp-variable`).
3. Verify sending domain (`MSG91_EMAIL_DOMAIN`) and `from-email`.
4. **SMS** → separate `auth-key` for creator OTP only.
5. Rotate keys if exposed; use `.env` locally, secrets manager in prod.

---

## Implementation status

| Piece | Status |
|-------|--------|
| `application.yml` + `.env.example` keys | Documented |
| `email_otp_challenges` table + `Msg91EmailService` | Phase 1b (next) |
| Wire register + resend + verify endpoints | Phase 1b |
| Creator SMS via MSG91 | Phase creator-auth |

*See `BACKEND-API-SPEC.md` §1, §4.9, §32.2, §21.*
