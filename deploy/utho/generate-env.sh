#!/usr/bin/env bash
# Influora .env generator -- run ON the Utho box:   bash generate-env.sh
#
# Generates the 14 values no vendor can give you; leaves REPLACE_ME for the rest.
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
INFLUORA_JWKS_PRIVATEKEYPEM="$JWKS_PRIV"
INFLUORA_JWKS_PUBLICKEYPEM="$JWKS_PUB"

# ---- paste your own ----
RAZORPAY_KEY_ID=REPLACE_ME
RAZORPAY_KEY_SECRET=REPLACE_ME
RAZORPAY_WEBHOOK_SECRET=REPLACE_ME
RAZORPAYX_ACCOUNT_NUMBER=REPLACE_ME
MSG91_AUTH_KEY=REPLACE_ME
MSG91_TOKEN_AUTH=REPLACE_ME
MSG91_WIDGET_ID=REPLACE_ME
MSG91_OTP_TEMPLATE_ID=REPLACE_ME
MSG91_WELCOME_TEMPLATE_ID=REPLACE_ME
MSG91_EMAIL_TRANSACTIONAL_TEMPLATE_ID=REPLACE_ME
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
META_REDIRECT_URI=https://api.influora.in/api/v1/meta/callback
META_INSTAGRAM_REDIRECT_URI=https://api.influora.in/api/v1/meta/instagram/callback
# CompanyTaxStartupValidator REJECTS placeholders -- the API will not boot until these are real
INFLUORA_COMPANY_GSTIN=REPLACE_ME
INFLUORA_LEGAL_NAME=REPLACE_ME
INFLUORA_COMPANY_ADDRESS=REPLACE_ME
ENVEOF

chmod 600 "$ENV_PATH"
echo "wrote $ENV_PATH"
echo "still REPLACE_ME: $(grep -c REPLACE_ME "$ENV_PATH")"
echo "AES keys must each read 32 bytes:"
for k in INFLUORA_PII_EMAILPHONEENCRYPTIONKEY INFLUORA_PII_BANKENCRYPTIONKEY INFLUORA_ADMIN_MFASECRETENCRYPTIONKEY META_TOKEN_ENCRYPTION_KEY; do v=$(grep "^$k=" "$ENV_PATH" | cut -d= -f2-); n=$(printf %s "$v" | base64 -d 2>/dev/null | wc -c); echo "  $k -> $n bytes"; done
