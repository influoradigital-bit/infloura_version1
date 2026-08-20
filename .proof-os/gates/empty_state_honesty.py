#!/usr/bin/env python3
"""Gate for ledger class `empty-state-misleads` (F-0278, F-0349, F-0384).

The shared shape of all three failures: a UI renders an AFFIRMATIVE state that is
derived ONLY from local data, when the condition that would justify the claim lives
in another system, or in no system at all.

  F-0278  "All caught up!"  <- an empty campaign list that means NEVER STARTED
  F-0349  "They appear here once the creator submits"  <- 0 slots, which are
          materialised at contract generation, never by creator action
  F-0384  "Active"  <- !expired && !atLimit, with no field anywhere in the model
          reflecting the store that actually grants the code its validity

tsc, eslint and a screenshot pass all three: the string is a valid string and the
boolean is a correctly-computed boolean. Only the relationship between the claim and
its evidence is wrong, so that relationship is what this gate reads.

exit 0 proved . 1 broken . 2 unavailable
"""
import re
import os
import sys

ROOT = sys.argv[1] if len(sys.argv) > 1 else "src"

CLAIMS = [
    "all caught up",
    "no pending actions",
    "you're all set",
    "youre all set",
    "ready to use",
    "available now",
    "nothing to do",
    "no active",
    "they appear here once",
    "appear here once the",
]

# A bare status word IS the whole rendered string ("Active"), as opposed to a label
# that merely contains it ("Active Campaigns"). F-0384's coupon badge is this shape.
EXACT_CLAIMS = {"active", "available", "valid", "ready", "all caught up!"}

# The claim is legitimate when it is tied to state fetched from the system that
# actually decides it -- a sync marker, an external id, a verified/unknown status.
JUSTIFIED = re.compile(
    r"(verifiedAt|confirmedBy|syncedAt|syncStatus|syncState|externalId|external_id"
    r"|providerStatus|storeStatus|remoteState|lastCheckedAt|UNVERIFIED|UNKNOWN"
    r"|NOT_VERIFIED|unverified)"
)

# ...and it is suspect when the only thing behind it is local emptiness or a
# purely client-side predicate.
LOCAL_ONLY = re.compile(
    r"(\.length\s*===?\s*0|!\w+\.length|isEmpty|\.length\s*<\s*1"
    r"|expiresAt|usageLimit|usageCount)"
)

STRING_LIT = re.compile(r"""(['"`])([^'"`\n]{0,160})\1""")


def scan(root):
    bad = []
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in ("node_modules", "__tests__", ".git")]
        for name in filenames:
            if not name.endswith((".tsx", ".ts")) or ".test." in name:
                continue
            path = os.path.join(dirpath, name).replace("\\", "/")
            try:
                src = open(path, encoding="utf-8", errors="replace").read()
            except OSError:
                continue
            for m in STRING_LIT.finditer(src):
                text = m.group(2)
                low = text.lower()
                stripped = low.strip()
                if stripped not in EXACT_CLAIMS and not any(c in low for c in CLAIMS):
                    continue
                lo = max(0, m.start() - 1500)
                hi = min(len(src), m.end() + 1500)
                window = src[lo:hi]
                if JUSTIFIED.search(window):
                    continue
                if not LOCAL_ONLY.search(window):
                    continue
                bad.append((path, src[: m.start()].count("\n") + 1, text.strip()[:72]))
    return bad


def main():
    if not os.path.isdir(ROOT):
        print("* %s is not a directory - unavailable" % ROOT)
        print("NOT CHECKED: everything; there was no source tree to read")
        return 2
    bad = scan(ROOT)
    print("* empty-state honesty over %s" % ROOT)
    for path, line, text in bad:
        print('  %s:%d  asserts "%s" from local emptiness, with no verified-state field in scope'
              % (path, line, text))
    if bad:
        print("VERDICT: broken (real findings above)")
    else:
        print("VERDICT: aligned (proved) - no unbacked availability claim found")
    print(
        "NOT CHECKED: whether a claim tied to a verifiedAt/syncStatus field is tied to the RIGHT "
        "one, or whether that field is ever populated; affirmative claims phrased in wording this "
        "gate does not know; claims assembled at runtime from i18n catalogues or server strings; "
        "and whether the empty state, once honest, is the one the user actually needed"
    )
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
