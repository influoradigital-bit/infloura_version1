#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# gate: stale-runtime-copy
# ledger records: F-0053, F-0426, F-0428   (same class as the closed F-0024)
#
# THE CLASS
#   proof-os ships gates. This project keeps its own copy of them in
#   .proof-os/gates/ and RUNS THAT COPY. Nothing compared the two, so the
#   project copy silently rotted while every downstream verdict stayed green:
#     F-0053  .proof-os/gates/registry_render.py was still proof-os 0.3.x and
#             rejected may_claim "echo" for 7 services -- a false RED that the
#             shipped 0.4.x copy of the same gate does not produce.
#     F-0426  .proof-os/gates/citations.py was a superseded stub that exits 0
#             on a document with zero citations -- a false GREEN.
#     F-0428  ~9 shared gates (deck, frontmatter, graph_source, meta_length,
#             registry_render, version_assert, e2e.sh, frontend.sh,
#             security.sh) were all far smaller in-project than canonical.
#   None of this is visible to any gate that reads the PRODUCT. All of it is
#   visible the moment you hash the gate files themselves.
#
# WHAT IS ASSERTED
#   1. a canonical (shipped) proof-os tree with a parseable MANIFEST.sha256
#      exists -- otherwise there is NO baseline and the answer is exit 2.
#   2. THE BASELINE IS CHOSEN BY COVERAGE, NEVER BY VERSION STRING. Every
#      discovered candidate tree is scored by how many files in this project's
#      gates dir its manifest actually names; the widest baseline wins and the
#      version string is only a tie-break. See "THE REFUTED SELECTION" below.
#   3. each canonical gate is authenticated against that manifest BEFORE it is
#      used as a baseline. A canonical file that fails its own manifest is
#      excluded and named -- never used to bless a project copy.
#   4. every project gate sharing a name with an authenticated canonical gate
#      is byte-identical to it. Any difference is DRIFT -> exit 1.
#   5. a copy of a shipped gate hiding in a SUBDIRECTORY of the project gates
#      dir (gates/legacy/citations.py) is drift too -- same name, same class,
#      previously invisible because only the top level was walked.
#   6. the shared modules _oracles.py / _rc.py / _rc.sh are PRESENT in the
#      project copy. Their absence is swallowed by the `except Exception: pass`
#      at the top of every gate, so only this check can ever see it -> exit 1.
#
# THE REFUTED SELECTION  (why this file was rewritten)
#   An independent verifier defeated the previous revision without touching a
#   single one of the 4 real drifts. It dropped a directory into
#   .proof-os/.runtime/os999/ holding ONE already-matching gate and a two-line
#   MANIFEST.sha256 whose header read "proof-os 0.9.9". The old selector ranked
#   candidates purely on that version string with no floor on what the manifest
#   covered, so the two-line tree beat the real 26-record 0.4.2 tree, the gate
#   compared 1 file of 16, and printed "VERDICT: aligned (proved)", exit 0 --
#   on a tree still carrying all 4 unrepaired drifts. The old vacuous guard
#   (COMPARED -eq 0) could not see it: 1 is not 0.
#   .proof-os/.runtime/ already holds several unpacked trees, so a partial or
#   interrupted unpack reaches that state by accident, not only by attack.
#   Five things close it, all below:
#     (a) rank candidates by shared-name coverage; version only breaks ties.
#         A higher version string can never buy a smaller baseline.
#     (b) the vacuous guard is now a COVERAGE guard: if the winning baseline
#         authenticates fewer project gates than the union of what ALL
#         discovered trees name, the answer is exit 2 ("covers N of M"), never
#         "proved".
#     (c) a floor: a baseline authenticating fewer than MIN_BASELINE project
#         gates is refused outright, and the coverage is stated on the VERDICT
#         line either way so a thin one can never pass silently.
#     (d) --canonical and PROOF_OS_CANONICAL no longer suppress discovery. They
#         say which tree is the baseline; they cannot shrink the set of project
#         gates that baseline has to answer for, or the same evasion just moves
#         from "plant a tree" to "point at one".
#     (e) between candidates of EQUAL coverage, a bare gates/ dir loses to one
#         that also carries rules/ scripts/ templates/ skills/. A real unpack
#         has the rest of the payload; a hand-built baseline usually does not.
#
#   WHAT THIS STILL CANNOT DO, stated plainly: the baseline lives under
#   .proof-os/.runtime/, which anything that can write this repo can also
#   write. A forged tree that names EVERY project gate, ships the current
#   (drifted) bytes as its own, copies a rules/ dir, and claims a high version
#   wins the ranking and prints "aligned" -- verified, it does. Coverage
#   ranking kills the thin/partial forgery, which is the accidental case and
#   the one the verifier used; it cannot kill a complete one. Only a version
#   pinned OUTSIDE the repo (a signed plugin install this project cannot mint)
#   closes that, and no amount of shell in this file substitutes for it.
#
# WHAT IS DELIBERATELY *NOT* A FINDING
#   A canonical gate with NO project copy at all. On this repo that is
#   build.mvn.sh / build.node.sh / build.py / confirm.py / liveness.py: each has
#   live rc records in .proof-os/rc/, so the project plainly runs them -- from
#   the canonical tree, which is the healthy arrangement, not a stale copy. It
#   is printed as "adopted, run from canonical" (or "not adopted" where there is
#   no rc record at all, e.g. build.go.sh in a Java/TS repo) and is NOT counted.
#   Only a copy that EXISTS and DIVERGED can be a stale runtime copy.
#
#   Drift is reported even when it looks like a deliberate local customisation
#   (this repo's build.sh is larger than canonical). There is no opt-out list on
#   purpose: an exemption file is how this gate would be talked down to zero.
#   A customised copy is still a copy no manifest can vouch for -- if the change
#   is wanted, it belongs upstream or in a project-only F-*.sh gate.
#
#   A losing candidate tree is not a finding either. It is printed on an
#   "ignored" line with its version, its record count and its coverage, so the
#   os999-shaped tree is visible in the output instead of silently winning.
#
# EXIT
#   0  every authenticated canonical gate present here is byte-identical, and
#      the baseline that proved it covered every project gate any discovered
#      canonical tree can speak for
#   1  at least one finding (printed as file:line)
#   2  UNAVAILABLE, which is NOT a pass: no sha256 tool, no canonical tree, no
#      manifest, no project gates dir, ZERO candidate files compared, a baseline
#      under the floor, or a baseline narrower than the union of the trees found
#
# usage: stale-runtime-copy.sh [--canonical DIR] [--project DIR] [-v]
# ---------------------------------------------------------------------------

# Deliberately no `set -e`: every failure path below must reach a explicit
# exit code, and a gate that dies mid-way with the shell's exit status is
# exactly the "could not check, therefore green" failure being guarded against.
set -u

CANON_ARG=""
PROJECT_ARG=""
VERBOSE=0

# A baseline authenticating fewer than this many of THIS project's gate files
# is refused. Every real proof-os tree ships ~26 gates and this repo shares 16
# of them; the refuted evasion shared 1. Not settable from the environment on
# purpose -- a knob that lowers this floor is an opt-out, and an opt-out is how
# this gate gets talked down to zero.
MIN_BASELINE=5

usage() { echo "usage: stale-runtime-copy.sh [--canonical DIR] [--project DIR] [-v]"; }

while [ $# -gt 0 ]; do
  case "$1" in
    --canonical) [ $# -ge 2 ] || { echo "* --canonical needs a directory"; usage; exit 2; }
                 CANON_ARG="$2"; shift 2 ;;
    --project)   [ $# -ge 2 ] || { echo "* --project needs a directory"; usage; exit 2; }
                 PROJECT_ARG="$2"; shift 2 ;;
    -v|--verbose) VERBOSE=1; shift ;;
    -h|--help)   usage; exit 0 ;;
    *) echo "* unknown argument: $1"; usage; exit 2 ;;
  esac
done

# --------------------------------------------------------------- self-locate
# Resolve the script's own directory so the gate behaves identically from any
# cwd (F-0053 was found by a run from the repo root; CI runs it from elsewhere).
# dir_of avoids the external `dirname`: on a stripped PATH `dirname` returns
# nothing, `cd ""` silently succeeds, and the gate would then compare the wrong
# directory while looking healthy -- the same shape of quiet wrongness as the
# defect itself.
dir_of() {
  case "$1" in
    */*) d=${1%/*}; [ -n "$d" ] || d="/"; echo "$d" ;;
    *)   echo "." ;;
  esac
}
SELF="$0"
_guard=0
while [ -L "$SELF" ] && [ $_guard -lt 40 ]; do
  _link=$(readlink "$SELF" 2>/dev/null) || break
  [ -n "$_link" ] || break
  case "$_link" in
    /*) SELF="$_link" ;;
    *)  SELF="$(dir_of "$SELF")/$_link" ;;
  esac
  _guard=$((_guard + 1))
done
_selfdir=$(dir_of "$SELF")
HERE=""
if [ -n "$_selfdir" ] && [ -d "$_selfdir" ]; then
  HERE=$(cd "$_selfdir" 2>/dev/null && pwd)
fi

# ------------------------------------------------------------ sha256 backend
HASHER=""
# PROOF_OS_HASHER pins one backend. It exists so the openssl/certutil paths can
# actually be exercised instead of being dead code nobody has ever run; an
# unknown or absent tool is exit 2, never a silent fallback.
if [ -n "${PROOF_OS_HASHER:-}" ]; then
  case "$PROOF_OS_HASHER" in
    sha256sum|shasum|openssl|certutil)
      if command -v "$PROOF_OS_HASHER" >/dev/null 2>&1; then
        HASHER="$PROOF_OS_HASHER"
      else
        echo "* PROOF_OS_HASHER=$PROOF_OS_HASHER is not on PATH - unavailable"; exit 2
      fi ;;
    *) echo "* PROOF_OS_HASHER=$PROOF_OS_HASHER is not a known backend"
       echo "  (sha256sum | shasum | openssl | certutil) - unavailable"; exit 2 ;;
  esac
fi
if [ -n "$HASHER" ]; then :
elif command -v sha256sum >/dev/null 2>&1;    then HASHER="sha256sum"
elif command -v shasum >/dev/null 2>&1;       then HASHER="shasum"
elif command -v openssl >/dev/null 2>&1;      then HASHER="openssl"
elif command -v certutil >/dev/null 2>&1;     then HASHER="certutil"
fi
if [ -z "$HASHER" ]; then
  echo "* no sha256 tool found (sha256sum / shasum / openssl / certutil) - unavailable"
  echo "  a drift check without a hash is not a check; refusing to report green"
  exit 2
fi

is_hex64() {  # $1 -> 0 if exactly 64 lowercase-able hex chars
  case "$1" in
    ????????????????????????????????????????????????????????????????)
      case "$1" in *[!0-9a-fA-F]*) return 1 ;; *) return 0 ;; esac ;;
    *) return 1 ;;
  esac
}

hash_of() {
  # $1 = path -> lowercase hex sha256 on stdout, empty string on failure.
  [ -f "$1" ] || { echo ""; return 1; }
  case "$HASHER" in
    sha256sum) _h=$(sha256sum -- "$1" 2>/dev/null); _h=${_h%% *} ;;
    shasum)    _h=$(shasum -a 256 -- "$1" 2>/dev/null); _h=${_h%% *} ;;
    openssl)   _h=$(openssl dgst -sha256 "$1" 2>/dev/null); _h=${_h##* } ;;
    certutil)  _h=$(certutil -hashfile "$1" SHA256 2>/dev/null | tr -d '\r ' | sed -n 2p) ;;
  esac
  case "$_h" in *[A-F]*) _h=$(printf '%s' "$_h" | tr 'A-F' 'a-f') ;; esac
  if is_hex64 "$_h"; then printf '%s\n' "$_h"; else echo ""; fi
}

hash_batch() {
  # $1 = directory, stdin = newline-separated RELATIVE paths (no spaces).
  # stdout = "relpath|hash" lines. One process for the whole set instead of one
  # per file: this gate now hashes several trees and a process spawn costs
  # ~0.2s on Windows, which is the difference between 9s and 90s.
  _hb_dir="$1"
  _hb_names=$(cat)
  [ -n "$_hb_names" ] || return 0
  ( cd "$_hb_dir" 2>/dev/null || exit 0
    set --
    _hb_ifs=$IFS
    IFS='
'
    for _n in $_hb_names; do
      IFS=$_hb_ifs
      [ -n "$_n" ] && [ -f "$_n" ] && set -- "$@" "$_n"
      IFS='
'
    done
    IFS=$_hb_ifs
    [ $# -gt 0 ] || exit 0
    case "$HASHER" in
      sha256sum) sha256sum -- "$@" 2>/dev/null |
                   awk '{ h = tolower($1); p = $0
                          sub(/^[^ \t]+[ \t]+\**/, "", p)
                          if (h ~ /^[0-9a-f]{64}$/ && p != "") print p "|" h }' ;;
      shasum)    shasum -a 256 -- "$@" 2>/dev/null |
                   awk '{ h = tolower($1); p = $0
                          sub(/^[^ \t]+[ \t]+\**/, "", p)
                          if (h ~ /^[0-9a-f]{64}$/ && p != "") print p "|" h }' ;;
      openssl)   openssl dgst -sha256 "$@" 2>/dev/null |
                   awk '{ h = tolower($NF); p = $0
                          sub(/^[^(]*\(/, "", p); sub(/\)[ \t]*=.*$/, "", p)
                          if (h ~ /^[0-9a-f]{64}$/ && p != "") print p "|" h }' ;;
      certutil)  for _f in "$@"; do
                   _ch=$(certutil -hashfile "$_f" SHA256 2>/dev/null | tr -d '\r ' | sed -n 2p)
                   _ch=$(printf '%s' "$_ch" | tr 'A-F' 'a-f')
                   case "$_ch" in
                     ????????????????????????????????????????????????????????????????)
                       case "$_ch" in *[!0-9a-f]*) : ;; *) printf '%s|%s\n' "$_f" "$_ch" ;; esac ;;
                   esac
                 done ;;
    esac )
}

tbl_get() {   # $1 = "name|hash" table, $2 = name -> sets TBL_VAL ("" if absent)
  # Sets a variable instead of echoing: `x=$(tbl_get ...)` would fork a subshell
  # per lookup, and at ~0.2s a fork on Windows that alone tripled this gate's
  # wall time. Nothing here needs a subprocess.
  TBL_VAL=""
  _tg="
$1"
  case "$_tg" in
    *"
$2|"*) _tr=${_tg##*"
$2|"}; TBL_VAL=${_tr%%"
"*} ;;
  esac
}

has_name() {  # $1 = newline-separated list, $2 = item -> 0 if present
  case "
$1
" in *"
$2
"*) return 0 ;; *) return 1 ;; esac
}

command -v awk >/dev/null 2>&1 || {
  echo "* awk not found - unavailable"; exit 2; }

# ------------------------------------------------------------- project gates
if [ -n "$PROJECT_ARG" ]; then
  PROJECT_GATES=$(cd "$PROJECT_ARG" 2>/dev/null && pwd) || PROJECT_GATES=""
else
  if [ -z "$HERE" ]; then
    echo "* cannot resolve this script's own directory from \$0=$0 - unavailable"
    echo "  (pass --project DIR to say which gates dir to check)"
    exit 2
  fi
  PROJECT_GATES="$HERE"
fi
if [ -z "$PROJECT_GATES" ] || [ ! -d "$PROJECT_GATES" ]; then
  echo "* project gates dir not found (${PROJECT_ARG:-$HERE}) - unavailable"
  exit 2
fi
STORE=$(dir_of "$PROJECT_GATES")   # .proof-os

# ----------------------------------------------------------- canonical tree
manifest_version() {   # header line: "# proof-os 0.4.2 - ..."
  awk '/^#/ { if (match($0, /proof-os[ \t]+[0-9][0-9A-Za-z.-]*/)) {
                s = substr($0, RSTART, RLENGTH); sub(/^proof-os[ \t]+/, "", s);
                print s; exit } }' "$1" 2>/dev/null
}
version_key() {        # 0.4.10 -> 000000000400010, for a plain string compare
  echo "$1" | awk '{ n = split($0, p, "."); k = "";
                     for (i = 1; i <= 3; i++) { v = (i <= n ? p[i] : "0");
                       gsub(/[^0-9]/, "", v); if (v == "") v = 0;
                       k = k sprintf("%05d", v) } print k }'
}
manifest_entries() {   # $1 = manifest -> "hash name" for top-level gates/ files
  awk '
    /^[ \t]*#/ { next }
    /^[ \t]*$/ { next }
    {
      h = $1; sub(/^[ \t]*/, "", h)
      if (h !~ /^[0-9a-fA-F]{64}$/) next
      path = $0
      sub(/^[ \t]*[0-9a-fA-F]{64}[ \t]+\**/, "", path)
      gsub(/\\/, "/", path); gsub(/\r/, "", path)
      if (path ~ /^gates\//) {
        name = path; sub(/^gates\//, "", name)
        if (name !~ /\//) print tolower(h) " " name
      }
    }' "$1" 2>/dev/null
}

CANDIDATES=""
add_cand() { [ -n "${1:-}" ] && CANDIDATES="$CANDIDATES
$1"; }

# --canonical PINS which tree is the baseline; it does NOT stop the others from
# being discovered. Discovery still runs so the coverage union below is computed
# over everything on disk -- otherwise `--canonical <narrow tree>` would be the
# same evasion by another door: pin a tree that names 6 of this project's 16
# gates and the other 10 vanish from the question. Pinned or not, the baseline
# has to answer for every project gate SOME canonical tree can speak for.
FORCED_ARG=""
[ -n "$CANON_ARG" ] && { FORCED_ARG="$CANON_ARG"; add_cand "$CANON_ARG"; }
add_cand "${PROOF_OS_CANONICAL:-}"
add_cand "${PROOF_OS_HOME:-}"
add_cand "${PROOF_OS_PLUGIN_ROOT:-}"
# the runtime copies this project unpacks the shipped plugin into
if [ -d "$STORE/.runtime" ]; then
  for d in "$STORE/.runtime"/*; do add_cand "$d"; done
  add_cand "$STORE/.runtime"
fi
add_cand "$(dir_of "$STORE")/proof-os"
# and a plugin-managed install, if one is on this machine
if [ -n "${HOME:-}" ] && [ -d "$HOME/.claude/plugins" ]; then
  for d in $(find "$HOME/.claude/plugins" -maxdepth 5 -type d -name 'proof-os*' 2>/dev/null); do
    add_cand "$d"
  done
fi
FORCED=""
if [ -n "$FORCED_ARG" ]; then
  FORCED=$(cd "$FORCED_ARG" 2>/dev/null && pwd) || FORCED=""
  if [ -z "$FORCED" ]; then
    echo "* --canonical $FORCED_ARG is not a directory - unavailable"; exit 2
  fi
fi

# ------------------------------------------------- score every candidate tree
# Coverage, not version, decides. For each candidate: how many files in THIS
# project's gates dir does its manifest actually name and actually ship?
# The union of those sets across all candidates is what the winner is later
# held to, so a narrow tree cannot shrink the question being asked.
CANONICAL=""; CANON_VER=""; BEST_KEY=""; BEST_COV=-1; BEST_MAN=-1; BEST_PAY=-1
UNION_NAMES=""; UNION_COV=0
CAND_REPORT=""      # lines: cov|man|ver|payload|abs
SEEN=""
TRIED=""
N_DISCOVERED=0
N_USABLE=0

OLDIFS=$IFS
IFS='
'
for c in $CANDIDATES; do
  IFS=$OLDIFS
  if [ -n "$c" ]; then
    abs=$(cd "$c" 2>/dev/null && pwd) || abs=""
    if [ -n "$abs" ]; then
      N_DISCOVERED=$((N_DISCOVERED + 1))
      case "
$SEEN
" in *"
$abs
"*) abs="" ;; *) SEEN="$SEEN
$abs"; TRIED="$TRIED $abs" ;;
      esac
    fi
    if [ -n "$abs" ] && [ -d "$abs/gates" ] && [ -f "$abs/MANIFEST.sha256" ]; then
      ents=$(manifest_entries "$abs/MANIFEST.sha256")
      man_n=0
      [ -n "$ents" ] && man_n=$(printf '%s\n' "$ents" | grep -c .)
      if [ "$man_n" -gt 0 ]; then
        N_USABLE=$((N_USABLE + 1))
        v=$(manifest_version "$abs/MANIFEST.sha256"); [ -n "$v" ] || v="0"
        k=$(version_key "$v")
        cov=0
        IFS='
'
        for e in $ents; do
          IFS=$OLDIFS
          nm=${e#* }
          if [ -f "$abs/gates/$nm" ] && [ -f "$PROJECT_GATES/$nm" ]; then
            cov=$((cov + 1))
            if ! has_name "$UNION_NAMES" "$nm"; then
              UNION_NAMES="$UNION_NAMES
$nm"
              UNION_COV=$((UNION_COV + 1))
            fi
          fi
          IFS='
'
        done
        IFS=$OLDIFS
        # A real unpack of the plugin is not a bare gates/ dir: it also carries
        # rules/, scripts/, templates/ or skills/. A directory holding gates/
        # and nothing else is what a half-finished unpack -- or a hand-built
        # baseline -- looks like, so it loses every tie to a tree that has the
        # rest of the payload. Only a discriminator between OTHERWISE EQUAL
        # candidates: if nothing on disk has the payload, nobody is demoted.
        pay=0
        for _p in rules scripts templates skills; do
          [ -d "$abs/$_p" ] && { pay=1; break; }
        done
        CAND_REPORT="$CAND_REPORT
$cov|$man_n|$v|$pay|$abs"
        # Rank: coverage, then payload, then manifest size, then version, then
        # path. Version is a TIE-BREAK ONLY. This is the line the refutation
        # turned on: ranking on version let a 2-line "0.9.9" tree beat the real
        # 26-record one and green a tree with 4 live drifts in it.
        better=0
        if [ "$cov" -gt "$BEST_COV" ]; then better=1
        elif [ "$cov" -eq "$BEST_COV" ]; then
          if [ "$pay" -gt "$BEST_PAY" ]; then better=1
          elif [ "$pay" -eq "$BEST_PAY" ]; then
            if [ "$man_n" -gt "$BEST_MAN" ]; then better=1
            elif [ "$man_n" -eq "$BEST_MAN" ]; then
              if [ "$k" \> "$BEST_KEY" ]; then better=1
              elif [ "$k" = "$BEST_KEY" ] && [ -n "$CANONICAL" ] && [ "$abs" \< "$CANONICAL" ]; then better=1
              fi
            fi
          fi
        fi
        if [ "$better" -eq 1 ]; then
          BEST_COV="$cov"; BEST_MAN="$man_n"; BEST_KEY="$k"; BEST_PAY="$pay"
          CANONICAL="$abs"; CANON_VER="$v"
        fi
      fi
    fi
  fi
  IFS='
'
done
IFS=$OLDIFS

if [ -z "$CANONICAL" ]; then
  echo "* no canonical proof-os tree (a dir holding gates/ and a MANIFEST.sha256 that names at least one gates/ file) found - unavailable"
  echo "  looked at:${TRIED:- nothing}"
  echo "  set PROOF_OS_HOME or pass --canonical DIR. With no baseline, drift is"
  echo "  undecidable - that is NOT a pass."
  exit 2
fi

# An explicit --canonical overrides the ranking (the operator is allowed to say
# which tree is the shipped one) but NOT the coverage guards below, which were
# computed over every tree found.
if [ -n "$FORCED" ]; then
  _hit=""
  OLDIFS=$IFS
  IFS='
'
  for row in $CAND_REPORT; do
    IFS=$OLDIFS
    [ -n "$row" ] && [ "${row#*|*|*|*|}" = "$FORCED" ] && _hit="$row"
    IFS='
'
  done
  IFS=$OLDIFS
  if [ -z "$_hit" ]; then
    echo "* --canonical $FORCED holds no gates/ dir with a MANIFEST.sha256 naming a gates/ file - unavailable"
    exit 2
  fi
  BEST_COV=${_hit%%|*};        _r=${_hit#*|}
  BEST_MAN=${_r%%|*};          _r=${_r#*|}
  CANON_VER=${_r%%|*};         _r=${_r#*|}
  BEST_PAY=${_r%%|*}
  BEST_KEY=$(version_key "$CANON_VER")
  CANONICAL="$FORCED"
fi

MAN="$CANONICAL/MANIFEST.sha256"
MANLIST=$(manifest_entries "$MAN")
MAN_COUNT=0
[ -n "$MANLIST" ] && MAN_COUNT=$(printf '%s\n' "$MANLIST" | grep -c .)
if [ "$MAN_COUNT" -eq 0 ]; then
  echo "* $MAN names no gates/ file - unavailable"
  echo "  an empty baseline is not a passing comparison"
  exit 2
fi

# Losing candidates are printed, never silently discarded: the tree that
# refuted the previous revision has to be visible in the output.
IGNORED_LINES=""
OLDIFS=$IFS
IFS='
'
for row in $CAND_REPORT; do
  IFS=$OLDIFS
  [ -n "$row" ] || { IFS='
'; continue; }
  rcov=${row%%|*};  rest=${row#*|}
  rman=${rest%%|*}; rest=${rest#*|}
  rver=${rest%%|*}; rest=${rest#*|}
  rpay=${rest%%|*}; rabs=${rest#*|}
  if [ "$rabs" != "$CANONICAL" ]; then
    why="narrower baseline"
    [ "$rcov" -eq "$BEST_COV" ] && why="tie on coverage, lost on manifest size / version / path"
    if [ "$rcov" -eq "$BEST_COV" ] && [ "$rpay" -lt "$BEST_PAY" ]; then
      why="ships gates/ and nothing else - no rules/ scripts/ templates/ skills/, so it is not a whole unpack of the plugin"
    fi
    if [ "$rcov" -lt "$BEST_COV" ] && [ "$(version_key "$rver")" \> "$BEST_KEY" ]; then
      why="claims a HIGHER version ($rver > $CANON_VER) but covers less - a version string cannot buy a smaller baseline"
    fi
    if [ -n "$FORCED" ]; then
      why="not selected: --canonical pinned another tree (still counted toward the coverage the pinned tree must answer for)"
    fi
    IGNORED_LINES="$IGNORED_LINES
  ignored   : $rabs (proof-os $rver, $rman manifest gate record(s), shares $rcov of $UNION_COV) - $why"
  fi
  IFS='
'
done
IFS=$OLDIFS

# --------------------------------------------- authenticate the chosen tree
# Batch-hash the winner's gates and this project's gates once each.
CANON_WANT_NAMES=$(printf '%s\n' "$MANLIST" | awk '{ $1=""; sub(/^ /,""); print }')
CANON_TBL=$(printf '%s\n' "$CANON_WANT_NAMES" | hash_batch "$CANONICAL/gates")
PROJ_TBL=$(printf '%s\n' "$CANON_WANT_NAMES" | hash_batch "$PROJECT_GATES")

AUTH_NAMES=""       # canonical gates that match their own manifest
AUTH_COV=0          # ... and have a project counterpart
UNVERIFIABLE=0
UNVER_LINES=""
OLDIFS=$IFS
IFS='
'
for entry in $MANLIST; do
  IFS=$OLDIFS
  want=${entry%% *}
  name=${entry#* }
  if [ ! -f "$CANONICAL/gates/$name" ]; then
    UNVER_LINES="$UNVER_LINES
  UNVERIFIABLE  $name - named by the manifest but absent from $CANONICAL/gates"
    UNVERIFIABLE=$((UNVERIFIABLE + 1))
  else
    tbl_get "$CANON_TBL" "$name"; got=$TBL_VAL
    if [ -z "$got" ] || [ "$got" != "$want" ]; then
      UNVER_LINES="$UNVER_LINES
  UNVERIFIABLE  $name - the CANONICAL copy fails its own manifest (${got:-unreadable} != $want); excluded, never used as a baseline"
      UNVERIFIABLE=$((UNVERIFIABLE + 1))
    else
      AUTH_NAMES="$AUTH_NAMES
$name"
      [ -f "$PROJECT_GATES/$name" ] && AUTH_COV=$((AUTH_COV + 1))
    fi
  fi
  IFS='
'
done
IFS=$OLDIFS

hdr() {
  echo "stale-runtime-copy gate"
  echo "  canonical : $CANONICAL  (proof-os ${CANON_VER:-unstated}, $MAN_COUNT gate record(s) in MANIFEST.sha256)"
  echo "  project   : $PROJECT_GATES"
  echo "  candidates: $N_DISCOVERED path(s) discovered, $N_USABLE with a manifest that names a gate"
  [ -n "$IGNORED_LINES" ] && printf '%s\n' "$IGNORED_LINES" | sed '/^$/d'
  echo "  baseline  : authenticates $AUTH_COV of the $UNION_COV project gate file(s) that any discovered canonical tree names"
}

# ---- guard (c): a baseline too thin to mean anything is not a pass ---------
if [ "$AUTH_COV" -eq 0 ]; then
  hdr
  [ -n "$UNVER_LINES" ] && printf '%s\n' "$UNVER_LINES" | sed '/^$/d'
  echo "* 0 candidate files compared - unavailable, NOT green and NOT a red either"
  echo "  ($MAN_COUNT canonical gate(s) in the chosen baseline, $UNVERIFIABLE unverifiable, none of"
  echo "   the rest present in $PROJECT_GATES). A dir holding no copy of any shipped"
  echo "   gate is far more likely a wrong --project path than a gutted tree. Point the"
  echo "   gate at the real gates dir and re-run."
  exit 2
fi
if [ "$AUTH_COV" -lt "$MIN_BASELINE" ]; then
  hdr
  [ -n "$UNVER_LINES" ] && printf '%s\n' "$UNVER_LINES" | sed '/^$/d'
  echo "* baseline too thin: $AUTH_COV authenticated project-shared gate(s) < floor of $MIN_BASELINE - unavailable"
  echo "  A real proof-os tree ships ~26 gates. A tree that can only speak for $AUTH_COV of"
  echo "  this project's $UNION_COV comparable gate file(s) cannot certify the other"
  echo "  $((UNION_COV - AUTH_COV)), and a 'proved' printed off it would be a lie by omission."
  echo "  This is the exact shape that defeated the previous revision (a 2-line"
  echo "  MANIFEST.sha256 headed 'proof-os 0.9.9'). Point --canonical at the full"
  echo "  shipped tree, or repair the unpack under $STORE/.runtime/."
  exit 2
fi

# ---- guard (b): coverage, not "compared > 0", is the vacuity test ----------
if [ "$AUTH_COV" -lt "$UNION_COV" ]; then
  MISSING=""
  OLDIFS=$IFS
  IFS='
'
  for nm in $UNION_NAMES; do
    IFS=$OLDIFS
    if [ -n "$nm" ] && ! has_name "$AUTH_NAMES" "$nm"; then MISSING="$MISSING $nm"; fi
    IFS='
'
  done
  IFS=$OLDIFS
  hdr
  [ -n "$UNVER_LINES" ] && printf '%s\n' "$UNVER_LINES" | sed '/^$/d'
  echo "* baseline covers $AUTH_COV of $UNION_COV project gates - unavailable"
  echo "  not covered:$MISSING"
  echo "  Another discovered canonical tree names (or this one names but cannot"
  echo "  authenticate) project gate file(s) the chosen baseline cannot vouch for."
  echo "  Those project copies escaped the comparison entirely, so the honest"
  echo "  answer is 'did not check', not 'aligned'. Reconcile the trees under"
  echo "  $STORE/.runtime/ (or pass --canonical) and re-run."
  exit 2
fi

# ---------------------------------------------------------- adoption evidence
# An rc record is this project's own proof that it RUNS a given gate.
# "<label>.rc" and "<label>.<pid>.rc" both count.
RC_DIR="$STORE/rc"
RC_READABLE=0
ADOPTED=""
if [ -d "$RC_DIR" ]; then
  RC_READABLE=1
  ADOPTED=$(ls -1 "$RC_DIR" 2>/dev/null | sed -n 's/\.rc$//p' | sed 's/\.[0-9][0-9]*$//' | sort -u)
fi
is_adopted() {  # $1 = gate basename
  # Fork-free on purpose: a process spawn costs ~0.2s on Windows and this runs
  # once per canonical gate with no project copy.
  [ "$RC_READABLE" -eq 1 ] || return 1
  stem="$1"
  case "$stem" in
    *.py|*.sh|*.js|*.ts) stem=${stem%.*} ;;
    *.mjs|*.json)        stem=${stem%.*} ;;
  esac
  has_name "$ADOPTED" "$stem"
}

first_diff_line() {   # $1 canonical, $2 project -> 1-based line, defaults to 1
  n=$(awk '
    NR == FNR { a[FNR] = $0; n = FNR; next }
    { if (FNR > n || $0 != a[FNR]) { print FNR; found = 1; exit } }
    END { if (!found) { if (FNR < n) print FNR + 1; else print 1 } }
  ' "$1" "$2" 2>/dev/null)
  case "$n" in ''|*[!0-9]*) n=1 ;; esac
  [ "$n" -lt 1 ] && n=1
  echo "$n"
}

SHARED="_oracles.py _rc.py _rc.sh"
is_shared() { case " $SHARED " in *" $1 "*) return 0 ;; *) return 1 ;; esac; }

# ------------------------------------------------------------------ compare
COMPARED=0        # authenticated canonical gates that had a project counterpart
FINDINGS=0
NOT_ADOPTED=""
FROM_CANON=""
FIND_LINES=""
OK_LINES=""

record() { FIND_LINES="$FIND_LINES
$1"; FINDINGS=$((FINDINGS + 1)); }

OLDIFS=$IFS
IFS='
'
for entry in $MANLIST; do
  IFS=$OLDIFS
  name=${entry#* }
  cpath="$CANONICAL/gates/$name"
  if has_name "$AUTH_NAMES" "$name"; then
    tbl_get "$CANON_TBL" "$name"; got=$TBL_VAL
    ppath="$PROJECT_GATES/$name"
    if [ ! -f "$ppath" ]; then
      if is_shared "$name"; then
        record "$ppath:1: MISSING SHARED - $name is absent from the project copy. Every \`from _rc import rc_init\` / oracle import in this tree falls into its \`except: pass\`, so the capability is silently OFF and no other gate can report it."
      elif is_adopted "$name"; then
        FROM_CANON="$FROM_CANON $name"
      else
        NOT_ADOPTED="$NOT_ADOPTED $name"
      fi
    else
      COMPARED=$((COMPARED + 1))
      tbl_get "$PROJ_TBL" "$name"; phash=$TBL_VAL
      if [ -z "$phash" ]; then
        record "$ppath:1: UNREADABLE - the project copy of $name could not be read; a gate that cannot be read cannot be the gate that ran."
      elif [ "$phash" != "$got" ]; then
        ln=$(first_diff_line "$cpath" "$ppath")
        psz=$(wc -c < "$ppath" 2>/dev/null | tr -d ' ')
        csz=$(wc -c < "$cpath" 2>/dev/null | tr -d ' ')
        record "$ppath:$ln: DRIFT - project copy of $name (${phash%${phash#????????????}} , ${psz:-?} bytes) differs from shipped proof-os $CANON_VER (${got%${got#????????????}} , ${csz:-?} bytes) at line $ln. The gate this project runs is not the gate proof-os shipped."
      else
        OK_LINES="$OK_LINES
  ok            $name"
      fi
    fi
  fi
  IFS='
'
done
IFS=$OLDIFS

# ------------------------------------------- copies hiding in subdirectories
# A stale gate does not stop being a stale gate because someone moved it to
# gates/legacy/ or gates/old/. Scoped hard to name collisions with an
# AUTHENTICATED canonical gate, so ordinary project files under gates/ (this
# repo's class/, fixtures/, __pycache__/) can never be swept in.
SUB_SKIPPED=0
SUBLIST=""
if command -v find >/dev/null 2>&1; then
  SUBLIST=$( cd "$PROJECT_GATES" 2>/dev/null &&
    find . -mindepth 2 -type f 2>/dev/null |
    sed 's|^\./||' |
    awk '$0 !~ /(^|\/)__pycache__\// && $0 !~ /(^|\/)fixtures\//' )
fi
SUB_TBL=""
if [ -n "$SUBLIST" ]; then
  SUB_HASHABLE=$(printf '%s\n' "$SUBLIST" | awk '$0 !~ /[ \t]/')
  SUB_SKIPPED=$(printf '%s\n' "$SUBLIST" | awk '$0 ~ /[ \t]/' | grep -c . )
  SUB_TBL=$(printf '%s\n' "$SUB_HASHABLE" | hash_batch "$PROJECT_GATES")
  OLDIFS=$IFS
  IFS='
'
  for rel in $SUB_HASHABLE; do
    IFS=$OLDIFS
    base=${rel##*/}
    if [ -n "$base" ] && has_name "$AUTH_NAMES" "$base"; then
      tbl_get "$CANON_TBL" "$base"; cgot=$TBL_VAL
      tbl_get "$SUB_TBL" "$rel"; shash=$TBL_VAL
      if [ -z "$shash" ] || [ "$shash" != "$cgot" ]; then
        sln=1
        [ -n "$shash" ] && sln=$(first_diff_line "$CANONICAL/gates/$base" "$PROJECT_GATES/$rel")
        record "$PROJECT_GATES/$rel:$sln: BURIED COPY - a second copy of the shipped gate $base lives in a subdirectory of the project gates dir and differs from proof-os $CANON_VER. Whichever of the two a runner picks up, one of them is a stale runtime copy; a moved gate is not a retired gate."
      else
        OK_LINES="$OK_LINES
  ok            $rel (buried duplicate of $base, byte-identical to canonical)"
      fi
    fi
    IFS='
'
  done
  IFS=$OLDIFS
fi

# Shared modules are enforced even if the manifest somehow omits one: their
# absence is invisible to every other gate in the tree.
for s in $SHARED; do
  if [ ! -f "$PROJECT_GATES/$s" ]; then
    case "$FIND_LINES" in
      *"$PROJECT_GATES/$s:"*) : ;;
      *) record "$PROJECT_GATES/$s:1: MISSING SHARED - $s is absent from the project copy (and unverifiable against the manifest); imports of it fail silently." ;;
    esac
  fi
done

# ------------------------------------------------------------------ report
hdr
echo "  scanned   : $COMPARED candidate file(s) compared, $UNVERIFIABLE canonical file(s) unverifiable"

if [ -n "$FIND_LINES" ]; then
  printf '%s\n' "$FIND_LINES" | sed '/^$/d'
fi
# Always printed: an unverifiable canonical file is a hole in the comparison --
# the project copy of that name escaped the check entirely.
if [ -n "$UNVER_LINES" ]; then
  printf '%s\n' "$UNVER_LINES" | sed '/^$/d'
fi
if [ "$VERBOSE" -eq 1 ] && [ -n "$OK_LINES" ]; then
  printf '%s\n' "$OK_LINES" | sed '/^$/d'
fi
if [ -n "$FROM_CANON" ]; then
  echo "  no local copy, run from canonical:$FROM_CANON (rc records prove this project runs them - reported, NOT a finding)"
fi
if [ -n "$NOT_ADOPTED" ]; then
  echo "  not adopted:$NOT_ADOPTED (shipped canonically, no rc record here - reported, NOT a finding)"
fi
if [ "$SUB_SKIPPED" -gt 0 ]; then
  echo "  NOTE: $SUB_SKIPPED file(s) under $PROJECT_GATES subdirectories have whitespace in their"
  echo "        path and were not hashed for the buried-copy check."
fi
if [ "$RC_READABLE" -eq 0 ]; then
  echo "  NOTE: $RC_DIR unreadable - no canonical gate could be shown to be adopted here,"
  echo "        so every absence fell through to not-adopted; only drift and the shared"
  echo "        modules were enforced."
fi

# Refuse a vacuous pass. "I compared nothing, therefore green" is the exact
# shape of the bug this class exists for. (The coverage guards above already
# catch the thin-baseline version of this; this one stays for the case where
# the project dir simply holds no copy of any shipped gate.)
if [ "$COMPARED" -eq 0 ]; then
  echo "  count     : $FINDINGS finding(s) (absence-only; nothing was compared)"
  echo "* 0 candidate files compared - unavailable, NOT green and NOT a red either"
  echo "  ($MAN_COUNT canonical gate(s), $UNVERIFIABLE unverifiable, none of the rest present"
  echo "   in $PROJECT_GATES). A dir holding no copy of any shipped gate is far more likely a"
  echo "   wrong --project path than a gutted tree, so absence-based findings above are not"
  echo "   trusted to stand on their own. Point the gate at the real gates dir and re-run."
  exit 2
fi

echo "  count     : $FINDINGS finding(s)"
echo "NOT CHECKED: whether the CANONICAL gate is itself correct (this proves the project copy matches the shipped one, never that the shipped one works) | THE BASELINE'S OWN PROVENANCE - the winning tree here is under $STORE/.runtime/, which this project can write, so anything that can edit the repo can also mint a manifest; coverage ranking stops a THIN forgery, it cannot stop a FULL one that simply re-blesses the current bytes. Closing that needs a version pinned outside the repo (a signed plugin install), not a wider grep | project-only gates (F-*.sh and friends) have no canonical counterpart and are not judged here, so one of them can still be a stub | a shipped gate RENAMED in the project (citations_v2.py) - no name to collide on, and content-similarity guessing would invent false positives | whether an identical gate is actually WIRED INTO a run | canonical gates with no project copy - absence is reported, never counted | the age, exit code or truthfulness of the rc records used as adoption evidence"

if [ "$FINDINGS" -gt 0 ]; then
  echo "VERDICT: broken - the gates this project runs are not the gates proof-os shipped"
  exit 1
fi
echo "VERDICT: aligned (proved) - all $COMPARED project gate(s) named by the widest authenticated baseline ($AUTH_COV of $UNION_COV comparable) are byte-identical to proof-os $CANON_VER"
exit 0
