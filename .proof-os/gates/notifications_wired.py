#!/usr/bin/env python3
"""gates/notifications_wired.py — guards the F-0043 fix from regressing.

ORIGIN: F-0043 (notifications-mock-panel, src/components/brand/brand-layout.tsx).
  The brand top-bar notification dropdown rendered a hardcoded `mockNotifications`
  array, and its badge read `useNotificationStore` — a zustand store that was NEVER
  populated (so the badge was always 0). Both were disconnected from the live
  GET /notifications system. Ledger symptom: "brand notifications dropdown renders
  hardcoded mockNotifications (defined :131) while the badge count is real;
  /notifications backend exists but layout never calls it". The fix wired brand-layout
  to useNotifications('brand') and DELETED the dead store.

F-0329 — THIS GATE'S OWN TWO DEFECTS, both reproduced by injection before this repair.
Log: .proof-os/tasks/T-F0329-GATES/notifications_wired.inject.log.

  1. FALSE RED (the F-0266 disease, fixed across 53 shell gates but never in the .py
     population, which does its own file reading). This gate read RAW BYTES. Adding the
     ordinary documenting comment above the live wiring —
         // F-0043: this replaced the hardcoded mockNotifications array that used to be
         // declared here and rendered straight into the popover body.
     — took a completely correct, fully wired tree to OBSERVED EXIT 1, "broken - 1
     reference(s) to the removed mock/dead-store notification pattern", pointing at its
     own fix's documentation. The pressure that creates is to stop documenting fixes.

  2. FALSE GREEN, the serious one. Every assertion was an ABSENCE. Nothing here ever
     proved brand-layout was WIRED. Deleting the `useNotifications` import, replacing the
     hook call with a hardcoded array under a different name, and leaving the popover
     rendering that array — F-0043 reintroduced in behaviour, exactly, with GET
     /notifications never called — left this gate at OBSERVED EXIT 0 printing
     "aligned (proved) - brand notifications wired to useNotifications" about a file
     containing ZERO occurrences of useNotifications. A closed record standing on a gate
     that could not fail.

WHAT THIS GATE NOW ASSERTS. Not that a token appears. A CHAIN, over the comment-free CODE
of two files, from the rendered list back to the network call:

    import useNotifications  ->  `notifications` is BOUND BY that hook, for role 'brand'
    ->  the popover iterates THAT binding  ->  the badge consumes THAT unreadCount
    ->  and the hook itself really calls notificationsApi.list(), i.e. GET /notifications

Every link is checked against the binding, not against the presence of a word, so the word
appearing in a neighbouring statement (F-0319) or in a comment (F-0266) satisfies nothing.

AND IT PROVES IT CAN FAIL, on every run, before it believes itself. Following
gates/F-0273-frozen-escrow-counts-as-locked.sh: three known implementations are FROZEN into
this file and put through the very same assertion table first —
    · F-0043 verbatim (mockNotifications)                 must be REJECTED
    · the F-0329 injection (renamed array, no forbidden
      token, wiring deleted) — what the old gate greened  must be REJECTED
    · correct wiring whose comments quote both forbidden
      tokens                                              must be ACCEPTED
If a bad one passes, this gate is blind and exits 1 saying so rather than reporting a
verdict about the real code. If the documented-good one fails, this gate has regressed to
the F-0266 shape and exits 1 saying that instead. A gate that cannot fail is worse than no
gate; so is one that cannot pass the fix it guards.

LAW: exit 1 = real finding · 2 = cannot run · 0 = proved.
usage: notifications_wired.py [project_root]
"""
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
try:
    from _code import CodeUnavailable, code_of, code_of_text, harden_stdout
except Exception as _e:  # noqa: BLE001
    print(f"gates/_code.py unreadable ({_e}) - unavailable")
    sys.exit(2)

# F-0325: a gate that crashes emitting a character the console cannot encode is a false
# RED that says nothing about the artefact it guards. Do this before the first print.
harden_stdout()

LAYOUT = "src/components/brand/brand-layout.tsx"
HOOK = "src/hooks/useNotifications.ts"
ROLE = "brand"

# The two patterns F-0043 removed. MOCK_NOTIFICATIONS inside the useNotifications hook is
# the demo-mode fallback (isApiLive() === false) and is deliberately NOT one of these.
FORBIDDEN = ("mockNotifications", "useNotificationStore")


# ---------------------------------------------------------------------------------------
# 1 · reading bindings, not words
# ---------------------------------------------------------------------------------------
def _line_of(code: str, offset: int) -> int:
    return code.count("\n", 0, offset) + 1


def _destructured_locals(props: str):
    """Local names introduced by `{ a, b: c, d }`. For `b: c` the LOCAL name is `c` —
    that is the name JSX will actually reference, and the only one worth binding to."""
    out = []
    depth = 0
    part = ""
    for ch in props:
        if ch in "{[(":
            depth += 1
        elif ch in "}])":
            depth -= 1
        if ch == "," and depth == 0:
            out.append(part)
            part = ""
        else:
            part += ch
    out.append(part)
    names = []
    for raw in out:
        p = raw.split("=")[0].strip()          # drop a default value
        if not p:
            continue
        local = p.split(":")[-1].strip() if ":" in p else p
        if re.fullmatch(r"[A-Za-z_$][\w$]*", local):
            names.append(local)
    return names


def bindings_of(code: str, name: str):
    """Every place `name` is BOUND in this code, as (kind, rhs, lineno).

    kind is 'destructure' (const { name } = rhs) or 'direct' (const name = rhs). This is
    the whole point of the repair: `notifications` existing in the file proves nothing;
    what it is bound TO is the fact F-0043 is about.
    """
    found = []
    for m in re.finditer(r"\b(?:const|let|var)\s*\{([^{}]*)\}\s*=\s*([^;]+);", code, re.S):
        if name in _destructured_locals(m.group(1)):
            found.append(("destructure", " ".join(m.group(2).split()), _line_of(code, m.start())))
    for m in re.finditer(r"\b(?:const|let|var)\s+([A-Za-z_$][\w$]*)\s*=\s*([^;]+);", code, re.S):
        if m.group(1) == name:
            found.append(("direct", " ".join(m.group(2).split()), _line_of(code, m.start())))
    return found


def forbidden_in(code: str):
    """(lineno, pattern, text) for each forbidden token in CODE. Comments are already gone
    by the time this sees the text — that is defect 1's repair, and it is self-checked."""
    hits = []
    for i, line in enumerate(code.splitlines(), 1):
        for pat in FORBIDDEN:
            if re.search(r"\b" + re.escape(pat) + r"\b", line):
                hits.append((i, pat, line.strip()[:70]))
    return hits


# ---------------------------------------------------------------------------------------
# 2 · the assertion table. One function, applied to whichever layout source is handed to
#     it — the frozen fixtures first, the real file only after those have proved it works.
# ---------------------------------------------------------------------------------------
def assess_layout(code: str):
    """[] when the wiring chain holds; a list of human-readable failures when it does not."""
    bad = []

    # A1 — the hook is actually imported here, in code.
    if not re.search(r"^\s*import\s*\{[^}]*\buseNotifications\b[^}]*\}\s*from\s*['\"][^'\"]*"
                     r"useNotifications['\"]", code, re.M):
        bad.append("no `import { useNotifications } from '.../useNotifications'` in this "
                   "file's CODE - the layout cannot be wired to a hook it does not import")

    # A2/A3 — the rendered list is BOUND BY that hook, for this role. This is the assertion
    # the old gate never had, and the one the F-0329 injection walked straight through.
    notif = bindings_of(code, "notifications")
    hook_rhs = None
    if not notif:
        bad.append("`notifications` is never bound in this file - the popover has no list")
    elif len(notif) > 1:
        bad.append(f"`notifications` is bound {len(notif)} times (lines "
                   f"{', '.join(str(b[2]) for b in notif)}) - which one reaches the popover "
                   "is not decidable from here; collapse it to one binding")
    else:
        kind, rhs, ln = notif[0]
        if not re.match(r"useNotifications\s*\(", rhs):
            bad.append(f"line {ln}: `notifications` is bound by a {kind} from `{rhs[:60]}` - "
                       "NOT from useNotifications(). This is F-0043: the dropdown renders a "
                       "local list and GET /notifications is never called")
        else:
            hook_rhs = rhs
            arg = re.match(r"useNotifications\s*\(\s*(['\"])([^'\"]*)\1", rhs)
            if not arg:
                bad.append(f"line {ln}: useNotifications() is called without a literal role "
                           "argument - this gate cannot tell which feed the brand bell reads")
            elif arg.group(2) != ROLE:
                bad.append(f"line {ln}: useNotifications('{arg.group(2)}') in the BRAND layout "
                           f"- expected '{ROLE}'")

    # A4 — the badge count comes off the SAME hook call, not from a second source. F-0043's
    # signature was precisely a real-looking badge over a fake list.
    unread = bindings_of(code, "unreadCount")
    if not unread:
        bad.append("`unreadCount` is never bound in this file - the bell badge has no count")
    elif len(unread) > 1:
        bad.append(f"`unreadCount` is bound {len(unread)} times - ambiguous badge source")
    elif hook_rhs is not None and unread[0][1] != hook_rhs:
        bad.append(f"line {unread[0][2]}: `unreadCount` comes from `{unread[0][1][:60]}`, not "
                   "from the same useNotifications() call that feeds the list - badge and "
                   "panel can disagree, which is the F-0043 symptom verbatim")

    # A5 — the popover actually iterates that binding.
    if not re.search(r"\bnotifications\s*\.\s*map\s*\(", code):
        bad.append("nothing iterates `notifications` (no `notifications.map(`) - the hook may "
                   "be called, but its list is not what the dropdown renders")

    # A6 — and the badge actually consumes the count somewhere other than its own binding.
    uses = [i for i, l in enumerate(code.splitlines(), 1)
            if re.search(r"\bunreadCount\b", l) and not re.search(r"\b(?:const|let|var)\b", l)]
    if not uses:
        bad.append("`unreadCount` is bound but never used - the badge is not reading it")

    # A7 — the original absence check, now over CODE. Kept: it is still the cheapest way to
    # catch the dead store coming back. It is no longer the ONLY thing here.
    for ln, pat, text in forbidden_in(code):
        bad.append(f"line {ln}: `{pat}` is back in code - {text}")

    return bad


def assess_hook(code: str):
    """The last hop: the hook the layout is wired to must really call the endpoint.

    Without this, 'wired' means wired to something — a hook rewritten to return a constant
    would satisfy every assertion above while GET /notifications is never called, which is
    F-0043 moved one file to the left.
    """
    bad = []
    if not re.search(r"\bnotificationsApi\s*\.\s*list\s*\(", code):
        bad.append("useNotifications never calls `notificationsApi.list(` - the hook the "
                   "layout is wired to does not reach GET /notifications (F-0043 moved one "
                   "file left)")
    return bad


# ---------------------------------------------------------------------------------------
# 3 · FROZEN IMPLEMENTATIONS. The gate runs its own table over these before it will report
#     anything about the real tree. Device copied from
#     gates/F-0273-frozen-escrow-counts-as-locked.sh.
# ---------------------------------------------------------------------------------------
FROZEN_BAD_F0043 = """
import { useNotificationStore } from '@/lib/store';
const mockNotifications = [
  { id: '1', title: 'New application', body: 'x', read: false, createdAt: '' },
];
export function BrandLayout() {
  const notifications = mockNotifications;
  const unreadCount = useNotificationStore((s) => s.count);
  return <div>{notifications.map((n) => <span key={n.id}>{n.title}</span>)}{unreadCount}</div>;
}
"""

# The injection that walked through the OLD gate at observed exit 0. No forbidden token
# appears anywhere in it; the array is simply renamed and the import deleted.
FROZEN_BAD_F0329_INJECTION = """
export function BrandLayout() {
  const notifications = [
    { id: '1', title: 'New application', body: 'x', read: false, createdAt: '' },
  ];
  const unreadCount = notifications.filter((n) => !n.read).length;
  return <div>{notifications.map((n) => <span key={n.id}>{n.title}</span>)}{unreadCount}</div>;
}
"""

# Correct wiring whose comments quote BOTH forbidden tokens — the false RED of defect 1.
# This must be ACCEPTED, or the repair has regressed and documenting a fix is punished again.
FROZEN_GOOD_DOCUMENTED = """
import { useNotifications } from '@/hooks/useNotifications';
/* F-0043: the hardcoded mockNotifications array that used to live here is gone, and so is
   the never-populated useNotificationStore the badge used to read. */
export function BrandLayout() {
  // replaces mockNotifications; the badge no longer reads useNotificationStore
  const { notifications, unreadCount, refresh: refreshNotifications } = useNotifications('brand');
  return <div>{notifications.map((n) => <span key={n.id}>{n.title}</span>)}{unreadCount}</div>;
}
"""

FROZEN_BAD_HOOK = """
export function useNotifications(role: Role) {
  // backend not started - returning the fixture for now
  const [notifications] = useState(MOCK_NOTIFICATIONS);
  return { notifications, unreadCount: 0, refresh: async () => {} };
}
"""


def self_check() -> int:
    """0 = the table demonstrably works. 1 = this gate must not report a verdict."""
    print("- self-check: this gate's assertions are run over three FROZEN implementations")
    broken = False

    for label, src in (("F-0043 verbatim (mockNotifications + dead store)", FROZEN_BAD_F0043),
                       ("the F-0329 injection (renamed array, wiring deleted, no forbidden "
                        "token) - what the OLD gate certified as aligned",
                        FROZEN_BAD_F0329_INJECTION)):
        found = assess_layout(code_of_text(src, "ts"))
        if not found:
            print(f"  x ACCEPTED a known-bad layout: {label}")
            broken = True
        else:
            print(f"  ok rejected: {label} ({len(found)} failure(s))")

    good = assess_layout(code_of_text(FROZEN_GOOD_DOCUMENTED, "ts"))
    if good:
        print("  x REJECTED correct wiring whose comments quote the forbidden tokens:")
        for g in good:
            print(f"      {g}")
        broken = True
    else:
        print("  ok accepted: correct wiring documented with comments naming both forbidden "
              "tokens")

    hook_bad = assess_hook(code_of_text(FROZEN_BAD_HOOK, "ts"))
    if not hook_bad:
        print("  x ACCEPTED a hook that never calls the endpoint")
        broken = True
    else:
        print("  ok rejected: a useNotifications that returns the fixture and never calls "
              "notificationsApi.list()")

    if broken:
        print("- THIS GATE CANNOT BE TRUSTED: its own assertion table just mis-classified an")
        print("  implementation it was written to classify. Refusing to report a verdict about")
        print("  the real code from a check that has proved itself blind.")
        print("VERDICT: broken - the F-0043 gate's assertions no longer detect F-0043 (F-0329)")
        print("NOT CHECKED: the real product code - this run never got that far")
        return 1
    print("  good - the table rejects the two known-bad shapes and accepts the documented fix,")
    print("  so a green below means something")
    return 0


# ---------------------------------------------------------------------------------------
# 4 · the real tree
# ---------------------------------------------------------------------------------------
def main(argv) -> int:
    root = Path(argv[0] if argv else ".").resolve()
    src_dir = root / "src"
    if not src_dir.is_dir():
        print(f"- no {src_dir} - unavailable")
        return 2
    layout = root / LAYOUT
    hook = root / HOOK
    for f in (layout, hook):
        if not f.is_file():
            print(f"- {f} missing - unavailable")
            return 2

    rc = self_check()
    if rc != 0:
        return rc

    try:
        layout_code = code_of(layout)
        hook_code = code_of(hook)
    except CodeUnavailable as e:
        print(f"- {e} - unavailable")
        return 2

    fail = []
    print(f"- {LAYOUT}: the wiring chain, over CODE")
    fail += [f"{LAYOUT}: {m}" for m in assess_layout(layout_code)]
    print(f"- {HOOK}: reaches GET /notifications")
    fail += [f"{HOOK}: {m}" for m in assess_hook(hook_code)]

    # repo-wide: the dead patterns must not come back anywhere in src/, comment-aware now.
    print("- src/: neither dead pattern is back anywhere in application CODE")
    scanned = 0
    for f in sorted(list(src_dir.rglob("*.ts")) + list(src_dir.rglob("*.tsx"))):
        try:
            text = code_of(f)
        except CodeUnavailable as e:
            # LAW: never a silent partial answer over a subset of the tree.
            print(f"- {e} - unavailable")
            return 2
        scanned += 1
        rel = str(f.relative_to(root)).replace("\\", "/")
        for ln, pat, line in forbidden_in(text):
            fail.append(f"{rel}:{ln}: `{pat}` - {line}")
    print(f"  {scanned} source files read as code, not as bytes")

    if fail:
        print(f"VERDICT: broken - the brand notification bell is not wired to the live feed "
              f"({len(fail)} failure(s)) (F-0043)")
        for m in fail:
            print(f"  {m}")
        print("NOT CHECKED: everything below the first broken link - a chain reports where it "
              "parted, not what the rest would have said")
        return 1

    print("VERDICT: aligned (proved) - brand-layout imports useNotifications; `notifications` "
          "and `unreadCount` are BOUND BY that one useNotifications('brand') call and by "
          "nothing else; the popover iterates that binding and the badge consumes that count; "
          "the hook itself calls notificationsApi.list(), so the feed reaches GET "
          "/notifications; and neither mockNotifications nor useNotificationStore appears in "
          f"the CODE of any of the {scanned} source files (comments excluded). The assertion "
          "table was proved able to reject F-0043 - and able to accept a documented fix - "
          "before any of that was believed.")
    print("NOT CHECKED: that the popover RENDERS what it iterates (no test mounts brand-layout; "
          "this gate reads structure, not a render); the demo-mode branch inside the hook "
          "(isApiLive() === false still serves MOCK_NOTIFICATIONS, by design); the backend "
          "GET /notifications response itself; the creator-side bell; whether markRead / "
          "markAllRead reach the server; any other file that renders notifications.")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv[1:]))
    except CodeUnavailable as exc:
        print(f"- {exc} - unavailable")
        sys.exit(2)
