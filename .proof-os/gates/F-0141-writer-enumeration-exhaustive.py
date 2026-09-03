#!/usr/bin/env python3
"""
proof-os gate for F-0141 (incomplete-doc-correction).

RECORD
  where:     influora-api/src/main/java/com/influora/service/billing/SubscriptionService.java:45-66
  symptom:   BL-5's fix replaced a false 'webhook-only' javadoc with a new enumeration of
             non-webhook Subscription writers that itself omits one.
  missed_by: "Kavya's QA pass verified the 3 named paths were correctly scoped but didn't check
             the enumeration was exhaustive."

That missed_by line IS this gate's specification. The QA pass checked each *named* bullet.
Nobody checked the *unnamed* remainder. So this gate never reads the bullets to decide what the
writers are — it derives the writer set mechanically from the code and then asks whether the
prose names all of them.

DERIVATION (the side the doc cannot influence)
  1. Find every field/parameter typed `SubscriptionRepository` in the backend main sources and
     learn its identifier.
  2. Find every mutating call on those identifiers — save / saveAndFlush / saveAll / delete /
     deleteAll / deleteById — in COMMENT-STRIPPED source.
  3. Attribute each call site to its enclosing method by brace depth.
  That is the true set of Subscription-row writers. Partition it into the verified-webhook writer
  (`applySubscriptionWebhookUpdate`) and everything else.

ASSERTION (the side the doc controls)
  Every non-webhook writer must be NAMED by a <li> bullet of SubscriptionService's class javadoc,
  and the enumeration's count word must equal the bullet count. A writer that exists in code and
  appears in no bullet is the F-0141 defect, whichever class it lives in — the paragraph exists
  precisely to bound the sentence "a PRO row is created/updated only from a verified webhook",
  and it closes by asserting "only applySubscriptionWebhookUpdate can move a workspace onto paid
  Pro entitlement". Those are claims about the system, so the enumeration that carries them has
  to be exhaustive about the system, not about one file.

WHY COMMENTS ARE STRIPPED (F-0266 discipline)
  The javadoc under test literally contains the token `subscriptionRepository.save(...)`, and
  SubscriptionDunningJob has a `//` comment containing `subscriptionRepository.save`. A byte-level
  grep counts those as call sites and both over-counts and mis-attributes. Stripping is not
  cosmetic here; without it this gate is wrong in both directions.

Exit: 0 proved (enumeration exhaustive) · 1 broken (a writer is unnamed / count disagrees)
      2 unavailable (sources unreadable, attribution impossible)
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent
SRC = ROOT / "influora-api" / "src" / "main" / "java" / "com" / "influora"
TARGET = SRC / "service" / "billing" / "SubscriptionService.java"
WEBHOOK_METHOD = "applySubscriptionWebhookUpdate"
WRITE_OPS = ("save", "saveAndFlush", "saveAll", "delete", "deleteAll", "deleteById")

NUMBER_WORDS = {
    "zero": 0, "one": 1, "two": 2, "three": 3, "four": 4, "five": 5, "six": 6,
    "seven": 7, "eight": 8, "nine": 9, "ten": 10, "eleven": 11, "twelve": 12,
}

# A member method declaration sitting directly in a class body. Deliberately conservative:
# an `=` anywhere on the line disqualifies it (that is a field initialiser such as
# `private static final Logger log = LoggerFactory.getLogger(...)`), and a leading modifier is
# required so a bare call statement can never be mistaken for a declaration.
METHOD_DECL = re.compile(
    r"^\s+(?:(?:public|private|protected|static|final|synchronized|abstract|default|native)\s+)+"
    r"[\w.$<>\[\],\s?]+?\s(\w+)\s*\("
)


def unavailable(msg: str) -> None:
    print(f"- {msg}")
    print("VERDICT: unavailable -- this gate could not run, so it proves nothing either way")
    print("NOT CHECKED: everything below; treat this as no signal, not as a pass")
    sys.exit(2)


def strip_java(text: str) -> str:
    """Blank out //, /* */ and string/char literals, preserving line structure and offsets."""
    out = []
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if c == "/" and nxt == "/":
            while i < n and text[i] != "\n":
                out.append(" ")
                i += 1
        elif c == "/" and nxt == "*":
            while i < n and not (text[i] == "*" and i + 1 < n and text[i + 1] == "/"):
                out.append("\n" if text[i] == "\n" else " ")
                i += 1
            out.append("  ")
            i = min(i + 2, n)
        elif c in ('"', "'"):
            quote = c
            out.append(" ")
            i += 1
            while i < n and text[i] != quote:
                if text[i] == "\\":
                    out.append(" ")
                    i += 1
                    if i < n:
                        out.append("\n" if text[i] == "\n" else " ")
                        i += 1
                    continue
                out.append("\n" if text[i] == "\n" else " ")
                i += 1
            out.append(" ")
            i = min(i + 1, n)
        else:
            out.append(c)
            i += 1
    return "".join(out)


def depth_before_each_line(lines: list[str]) -> list[int]:
    depths, d = [], 0
    for line in lines:
        depths.append(d)
        d += line.count("{") - line.count("}")
    return depths


def writers_in(path: Path) -> tuple[list[tuple[str, str, int]], list[int]]:
    """-> ([(classSimpleName, methodName, lineNo)], [unattributed line numbers])"""
    raw = path.read_text(encoding="utf-8", errors="replace")
    code = strip_java(raw)
    idents = set(re.findall(r"\bSubscriptionRepository\s+(\w+)\s*[;,)=]", code))
    if not idents:
        return [], []
    lines = code.splitlines()
    depths = depth_before_each_line(lines)
    cls = path.stem
    call = re.compile(
        r"\b(?:" + "|".join(map(re.escape, sorted(idents))) + r")\s*\.\s*(?:"
        + "|".join(WRITE_OPS) + r")\s*\("
    )
    found, orphans = [], []
    for idx, line in enumerate(lines):
        if not call.search(line):
            continue
        method = None
        for back in range(idx, -1, -1):
            if depths[back] != 1:
                continue
            m = METHOD_DECL.match(lines[back])
            if m and "=" not in lines[back].split("(")[0]:
                method = m.group(1)
                break
        if method is None:
            orphans.append(idx + 1)
        else:
            found.append((cls, method, idx + 1))
    return found, orphans


def class_javadoc(path: Path) -> str | None:
    raw = path.read_text(encoding="utf-8", errors="replace")
    anchor = raw.find("public class SubscriptionService")
    if anchor < 0:
        return None
    close = raw.rfind("*/", 0, anchor)
    if close < 0:
        return None
    open_ = raw.rfind("/**", 0, close)
    if open_ < 0:
        return None
    block = raw[open_ + 3:close]
    return "\n".join(re.sub(r"^\s*\*\s?", "", ln) for ln in block.splitlines())


def bullets_of(doc: str) -> list[str]:
    m = re.search(r"<ul>(.*?)</ul>", doc, re.S)
    if not m:
        return []
    body = m.group(1)
    parts = re.split(r"<li>", body)[1:]
    return [re.sub(r"</li>", " ", p).strip() for p in parts]


def main() -> None:
    if not SRC.is_dir():
        unavailable(f"{SRC} missing -- backend sources not present")
    if not TARGET.is_file():
        unavailable(f"{TARGET.relative_to(ROOT)} missing")

    print("- deriving the real Subscription-writer set from comment-stripped code")
    derived: list[tuple[str, str, int, Path]] = []
    orphaned: list[str] = []
    try:
        java_files = sorted(SRC.rglob("*.java"))
    except OSError as exc:
        unavailable(f"cannot walk {SRC}: {exc}")
    if not java_files:
        unavailable(f"no .java sources under {SRC}")

    for f in java_files:
        try:
            hits, orphans = writers_in(f)
        except OSError as exc:
            unavailable(f"cannot read {f}: {exc}")
        for cls, method, line in hits:
            derived.append((cls, method, line, f))
        for line in orphans:
            orphaned.append(f"{f.relative_to(ROOT).as_posix()}:{line}")

    if orphaned:
        unavailable(
            "a repository write could not be attributed to an enclosing method -- "
            + ", ".join(orphaned)
        )
    if not derived:
        unavailable("no SubscriptionRepository write call sites found at all -- parser is wrong")

    webhook = sorted({(c, m) for c, m, _, _ in derived if m == WEBHOOK_METHOD})
    nonwebhook = sorted({(c, m) for c, m, _, _ in derived if m != WEBHOOK_METHOD})
    for cls, method in nonwebhook:
        sites = [str(l) for c, m, l, _ in derived if (c, m) == (cls, method)]
        print(f"    non-webhook writer: {cls}#{method} (line{'s' if len(sites) > 1 else ''} {', '.join(sites)})")
    for cls, method in webhook:
        sites = [str(l) for c, m, l, _ in derived if (c, m) == (cls, method)]
        print(f"    webhook writer:     {cls}#{method} (lines {', '.join(sites)})")
    print(f"  derived: {len(nonwebhook)} non-webhook writer(s), {len(webhook)} webhook writer(s)")

    if not webhook:
        unavailable(f"{WEBHOOK_METHOD} was not detected as a writer -- the derivation is unsound")

    doc = class_javadoc(TARGET)
    if doc is None:
        unavailable("could not locate SubscriptionService's class javadoc")
    bullets = bullets_of(doc)
    if not bullets:
        unavailable("class javadoc has no <ul>/<li> writer enumeration to check")
    print(f"- class javadoc enumerates {len(bullets)} writer bullet(s)")

    uncovered = []
    for cls, method in nonwebhook:
        name_re = re.compile(r"\b" + re.escape(method) + r"\b")
        hit = False
        for b in bullets:
            if not name_re.search(b):
                continue
            if cls != TARGET.stem and cls not in b:
                continue
            hit = True
            break
        if not hit:
            uncovered.append(f"{cls}#{method}")

    broken = []
    if uncovered:
        for u in uncovered:
            print(f"  UNNAMED: {u} writes a Subscription row and appears in no bullet")
        broken.append(
            "the enumeration omits " + ", ".join(uncovered)
        )
    else:
        print("  every derived non-webhook writer is named by a bullet")

    count_checked = True
    m = re.search(
        r"has\s+([A-Za-z]+|\d+)\s+other\s+\{@code\s+Subscription\}\s+writers", doc, re.S
    )
    if not m:
        count_checked = False
        print("  (count sentence not found in its expected wording -- count check skipped)")
    else:
        word = m.group(1)
        claimed = int(word) if word.isdigit() else NUMBER_WORDS.get(word.lower())
        if claimed is None:
            count_checked = False
            print(f"  (count word {word!r} unrecognised -- count check skipped)")
        else:
            print(f"- enumeration claims {word.upper()} ({claimed}) non-webhook writers")
            if claimed != len(bullets):
                broken.append(
                    f"count word says {claimed} but the list has {len(bullets)} bullets"
                )
            if claimed != len(nonwebhook):
                broken.append(
                    f"count word says {claimed} but the code has {len(nonwebhook)} non-webhook writers"
                )

    print("NOT CHECKED: whether each bullet's prose about reachability, scoping and payment risk is"
          " true; writers that mutate a managed Subscription entity through JPA dirty-checking with"
          " no repository call at all; writers reaching the table through raw SQL, a native query,"
          " a Flyway migration or another repository interface; and every prose claim in the class"
          " javadoc outside the <ul>."
          + ("" if count_checked else " Count sentence not parseable, so the count word was not checked."))

    if broken:
        for b in broken:
            print(f"  - {b}")
        print("VERDICT: broken -- the non-webhook Subscription-writer enumeration in"
              " SubscriptionService's class javadoc is not exhaustive (F-0141): "
              + "; ".join(broken))
        sys.exit(1)

    print("VERDICT: proved -- every Subscription writer the code contains is named by the"
          " enumeration, and the count word agrees with both the bullet list and the code")
    sys.exit(0)


if __name__ == "__main__":
    main()
