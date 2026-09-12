#!/usr/bin/env bash
# Gate for the class of defect that hid META_TOKEN_ENCRYPTION_KEY on the Hostinger stack.
#
# WHAT HAPPENED. deploy/hostinger/docker-compose.hostinger.yml forwarded three of the four
# fail-closed AES token-encryption keys and omitted Meta's. Both deploy compose files use an
# EXPLICIT `environment:` map, so a var the map does not list can never reach the container — the
# property falls back to its application.yml default no matter what ops puts in the project .env.
# For META_TOKEN_ENCRYPTION_KEY that default is empty, MetaTokenStorage is an eager @Service, and
# its constructor throws IllegalStateException on a blank key: the API could not boot at all. The
# six Meta OAuth credentials were missing from the same map, which is what made the omission read
# as "Instagram is off here" rather than "this file has never started".
#
# Nothing reported it. The file is internally consistent, valid YAML, `docker compose config` exits
# 0, and every unit test passes — because no test and no gate compares a compose file's env surface
# against what the application actually binds. It surfaced only because the file had never been
# deployed (verified 2026-09-12: no `influora` project on Hostinger VPS 1844961, only a stopped
# `influora-test`), so no boot had ever been attempted to fail.
#
# WHY THIS SHAPE. The two stacks are the SAME application, so their forwarded surfaces must agree.
# This gate extracts both sets from the files themselves and compares them to each other — never to
# a list written into this gate, which is how it avoids becoming a third copy that goes stale the
# next time a var is added. It therefore stays correct as the app grows: add a var to one compose
# file and forget the other, and this goes red.
#
# DIRECTION. Utho is the live production surface, so it is the reference: anything Utho forwards
# that Hostinger does not is a defect. The reverse is only reported, not failed — Hostinger runs
# Caddy and has vars Utho legitimately lacks.
#
# NOT COVERED: whether a forwarded var holds a correct VALUE (that is ops + the startup
# validators), and Spring relaxed binding — INFLUORA_ADMIN_MFASECRETENCRYPTIONKEY binds the same
# property as ADMIN_MFA_SECRET_ENCRYPTION_KEY, so a var can be absent under one spelling and
# present under another. That is why the app-binds set is used to SCOPE the comparison rather than
# to demand every bound var be forwarded: 61 bound vars are defaulted tunables neither file
# forwards, which is correct.
#
# exit 0 = proved · 1 = broken · 2 = unavailable (never green)
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
YAML="$ROOT/influora-api/src/main/resources/application.yml"
HOSTINGER="$ROOT/deploy/hostinger/docker-compose.hostinger.yml"
UTHO="$ROOT/deploy/utho/docker-compose.utho.yml"

for f in "$YAML" "$HOSTINGER" "$UTHO"; do
  if [ ! -f "$f" ]; then
    echo "UNAVAILABLE: expected file missing: $f"
    exit 2
  fi
done
if ! command -v python >/dev/null 2>&1; then
  echo "UNAVAILABLE: python not on PATH - this gate cannot report green without running"
  exit 2
fi
if ! python -c "import yaml" >/dev/null 2>&1; then
  echo "UNAVAILABLE: PyYAML not importable - a compose file cannot be parsed by grep alone"
  exit 2
fi

# Heredoc is quoted ('PYEOF') so the shell does not touch backslashes or ${...} inside it: the
# regex below contains both, and an unquoted heredoc silently eats one level of escaping.
python - "$YAML" "$HOSTINGER" "$UTHO" <<'PYEOF'
import re
import sys

import yaml

yaml_path, hostinger_path, utho_path = sys.argv[1:4]

# ${VAR}, ${VAR:default}, ${VAR:${nested}} -- capture the NAME only.
name_re = re.compile(r"\$\{([A-Z][A-Z0-9_]*)[:}]")

bound = set()
with open(yaml_path, encoding="utf-8") as fh:
    for line in fh:
        # A var named only in a comment binds to nothing; counting it would be a false hit.
        if line.lstrip().startswith("#"):
            continue
        bound.update(name_re.findall(line))

if not bound:
    print("UNAVAILABLE: extracted zero env vars from application.yml - the extraction is broken,")
    print("             not the config. Check the ${VAR} pattern before trusting a green here.")
    sys.exit(2)


def forwarded(path):
    with open(path, encoding="utf-8") as fh:
        doc = yaml.safe_load(fh)
    env = doc["services"]["influora-api"].get("environment") or {}
    return set(env)


host = forwarded(hostinger_path)
utho = forwarded(utho_path)

missing = sorted((bound & utho) - host)
extra = sorted((bound & host) - utho)

if missing:
    print("BROKEN: docker-compose.hostinger.yml does not forward %d var(s) that Utho forwards and" % len(missing))
    print("        application.yml binds. An explicit `environment:` map only passes keys it lists,")
    print("        so these resolve to their yaml default on that stack whatever ops sets:")
    for var in missing:
        print("          %s" % var)
    print("        If any is a fail-closed secret (a *_ENCRYPTION_KEY / *_SECRET with no yaml")
    print("        default), that stack cannot boot - see MetaTokenStorage's constructor.")
    sys.exit(1)

print("PROVED: both deploy compose files forward the same %d application-bound env vars" % len(bound & utho))
print("        to influora-api; nothing Utho passes is dropped on Hostinger.")
if extra:
    print("        (Hostinger additionally forwards %d Utho does not, which is allowed: %s)"
          % (len(extra), ", ".join(extra)))
sys.exit(0)
PYEOF
exit $?
