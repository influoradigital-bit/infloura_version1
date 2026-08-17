#!/usr/bin/env python3
"""gates/F-0270-no-dead-controls.py

origin failures: F-0270, F-0274, F-0276 (class: dead-control) — recurred x3, blocked by
scripts/promote.py --recurrence.
Also closes F-0289 (dead-control-repair): the live instances this gate first reported are
now fixed, so it exits 0 rather than merely detecting them.
Also closes F-0310 (gate-accepts-noop-handler): this gate used to accept
`onClick={e => e.preventDefault()}` as wiring and the bare substring "disabled" — which
`aria-disabled` contains — so a still-clickable control read as honestly disabled. It now
requires a REAL disabled attribute and rejects the known no-op handler shapes.

A control that renders enabled and does nothing is worse than a missing one: the user
believes the product supports the action, clicks, and gets silence. This class produced a
signature button, both chat attach buttons, "Continue Chat", "Download Approved Version"
and "View Contract".

RULE: every <Button ...> opening tag must carry ONE of
  onClick | asChild | type="submit" | disabled | form= | onMouseDown | onPointerDown
`asChild` delegates behaviour to the child (a Link, an <a>, a DialogTrigger), and
`disabled` is the sanctioned way to render an inert control — brand-wallet.tsx already
models disabled + a tooltip reason, so the correct pattern is established in-tree.

EXITS: 0 proved · 1 real findings · 2 cannot run · 64 usage.
Usage: F-0270-no-dead-controls.py [root]   (default: src)
"""
import os
import re
import sys

ALLOW = ("onclick", "aschild", 'type="submit"', "type='submit'",
         "form=", "onmousedown", "onpointerdown", "onkeydown",
         # A wrapper that spreads its props forwards whatever handler the caller passed
         # (MagneticButton, the ui/ primitives). Flagging these produced 4 of the first
         # run's 39 hits and none was a dead control.
         "{...props}", "{...rest}", "{...buttonprops}")

# A <Button> nested directly inside a Radix `asChild` trigger IS wired: the trigger clones
# the child and injects its own handler, so the child carries no onClick by design. The
# first version of this gate inspected only the Button's own tag and therefore reported 21
# correctly-wired controls out of 39 — a false-positive rate that would have trained every
# reader to ignore it. See F-0289.
TRIGGER_ASCHILD = re.compile(r"asChild\s*>\s*$")

# A handler that does nothing is the same lie as no handler — and the first version of this
# gate accepted it, because it only asked whether the substring "onclick" appeared. That is
# how `onClick={(e) => e.preventDefault()}` on a paperclip button passed while the control
# was still, in every sense the user experiences, dead. The declared blind spot in this
# gate's own verdict turned out to be live in the same run that declared it.
# A REAL `disabled` attribute — not `aria-disabled`. These are not interchangeable: `disabled`
# makes the browser refuse activation (mouse and keyboard); `aria-disabled` only announces the
# state to assistive tech and leaves the control fully clickable. The first version of this gate
# accepted the bare substring "disabled", so `aria-disabled="true"` alone satisfied it — which is
# how a paperclip that was still clickable, and relied on a no-op handler to swallow the click,
# read as an honestly-disabled control.
REAL_DISABLED = re.compile(r"(?<!-)\bdisabled\b")

NOOP_HANDLER = re.compile(
    r"on(?:Click|MouseDown|PointerDown|KeyDown)\s*=\s*\{\s*\(?\s*[\w]*\s*\)?\s*=>\s*"
    r"(?:\{\s*\}|(?:e|ev|event)\.(?:preventDefault|stopPropagation)\s*\(\s*\)\s*|void\s+0|undefined|null)\s*\}",
    re.IGNORECASE,
)

NOT_CHECKED = (
    "NOT CHECKED: whether a handler that is not one of the recognised no-op shapes actually "
    "does anything — a body that calls a function which itself returns early still passes; "
    "controls that are not <Button> (raw <button>, DropdownMenuItem, Card onClick); whether "
    "`asChild` children are themselves wired; whether a disabled control's stated reason is "
    "true or its tooltip reachable; and runtime behaviour"
)


def opening_tags(src: str):
    """Yield (line_no, tag_text) for each <Button ...> opening tag, spanning lines.

    A Button whose immediately-preceding markup is an `asChild` trigger is skipped: the
    trigger injects the handler, so the child is wired even with a bare tag.
    """
    for m in re.finditer(r"<Button\b", src):
        i, depth, j = m.start(), 0, m.start()
        if TRIGGER_ASCHILD.search(src[:i]):
            continue
        while j < len(src):
            c = src[j]
            if c == "{":
                depth += 1
            elif c == "}":
                depth -= 1
            elif c == ">" and depth == 0:
                break
            j += 1
        yield src.count("\n", 0, i) + 1, src[i:j + 1]


def main() -> int:
    args = sys.argv[1:]
    if len(args) > 1:
        print(f"· {len(args)} arguments given; this gate takes at most 1")
        print("usage: F-0270-no-dead-controls.py [root]")
        print(NOT_CHECKED)
        return 64
    root = args[0] if args else "src"
    if not os.path.isdir(root):
        print(f"· not a directory: {root} — unavailable")
        print(NOT_CHECKED)
        return 2

    findings, scanned = [], 0
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames
                       if d not in ("node_modules", "dist", "build", ".next", "__tests__")]
        for fn in filenames:
            if not fn.endswith((".tsx", ".jsx")) or ".test." in fn:
                continue
            path = os.path.join(dirpath, fn)
            try:
                src = open(path, encoding="utf-8").read()
            except OSError:
                continue
            scanned += 1
            for line_no, tag in opening_tags(src):
                low = tag.lower()
                # A real `disabled` is an inert state and always acceptable.
                if REAL_DISABLED.search(tag):
                    continue
                # Otherwise a handler that provably does nothing is not wiring.
                if NOOP_HANDLER.search(tag):
                    findings.append(f"{path.replace(os.sep, '/')}:{line_no} (no-op handler)")
                    continue
                if not any(a in low for a in ALLOW):
                    findings.append(f"{path.replace(os.sep, '/')}:{line_no}")

    if findings:
        print(f"· {len(findings)} enabled <Button> with no handler and no disabled state "
              f"(scanned {scanned} files under {root}):")
        for f in findings[:25]:
            print(f"    {f}")
        if len(findings) > 25:
            print(f"    … and {len(findings) - 25} more")
        print("VERDICT: broken (dead-control instances present)")
        print(NOT_CHECKED)
        return 1

    print(f"VERDICT: aligned (proved) — {scanned} files scanned under {root}")
    print(NOT_CHECKED)
    return 0


if __name__ == "__main__":
    sys.exit(main())
