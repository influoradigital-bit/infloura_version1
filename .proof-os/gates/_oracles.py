"""_oracles.py — the allowlist that decides what may render green. FROZEN.

origin: 0.3.3 audit. Every green-verdict check in the release tested that an
`oracle` field was PRESENT, never that its value was legitimate:

    validate.py   {"oracle": "Model"}  -> alignment 100.0% · proved 100.0%
    confirm.py    {"verdict":"proved","oracle":"model"}  -> admissible, exit 0
    confirm.py    {"verdict":"proved","oracle":"vibes"}  -> admissible, exit 0
    graph_source  an edge with no `oracle` key at all    -> aligned, exit 0

rules/VERDICT.md:30 — "`oracle: model` may NEVER render as proved. Ceiling:
believed (amber)." That law now has exactly one implementation, here, and
every caller asks this module rather than writing its own string test.

The design is a strict ALLOWLIST, not a denylist of model spellings. A
denylist loses to "Model", "claude-opus", "llm-judge", "" and a missing key —
all of which were verified to pass in 0.3.3. Unknown provenance is not
neutral: it is unproved.
"""
import os

# Deterministic oracles: a tool that produced an exit code or a parse.
DETERMINISTIC = {
    # type / lint / test
    "tsc", "eslint", "ruff", "pyflakes", "pytest", "mypy", "py_compile",
    "shellcheck", "node", "go", "mvn", "npm", "cargo",
    # security / runtime / browser
    "gitleaks", "lighthouse", "playwright", "curl", "npm-audit",
    # analysis provenance
    "ast", "parser", "compiler", "regex", "hash", "sha256",
    # a named executable check
    "script",
    # NOT "gate": it names no check. "some gate ran, unspecified" is exactly the
    # unstated provenance this allowlist exists to cap, and it was a one-word
    # bypass needing no path at all. Name the gate file instead.
}

# Named explicitly so the message can say what is wrong rather than "unknown".
KNOWN_MODEL = {
    "model", "llm", "claude", "gpt", "gemini", "openai", "anthropic",
    "judgment", "judge", "review", "opinion", "vibes", "believed", "inferred",
}


def normalise(value):
    """Whitespace-stripped, case-folded. Non-strings are not oracles."""
    if isinstance(value, str):
        return value.strip().lower()
    return None


def is_green_oracle(value, plugin_root=None):
    """(ok, reason). ok=False means this may never render green.

    A `gates/...` path counts only if the file actually exists — naming a gate
    that is not on disk is the F-0023 silent-oracle shape, and in 0.3.3 it was
    how a registry entry could be granted the `proved` ceiling.
    """
    v = normalise(value)
    if v is None:
        return False, (f"oracle is {type(value).__name__}, not a string — "
                       f"unverifiable provenance is capped at believed")
    if v == "":
        return False, ("oracle is empty — an unstated oracle is not a "
                       "deterministic one; ceiling is believed")
    if v in KNOWN_MODEL:
        return False, (f"oracle {v!r} is a model, not a deterministic check — "
                       f"ceiling is believed (VERDICT.md trust law)")
    if v.startswith("gates/") or v.startswith("./gates/"):
        # F-0333: normalise() case-folds, which is right for matching a NAME against the
        # deterministic allowlist and wrong for a filesystem PATH. Every gate in a real
        # project is uppercase (F-NNNN-slug.sh); on a case-sensitive filesystem the folded
        # path misses and each one reports "no such gate is on disk (F-0023)". NTFS hides
        # this. Resolve the caller's original spelling; keep v for the prefix test above,
        # which is case-insensitive by intent.
        v = value.strip() if isinstance(value, str) else v
        # A gate path must name an EXECUTABLE CHECK inside a gates/ directory that
        # this plugin controls. 0.3.4-rc granted green to anything isfile() liked:
        #   gates/../../../../etc/passwd   (lstrip('./') is a character strip, and
        #                                   '..' was never normalised)
        #   gates/eslint.sage.json         (a config asset, not a check)
        #   gates/__pycache__/x.pyc        (a build artefact)
        #   gates/x.sh -> /etc/passwd      (a symlink out of the tree)
        # and the roots included the CURRENT WORKING DIRECTORY, so an audited
        # project could grant itself `proved` with `mkdir gates && touch gates/x.py`.
        rel = v[2:] if v.startswith("./") else v
        parts = rel.split("/")
        if any(p in ("..", "", ".") for p in parts):
            return False, (f"oracle path {rel!r} is not a plain gates/<name> path — "
                           f"a traversal cannot be audited by anyone reading it")
        if len(parts) != 2:
            return False, (f"oracle names {rel!r}; a gate is gates/<file>, not a "
                           f"nested path (build artefacts and caches are not checks)")
        if os.path.splitext(parts[1])[1] not in (".py", ".sh"):
            return False, (f"oracle names {rel!r}, which is not an executable check — "
                           f"a gate is a .py or .sh file; config and data assets "
                           f"cannot have run")
        # Only roots this PLUGIN controls. Never the cwd: the audited project must
        # not be able to mint its own oracle.
        here = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
        roots = [r for r in (plugin_root, here) if r]
        pod = os.environ.get("PROOF_OS_DIR")
        if pod:
            roots.append(pod)          # project-local gates live in .proof-os/gates
        for root in roots:
            cand = os.path.join(root, rel)
            real = os.path.realpath(cand)
            gdir = os.path.realpath(os.path.join(root, "gates"))
            if not os.path.isfile(real):
                continue
            if os.path.dirname(real) != gdir:
                return False, (f"oracle names {rel!r} but it resolves to {real}, "
                               f"outside {gdir} — a symlink out of the gates "
                               f"directory is not a gate")
            return True, f"gate {rel} exists"
        return False, (f"oracle names {rel} but no such gate is on disk — "
                       f"a gate that does not exist cannot have run (F-0023)")
    # bare tool name, or "tool: detail" / "tool (detail)"
    head_parts = v.replace("(", " ").replace(":", " ").split()
    if not head_parts:
        return False, (f"oracle {value!r} contains no name — punctuation is not "
                       f"provenance; ceiling is believed")
    head = head_parts[0].rstrip(",;")
    if head in DETERMINISTIC:
        return True, f"deterministic oracle {head}"
    return False, (f"oracle {v!r} is not in the deterministic allowlist — "
                   f"unrecognised provenance is capped at believed")


def oracle_reason(value, plugin_root=None):
    return is_green_oracle(value, plugin_root)[1]


GREEN_STATUSES = {"aligned", "proved"}
ALL_STATUSES = {"aligned", "partial", "broken", "missing"}
VERDICT_WORDS = {"proved", "believed", "echo", "broken", "learned"}

# ─────────────────────────────────────────────────────────────────────────────
# ISOLATION (0.4.0) — see ISOLATION-SPEC.md
#
# 0.3.4 had trust ceilings and no concept of context isolation, and isolation is
# what actually makes a checker independent. A judgment service in the producer's
# own context is the same model holding the same assumptions: labelling it
# `believed` is honest, but it is not a second opinion. It is THE SAME EVIDENCE
# COUNTED TWICE, and scoring that at 0.5 is a statistical error dressed as
# caution — one observation, double-weighted, reported as more confidence.
#
# `echo` is the rung below `believed`. It contributes nothing AND is excluded
# from the denominator: an echo found nothing and proved nothing, so it must not
# move the score in either direction.
#
# Isolation is RECORDED BY THE DISPATCHER, never declared by the service about
# itself — a self-declared isolation just moves the assertion up one level, and
# VERDICT.md already forbids self-asserted trust. The dispatcher knows whether it
# created a fresh context, because it created it.
# ─────────────────────────────────────────────────────────────────────────────

ISOLATION_LEVELS = ("oracle", "fresh-context", "shared-context", "none")

_CEILING_BY_ISOLATION = {
    "oracle":         "proved",
    "fresh-context":  "believed",
    "shared-context": "echo",
    "none":           "echo",
}

_CEILING_RANK = {"echo": 0, "believed": 1, "proved": 2}

# Scoring weights. `echo` is deliberately absent: it is not a weight, it is an
# EXCLUSION. Callers must drop echo rows from the denominator, not score them 0.
STATUS_WEIGHT = {"aligned": 1.0, "partial": 0.5, "broken": 0.0, "missing": 0.0}


def normalise_isolation(value):
    """Any unrecognised or missing value is `none`. Failing closed here means an
    unrecorded review contributes nothing, which is the correct default."""
    if isinstance(value, str):
        v = value.strip().lower()
        if v in ISOLATION_LEVELS:
            return v
    return "none"


def ceiling_for_isolation(value):
    """The ceiling this isolation level can support, before the registry lowers it."""
    return _CEILING_BY_ISOLATION[normalise_isolation(value)]


def effective_ceiling(isolation, granted=None):
    """min(derived, granted). The registry may only ever LOWER the derived value.

    Returns (ceiling, over_granted, reason). `over_granted` is True when the
    registry tried to grant more than the recorded isolation can support — that
    is a registry error, not a service error, and the caller should exit 1.
    """
    derived = ceiling_for_isolation(isolation)
    iso = normalise_isolation(isolation)
    if granted is None:
        return derived, False, f"isolation {iso} supports {derived}"
    g = str(granted).strip().lower()
    if g not in _CEILING_RANK:
        return derived, False, (f"granted ceiling {granted!r} is not one of "
                                f"proved|believed|echo — ignored; isolation "
                                f"{iso} supports {derived}")
    if _CEILING_RANK[g] > _CEILING_RANK[derived]:
        return derived, True, (f"registry grants {g} but recorded isolation is "
                               f"{iso}, which supports at most {derived} — a "
                               f"ceiling is derived from isolation and the "
                               f"registry may only lower it")
    return g, False, (f"registry lowers {derived} to {g}" if g != derived
                      else f"isolation {iso} supports {derived}")


def is_echo(isolation, granted=None):
    """True when this review contributes no independent evidence."""
    return effective_ceiling(isolation, granted)[0] == "echo"
