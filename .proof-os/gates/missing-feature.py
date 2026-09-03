#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""gates/missing-feature.py -- detector for ledger class `missing-feature`.

RECURRENCES THIS GATE EXISTS FOR: F-0403, F-0413, F-0414.

  F-0403  Contract#setStatus(..) has ZERO non-test call sites, so ContractStatus.CANCELLED
          and .COMPLETED can never be written by any code path. A contract signed by one
          party only is frozen forever; post-contract cancellation does not exist as a
          feature. CANCELLED *does* appear in main source -- inside
          `existsByCollaborationIdAndStatusNot(.., ContractStatus.CANCELLED)`, which is a
          READ. Every grep-for-the-constant gate greens this. The question that catches it
          is "who WRITES it", not "who mentions it".
  F-0413  Contract.Builder#expirationDate(..) exists, the `expiration_date` column exists,
          and no main-source caller ever supplies it -- so the column is NULL on every row
          and nothing can expire.
  F-0414  ContractController exposes generate/list/get/sign/pdf-url and no PUT, PATCH or
          amend route, so Contract#version and the DRAFT status are decoration.

THE CLASS: a domain state or transition is declared in the model and nothing in production
can ever reach it. javac is happy. The entity compiles, the enum compiles, the unit tests
that construct the state by hand compile and pass. The product is missing the feature.

WHAT IS ASSERTED (over every @Entity in the domain entity package that holds a project
enum -- the set is DERIVED from the tree, not hand-listed):

  A. ENUM STATE REACHABILITY -- every constant of every status enum held by such an entity
     has at least one NON-TEST WRITE site in main source, that write targets the OWNING
     ENTITY (not some response DTO that happens to have a `setDisplayStatus`), and the
     method enclosing that write is TRANSITIVELY REACHABLE from a production entry point.
  B. MUTATOR REACHABILITY -- every public mutator declared on such an entity, the nested
     Builder's fluent setters included (which is where F-0413 lived), has at least one
     NON-TEST call site with a TYPE-PLAUSIBLE receiver, itself inside a method reachable
     from a production entry point.

A write or call site that exists only under src/test is reported as a finding, not as
proof: a state only a test can produce is a state the product cannot produce. A write whose
only home is a method no entry point can reach is reported the same way -- dead code is not
a feature.

WHAT COUNTS AS A WRITE (three shapes, all attributed to the owning entity):
  1. a call to a method DECLARED on an owning entity or its Builder --
     `c.setStatus(CANCELLED)`, `PortfolioEvent.builder().eventType(VIEW)` -- with a receiver
     that is not provably an instance of some other type;
  2. a call to a SINK: a method whose own parameter of that enum type is forwarded (to a
     fixpoint, so chains of any depth) into shape 1 --
     `historyService.record(id, CONTRACT_SIGNED, ..)` reaches
     `ApplicationHistoryEvent.create(..)` two hops away and is a real write;
  3. a Flyway seed: `plans` and `campaign_templates` rows exist in every environment
     because V55/V2026071415 INSERT them, so a constant named in that migration IS
     produced in production even though no Java line writes it.

NOT ASSERTED (reported, never silently dropped) -- constants of an enum that can be built
without any source token naming them: `X.valueOf(raw)`, `X.class` handed to a reflective
parser or binder, `@RequestParam X`, or a `*Request` record field; and Builder fields of an
entity whose rows are only ever created by a database seed.

PRODUCTION ENTRY POINTS (roots of the call graph built over main source):
  @*Mapping / @RequestMapping controller methods, @Scheduled jobs, @EventListener and
  @TransactionalEventListener handlers, @PostConstruct / @PreDestroy, @Bean factory
  methods, JPA @PrePersist/@PreUpdate/@PostLoad/... callbacks, message listeners
  (@KafkaListener, @RabbitListener, @JmsListener, @SqsListener, @MessageMapping),
  @ExceptionHandler / @InitBinder / @ModelAttribute, `public static void main`, every
  @Override or conventional framework callback (run/write/execute/handle/...) since those
  are dispatched reflectively, constructors of Spring stereotypes and of @Entity classes,
  and any code sitting outside a method body (field / static initialisers).

TRAPS THIS GATE IS BUILT AGAINST (each cost a false verdict when it was not handled):
  * the read/write distinction -- F-0403 hid behind a repository query argument;
  * name-only caller matching: `response.setStatus(429)` in some web filter would otherwise
    green Contract#setStatus. Receivers are resolved against declarations of the entity
    type in the same file;
  * ATTRIBUTION: `dto.setDisplayStatus(ContractStatus.CANCELLED)` on a response DTO is not
    a write of the entity's state. The invoked method must be declared on an entity that
    owns a field of that enum, and the receiver must not be provably of another type;
  * DEAD WRITERS: `private void neverCalled() { c.setStatus(CANCELLED); }` compiles, greps
    green, and ships nothing. A lexical write site is only counted when its enclosing
    method is in the entry-point closure;
  * chained builder calls: `Contract.builder().id(x).expirationDate(d)` has no simple
    receiver, so a plain `\\bvar\\.method\\(` regex reads every builder method as dead;
  * ternary arms: `s = flag ? A : B` writes B, and a naive "assignment RHS" match that
    only looks at the token right after `=` misses it;
  * implicit-this calls inside the entity (`touch();`) have no receiver at all;
  * declaration shapes: a `throws` clause between `)` and `{`, a multi-line parameter list,
    an annotated parameter with its own parentheses, and a fluent mutator returning the
    ENTITY type rather than `Builder` -- each one used to make the method invisible to the
    gate, i.e. an unreachable transition the gate never even listed as a subject;
  * comments and string literals -- including Java text blocks (\"\"\") -- must be stripped
    or a javadoc sentence naming a constant counts as a write;
  * lambda receivers: `repo.findById(id).ifPresent(p -> p.setThemeTagsJson(..))` has a
    receiver with no declared type anywhere, and reading it as unreachable is a false red.

KNOWN IMPRECISION (stated, not hidden): a state deliberately produced only into a response
-- ShipmentStatus.AWAITING_ADDRESS, which ShipmentService synthesises for a collaboration
with no shipment row -- is reported, because it is structurally identical to writing an
entity's state into a DTO and calling the feature shipped. It is printed with its own
reason line ("produced only into a DTO / response object") so it costs one read to triage.

EXIT LAW
  0   clean -- every state and mutator is reachable from production code
  1   at least one unreachable state/mutator, every one printed as file:line
  2   UNAVAILABLE, which is not a pass: no source tree, unreadable file, zero candidate
      files scanned, zero subjects examined, zero entities derived, a required entity that
      has vanished, or --only matching nothing. "I checked nothing, therefore it passed"
      is the exact failure mode this class is about.
  64  usage error

USAGE
  gates/missing-feature.py [repo_root] [--only REGEX] [--list] [--self-test]
    repo_root     defaults to the repo two levels above this script; the script is
                  runnable from any working directory.
    --only REGEX  scope to matching subjects ("Contract.CANCELLED", "Contract#setStatus")
    --list        print every subject examined, reachable ones included
    --entities    print the derived entity set and exit
    --self-test   run the built-in known-good / known-bad fixture pairs and exit
"""

import os
import re
import shutil
import sys
import tempfile

USAGE = "usage: missing-feature.py [repo_root] [--only REGEX] [--list] [--entities] [--self-test]"

MAIN_REL = os.path.join("influora-api", "src", "main", "java")
TEST_REL = os.path.join("influora-api", "src", "test", "java")
ENTITY_REL = os.path.join(MAIN_REL, "com", "influora", "domain", "entity")
ENUM_REL = os.path.join(MAIN_REL, "com", "influora", "domain", "enums")

# The entity set is DERIVED (every @Entity in the entity package holding a project enum),
# so a newly added lifecycle entity cannot be silently unexamined. This list is only the
# FLOOR: these are the lifecycle / money entities the ledger recurrences live on, and if any
# of them stops being derived the gate goes UNAVAILABLE (2) rather than quietly green.
REQUIRED_ENTITIES = [
    "Campaign",
    "Collaboration",
    "Contract",
    "Deliverable",
    "Dispute",
    "EscrowHold",
    "Invoice",
    "PaymentMilestone",
    "Payout",
    "Shipment",
]

# Methods that are never mutators, or whose reachability is not a product question.
MUTATOR_SKIP = {
    "build",
    "builder",
    "equals",
    "hashCode",
    "toString",
    "clone",
    "compareTo",
}

# --------------------------------------------------------------------------------------
# source loading


def strip_java(src):
    """Blank out comments and string literals, keeping every byte offset and newline.

    Offsets are preserved so line numbers reported to the operator are the real ones.
    Text blocks (\"\"\") are handled explicitly: a javadoc or an embedded SQL string that
    happens to name a status constant must not count as a write site.
    """
    out = []
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        two = src[i : i + 2]
        if two == "//":
            while i < n and src[i] != "\n":
                out.append(" ")
                i += 1
            continue
        if two == "/*":
            while i < n and src[i : i + 2] != "*/":
                out.append("\n" if src[i] == "\n" else " ")
                i += 1
            out.append("  ")
            i = min(i + 2, n)
            continue
        if src[i : i + 3] == '"""':
            out.append("   ")
            i += 3
            while i < n and src[i : i + 3] != '"""':
                out.append("\n" if src[i] == "\n" else " ")
                i += 1
            out.append("   ")
            i = min(i + 3, n)
            continue
        if c == '"' or c == "'":
            q = c
            out.append(" ")
            i += 1
            while i < n and src[i] != q:
                if src[i] == "\\":
                    out.append("  ")
                    i += 2
                    continue
                out.append("\n" if src[i] == "\n" else " ")
                i += 1
            out.append(" ")
            i = min(i + 1, n)
            continue
        out.append(c)
        i += 1
    return "".join(out)


class Src(object):
    __slots__ = ("path", "rel", "code", "starts", "methods", "types", "vartypes",
                 "idents", "recvs")

    def __init__(self, path, rel, code):
        self.path = path
        self.rel = rel
        self.code = code
        self.starts = [0]
        for m in re.finditer("\n", code):
            self.starts.append(m.end())
        self.methods = None
        self.types = None
        self.vartypes = None
        self.idents = frozenset(re.findall(r"[A-Za-z_$][\w$]*", code))
        self.recvs = {}

    def line_of(self, pos):
        lo, hi = 0, len(self.starts) - 1
        while lo < hi:
            mid = (lo + hi + 1) // 2
            if self.starts[mid] <= pos:
                lo = mid
            else:
                hi = mid - 1
        return lo + 1


def load_tree(root, rel):
    base = os.path.join(root, rel)
    if not os.path.isdir(base):
        return None
    files = []
    for dirpath, _dirs, names in os.walk(base):
        for nm in names:
            if not nm.endswith(".java"):
                continue
            p = os.path.join(dirpath, nm)
            try:
                with open(p, "r", encoding="utf-8", errors="replace") as fh:
                    raw = fh.read()
            except (IOError, OSError):
                return p  # signals unreadable
            files.append(
                Src(p, os.path.relpath(p, root).replace(os.sep, "/"), strip_java(raw))
            )
    return files


# --------------------------------------------------------------------------------------
# java member parsing (declaration shapes, bodies, enclosing types)
#
# The first cut of this gate matched declarations with one regex,
# `public <ret> <name>([^)]*)\s*\{`, and three ordinary Java shapes walked straight through
# it: a `throws` clause between `)` and `{`, an annotated parameter carrying its own
# parentheses, and a fluent mutator returning the entity type. A method the parser cannot
# see is not a subject, and a subject that does not exist can never fail -- that is a
# silent green, the exact failure mode of this class. So declarations are now found
# structurally: every `{` whose prefix is a balanced parameter list, with the keyword and
# `new`/`record` shapes excluded.

# NOT `record`: `ErrorLogService#record(..)` and `MeeraInteractionLogService#record(..)` are
# ordinary methods whose name happens to be a contextual keyword. Treating it as a keyword
# made both methods invisible to the parser AND dropped every `record(` call edge. The
# `record Foo(..)` type declaration is excluded by the preceding-keyword test instead.
BLOCK_KEYWORDS = {
    "if", "while", "for", "switch", "catch", "synchronized", "try", "do", "else",
    "return", "new", "assert",
}
MODIFIERS = {
    "public", "private", "protected", "static", "final", "abstract", "synchronized",
    "native", "default", "strictfp", "transient", "volatile",
}
THROWS_RE = re.compile(r"\)\s*throws\s+[\w.,\s<>\[\]]+$")
IDENT_BEFORE_RE = re.compile(r"([A-Za-z_$][\w$]*)\s*$")
NEW_BEFORE_RE = re.compile(r"\bnew\s+[\w.<>\[\],\s]*$")


def _match_back(code, close_pos, open_ch="(", close_ch=")"):
    """Index of the '(' matching the ')' at close_pos, or -1."""
    depth = 0
    i = close_pos
    while i >= 0:
        ch = code[i]
        if ch == close_ch:
            depth += 1
        elif ch == open_ch:
            depth -= 1
            if depth == 0:
                return i
        i -= 1
    return -1


def _match_fwd(code, open_pos):
    """Index just past the '}' matching the '{' at open_pos."""
    depth = 0
    i, n = open_pos, len(code)
    while i < n:
        ch = code[i]
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                return i + 1
        i += 1
    return n


def _header_start(code, pos):
    """Start of the member's own text: just past the previous ; { } before pos."""
    b = max(code.rfind(";", 0, pos), code.rfind("{", 0, pos), code.rfind("}", 0, pos))
    return 0 if b < 0 else b + 1


def strip_annotations(head):
    """Remove `@Anno` and its BALANCED argument list from a declaration header.

    Balanced, not `\\([^()]*\\)`: `@SchedulerLock(name = "x", lockAtMostFor = "PT20M")` and
    `@Scheduled(cron = "...")` leave `=` signs behind under a naive strip, and the `=` test
    that separates a declaration from a field initialiser then threw away every annotated
    method in the tree -- including every @Scheduled job. The visible symptom was 30-odd
    "dead caller" findings pointing at lines sitting directly inside a cron entry point.
    """
    out = []
    i, n = 0, len(head)
    while i < n:
        if head[i] == "@":
            j = i + 1
            while j < n and (head[j].isalnum() or head[j] in "_$."):
                j += 1
            k = j
            while k < n and head[k] in " \t\r\n":
                k += 1
            if k < n and head[k] == "(":
                depth = 0
                while k < n:
                    if head[k] == "(":
                        depth += 1
                    elif head[k] == ")":
                        depth -= 1
                        if depth == 0:
                            k += 1
                            break
                    k += 1
                i = k
            else:
                i = j
            out.append(" ")
            continue
        out.append(head[i])
        i += 1
    return "".join(out)


def _return_type(head):
    h = strip_annotations(head)
    h = " " + h + " "
    for mod in MODIFIERS:
        h = re.sub(r"\b" + mod + r"\b", " ", h)
    h = h.strip()
    while h.startswith("<"):
        depth = 0
        for i, ch in enumerate(h):
            if ch == "<":
                depth += 1
            elif ch == ">":
                depth -= 1
                if depth == 0:
                    h = h[i + 1 :].strip()
                    break
        else:
            break
    return re.sub(r"\s+", "", h)


def parse_params(text):
    """[(simple_type, name)] for a parameter list, generics and annotations tolerated."""
    parts, depth, buf = [], 0, []
    for ch in text:
        if ch in "(<[":
            depth += 1
        elif ch in ")>]":
            depth -= 1
        if ch == "," and depth <= 0:
            parts.append("".join(buf))
            buf = []
            continue
        buf.append(ch)
    parts.append("".join(buf))
    out = []
    for p in parts:
        p = strip_annotations(p).strip()
        if not p:
            continue
        p = re.sub(r"\bfinal\b", " ", p).replace("...", " ")
        toks = p.split()
        if len(toks) < 2:
            continue
        name = toks[-1]
        rest = re.sub(r"<.*>", " ", p[: p.rfind(name)], flags=re.S).replace("[]", "")
        rt = rest.split()
        if not rt:
            continue
        out.append((rt[-1].split(".")[-1], name))
    return out


CALL_RE = re.compile(r"([A-Za-z_$][\w$]*)\s*\(")


def _paren_end(code, open_pos):
    depth, i, n = 0, open_pos, len(code)
    while i < n:
        if code[i] == "(":
            depth += 1
        elif code[i] == ")":
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return n


def call_args(src, m):
    """[(callee_simple_name, {identifiers in its argument list})] inside this method body."""
    if m.calls is None:
        out = []
        code = src.code
        for cm in CALL_RE.finditer(code, m.body_start, m.body_end):
            nm = cm.group(1)
            if nm in BLOCK_KEYWORDS:
                continue
            end = _paren_end(code, cm.end() - 1)
            out.append((nm, frozenset(re.findall(r"[A-Za-z_$][\w$]*", code[cm.end() : end]))))
        m.calls = out
    return m.calls


class Method(object):
    __slots__ = ("name", "ret", "start", "body_start", "body_end", "line", "head",
                 "annos", "is_static", "is_public", "owner", "parent", "key",
                 "params", "calls")

    def __init__(self, **kw):
        for k in self.__slots__:
            setattr(self, k, kw.get(k))


def type_spans(code):
    """[(name, kind, decl_start, body_start, body_end)] for every type declaration."""
    out = []
    for m in re.finditer(r"\b(class|interface|enum|record)\s+([A-Za-z_$][\w$]*)", code):
        i, n = m.end(), len(code)
        depth = 0
        brace = -1
        while i < n:
            ch = code[i]
            if ch in "(<":
                depth += 1
            elif ch in ")>":
                depth -= 1
            elif ch == ";" and depth <= 0:
                break
            elif ch == "{" and depth <= 0:
                brace = i
                break
            i += 1
        if brace < 0:
            continue
        out.append((m.group(2), m.group(1), m.start(), brace, _match_fwd(code, brace)))
    return out


def parse_methods(src):
    """Every method / constructor declaration with a body, innermost-owner attributed."""
    if src.methods is not None:
        return src.methods
    code = src.code
    types = type_spans(code)
    src.types = types
    out = []
    for m in re.finditer(r"\{", code):
        bpos = m.start()
        pre = code[:bpos].rstrip()
        if not pre:
            continue
        close = -1
        if pre.endswith(")"):
            close = len(pre) - 1
        else:
            tm = THROWS_RE.search(pre)
            if tm:
                close = tm.start()
        if close < 0:
            continue
        opar = _match_back(code, close)
        if opar < 0:
            continue
        im = IDENT_BEFORE_RE.search(code[:opar])
        if not im:
            continue
        name = im.group(1)
        if name in BLOCK_KEYWORDS:
            continue
        istart = im.start(1)
        if NEW_BEFORE_RE.search(code[:istart]):
            continue          # anonymous class body
        if re.search(r"\b(record|class|enum|interface)\s+$", code[:istart]):
            continue          # record header
        hstart = _header_start(code, istart)
        head = code[hstart:istart]
        bare = strip_annotations(head)
        if "=" in bare or "->" in bare:
            continue          # lambda / initialiser, not a declaration
        ret = _return_type(head)
        annos = set(re.findall(r"@(\w+)", head))
        owner = None
        best = -1
        for tname, _k, _ds, bs, be in types:
            if bs < istart < be and bs > best:
                best = bs
                owner = tname
        out.append(
            Method(
                params=parse_params(code[opar + 1 : close]),
                calls=None,
                name=name,
                ret=ret,
                start=hstart,
                body_start=bpos,
                body_end=_match_fwd(code, bpos),
                line=src.line_of(istart),
                head=head,
                annos=annos,
                is_static="static" in bare.split(),
                is_public="public" in bare.split(),
                owner=owner,
                parent=None,
                key=None,
            )
        )
    out.sort(key=lambda x: x.body_start)
    for i, mm in enumerate(out):
        mm.key = (src.rel, mm.body_start)
        for j in range(i - 1, -1, -1):
            if out[j].body_start < mm.body_start < out[j].body_end:
                mm.parent = out[j].key
                break
    src.methods = out
    return out


# --------------------------------------------------------------------------------------
# production-entry reachability
#
# THE hole the adversary drove through: a write inside `private void neverCalledByAnyone()`
# used to count as proof the feature exists. It is not. Nothing ships from dead code.

ENTRY_ANNOS = {
    "GetMapping", "PostMapping", "PutMapping", "PatchMapping", "DeleteMapping",
    "RequestMapping", "MessageMapping", "SubscribeMapping", "ExceptionHandler",
    "InitBinder", "ModelAttribute", "Scheduled", "EventListener",
    "TransactionalEventListener", "PostConstruct", "PreDestroy", "Bean",
    "PrePersist", "PreUpdate", "PostPersist", "PostUpdate", "PostLoad", "PreRemove",
    "PostRemove", "KafkaListener", "RabbitListener", "JmsListener", "SqsListener",
    "StreamListener", "Override", "Test",
}
# Conventional framework callbacks: dispatched by name through an interface, so no lexical
# caller exists in this tree. @Override normally marks them; this is the belt to that brace.
ENTRY_NAMES = {
    "run", "call", "execute", "handle", "process", "write", "read", "apply", "accept",
    "get", "compare", "convert", "supports", "doFilter", "doFilterInternal",
    "afterPropertiesSet", "destroy", "onApplicationEvent", "resolveArgument",
    "beforeStep", "afterStep", "onMessage", "customize", "addCorsMappings",
    "addInterceptors", "configure", "main",
}
STEREOTYPES = {
    "Service", "Component", "RestController", "Controller", "Repository",
    "Configuration", "ControllerAdvice", "RestControllerAdvice", "Entity",
    "Embeddable", "MappedSuperclass", "Aspect",
}

CALL_NAME_RES = (
    re.compile(r"(?<![\w.$])([A-Za-z_$][\w$]*)\s*\("),
    re.compile(r"\.\s*([A-Za-z_$][\w$]*)\s*\("),
    re.compile(r"::\s*([A-Za-z_$][\w$]*)"),
    re.compile(r"\bnew\s+([A-Za-z_$][\w$]*)"),
)


def _class_annos(code, types, pos):
    """Annotations on the innermost type declaration containing pos."""
    best, annos = -1, set()
    for _tname, _k, ds, bs, be in types:
        if bs < pos < be and bs > best:
            best = bs
            annos = set(re.findall(r"@(\w+)", code[_header_start(code, ds) : ds]))
    return annos


class CallGraph(object):
    """Name-keyed over-approximation of the main-source call graph.

    Over-approximating is deliberate: every method whose SIMPLE NAME is invoked anywhere in
    a reachable body is treated as reachable, so interface dispatch, overloads and Spring
    proxies can never produce a false "unreachable". The graph exists to catch the opposite
    error -- code no entry point names AT ALL.
    """

    def __init__(self, files):
        self.by_key = {}
        self.by_name = {}
        self.calls = {}
        self.reachable = set()
        seeds = []
        for s in files:
            ms = parse_methods(s)
            # The span excluded from the class-level seed starts at the DECLARATION, not at
            # the body brace: leaving the header in would make every method's own signature
            # `neverCalledByAnyone(Widget w)` read as a call to itself, and every dead
            # private method would seed itself as reachable. That bug greened the exact
            # evasion this rewrite exists to close, and the self-test caught it.
            spans = [(mm.start, mm.body_end) for mm in ms]
            for mm in ms:
                self.by_key[mm.key] = (s, mm)
                self.by_name.setdefault(mm.name, []).append(mm.key)
            # code outside every method body (field / static initialisers) always runs
            covered = []
            for a, b in sorted(spans):
                if covered and a <= covered[-1][1]:
                    covered[-1] = (covered[-1][0], max(covered[-1][1], b))
                else:
                    covered.append((a, b))
            pos, chunks = 0, []
            for a, b in covered:
                chunks.append(s.code[pos:a])
                pos = b
            chunks.append(s.code[pos:])
            seeds.append("".join(chunks))
            for mm in ms:
                if self._is_entry(s, mm):
                    self.reachable.add(mm.key)
        work = list(self.reachable)
        for text in seeds:
            for nm in self._names(text):
                for k in self.by_name.get(nm, ()):
                    if k not in self.reachable:
                        self.reachable.add(k)
                        work.append(k)
        while work:
            k = work.pop()
            s, mm = self.by_key[k]
            body = self.calls.get(k)
            if body is None:
                body = self._names(s.code[mm.body_start : mm.body_end])
                self.calls[k] = body
            for nm in body:
                for k2 in self.by_name.get(nm, ()):
                    if k2 not in self.reachable:
                        self.reachable.add(k2)
                        work.append(k2)

    @staticmethod
    def _names(text):
        out = set()
        for rx in CALL_NAME_RES:
            for m in rx.finditer(text):
                nm = m.group(1)
                if nm not in BLOCK_KEYWORDS:
                    out.add(nm)
        return out

    def _is_entry(self, s, mm):
        if mm.annos & ENTRY_ANNOS:
            return True
        if mm.name in ENTRY_NAMES:
            return True
        if mm.ret == "" :                      # constructor
            annos = _class_annos(s.code, s.types or [], mm.start)
            if annos & STEREOTYPES:
                return True
        return False

    def site_reachable(self, s, pos):
        """True unless the site sits inside a method no entry point can reach.

        A method nested in another (anonymous class, local class) inherits its enclosing
        method's reachability. A site outside every method body is class-level code.
        """
        ms = parse_methods(s)
        inner = None
        for mm in reversed(ms):              # sorted by body_start; innermost hit wins
            if mm.body_start < pos < mm.body_end:
                inner = mm
                break
        if inner is None:
            return True                      # class-level code: field / static initialiser
        while inner is not None:
            if inner.key in self.reachable:
                return True
            inner = self.by_key[inner.parent][1] if inner.parent in self.by_key else None
        return False


# --------------------------------------------------------------------------------------
# parsing enums / entity fields


# NOT `[A-Z][A-Z0-9_]*`: DealMessageKind's constants are lowercase (`text`, `system`,
# `proposal`, ...) to mirror the TypeScript union in src/lib/api.ts, and a SCREAMING_CASE
# rule parsed that enum to zero constants. Under the old hand-curated entity list the enum
# was never looked at, so the bug was invisible; the moment the set is derived from the
# tree it turns a whole enum into either a crash or a silent skip.
ENUM_CONST_RE = re.compile(r"^\s*([A-Za-z_$][\w$]*)\s*$")


def enum_constants(code):
    """Constants of a single-enum file. Returns [] if the enum body cannot be found.

    Splits the constant list on top-level commas rather than on NEWLINES: the first cut of
    this gate matched one constant per line and so read the one-line enum
    `enum S { DRAFT, LIVE, CANCELLED }` as having exactly one state -- silently examining
    a third of the subjects and reporting a clean tree. Caught by the built-in self-test.
    """
    m = re.search(r"\benum\s+(\w+)\s*(?:implements[^{]*)?\{", code)
    if not m:
        return []
    body = code[m.end() :]
    consts = []
    depth = 0        # parens, for constants with constructor args: FOO("f")
    angle = 0
    buf = []
    for ch in body:
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
        elif ch == "<":
            angle += 1
        elif ch == ">":
            angle = max(0, angle - 1)
        if depth == 0 and angle == 0 and ch in ",;}":
            piece = "".join(buf)
            piece = re.sub(r"\(.*$", "", piece, flags=re.S)
            piece = re.sub(r"\{.*$", "", piece, flags=re.S)
            piece = re.sub(r"@\w+", " ", piece).strip()
            cm = ENUM_CONST_RE.match(piece)
            if cm:
                consts.append(cm.group(1))
            buf = []
            if ch in ";}":
                break
            continue
        buf.append(ch)
    return consts


FIELD_RE = re.compile(r"\b(?:private|protected|public)\s+(?:final\s+)?(\w+)\s+(\w+)\s*[;=]")


def entity_enum_fields(code, known_enums):
    found = []
    for m in FIELD_RE.finditer(code):
        typ = m.group(1)
        if typ in known_enums and typ not in found:
            found.append(typ)
    return found


def entity_mutators(src, entity):
    """public mutators on the entity and on its nested Builder.

    A mutator is a public, non-static method that is not a getter, not in MUTATOR_SKIP, and
    returns void, the nested Builder (the fluent-setter shape -- F-0413), or the ENTITY
    TYPE ITSELF (the other fluent shape, which the old return-type filter dropped on the
    floor: `public Contract note(String n) { ..; return this; }` was never a subject at all).
    Returns [(name, line, is_builder)].
    """
    out = []
    for m in parse_methods(src):
        if not m.is_public or m.ret == "":
            continue
        name = m.name
        if name in MUTATOR_SKIP or name == entity:
            continue
        if re.match(r"^(get|is|has)[A-Z]", name):
            continue
        fluent_builder = m.ret in ("Builder", entity + ".Builder")
        fluent_self = (m.ret == entity) and not m.is_static
        if m.ret != "void" and not fluent_builder and not fluent_self:
            continue
        if m.ret == "void" and m.is_static:
            continue
        out.append((name, m.line, m.ret != "void"))
    return out


# --------------------------------------------------------------------------------------
# write-site classification for enum constants

READ_CALL = re.compile(
    r"^(?:.*\.)?(?:find\w*|exists\w*|count\w*|delete\w*|remove\w*|search\w*|contains\w*"
    r"|equals|compareTo|filter|anyMatch|allMatch|noneMatch|in|notIn|not|isEqualTo|hasStatus)$",
    re.I,
)
COLLECTION_OF = re.compile(r"^(?:List|Set|EnumSet|Arrays|Collections|Stream|Map)\.\w+$")
GETTERISH = re.compile(r"^(?:get|is|has)[A-Z0-9_]")
CONTROL = {"if", "while", "for", "switch", "catch", "return", "synchronized", "assert"}

READ_AFTER = [
    re.compile(r"^\s*[=!]="),                                        # CONST == x
    re.compile(r"^\s*->"),                                           # switch arrow label
    re.compile(r"^\s*\.\s*(?:name|toString|ordinal|equals|compareTo|getValue)\s*\("),
]
ASSIGN_RE = re.compile(r"(?<![=!<>+\-*/%&|^])=\s*(?!=)")
CASE_RE = re.compile(r"\bcase\s+[^()]*$")
CMP_RE = re.compile(r"[=!]=\s*$")
RETURN_RE = re.compile(r"\breturn\b[^;()]*$")
NEW_RE = re.compile(r"\bnew\s+([\w.]+)\s*$")
VARTYPE_RE = re.compile(r"(?<![\w.])([A-Z][\w.]*(?:\s*<[^;{}()=]{0,120}?>)?)\s+([a-z_$][\w$]*)\s*[=;,):]")


def file_vartypes(src):
    """identifier -> declared type text, for every local/field/param declaration in a file.

    Used only to REJECT a write: if the receiver of a `setX(CONST)` call is provably an
    identifier of some other type, the call cannot be writing the entity's state. Unknown
    receivers are accepted -- this is a falsifier, not a type checker.
    """
    if src.vartypes is not None:
        return src.vartypes
    out = {}
    for m in VARTYPE_RE.finditer(src.code):
        typ = re.sub(r"\s+", "", m.group(1))
        nm = m.group(2)
        if nm in MODIFIERS:
            continue
        out.setdefault(nm, set()).add(typ)
    src.vartypes = out
    return out


def statement_around(code, pos):
    """Crude statement slice: back to the previous ; { } and forward to the next ; { }."""
    start = max(
        code.rfind(";", 0, pos),
        code.rfind("{", 0, pos),
        code.rfind("}", 0, pos),
    )
    start = 0 if start < 0 else start + 1
    stops = [code.find(ch, pos) for ch in ";{}"]
    stops = [s for s in stops if s != -1]
    end = min(stops) if stops else len(code)
    return code[start:pos], code[pos:end]


def enclosing_call(before):
    """(callee_text, index_of_open_paren) for the innermost unclosed '(' before pos."""
    depth = 0
    i = len(before) - 1
    while i >= 0:
        ch = before[i]
        if ch == ")":
            depth += 1
        elif ch == "(":
            if depth == 0:
                m = re.search(r"((?:[\w$]+\s*\.\s*)*[\w$]+)\s*$", before[:i])
                return (re.sub(r"\s+", "", m.group(1)) if m else ""), i
            depth -= 1
        i -= 1
    return None, -1


class Owner(object):
    """Who legitimately holds this enum: the entity types with a field of it, the mutator
    names declared on those entities (and their Builders), the entity source files, and the
    SINK CLOSURE -- service methods that forward an argument of this enum type into one of
    those entity methods.

    The closure is what keeps the assertion honest once the entity set is derived rather
    than curated. Outside the ten hand-picked lifecycle entities the dominant idiom is not
    `entity.setStatus(CONST)` but `historyService.record(id, EVENT_TYPE, ACTOR, ..)`, whose
    body hands the argument to `ApplicationHistoryEvent.create(..)`. Scoring that as "not a
    write" turns roughly forty live, shipping states into findings -- breadth that flags
    working code is not a better gate.
    """

    __slots__ = ("types", "methods", "rels", "sinks")

    def __init__(self, types, methods, rels, sinks=None):
        self.types = types
        self.methods = methods
        self.rels = rels
        self.sinks = sinks or set()

    def plausible_receiver(self, src, recv):
        if not recv or recv == "this":
            return True                      # implicit this, or a builder/call chain
        head = recv.split(".")[0]
        if head in self.types:
            return True                      # static-ish `Contract.builder()...`
        types = file_vartypes(src).get(head)
        if not types:
            return True                      # unknown identifier: do not reject on a guess
        for t in types:
            base = t.split("<")[0]
            if base in self.types or base.endswith("Builder") or base == "var":
                return True
            for o in self.types:
                if base.startswith(o + "."):
                    return True
        return False


def classify(src, before, after, owner):
    """-> 'write' | 'read' | 'other' for one occurrence of an enum constant.

    Walks OUTWARD through enclosing call parentheses rather than looking only at the few
    characters before the constant. Two bugs the built-in self-test caught in the first
    cut of this function:
      * `w.setStatus(cancel ? CANCELLED : LIVE)` -- LIVE is not adjacent to the '(' so a
        "constant immediately after a setter paren" rule missed it entirely, and CANCELLED
        was classified as a `case CONST:` label because a ternary ':' followed it;
      * the `case` rule must not cross a '(' or every argument inside a switch subject is
        read as a label.

    ATTRIBUTION (the `dto.setDisplayStatus(CANCELLED)` evasion): a setter-shaped call only
    counts as a write when the invoked method is declared on an entity that OWNS a field of
    this enum and the receiver is not provably of another type. Writing an entity's status
    constant into a response DTO does not put any row into that state.
    """
    for rx in READ_AFTER:
        if rx.search(after):
            return "read"
    b = before
    # These two must be tested on the RAW prefix, before any outward walk. Walking out of
    # `appendShipmentMessage(cond == ShipmentCondition.DAMAGED ? .. : ..)` discards the
    # `cond ==` that sits INSIDE the parens, and the site was scored "other" instead of
    # "read". Found by hand-auditing ShipmentService.java:232 against the gate's output.
    if CMP_RE.search(b.rstrip()):
        return "read"
    if CASE_RE.search(b.rstrip()):
        return "read"
    for _ in range(6):
        name, idx = enclosing_call(b)
        if name is None:
            break
        if name in CONTROL:
            b = b[:idx]
            continue
        if READ_CALL.match(name) or COLLECTION_OF.match(name):
            return "read"
        simple = name.split(".")[-1]
        recv = name[: -(len(simple) + 1)] if len(name) > len(simple) else ""
        if simple in owner.methods:
            # declared on an entity that owns a field of this enum: a real state write,
            # provided the receiver is not provably an instance of something else
            if owner.plausible_receiver(src, recv):
                return "write"
            return "dto"
        if simple in owner.sinks:
            return "write"       # forwarded into an owner entity by the callee
        nm = NEW_RE.search(b[:idx])
        if nm:
            if nm.group(1).split(".")[-1] in owner.types:
                return "write"
            return "dto"         # constructed into something that is not the owning entity
        b = b[:idx]          # unknown wrapper (orElse, requireNonNullElse) -- look outside
    tail = b.rstrip()
    if CMP_RE.search(tail):
        return "read"
    if CASE_RE.search(tail):
        return "read"
    am = ASSIGN_RE.search(b)
    if am:
        lhs = b[: am.start()].strip()
        lm = re.search(r"((?:[\w$]+\s*\.\s*)*[\w$]+)\s*$", lhs)
        target = re.sub(r"\s+", "", lm.group(1)) if lm else ""
        if "." in target:
            recv = target.rsplit(".", 1)[0]
            if recv != "this" and not owner.plausible_receiver(src, recv):
                return "other"
        return "write"       # plain assignment AND both arms of `x = c ? A : B`
    if RETURN_RE.search(tail):
        return "write"       # a factory/mapper producing the state for a caller to store
    return "other"


def const_sites(files, enum, const, owner, graph=None):
    """[(rel, line, kind, reachable)] for every qualified occurrence outside the enum file."""
    qualified = re.compile(r"\b" + re.escape(enum) + r"\s*\.\s*" + re.escape(const) + r"\b")
    bare = re.compile(r"(?<![\w.])" + re.escape(const) + r"\b")
    static_import = re.compile(
        r"import\s+static\s+[\w.]*\." + re.escape(enum) + r"\.(?:" + re.escape(const) + r"|\*)\s*;"
    )
    hits = []
    for s in files:
        if const not in s.idents:            # identifier index: skip the 99% cheaply
            continue
        if s.rel.endswith("/" + enum + ".java"):
            continue
        rxs = [qualified] if enum in s.idents else []
        if static_import.search(s.code):
            rxs.append(bare)
        seen = set()
        for rx in rxs:
            for m in rx.finditer(s.code):
                if m.start() in seen:
                    continue
                seen.add(m.start())
                before, after = statement_around(s.code, m.start())
                kind = classify(s, before, after, owner)
                live = True
                if graph is not None and kind == "write":
                    live = graph.site_reachable(s, m.start())
                hits.append((s.rel, s.line_of(m.start()), kind, live))
    return hits


# --------------------------------------------------------------------------------------
# call-site resolution for mutators

WEB_REL = os.path.join(MAIN_REL, "com", "influora", "web").replace(os.sep, "/")
REQUESTISH = re.compile(r"(Request|Command|Input|Payload|Body)$")


def dynamic_entry(files, enum):
    """(reason, site) if this enum has a NON-LEXICAL way to reach its constants, else None.

    This is the KNOWN TRAP for the class: a constant can be produced without any source
    token naming it -- `DeliverableType.valueOf(raw)` in ContractService, or Jackson
    binding `@NotNull ShipmentCondition condition` in a *Request record, or Spring
    converting a `@RequestParam DisputeStatus status`. For such enums per-constant
    reachability is NOT decidable by reading the tree, so they are reported as NOT
    ASSERTED rather than either failed (7 DeliverableType constants and 2
    ShipmentCondition constants were false reds before this existed) or silently dropped.

    Deliberately narrow: an enum that appears only in a *Response* record is still fully
    asserted, which is why ContractStatus -- present in DealDtos and MoneyDtos as a
    response field -- keeps failing on CANCELLED/COMPLETED. That is F-0403.
    """
    vo = re.compile(r"\b" + re.escape(enum) + r"\s*\.\s*valueOf\s*\(")
    # The reflective shape of the same trap: `parseEnum(TicketStatus.class, raw, "status")`,
    # `Enum.valueOf(cls, ..)`, `objectMapper.convertValue(.., X.class)`. The class literal is
    # the only token that names the enum, so no constant of it is decidable by reading the
    # tree -- TicketStatus/TicketPriority were four false reds until this existed.
    cls = re.compile(r"\b" + re.escape(enum) + r"\s*\.\s*class\b")
    files = [s for s in files if enum in s.idents]
    for s in files:
        m = vo.search(s.code)
        if m:
            return ("%s.valueOf(..) parses constants from a string" % enum,
                    "%s:%d" % (s.rel, s.line_of(m.start())))
    for s in files:
        if s.rel.endswith("/" + enum + ".java"):
            continue
        m = cls.search(s.code)
        if m:
            return ("%s.class is handed to a reflective parser/binder" % enum,
                    "%s:%d" % (s.rel, s.line_of(m.start())))
    typ = re.compile(r"(?<![\w.])" + re.escape(enum) + r"\s+\w+\s*[,)]")
    for s in files:
        if not s.rel.startswith(WEB_REL):
            continue
        m = re.search(
            r"@(?:RequestParam|PathVariable|RequestHeader)[^)]{0,120}?(?<![\w.])"
            + re.escape(enum) + r"\s+\w+", s.code)
        if m:
            return ("bound from the query string / path",
                    "%s:%d" % (s.rel, s.line_of(m.start())))
        for rm in re.finditer(r"\brecord\s+(\w+)\s*\(", s.code):
            name = rm.group(1)
            if not REQUESTISH.search(name):
                continue
            depth, i, n = 0, rm.end() - 1, len(s.code)
            while i < n:
                if s.code[i] == "(":
                    depth += 1
                elif s.code[i] == ")":
                    depth -= 1
                    if depth == 0:
                        break
                i += 1
            body = s.code[rm.end() : i]
            tm = typ.search(body + ")")
            if tm:
                return ("deserialized into request record %s" % name,
                        "%s:%d" % (s.rel, s.line_of(rm.start())))
    return None


def receiver_names(code, entity):
    """Identifiers in this file that plausibly hold an instance of `entity`.

    The ':' in the terminator class is load-bearing: without it a for-each header
    `for (Deliverable d : batch)` declares nothing as far as this function is concerned,
    and Deliverable#applyMediaCleanup was reported unreachable although
    DeliverableCleanupJob.java:224 calls it on exactly such a `d`. A hand audit of the
    gate's own first output caught it.
    """
    names = set()
    for m in re.finditer(r"\b" + re.escape(entity) + r"\s+(\w+)\s*[=;,):]", code):
        names.add(m.group(1))
    for m in re.finditer(
        r"\b(?:Optional|List|Set|Collection|Page|Iterable)\s*<\s*" + re.escape(entity)
        + r"\s*>\s+(\w+)", code
    ):
        names.add(m.group(1))
    # Lambda parameters have NO declared type:
    #   creatorProfileRepository.findById(id).ifPresent(profile -> profile.setThemeTagsJson(..))
    # is a live production write that the two patterns above cannot see, and
    # CreatorProfile#setThemeTagsJson was a false red because of it. A lambda parameter is
    # accepted only when its own statement names this entity's repository or the entity
    # type itself, so an unrelated `subscription -> subscription.setStatus(..)` elsewhere in
    # the file still cannot vouch for Contract#setStatus.
    repo = entity[0].lower() + entity[1:] + "Repository"
    ent_rx = re.compile(r"\b(?:" + re.escape(repo) + r"|" + re.escape(entity) + r")\b")
    for m in re.finditer(r"(?<![\w.])(\w+)\s*->", code):
        st = max(code.rfind(";", 0, m.start()), code.rfind("{", 0, m.start()),
                 code.rfind("}", 0, m.start()))
        st = 0 if st < 0 else st + 1
        if ent_rx.search(code[st : m.start()]):
            names.add(m.group(1))
    return names


def mutator_sites(files, entity, entity_rel, method, is_builder, graph=None):
    """[(rel, line, reachable)] for type-plausible call sites of entity#method."""
    hits = []
    chain_root = re.compile(r"\b" + re.escape(entity) + r"\s*\.\s*builder\s*\(\s*\)")
    dotted = re.compile(r"\.\s*" + re.escape(method) + r"\s*\(")
    implicit = re.compile(r"(?<![\w.])" + re.escape(method) + r"\s*\(")
    decl = re.compile(r"\b(?:public|private|protected)\b[^;{]*\b" + re.escape(method) + r"\s*\(")
    for s in files:
        own = s.rel == entity_rel
        if method not in s.idents:
            continue
        recvs = s.recvs.get(entity)
        if recvs is None:
            recvs = receiver_names(s.code, entity)
            s.recvs[entity] = recvs
        has_chain = bool(chain_root.search(s.code))
        for m in dotted.finditer(s.code):
            pre = s.code[max(0, m.start() - 120) : m.start()]
            tail = re.search(r"([\w\)\]]+)\s*$", pre)
            tok = tail.group(1) if tail else ""
            ok = False
            if tok in recvs:
                ok = True
            elif is_builder and has_chain:
                # Contract.builder().id(x).expirationDate(d) -- no simple receiver exists
                ok = True
            elif tok in (")", "]") and (has_chain or recvs):
                ok = True
            elif own:
                ok = True
            if ok:
                live = graph.site_reachable(s, m.start()) if graph is not None else True
                hits.append((s.rel, s.line_of(m.start()), live))
        if own:
            for m in implicit.finditer(s.code):
                seg_start = max(
                    s.code.rfind(";", 0, m.start()),
                    s.code.rfind("{", 0, m.start()),
                    s.code.rfind("}", 0, m.start()),
                )
                seg = s.code[(0 if seg_start < 0 else seg_start + 1) : m.end()]
                if decl.search(seg) or re.search(r"\b(new|class|enum)\s*$", seg[: -len(method) - 1] or ""):
                    continue
                live = graph.site_reachable(s, m.start()) if graph is not None else True
                hits.append((s.rel, s.line_of(m.start()), live))
    return hits


# --------------------------------------------------------------------------------------
# the check


class Result(object):
    def __init__(self):
        self.files_scanned = 0
        self.subjects = 0
        self.entities = []
        self.findings = []      # (subject, why, [evidence lines])
        self.ok = []            # (subject, evidence)
        self.skipped = []       # (subject, reason, site) -- not decidable statically
        self.fatal = None


MIGRATION_REL = os.path.join("influora-api", "src", "main", "resources", "db", "migration")
INSERT_RE = re.compile(r"\binsert\s+into\s+`?(\w+)`?", re.I)
TABLE_RE = re.compile(r"@Table\s*\([^)]*name\s*=\s*\"(\w+)\"")


def load_seeds(root):
    """table -> [(rel, raw text)] for every Flyway migration that INSERTs into that table.

    Not every production row is born in Java. `plans` and `campaign_templates` are seeded by
    V55__seed_billing_plans.sql / V20260714150000__campaign_templates.sql, so
    `CampaignTemplateScope.SYSTEM` really does exist in every environment and Plan's builder
    really is unnecessary -- reporting either as a missing feature is a false alarm about
    code that ships and works. The seed is evidence, so the gate reads it.
    """
    base = os.path.join(root, MIGRATION_REL)
    out = {}
    if not os.path.isdir(base):
        return out
    for dirpath, _d, names in os.walk(base):
        for nm in sorted(names):
            if not nm.endswith(".sql"):
                continue
            p = os.path.join(dirpath, nm)
            try:
                with open(p, "r", encoding="utf-8", errors="replace") as fh:
                    txt = fh.read()
            except (IOError, OSError):
                continue
            rel = os.path.relpath(p, root).replace(os.sep, "/")
            for m in INSERT_RE.finditer(txt):
                out.setdefault(m.group(1).lower(), []).append((rel, txt))
    return out


def entity_table(src):
    """The @Table(name = "..") of an entity, read from the RAW file (strip_java blanks it)."""
    try:
        with open(src.path, "r", encoding="utf-8", errors="replace") as fh:
            raw = fh.read()
    except (IOError, OSError):
        return None
    m = TABLE_RE.search(raw)
    return m.group(1).lower() if m else None


def seed_site(seeds, tables, const):
    """(rel, line) where a seed INSERT for one of `tables` names this constant, else None."""
    for t in tables:
        for rel, txt in seeds.get(t, ()):
            m = re.search(r"(?<![\w])" + re.escape(const) + r"(?![\w])", txt)
            if m:
                return (rel, txt.count("\n", 0, m.start()) + 1)
    return None


def sink_closure(enum, types, methods, by_param_type):
    """Simple names of methods that put a value of `enum` onto an owning entity.

    Seeded with the entity's own mutators/factories/constructors, then closed over
    one-argument forwarding: a method with a parameter of this enum type that hands that
    parameter to a known sink is itself a sink. Two hops is what
    `ContractService -> ApplicationHistoryService#record -> ApplicationHistoryEvent.create`
    needs; the loop runs to a fixpoint, so deeper chains are covered too.

    Deliberately NOT a general taint analysis: only a parameter whose DECLARED TYPE is the
    enum can propagate, so a repository read (`existsByStatusNot(CANCELLED)`, whose
    interface declaration has no body) can never become a sink and F-0403 stays red.
    """
    sinks = set(methods) | set(types)
    cands = by_param_type.get(enum, [])
    if not cands:
        return sinks
    for _round in range(6):
        grew = False
        for s, mm, pname in cands:
            if mm.name in sinks:
                continue
            for cname, idents in call_args(s, mm):
                if cname in sinks and pname in idents:
                    sinks.add(mm.name)
                    grew = True
                    break
        if not grew:
            break
    return sinks


def derive_entities(main, known_enums):
    """Every @Entity in the domain entity package that holds at least one project enum,
    plus every REQUIRED_ENTITIES member present in the tree (Payout keeps its lifecycle in
    a String column mirroring RazorpayX, so it carries no project enum, but its mutators
    are still product surface).

    Hard-coding the list is how a brand-new `Refund` entity with three dead constants and
    two uncalled mutators stays unexamined forever while the gate prints VERDICT: clean.
    """
    prefix = ENTITY_REL.replace(os.sep, "/") + "/"
    req = set(REQUIRED_ENTITIES)
    out = []
    for s in main:
        if not s.rel.startswith(prefix):
            continue
        name = s.rel.rsplit("/", 1)[-1][:-5]
        if name in req:
            out.append((name, s))
            continue
        if not re.search(r"@Entity\b", s.code):
            continue
        if not entity_enum_fields(s.code, known_enums):
            continue
        out.append((name, s))
    out.sort(key=lambda x: x[0])
    return out


def run(root, only_rx=None):
    r = Result()
    main = load_tree(root, MAIN_REL)
    if main is None:
        r.fatal = "no main source tree at %s" % os.path.join(root, MAIN_REL)
        return r
    if isinstance(main, str):
        r.fatal = "unreadable source file: %s" % main
        return r
    tests = load_tree(root, TEST_REL)
    if isinstance(tests, str):
        r.fatal = "unreadable test file: %s" % tests
        return r
    if tests is None:
        tests = []
    r.files_scanned = len(main)
    if not main:
        r.fatal = "zero .java files under %s" % MAIN_REL
        return r

    enum_dir = os.path.join(root, ENUM_REL)
    if not os.path.isdir(enum_dir):
        r.fatal = "no enum package at %s" % ENUM_REL
        return r
    known_enums = {}
    for nm in sorted(os.listdir(enum_dir)):
        if nm.endswith(".java"):
            known_enums[nm[:-5]] = os.path.join(enum_dir, nm)
    if not known_enums:
        r.fatal = "zero enums under %s" % ENUM_REL
        return r

    entity_srcs = derive_entities(main, known_enums)
    if not entity_srcs:
        r.fatal = "zero @Entity classes holding a project enum under %s" % ENTITY_REL
        return r
    r.entities = [e for e, _s in entity_srcs]
    have = set(r.entities)
    missing = [e for e in REQUIRED_ENTITIES if e not in have]
    if missing:
        r.fatal = ("required lifecycle entit%s no longer derived from the tree: %s "
                   "(moved, renamed or stripped of its enum -- the gate is looking at the "
                   "wrong tree)" % ("y" if len(missing) == 1 else "ies", ", ".join(missing)))
        return r

    graph = CallGraph(main)

    efields = dict((s.rel, entity_enum_fields(s.code, known_enums)) for _e, s in entity_srcs)
    seeds = load_seeds(root)
    tables = dict((e, entity_table(s)) for e, s in entity_srcs)

    # methods anywhere in main that take a parameter of a given type -- the frontier for the
    # sink closure below
    by_param_type = {}
    for s in main:
        for mm in parse_methods(s):
            for typ, nm in mm.params:
                by_param_type.setdefault(typ, []).append((s, mm, nm))

    # who legitimately owns each enum: every derived entity with a field of that type
    owners = {}
    for enum in known_enums:
        types, methods, rels = set(), set(), set()
        for ent, s in entity_srcs:
            if enum in efields[s.rel]:
                types.add(ent)
                rels.add(s.rel)
                for mm in parse_methods(s):
                    if mm.name in MUTATOR_SKIP or GETTERISH.match(mm.name):
                        continue
                    methods.add(mm.name)
        owners[enum] = Owner(types, methods, rels,
                             sink_closure(enum, types, methods, by_param_type))

    # ---- A. enum state reachability
    done_enums = set()
    for entity, s in entity_srcs:
        for enum in efields[s.rel]:
            if enum in done_enums:
                continue
            done_enums.add(enum)
            try:
                with open(known_enums[enum], "r", encoding="utf-8", errors="replace") as fh:
                    ecode = strip_java(fh.read())
            except (IOError, OSError):
                r.fatal = "unreadable enum %s" % enum
                return r
            consts = enum_constants(ecode)
            if not consts:
                r.fatal = "enum %s parsed to zero constants -- parser broken, not clean" % enum
                return r
            dyn = dynamic_entry(main, enum)
            if dyn:
                for c in consts:
                    subject = "%s.%s" % (enum, c)
                    if only_rx and not only_rx.search(subject):
                        continue
                    r.subjects += 1
                    r.skipped.append((subject, dyn[0], dyn[1]))
                continue
            owner = owners[enum]
            for c in consts:
                subject = "%s.%s" % (enum, c)
                if only_rx and not only_rx.search(subject):
                    continue
                r.subjects += 1
                main_sites = const_sites(main, enum, c, owner, graph)
                writes = [h for h in main_sites if h[2] == "write"]
                live = [h for h in writes if h[3]]
                if live:
                    r.ok.append((subject, "%s:%d" % (live[0][0], live[0][1])))
                    continue
                seed = seed_site(seeds, [tables.get(t) for t in owner.types if tables.get(t)], c)
                if seed:
                    r.ok.append((subject, "%s:%d (database seed)" % seed))
                    continue
                test_sites = const_sites(tests, enum, c, owner)
                tw = [h for h in test_sites if h[2] == "write"]
                if writes:
                    why = ("written ONLY by code no production entry point can reach -- "
                           "dead writer, the state still cannot occur")
                    ev = ["%s:%d  (write inside unreachable code)" % (h[0], h[1])
                          for h in writes[:3]]
                elif tw:
                    why = "written ONLY by tests -- production can never reach this state"
                    ev = ["%s:%d  (test write)" % (h[0], h[1]) for h in tw[:3]]
                elif main_sites:
                    if any(h[2] == "dto" for h in main_sites):
                        why = ("produced only into a DTO / response object, never onto the "
                               "entity -- no row can ever hold this state")
                    else:
                        why = "read but never written -- no code path assigns this state"
                    ev = ["%s:%d  (%s)" % (h[0], h[1],
                          "into a non-entity object, not a state write" if h[2] == "dto"
                          else h[2] + ", not a write") for h in main_sites[:3]]
                else:
                    why = "declared and referenced nowhere at all"
                    ev = ["%s/%s.java  (declaration only)"
                          % (ENUM_REL.replace(os.sep, "/"), enum)]
                r.findings.append((subject, why, ev))

    # ---- B. mutator reachability
    for entity, s in entity_srcs:
        for name, line, is_builder in entity_mutators(s, entity):
            subject = "%s#%s%s" % (entity, name, "()" if not is_builder else "() [Builder]")
            if only_rx and not only_rx.search("%s#%s" % (entity, name)):
                continue
            r.subjects += 1
            if is_builder and tables.get(entity) in seeds:
                # Every row of this table is created by a Flyway seed, so whether a
                # row-creation field is populated in production is a question about the SQL,
                # not about Java. A TRANSITION on an existing row (setActive, markX) is still
                # asserted below -- a seed inserts rows, it never transitions them.
                r.skipped.append((subject,
                                  "rows of `%s` are created by a database seed, not by Java"
                                  % tables.get(entity),
                                  seeds[tables.get(entity)][0][0]))
                continue
            hits = mutator_sites(main, entity, s.rel, name, is_builder, graph)
            hits = [h for h in hits if not (h[0] == s.rel and h[1] == line)]
            live = [h for h in hits if h[2]]
            if live:
                r.ok.append((subject, "%s:%d" % (live[0][0], live[0][1])))
                continue
            thits = mutator_sites(tests, entity, s.rel, name, is_builder)
            if hits:
                why = ("called ONLY from code no production entry point can reach -- "
                       "dead caller, the transition still cannot fire")
                ev = ["%s:%d  (call inside unreachable code)" % (h[0], h[1]) for h in hits[:3]]
            elif thits:
                why = "called ONLY by tests -- no production path invokes it"
                ev = ["%s:%d  (test call)" % (h[0], h[1]) for h in thits[:3]]
            else:
                why = "zero call sites anywhere -- the transition is unreachable"
                ev = ["%s:%d  (declaration)" % (s.rel, line)]
            r.findings.append((subject, why, ev))

    return r


# --------------------------------------------------------------------------------------
# built-in falsification fixtures

_GOOD_ENUM = "package com.influora.domain.enums;\npublic enum WidgetStatus { DRAFT, LIVE, CANCELLED }\n"
_GOOD_ENTITY = """package com.influora.domain.entity;
import com.influora.domain.enums.WidgetStatus;
import jakarta.persistence.Entity;
@Entity
public class Widget {
    private WidgetStatus status;
    private String note;
    public WidgetStatus getStatus() { return status; }
    public void setStatus(WidgetStatus status) { this.status = status; }
    public static Builder builder() { return new Builder(); }
    public static final class Builder {
        private final Widget w = new Widget();
        public Builder note(String note) { w.note = note; return this; }
        public Widget build() { w.status = WidgetStatus.DRAFT; return w; }
    }
}
"""
_CONTROLLER = """package com.influora.web;
import com.influora.service.WidgetService;
import org.springframework.web.bind.annotation.PostMapping;
public class WidgetController {
    private final WidgetService svc = new WidgetService();
    @PostMapping("/widgets")
    public void create() { svc.go(null, true); }
}
"""
_GOOD_SERVICE = """package com.influora.service;
import com.influora.domain.entity.Widget;
import com.influora.domain.enums.WidgetStatus;
public class WidgetService {
    public void go(Widget w, boolean cancel) {
        w.setStatus(cancel ? WidgetStatus.CANCELLED : WidgetStatus.LIVE);
        Widget made = Widget.builder().note("x").build();
    }
}
"""
# Known-GOOD #2: the write never touches the entity lexically at the call site -- the
# constant is an ARGUMENT to a service method that forwards it into the entity two hops
# away. This is the dominant idiom outside the ten hand-curated entities, and scoring it as
# "not a write" is what turns ~40 shipping states into false reds. Guards the sink closure.
_GOOD_SINK_SERVICE = """package com.influora.service;
import com.influora.domain.entity.Widget;
import com.influora.domain.enums.WidgetStatus;
public class WidgetService {
    public void go(Widget w, boolean cancel) {
        historyService.record(w.getId(), WidgetStatus.CANCELLED);
        historyService.record(w.getId(), WidgetStatus.LIVE);
    }
}
class HistoryService {
    public void record(String id, WidgetStatus state) {
        Widget made = Widget.builder().note(id).build();
        made.setStatus(state);
    }
}
"""
# The known-bad variants. Each is a shape that shipped nothing and used to exit 0.
_BAD_READ_ONLY = """package com.influora.service;
import com.influora.domain.entity.Widget;
import com.influora.domain.enums.WidgetStatus;
public class WidgetService {
    public boolean go(Widget w) {
        // "we should also set CANCELLED here one day"
        if (w.getStatus() == WidgetStatus.CANCELLED) { return true; }
        Widget made = Widget.builder().build();
        return repo.existsByStatusNot(WidgetStatus.CANCELLED);
    }
}
"""
_BAD_DEAD_WRITER = """package com.influora.service;
import com.influora.domain.entity.Widget;
import com.influora.domain.enums.WidgetStatus;
public class WidgetService {
    public void go(Widget w, boolean cancel) {
        Widget made = Widget.builder().note("x").build();
    }
    private void neverCalledByAnyone(Widget w) { w.setStatus(WidgetStatus.CANCELLED); }
    private WidgetStatus deadMapper() { return WidgetStatus.CANCELLED; }
}
"""
_BAD_DTO_WRITE = """package com.influora.service;
import com.influora.domain.entity.Widget;
import com.influora.domain.enums.WidgetStatus;
public class WidgetService {
    public void go(Widget w, boolean cancel) {
        WidgetView dto = new WidgetView();
        dto.setDisplayStatus(WidgetStatus.CANCELLED);
        Widget made = Widget.builder().note("x").build();
    }
}
"""
_BAD_THROWS_MUTATOR = """package com.influora.domain.entity;
import com.influora.domain.enums.WidgetStatus;
import jakarta.persistence.Entity;
@Entity
public class Widget {
    private WidgetStatus status;
    private String note;
    public WidgetStatus getStatus() { return status; }
    public void setStatus(WidgetStatus status) { this.status = status; }
    public void markCancelled(String reason) throws IllegalStateException {
        this.status = WidgetStatus.CANCELLED;
    }
    public Widget note2(String n) { this.note = n; return this; }
    public static Builder builder() { return new Builder(); }
    public static final class Builder {
        private final Widget w = new Widget();
        public Builder note(String note) { w.note = note; return this; }
        public Widget build() { w.status = WidgetStatus.DRAFT; return w; }
    }
}
"""
_EXTRA_ENTITY = """package com.influora.domain.entity;
import com.influora.domain.enums.RefundStatus;
import jakarta.persistence.Entity;
@Entity
public class Refund {
    private RefundStatus status;
    public RefundStatus getStatus() { return status; }
    public void setStatus(RefundStatus status) { this.status = status; }
    public void approve() { this.status = RefundStatus.APPROVED; }
}
"""
_EXTRA_ENUM = "package com.influora.domain.enums;\npublic enum RefundStatus { REQUESTED, APPROVED, DENIED }\n"


def _write(p, txt):
    d = os.path.dirname(p)
    if not os.path.isdir(d):
        os.makedirs(d)
    with open(p, "w", encoding="utf-8") as fh:
        fh.write(txt)


def _fixture(root, service_src, entity_src=None, extra=False):
    ent = os.path.join(root, ENTITY_REL)
    enm = os.path.join(root, ENUM_REL)
    svc = os.path.join(root, MAIN_REL, "com", "influora", "service")
    web = os.path.join(root, MAIN_REL, "com", "influora", "web")
    _write(os.path.join(enm, "WidgetStatus.java"), _GOOD_ENUM)
    _write(os.path.join(ent, "Widget.java"), entity_src or _GOOD_ENTITY)
    _write(os.path.join(svc, "WidgetService.java"), service_src)
    _write(os.path.join(web, "WidgetController.java"), _CONTROLLER)
    if extra:
        _write(os.path.join(enm, "RefundStatus.java"), _EXTRA_ENUM)
        _write(os.path.join(ent, "Refund.java"), _EXTRA_ENTITY)
    _write(os.path.join(root, TEST_REL, "com", "influora", "Keep.java"),
           "package com.influora;\nclass Keep {}\n")


CASES = [
    ("known-good", _GOOD_SERVICE, None, False, 0),
    ("known-good:sink", _GOOD_SINK_SERVICE, None, False, 0),
    ("bad:read-only", _BAD_READ_ONLY, None, False, 1),
    ("bad:dead-writer", _BAD_DEAD_WRITER, None, False, 1),
    ("bad:dto-write", _BAD_DTO_WRITE, None, False, 1),
    ("bad:throws+fluent", _GOOD_SERVICE, _BAD_THROWS_MUTATOR, False, 1),
    ("bad:uncurated", _GOOD_SERVICE, None, True, 1),
]


def self_test():
    """Prove the detector fails on every known-bad shape and passes on known-good."""
    global REQUIRED_ENTITIES
    saved = REQUIRED_ENTITIES
    REQUIRED_ENTITIES = ["Widget"]
    ok = True
    try:
        for label, svc, ent, extra, want in CASES:
            tmp = tempfile.mkdtemp(prefix="mfgate-")
            try:
                _fixture(tmp, svc, ent, extra)
                res = run(tmp)
            finally:
                shutil.rmtree(tmp, ignore_errors=True)   # leave no fixture behind
            if res.fatal:
                print("  %-18s FATAL %s" % (label, res.fatal))
                got = 2
            else:
                got = 1 if res.findings else 0
                print("  %-18s subjects=%d files=%d findings=%d -> exit %d (want %d)"
                      % (label, res.subjects, res.files_scanned, len(res.findings), got, want))
                for sub, why, _ev in res.findings:
                    print("      %-26s %s" % (sub, why))
            if got != want:
                ok = False
                print("      *** MISMATCH")
    finally:
        REQUIRED_ENTITIES = saved
    print("  falsification: every known-bad shape exits 1 and known-good exits 0 = %s" % ok)
    return 0 if ok else 1


# --------------------------------------------------------------------------------------


def main(argv):
    root = None
    only = None
    show_all = False
    show_entities = False
    i = 1
    while i < len(argv):
        a = argv[i]
        if a == "--only":
            i += 1
            if i >= len(argv):
                sys.stderr.write(USAGE + "\n")
                return 64
            only = argv[i]
        elif a == "--list":
            show_all = True
        elif a == "--entities":
            show_entities = True
        elif a == "--self-test":
            print("· missing-feature gate self-test (built-in fixtures)")
            return self_test()
        elif a in ("-h", "--help"):
            print(USAGE)
            return 0
        elif a.startswith("-"):
            sys.stderr.write("unknown option %s\n%s\n" % (a, USAGE))
            return 64
        elif root is None:
            root = a
        else:
            sys.stderr.write(USAGE + "\n")
            return 64
        i += 1

    here = os.path.dirname(os.path.abspath(__file__))
    if root is None:
        root = os.path.abspath(os.path.join(here, os.pardir, os.pardir))
    if not os.path.isdir(root):
        print("· repo root %s does not exist — UNAVAILABLE" % root)
        return 2

    only_rx = None
    if only is not None:
        try:
            only_rx = re.compile(only)
        except re.error as e:
            sys.stderr.write("bad --only regex: %s\n" % e)
            return 64

    print("· gate: missing-feature  (F-0403, F-0413, F-0414)")
    print("· root: %s" % root)

    res = run(root, only_rx)
    if res.fatal:
        print("· %s — UNAVAILABLE (this is not a pass)" % res.fatal)
        return 2
    if res.files_scanned == 0:
        print("· zero candidate files scanned — UNAVAILABLE (this is not a pass)")
        return 2
    if show_entities:
        print("· %d derived entities: %s" % (len(res.entities), ", ".join(res.entities)))
        return 0 if not res.findings else 1
    if res.subjects == 0:
        msg = "--only matched no subject" if only_rx else "zero subjects examined"
        print("· %s — UNAVAILABLE (this is not a pass)" % msg)
        return 2

    print("· scanned %d main .java files; examined %d subjects across %d derived entities"
          % (res.files_scanned, res.subjects, len(res.entities)))

    if res.skipped:
        seen = set()
        print("· NOT ASSERTED — %d subject(s) whose reachability is not decidable statically:"
              % len(res.skipped))
        for sub, reason, site in res.skipped:
            key = reason + site
            n = len([1 for s2 in res.skipped if s2[1] + s2[2] == key])
            if key in seen:
                continue
            seen.add(key)
            print("    %d constant(s): %s  (%s)" % (n, reason, site))

    if show_all:
        for sub, ev in res.ok:
            print("  ok   %-46s reached at %s" % (sub, ev))

    if not res.findings:
        print("· 0 unreachable states or mutators")
        print("VERDICT: clean — every domain state and mutator has a live production writer")
        print("NOT CHECKED: branch feasibility -- a writer inside a reachable method whose")
        print("             guard can never be true still counts here; states produced only")
        print("             by a hand-run SQL statement outside db/migration; HTTP-route gaps")
        print("             such as F-0414, which need a route-vs-lifecycle map, not a scan.")
        return 0

    print("· %d unreachable domain state(s)/mutator(s):" % len(res.findings))
    for sub, why, ev in res.findings:
        print("")
        print("  BROKEN  %s" % sub)
        print("          %s" % why)
        for line in ev:
            print("          %s" % line)
    print("")
    print("VERDICT: broken — %d declared state(s)/transition(s) no production path can reach"
          % len(res.findings))
    return 1


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv))
    except KeyboardInterrupt:
        sys.exit(2)
