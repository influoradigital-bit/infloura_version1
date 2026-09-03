#!/usr/bin/env bash
# Influora .env generator -- run ON the Utho box:   bash generate-env.sh
#
# Generates the 16 values no vendor can give you; leaves REPLACE_ME for the rest.
# Refuses to overwrite an existing .env.
#
# Formats are enforced at runtime, not cosmetic:
#   * 4 AES keys: base64 of EXACTLY 32 bytes (AesGcmCipher.decodeKey throws otherwise)
#   * JWKS pair: EC P-256, PKCS#8 private / X.509 public, stored single-line with
#     literal \n escapes -- decodePemBody() converts those back to newlines
#   * signing secrets: >=32 bytes and all DISTINCT
set -euo pipefail

ENV_PATH="${1:-/opt/influora/.env}"
if [ -e "$ENV_PATH" ]; then echo "REFUSING: $ENV_PATH exists. Move it aside first." >&2; exit 1; fi
mkdir -p "$(dirname "$ENV_PATH")"
umask 077
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT

openssl ecparam -name prime256v1 -genkey -noout -out "$TMP/ec.key" 2>/dev/null
openssl pkcs8 -topk8 -nocrypt -in "$TMP/ec.key" -out "$TMP/priv.pem" 2>/dev/null
openssl ec -in "$TMP/ec.key" -pubout -out "$TMP/pub.pem" 2>/dev/null
NLIT='\\n'
JWKS_PRIV="$(awk -v n="$NLIT" '{printf "%s%s", $0, n}' "$TMP/priv.pem")"
JWKS_PUB="$(awk -v n="$NLIT" '{printf "%s%s", $0, n}' "$TMP/pub.pem")"

aes32()  { openssl rand -base64 32; }
secret() { openssl rand -base64 48 | tr -d "=+/" | cut -c1-48; }
pw()     { openssl rand -hex 24; }

cat > "$ENV_PATH" <<ENVEOF
# Influora production env -- generated $(date -u +%Y-%m-%dT%H:%M:%SZ)
# NEVER commit this file.

ROOT_DOMAIN=influora.in
APP_DOMAIN=app.influora.in
API_DOMAIN=api.influora.in
AI_DOMAIN=ai.influora.in
ACME_EMAIL=REPLACE_ME
TRUSTED_PROXIES=172.16.0.0/12

# ---- generated, do not edit ----
MYSQL_ROOT_PASSWORD=$(pw)
MYSQL_PASSWORD=$(pw)
JWT_ACCESS_SECRET=$(secret)
JWT_REFRESH_SECRET=$(secret)
MEERA_STREAM_SIGNING_SECRET=$(secret)
INTERNAL_SERVICE_TOKEN_SECRET=$(secret)
INTERNAL_REQUEST_HMAC_SECRET=$(secret)
INFLUORA_BRANDSAFETYSERVICETOKEN_SIGNINGSECRET=$(secret)
INFLUORA_PII_EMAILPHONEENCRYPTIONKEY=$(aes32)
INFLUORA_PII_BANKENCRYPTIONKEY=$(aes32)
INFLUORA_ADMIN_MFASECRETENCRYPTIONKEY=$(aes32)
META_TOKEN_ENCRYPTION_KEY=$(aes32)
INFLUORA_SHOPIFY_TOKENENCRYPTIONKEY=$(aes32)
INFLUORA_WOOCOMMERCE_TOKENENCRYPTIONKEY=$(aes32)
INFLUORA_CONVERSIONWEBHOOK_TOKENENCRYPTIONKEY=$(aes32)
INFLUORA_JWKS_PRIVATEKEYPEM="$JWKS_PRIV"
INFLUORA_JWKS_PUBLICKEYPEM="$JWKS_PUB"
UNSUBSCRIBE_SIGNING_SECRET=$(secret)
TREND_TAG_INGEST_SECRET=$(secret)
# Q5.5 (T-CREATORCONNECT-0902) -- signs the creator-invite link consumed by
# RegistrationService#consumeInviteToken. InviteTokenService.java fails CLOSED (rejects every
# token) outside the dev/test Spring profile if this is ever left at its committed
# dev-...-min-32-chars default or shorter than 32 chars -- same random-secret mechanism as the
# JWT/other signing secrets above, so a fresh box never boots on the forgeable literal.
CREATOR_INVITE_TOKEN_SECRET=$(secret)

# ---- paste your own ----
# NOTE: these three MUST use the exact literal SecretsStartupValidator rejects
# (application.yml's own ${VAR:default}), not a generic REPLACE_ME -- otherwise an
# unfilled placeholder boots clean with a non-functional Razorpay integration (F-0390 A4).
RAZORPAY_KEY_ID=rzp_test_REPLACE_WITH_YOUR_KEY
RAZORPAY_KEY_SECRET=REPLACE_WITH_YOUR_RAZORPAY_SECRET
RAZORPAY_WEBHOOK_SECRET=REPLACE_WITH_YOUR_WEBHOOK_SECRET
RAZORPAYX_ACCOUNT_NUMBER=REPLACE_ME
MSG91_AUTH_KEY=REPLACE_ME
MSG91_TOKEN_AUTH=REPLACE_ME
MSG91_WIDGET_ID=REPLACE_ME
MSG91_OTP_TEMPLATE_ID=REPLACE_ME
MSG91_WELCOME_TEMPLATE_ID=REPLACE_ME
MSG91_EMAIL_TRANSACTIONAL_TEMPLATE_ID=REPLACE_ME
# Deploy TLD is .in, not the application.yml default of .com (F-0390 A5).
# SENDING DOMAIN = mail.influora.in (INTERIM, chosen 2026-09-02). Both influora.in and
# mail.influora.in are verified inside MSG91, but only the subdomain is DNS-complete: it has
# include:mailer91.com in its SPF and a live DKIM key at spaceship._domainkey.mail.influora.in,
# so MSG91's d=mail.influora.in signature actually resolves and DMARC p=quarantine passes.
# The apex has NEITHER record yet -- sending as @influora.in today authenticates nowhere and
# Gmail quarantines it, even though the MSG91 dashboard reports the domain as verified.
# TO SWITCH TO APEX: import influora.in.apex-msg91.txt in GoDaddy (adds apex SPF include +
# DKIM + mailer91 CNAME, leaves the Hostinger apex MX alone), wait for the 3600s TTL, confirm
#   nslookup -type=txt spaceship._domainkey.influora.in 8.8.8.8
# returns the key, THEN set both vars below to the apex. Never point the apex MX at mailer91 --
# that is Hostinger's official mailbox delivery.
MSG91_EMAIL_DOMAIN=mail.influora.in
MSG91_FROM_EMAIL=noreply@mail.influora.in
MSG91_FROM_NAME=Influora
MSG91_EMAIL_COMPANY_NAME=Influora
# MSG91's SMTP relay (Msg91EmailClient) -- SMTP_HOST unset means Spring never creates a
# JavaMailSender bean at all, i.e. all outbound email silently dead (F-0390 A2).
SMTP_HOST=smtp.mailer91.com
# 465 (implicit TLS), NOT the conventional 587: Utho DROPS outbound 587, verified from the VPS
# on 2026-09-02 -- it fails as SocketTimeoutException "Connect timed out", never as a refusal
# or an auth error, so it reads like an MSG91 outage when it is purely egress. 465 is open and
# serves a valid cert (CN=smtp.mailer91.com, AUTH LOGIN PLAIN).
SMTP_PORT=465
# The mailbox MSG91 provisions on the SENDING domain, e.g. emailer@mail.influora.in. This is
# why MSG91_FROM_EMAIL must stay on mail.influora.in: the relay authenticates that domain,
# and a MAIL FROM outside it comes back 550 5.7.1 Invalid Mail From address received.
SMTP_USERNAME=REPLACE_ME
SMTP_PASSWORD=REPLACE_ME
# STARTTLS must stay false on 465: the session is already encrypted before the banner, and the
# server still advertises 250-STARTTLS, so leaving this true makes JavaMail renegotiate TLS
# inside TLS and the connection dies. On 587 the pair inverts (STARTTLS=true, SSL=false).
SMTP_STARTTLS_ENABLE=false
SMTP_SSL_ENABLE=true
R2_ACCOUNT_ID=REPLACE_ME
R2_ACCESS_KEY_ID=REPLACE_ME
R2_SECRET_ACCESS_KEY=REPLACE_ME
R2_BUCKET_NAME=REPLACE_ME
R2_ENDPOINT=REPLACE_ME
R2_PUBLIC_URL=REPLACE_ME
ANTHROPIC_API_KEY=REPLACE_ME
GEMINI_API_KEY=REPLACE_ME
SARVAM_API_KEY=REPLACE_ME
META_APP_ID=REPLACE_ME
META_APP_SECRET=REPLACE_ME
META_INSTAGRAM_APP_ID=REPLACE_ME
META_INSTAGRAM_APP_SECRET=REPLACE_ME
# Meta redirects the BROWSER here, so this must be the SPA route, not an API path. The React page
# (src/pages/creator-meta-callback.tsx, routed at App.tsx) reads code/state off its own query
# string and calls GET /meta/oauth/callback itself as an authenticated XHR. Pointing this at the
# API instead returns UNAUTHENTICATED every time: that endpoint requires @AuthenticationPrincipal
# and Meta's redirect carries no bearer token. One route serves both auth paths — the backend
# recovers which one from the state token. Must match a Valid OAuth Redirect URI byte-for-byte.
META_REDIRECT_URI=https://app.influora.in/creator/settings/meta/callback
META_INSTAGRAM_REDIRECT_URI=https://app.influora.in/creator/settings/meta/callback
# T-CREATORCONNECT-0902 — CreatorMarketplaceClient gate. OFF: instagram_creator_marketplace_discovery
# cannot even be App-Reviewed yet (wiki/decisions/2026-09-02-what-we-need.md); ExternalCreatorService
# falls back to the external_creators table (Business Discovery + admin import) regardless.
META_CREATOR_MARKETPLACE_ENABLED=false
# Q1.3 (T-CREATORCONNECT-0902) — Influora-owned IG Business system caller for Business Discovery
# lookups, preferred ahead of borrowing a live creator's own token (which throttles that creator's
# own MetricsPollingJob — see MetaApiProperties#systemIgUserId javadoc). Blank by default, same
# off-by-default convention as every other Meta property here: ExternalCreatorService falls
# through to the pre-existing creator-token path exactly as before when either is unset. No
# REPLACE_ME — a literal REPLACE_ME would reach InstagramInsightsClient as a real ig-user-id/token
# and fail confusingly, same reasoning as ADMIN_NOTIFICATION_EMAIL below. Cross-user token reuse on
# the fallback path this reduces reliance on still needs a platform-terms ruling from Swapnil
# before being relied on in production; provisioning a real system caller here is what lets that
# fallback path stop being exercised.
META_SYSTEM_IG_USER_ID=
META_SYSTEM_IG_ACCESS_TOKEN=
# T-CREATORCONNECT-0902 — recipient for admin.creator_connection_requested. No REPLACE_ME
# here on purpose: nothing in this codebase validates against that literal (grepped, zero
# hits), so an un-edited REPLACE_ME would reach Msg91EmailClient as a literal "to" address
# and fail confusingly. Blank is a real, handled value: NotificationListener logs a WARN and
# skips sending. Fill in a real address to actually receive these emails.
ADMIN_NOTIFICATION_EMAIL=
# CompanyTaxStartupValidator REJECTS placeholders -- the API will not boot until these are real
INFLUORA_COMPANY_GSTIN=REPLACE_ME
INFLUORA_LEGAL_NAME=REPLACE_ME
INFLUORA_COMPANY_ADDRESS=REPLACE_ME
ENVEOF

chmod 600 "$ENV_PATH"
echo "wrote $ENV_PATH"
echo "still REPLACE_ME: $(grep -c REPLACE_ME "$ENV_PATH")"
echo "AES keys must each read 32 bytes:"
for k in INFLUORA_PII_EMAILPHONEENCRYPTIONKEY INFLUORA_PII_BANKENCRYPTIONKEY INFLUORA_ADMIN_MFASECRETENCRYPTIONKEY META_TOKEN_ENCRYPTION_KEY INFLUORA_SHOPIFY_TOKENENCRYPTIONKEY INFLUORA_WOOCOMMERCE_TOKENENCRYPTIONKEY INFLUORA_CONVERSIONWEBHOOK_TOKENENCRYPTIONKEY; do v=$(grep "^$k=" "$ENV_PATH" | cut -d= -f2-); n=$(printf %s "$v" | base64 -d 2>/dev/null | wc -c); echo "  $k -> $n bytes"; done
