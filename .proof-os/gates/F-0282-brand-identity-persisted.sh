#!/usr/bin/env bash
# F-0282-brand-identity-persisted.sh — gate for F-0282 (dropped-identity-at-session-persist).
#
# `persistBrandSession` (src/lib/auth-session.ts) previously discarded `data.user.displayName`
# where the sibling `persistCreatorSession` kept it — the root cause of "the app greets nobody"
# for brand sessions. The backend really does return a personal display name for brand users
# (AuthDtos.UserDto.displayName, built from BrandRegisterRequest.firstName/lastName in
# AuthService#brandRegister and returned on every TokenPair, login included), so the honest fix
# is to actually persist it, mirroring the creator helper.
#
# This gate does NOT just grep for the string "displayName" appearing somewhere near
# "localStorage" — a wrong fix can trivially satisfy that (declare a `displayName` variable,
# guard it, call setItem with it) while never actually assigning it FROM `data.user.displayName`,
# so the value persisted is always undefined/empty. This gate traces the data flow: it extracts
# the variable assigned from `data.user.displayName` inside `persistBrandSession`'s own body, then
# confirms THAT SAME variable (not a same-named-but-differently-sourced one) is the value written
# to localStorage and the value returned on the BrandSession object.
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

FILE=src/lib/auth-session.ts
[ -f "$FILE" ] || { echo "· $FILE missing — unavailable"; exit 2; }
FILE_CODE=$(code_view "$FILE") || { echo "$(code_why) - unavailable"; exit 2; }

echo "· BrandSession interface declares a displayName field"
brand_session_block=$(awk '/^export interface BrandSession[ \t]*\{/{flag=1} flag{print; if ($0 ~ /^\}/) exit}' "$FILE_CODE")
if [ -z "$brand_session_block" ]; then
  echo "VERDICT: broken — could not locate 'export interface BrandSession { ... }' in $FILE"
  echo "         (F-0282)"
  exit 1
fi
if ! printf '%s\n' "$brand_session_block" | grep -qE '\bdisplayName\??:[[:space:]]*string'; then
  echo "  $brand_session_block"
  echo "VERDICT: broken — BrandSession has no displayName field; there is nowhere for the"
  echo "         persisted identity to live in the return value (F-0282)"
  exit 1
fi
echo "  clean — BrandSession declares displayName"

echo "· persistBrandSession() body located"
body=$(awk '/^export function persistBrandSession\(/{flag=1} flag{print; if ($0 ~ /^\}/) exit}' "$FILE_CODE")
if [ -z "$body" ]; then
  echo "VERDICT: broken — could not locate 'export function persistBrandSession(...) { ... }'"
  echo "         in $FILE (F-0282)"
  exit 1
fi

echo "· persistBrandSession() actually reads data.user.displayName into a traceable local"
# Same technique as F-0292's name_var trace: find `<ident> = ... data.user.displayName ...`
# on its own statement and capture the identifier — NOT just any line that mentions
# data.user.displayName (a comment or an unrelated log line would false-pass a bare grep).
name_var=$(printf '%s\n' "$body" \
  | grep -oE '\b[A-Za-z_][A-Za-z0-9_]*[[:space:]]*=[^;]*\bdata\.user\.displayName\b[^;]*;' \
  | head -1 \
  | grep -oE '^[A-Za-z_][A-Za-z0-9_]*')
if [ -z "$name_var" ]; then
  echo "  no statement of the form '<ident> = ...data.user.displayName...;' found in the"
  echo "  function body"
  echo "VERDICT: broken — persistBrandSession() never reads data.user.displayName into a local"
  echo "         variable; a wrong fix that declares a same-named 'displayName' variable without"
  echo "         sourcing it from the login payload (shape right, value always empty) is exactly"
  echo "         what this leg is built to catch (F-0282)"
  exit 1
fi
echo "  clean — '$name_var' assigned from data.user.displayName"

echo "· that same variable ('$name_var') is written to localStorage, not a differently-sourced"
echo "  or literal stand-in"
if ! printf '%s\n' "$body" | grep -qE "localStorage\.setItem\([^)]*\b${name_var}\b"; then
  echo "VERDICT: broken — '$name_var' (sourced from data.user.displayName) is never passed to"
  echo "         localStorage.setItem(...); persistBrandSession still does not durably store the"
  echo "         brand user's display name (F-0282)"
  exit 1
fi
echo "  clean — localStorage.setItem(...) call references '$name_var'"

echo "· the setItem call is guarded so an absent name never writes a useless value"
# Mirrors the sibling persistCreatorSession's `if (displayName) localStorage.setItem(...)`
# pattern and the existing `if (data.workspace?.name) localStorage.setItem('brand_company', ...)`
# guard already in this same function — an unconditional setItem on a possibly-undefined value
# would write the literal string "undefined" into localStorage.
if ! printf '%s\n' "$body" | grep -qE "if[[:space:]]*\([[:space:]]*${name_var}[[:space:]]*\)[[:space:]]*localStorage\.setItem\([^)]*\b${name_var}\b"; then
  echo "VERDICT: broken — the localStorage.setItem(...) call for '$name_var' is not guarded by"
  echo "         'if ($name_var) ...' on the same line; an absent display name would persist the"
  echo "         literal string \"undefined\" instead of leaving the key unset (F-0282)"
  exit 1
fi
echo "  clean — guarded with 'if ($name_var) localStorage.setItem(...)'"

echo "· persistBrandSession() actually returns '$name_var' on the BrandSession object"
if ! printf '%s\n' "$body" | grep -E '^[[:space:]]*return[[:space:]]*\{' -A5 | grep -qE "\b${name_var}\b"; then
  echo "VERDICT: broken — the returned BrandSession object does not include '$name_var'; callers"
  echo "         reading the return value directly still see no identity (F-0282)"
  exit 1
fi
echo "  clean — return object includes '$name_var'"

echo "VERDICT: aligned (proved) — persistBrandSession() reads data.user.displayName into"
echo "         '$name_var', writes it to localStorage guarded against an absent value, and"
echo "         returns it on BrandSession, mirroring persistCreatorSession"
echo "NOT CHECKED: whether anything in the brand LOGIN call path (src/lib/api.ts mapBrandAuth /"
echo "             LoginResponse, or wherever the brand login page is) actually forwards this"
echo "             value into useAuthStore().setUser()/login() so a live brand session's global"
echo "             \`user\` is non-null — that wiring lives outside auth-session.ts and is"
echo "             explicitly out of this producer's file ownership; a real localStorage round"
echo "             trip in a browser; whether the backend ever returns a BLANK (empty-string,"
echo "             not undefined) displayName, which this gate's guard treats as falsy and"
echo "             correctly does not persist, but which a live account could theoretically have"
exit 0
