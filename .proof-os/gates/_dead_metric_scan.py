"""dead-metric scanner.

The class: a metric is DECLARED and READ but never WRITTEN, so it holds its default forever while
a surface presents it as measurement. Every row is a number a human would act on:

  F-0490  UsageMetric.TRACKED_CREATOR is read by BillingController and incremented nowhere, so any
          plan limit resting on it is meaningless.
  F-0506  avgEngagementRate / avgReachPerPost / avgImpressionsPerPost on creator_metrics are
          written by nothing, so any surface reading them shows a permanent default.
  F-0525  the support stats avgResponseTime is a hardcoded 0.0 - no first-reply column exists to
          compute it from - and admins read it as a real response time.
  F-0688  UsageMetric.EXPORT was the same class as F-0490 (a meter with no writer) but ALSO the
          reason RULE 1's own enum parser was blind: it required a trailing comma, so the LAST
          declared constant was invisible and a dead meter added at the end passed silently.
  F-0689  MediaMetric.avgWatchTimeSeconds - same shape as F-0506, on a different entity.

RULE 1 (enum meters): every UsageMetric constant must appear in at least one WRITE call, not only
in reads. incrementUsage(...) is a write; getUsageForCurrentPeriod(...) is not. A meter nobody
increments is always zero, and zero is indistinguishable from "nothing happened".

RULE 1 also follows the constant through ONE local-variable hop (T-DEADMETRIC-0913, closing
round): `UsageMetric.CREATOR_ANALYTICS_VIEW` is never itself a write-call argument — it is
returned from `EntitlementService.meteredMetricFor`, bound to a local (`metric`), and THAT local
is what reaches `incrementUsage`/`recordCreatorLookup`. The original literal-proximity check is
blind to this (the literal and the write are in different methods), so it read a live 6th meter
as dead and that false claim was written into `_dead_metric_baseline.json` as if verified.
`written_via_local` closes exactly this one hop — a direct local assignment, or a local bound to
a method's return value — without widening WRITE_CALL itself, so a constant that is merely READ
into a local (never handed to a write-shaped call) still reads as dead. See NOT CHECKED in the
gate script for how many hops this covers.

The enum body is parsed by locating the `enum X { ... }` block itself (brace-depth matched, not a
per-line regex) and taking every ALL_CAPS identifier up to the first `;` (the constant list ends
there if the enum has fields/methods after it). This does not require a trailing comma, so the
LAST constant is visible - the exact defect F-0688 was filed for: "F-0490 was filed against
TRACKED_CREATOR specifically, as an instance. Nobody enumerated the enum and asked the same
question of every constant." A parser that still needs a trailing comma cannot ask that question
of the last constant, which is exactly where a newly-appended dead meter lands.

A SELF-CHECK cross-validates the count: the same block is independently split on commas, and if
that count disagrees with the identifier count, the parse is treated as PARTIAL and the gate
refuses to pass (returns 2) rather than silently under-reporting - a partial parse that still
prints a plausible-looking "declares N meter(s)" line is worse than an obvious zero, because zero
was already caught below.

RULE 2 (entity metric fields): a field named avg*/total*/count*/sum* on a domain entity must be
assigned somewhere outside its own class and outside tests. A field only the entity itself touches
can never hold anything but its default.

RULE 3 (literal stats returned as measurements): a local variable named avg*/total*/count*/sum*
that is assigned a BARE NUMERIC LITERAL (not a call, not a ternary over real data, not a loop
accumulator) and then flows - unmodified - into a `new SomethingDto(...)` / `...Stats(...)` /
`...Response(...)` / `...Summary(...)` constructor call is a hand-written F-0525 shape: a stat
surface reporting a number that was never computed. A variable that is reassigned anywhere else
(`+=`, plain `=`, `++`, a loop accumulator, a conditional branch) is real computation, not this
defect, and is excluded - the false-positive check below confirms this against every current
avg/total/count/sum local in the tree (`totalDiscoverable += ...`, `totalSlots += ...`,
`totalReach = mostRecent...`, `avgRating = BigDecimal...` all get excluded; only
AdminSupportService's `avgResponseTime` - assigned once, never touched again, handed straight to
`SupportStatsDto` - survives). RULE 3 only looks at production sources (the same MAIN tree RULE 1
and RULE 2 use), never tests.

All three rules ignore comments, so prose ABOUT a dead metric never counts as a writer - a mistake
made three separate times in this session's other scanners.
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
LITERAL_STAT_VAR = re.compile(
    r"\b(?:double|float|int|long|Integer|Long|Double|BigDecimal)\s+"
    # T-DEADMETRIC-REPAIR-0915 (fix round, RULE 3 gate slip): the numeric literal may carry a
    # Java type suffix (0d/0D/0f/0F/0l/0L, or the same on a decimal like 0.0d) — that suffix used
    # to sit directly before the `;` this pattern required to immediately follow the digits, so
    # `double avgResponseTime = 0d;` (or 0L/0f/0.0d/...) matched -\d+(?:\.\d+)?\s*; only up to the
    # digits, left the trailing letter unconsumed before the expected `;`, and the whole pattern
    # failed to match — the literal evaded RULE 3 entirely. The optional [dDfFlL]? below consumes
    # that suffix so the `;` check still lines up.
    r"((?:avg|total|count|sum)[A-Z]\w*)\s*=\s*-?\d+(?:\.\d+)?[dDfFlL]?\s*;"
)
STAT_CTOR = re.compile(r"\bnew\s+\w*(?:Dto|Stats|Response|Summary)\w*\s*\(")
REASSIGN = re.compile(r"\s*(?:\+\+|--|[+\-*/]=|=(?!=))")

# RULE 1, single-hop indirection: a method signature shaped like an access modifier followed by
# a name and a parameter list - used to find the method ENCLOSING a `return UsageMetric.CONST;`
# so its call sites can be located. Deliberately narrow (public/private/protected only, no
# attempt at generics-in-return-type edge cases) - this is a heuristic "nearest signature above
# this position" lookup, not a parser, and is scoped to ONE hop by design (see NOT CHECKED below).
METHOD_SIG = re.compile(r"(?:public|private|protected)[\w<>\[\],\s]*?\b(\w+)\s*\([^)]*\)\s*\{")


def strip_comments(text):
    return LINE_C.sub("", BLOCK.sub("", text))


def java_files():
    return [p for p in MAIN.rglob("*.java")]


def parse_enum_constants(enum_body):
    """Extract every ALL_CAPS constant identifier from a (comment-stripped) `enum X { ... }`
    declaration, independent of trailing-comma formatting. Returns (constants, error) - exactly
    one of which is falsy. This is factored out so a gate wrapper can self-test it directly
    against a known-bad sample (a last constant with no trailing comma) without re-implementing
    the parse, the same way doc-stale-doc-claim.sh self-tests its own scanner's regexes.
    """
    enum_open = re.search(r"\benum\s+\w+\s*\{", enum_body)
    if not enum_open:
        return None, "could not locate the enum block"
    depth, i = 1, enum_open.end()
    while i < len(enum_body) and depth:
        if enum_body[i] == "{":
            depth += 1
        elif enum_body[i] == "}":
            depth -= 1
        i += 1
    if depth:
        return None, "enum block never closes"
    enum_block = enum_body[enum_open.end() : i - 1]
    # The constant list ends at the first ';' if the enum declares fields/methods after its
    # constants (a plain constant-only enum has none - the split is then a no-op).
    const_section = enum_block.split(";", 1)[0]
    constants = re.findall(r"\b([A-Z][A-Z0-9_]*)\b", const_section)
    if not constants:
        return None, "parsed ZERO constants"
    # SELF-CHECK: an independent comma-split count must agree with the identifier count. A
    # PARTIAL parse must read as broken, not as a smaller-but-plausible number.
    naive_items = [seg.strip() for seg in const_section.split(",") if seg.strip()]
    if len(naive_items) != len(constants):
        return None, (
            f"parsed {len(constants)} constant identifier(s) but comma-split found "
            f"{len(naive_items)} segment(s) - PARTIAL parse"
        )
    return constants, None


def local_reaches_write(local, code):
    """True if `local` (a variable name) appears inside the argument list of a write-shaped
    call anywhere in `code`. Used to finish the single hop: once a constant is known to be bound
    to a local, this checks whether that SAME local is the thing actually handed to the write."""
    for wm in WRITE_CALL.finditer(code):
        depth, i = 1, wm.end()
        while i < len(code) and depth:
            if code[i] == "(":
                depth += 1
            elif code[i] == ")":
                depth -= 1
            i += 1
        args = code[wm.end() : i - 1]
        if re.search(r"\b" + re.escape(local) + r"\b", args):
            return True
    return False


def enclosing_method_name(code, pos):
    """The name of the method whose signature most recently opened before `pos` - a "nearest
    preceding signature" heuristic, not brace-depth tracking, so it is ONE hop only: it does not
    verify `pos` is still inside that method's body (a second, more deeply nested method between
    the signature and `pos` would fool it). Good enough for the shape this rule targets (a return
    statement a few lines below its own method's signature); see the gate's NOT CHECKED note."""
    sigs = list(METHOD_SIG.finditer(code, 0, pos))
    return sigs[-1].group(1) if sigs else None


def written_via_local(const, sources):
    """RULE 1 extension: a constant reaching a write through exactly ONE local-variable hop still
    counts as written. Two shapes, both requiring the local to then be handed to a write-shaped
    call (checked by local_reaches_write):

      Case A - direct assignment:  UsageMetric x = ...CONST...;
      Case B - method-return:      return ...CONST...;  (in method M)
                                    ...
                                    UsageMetric x = M(...);   <- anywhere in the tree

    This deliberately does NOT chase a local through a SECOND hop (e.g. x passed to another
    method that itself assigns it to a further local before writing) - see the gate's NOT CHECKED
    block for the declared limit. It also does not widen WRITE_CALL itself, so a constant that
    reaches only a genuinely non-write call via a local (e.g. TRACKED_CREATOR, which is never
    assigned to a local at all) still reads as dead.
    """
    literal = "UsageMetric." + const
    for path, code in sources.items():
        if path == ENUM:
            continue
        for m in re.finditer(
            r"\bUsageMetric\s+(\w+)\s*=\s*[^;]*?" + re.escape(literal) + r"\b[^;]*;", code
        ):
            if local_reaches_write(m.group(1), code):
                return True
        for rm in re.finditer(r"\breturn\s+[^;]*?" + re.escape(literal) + r"\b[^;]*;", code):
            method_name = enclosing_method_name(code, rm.start())
            if not method_name:
                continue
            call_pattern = re.compile(
                r"\bUsageMetric\s+(\w+)\s*=\s*" + re.escape(method_name) + r"\s*\("
            )
            for path2, code2 in sources.items():
                for cm in call_pattern.finditer(code2):
                    if local_reaches_write(cm.group(1), code2):
                        return True
    return False


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
        constants, err = parse_enum_constants(enum_body)
        if err:
            print(f"- {err} - broken instrument, not a pass")
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
                written = written_via_local(const, sources)
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

    # --- RULE 3 ---------------------------------------------------------------------------------
    checked3 = 0
    for path, body in sources.items():
        if path == ENUM:
            continue
        for m in LITERAL_STAT_VAR.finditer(body):
            name = m.group(1)
            checked3 += 1
            # Exactly one assignment-shaped occurrence (the declaration itself) means the value is
            # never touched again. Two or more means it is a loop accumulator or a conditionally
            # computed value (a real measurement), not a hardcoded literal - exclude it.
            reassignments = list(re.finditer(r"\b" + name + REASSIGN.pattern, body))
            if len(reassignments) != 1:
                continue
            flowed_into = None
            for cm in STAT_CTOR.finditer(body, m.end()):
                depth, i = 1, cm.end()
                while i < len(body) and depth:
                    if body[i] == "(":
                        depth += 1
                    elif body[i] == ")":
                        depth -= 1
                    i += 1
                if depth:
                    continue
                args = body[cm.end() : i - 1]
                if re.search(r"\b" + name + r"\b", args):
                    ctor_name = re.search(r"\bnew\s+(\w+)", body[cm.start() : cm.end()])
                    flowed_into = ctor_name.group(1) if ctor_name else "a stats constructor"
                    break
            if flowed_into:
                dead.append(
                    (
                        f"{path.stem}.{name} (local)",
                        f"assigned a literal, never computed, handed straight to {flowed_into}",
                    )
                )
    print(f"- checked {checked3} literal-assigned avg/total/count/sum local(s)")

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
