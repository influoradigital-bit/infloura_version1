#!/usr/bin/env bash
# F-0308 gate — every blog post must exist as prerendered static HTML.
#
# scripts/prerender.mjs used to hardcode the blog slugs, so a post added to
# src/content/blog/ shipped with no static HTML, no meta description and no
# Article JSON-LD while the build printed "16/16" and exited 0. The count was
# self-referential: it compared the route list to itself, so it could never
# fail. This gate compares the two things that must agree — the content
# directory the app globs, and the dist/ output a crawler actually receives.
#
# Run AFTER `npm run build`.
# exit 0 = every post prerendered · 1 = at least one missing · 2 = no state.
set -uo pipefail
cd "$(dirname "$0")/../.." || exit 2

SRC="src/content/blog"
DIST="dist/blog"

[ -d "$SRC" ]  || { echo "no content dir: $SRC"   >&2; exit 2; }
[ -d "$DIST" ] || { echo "no dist dir: $DIST — run npm run build first" >&2; exit 2; }

missing=0
total=0
for f in "$SRC"/*.md; do
  [ -e "$f" ] || { echo "no posts in $SRC" >&2; exit 2; }
  slug="$(basename "$f" .md)"
  total=$((total + 1))
  out="$DIST/$slug/index.html"
  if [ ! -s "$out" ]; then
    echo "   $f -> $out MISSING — post is not prerendered; a crawler sees an empty shell"
    missing=$((missing + 1))
    continue
  fi
  # A prerendered post must carry the two tags the SEO depends on.
  if ! grep -q '<meta name="description"' "$out"; then
    echo "   $out has no <meta name=\"description\"> — prerender produced a shell, not a page"
    missing=$((missing + 1))
  elif ! grep -q '"@type":"Article"\|"@type": "Article"' "$out"; then
    echo "   $out has no Article JSON-LD — prerender produced a shell, not a page"
    missing=$((missing + 1))
  fi
done

echo "blog posts prerendered: $((total - missing))/$total"
echo "NOT CHECKED: whether the prerendered HTML is CORRECT — only that the file exists and carries a description and Article schema | whether the copy matches the markdown source | any route outside $DIST | whether the page is actually reachable once deployed"
[ "$missing" -eq 0 ] || exit 1
exit 0
