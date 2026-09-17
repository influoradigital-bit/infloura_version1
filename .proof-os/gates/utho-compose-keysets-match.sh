#!/usr/bin/env bash
# gates/utho-compose-keysets-match.sh — origin: F-0787 (utho-shared compose silently dropped
# CREATOR_COPILOT_ENABLED + MEERA_CREATOR_ENABLED).
# The influora-api `environment:` block is an explicit map: Compose forwards ONLY the keys listed,
# whatever the host .env holds. The two Utho compose files are meant to be the same stack (the
# shared-box variant only caps the heap), so any key present in one and absent from the other is a
# flag/secret that silently never reaches the API on one of the two boxes.
# Fails (exit 1) naming every key present in one file's influora-api environment and not the other.
set -u
cd "$(dirname "$0")/../.." || { echo "· cannot reach repo root — unavailable"; exit 2; }

A=deploy/utho/docker-compose.utho.yml
B=deploy/utho/docker-compose.utho-shared.yml

# Intentional differences. Each entry MUST carry a reason; nothing else belongs here.
#   JAVA_TOOL_OPTIONS — shared box only: caps the JVM heap so it does not size itself to 25% of
#                       a host it shares with Snapsby + n8n (see the comment on that key).
ALLOW="JAVA_TOOL_OPTIONS"

[ -f "$A" ] || { echo "· $A missing — unavailable"; exit 2; }
[ -f "$B" ] || { echo "· $B missing — unavailable"; exit 2; }

# Prints the sorted key names of services.influora-api.environment. Tolerates CRLF and comments;
# stops at the first non-blank, non-comment line indented <= 4 (next service key / next service).
api_env_keys() {
  tr -d '\r' < "$1" | awk '
    /^  influora-api:[[:space:]]*$/ { in_svc = 1; in_env = 0; next }
    in_svc && /^  [^ #]/            { in_svc = 0; in_env = 0 }
    in_svc && /^    environment:[[:space:]]*$/ { in_env = 1; next }
    in_env {
      if ($0 ~ /^[[:space:]]*$/ || $0 ~ /^[[:space:]]*#/) next
      if ($0 !~ /^      /) { in_env = 0; next }
      if (match($0, /^      [A-Za-z_][A-Za-z0-9_]*:/)) {
        k = substr($0, 7, RLENGTH - 7); print k
      }
    }' | sort -u
}

TMP=$(mktemp -d) || { echo "· mktemp failed — unavailable"; exit 2; }
trap 'rm -rf "$TMP"' EXIT
api_env_keys "$A" > "$TMP/a"
api_env_keys "$B" > "$TMP/b"

NA=$(wc -l < "$TMP/a" | tr -d ' '); NB=$(wc -l < "$TMP/b" | tr -d ' ')
# Anti-vacuous: an empty-vs-empty diff is not a pass (a renamed service or re-indented file would
# otherwise green this gate forever). Both files carry ~90 keys today.
if [ "$NA" -lt 20 ] || [ "$NB" -lt 20 ]; then
  echo "· parsed only $NA keys from $A and $NB from $B — parser no longer matches the files; unavailable"
  exit 2
fi

FAIL=0
for k in $(comm -23 "$TMP/a" "$TMP/b"); do
  case " $ALLOW " in *" $k "*) echo "allowed: $k only in $A"; continue;; esac
  echo "KEY MISSING FROM $B: $k (present in $A)"; FAIL=1
done
for k in $(comm -13 "$TMP/a" "$TMP/b"); do
  case " $ALLOW " in *" $k "*) echo "allowed: $k only in $B"; continue;; esac
  echo "KEY MISSING FROM $A: $k (present in $B)"; FAIL=1
done

echo "checked: influora-api environment keys — $NA in $A, $NB in $B"
echo "NOT CHECKED: key VALUES/defaults; other services (influora-ai, caddy, mysql); the live box's env file (/usr/local/App/influora/influora.env, not in this repo — F-0842)"
[ $FAIL -eq 0 ] && echo "PASS: key sets match (modulo allow-list)"
exit $FAIL
