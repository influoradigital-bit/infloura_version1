"""dead-metric scanner.

The class: a metric is DECLARED and READ but never WRITTEN, so it holds its default forever while
a surface presents it as measurement. Every row is a number a human would act on:

  F-0490  UsageMetric.TRACKED_CREATOR is read by BillingController and incremented nowhere, so any
          plan limit resting on it is meaningless.
  F-0506  avgEngagementRate / avgReachPerPost / avgImpressionsPerPost on creator_metrics are
          written by nothing, so any surface reading them shows a permanent default.
  F-0525  the support stats avgResponseTime is a hardcoded 0.0 - no first-reply column exists to
          compute it from - and admins read it as a real response time.

RULE 1 (enum meters): every UsageMetric constant must appear in at least one WRITE call, not only
in reads. incrementUsage(...) is a write; getUsageForCurrentPeriod(...) is not. A meter nobody
increments is always zero, and zero is indistinguishable from "nothing happened".

RULE 2 (entity metric fields): a field named avg*/total*/count*/sum* on a domain entity must be
assigned somewhere outside its own class and outside tests. A field only the entity itself touches
can never hold anything but its default.

Both rules ignore comments, so prose ABOUT a dead metric never counts as a writer - a mistake made
three separate times in this session's other scanners.
"""
import re
import sys
import pathlib

MAIN = pathlib.Path("influora-api/src/main/java")
ENUM = MAIN / "com/influora/domain/enums/UsageMetric.java"
ENTITIES = MAIN / "com/influora/domain/entity"

BLOCK = re.compile(r"/\*.*?\*/", re.S)
LINE_C = re.compile(r"//[^\n]*")
WRITE_CALL = re.compile(r"\b(increment\w*|record\w*|track\w*|add\w*|report\w*)\s*\(", re.I)
METRIC_FIELD = re.compile(r"\bprivate\s+(?:\w+)\s+((?:avg|total|count|sum)[A-Z]\w*)\s*[;=]")


def strip_comments(text):
    return LINE_C.sub("", BLOCK.sub("", text))


def java_files():
    return [p for p in MAIN.rglob("*.java")]


def main():
    if not MAIN.is_dir():
        print("- influora-api main sources missing - unavailable")
        return 2
    files = java_files()
    if not files:
        print("- ZERO java files found, which cannot be right - broken instrument, not a pass")
        return 2

    sources = {}
    for p in files:
        try:
            sources[p] = strip_comments(p.read_text(encoding="utf-8", errors="replace"))
        except Exception:
            continue

    dead = []

    # --- RULE 1 ---------------------------------------------------------------------------------
    if ENUM.is_file():
        enum_body = strip_comments(ENUM.read_text(encoding="utf-8", errors="replace"))
        constants = re.findall(r"^\s{4}([A-Z][A-Z0-9_]+)\s*,", enum_body, re.M)
        if not constants:
            print("- parsed ZERO UsageMetric constants - broken instrument, not a pass")
            return 2
        print(f"- UsageMetric declares {len(constants)} meter(s): {', '.join(constants)}")
        for const in constants:
            written = False
            for path, code in sources.items():
                if path == ENUM:
                    continue
                for m in re.finditer(r"UsageMetric\." + const + r"\b", code):
                    # Walk back to the opening of the enclosing call; a write passes the meter to
                    # an increment/record-shaped method.
                    head = code[max(0, m.start() - 160):m.start()]
                    if WRITE_CALL.search(head):
                        written = True
                        break
                if written:
                    break
            if not written:
                dead.append(("UsageMetric." + const, "read but never incremented"))
    else:
        print("- UsageMetric.java absent - RULE 1 NOT CHECKED")

    # --- RULE 2 ---------------------------------------------------------------------------------
    if ENTITIES.is_dir():
        checked = 0
        for entity in sorted(ENTITIES.glob("*.java")):
            body = sources.get(entity)
            if body is None:
                continue
            for field in METRIC_FIELD.findall(body):
                checked += 1
                setter = "set" + field[0].upper() + field[1:]
                # A field can also be written by a DOMAIN METHOD rather than a setter or a builder:
                # CreatorProfile.totalFollowers is assigned inside applyAggregatedStats(), which
                # PlatformStatsAggregationJob calls. The first version of this rule looked only for
                # setX( and .x( and reported that live field as dead. A false positive is fatal to a
                # gate like this - one bogus row and the team skims the whole list.
                mutators = set()
                for mm in re.finditer(
                    r"(?:public|protected)\s+[\w<>\[\], ]+\s+(\w+)\s*\([^)]*\)\s*\{", body
                ):
                    depth, i = 1, mm.end()
                    while i < len(body) and depth:
                        if body[i] == "{":
                            depth += 1
                        elif body[i] == "}":
                            depth -= 1
                        i += 1
                    if re.search(r"(?:this\.)?" + field + r"\s*=[^=]", body[mm.end():i]):
                        mutators.add(mm.group(1))

                assigned = False
                for path, code in sources.items():
                    if path == entity:
                        continue
                    if re.search(r"\b" + setter + r"\s*\(", code) or re.search(
                        r"\.\s*" + field + r"\s*\(", code
                    ):
                        assigned = True
                        break
                    if mutators and any(
                        re.search(r"\.\s*" + mu + r"\s*\(", code) for mu in mutators
                    ):
                        assigned = True
                        break
                if not assigned:
                    dead.append((f"{entity.stem}.{field}", "declared but assigned nowhere outside its own class"))
        print(f"- checked {checked} entity metric field(s)")
    else:
        print("- entity package absent - RULE 2 NOT CHECKED")

    if dead:
        for name, why in dead:
            print(f"    DEAD METRIC: {name} - {why}")
        print(f"VERDICT: broken - {len(dead)} metric(s) can never hold anything but their default,")
        print("         while a surface presents them as measurement. A permanent zero is not a")
        print("         reading; it is the absence of one wearing a number's clothes.")
        return 1

    print("- every declared metric has a writer")
    return 0


if __name__ == "__main__":
    sys.exit(main())
