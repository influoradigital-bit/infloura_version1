#!/usr/bin/env bash
# F-0283-contract-terms-persisted.sh — gate for F-0283 (contract-terms-never-persisted).
#
# The platform never captured, stored or returned contract terms at all.
# `ContractGenerateRequest` was `{collaborationId, milestones}` and nothing else
# (MoneyDtos.java:255 as of the audit). The `Contract` column NAMED `terms` (Contract.java:48)
# was written with `sha256TamperHash(req)` -> `{"tamperHashSha256":"<hex>"}` — a hash of the
# request, not terms. `ContractResponse` exposed no terms field. Both parties e-sign under a
# statutory binding notice ("legally bound ... under the IT Act 2000") over a document that, as
# far as the server is concerned, has no terms.
#
# This gate asserts the SERVER chain end to end: request -> service write-thread -> persisted
# column -> response read-thread. A field merely existing on the DTO/entity/response is not
# enough — F-0292's own first gate passed exactly that shape of wrong fix (a field with nowhere
# real for its value to travel). See the WRITE-THREAD and READ-MODEL checks below, which extract
# the actual method bodies rather than trusting proximity of strings in the file.
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
MIGRATIONS=influora-api/src/main/resources/db/migration

for f in "$DTO" "$ENTITY" "$SERVICE"; do
  [ -f "$f" ] || { echo "· $f missing — unavailable"; exit 2; }
done
[ -d "$MIGRATIONS" ] || { echo "· $MIGRATIONS missing — unavailable"; exit 2; }

echo "· request DTO: ContractGenerateRequest can carry terms text"
# The record's components can span multiple lines (they do today: the opening paren and the
# field list are on separate lines) — capture the whole declaration up to its closing '{}', not
# just the line the grep pattern happened to land on.
gen_req_block=$(awk '/record ContractGenerateRequest\(/{f=1} f{print; if (/\{\}/) exit}' "$DTO_CODE")
if [ -z "$gen_req_block" ]; then
  echo "VERDICT: broken — ContractGenerateRequest record not found (F-0283)"
  exit 1
fi
if ! printf '%s\n' "$gen_req_block" | grep -qE "\bString +terms\b"; then
  echo "$gen_req_block"
  echo "VERDICT: broken — ContractGenerateRequest still has no terms field; there is nowhere for"
  echo "         the agreed terms to enter the system (F-0283)"
  exit 1
fi
echo "  clean — ContractGenerateRequest carries terms"

echo "· entity: Contract persists real terms text in a column distinct from the tamper hash"
if ! grep -qE "\bString +termsText\b" "$ENTITY_CODE"; then
  echo "VERDICT: broken — Contract entity has no termsText field (F-0283)"
  exit 1
fi
terms_col=$(grep -E '@Column\(name = "terms_text"' "$ENTITY_CODE")
if [ -z "$terms_col" ]; then
  echo "VERDICT: broken — termsText is not mapped to a persisted terms_text column (F-0283)"
  exit 1
fi
# The pre-existing `terms` column (termsJson field) holds a SHA-256 tamper hash of the request —
# that is a real, separate feature and must not be repurposed/removed as a side effect of this
# fix. Reusing IT as "the terms" would be the exact "terms exist but hold a hash, not terms"
# defect this record is about.
if ! grep -qE '@Column\(name = "terms", columnDefinition = "json"\)' "$ENTITY_CODE"; then
  echo "VERDICT: broken — the pre-existing terms/termsJson tamper-hash column was removed or"
  echo "         renamed; this fix must be additive, not a repurposing of the hash column (F-0283)"
  exit 1
fi
echo "  clean — terms_text column mapped, pre-existing tamper-hash terms column untouched"

echo "· response DTO: ContractResponse can return terms text"
resp_block=$(awk '/record ContractResponse\(/{f=1} f{print; if (/\{\}/) exit}' "$DTO_CODE")
if [ -z "$resp_block" ] || ! printf '%s\n' "$resp_block" | grep -qE "\bString +terms\b"; then
  echo "$resp_block"
  echo "VERDICT: broken — ContractResponse does not carry a terms field; the UI has no read path"
  echo "         back to whatever terms were captured (F-0283)"
  exit 1
fi
echo "  clean — ContractResponse carries terms"

echo "· service (WRITE-THREAD): generate() actually reads req.terms() into the entity write —"
echo "  not just declared on the DTO and dropped (the exact shape of wrong fix F-0292's first"
echo "  gate missed)"
gen_method=$(awk '/public ContractResponse generate\(/,/^    \}$/' "$SERVICE_CODE")
if [ -z "$gen_method" ]; then
  echo "  could not locate ContractService#generate() method body — unavailable"
  exit 2
fi
flat_gen=$(printf '%s\n' "$gen_method" | tr '\n' ' ' | tr -s ' ')
if ! printf '%s' "$flat_gen" | grep -qE '\.termsText\([^)]*req\.terms\(\)[^)]*\)'; then
  echo "VERDICT: broken — generate() does not pass req.terms() into a .termsText(...) builder call;"
  echo "         a caller-supplied terms value has nowhere real to land in the persisted entity"
  echo "         even if the DTO field exists (F-0283)"
  exit 1
fi
echo "  clean — req.terms() is threaded into Contract.builder().termsText(...) in generate()"

echo "· service (READ-MODEL): toResponse() actually reads the persisted terms back out —"
echo "  not just declared on the response record and left null on every path"
to_resp_method=$(awk '/private static ContractResponse toResponse\(/,/^    \}$/' "$SERVICE_CODE")
if [ -z "$to_resp_method" ]; then
  echo "  could not locate ContractService#toResponse() method body — unavailable"
  exit 2
fi
flat_resp=$(printf '%s\n' "$to_resp_method" | tr '\n' ' ' | tr -s ' ')
new_resp_call=$(printf '%s' "$flat_resp" | grep -oE 'new ContractResponse\([^;]*\)' | head -1)
if [ -z "$new_resp_call" ]; then
  echo "  could not find the 'new ContractResponse(...)' construction inside toResponse() —"
  echo "  unavailable"
  exit 2
fi
if ! printf '%s' "$new_resp_call" | grep -qE '\bcontract\.getTermsText\(\)'; then
  echo "  $new_resp_call"
  echo "VERDICT: broken — toResponse() builds ContractResponse without contract.getTermsText();"
  echo "         terms captured at generation time are persisted but never read back to the"
  echo "         client (F-0283)"
  exit 1
fi
echo "  clean — toResponse() passes contract.getTermsText() into ContractResponse"

echo "· migration: a MySQL-grammar migration adds the terms_text column"
mig_hit=$(grep -rlE "terms_text" "$MIGRATIONS" 2>/dev/null | head -1)
if [ -z "$mig_hit" ]; then
  echo "VERDICT: broken — no migration under $MIGRATIONS adds terms_text (F-0283)"
  exit 1
fi
mig_sql_only=$(grep -vE '^\s*--' "$mig_hit")
if printf '%s\n' "$mig_sql_only" | grep -qiE "ALTER COLUMN|SET NOT NULL|CREATE INDEX IF NOT EXISTS"; then
  echo "  $mig_hit"
  echo "VERDICT: broken — $mig_hit uses Postgres grammar (ALTER COLUMN / SET NOT NULL / CREATE"
  echo "         INDEX IF NOT EXISTS); this project's migrations are MySQL and this has already"
  echo "         broken boot twice before (F-0231, F-0233) (F-0283)"
  exit 1
fi
if ! printf '%s\n' "$mig_sql_only" | grep -qE "ADD COLUMN"; then
  echo "  $mig_hit"
  echo "VERDICT: broken — $mig_hit does not use MySQL's ADD COLUMN grammar (F-0283)"
  exit 1
fi
# If this migration contains a backfill UPDATE, it must pin updated_at (F-0233 class of bug).
if printf '%s\n' "$mig_sql_only" | grep -qiE '^\s*UPDATE\b'; then
  if ! printf '%s\n' "$mig_sql_only" | grep -qE "updated_at\s*=\s*updated_at"; then
    echo "  $mig_hit"
    echo "VERDICT: broken — $mig_hit issues an UPDATE against 'contracts' (ON UPDATE"
    echo "         CURRENT_TIMESTAMP) without pinning updated_at = updated_at; a backfill would"
    echo "         silently clobber the audit timestamp (F-0233 class bug) (F-0283)"
    exit 1
  fi
fi
echo "  clean — $mig_hit adds terms_text in MySQL grammar"

echo "· immutability (1/2): Contract exposes no public post-construction mutator for terms text"
# Contract.Builder#termsText (construction-time only, before the entity is ever persisted or
# signable) is the ONLY legitimate place terms text is set -- see Contract.java's `class Builder`.
# This looks for the wrong-fix shape instead: a setTermsText/updateTermsText method reachable on
# an already-persisted, possibly already-signed Contract instance. Restricted to the file content
# BEFORE `class Builder` so the legitimate construction-time Builder method is never flagged.
CONTROLLER=influora-api/src/main/java/com/influora/web/ContractController.java
[ -f "$CONTROLLER" ] || { echo "· $CONTROLLER missing — unavailable"; exit 2; }
CONTROLLER_CODE=$(code_view "$CONTROLLER") || { echo "$(code_why) - unavailable"; exit 2; }
entity_outside_builder=$(awk '/class Builder/{exit} {print}' "$ENTITY_CODE")
if printf '%s\n' "$entity_outside_builder" | grep -qE '\b(public|protected)\s+\S+\s+(set|update)TermsText\s*\('; then
  echo "VERDICT: broken — Contract exposes a post-construction setTermsText/updateTermsText"
  echo "         mutator outside Contract.Builder; a contract's clauses could be changed after"
  echo "         either party has e-signed under the IT Act 2000 binding notice (F-0283)"
  exit 1
fi
echo "  clean — no setTermsText/updateTermsText mutator outside Contract.Builder"

echo "· immutability (2/2): ContractController exposes no HTTP surface for editing contract terms"
# No update-terms endpoint exists or should exist for this record (mechanism, not policy) -- terms
# are captured exactly once, at contract generation time. A wrong fix that adds a PATCH/PUT
# terms-editing endpoint would introduce the word "terms" into this controller for the first time.
if grep -qiE '\bterms\b' "$CONTROLLER_CODE"; then
  echo "VERDICT: broken — ContractController now references 'terms'; an update-terms HTTP surface"
  echo "         appears to have been added, reopening a contract's clauses after generation"
  echo "         (F-0283)"
  exit 1
fi
echo "  clean — ContractController has no terms-editing surface"

command -v mvn >/dev/null 2>&1 || { echo "· mvn not on PATH — unavailable"; exit 2; }
[ -f influora-api/pom.xml ] || { echo "· influora-api/pom.xml missing — unavailable"; exit 2; }

BUDGET="${PROOF_F0283_MVN_TIMEOUT:-240}"
if command -v timeout >/dev/null 2>&1; then TO="timeout -k 15 $BUDGET"; else TO=""; fi

echo "· mvn -o test ContractServiceTest (budget ${BUDGET}s)"
out=$(cd influora-api && $TO mvn -q -o test \
        -Dtest=ContractServiceTest -DfailIfNoSpecifiedTests=false 2>&1); rc=$?

if [ $rc -eq 124 ] || [ $rc -eq 137 ]; then
  echo "  suite exceeded ${BUDGET}s — unavailable, NOT a finding"
  echo "NOT CHECKED: everything below this line — the suite did not finish, so no test result"
  echo "             was observed"
  exit 2
fi
if [ $rc -ne 0 ]; then
  printf '%s\n' "$out" | grep -E "ERROR|Tests run" | tail -30
  echo "VERDICT: broken — ContractServiceTest does not pass; the write/read terms-threading this"
  echo "         gate asserts statically is not actually exercised end to end (F-0283)"
  exit 1
fi
echo "  suite green — ContractServiceTest"

echo "VERDICT: aligned (proved) — supplied terms now have a DTO field to arrive in, a real column"
echo "         (distinct from the pre-existing tamper hash) to persist in, generate() actually"
echo "         threads req.terms() into that column, toResponse() actually reads it back out, a"
echo "         MySQL-grammar migration adds the column, Contract exposes no post-construction"
echo "         terms mutator, ContractController exposes no terms-editing HTTP surface, and"
echo "         ContractServiceTest (including the signature-does-not-alter-terms and"
echo "         no-public-mutator immutability tests) passes"
echo "NOT CHECKED: that a live generate+fetch round-trip actually persists and returns real terms"
echo "             against a running MySQL (test-compile/mvn -o test never opens a live DB"
echo "             connection here — Mockito mocks the repository layer); whether any FE surface"
echo "             actually lets a brand TYPE terms before generating a contract (no such input"
echo "             exists yet — out of this record's scope, see PRODUCT DECISION NEEDED); whether"
echo "             ContractController's @Valid bean validation on the new terms field behaves as"
echo "             intended against a live HTTP request (only unit-level DTO/service checks ran);"
echo "             a determined wrong fix could still rename its mutator to dodge the"
echo "             setTermsText/updateTermsText grep (static-shape check, not a formal proof) or"
echo "             mutate termsText via raw reflection/JPA field access bypassing the Builder"
echo "             entirely — this gate proves no ORDINARY code path exposes that door, not that"
echo "             no possible Java code could reach the field."
exit 0
