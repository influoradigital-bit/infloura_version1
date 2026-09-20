#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# portfolio-visibility-enforced.sh
# (F-0972 / F-0973 / F-0974 / F-0975 / F-0976 / F-0977 / F-0980)
#
# Exit 0 = proved · 1 = broken · 2 = unavailable (never green)
#
# Why this gate exists
# --------------------
# PortfolioVisibility carries nine creator-facing switches. Four of them --
# badges, platformStats, languages, contactForm -- sat on the record for months
# with ZERO readers anywhere in src/main: the browser hid those sections while
# the unauthenticated, permitAll GET /portfolio/{username} kept serving every
# handle, follower count, engagement rate, language and audience city the
# creator had switched off. A fifth, `stats`, was never gated at all.
#
# F-0980 -- and counting READERS was not enough either. This gate printed
# "ok: platformStats read 1 time(s) server-side" for weeks while FIVE producers
# in CreatorDiscoveryService read rows straight from PlatformStatRepository and
# emitted them onto the top-level `platforms` field of the brand DTOs. One
# reader satisfied check 1; a reader count can never see a PRODUCER that walks
# around the flag. Checks 1b and 1c below are producer checks, not reader
# counts.
#
# A grep gate alone cannot prove enforcement -- it proves only that a symbol is
# mentioned. So this gate has two halves, and the static half alone is NEVER
# green:
#   STATIC    : every visibility component is READ in src/main, no brand-facing
#               PRODUCER bypasses the platform-stats projection, the suspension
#               filters are present, and no dead control has crept back in.
#   BEHAVIOUR : PortfolioServiceVisibilityMatrixTest, which reflects over the
#               record and flips each flag off in turn, plus
#               CreatorDiscoveryPlatformStatsVisibilityTest, which asserts an
#               empty platforms list on all five brand-facing shapes. Both were
#               falsified against per-call-site mutants before being trusted.
#
# Anti-self-match: every STATIC check runs against a COMMENT-STRIPPED, string-
# literal-stripped view of src/main (lib/strip_comments.py). This very comment
# block names all four flags and cannot satisfy any check below.
# ---------------------------------------------------------------------------
set -uo pipefail

cd "$(dirname "$0")/../.." || { echo "GATE 2: cannot reach project root"; exit 2; }

LIB=".proof-os/gates/lib"
MAIN="influora-api/src/main/java"
SVC="$MAIN/com/influora/service/portfolio/PortfolioService.java"
DTO="$MAIN/com/influora/web/dto/portfolio/PortfolioDtos.java"
DISC="$MAIN/com/influora/service/CreatorDiscoveryService.java"
CPS="$MAIN/com/influora/service/CreatorProfileService.java"
MAPPER="$MAIN/com/influora/service/CreatorMapper.java"

for f in "$SVC" "$DTO" "$DISC" "$CPS" "$MAPPER" "$LIB/strip_comments.py" "$LIB/visibility_components.py"; do
  [ -f "$f" ] || { echo "GATE 2: missing $f -- cannot evaluate"; exit 2; }
done

if command -v python >/dev/null 2>&1; then PY=python
elif command -v python3 >/dev/null 2>&1; then PY=python3
else echo "GATE 2: no python on PATH"; exit 2; fi

fail=0
note() { echo "  $*"; }

# A temp FILE, not a shell variable: src/main strips to ~6.5MB, and piping that
# through `printf '%s' "$VAR" | grep` silently matched nothing -- which printed
# eight BROKEN flags against an already-correct fix. A false red is as bad as a
# false green; both make the gate untrustworthy.
STRIPPED="$(mktemp)"
GATELOG="$(mktemp)"
trap 'rm -f "$STRIPPED" "$GATELOG"' EXIT

"$PY" "$LIB/strip_comments.py" "$MAIN" > "$STRIPPED" || {
  echo "GATE 2: comment-stripping failed"; exit 2; }
[ "$(wc -l < "$STRIPPED")" -gt 1000 ] || {
  echo "GATE 2: comment-stripping produced implausibly little output"; exit 2; }

COMPONENTS="$("$PY" "$LIB/visibility_components.py" "$DTO")" || {
  echo "GATE 2: component parse failed"; exit 2; }
[ "$COMPONENTS" = "NO_RECORD" ] && { echo "GATE 2: PortfolioVisibility record not found"; exit 2; }
[ -n "$COMPONENTS" ] || { echo "GATE 2: parsed zero visibility components"; exit 2; }

echo "PortfolioVisibility components: $(echo "$COMPONENTS" | tr '\n' ' ')"

# --- 1. every PortfolioVisibility component is READ in src/main -------------
for c in $COMPONENTS; do
  # A reader is `.<component>()` applied to a getVisibility()/visibility()
  # expression, anywhere EXCEPT the DTO that declares the accessor itself.
  hits="$(grep -v "^$DTO:" "$STRIPPED" \
          | grep -cE "getVisibility\(\)\.$c\(\)|visibility\(\)\.$c\(\)" || true)"
  if [ "$hits" -eq 0 ]; then
    note "BROKEN: PortfolioVisibility.$c has NO server-side reader -- browser-only switch (F-0972)"
    fail=1
  else
    note "ok: $c read $hits time(s) server-side"
  fi
done

# --- 1b. PRODUCER: the brand mapper cannot accept platform ENTITIES ----------
# F-0980. This check does not police call sites at all -- it delegates
# enforcement to javac and then proves the delegation is still in place.
# CreatorMapper#toResponse takes the already-projected List<PlatformStatResponse>
# that only PortfolioService#getVisiblePlatformStats can produce (it applies the
# platformStats flag). A sixth brand-facing call site added next month that tries
# to hand it raw repository rows does not fail a grep; it fails to COMPILE.
# That is the only kind of check that survives a caller nobody anticipated --
# and the reason a grep on `toPlatformResponse` was REJECTED here: four of the
# five F-0980 producers never mentioned that symbol, so such a gate would have
# shipped the bug it was written to catch.
dto_params="$(grep -cE "^$MAPPER:.*List<PlatformStatResponse> platforms" "$STRIPPED" || true)"
entity_params="$(grep -cE "^$MAPPER:.*List<PlatformStat> platforms" "$STRIPPED" || true)"
if [ "$entity_params" -ne 0 ]; then
  note "BROKEN: CreatorMapper.toResponse still accepts List<PlatformStat> -- a brand-facing caller can hand it ungated repository rows and javac will not object (F-0980)"
  fail=1
elif [ "$dto_params" -lt 2 ]; then
  note "BROKEN: CreatorMapper.toResponse does not declare List<PlatformStatResponse> platforms on both overloads (found $dto_params) -- the compile-time guarantee behind F-0980 is gone"
  fail=1
else
  note "ok: the brand mapper takes the projected DTO on $dto_params overload(s); entity rows will not compile"
fi

# --- 1c. PRODUCER: one repository, one owner --------------------------------
# F-0980. PlatformStatRepository is the raw, ungated source of platform rows.
# Only the projection owner may read it for a reader-facing shape. This is a
# DIRECTORY rule wherever it can be: a filename allow-list is how the hole gets
# reopened, because the next engineer adds their file to the list in the same
# commit as the bypass and CI stays green.
#
# The three named service-root files are audience exemptions, not conveniences,
# and each must stay justified by its AUDIENCE -- not by being in the list:
#   CreatorProfileService     -- the creator reading their OWN profile; no
#                                visibility flag was ever written about the
#                                creator's own view of their own data.
#   MetaConnectionService     -- the creator's own Meta connection status.
#   ExternalCreatorLinkService-- WRITE path: recomputes the follower rollup; it
#                                emits no reader-facing platform list.
# service/admin/** and job/** are directory exemptions for the same reason:
# admin moderation and scheduled aggregation are not brand-facing or public
# audiences. Adding a name here without that argument re-opens F-0980.
psr_allowed() {
  case "$1" in
    */com/influora/repository/PlatformStatRepository.java) return 0 ;;
    */com/influora/service/portfolio/PortfolioService.java) return 0 ;;
    */com/influora/service/admin/*) return 0 ;;
    */com/influora/job/*) return 0 ;;
    */com/influora/service/CreatorProfileService.java) return 0 ;;
    */com/influora/service/MetaConnectionService.java) return 0 ;;
    */com/influora/service/ExternalCreatorLinkService.java) return 0 ;;
  esac
  return 1
}

psr_files="$(grep -E "platformStatRepository|PlatformStatRepository" "$STRIPPED" \
             | sed 's/:.*//' | sort -u || true)"
[ -n "$psr_files" ] || { echo "GATE 2: PlatformStatRepository not mentioned anywhere in the stripped view -- the check cannot be evaluated"; exit 2; }

psr_offenders=""
for p in $psr_files; do
  psr_allowed "$p" || psr_offenders="$psr_offenders $p"
done
if [ -n "$psr_offenders" ]; then
  note "BROKEN: PlatformStatRepository is reachable outside the projection owner --$psr_offenders"
  note "        Platform rows must come from PortfolioService#getVisiblePlatformStats, which applies the platformStats flag (F-0980)"
  fail=1
else
  note "ok: PlatformStatRepository confined to the projection owner, the creator's own reads, admin and jobs"
fi

# --- 2. suspension filters on the public portfolio paths --------------------
# getPublic (via requireDiscoverablePortfolio) and recordPublicView both need it.
susp="$(grep -c "^$SVC:.*isDiscoverable() || profile.isSuspended()" "$STRIPPED" || true)"
if [ "$susp" -lt 2 ]; then
  note "BROKEN: PortfolioService filters isSuspended() on only $susp path(s) -- a moderated creator keeps a live public page (F-0975)"
  fail=1
else
  note "ok: suspension filtered on $susp portfolio path(s)"
fi

# --- 2b. the resolver itself filters suspension -----------------------------
# Independent verification (2026-09-20) found the suspension guarantee held only
# because all three callers of requireProfileByUsername happened to guard it. A
# fourth caller would have been unprotected and nothing would have failed. The
# filter now lives in the resolver; this check keeps it there.
if ! grep -qE "^$CPS:.*\.filter\(.*!.*isSuspended\(\)\)" "$STRIPPED"; then
  note "BROKEN: CreatorProfileService.requireProfileByUsername does not itself filter isSuspended -- the guarantee is a property of its callers, not the method (F-0975)"
  fail=1
else
  note "ok: the public-handle resolver filters suspension itself"
fi

# --- 3. the brand endpoint honours rateCard visibility ----------------------
if ! grep -qE "^$DISC:.*rateCardVisibilityOf\(" "$STRIPPED"; then
  note "BROKEN: CreatorDiscoveryService never reads rateCard visibility -- rateMin/rateMax leak past 'hidden' (F-0974)"
  fail=1
else
  note "ok: brand endpoint consults rateCard visibility"
fi

# --- 3b. the brand DTO does not emit the raw cover column -------------------
# F-0977: uploadCover persists a bare R2 object key, not a URL. getPublicProfile
# used to emit profile.getCoverImageUrl() directly, so every brand-side cover was
# an unservable key -- invisible only because that page never rendered it.
if grep -qE "^$DISC:.*profile\.getCoverImageUrl\(\)" "$STRIPPED"; then
  note "BROKEN: CreatorDiscoveryService emits the raw cover column -- uploadCover stores an R2 object key, not a URL; it must come through the presigned portfolio view (F-0977)"
  fail=1
else
  note "ok: brand cover photo is served presigned, not as a raw object key"
fi

# --- 4. no re-declared dead control on the contact request ------------------
captcha="$(grep -c "captchaToken" "$STRIPPED" || true)"
if [ "$captcha" -eq 1 ]; then
  note "BROKEN: captchaToken declared once and never read -- a documented control that does not exist (F-0976)"
  fail=1
elif [ "$captcha" -gt 1 ]; then
  note "ok: captchaToken declared AND read ($captcha occurrences) -- a real verifier exists"
else
  note "ok: captchaToken absent"
fi

# --- 5. BEHAVIOUR: the reflective matrix must actually pass -----------------
if [ "${PORTVIS_STATIC_ONLY:-0}" = "1" ]; then
  echo "GATE 2: static half only (PORTVIS_STATIC_ONLY=1) -- grep cannot prove enforcement"
  exit 2
fi
command -v mvn >/dev/null 2>&1 || { echo "GATE 2: mvn not on PATH -- behaviour half unevaluated"; exit 2; }

( cd influora-api && mvn -o -q \
    -Dtest=PortfolioServiceVisibilityMatrixTest,PortfolioServicePublicVisibilityTest,CreatorDiscoveryServiceTest,CreatorDiscoveryPlatformStatsVisibilityTest \
    -DfailIfNoTests=false test ) > "$GATELOG" 2>&1
mvn_rc=$?
if [ "$mvn_rc" -ne 0 ]; then
  note "BROKEN: visibility test suite failed (mvn exit $mvn_rc)"
  grep -E "^\[ERROR\]   " "$GATELOG" | head -10
  fail=1
else
  note "ok: PortfolioServiceVisibilityMatrixTest + CreatorDiscoveryPlatformStatsVisibilityTest (all five brand shapes) + both regression suites pass"
fi

if [ "$fail" -ne 0 ]; then
  echo "GATE 1: portfolio visibility is NOT enforced server-side"
  exit 1
fi
echo "GATE 0: all $(echo "$COMPONENTS" | wc -w | tr -d ' ') visibility flags enforced server-side; suspension, rate-card and dead-control checks pass"
exit 0
