#!/usr/bin/env bash
# F-0390 — every VITE_* money flag that src/ READS must be DECLARED, in the build input that
# actually decides the shipped image, with a value that survives to the bundle.
#
# THE DEFECT: VITE_PAYMENTS_IN_ENABLED and VITE_PAYOUTS_ENABLED are read at src/lib/api.ts:71/:104
# and gate every money action. They were declared in NO build input — so Vite inlined nothing,
# `isMoneyActionBlocked` compiled to an unconditional `return!0`, and wallet top-up and escrow
# funding were dead in every published image with no deploy-time lever.
#
# WHY v2 (priya, fresh-context review of the v1 fix): v1 exited 1 only against the byte-for-byte
# pre-fix tree. It greened five differently-broken trees — ENV moved to the runtime stage,
# build-args reshaped to render empty on push, build-args attached to the wrong job, the payouts
# fallback flipped to 'true', and the api.ts reads deleted but their names left in comments. Every
# leg below exists because one of those got past v1. A gate that only recognises the exact
# original byte sequence is a fingerprint, not a gate.
#
# WHAT THIS GATE CANNOT SEE (law 5):
#   - It does not run `docker build`, so the --build-arg -> ARG -> ENV handoff is read, not
#     executed. It also does not run GitHub Actions, so the rendered build-arg strings are
#     analysed statically, never observed.
#   - It proves nothing about whether a payment SUCCEEDS. That needs a provisioned
#     RAZORPAY_KEY_ID (still REPLACE_ME at deploy/utho/generate-env.sh:59) and a live run.
#     Green here means the lever exists, is wired to the input that decides, and is set.
#   - It does not check RAZORPAY_WEBHOOK_SECRET, whose absence silently strands captured
#     payments. That hazard is its own ledger record (F-0391), not this one.
#   - Leg 6 judges provisioning by grepping deploy/utho/generate-env.sh for REPLACE_ME. That is
#     the GENERATOR TEMPLATE, not the live .env on the VPS, and the two can diverge in either
#     direction — a box that is genuinely provisioned still trips it until someone edits the
#     template, and a template that was edited without provisioning passes. It is a tripwire on
#     the repo's own declared state, not a probe of the deployment.
#   - It cannot tell a deliberate money-IN=false (an incident rollback) from an accidental one.
#     That is why the push fallback is required to come from a repository variable: the gate
#     checks that a lever EXISTS and that its value is legible, and leaves the setting to whoever
#     owns the deploy.
#
# Exit: 0 proved · 1 broken · 2 unavailable (never green).
set -uo pipefail

ROOT="${1:-$(git rev-parse --show-toplevel 2>/dev/null || pwd)}"
# Where THIS gate lives — used to resolve `vite` for leg 5, so ROOT is never required to carry a
# node_modules of its own. v1 demanded one, which made every falsification tree expensive to build
# and therefore made falsification rare.
GATE_REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

cd "$ROOT" 2>/dev/null || { echo "UNAVAILABLE: cannot cd to $ROOT"; exit 2; }

WF=.github/workflows/publish-images.yml
CI=.github/workflows/frontend-checks.yml
FLAGS=(VITE_PAYMENTS_IN_ENABLED VITE_PAYOUTS_ENABLED)
fail=0
say_fail() { echo "BROKEN: $*"; fail=1; }

for f in .env.production Dockerfile "$WF" src/lib/api.ts; do
  [ -f "$f" ] || { echo "UNAVAILABLE: $f missing under $ROOT"; exit 2; }
done

# --- leg 0: the flags are still READ by src/, as code -----------------------------------------
# v1 grepped the name anywhere in api.ts, so leaving `// VITE_PAYMENTS_IN_ENABLED` in a comment
# after deleting the read kept it green. Anchor on the actual `import.meta.env` access instead.
for f in "${FLAGS[@]}"; do
  if ! grep -rqE "import\.meta\.env\??\.?\[?['\"]?${f}" src/ 2>/dev/null; then
    echo "UNAVAILABLE: no import.meta.env read of ${f} anywhere in src/ — this gate now guards nothing"
    exit 2
  fi
done
# ...and the CONSUMER must still branch on them. priya greened a tree where both reads were intact
# and `isMoneyActionBlocked` was hard-coded to `return true` — the original F-0390 symptom, every
# money action blocked, with all the build wiring this gate checks perfectly in place. A flag that
# is read into a constant nothing consults is not a flag. Assert the predicate still consults the
# two module constants the reads assign to.
guard=$(awk '/export function isMoneyActionBlocked/{f=1} f{print} f&&/^}/{exit}' src/lib/api.ts)
if [ -z "$guard" ]; then
  echo "UNAVAILABLE: isMoneyActionBlocked is no longer declared in src/lib/api.ts — this gate cannot tell what the flags now gate"
  exit 2
fi
for c in PAYMENTS_IN_ENABLED PAYOUTS_ENABLED; do
  printf '%s\n' "$guard" | grep -q "$c" \
    || say_fail "src/lib/api.ts: isMoneyActionBlocked does not reference ${c} — the flag is parsed from the environment and then ignored, so every build input this gate verifies decides nothing"
done

# --- leg 1: .env.production declares both, with a non-empty value ------------------------------
# Governs a local `npm run build` only (the workflow build-arg overrides it for the image), but a
# missing line here still means a local prod build silently loses the flag.
# v3 asserted only that the line existed and was non-empty, so setting it to `false` greened while
# every local and CI `npm run build` (.github/workflows/frontend-checks.yml) shipped money IN dead
# — F-0390's symptom, on the path this leg is the ONLY check for. Pin it to the same ruling leg 5
# pins the workflow literal to.
#
# PINNED is the single source of the recorded ruling and is read by legs 1, 2 and 5. Declared once
# here so the three can never disagree — a gate that pinned .env.production to one value and the
# workflow to another would certify a tree whose local build and published image differ.
declare -A PINNED=( [VITE_PAYMENTS_IN_ENABLED]=true [VITE_PAYOUTS_ENABLED]=false )
for f in "${FLAGS[@]}"; do
  v=$(grep -E "^${f}=" .env.production | head -1 | sed -E "s/^${f}=//" | tr -d '\r')
  n=$(grep -cE "^${f}=" .env.production)
  if [ "$n" -eq 0 ]; then
    say_fail ".env.production does not declare ${f}"
  elif [ "$n" -gt 1 ]; then
    say_fail ".env.production declares ${f} ${n} times — dotenv takes one of them and this gate will not guess which"
  elif [ "$v" != "${PINNED[$f]}" ]; then
    say_fail ".env.production sets ${f}='${v}', but F-0390's recorded ruling pins the committed default to '${PINNED[$f]}'. This file governs local and CI builds, which have no vars.<NAME> lever — so a value here is a commit, not a runtime override, and changing it has to be re-recorded"
  fi
done

# --- leg 2: Dockerfile declares both as ARG *and* ENV, INSIDE the build stage ------------------
# An ENV in the runtime stage is a no-op: nginx never runs Vite. v1 compared nothing but presence,
# so relocating the whole ENV instruction past the second FROM kept it green while making every
# --build-arg inert. Slice the file at the LAST FROM and require both lines above it.
# v2 checked only membership in the build stage, which greened two more trees (priya, round 2):
# the whole ARG/ENV block moved BELOW `RUN npx vite build` (declared, but after the only consumer),
# and ENV placed ABOVE its ARG (Docker resolves $VAR to empty there, and an ENV always beats a
# later same-named ARG, so every --build-arg goes inert). Position is the whole point, so assert
# it: ARG before ENV before the build command.
last_from=$(grep -nE '^FROM ' Dockerfile | tail -1 | cut -d: -f1)
[ -n "$last_from" ] || { echo "UNAVAILABLE: Dockerfile has no FROM — cannot tell build stage from runtime"; exit 2; }
build_cmd_ln=$(awk -v lf="$last_from" 'NR<lf && /^RUN .*(vite build|npm run build|yarn build|pnpm build)/{print NR; exit}' Dockerfile)
[ -n "$build_cmd_ln" ] || { echo "UNAVAILABLE: no bundle-building RUN found before the final FROM in Dockerfile"; exit 2; }
for f in "${FLAGS[@]}"; do
  # `head -1` on both of these was itself a hole (priya, round 3): a SECOND `ENV ${f}=true` added
  # later in the build stage is what Docker actually takes, and it shadows the ARG so every
  # --build-arg goes inert — while the gate read only the first, well-formed occurrence. Refuse to
  # certify a file that declares the same money flag twice, rather than picking one.
  arg_n=$(grep -cE "^ARG ${f}=" Dockerfile)
  env_n=$(grep -cE "(^ENV |^[[:space:]]+)${f}=" Dockerfile)
  if [ "$arg_n" -gt 1 ] || [ "$env_n" -gt 1 ]; then
    say_fail "Dockerfile declares ${f} more than once (ARG ×${arg_n}, ENV ×${env_n}) — Docker takes the LAST, and an ENV shadows a same-named ARG, so this gate will not guess which one ships"
    continue
  fi
  # The ARG's DEFAULT value matters on its own: any `docker build` invoked by hand or by a future
  # workflow that passes only the URL args never passes these two, so the ARG default IS the
  # shipped value on that path. v3 checked position and never the value.
  # (An earlier version of this comment cited deploy/utho/README.md as documenting such a hand
  # build. It does not — README.md:22-25 documents dispatching publish-images.yml. The check is
  # still worth having; the citation was wrong and is removed rather than repaired.)
  arg_default=$(grep -E "^ARG ${f}=" Dockerfile | head -1 | sed -E "s/^ARG ${f}=//" | tr -d '\r')
  if [ "$arg_default" != "${PINNED[$f]}" ]; then
    say_fail "Dockerfile: ARG ${f} defaults to '${arg_default}', but F-0390's recorded ruling pins it to '${PINNED[$f]}'. A hand-run \`docker build\` that passes only --build-arg VITE_API_BASE_URL (the rebuild deploy/utho/README.md documents) never passes this flag at all, so the ARG default IS the shipped value on that path"
  fi
  arg_ln=$(grep -nE "^ARG ${f}=" Dockerfile | head -1 | cut -d: -f1)
  env_ln=$(grep -nE "(^ENV |^[[:space:]]+)${f}=\\\$${f}" Dockerfile | head -1 | cut -d: -f1)
  if [ -z "$arg_ln" ]; then
    say_fail "Dockerfile has no 'ARG ${f}='"; continue
  fi
  if [ -z "$env_ln" ]; then
    say_fail "Dockerfile never promotes ${f} to ENV — the --build-arg is inert"; continue
  fi
  [ "$arg_ln" -lt "$last_from" ] \
    || say_fail "Dockerfile: ARG ${f} (line $arg_ln) is in the RUNTIME stage (after the final FROM at $last_from) — nginx never runs Vite"
  [ "$env_ln" -lt "$last_from" ] \
    || say_fail "Dockerfile: ENV ${f} (line $env_ln) is in the RUNTIME stage (after the final FROM at $last_from) — the build never sees it"
  [ "$arg_ln" -lt "$env_ln" ] \
    || say_fail "Dockerfile: ENV ${f} (line $env_ln) precedes its ARG (line $arg_ln) — \$${f} resolves empty there, and the ENV then shadows the later ARG, so --build-arg is ignored"
  [ "$env_ln" -lt "$build_cmd_ln" ] \
    || say_fail "Dockerfile: ENV ${f} (line $env_ln) comes AFTER the build command (line $build_cmd_ln) — Vite has already run, so nothing is inlined"
done

# --- leg 3: the WEB job passes both as build-args ----------------------------------------------
# v1 grepped the whole workflow, so moving the build-args onto the `api` job — which does not
# build the bundle — kept it green. Isolate the job whose build-args carry VITE_API_MODE (the
# frontend image is the only one that takes VITE_* at all) and require the flags in THAT block.
# v2's awk PRINTED EVERY matching block, and the later `head -1` then read whichever came first in
# file order. priya greened a tree that added a decoy build-args block to the `api` job carrying
# safe values while the real web job shipped payouts-on. So: count the blocks, and refuse to guess
# when there is more than one. Ambiguity here is not a tie to be broken, it is a gate that cannot
# know which block builds the bundle.
nblocks=$(awk '
  /^[[:space:]]*build-args:[[:space:]]*\|/ {inblk=1; buf=""; hasmode=0; next}
  inblk {
    if ($0 ~ /^[[:space:]]*[A-Za-z_]+[A-Za-z0-9_-]*:[[:space:]]/ || $0 ~ /^[[:space:]]*-[[:space:]]/) {
      if (hasmode) n++; inblk=0; next
    }
    buf = buf $0 "\n"
    if ($0 ~ /VITE_API_MODE=/) hasmode=1
  }
  END {if (inblk && hasmode) n++; print n+0}
' "$WF")
webargs=$(awk '
  /^[[:space:]]*build-args:[[:space:]]*\|/ {inblk=1; buf=""; hasmode=0; next}
  inblk {
    if ($0 ~ /^[[:space:]]*[A-Za-z_]+[A-Za-z0-9_-]*:[[:space:]]/ || $0 ~ /^[[:space:]]*-[[:space:]]/) {
      if (hasmode) print buf; inblk=0; next
    }
    buf = buf $0 "\n"
    if ($0 ~ /VITE_API_MODE=/) hasmode=1
  }
  END {if (inblk && hasmode) print buf}
' "$WF")
# A build-args block on a job that never runs certifies nothing. priya greened a tree whose web
# job carried `if: false` — every flag correctly declared, and the frontend image never
# republished, so the shipped bundle stays whatever it was. Require the job's condition to still
# admit both trigger paths.
webif=$(awk '/^  web:/{injob=1} injob && /^[[:space:]]+if:/{print; exit} /^  [a-z]/ && !/^  web:/{injob=0}' "$WF")
if [ -n "$webif" ]; then
  # v4 substring-matched `github.event_name == 'push'` and the literal `false`, so priya greened
  # `if: ${{ github.event_name == 'push' && github.ref == 'refs/heads/no-such-branch' }}` — an
  # always-false condition that contains the required substring and no literal `false`. Enumerating
  # the ways a condition can be false is unwinnable; require the SHAPE that is known good instead,
  # and make anything else a decision someone has to make deliberately.
  want_if="if: \${{ github.event_name == 'push' || inputs.publish_web }}"
  if [ "$(printf '%s' "$webif" | sed -E 's/^[[:space:]]+//; s/[[:space:]]+$//')" != "$want_if" ]; then
    say_fail "$WF: the web job's condition is not the known-good shape. Expected exactly \`${want_if}\`, found \`$(printf '%s' "$webif" | sed -E 's/^[[:space:]]+//')\`. This gate will not attempt to decide whether an arbitrary condition can ever be true — if the change is intended, update the expected shape here in the same commit"
  fi
fi

# The job can be perfectly conditioned and still never fire, if the TRIGGERS that reach it are
# removed. priya greened two such trees: `on.push` deleted outright, and `paths:` narrowed to drop
# `src/**` and `Dockerfile`. Both leave every declaration this gate checks intact while no push
# ever republishes the image again.
awk '/^on:/{o=1} o&&/^[[:space:]]+push:/{f=1} /^jobs:/{exit} END{exit !f}' "$WF" \
  || say_fail "$WF: no \`on.push\` trigger — the frontend image is only ever republished by hand, so a regression to the money flags ships silently until someone dispatches"

# --- leg 3b: this gate must actually be INVOKED -----------------------------------------------
# The cheapest way to defeat every leg above is to delete the step that runs them, and until now
# the gate read neither workflow that invokes it (priya, round 4: "the free one"). A gate asserting
# its own wiring is not circular — it is the same check it applies to the Dockerfile: a declaration
# nothing executes decides nothing. It cannot defend against deleting the gate file itself, and
# does not pretend to; it catches the realistic disarm, which is quietly dropping the step.
SELF=F-0390-money-flags-declared-in-build.sh
for wfile in "$CI" "$WF"; do
  if [ ! -f "$wfile" ]; then
    say_fail "$wfile is missing — this gate is invoked from it, so its absence means nothing runs this check"
  elif ! grep -q "$SELF" "$wfile"; then
    say_fail "$wfile no longer invokes ${SELF} — the money-flag wiring would go unchecked on $([ "$wfile" = "$CI" ] && echo 'pull requests and pushes' || echo 'the publish that ships the bundle')"
  fi
done
# The falsification harness must run too, and only in CI: a gate that is never attacked drifts into
# passing everything, which is this record's whole history.
grep -q "F-0390-falsify.py" "$CI" \
  || say_fail "$CI no longer runs F-0390-falsify.py — nothing regularly tries to break this gate, and a gate nothing attacks stops being one"
for p in 'src/\*\*' 'Dockerfile' '.github/workflows/publish-images.yml'; do
  grep -qE "^[[:space:]]+- '?${p}'?$" "$WF" \
    || say_fail "$WF: the push \`paths:\` filter no longer includes ${p} — a change to it would not trigger a republish, so the shipped bundle would silently diverge from the repo"
done

if [ "$nblocks" -gt 1 ]; then
  say_fail "$WF has $nblocks build-args blocks carrying VITE_API_MODE — this gate cannot tell which one builds the frontend image, so it will not certify any of them"
  webargs=""
fi
if [ -z "$webargs" ]; then
  [ "$nblocks" -le 1 ] && say_fail "$WF has no build-args block carrying VITE_API_MODE — the frontend image job is gone or unrecognisable"
else
  for f in "${FLAGS[@]}"; do
    n=$(printf '%s\n' "$webargs" | grep -cE "^[[:space:]]*${f}=")
    case "$n" in
      1) : ;;
      0) say_fail "$WF: the frontend build-args block does not pass ${f}" ;;
      # Same class as the duplicate-ENV hole: v3 used `grep -q` then `head -1`, so a second,
      # contradictory declaration of the same flag in the same block was invisible. Which one
      # buildx honours is beside the point — certifying a file the gate did not finish reading is.
      *) say_fail "$WF: the frontend build-args block declares ${f} ${n} times — contradictory declarations of a money flag are not something this gate will pick between" ;;
    esac
  done
fi

# --- leg 4: the rendered build-arg can never be empty, and an explicit false must survive -------
# Three distinct ways v1 was evaded here, so three assertions per flag:
#   (a) a bare `${{ inputs.x }}` renders EMPTY on a push — Vite reads "" as false. The original
#       defect, restored, with the declaration still present. v1 only looked for the `||` shape.
#   (b) `inputs.x || 'y'` flips an explicit false back on.
#   (c) toJSON() quotes a string input, so `"true" !== 'true'` silently disables the flag.
for f in "${FLAGS[@]}"; do
  line=$(printf '%s\n' "$webargs" | grep -E "^[[:space:]]*${f}=" | head -1)
  [ -n "$line" ] || continue
  case "$line" in
    *'${{'*)
      case "$line" in
        *"||"*) : ;;
        *) say_fail "$WF: ${f} is an expression with no \`||\` fallback — it renders EMPTY on a push event, which Vite reads as false" ;;
      esac
      case "$line" in
        *toJSON*) say_fail "$WF: ${f} uses toJSON(), which renders a STRING input with quotes — \`\"true\"\` fails the \`=== 'true'\` test at src/lib/api.ts:71" ;;
      esac
      # (d) CROSSED WIRES. v3 checked that the expression contained `vars.` and `inputs.` — never
      # that they were THIS flag's. priya greened a one-token tree where VITE_PAYOUTS_ENABLED was
      # driven by `vars.PAYMENTS_IN_ENABLED`, so the documented incident procedure ("set
      # PAYMENTS_IN_ENABLED to hold collection off, then set it back") would turn creator
      # withdrawals ON against an unprovisioned RazorpayX. Leg 6 could not see it: the `|| '...'`
      # tail still read 'false'. Derive the identifiers this flag MUST use and require them.
      want_input="$(printf '%s' "$f" | tr 'A-Z' 'a-z')"   # VITE_PAYOUTS_ENABLED -> vite_payouts_enabled
      want_var="${f#VITE_}"                               #                      -> PAYOUTS_ENABLED
      case "$line" in
        *"inputs.${want_input}"*) : ;;
        *"inputs."*) say_fail "$WF: ${f} is driven by a different flag's dispatch input (expected inputs.${want_input}) — crossed wiring" ;;
        *) say_fail "$WF: ${f} consults no dispatch input (expected inputs.${want_input}) — there is no per-publish override" ;;
      esac
      # (e) the push-path fallback must be a repository VARIABLE, and THIS flag's. A literal means
      # the only way to change the shipped behaviour — including holding collection OFF during an
      # incident — is a commit + rebuild + re-pull. That absence of a lever is the whole of F-0390;
      # a literal fallback re-creates it one level up.
      case "$line" in
        *"vars.${want_var}"*) : ;;
        *vars.*) say_fail "$WF: ${f} falls back to a different flag's repository variable (expected vars.${want_var}) — crossed wiring" ;;
        *) say_fail "$WF: ${f}'s push fallback is a hardcoded literal — set it from vars.${want_var} so it can be changed without a commit (F-0390 is precisely 'no deploy-time lever')" ;;
      esac
      # (f) an explicit OFF from the dispatch form must survive the `||` chain. `inputs.x || y`
      # cannot do that for a boolean. Either the input is compared against a sentinel — the
      # `!= 'default'` form, which also lets a blank dispatch DEFER to the variable — or it is
      # rendered quote-free through format() behind an event_name guard. v3 accepted `event_name`
      # OR `format(` as alternatives, so deleting format() left the check satisfied by the guard
      # while the kill switch silently stopped working; require the sentinel or both.
      case "$line" in
        *"!= 'default'"*) : ;;
        *event_name*format\(*|*format\(*event_name*) : ;;
        *) say_fail "$WF: ${f}'s dispatch override cannot express OFF — with a bare \`inputs.x || fallback\`, a form set to false falls through to the fallback and the kill switch reports success while doing nothing" ;;
      esac
      # (g) the dispatch input it references must actually be DEFINED. Deleting the
      # `workflow_dispatch.inputs` block leaves this expression syntactically intact while
      # `inputs.<name>` becomes null on every run — the per-publish override silently stops
      # existing, which is F-0390's "no lever" with the wiring still in place.
      awk -v want="$want_input" '
        /^on:/{in_on=1}
        in_on && /^[[:space:]]+inputs:/{in_inputs=1; next}
        in_inputs && $0 ~ "^[[:space:]]+" want ":" {found=1; exit}
        /^jobs:/{exit}
        END{exit !found}
      ' "$WF" || say_fail "$WF: ${f} reads inputs.${want_input}, but no such workflow_dispatch input is defined — it resolves to null on every run and the per-publish override does not exist"
      # (h) the `verify money flags` step must validate the SAME expression the build-arg bakes in.
      # Found by a probe that edited the verification step and left the build-arg alone: a check
      # that guards a different expression than the one that ships is worse than no check, because
      # it reports success about a value nobody uses.
      var_short="${want_var%%_ENABLED}"
      case "$var_short" in PAYMENTS_IN) key=IN ;; PAYOUTS) key=OUT ;; *) key="" ;; esac
      if [ -n "$key" ]; then
        # Both sides trimmed: the YAML indents differ (`IN:` sits in an env: block, the build-arg
        # in a build-args: block), so a raw comparison always reports drift.
        trim() { printf '%s' "$1" | sed -E 's/^[[:space:]]+//; s/[[:space:]]+$//'; }
        # v4 grepped `^\s+IN:` across the WHOLE file and compared only its text, so priya greened
        # two trees: the verify step's `run:` body replaced with `echo skipping` (env lines
        # untouched), and the step deleted entirely with an identical `IN:`/`OUT:` pair parked on
        # some other no-op step. Scope the search to the named step, and require its body to still
        # do the two things it exists for.
        vstep=$(awk '/^[[:space:]]+- name: verify money flags$/{f=1; next}
                     f && /^[[:space:]]+- (name|uses):/{exit}
                     f{print}' "$WF")
        if [ -z "$vstep" ]; then
          say_fail "$WF: there is no step named 'verify money flags' — nothing validates the resolved flag values before they are baked into an image"
          continue
        fi
        case "$vstep" in
          *"true|false)"*) : ;;
          *) say_fail "$WF: the verify-money-flags step no longer rejects values that are not exactly 'true'/'false' — its body has been replaced or gutted" ;;
        esac
        case "$vstep" in
          *RAZORPAYX_ACCOUNT_NUMBER*) : ;;
          *) say_fail "$WF: the verify-money-flags step no longer refuses payouts-on against an unprovisioned RazorpayX — that check is the only place vars.PAYOUTS_ENABLED is visible, so removing it makes the hazard reachable from repository settings alone" ;;
        esac
        vline=$(printf '%s\n' "$vstep" | grep -E "^[[:space:]]+${key}:" | head -1)
        if [ -z "$vline" ]; then
          say_fail "$WF: no '${key}:' env line inside the verify-money-flags step — nothing validates ${f} before it is baked into an image"
        elif [ "$(trim "${vline#*:}")" != "$(trim "${line#*=}")" ]; then
          say_fail "$WF: the verify-money-flags step's ${key} expression differs from the ${f} build-arg — the step would validate a value the image never receives"
        fi
      fi ;;
    *)
      # No `${{ }}` at all: a bare literal such as `VITE_PAYMENTS_IN_ENABLED=false`. v2's case
      # arms only matched an EMPTY value, so this fell through every check — including leg 6's
      # safety assertion, because shipped() parses only the `|| '...'` shape and returned nothing.
      # priya greened two trees this way, one of them publishing creator withdrawals against an
      # unprovisioned RazorpayX.
      lit=${line#*=}
      say_fail "$WF: ${f} is passed as the fixed literal '${lit}' with no expression — unreachable from the dispatch form and from repository variables, so there is no deploy-time lever at all" ;;
  esac
done

# --- leg 5: what the SHIPPED image will actually see -------------------------------------------
# v1 asked vite.loadEnv about .env.production — the input the published image ignores. Read the
# workflow's push-path fallback (the value every push-triggered publish bakes in) and check THAT,
# then use loadEnv only for the local-build path it really governs.
shipped() {  # $1 = flag name -> the literal the `push` fallback ultimately renders
  printf '%s\n' "$webargs" | grep -E "^[[:space:]]*$1=" | head -1 \
    | sed -nE "s/.*\|\|[[:space:]]*'([^']*)'[[:space:]]*\}\}.*/\1/p"
}
IN_SHIPPED=$(shipped VITE_PAYMENTS_IN_ENABLED)
OUT_SHIPPED=$(shipped VITE_PAYOUTS_ENABLED)
echo "shipped-image fallback: VITE_PAYMENTS_IN_ENABLED='${IN_SHIPPED:-<unreadable>}' VITE_PAYOUTS_ENABLED='${OUT_SHIPPED:-<unreadable>}'"
# v2 printed those two and asserted NOTHING about them, so flipping the money-IN fallback to
# 'false' — the ledger's exact symptom, in the one input that decides the shipped image — still
# exited 0 (priya, round 2, tree H). The VALUE itself is a deploy decision this gate must not pin,
# or it would block the very lever the fix created. What it CAN insist on is that the value is
# legible: an unparseable or empty fallback is not a decision anyone made, it is the omission
# F-0390 is about.
#
# The resolution: the COMMITTED literal is pinned to Swapnil's F-0390 ruling (money IN on, money
# OUT off), while the RUNTIME overrides above it — the dispatch input and `vars.<NAME>` — stay
# completely free. So the lever is untouched (hold collection off from the settings UI, no commit),
# but silently committing the symptom back into the repo is a gate failure, which is what F-0390
# is for. Change the pins here and in the workflow together, deliberately, or not at all.
# PINNED is declared once at leg 1 and reused here — the two must never diverge.
for f in "${FLAGS[@]}"; do
  v=$(shipped "$f")
  want=${PINNED[$f]}
  case "$v" in
    "") say_fail "$WF: cannot read ${f}'s push fallback — the expression does not end in \`|| '<literal>'\`, so what a push-published image gets is unknowable from this file" ;;
    "$want") : ;;
    true|false) say_fail "$WF: ${f}'s committed push fallback is '${v}', but F-0390's recorded ruling pins it to '${want}'. Overriding at RUNTIME is what vars.${f#VITE_} and the dispatch input are for; changing the committed default is a decision that has to be re-recorded, not a diff" ;;
    *)  say_fail "$WF: ${f}'s push fallback is '${v}', which is neither 'true' nor 'false' — src/lib/api.ts compares it to the exact string 'true', so anything else silently means off" ;;
  esac
done

command -v node >/dev/null 2>&1 || { echo "UNAVAILABLE: node not on PATH"; exit 2; }
local_env=$(cd "$GATE_REPO" && node --input-type=module -e "
import { loadEnv } from 'vite';
const e = loadEnv('production', process.argv[1], 'VITE_');
for (const k of ['VITE_PAYMENTS_IN_ENABLED','VITE_PAYOUTS_ENABLED'])
  console.log(k + '=' + (e[k] === undefined ? '<UNDECLARED>' : e[k]));
" "$ROOT" 2>/dev/null) || { echo "UNAVAILABLE: could not run vite loadEnv from $GATE_REPO"; exit 2; }
echo "local-build (all .env files, as Vite merges them): $(printf '%s' "$local_env" | tr '\n' ' ')"
# Leg 1 reads the literal in .env.production. That is NOT what a local build gets: Vite loads
# .env, .env.local, .env.production and .env.production.local in that order, each overriding the
# last. So a `.env.production.local` containing VITE_PAYMENTS_IN_ENABLED=false silently wins over a
# correct .env.production — leg 1 sees the good literal, and the build ships money IN dead. Compare
# what loadEnv ACTUALLY resolves against the same pin, which closes the whole shadowing family
# rather than the one file this gate happens to read.
for f in "${FLAGS[@]}"; do
  rv=$(printf '%s\n' "$local_env" | grep -E "^${f}=" | head -1 | sed -E "s/^${f}=//")
  if [ "$rv" = "<UNDECLARED>" ]; then
    say_fail "vite loadEnv('production') resolves ${f} to nothing — a local prod build inlines it as false"
  elif [ "$rv" != "${PINNED[$f]}" ]; then
    say_fail "vite loadEnv('production') resolves ${f}='${rv}', not the pinned '${PINNED[$f]}' — some .env file later in Vite's precedence chain (.env.local / .env.production.local) is overriding .env.production"
  fi
done

# --- leg 6: the one combination that is unsafe rather than merely a choice ----------------------
# The VALUES are a deploy decision (Swapnil, F-0390: money IN on, money OUT off) and pinning them
# would block the very lever this fix created. Only refuse payouts-on against an unprovisioned
# RazorpayX: WalletService debits the creator through the ledger BEFORE calling the gateway, and
# RazorpayXClient.isConfigured() is a blankness check, so REPLACE_ME reads as configured and a
# live 401 is what comes back — the orphaned-debit window, on a real creator's balance.
# Checked against the SHIPPED value, which is the one v1 could not see.
GEN=deploy/utho/generate-env.sh
if [ "$OUT_SHIPPED" = "true" ] || printf '%s\n' "$local_env" | grep -qE '^VITE_PAYOUTS_ENABLED=true$'; then
  if [ -f "$GEN" ] && grep -qE '^RAZORPAYX_ACCOUNT_NUMBER=REPLACE_ME' "$GEN"; then
    say_fail "VITE_PAYOUTS_ENABLED is true while RAZORPAYX_ACCOUNT_NUMBER is REPLACE_ME ($GEN) — withdrawal debits the creator before calling RazorpayX"
  fi
fi

if [ "$fail" -eq 0 ]; then
  echo "PROVED: both money flags are declared in .env.production, promoted to ENV inside the Dockerfile BUILD stage, passed as build-args by the frontend job, rendered so neither an empty value nor an explicit false can be lost, and resolvable by Vite."
  exit 0
fi
exit 1
