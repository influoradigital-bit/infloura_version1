# Creator Platform — OWASP K6 Cycle 3 (Kabir)

**Auditor:** Kabir Singh (Offensive Security / Red-Team Lead)  
**Date:** 2026-07-10 (~16:20 IST)  
**Task:** K-GA-4 · P0 (Tick #37) · OWASP cycle 3 kickoff — PII-at-rest + upload malware + OAuth token-log / SSRF URL mapping  
**Verdict:** ⚠️ **PASS WITH FINDINGS** — **0 Critical, 0 High, 5 Medium, 6 Low**  
**Scope:** Spec 12 §3 (PII), §5 (content upload), §2/§8 (OAuth token hygiene) + A10 SSRF URL-mapping spot-checks  
**Reference:** Cycle 1 `wiki/errors/creator-owasp-K6-kickoff.md`; Cycle 2 `wiki/errors/creator-owasp-K6-cycle2.md`; `wiki/tech/creator/12_CREATOR_SECURITY_SPEC.md` §3–§5  
**Note:** C2 Mediums 1–5 are **CLOSED** (do not reopen). **#38 / #39 / #40** remain **SHIPPED/CONDITIONAL** — **not reopened**. Security matrix ~75% → **~80%**.

---

## Executive Summary

Cycle 3 closes the deferred content / privacy / outbound-URL slice from K6-1/K6-2. Adversarial probes find **no Critical or High** and **no reason to reopen #38/#39/#40**.

**What holds:**
- Deliverable upload path (`CreatorDeliverableService`) — magic-byte sniff + declared/sniffed family match + stream-to-R2 + owned proof keys — **PASS** (cycle 2 / #40 standing).
- OAuth tokens at rest — Meta / Shopify AES-256-GCM via dedicated keys — **PASS**.
- Shopify shop-domain SSRF choke (`SHOP_DOMAIN_PATTERN`) — **PASS**.
- Meta / MSG91 / Razorpay / BrandSafety outbound hosts are **config- or constant-bound** (not caller-interpolated) — **PASS** for classic SSRF.
- Tracking `baseUrl` scheme gate (`CampaignLinkService.validateBaseUrl`) — **PASS** (non-http(s) blocked; residual open-redirect-to-arbitrary-https is Low).

**What does not hold vs Spec 12:**
- Email / phone stored **plaintext** in `users` (+ OTP / outbox tables) despite §3.1 “Encrypted”.
- Portfolio cover upload skips magic-byte sniff, uses `getBytes()`, persists **public** R2 URL.
- **No virus/malware scan** on any upload path (§5.1).
- OAuth / Graph error paths still log **full response bodies** (cycle-2 carry).
- Meta Graph puts `access_token` in query string (proxy/access-log exposure).

**Bank / UPI PII:** no creator bank-account / UPI columns or encrypt-at-rest path shipped yet. RazorpayX payouts take a `fund_account_id` at call time — **gap is “not built,” not “plaintext bank rows.”** Filed as Medium so the first bank-onboarding slice cannot ship without AES-GCM.

---

## Probe Matrix

### 1. PII-at-rest (email / phone / bank)

| Asset | Spec 12 §3.1 | Code | Result |
|-------|--------------|------|--------|
| `users.email` | Encrypted | Plain `VARCHAR(255)` — `User.java` / `V2__core_auth.sql` | ❌ **M-K6-C3-1** |
| `users.phone_number` | Encrypted | Plain `VARCHAR(20)` — same | ❌ **M-K6-C3-1** |
| `email_otp_challenges.email` | Auth-only | Plain email + hashed OTP — `EmailOtpChallenge` | ⚠️ plaintext email index — fold into **M-K6-C3-1** |
| `email_outbox.to_email` | Auth/notify | Plain — `V18__email_outbox.sql` | ⚠️ fold into **M-K6-C3-1** |
| Bank account / UPI | Encrypted, payments-only, 7y | **No entity / migration** for creator bank/UPI | ❌ **M-K6-C3-2** (must-encrypt-before-ship) |
| OAuth tokens | AES-256-GCM | `MetaTokenStorage` / `ShopifyTokenStorage` | ✅ PASS |
| Admin MFA / webhook secrets | Encrypted | AES-256-GCM ciphers | ✅ PASS (out of creator PII scope) |
| Right-to-deletion §3.3 | Required | No `CreatorDataDeletionService` | ⚠️ **L-K6-C3-1** |

No JPA `@Convert` / `AttributeConverter` exists for email/phone. Meera AI assemblers correctly **exclude** email/phone/bank from tool egress — access-control minimization **PASS**; storage encryption **FAIL**.

### 2. Upload malware / content-type probes

| Path | Magic sniff | Size cap | Storage | Malware scan | Result |
|------|-------------|----------|---------|--------------|--------|
| `CreatorDeliverableService` upload/proof | ✅ `MediaMimeSniffer` + family match | ✅ stream + `LimitedInputStream` | R2 key + `presignGet` | ❌ none | MIME ✅; malware ❌ **M-K6-C3-3** |
| `PortfolioService.uploadCover` | ❌ header-only `image/*` | ❌ `getBytes()` full buffer | `putBytes` + **`publicUrl`** | ❌ none | ❌ **M-K6-C3-4** (+ malware via C3-3) |
| Contract PDF | N/A (server-generated) | server bytes | R2 + presign | N/A | ✅ PASS |
| EXIF strip (§5.1) | Required | — | — | not implemented | ⚠️ **L-K6-C3-2** |

**Content-type adversarial notes (deliverable path):**
- Declared `video/mp4` + ZIP bytes → rejected (sniff fail) — ✅ covered by existing tests.
- Declared `image/jpeg` + PNG bytes → **accepted** (same `image` family) — intentional; residual polyglot risk → **L-K6-C3-3**.
- SVG / HTML / EXE without image/video magic → rejected — ✅.

### 3. OAuth token-log + SSRF URL mapping

| Surface | Probe | Result |
|---------|-------|--------|
| `MetaTokenStorage` audit maps | ids/counts only, never token/ciphertext | ✅ PASS |
| `MetaOAuthService.fetchToken` error log | `body={}` full `getResponseBodyAsString()` | ❌ **M-K6-C3-5** |
| `ShopifyOAuthService.exchangeCodeForToken` error log | same pattern | ❌ fold **M-K6-C3-5** |
| `MetaGraphApiClient.translate` error log | same pattern | ❌ fold **M-K6-C3-5** |
| `MetaGraphApiClient.get` | `access_token` query param on every Graph call | ⚠️ **L-K6-C3-4** (URI/proxy log risk) |
| `MetaOAuthService` token URLs | `client_secret` in query string (Meta API shape) | ⚠️ **L-K6-C3-5** |
| Shopify shop interpolation | `validateShopDomain` before URL build | ✅ SSRF PASS |
| Meta authorize/token hosts | fixed `facebook.com` / `graph.facebook.com` | ✅ PASS |
| `BrandSafetyAiClient` | `props.getBaseUrl()` config-only | ✅ PASS |
| `RazorpayXClient` / `Msg91EmailClient` | config/constant URLs | ✅ PASS |
| WooCommerce `site_url` | normalize only; **no outbound dial** | ✅ N/A SSRF (documented) |
| UTM `fullTrackingUrl` redirect | http(s)+host required at create | ✅ scheme PASS; arbitrary host → **L-K6-C3-6** |
| Avatar/cover URL fields | stored + FE `<img>` / CSS; **no server fetch** | ✅ no SSRF; scheme validation absent → fold L-K6-C3-6 |

**SSRF map (outbound dialers):** MSG91 · Meta Graph/OAuth · Shopify OAuth · Razorpay/RazorpayX · BrandSafety AI · R2 S3 endpoint. **Zero** user-controlled host interpolation except Shopify `shop` (pattern-gated) and WooCommerce (no dial).

---

## Findings Register (Numbered)

### Blockers

**None.** 0 Critical / 0 High. Do **not** reopen #38 / #39 / #40.

### Medium — pre-prod / GA hardening

| ID | Finding | Owner | Action |
|----|---------|-------|--------|
| **M-K6-C3-1** | **Email/phone PII plaintext at rest** — Spec 12 §3.1 requires Encrypted; `users.email` / `users.phone_number` (+ OTP challenge + email outbox) are plain VARCHAR with no converter | Vikram | AES-256-GCM (or column-level KMS) for email/phone; searchable hash/blind-index for login lookup; migrate existing rows |
| **M-K6-C3-2** | **Bank/UPI encrypt-before-ship gate** — no creator bank/UPI persistence yet; Spec §3.1/§4 requires Encrypted + audit + cool-down. First bank-onboarding PR must ship AES-GCM + change audit or be rejected | Vikram | Block bank-onboarding merge until encrypt + audit + 24h cool-down land |
| **M-K6-C3-3** | **No virus/malware scan on uploads** — Spec §5.1 `containsMaliciousContent` / virus scan absent on deliverable + portfolio paths | Vikram / Meera | Async ClamAV (or equivalent) gate before object is brand-visible; quarantine bucket optional |
| **M-K6-C3-4** | **Portfolio cover upload under-hardened** — `PortfolioService.uploadCover`: Content-Type header only (no `MediaMimeSniffer`), `file.getBytes()`, `publicUrl` (not time-limited presign). Cycle-2 #40 explicitly deferred this | Vikram | Reuse deliverable validators + streamToR2 pattern; store object key; serve via `presignGet` |
| **M-K6-C3-5** | **OAuth/Graph error bodies logged** — `MetaOAuthService`, `ShopifyOAuthService`, `MetaGraphApiClient` log full response bodies on failure (cycle-2 carry). Defense-in-depth: never log OAuth/token-adjacent bodies | Vikram | Log status + safe error code only; redact/omit body |

### Low — next sprint

| ID | Finding | Action |
|----|---------|--------|
| **L-K6-C3-1** | Spec §3.3 right-to-deletion service not implemented | Track for GDPR/account-delete epic (ties to caption purge ADR) |
| **L-K6-C3-2** | EXIF metadata not stripped from image uploads | Strip on ingest (deliverable + portfolio) |
| **L-K6-C3-3** | MIME family match allows declared JPEG + sniffed PNG | Tighten to exact sniffed allow-list if product allows |
| **L-K6-C3-4** | Meta Graph `access_token` in query string | Prefer Authorization header if Meta supports; ensure access logs never record query | 
| **L-K6-C3-5** | Meta OAuth `client_secret` in authorize/token query URLs | Avoid URI logging; prefer POST body where API allows |
| **L-K6-C3-6** | Avatar/cover/custom-link + UTM http(s) URLs allow arbitrary external hosts (stored XSS / phishing open-redirect residual; not server SSRF) | Scheme allow-list + optional host allow-list / warning |

### Explicitly NOT reopened

| Item | Status |
|------|--------|
| #38 creator-disputes FE | ✅ SHIPPED/CONDITIONAL — untouched |
| #39 `AuthRateLimitFilter` | ✅ PASS standing |
| #40 `CreatorDeliverableService` stream/presign/proof | ✅ PASS standing; portfolio gap is **new** C3-4 only |
| M-K6-C2-1…5 | ✅ CLOSED — not reopened |
| M-K6-2 Redis cluster limiter | P1 non-blocking (unchanged) |

---

## OWASP Top 10 × Creator Surfaces — Cycle 3 Update

| OWASP | Cycle 2 | Cycle 3 |
|-------|---------|---------|
| **A02 Cryptographic Failures** | OAuth/password ✅; PII unverified | PII probed — **FAIL** email/phone (**M-K6-C3-1**); bank gate **M-K6-C3-2** |
| **A08 Software/Data Integrity** | Webhooks ✅ | Upload malware gate **FAIL** (**M-K6-C3-3**); portfolio MIME **FAIL** (**M-K6-C3-4**) |
| **A09 Logging/Monitoring** | Partial | OAuth body-log **FAIL** (**M-K6-C3-5**) |
| **A10 SSRF** | Partial | URL map complete — **PASS** (Shopify gated; no other user-host dialers) |

### Spec 12 §8 coverage

| Area | Cycle 1 | Cycle 2 | Cycle 3 |
|------|---------|---------|---------|
| Authentication | 2/5 | 5/5 | — |
| OAuth | 1/4 | 4/4 | token-log residual **M-K6-C3-5** |
| Content | 2/4 | — | **4/4 probed** (malware ❌, portfolio MIME ❌, SSRF URL ✅, deliverable MIME ✅) |
| PII / Privacy §3 | — | — | **probed** — encrypt gaps filed |

---

## Files Reviewed

**PII:**
- `influora-api/src/main/java/com/influora/domain/entity/User.java`
- `influora-api/src/main/java/com/influora/domain/entity/EmailOtpChallenge.java`
- `influora-api/src/main/resources/db/migration/V2__core_auth.sql`, `V5__email_otp.sql`, `V18__email_outbox.sql`
- `influora-api/src/main/java/com/influora/service/PayoutService.java`
- `influora-api/src/main/java/com/influora/integration/razorpay/RazorpayXClient.java`

**Upload:**
- `influora-api/src/main/java/com/influora/service/CreatorDeliverableService.java`
- `influora-api/src/main/java/com/influora/common/MediaMimeSniffer.java`
- `influora-api/src/main/java/com/influora/service/portfolio/PortfolioService.java`
- `influora-api/src/main/java/com/influora/web/PortfolioController.java`

**OAuth / SSRF:**
- `influora-api/src/main/java/com/influora/integration/meta/oauth/{MetaOAuthService,MetaTokenStorage}.java`
- `influora-api/src/main/java/com/influora/integration/meta/client/MetaGraphApiClient.java`
- `influora-api/src/main/java/com/influora/integration/shopify/oauth/ShopifyOAuthService.java`
- `influora-api/src/main/java/com/influora/integration/ai/BrandSafetyAiClient.java`
- `influora-api/src/main/java/com/influora/integration/woocommerce/WooCommerceSiteUrl.java`
- `influora-api/src/main/java/com/influora/service/tracking/CampaignLinkService.java`
- `influora-api/src/main/java/com/influora/web/ConversionWebhookController.java`

---

## Pipeline Routing

```
K6-3 (this doc) ──► PASS WITH FINDINGS (0C/0H/5M/6L)
        │
        ├──► #38/#39/#40 — DO NOT REOPEN (standing SHIPPED/CONDITIONAL)
        ├──► Vikram: M-K6-C3-1 (PII encrypt) · M-K6-C3-4 (portfolio upload) · M-K6-C3-5 (log redact)
        ├──► Vikram: M-K6-C3-2 bank encrypt-before-ship gate (on first bank PR)
        ├──► Vikram/Meera: M-K6-C3-3 malware scan
        ├──► Kabir K-GA-5 / K6-4: dependency CVE + denylist audit (next)
        └──► Priya: GA security still needs C3 Medium close-outs + K6-4 + M-K6-2 (P1)
```

**Security matrix:** ~75% → **~80%** (PII/upload/SSRF probes complete; 5 Mediums open block ~80%→~90%+).

---

*Kabir Singh, Offensive Security / Red-Team Lead — Sage Digital — Tick #37 K-GA-4 K6-3*
