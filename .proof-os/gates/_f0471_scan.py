"""F-0471 scanner — every deliverable-approval call site must branch on the release outcome.

F-0406 fixed the backend: approve() returns ReviewResponse(status, paymentReleased,
paymentHeldReason) and a held release no longer masquerades as success. F-0471 is the residual —
the FRONTEND had four approval surfaces and two of them threw the outcome away, so a brand
approving from those saw plain success while the creator was not paid.

This is written as a repo-wide rule rather than two component tests because that is precisely what
the finding's own missed_by asked for: "a test asserting EVERY approval call site surfaces a held
release to the user". A per-component test proves the component you remembered to write it for; a
fifth surface added tomorrow would sail past it, which is how F-0471 followed F-0406.

Rule: within WINDOW lines after a call to `.approve(` on the deliverables client, the code must
mention `paymentReleased`. Comments are stripped first, so a note ABOUT the outcome never counts as
handling it.
"""
import re
import sys
import pathlib

SRC = pathlib.Path("src")
WINDOW = 30

BLOCK = re.compile(r"/\*.*?\*/", re.S)
LINE_C = re.compile(r"//[^\n]*")
# `api.deliverables.approve(` / `deliverablesApi.approve(` — not admin or dispute approvals.
APPROVE = re.compile(r"\bdeliverables(?:Api)?\s*\.\s*approve\s*\(")


def strip_comments(text):
    return LINE_C.sub("", BLOCK.sub("", text))


def main():
    if not SRC.is_dir():
        print("- src/ missing - unavailable")
        return 2

    files = [
        p
        for p in SRC.rglob("*.ts*")
        if p.is_file()
        and "__tests__" not in p.as_posix()
        and ".test." not in p.name
        and ".spec." not in p.name
    ]
    if not files:
        print("- ZERO source files scanned, which cannot be right - broken instrument, not a pass")
        return 2

    call_sites, unhandled = 0, []
    for path in files:
        try:
            code = strip_comments(path.read_text(encoding="utf-8", errors="replace"))
        except Exception:
            continue
        lines = code.split("\n")
        for i, line in enumerate(lines):
            if not APPROVE.search(line):
                continue
            call_sites += 1
            window = "\n".join(lines[i : i + WINDOW])
            if "paymentReleased" not in window:
                unhandled.append((path.as_posix(), i + 1, line.strip()[:90]))

    if call_sites == 0:
        print("- found ZERO approval call sites; the client method was renamed and this rule now")
        print("  guards nothing - broken instrument, not a pass")
        return 2

    print(f"- {call_sites} deliverable-approval call site(s); {len(unhandled)} ignore the outcome")

    if unhandled:
        for path, lineno, text in unhandled:
            print(f"    UNHANDLED: {path}:{lineno}  {text}")
        print("VERDICT: broken - an approval call site discards the release outcome. Approving is")
        print("         what pays the creator, and the server can hold the release WITHOUT")
        print("         throwing, so this surface tells a brand the money moved when it did not.")
        return 1

    print("- every approval call site branches on paymentReleased")
    return 0


if __name__ == "__main__":
    sys.exit(main())
