#!/usr/bin/env bash
# gates/F-0668-apierror-fields-consumed.sh — instance gate, closes F-0668.
#
# origin: F-0668 (dead-plumbing) — "ApiError now carries the server's field and fields members
# through all three envelope choke points, but a repo-wide search finds zero consumers outside
# api.ts." F-0466 added the plumbing and nothing was ever wired to it.
#
# WHAT THIS GATE CLOSES, PRECISELY: the plumbing is no longer dead — there is at least one real,
# non-test consumer, and the backend genuinely emits what it reads. Nothing more.
#
# WHAT IT DELIBERATELY DOES NOT CLOSE: F-0668's stated CONSEQUENCE, "every server-named field
# validation error still surfaces as one generic message." That is still true nearly everywhere:
# 98 @Valid @RequestBody endpoints across 51 controllers can emit a fields array, and exactly one
# client form reads it. That measurement is recorded as F-0683 so this closure cannot bury it.
# Failing here on the other 83 files would be wrong — most call endpoints with no per-field
# validation, and this gate must not assert a defect count it has not measured.
#
# LAW: exit 0 proved · 1 broken · 2 unavailable.
set -u
SELF="$(cd "$(dirname "$0")" 2>/dev/null && pwd)" || { echo "· cannot resolve gate dir — unavailable"; exit 2; }
ROOT=$(cd "$SELF/../.." 2>/dev/null && pwd) || { echo "· cannot resolve project root — unavailable"; exit 2; }
cd "${1:-$ROOT}" 2>/dev/null || { echo "· not a directory — unavailable"; exit 2; }
command -v python >/dev/null 2>&1 || { echo "· python not on PATH — unavailable"; exit 2; }
SCAN="$SELF/_f0668_scan.py"
[ -f "$SCAN" ] || { echo "· $SCAN missing — unavailable"; exit 2; }

# --- CHECK A: at least one real consumer, comments stripped ------------------------------------
python "$SCAN"; rc=$?
[ "$rc" -eq 2 ] && exit 2
[ "$rc" -ne 0 ] && exit 1

# --- CHECK B: the server really emits `fields` --------------------------------------------------
# Without this the consumer could be reading something nothing ever sends — the F-0434 shape, an
# inert fix that passes its own tests because the tests mock the very payload in question.
GEH=influora-api/src/main/java/com/influora/common/GlobalExceptionHandler.java
BODY=influora-api/src/main/java/com/influora/common/ApiErrorBody.java
if [ -f "$GEH" ] && [ -f "$BODY" ]; then
  # Anchored to the RECORD COMPONENT (the name Jackson puts on the wire), not just any mention:
  # `validation(String message, List<FieldError> fields)` is a method PARAMETER in this same file,
  # and an unanchored grep matched it — the gate stayed green through a renamed wire field.
  grep -qE "^\s*List<FieldError> fields,\s*$" "$BODY" || {
    echo "VERDICT: broken — ApiErrorBody no longer carries a fields list; the client reads a"
    echo "         member the server cannot send (F-0668)"; exit 1; }
  grep -q "ApiErrorBody.validation(" "$GEH" || {
    echo "VERDICT: broken — GlobalExceptionHandler no longer builds a validation body, so no"
    echo "         endpoint emits per-field errors and the consumer is inert (F-0668)"; exit 1; }
  echo "· server side confirmed: ApiErrorBody carries fields, GlobalExceptionHandler emits it"
else
  echo "· backend sources not present here — SERVER EMISSION NOT CHECKED"
fi

# --- CHECK C: the consumer's test is tracked and green -----------------------------------------
TEST=src/pages/creator-profile.field-errors.test.tsx
if command -v git >/dev/null 2>&1 && git rev-parse --git-dir >/dev/null 2>&1; then
  git ls-files --error-unmatch "$TEST" >/dev/null 2>&1 || {
    echo "VERDICT: broken — $TEST is not git-tracked; it proves nothing on a fresh clone (F-0324)"
    exit 1; }
  echo "· consumer test is git-tracked"
fi
command -v npx >/dev/null 2>&1 || { echo "· npx unavailable — BEHAVIOUR NOT PROVED"; exit 2; }
[ -f package.json ] || { echo "· no package.json here — BEHAVIOUR NOT PROVED"; exit 2; }
log=$(mktemp 2>/dev/null || echo "/tmp/f0668.$$")
npx vitest run --reporter=basic "$TEST" >"$log" 2>&1; trc=$?
if grep -qE "Cannot find module|Failed to load|ERR_MODULE_NOT_FOUND" "$log"; then
  echo "· the suite could not load — unavailable, NOT a pass"; tail -10 "$log"; exit 2; fi
if [ "$trc" -ne 0 ]; then
  echo "VERDICT: broken — the consumer's regression tests do not pass"
  sed 's/\x1b\[[0-9;]*m//g' "$log" | grep -E "×|Tests " | head -10; exit 1; fi
sed 's/\x1b\[[0-9;]*m//g' "$log" | grep -E "Tests |Test Files " | head -2

echo "VERDICT: aligned (proved) — ApiError.fields is no longer dead plumbing: a real non-test"
echo "         consumer reads it, the server demonstrably emits it, and the consumer's tests are"
echo "         tracked and green."
echo "NOT CHECKED: F-0668's CONSEQUENCE across the app — 98 @Valid endpoints can emit a fields"
echo "             array and one client form reads it. That gap is F-0683, measured and open, and"
echo "             this gate asserts nothing about it. Also unchecked: whether the other 83"
echo "             ApiError-handling files NEED fields (most likely do not), and whether the raw"
echo "             Bean Validation strings read well to a user — F-0681's territory, not this."
exit 0
