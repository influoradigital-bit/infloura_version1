#!/usr/bin/env python3
"""gates/F-0270-no-dead-controls.py

origin failures: F-0270, F-0274, F-0276 (class: dead-control) — recurred x3, blocked by
scripts/promote.py --recurrence.

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

ALLOW = ("onclick", "aschild", 'type="submit"', "type='submit'", "disabled",
         "form=", "onmousedown", "onpointerdown", "onkeydown")

NOT_CHECKED = (
    "NOT CHECKED: whether an onClick that EXISTS actually does anything (a no-op handler "
    "passes), controls that are not <Button> (raw <button>, DropdownMenuItem, Card onClick), "
    "whether `asChild` children are themselves wired, and runtime behaviour"
)


def opening_tags(src: str):
    """Yield (line_no, tag_text) for each <Button ...> opening tag, spanning lines."""
    for m in re.finditer(r"<Button\b", src):
        i, depth, j = m.start(), 0, m.start()
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
