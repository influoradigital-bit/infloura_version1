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
SELF="$(cd "$(dirname "$0")" 2>/dev/null && pwd)"
cd "$ROOT" || { echo "· project root unreadable — unavailable"; exit 2; }
# F-0266: grep CODE, not file bytes. A gate that cannot tell a comment from a
# statement fails the fix whose own comment quotes the string it forbids, and
# greens a "fix" that was only ever described in one. gates/_code.sh is the one
# shared, tested place that distinction lives.
. "$SELF/_code.sh" 2>/dev/null || { echo "gates/_code.sh unreadable - unavailable"; exit 2; }
code_ready || { echo "$(code_why) - unavailable"; exit 2; }

DTO=influora-api/src/main/java/com/influora/web/dto/money/MoneyDtos.java
DTO_CODE=$(code_view "$DTO") || { echo "$(code_why) - unavailable"; exit 2; }
ENTITY=influora-api/src/main/java/com/influora/domain/entity/Contract.java
ENTITY_CODE=$(code_view "$ENTITY") || { echo "$(code_why) - unavailable"; exit 2; }
SERVICE=influora-api/src/main/java/com/influora/service/ContractService.java
SERVICE_CODE=$(code_view "$SERVICE") || { echo "$(code_why) - unavailable"; exit 2; }
CONTROLLER=influora-api/src/main/java/com/influora/web/ContractController.java
CONTROLLER_CODE=$(code_view "$CONTROLLER") || { echo "$(code_why) - unavailable"; exit 2; }
MIGRATIONS=influora-api/src/main/resources/db/migration

for f in "$DTO" "$ENTITY" "$SERVICE" "$CONTROLLER"; do
  [ -f "$f" ] || { echo "· $f missing — unavailable"; exit 2; }
done
[ -d "$MIGRATIONS" ] || { echo "· $MIGRATIONS missing — unavailable"; exit 2; }

echo "· request DTO: ContractSignRequest carries the signer name"
sign_req_line=$(grep -n "record ContractSignRequest" "$DTO_CODE")
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
if ! grep -qE "brandSignerName" "$ENTITY_CODE"; then
  echo "VERDICT: broken — Contract entity has no brandSignerName field (F-0292)"
  exit 1
fi
if ! grep -qE "creatorSignerName" "$ENTITY_CODE"; then
  echo "VERDICT: broken — Contract entity has no creatorSignerName field (F-0292)"
  exit 1
fi
if ! grep -qE '@Column\(name = "brand_signer_name"' "$ENTITY_CODE"; then
  echo "VERDICT: broken — brandSignerName is not mapped to a persisted column (F-0292)"
  exit 1
fi
if ! grep -qE '@Column\(name = "creator_signer_name"' "$ENTITY_CODE"; then
  echo "VERDICT: broken — creatorSignerName is not mapped to a persisted column (F-0292)"
  exit 1
fi
echo "  clean — brand_signer_name / creator_signer_name columns mapped"

echo "· entity: recordBrandSignature/recordCreatorSignature actually accept and write the name"
if ! grep -qE "void recordBrandSignature\([^)]*String[^)]*\)" "$ENTITY_CODE"; then
  echo "VERDICT: broken — recordBrandSignature still takes no name parameter; a signer name"
  echo "         passed in from the request has nothing to call it with (F-0292)"
  exit 1
fi
if ! grep -qE "void recordCreatorSignature\([^)]*String[^)]*\)" "$ENTITY_CODE"; then
  echo "VERDICT: broken — recordCreatorSignature still takes no name parameter (F-0292)"
  exit 1
fi
echo "  clean — both record*Signature methods take a name argument"

echo "· service: the sign flow actually threads a caller-supplied name into the entity write"
if ! grep -qE "\.recordBrandSignature\([a-zA-Z]" "$SERVICE_CODE"; then
  echo "VERDICT: broken — ContractService still calls recordBrandSignature() with no argument;"
  echo "         the entity has a name column no code path ever writes (F-0292)"
  exit 1
fi
if ! grep -qE "\.recordCreatorSignature\([a-zA-Z]" "$SERVICE_CODE"; then
  echo "VERDICT: broken — ContractService still calls recordCreatorSignature() with no argument"
  echo "         (F-0292)"
  exit 1
fi
echo "  clean — ContractService passes a name through to both record*Signature calls"

echo "· controller: sign() still server-derives role and is not broken by the new field"
if ! grep -qE '"BRAND"' "$CONTROLLER_CODE"; then
  echo "VERDICT: broken — ContractController#sign no longer server-derives the BRAND role from"
  echo "         the authenticated principal (F-0292 fix must not regress this)"
  exit 1
fi
echo "  clean — server-derived role default still present"

# T-BRANDMED-0817 (F-0292 self-check) — every leg above still passes if sign() is stubbed to
# `String signerName = null;`: the DTO field, entity columns, service threading, response and
# migration are all untouched by that one-line edit. This is F-0292 verbatim, reproduced by the
# checker, and the only leg that closes it is one that reads the CONTROLLER METHOD BODY itself,
# not just the string "BRAND" surviving nearby.
echo "· controller: sign() actually reads body.name() (not a stubbed/null substitute) and"
echo "  threads that value into BOTH signature calls"
sign_method=$(awk '/public ApiResponse<ContractResponse> sign\(/,/^    \}$/' "$CONTROLLER_CODE")
if [ -z "$sign_method" ]; then
  echo "  could not locate ContractController#sign() method body — unavailable"
  exit 2
fi
if ! printf '%s\n' "$sign_method" | grep -qE '\bbody\.name\(\)'; then
  echo "  sign() never calls body.name() anywhere in its body"
  echo "VERDICT: broken — ContractController#sign() does not read body.name(); a stub such as"
  echo "         'String signerName = null;' satisfies every other leg of this gate (DTO field,"
  echo "         entity columns, service threading, response and migration are all still in"
  echo "         place) while silently discarding the typed name a second time — F-0292 verbatim"
  exit 1
fi
echo "  clean — body.name() is called"

name_var=$(printf '%s\n' "$sign_method" \
  | grep -oE '\b[A-Za-z_][A-Za-z0-9_]*[[:space:]]*=[^;]*\bbody\.name\(\)' \
  | head -1 | grep -oE '^[A-Za-z_][A-Za-z0-9_]*')
if [ -z "$name_var" ]; then
  echo "  body.name() is called but its result is not assigned to a traceable local variable on"
  echo "  its own statement"
  echo "VERDICT: broken — cannot confirm body.name()'s value reaches either signature call (F-0292)"
  exit 1
fi
echo "  name variable captured from body.name(): $name_var"

flat=$(printf '%s\n' "$sign_method" | tr '\n' ' ' | tr -s ' ')
call_creator=$(printf '%s' "$flat" | grep -oE "contractService\.recordSignatureForCreator\([^;]*;" | head -1)
call_brand=$(printf '%s' "$flat" | grep -oE "contractService\.recordSignature\([^;]*;" | head -1)

if [ -z "$call_creator" ] || ! printf '%s' "$call_creator" | grep -qE "\b${name_var}\b"; then
  echo "VERDICT: broken — '$name_var' (captured from body.name()) is never passed to"
  echo "         recordSignatureForCreator(...); the creator sign path still discards the typed"
  echo "         name (F-0292)"
  exit 1
fi
if [ -z "$call_brand" ] || ! printf '%s' "$call_brand" | grep -qE "\b${name_var}\b"; then
  echo "VERDICT: broken — '$name_var' (captured from body.name()) is never passed to"
  echo "         recordSignature(...); the brand sign path still discards the typed name (F-0292)"
  exit 1
fi
echo "  clean — '$name_var' is threaded into both recordSignatureForCreator(...) and recordSignature(...)"

echo "· read model: signer names come back on the contract response"
if ! grep -qE "brandSignerName" "$DTO_CODE"; then
  echo "VERDICT: broken — ContractResponse does not carry brandSignerName; the UI has no way to"
  echo "         show who signed (F-0292)"
  exit 1
fi
if ! grep -qE "creatorSignerName" "$DTO_CODE"; then
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
# Postgres grammar (ALTER COLUMN ... SET NOT NULL) and broke boot. Reject that grammar here too --
# checked against the SQL with `--` comment lines stripped, so a migration's own doc-comment
# explaining what NOT to do (as this one does) cannot trip its own guard.
mig_sql_only=$(grep -vE '^\s*--' "$mig_hit")
if printf '%s\n' "$mig_sql_only" | grep -qiE "ALTER COLUMN|SET NOT NULL|CREATE INDEX IF NOT EXISTS"; then
  echo "  $mig_hit"
  echo "VERDICT: broken — $mig_hit uses Postgres grammar (ALTER COLUMN / SET NOT NULL / CREATE"
  echo "         INDEX IF NOT EXISTS); this project's migrations are MySQL and this has already"
  echo "         broken boot once before (F-0292)"
  exit 1
fi
if ! printf '%s\n' "$mig_sql_only" | grep -qE "ADD COLUMN"; then
  echo "  $mig_hit"
  echo "VERDICT: broken — $mig_hit does not use MySQL's ADD COLUMN grammar (F-0292)"
  exit 1
fi
echo "  clean — $mig_hit adds both columns in MySQL grammar"

# T-BRANDMED-0817 (F-0296 batch rule) — ContractServiceTest#testSignerNameSurvivesToPersistedEntity
# and ContractControllerTest both exist, are F-0292-aware, and pass — but nothing wired them into
# this gate. A gate must run the relevant suite that already exists, not point at it. Guarded so
# an absent/offline maven toolchain reports unavailable (2), never a false pass and never a hang.
command -v mvn >/dev/null 2>&1 || { echo "· mvn not on PATH — unavailable"; exit 2; }
[ -f influora-api/pom.xml ] || { echo "· influora-api/pom.xml missing — unavailable"; exit 2; }

BUDGET="${PROOF_F0292_MVN_TIMEOUT:-240}"
if command -v timeout >/dev/null 2>&1; then TO="timeout -k 15 $BUDGET"; else TO=""; fi

echo "· mvn -o test ContractControllerTest,ContractServiceTest (budget ${BUDGET}s)"
out=$(cd influora-api && $TO mvn -q -o test \
        -Dtest=ContractControllerTest,ContractServiceTest -DfailIfNoSpecifiedTests=false 2>&1); rc=$?

if [ $rc -eq 124 ] || [ $rc -eq 137 ]; then
  echo "  suite exceeded ${BUDGET}s — unavailable, NOT a finding"
  echo "NOT CHECKED: everything below this line — the suite did not finish, so no test result"
  echo "             was observed"
  exit 2
fi
if [ $rc -ne 0 ]; then
  printf '%s\n' "$out" | grep -E "ERROR|Tests run" | tail -30
  echo "VERDICT: broken — ContractControllerTest and/or ContractServiceTest do not pass; the"
  echo "         name-threading this gate asserts statically is not actually exercised end to"
  echo "         end (F-0292)"
  exit 1
fi
echo "  suites green — ContractControllerTest, ContractServiceTest"

echo "VERDICT: aligned (proved) — the typed signer name the UI calls the binding act now has a"
echo "         field to travel in, a column to land in, and a place to be read back from, the"
echo "         controller actually reads body.name() and threads it into both signature calls,"
echo "         and ContractControllerTest/ContractServiceTest both pass"
echo "NOT CHECKED: that a live signature round-trip actually persists and displays the real name"
echo "             (only a running DB + live E2E proves that); whether the frontend is updated to"
echo "             RENDER brandSignerName/creatorSignerName anywhere (frontend is another"
echo "             producer's surface); that the backend actually BOOTS against the new migration"
echo "             against a real MySQL (test-compile/mvn -o test never opens a live DB connection"
echo "             for these two suites — Mockito mocks the repository layer)."
exit 0
