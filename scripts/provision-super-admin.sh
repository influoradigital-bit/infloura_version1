#!/usr/bin/env bash
# ADMIN-BOOTSTRAP-0829 permanent fix #1 — provisions the very FIRST admin_users row (SUPER_ADMIN),
# with MFA already enrolled, so it can never recreate the mfaEnabled=false + ADMIN_MFA_ENFORCE_ON_LOGIN
# login-lockout deadlock that bit influoradigital@gmail.com. See AdminUser.java's javadoc on
# `create()` and com.influora.ops.ProvisionSuperAdminRunner's class javadoc for the full writeup.
#
# ONE-TIME USE: refuses to run if admin_users already has any row. For every admin after the first,
# use the SUPER_ADMIN-only POST /admin/auth/mfa/reset/{targetAdminId} endpoint to recover a locked-out
# account instead of re-running this script.
#
# Prereqs (same as scripts/register-test-brand.sh):
#   1. MySQL running + Flyway migrations applied
#   2. influora-api built: mvn -o -q -f influora-api package -DskipTests
#   3. The app's normal DB / MFA-encryption-key env vars already exported (DATABASE_URL or
#      SPRING_DATASOURCE_*, INFLUORA_ADMIN_MFA_SECRET_ENCRYPTION_KEY / whatever application.yml maps
#      influora.admin.mfa-secret-encryption-key from) — this script does not invent new ones.
#
# Required env vars for THIS script:
#   ADMIN_BOOTSTRAP_EMAIL      email for the new SUPER_ADMIN
#   ADMIN_BOOTSTRAP_PASSWORD   plaintext password (>= 12 chars) — hashed with the app's real
#                              BCrypt(strength=12) PasswordEncoder, never rolled by hand here
#
# Usage:
#   ADMIN_BOOTSTRAP_EMAIL="ops@influora.in" ADMIN_BOOTSTRAP_PASSWORD="a-strong-passphrase-here" \
#     ./scripts/provision-super-admin.sh

set -euo pipefail

if [[ -z "${ADMIN_BOOTSTRAP_EMAIL:-}" || -z "${ADMIN_BOOTSTRAP_PASSWORD:-}" ]]; then
  echo "ADMIN_BOOTSTRAP_EMAIL and ADMIN_BOOTSTRAP_PASSWORD must both be set. Aborting." >&2
  exit 1
fi

JAR="$(ls "$(dirname "$0")"/../influora-api/target/influora-api-*.jar 2>/dev/null | grep -v sources | head -n1 || true)"
if [[ -z "$JAR" ]]; then
  echo "No influora-api jar found under influora-api/target/. Build it first:" >&2
  echo "  mvn -o -q -f influora-api package -DskipTests" >&2
  exit 1
fi

# -Dops.provision-super-admin=true activates ProvisionSuperAdminRunner (@ConditionalOnProperty —
# inert on every other boot of this app, including all existing tests).
# -Dspring.main.web-application-type=none skips starting Tomcat for this one-shot DB write.
# -Dspring.main.lazy-initialization=true keeps @Scheduled beans (EmailWorker, MetricsPollingJob,
# etc.) uninstantiated for this run's short lifetime — see the runner's class javadoc for why that
# matters here (this script cannot change InfluoraApiApplication.java to disable @EnableScheduling
# outright; that file is out of this task's scope).
java \
  -Dops.provision-super-admin=true \
  -Dspring.main.web-application-type=none \
  -Dspring.main.lazy-initialization=true \
  -Dspring.main.banner-mode=off \
  -jar "$JAR"
