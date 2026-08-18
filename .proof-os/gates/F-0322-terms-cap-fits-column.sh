#!/usr/bin/env bash
# F-0322-terms-cap-fits-column.sh — gate for F-0322 (validation-cap-exceeds-column-bytes), plus
# the immutability-enforcement-gap finding from the same review (no separate F-number).
#
# ContractGenerateRequest#terms declared @Size(max = 20000), which counts CHARACTERS, while
# contracts.terms_text is MySQL TEXT (65,535 BYTES) on a utf8mb4 schema. 20,000 characters of
# 4-byte utf8mb4 content is 80,000 bytes — bean validation accepted input the column could not
# store, and contract generation 500s on insert. Reachable with heavy emoji, not with Latin or
# Devanagari (3 bytes/char).
#
# Byte limit is read from the LAST migration under db/migration that declares terms_text's SQL
# type, not hardcoded — this project has shipped two migrations naming this column
# (V20260817140000 ADD COLUMN ... TEXT) and either widening the column or lowering the DTO cap
# is an accepted fix per the review; this gate proves the two AGREE, whichever side moved.
#
#   exit 0 = proved · 1 = broken · 2 = unavailable
set -u
SELF="$(cd "$(dirname "$0")" 2>/dev/null && pwd)"
cd "${1:-.}" 2>/dev/null || { echo "· not a directory — unavailable"; exit 2; }

. "$SELF/_code.sh" 2>/dev/null || { echo "· gates/_code.sh unreadable — unavailable"; exit 2; }
code_ready || { echo "· $(code_why) — unavailable"; exit 2; }

DTO=influora-api/src/main/java/com/influora/web/dto/money/MoneyDtos.java
ENTITY=influora-api/src/main/java/com/influora/domain/entity/Contract.java
MIGRATIONS=influora-api/src/main/resources/db/migration
[ -f "$DTO" ] || { echo "· $DTO missing — unavailable"; exit 2; }
[ -f "$ENTITY" ] || { echo "· $ENTITY missing — unavailable"; exit 2; }
[ -d "$MIGRATIONS" ] || { echo "· $MIGRATIONS missing — unavailable"; exit 2; }

DTO_CODE=$(code_view "$DTO") || { echo "· $(code_why) — unavailable"; exit 2; }
ENTITY_CODE=$(code_view "$ENTITY") || { echo "· $(code_why) — unavailable"; exit 2; }

# --- leg 1: extract the DTO's character cap for ContractGenerateRequest#terms -------------
echo "· DTO: ContractGenerateRequest#terms carries a numeric @Size(max=...) cap"
record_block=$(awk '/record ContractGenerateRequest\(/,/\{$/' "$DTO_CODE")
if [ -z "$record_block" ]; then
  echo "VERDICT: broken — ContractGenerateRequest record not found (F-0322)"
  exit 1
fi
terms_line=$(printf '%s\n' "$record_block" | grep -E 'String +terms *[,)]')
if [ -z "$terms_line" ]; then
  echo "VERDICT: broken — ContractGenerateRequest has no 'terms' field (F-0322)"
  exit 1
fi
cap_chars=$(printf '%s\n' "$terms_line" | grep -oE 'max *= *[0-9]+' | head -1 | grep -oE '[0-9]+')
if [ -z "$cap_chars" ]; then
  echo "  $terms_line"
  echo "VERDICT: broken — 'terms' has no @Size(max=<n>) bound at all; an unbounded free-text"
  echo "         field reaching an insert is the same overflow risk, just with no ceiling to"
  echo "         even reason about (F-0322)"
  exit 1
fi
echo "  clean — terms field capped at $cap_chars characters: $terms_line"

# --- leg 2: extract the real column byte limit from the migration set ---------------------
echo "· migrations: terms_text's declared SQL type sets the real byte ceiling"
# Raw grep, not code_view — SQL isn't a language _code.sh tokenizes, and this project's SQL
# comments are line-only ('-- ...'), which a plain type-name grep below cannot mistake for a
# column declaration (the declarations are ADD/MODIFY COLUMN statements, not English prose that
# happens to contain the word TEXT).
mig_files=$(ls "$MIGRATIONS" 2>/dev/null | sort -V)
last_type=""
last_file=""
for f in $mig_files; do
  hit=$(grep -iE 'terms_text[[:space:]]+(MEDIUMTEXT|LONGTEXT|TEXT|VARCHAR\([0-9]+\))' "$MIGRATIONS/$f" 2>/dev/null | tail -1)
  [ -z "$hit" ] && continue
  last_type=$(printf '%s' "$hit" | grep -oiE '(MEDIUMTEXT|LONGTEXT|TEXT|VARCHAR\([0-9]+\))' | tail -1)
  last_file="$f"
done
if [ -z "$last_type" ]; then
  echo "VERDICT: broken — no migration under $MIGRATIONS declares terms_text's SQL type; cannot"
  echo "         confirm the column that bean validation is supposed to fit (F-0322)"
  exit 1
fi
case "$(printf '%s' "$last_type" | tr 'a-z' 'A-Z')" in
  TEXT) limit_bytes=65535 ;;
  MEDIUMTEXT) limit_bytes=16777215 ;;
  LONGTEXT) limit_bytes=4294967295 ;;
  VARCHAR\(*\))
    n=$(printf '%s' "$last_type" | grep -oE '[0-9]+')
    limit_bytes=$((n * 4))
    ;;
  *)
    echo "VERDICT: broken — unrecognized SQL type '$last_type' for terms_text (F-0322)"
    exit 1
    ;;
esac
echo "  clean — $last_file declares terms_text as $last_type (utf8mb4 -> $limit_bytes byte ceiling)"

# --- leg 3: the two must agree — worst-case 4-byte utf8mb4 content must fit -----------------
echo "· agreement: worst-case 4-byte utf8mb4 content of $cap_chars chars must fit in $limit_bytes bytes"
worst_case_bytes=$((cap_chars * 4))
if [ "$worst_case_bytes" -gt "$limit_bytes" ]; then
  echo "  $cap_chars chars * 4 bytes/char = $worst_case_bytes bytes > $limit_bytes byte column ceiling"
  echo "VERDICT: broken — bean validation accepts input the column cannot store; heavy-emoji terms"
  echo "         text within the DTO's own declared cap still 500s the insert (F-0322)"
  exit 1
fi
echo "  clean — $cap_chars * 4 = $worst_case_bytes <= $limit_bytes"

# --- leg 4 (immutability-enforcement-gap, same review, no separate F-number) ---------------
echo "· immutability: Contract#termsText is mapped updatable = false, matching the createdAt idiom"
termstext_col=$(grep -A1 '@Column(name = "terms_text"' "$ENTITY_CODE" | head -1)
if [ -z "$termstext_col" ]; then
  echo "VERDICT: broken — could not locate the @Column(name = \"terms_text\" ...) mapping on"
  echo "         Contract#termsText"
  exit 1
fi
if ! printf '%s\n' "$termstext_col" | grep -qE 'updatable *= *false'; then
  echo "  $termstext_col"
  echo "VERDICT: broken — terms_text is not mapped updatable = false. Contract.java already uses"
  echo "         this exact idiom for created_at; without it on terms_text, any future write path"
  echo "         (a merge of a detached instance, a second builder call site, an @Query update)"
  echo "         silently rewrites clauses both parties e-signed under a statutory binding notice"
  exit 1
fi
echo "  clean — $termstext_col"

echo "VERDICT: aligned (proved) — ContractGenerateRequest#terms's character cap ($cap_chars) and"
echo "         terms_text's declared SQL type ($last_type, from $last_file) agree even for"
echo "         worst-case 4-byte utf8mb4 content, and Contract#termsText carries"
echo "         updatable = false so no update path can silently rewrite signed terms."
echo "NOT CHECKED: that this actually boots and inserts against a live MySQL 8 with real utf8mb4"
echo "             emoji content at exactly the cap (no live DB in this gate); whether any other"
echo "             caller builds a detached Contract via a second Builder call site that could"
echo "             still attempt to write terms_text on save (grep-shaped, not exhaustive);"
echo "             frontend character/byte counters, which are another producer's surface."
exit 0
