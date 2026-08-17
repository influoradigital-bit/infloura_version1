#!/usr/bin/env bash
# F-0292-signature-name-persisted.sh — gate for F-0292 (signature-name-discarded-server-side).
#
# The brand contract e-sign UI (contracts-and-deliverables.tsx) — and the creator side, which
# calls the exact same signContract() helper — gate the Sign button on a non-empty typed full
# name, send `{ name, agreedAt }` over the wire, and tell the user this typed name is the legally
# binding act ("By typing your full name and clicking 'Sign Contract' ... IT Act 2000"). The
# server previously bound that body to `ContractSignRequest(String role)` — `name` had no field
# to land in, Jackson silently dropped it, and `Contract.recordBrandSignature()` wrote only a
# timestamp. The value the copy names as the binding act was discarded.
#
# This gate asserts the SERVER side, not the client payload shape — a return-value pin on the
# client payload is the exact defect that produced this record (missed by the F-0253 gate).
#
#   exit 0 = proved · 1 = broken · 2 = unavailable
set -u
ROOT=$(cd "$(dirname "$0")/../.." 2>/dev/null && pwd) || { echo "· cannot resolve project root — unavailable"; exit 2; }
cd "$ROOT" || { echo "· project root unreadable — unavailable"; exit 2; }

DTO=influora-api/src/main/java/com/influora/web/dto/money/MoneyDtos.java
ENTITY=influora-api/src/main/java/com/influora/domain/entity/Contract.java
SERVICE=influora-api/src/main/java/com/influora/service/ContractService.java
CONTROLLER=influora-api/src/main/java/com/influora/web/ContractController.java
MIGRATIONS=influora-api/src/main/resources/db/migration

for f in "$DTO" "$ENTITY" "$SERVICE" "$CONTROLLER"; do
  [ -f "$f" ] || { echo "· $f missing — unavailable"; exit 2; }
done
[ -d "$MIGRATIONS" ] || { echo "· $MIGRATIONS missing — unavailable"; exit 2; }

echo "· request DTO: ContractSignRequest carries the signer name"
sign_req_line=$(grep -n "record ContractSignRequest" "$DTO")
if [ -z "$sign_req_line" ]; then
  echo "VERDICT: broken — ContractSignRequest record not found (F-0292)"
  exit 1
fi
if ! printf '%s\n' "$sign_req_line" | grep -qE "String +name"; then
  echo "  $sign_req_line"
  echo "VERDICT: broken — ContractSignRequest still has no name field; the typed signer name the"
  echo "         UI calls the binding act has nowhere to land server-side (F-0292)"
  exit 1
fi
echo "  clean — $sign_req_line"

echo "· entity: Contract persists brand + creator signer name as distinct columns"
if ! grep -qE "brandSignerName" "$ENTITY"; then
  echo "VERDICT: broken — Contract entity has no brandSignerName field (F-0292)"
  exit 1
fi
if ! grep -qE "creatorSignerName" "$ENTITY"; then
  echo "VERDICT: broken — Contract entity has no creatorSignerName field (F-0292)"
  exit 1
fi
if ! grep -qE '@Column\(name = "brand_signer_name"' "$ENTITY"; then
  echo "VERDICT: broken — brandSignerName is not mapped to a persisted column (F-0292)"
  exit 1
fi
if ! grep -qE '@Column\(name = "creator_signer_name"' "$ENTITY"; then
  echo "VERDICT: broken — creatorSignerName is not mapped to a persisted column (F-0292)"
  exit 1
fi
echo "  clean — brand_signer_name / creator_signer_name columns mapped"

echo "· entity: recordBrandSignature/recordCreatorSignature actually accept and write the name"
if ! grep -qE "void recordBrandSignature\([^)]*String[^)]*\)" "$ENTITY"; then
  echo "VERDICT: broken — recordBrandSignature still takes no name parameter; a signer name"
  echo "         passed in from the request has nothing to call it with (F-0292)"
  exit 1
fi
if ! grep -qE "void recordCreatorSignature\([^)]*String[^)]*\)" "$ENTITY"; then
  echo "VERDICT: broken — recordCreatorSignature still takes no name parameter (F-0292)"
  exit 1
fi
echo "  clean — both record*Signature methods take a name argument"

echo "· service: the sign flow actually threads a caller-supplied name into the entity write"
if ! grep -qE "\.recordBrandSignature\([a-zA-Z]" "$SERVICE"; then
  echo "VERDICT: broken — ContractService still calls recordBrandSignature() with no argument;"
  echo "         the entity has a name column no code path ever writes (F-0292)"
  exit 1
fi
if ! grep -qE "\.recordCreatorSignature\([a-zA-Z]" "$SERVICE"; then
  echo "VERDICT: broken — ContractService still calls recordCreatorSignature() with no argument"
  echo "         (F-0292)"
  exit 1
fi
echo "  clean — ContractService passes a name through to both record*Signature calls"

echo "· controller: sign() still server-derives role and is not broken by the new field"
if ! grep -qE '"BRAND"' "$CONTROLLER"; then
  echo "VERDICT: broken — ContractController#sign no longer server-derives the BRAND role from"
  echo "         the authenticated principal (F-0292 fix must not regress this)"
  exit 1
fi
echo "  clean — server-derived role default still present"

echo "· read model: signer names come back on the contract response"
if ! grep -qE "brandSignerName" "$DTO"; then
  echo "VERDICT: broken — ContractResponse does not carry brandSignerName; the UI has no way to"
  echo "         show who signed (F-0292)"
  exit 1
fi
if ! grep -qE "creatorSignerName" "$DTO"; then
  echo "VERDICT: broken — ContractResponse does not carry creatorSignerName (F-0292)"
  exit 1
fi
echo "  clean — ContractResponse carries brandSignerName / creatorSignerName"

echo "· migration: a MySQL-grammar migration adds the signer-name columns"
mig_hit=$(grep -rlE "brand_signer_name" "$MIGRATIONS" 2>/dev/null | head -1)
if [ -z "$mig_hit" ]; then
  echo "VERDICT: broken — no migration under $MIGRATIONS adds brand_signer_name (F-0292)"
  exit 1
fi
if ! grep -qE "creator_signer_name" "$mig_hit"; then
  echo "VERDICT: broken — $mig_hit adds brand_signer_name but not creator_signer_name (F-0292)"
  exit 1
fi
# Dialect guard: this migration set is MySQL, not Postgres. A prior migration in this repo used
# Postgres grammar (ALTER COLUMN ... SET NOT NULL) and broke boot. Reject that grammar here too.
if grep -qiE "ALTER COLUMN|SET NOT NULL|CREATE INDEX IF NOT EXISTS" "$mig_hit"; then
  echo "  $mig_hit"
  echo "VERDICT: broken — $mig_hit uses Postgres grammar (ALTER COLUMN / SET NOT NULL / CREATE"
  echo "         INDEX IF NOT EXISTS); this project's migrations are MySQL and this has already"
  echo "         broken boot once before (F-0292)"
  exit 1
fi
if ! grep -qE "ADD COLUMN" "$mig_hit"; then
  echo "  $mig_hit"
  echo "VERDICT: broken — $mig_hit does not use MySQL's ADD COLUMN grammar (F-0292)"
  exit 1
fi
echo "  clean — $mig_hit adds both columns in MySQL grammar"

echo "VERDICT: aligned (proved) — the typed signer name the UI calls the binding act now has a"
echo "         field to travel in, a column to land in, and a place to be read back from"
echo "NOT CHECKED: that a live signature round-trip actually persists and displays the real name"
echo "             (only a running DB + live E2E proves that); whether the frontend is updated to"
echo "             RENDER brandSignerName/creatorSignerName anywhere (frontend is another"
echo "             producer's surface); and that the backend actually COMPILES/boots against"
echo "             the new migration (checked separately by the build step, not this gate)."
exit 0
