#!/usr/bin/env python3
"""F-0382-money-post-census.py — gate for F-0382 (two-api-layers) and F-0486 (money-path-ungated).

F-0486 is here because this gate FOUND it. On its first run the census went red not on the
meera-api.ts instance F-0382 narrates — that one was already fixed — but on a THIRD client layer
nobody had counted: src/admin/services/api-contracts.ts's financeApi.retryPayout POSTed
/finance/payouts/{id}/retry, the live RazorpayX rail, with no money gate and no gate import in the
module at all, reachable from ReconciliationPanel behind nothing but a window.confirm. Two targeted
greps (api.ts, then meera-api.ts) had each reported the work complete. That is the whole argument
for a census, and the two records share this gate because they are one class seen twice.

WHAT THIS PROVES
----------------
The record's `missed_by` is the specification, verbatim:

    "a check that every client function POSTing to a money endpoint carries the
     gate — grepping api.ts alone reports the work as complete"

So this gate is a CENSUS, not a spot-check. It walks EVERY non-test .ts/.tsx
under src/, finds every call site that POSTs to a money-moving endpoint no
matter which client module it lives in, and requires each one to carry
`requirePaymentsEnabled(<op>)` / `isMoneyActionBlocked(<op>)` inside the
enclosing declaration.

Why a census and not a grep: this repo has THREE client layers that issue
requests —

    src/lib/api.ts                     (http.request('POST', '/wallet/...'))
    src/lib/meera-api.ts               (its own local request() helper)
    src/admin/services/api-contracts.ts(apiRequest(path, { method: 'POST' }))

F-0382 happened because the payments gate was added to layer 1 only.
`useEscrowFund`, the hook behind the real Fund Escrow control, calls
`meeraApi.fundEscrow` in layer 2, so the guarded path was not the path users
take. A grep of api.ts reported the work complete. The census is the check
that could not have reported that.

The hazard the gate exists to stop is PREEMPTIVE, which is why "catch the
server error instead" is not an alternative: WalletService debits the wallet
through the ledger BEFORE it calls RazorpayX, so a request that reaches an
unprovisioned gateway has already reduced a real balance with no transfer made
(the orphaned-debit window PayoutOrphanedDebitSweepJob exists to sweep). The
request must never leave the browser.

CLASSIFICATION
--------------
REQUIRED  — POSTs that move money over an external rail. Gate mandatory.
EXEMPT    — money-shaped paths that move nothing over a rail, each with a
            written reason. Gating these would be actively wrong (see below).
UNCLASSIFIED — a money-shaped POST path nobody has triaged. Fails on purpose:
            that is exactly the state meeraApi.fundEscrow was in.

ANTI-VACUITY
------------
  * Comments are stripped with a quote-aware scanner before anything is
    matched, so a fix whose comment quotes "POST /wallet/escrow/fund" (several
    already do — deal-payments-tab.tsx:44, useEscrowFund.ts:14) cannot create
    a phantom call site, and a comment naming `requirePaymentsEnabled` cannot
    satisfy a gate.
  * The gate token is not searched file-wide — a bare substring hit would pass
    vacuously off an unrelated sibling method in the same 4000-line module.
    It must appear between the enclosing declaration's header line and the
    POST line, and the operation literal must match the one that endpoint
    requires.

  exit 0 = proved (every money POST in every client layer carries the gate)
  exit 1 = broken (a money POST reaches the network ungated)
  exit 2 = unavailable (tool/paths missing) — never a false red
"""

from __future__ import annotations

import os
import re
import sys

# --------------------------------------------------------------------------
# Endpoint classification
# --------------------------------------------------------------------------

# POSTs that move money over an external rail -> operation the gate must name.
REQUIRED: list[tuple[str, str]] = [
    # Brand pays in through Razorpay Checkout; ledger credit follows the webhook.
    (r"/wallet/topup\b", "topup"),
    # Creator cash-out. Ledger debit posts BEFORE RazorpayX is called.
    (r"/wallet/withdraw\b", "withdraw"),
    # Brand funds a milestone hold. Debits the brand wallet / opens a Razorpay order.
    (r"/wallet/escrow/fund\b", "escrow-fund"),
    # Admin re-drives a FAILED payout through PayoutReconciliationService
    # #retryFailedPayout — the live RazorpayX rail, same money-OUT hazard as
    # /wallet/withdraw, which is why it must name the same operation.
    (r"/finance/payouts/[^/'\"`]+/retry\b", "withdraw"),
]

# Money-shaped paths that deliberately need NO gate, each with the reason the
# gate would be wrong. These are asserted-absent, not ignored: if one ever
# acquires a gate that is a separate conversation, but it is not this defect.
EXEMPT: list[tuple[str, str]] = [
    (
        r"/wallet/escrow/release\b",
        "ledger-internal (EscrowService#releaseInternal credits the creator's wallet, "
        "no gateway call); blocking it would strand money already collected",
    ),
    (
        r"/wallet/payout-methods\b",
        "registers a bank account, moves no money",
    ),
    (
        r"/finance/payouts/manual\b",
        "records a bank transfer a human ALREADY made; it is the payout rail WHILE "
        "RazorpayX is unprovisioned, so gating it on PAYOUTS_ENABLED inverts its purpose",
    ),
    (
        r"/billing/(checkout|cancel)\b",
        "subscription billing — returns a hosted checkout URL; no ledger debit precedes it, "
        "so the preemptive-block hazard does not apply",
    ),
    (
        r"/billing/(comp|override)\b",
        "admin entitlement change (grants a complimentary plan / overrides a plan assignment); "
        "it changes what a workspace may use, and charges, refunds and transfers nothing",
    ),
]

# A path literal is "money-shaped" if it starts a route and mentions one of these.
MONEY_WORDS = re.compile(
    r"(wallet|escrow|payout|payment|topup|top-up|withdraw|refund|billing|invoice|finance|razorpay)",
    re.IGNORECASE,
)

GATE_CALL = re.compile(
    r"\b(?:requirePaymentsEnabled|isMoneyActionBlocked)\s*\(\s*['\"]([a-z-]+)['\"]"
)

# Declaration headers: `foo: (a) =>`, `foo: async (a) =>`, `const foo = `,
# `export const foo = `, `function foo(`, `async foo(`.
DECL_HEADER = re.compile(
    r"^\s*(?:export\s+)?(?:const|let|var)\s+[A-Za-z_$][\w$]*\s*=|"
    r"^\s*(?:export\s+)?(?:async\s+)?function\s+[A-Za-z_$][\w$]*\s*\(|"
    r"^\s*[A-Za-z_$][\w$]*\s*:\s*(?:async\s*)?(?:\(|function\b)|"
    r"^\s*(?:async\s+)?[A-Za-z_$][\w$]*\s*\([^)]*\)\s*(?::[^={]+)?\{"
)

MAX_DECL_LOOKBACK = 90  # lines


def strip_comments(src: str) -> str:
    """Blank out // and /* */ comments, preserving line/column offsets.

    Quote-aware: '/' inside a string ('https://…', '/wallet/topup') is left
    alone. Replaces comment bytes with spaces (newlines kept) so every line
    number and slice offset in the stripped text still matches the original.
    """
    out = list(src)
    i, n = 0, len(src)
    quote = ""          # "'", '"', or '`' when inside a string
    while i < n:
        c = src[i]
        if quote:
            if c == "\\":
                i += 2
                continue
            if c == quote:
                quote = ""
            i += 1
            continue
        if c in "'\"`":
            quote = c
            i += 1
            continue
        if c == "/" and i + 1 < n:
            nxt = src[i + 1]
            if nxt == "/":
                while i < n and src[i] != "\n":
                    out[i] = " "
                    i += 1
                continue
            if nxt == "*":
                while i < n and not (src[i] == "*" and i + 1 < n and src[i + 1] == "/"):
                    if src[i] != "\n":
                        out[i] = " "
                    i += 1
                for _ in range(2):
                    if i < n:
                        out[i] = " "
                        i += 1
                continue
        i += 1
    return "".join(out)


def classify(path: str) -> tuple[str, str]:
    for pat, op in REQUIRED:
        if re.search(pat, path):
            return "REQUIRED", op
    for pat, reason in EXEMPT:
        if re.search(pat, path):
            return "EXEMPT", reason
    return "UNCLASSIFIED", ""


def is_post(code: str, start: int, end: int) -> bool:
    """True when the call around this path literal is a POST.

    Covers both call shapes in this repo:
      request('POST', '/wallet/topup', …)        -> 'POST' just BEFORE the path
      apiRequest(`/x/retry`, { method: 'POST' }) -> method: 'POST' just AFTER
    """
    before = code[max(0, start - 140):start]
    after = code[end:end + 260]
    if re.search(r"['\"]POST['\"]\s*,\s*$", before):
        return True
    if re.search(r"\bmethod\s*:\s*['\"]POST['\"]", after):
        return True
    return False


def enclosing_window(lines: list[str], post_line_idx: int) -> tuple[int, str]:
    """Return (header_line_idx, source of header..post_line inclusive)."""
    lo = max(0, post_line_idx - MAX_DECL_LOOKBACK)
    header = lo
    for j in range(post_line_idx, lo - 1, -1):
        if DECL_HEADER.search(lines[j]):
            header = j
            break
    return header, "\n".join(lines[header:post_line_idx + 1])


def main() -> int:
    root = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
    src_dir = os.path.join(root, "src")
    if not os.path.isdir(src_dir):
        print("· src/ not found — unavailable")
        print("VERDICT: unavailable")
        print("NOT CHECKED: everything — the frontend source tree is not at this path.")
        return 2

    files: list[str] = []
    for dirpath, dirnames, filenames in os.walk(src_dir):
        dirnames[:] = [d for d in dirnames if d not in ("node_modules", "__tests__", "dist")]
        for fn in filenames:
            if not fn.endswith((".ts", ".tsx")):
                continue
            if ".test." in fn or ".spec." in fn or fn.endswith(".d.ts"):
                continue
            files.append(os.path.join(dirpath, fn))

    if not files:
        print("· no source files under src/ — unavailable")
        print("VERDICT: unavailable")
        print("NOT CHECKED: everything — nothing to scan.")
        return 2

    # Sanity: the census is worthless if it cannot see the modules it exists to
    # compare. Absence of a layer is unavailable, never a pass.
    must_see = [
        os.path.join(src_dir, "lib", "api.ts"),
        os.path.join(src_dir, "lib", "meera-api.ts"),
        os.path.join(src_dir, "admin", "services", "api-contracts.ts"),
    ]
    missing = [p for p in must_see if not os.path.isfile(p)]
    if missing:
        for p in missing:
            print("· expected client layer missing: %s" % os.path.relpath(p, root))
        print("VERDICT: unavailable")
        print("NOT CHECKED: the census cannot claim completeness with a known client layer absent.")
        return 2

    path_lit = re.compile(r"(['\"`])(/[^'\"`\n]{2,120})\1")

    sites: list[dict] = []
    for fp in sorted(files):
        try:
            raw = open(fp, "r", encoding="utf-8", errors="replace").read()
        except OSError as exc:
            print("· cannot read %s (%s) — unavailable" % (fp, exc))
            print("VERDICT: unavailable")
            print("NOT CHECKED: the full census — a source file was unreadable.")
            return 2
        code = strip_comments(raw)
        if not MONEY_WORDS.search(code):
            continue
        lines = code.split("\n")
        for m in path_lit.finditer(code):
            path = m.group(2)
            if not MONEY_WORDS.search(path):
                continue
            if not is_post(code, m.start(), m.end()):
                continue
            line_idx = code.count("\n", 0, m.start())
            kind, detail = classify(path)
            header_idx, window = enclosing_window(lines, line_idx)
            ops = set(GATE_CALL.findall(window))
            sites.append({
                "file": os.path.relpath(fp, root).replace("\\", "/"),
                "line": line_idx + 1,
                "header": header_idx + 1,
                "path": path,
                "kind": kind,
                "detail": detail,
                "ops": ops,
            })

    if not sites:
        # Every known money POST vanished from the tree. That is not a pass —
        # it means the detector no longer matches this codebase's call shapes.
        print("· detector found ZERO money POST call sites — it has gone blind")
        print("VERDICT: unavailable")
        print("NOT CHECKED: anything — the scanner matched nothing, so its silence proves nothing.")
        return 2

    ungated: list[dict] = []
    untriaged: list[dict] = []
    ok: list[dict] = []

    print("· money-POST census across every client layer under src/")
    for s in sorted(sites, key=lambda x: (x["file"], x["line"])):
        loc = "%s:%d" % (s["file"], s["line"])
        if s["kind"] == "UNCLASSIFIED":
            untriaged.append(s)
            print("  UNTRIAGED  %-58s POST %s" % (loc, s["path"]))
        elif s["kind"] == "EXEMPT":
            print("  exempt     %-58s POST %s" % (loc, s["path"]))
        else:
            op = s["detail"]
            if op in s["ops"]:
                ok.append(s)
                print("  gated      %-58s POST %-30s (%s)" % (loc, s["path"], op))
            else:
                ungated.append(s)
                saw = ", ".join(sorted(s["ops"])) if s["ops"] else "no gate call at all"
                print("  UNGATED    %-58s POST %-30s wanted '%s' between line %d and here — %s"
                      % (loc, s["path"], op, s["header"], saw))

    print("· %d money POST site(s): %d gated, %d ungated, %d untriaged"
          % (len(sites), len(ok), len(ungated), len(untriaged)))

    # A census that only ever saw one layer proves nothing about the second —
    # that WAS the defect. Refuse to green from a single file.
    layers = {s["file"] for s in sites}
    if len(layers) < 2:
        print("· census saw only %s — a one-layer census is the F-0382 mistake" % ", ".join(layers))
        print("VERDICT: unavailable")
        print("NOT CHECKED: the other client layers — the scanner did not reach them.")
        return 2

    if ungated or untriaged:
        for s in ungated:
            print("· BROKEN %s:%d POSTs %s with no money gate — the request leaves the browser"
                  % (s["file"], s["line"], s["path"]))
        for s in untriaged:
            print("· BROKEN %s:%d POSTs %s, a money-shaped path this census has never triaged"
                  % (s["file"], s["line"], s["path"]))
        print("VERDICT: broken — a client function POSTs a money endpoint without the payments "
              "gate; checking src/lib/api.ts alone would have reported this complete (F-0382)")
        print("NOT CHECKED: whether the gate, where present, is CORRECT at runtime (that is "
              "src/lib/payments-gate.test.tsx's job); server-side authorization, which is the "
              "real backstop; money POSTs issued from outside src/ (e2e helpers, scripts); "
              "call shapes this scanner cannot see — a path built by string concatenation or "
              "held in a variable rather than written as a literal at the call site.")
        return 1

    print("VERDICT: aligned (proved) — every money-moving POST in every client layer under src/ "
          "carries requirePaymentsEnabled/isMoneyActionBlocked with the right operation")
    print("NOT CHECKED: whether the gate, where present, is CORRECT at runtime (that is "
          "src/lib/payments-gate.test.tsx's job); server-side authorization, which is the "
          "real backstop; money POSTs issued from outside src/ (e2e helpers, scripts); "
          "call shapes this scanner cannot see — a path built by string concatenation or "
          "held in a variable rather than written as a literal at the call site.")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as exc:  # never a false red
        print("· gate crashed: %r" % (exc,))
        print("VERDICT: unavailable")
        print("NOT CHECKED: the census — the gate itself failed to run.")
        sys.exit(2)
