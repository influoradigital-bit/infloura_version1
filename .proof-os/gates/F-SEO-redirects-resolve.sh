#!/usr/bin/env bash
# Gate: every retired URL declared in scripts/marketing-routes.mjs
# (PERMANENT_REDIRECTS) must actually be redirected in BOTH places that serve
# one, and must point at a target that exists.
#
# WHY BOTH PLACES
# ---------------
# A retired URL is reached two different ways and only one of them touches the
# edge:
#   - a crawler, or a cold visitor pasting the link, hits the origin  -> the 301
#     in public/_redirects answers;
#   - a visitor already inside the booted SPA follows a stale in-app link -> the
#     edge is never consulted and only the <Navigate replace> in src/App.tsx
#     answers.
# Declaring the redirect in one file and not the other looks correct in casual
# testing (whichever path you happened to try works) while the other silently
# 404s. This gate requires both.
#
# WHY IT ALSO CHECKS THE TARGET
# -----------------------------
# A redirect to a URL that does not resolve is worse than no redirect: it burns
# the crawl, drops the accumulated signal anyway, and reports as a redirect
# error rather than an obvious 404.
#
# EXIT: 0 all redirects complete · 1 one is missing or broken · 2 could not run
set -uo pipefail

cd "$(dirname "$0")/../.." || { echo "GATE UNAVAILABLE: cannot reach project root"; exit 2; }

command -v node >/dev/null 2>&1 || { echo "GATE UNAVAILABLE: node not on PATH"; exit 2; }
[ -f scripts/marketing-routes.mjs ] || { echo "GATE UNAVAILABLE: scripts/marketing-routes.mjs missing"; exit 2; }
[ -f public/_redirects ] || { echo "GATE UNAVAILABLE: public/_redirects missing"; exit 2; }
[ -f src/App.tsx ] || { echo "GATE UNAVAILABLE: src/App.tsx missing"; exit 2; }

PAIRS=$(node -e '
import("./scripts/marketing-routes.mjs")
  .then((m) => {
    const list = m.PERMANENT_REDIRECTS;
    if (!Array.isArray(list) || list.length === 0) {
      console.error("PERMANENT_REDIRECTS is empty or not an array");
      process.exit(3);
    }
    for (const r of list) console.log(r.from + " " + r.to);
  })
  .catch((e) => { console.error(e.message); process.exit(3); });
') || { echo "GATE UNAVAILABLE: could not read PERMANENT_REDIRECTS"; exit 2; }

fail=0
count=0

while read -r from to; do
  [ -z "$from" ] && continue
  count=$((count + 1))
  echo "== $from -> $to =="

  # 1. edge 301
  if grep -qE "^[[:space:]]*${from}[[:space:]]+${to}[[:space:]]+301([[:space:]]|$)" public/_redirects; then
    echo "  ok   edge 301 in public/_redirects"
  else
    echo "  FAIL no '301' rule for $from -> $to in public/_redirects"
    fail=1
  fi

  # 2. the rule must sit ABOVE the SPA wildcard, or the wildcard swallows it.
  if grep -qE '^/\*' public/_redirects; then
    rule_line=$(grep -nE "^[[:space:]]*${from}[[:space:]]" public/_redirects | head -1 | cut -d: -f1)
    star_line=$(grep -nE '^/\*' public/_redirects | head -1 | cut -d: -f1)
    if [ -n "$rule_line" ] && [ -n "$star_line" ] && [ "$rule_line" -gt "$star_line" ]; then
      echo "  FAIL rule is on line $rule_line, BELOW the /* wildcard on line $star_line — the wildcard wins"
      fail=1
    fi
  fi

  # 3. client-side twin
  if grep -qF "path=\"$from\"" src/App.tsx && grep -qF "to=\"$to\"" src/App.tsx; then
    echo "  ok   in-app <Navigate> in src/App.tsx"
  else
    echo "  FAIL no in-app route redirecting $from -> $to in src/App.tsx"
    fail=1
  fi

  # 4. the target must exist: a registered route, or a blog post file.
  case "$to" in
    /blog/*)
      slug=${to#/blog/}
      if [ -f "src/content/blog/${slug}.md" ]; then
        echo "  ok   target post src/content/blog/${slug}.md exists"
      else
        echo "  FAIL redirect target $to has no src/content/blog/${slug}.md"
        fail=1
      fi
      ;;
    *)
      if grep -qF "path=\"$to\"" src/App.tsx; then
        echo "  ok   target route registered"
      else
        echo "  FAIL redirect target $to is not a registered route in src/App.tsx"
        fail=1
      fi
      ;;
  esac
done <<< "$PAIRS"

echo
if [ "$fail" -eq 0 ]; then
  echo "PASS — $count declared redirect(s), each served at the edge and in-app, each targeting a real page"
  echo "NOT CHECKED: that the deployed host actually honours public/_redirects (platform config,"
  echo "             only observable against a real deploy); redirect chains longer than one hop;"
  echo "             retired URLs that were never added to PERMANENT_REDIRECTS in the first place."
  exit 0
fi

echo "FAIL — see above"
exit 1
