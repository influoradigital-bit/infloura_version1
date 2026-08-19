#!/usr/bin/env bash
# Gate for F-0338 (dead-route) and F-0340 (funnel-dead-end), plus the retired
# "escrow" vocabulary.
#
# Two things must hold on the public marketing surface:
#   1. the retired "escrow" wording must not reappear in rendered copy;
#   2. every link in the shared header/footer must resolve to a route that is
#      registered AND publicly reachable.
#
# F-0338: SiteFooter linked /refund-policy /disputes /grievance /kyc /tds
#         /disclosure from every page; none were registered, so each fell
#         through to the /:handle catch-all and served a soft 404 at HTTP 200.
# F-0340: /about's secondary CTA pointed at /brand/discover, an AUTHENTICATED
#         route, bouncing public visitors to a login screen at peak intent.
#
# WHY A GATE AND NOT A CODE REVIEW
# --------------------------------
# The word was removed from customer-facing copy in T-SEOCRO-0819 as a product
# decision. Removal by hand across ~14 files is exactly the kind of change that
# comes back one merge later, from a branch written before the decision, and
# nobody notices because the page still renders fine. The only difference is a
# meta description or one accordion answer — invisible in a diff review, visible
# to every crawler.
#
# WHAT IS DELIBERATELY OUT OF SCOPE
# ---------------------------------
# Logged-in app screens, hooks, API clients, admin console, and every code
# identifier (useEscrowFund, EscrowService, the
# escrow-and-refund-policy.md filename, DB columns). The Java API serialises
# escrow-named JSON fields; renaming those is a contract change, not a copy
# change, and is explicitly NOT what this gate is asking for. The scope here is
# only what an unauthenticated visitor or a crawler can read.
#
# EXIT: 0 clean · 1 a violation was found · 2 could not run
set -uo pipefail

cd "$(dirname "$0")/../.." || { echo "GATE UNAVAILABLE: cannot reach project root"; exit 2; }

command -v grep >/dev/null 2>&1 || { echo "GATE UNAVAILABLE: grep missing"; exit 2; }

fail=0

# The retired term, case-insensitive, as an ERE.
TERM='[Ee][Ss][Cc][Rr][Oo][Ww]'

# Reduce a file to the text a visitor or crawler can actually READ.
#
# Dropped before matching:
#   - `//` line comments and `/** ... */` block-comment continuation lines
#     (` * ...`) — these EXPLAIN the rename and must be allowed to name the old
#     term, otherwise the gate punishes documenting its own reason for existing;
#   - `#` comments, for the plain-text crawler files;
#   - any line carrying the explicit `[[retired-term-ok]]` marker. Exactly one
#     line uses it today: the note in public/llms.txt that tells AI systems the
#     old wording is retired and where the old URL now points. That note is
#     valuable precisely BECAUSE it names the old term, so it is opted out by
#     hand rather than by a pattern that might silently cover something else.
strip_comments() {
  grep -v '\[\[retired-term-ok\]\]' "$1"     | sed -E 's://.*::; s:^[[:space:]]*\*.*::; s:^[[:space:]]*/\*.*::; s:^[[:space:]]*#.*::'
}

# --- 1. the retired vocabulary --------------------------------------------
# Public marketing pages + the files crawlers read directly.
SURFACE_FILES=(
  index.html
  public/llms.txt
  public/robots.txt
  src/pages/landing.tsx
  src/pages/pricing.tsx
  src/pages/about.tsx
  src/pages/contact.tsx
  src/pages/how-it-works-brands.tsx
  src/pages/how-it-works-creators.tsx
  src/pages/blog/index.tsx
  src/pages/blog/post.tsx
  src/components/site/SiteHeader.tsx
  src/components/site/SiteFooter.tsx
  src/components/site/FaqSection.tsx
  src/components/site/FunnelCta.tsx
  src/components/site/TrustBar.tsx
  src/components/site/trust-items.ts
)

echo "== retired vocabulary in rendered marketing copy =="
for f in "${SURFACE_FILES[@]}"; do
  [ -f "$f" ] || { echo "GATE UNAVAILABLE: expected file missing: $f"; exit 2; }
  if strip_comments "$f" | grep -nE "$TERM" >/dev/null; then
    echo "FAIL  $f"
    strip_comments "$f" | grep -nE "$TERM" | sed 's/^/        /'
    fail=1
  fi
done

# src/pages/features/*.tsx and the markdown content are checked wholesale.
for f in src/pages/features/*.tsx src/content/blog/*.md; do
  [ -e "$f" ] || continue
  if strip_comments "$f" | grep -nE "$TERM" >/dev/null; then
    echo "FAIL  $f"
    strip_comments "$f" | grep -nE "$TERM" | sed 's/^/        /'
    fail=1
  fi
done

[ "$fail" -eq 0 ] && echo "  none found"

# --- 2. no marketing page may link to a route that does not exist ---------
# Every /path in the shared header + footer must be registered in App.tsx.
# This is what was actually broken before T-SEOCRO-0819: the footer linked six
# policy routes that had never been added, so each fell through to the
# /:handle creator-portfolio catch-all and rendered "creator not found" with a
# 200 status — a soft 404 on every page of the site.
echo
echo "== nav links resolve to a registered route =="
NAV_LINKS=$(grep -ohE "href: '/[^']*'|to=\"/[^\"]*\"" \
  src/components/site/SiteHeader.tsx src/components/site/SiteFooter.tsx 2>/dev/null \
  | grep -oE "/[^'\"]*" | sort -u)

if [ -z "$NAV_LINKS" ]; then
  echo "GATE UNAVAILABLE: extracted zero nav links — the extraction regex no longer matches."
  exit 2
fi

missing=0
for link in $NAV_LINKS; do
  # Skip anchors, external, and the bare root.
  case "$link" in
    /|/#*|//*) continue ;;
  esac
  if ! grep -qE "path=\"$link\"" src/App.tsx; then
    echo "FAIL  $link is linked from the site nav but has no <Route path=\"$link\"> in src/App.tsx"
    echo "        (it will fall through to the /:handle catch-all and render a soft 404)"
    missing=1
    fail=1
  fi
done
[ "$missing" -eq 0 ] && echo "  all nav links resolve"

# --- 3. no public marketing page may link into an AUTHENTICATED zone ------
# F-0340. /brand/*, /creator/* and /admin/* are behind a route guard. A public
# page linking into one sends an unauthenticated visitor to a login wall — a
# dead end at the exact moment they showed intent, and for a crawler an
# indexable page linking to content it can never fetch.
#
# The registration and sign-in entry points are the deliberate exceptions:
# those ARE the conversion targets, and they render fine logged out.
echo
echo "== public pages do not link into authenticated zones =="

PUBLIC_ENTRY_POINTS='^/(brand|creator)/(register|login|forgot-password)$|^/reset-password$'

MARKETING_PAGES="src/pages/landing.tsx src/pages/pricing.tsx src/pages/about.tsx src/pages/contact.tsx src/pages/how-it-works-brands.tsx src/pages/how-it-works-creators.tsx src/pages/blog/index.tsx src/pages/blog/post.tsx src/components/site/SiteHeader.tsx src/components/site/SiteFooter.tsx src/pages/features/secure-payments.tsx src/pages/features/deal-room.tsx src/pages/features/hype.tsx"

leaked=0
for f in $MARKETING_PAGES; do
  [ -f "$f" ] || continue
  # `to="/x"` (JSX) and `to: '/x'` (data constants used by FunnelCta/nav arrays).
  links=$(grep -ohE "to=\"/(brand|creator|admin)/[^\"]*\"|to: '/(brand|creator|admin)/[^']*'" "$f" 2>/dev/null           | grep -oE "/(brand|creator|admin)/[^\"']*" | sort -u)
  for link in $links; do
    if ! echo "$link" | grep -qE "$PUBLIC_ENTRY_POINTS"; then
      echo "  FAIL $f links to $link, which is behind the auth guard"
      echo "         (a logged-out visitor lands on a login wall; use a public page or a register route)"
      leaked=1
      fail=1
    fi
  done
done
[ "$leaked" -eq 0 ] && echo "  no public page links into an authenticated zone"

echo
if [ "$fail" -eq 0 ]; then
  echo "PASS — marketing surface clean"
  echo "NOT CHECKED: logged-in app screens, code identifiers, API field names (out of scope by decision);"
  echo "             whether the copy is GOOD, only that the retired term is absent;"
  echo "             whether a guarded route is guarded correctly, only that public pages avoid it;"
  echo "             links in marketing pages not listed in MARKETING_PAGES above."
  exit 0
fi

echo "FAIL — see above"
exit 1
