#!/usr/bin/env bash
# Install the ai.influora.in nginx vhost + Let's Encrypt certificate on the Utho box.
#
#   scp deploy/utho/install-ai-vhost.sh root@150.241.245.242:/root/
#   ssh root@150.241.245.242 'bash /root/install-ai-vhost.sh'
#
# SAFETY CONTRACT - this box is also the Cloudflare origin for snapsby.com on the same
# nginx. Everything below is additive and reversible:
#   * touches only /etc/nginx/sites-{available,enabled}/ai-influora.conf
#   * never edits, disables or reloads over the default vhost
#   * `nginx -t` gates EVERY reload; a failed test aborts and restores the previous
#     state, leaving snapsby.com serving the config it already had
#   * certbot runs in --webroot mode, so nginx is never stopped
#   * a --dry-run is issued before the real request, because Let's Encrypt allows only
#     5 failures per domain per week and a burnt budget blocks retries
#
# Idempotent: safe to re-run. A valid cert already present skips issuance.

set -euo pipefail

DOMAIN="ai.influora.in"
WEBROOT="/var/www/html"
AVAIL="/etc/nginx/sites-available/ai-influora.conf"
ENABLED="/etc/nginx/sites-enabled/ai-influora.conf"
LIVE="/etc/letsencrypt/live/${DOMAIN}"
BACKUP="/root/ai-vhost-backup-$(date +%Y%m%d-%H%M%S)"

say()  { printf '\n==> %s\n' "$*"; }
ok()   { printf '    ok   %s\n' "$*"; }
warn() { printf '    WARN %s\n' "$*"; }
die()  { printf '\nABORT: %s\n' "$*" >&2; exit 1; }

rollback() {
  warn "rolling back"
  rm -f "$ENABLED" "$AVAIL"
  if [ -d "$BACKUP" ]; then
    if [ -f "$BACKUP/ai-influora.conf.available" ]; then
      cp "$BACKUP/ai-influora.conf.available" "$AVAIL"
    fi
    if [ -f "$BACKUP/ai-influora.conf.enabled" ]; then
      cp "$BACKUP/ai-influora.conf.enabled" "$ENABLED"
    fi
  fi
  if nginx -t >/dev/null 2>&1; then
    systemctl reload nginx || true
  fi
  warn "restored; snapsby.com and the apex are untouched"
}

# ---------------------------------------------------------------------------
say "0 . Preflight"

[ "$(id -u)" -eq 0 ] || die "must run as root"
command -v nginx   >/dev/null || die "nginx not found"
command -v certbot >/dev/null || die "certbot not found - apt-get install -y certbot"
ok "root, nginx, certbot present"

nginx -t >/dev/null 2>&1 || die "nginx config is ALREADY broken before any change. Fix that first - do not layer this on top."
ok "existing nginx config tests clean"

[ -d "$WEBROOT" ] || die "$WEBROOT does not exist; certbot --webroot needs it"
ok "webroot $WEBROOT exists"

# This vhost points at Caddy. If Caddy is not up the install "succeeds" and every Meera
# request 502s - check now rather than discovering it in a browser.
if ss -lnt 2>/dev/null | grep -q '127\.0\.0\.1:8080'; then
  ok "something is listening on 127.0.0.1:8080 (expected: Caddy)"
else
  warn "NOTHING is listening on 127.0.0.1:8080."
  warn "The Influora docker stack is probably not running on this box - which matches"
  warn "the apex being served statically from /var/www/influora."
  warn "This vhost will install correctly and then 502 until you run:"
  warn "    cd /opt/influora && docker compose up -d"
  # Installing the vhost is still the right move even with the stack down - it is a
  # prerequisite either way. But make the operator say so, and stay non-interactive-safe:
  # the documented invocation is `ssh host 'bash script'`, where stdin is NOT a tty and a
  # bare `read` hits EOF and would abort the run under `set -e`.
  if [ "${ASSUME_YES:-}" = "1" ]; then
    warn "ASSUME_YES=1 - continuing"
  elif [ -t 0 ]; then
    reply=""
    read -r -p "    Continue anyway? [y/N] " reply || true
    [ "${reply:-N}" = "y" ] || die "stopped at your request"
  else
    die "stack appears down and this is a non-interactive run. Re-run with ASSUME_YES=1 to install the vhost anyway, or bring the stack up first."
  fi
fi

# A second enabled file claiming this server_name makes nginx silently pick one.
CONFLICT="$(grep -rl "server_name.*ai\.influora\.in" /etc/nginx/sites-enabled/ 2>/dev/null | grep -v 'ai-influora.conf' || true)"
if [ -n "$CONFLICT" ]; then
  die "another ENABLED vhost already declares ${DOMAIN}: ${CONFLICT} - resolve that first. Two enabled files with the same server_name is the 2026-08-21 fault."
fi
ok "no conflicting server_name for ${DOMAIN}"

mkdir -p "$BACKUP"
if [ -f "$AVAIL" ]; then cp "$AVAIL" "$BACKUP/ai-influora.conf.available"; fi
if [ -e "$ENABLED" ]; then cp -P "$ENABLED" "$BACKUP/ai-influora.conf.enabled"; fi
ls /etc/nginx/sites-enabled/ > "$BACKUP/sites-enabled.txt"
ok "backup at $BACKUP"

# ---------------------------------------------------------------------------
say "1 . Phase 1 - HTTP-only vhost so certbot can answer the ACME challenge"
# The HTTPS block cannot go in yet: it references a certificate that does not exist,
# so nginx -t would fail and the reload that serves the challenge could never happen.

cat > "$AVAIL" <<'PHASE1'
server {
    listen 80;
    listen [::]:80;
    server_name ai.influora.in;

    location ^~ /.well-known/acme-challenge/ {
        root /var/www/html;
    }

    location / {
        return 301 https://$host$request_uri;
    }
}
PHASE1

ln -sfn "$AVAIL" "$ENABLED"

if ! nginx -t; then rollback; die "nginx -t failed on the HTTP-only vhost"; fi
systemctl reload nginx
ok "HTTP vhost live; ${DOMAIN}:80 now serves the ACME webroot"

# ---------------------------------------------------------------------------
say "2 . Certificate"

if [ -d "$LIVE" ] && openssl x509 -checkend 604800 -noout -in "$LIVE/fullchain.pem" >/dev/null 2>&1; then
  ok "valid certificate already present at $LIVE - skipping issuance"
else
  say "2a . certbot --dry-run (protects the 5-failures-per-week budget)"
  if ! certbot certonly --webroot -w "$WEBROOT" -d "$DOMAIN" --dry-run \
        --non-interactive --agree-tos --register-unsafely-without-email; then
    rollback
    die "certbot dry-run failed. Fix the cause before a real request - usually DNS not pointing here, or port 80 for ${DOMAIN} not reaching this webroot."
  fi
  ok "dry-run passed"

  say "2b . issuing the real certificate"
  if ! certbot certonly --webroot -w "$WEBROOT" -d "$DOMAIN" \
        --non-interactive --agree-tos --keep-until-expiring; then
    rollback
    die "certbot issuance failed"
  fi
  ok "certificate issued to $LIVE"
fi

# ---------------------------------------------------------------------------
say "3 . Phase 2 - add the HTTPS/SSE block"

cat > "$AVAIL" <<'PHASE2'
server {
    listen 80;
    listen [::]:80;
    server_name ai.influora.in;

    location ^~ /.well-known/acme-challenge/ {
        root /var/www/html;
    }

    location / {
        return 301 https://$host$request_uri;
    }
}

server {
    listen 443 ssl;
    listen [::]:443 ssl;
    http2 on;
    server_name ai.influora.in;

    ssl_certificate     /etc/letsencrypt/live/ai.influora.in/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/ai.influora.in/privkey.pem;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;

        # Caddy routes by Host. Rewrite Host here and the request lands in the wrong
        # Caddy site block, or none.
        proxy_set_header Host              $host;
        proxy_set_header X-Real-IP         $remote_addr;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto https;
        proxy_set_header X-Forwarded-Host  $host;

        # THE SSE SET - Caddy's `flush_interval -1` expressed in nginx. Without these
        # three, Meera's token-by-token stream is buffered and the user sees nothing
        # until the turn completes, which reads as a hang, not a bug.
        proxy_buffering off;
        proxy_cache off;
        proxy_set_header Connection '';

        # A long stream must not be cut at nginx's 60s default.
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;
    }
}
PHASE2

if ! nginx -t; then rollback; die "nginx -t failed on the full vhost"; fi
systemctl reload nginx
ok "HTTPS vhost live"

# ---------------------------------------------------------------------------
say "4 . Verify"

echo "    certificate presented for ${DOMAIN}:"
echo | openssl s_client -connect "${DOMAIN}:443" -servername "$DOMAIN" 2>/dev/null \
  | openssl x509 -noout -subject 2>/dev/null | sed 's/^/      /' || warn "could not read cert"
echo "      (must say CN=${DOMAIN}; if it still says CN=snapsby.com the vhost is not matching)"

echo
echo "    POST https://${DOMAIN}/chat :"
code="$(curl -s -o /dev/null -w '%{http_code}' -m 20 -X POST \
        -H 'Content-Type: application/json' -d '{}' "https://${DOMAIN}/chat" || echo 000)"
echo "      HTTP $code"
case "$code" in
  401|422|400) ok "FastAPI is answering - this is the correct result (auth/validation rejection)" ;;
  405)     warn "still 405 - nginx is still not routing this host to the app" ;;
  502|503) warn "502/503 - vhost is correct but Caddy or influora-ai is down: cd /opt/influora && docker compose up -d" ;;
  200)     warn "200 on an empty unauthenticated body is suspicious - confirm you are not hitting snapsby" ;;
  *)       warn "unexpected $code" ;;
esac

say "Done. Remaining step, NOT done by this script:"
cat <<'NEXT'
    Point the API at the new host and restart it:

      cd /opt/influora
      grep -n MEERA .env
      # set:  MEERA_PUBLIC_CHAT_URL=https://ai.influora.in/chat
      docker compose up -d influora-api

    Until then Spring keeps handing browsers https://influora.in/meera/chat,
    which is the static SPA vhost and will keep returning 405.

    Rollback:
      rm -f /etc/nginx/sites-enabled/ai-influora.conf
      nginx -t && systemctl reload nginx
NEXT
