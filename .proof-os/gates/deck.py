#!/usr/bin/env python3
"""gates/deck.py — origin: riya STEP 6 self-check, promoted to an executable gate.

The header of the 0.3.3 gate advertised three checks: "opens-cleanly, ≤3 core colors,
AA contrast". Two of them did not exist.

  * `lum()` and `ratio()` were defined and never called — verified by grep, only the
    definition lines matched. A deck with white text on a white shape passed.
  * `r.font.color.rgb` RAISES on a theme-coloured run, and a `hasattr` guard swallowed
    it, so a deck of twelve theme colours reported `distinct text colors: 0` and passed.
    Decks built from layout placeholders — i.e. most real decks — always passed.
  * tables, charts and grouped shapes were never entered: the loop required
    `sh.has_text_frame`, so 16 distinct cell colours reported 0.
  * every unavailable input — missing file, no args, a .md file, an empty file, random
    bytes — exited 1 with a traceback.
  * the message said "≤3 core" while the code failed only above 6.

This version resolves colour properly: explicit RGB, theme colours through the master's
clrMap and the theme part's clrScheme, and PowerPoint's lumMod/lumOff brightness. It
resolves each run's effective background — shape fill, then group ancestors, then the
slide's `<p:bg>`, then the layout's, then the master's `<p:bgRef>` — and measures WCAG
contrast against it. Anything it cannot resolve is NAMED, never counted as a pass.

LAW (false-red): python-pptx missing / file missing / not a .pptx / unreadable => exit 2.
                 exit 1 = a real defect in the deck.
Usage: gates/deck.py <deck.pptx> [--max-colors N]
"""
import os as _o, sys as _s
_s.path.insert(0, _o.path.dirname(_o.path.abspath(__file__)))
try:
    from _rc import rc_init; rc_init("deck")   # F-0026: liveness is read, not inferred
except Exception:
    pass

import os, sys, zipfile

USAGE = "usage: gates/deck.py <deck.pptx> [--max-colors N]"

# WCAG 2.1 AA
AA_BODY, AA_LARGE = 4.5, 3.0
MAX_COLORS = 6           # 3 core (60/30/10) + one shade each; printed on every run

BLIND = [
    "images and picture fills: text set over a photograph has no resolvable background "
    "colour and its contrast is not measured here",
    "gradient and patterned fills: only the solid case is resolved",
    "per-series, per-point, axis and data-label colour overrides inside embedded charts "
    "(the chart-level text colour is read)",
    "layout, spacing, hierarchy, and whether the deck says anything worth reading",
    "runs whose colour or size is inherited from a placeholder's list style rather than "
    "stated on the run (counted and reported as unresolved, never as a pass)",
]


def emit(extra=()):
    print("NOT CHECKED: " + " | ".join(list(extra) + BLIND))


def die(code, msg, extra=()):
    print(msg)
    emit(extra)
    sys.exit(code)


# ---------------------------------------------------------------- contrast math
def lum(rgb):
    c = [x / 255 for x in rgb]
    c = [x / 12.92 if x <= .03928 else ((x + .055) / 1.055) ** 2.4 for x in c]
    return .2126 * c[0] + .7152 * c[1] + .0722 * c[2]


def ratio(a, b):
    L = sorted([lum(a), lum(b)], reverse=True)
    return (L[0] + .05) / (L[1] + .05)


# ---------------------------------------------------------------- arguments
paths, i, argv = [], 0, sys.argv[1:]
while i < len(argv):
    a = argv[i]
    if a == "--max-colors":
        if i + 1 >= len(argv) or argv[i + 1].startswith("--"):
            die(64, f"· --max-colors needs a number\n{USAGE}",
                ["everything: the gate never ran"])
        try:
            MAX_COLORS = int(argv[i + 1])
        except ValueError:
            die(64, f"· --max-colors {argv[i+1]!r} is not an integer\n{USAGE}",
                ["everything: the gate never ran"])
        i += 2
        continue
    if a.startswith("--"):
        die(64, f"· unknown option {a}\n{USAGE}", ["everything: the gate never ran"])
    paths.append(a)
    i += 1

if not paths:
    die(2, f"· no deck given — zero inputs is nothing checked, not a pass (unavailable)\n"
           f"{USAGE}", ["every slide: no file was named"])
deck = paths[0]
if os.path.isdir(deck):
    die(2, f"· {deck} is a directory, not a deck — unavailable",
        ["every slide: the input was not a file"])
if not os.path.isfile(deck):
    die(2, f"· {deck} not found — unavailable", ["every slide: the deck could not be read"])
if os.path.getsize(deck) == 0:
    die(2, f"· {deck} is empty (0 bytes) — unavailable",
        ["every slide: the deck is empty"])
if not zipfile.is_zipfile(deck):
    die(2, f"· {deck} is not an Office Open XML package (a .pptx is a zip; this is not) "
           f"— unavailable", ["every slide: the file is not a .pptx"])

try:
    from pptx import Presentation
    from pptx.enum.dml import MSO_FILL, MSO_COLOR_TYPE
    from pptx.enum.shapes import MSO_SHAPE_TYPE
except ImportError as e:
    die(2, f"· python-pptx unavailable ({e}) — oracle missing, not passing",
        ["every slide: the parser is not installed here"])

try:
    prs = Presentation(deck)          # opens cleanly or throws = check 1
except Exception as e:
    die(2, f"· {deck} could not be opened as a presentation: "
           f"{type(e).__name__}: {e} — unavailable",
        ["every slide: the deck did not open"])

# ---------------------------------------------------------------- theme resolution
A = "{http://schemas.openxmlformats.org/drawingml/2006/main}"
P = "{http://schemas.openxmlformats.org/presentationml/2006/main}"
SLOTS = ("dk1", "lt1", "dk2", "lt2", "accent1", "accent2", "accent3", "accent4",
         "accent5", "accent6", "hlink", "folHlink")
THEME_NAME_TO_SLOT = {
    "DARK_1": "dk1", "LIGHT_1": "lt1", "DARK_2": "dk2", "LIGHT_2": "lt2",
    "ACCENT_1": "accent1", "ACCENT_2": "accent2", "ACCENT_3": "accent3",
    "ACCENT_4": "accent4", "ACCENT_5": "accent5", "ACCENT_6": "accent6",
    "HYPERLINK": "hlink", "FOLLOWED_HYPERLINK": "folHlink",
    "TEXT_1": "tx1", "TEXT_2": "tx2", "BACKGROUND_1": "bg1", "BACKGROUND_2": "bg2",
}
_theme_cache = {}


def _color_from_elem(el):
    """<a:srgbClr val=..> / <a:sysClr lastClr=..> -> (r,g,b), else None."""
    if el is None:
        return None
    s = el.find(f"{A}srgbClr")
    if s is not None and s.get("val"):
        return _hex(s.get("val"))
    y = el.find(f"{A}sysClr")
    if y is not None and y.get("lastClr"):
        return _hex(y.get("lastClr"))
    return None


def _hex(h):
    h = str(h).strip().lstrip("#")
    if len(h) != 6:
        return None
    try:
        return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16))
    except ValueError:
        return None


def theme_of(master):
    """(scheme {slot:(r,g,b)}, clrmap {name:slot}) for a slide master."""
    key = id(master)
    if key in _theme_cache:
        return _theme_cache[key]
    scheme, clrmap = {}, {}
    try:
        for rel in master.part.rels.values():
            if rel.reltype.endswith("/theme") and not rel.is_external:
                from lxml import etree
                x = etree.fromstring(rel.target_part.blob)
                cs = x.find(f".//{A}clrScheme")
                if cs is not None:
                    for slot in SLOTS:
                        rgb = _color_from_elem(cs.find(f"{A}{slot}"))
                        if rgb:
                            scheme[slot] = rgb
                break
        cm = master._element.find(f"{P}clrMap")
        if cm is not None:
            clrmap = dict(cm.attrib)
    except Exception:
        pass
    _theme_cache[key] = (scheme, clrmap)
    return scheme, clrmap


def scheme_rgb(master, name):
    """`name` is a theme slot or a clrMap key (bg1/tx1/...)."""
    scheme, clrmap = theme_of(master)
    slot = clrmap.get(name, name)
    slot = clrmap.get(slot, slot) if slot not in scheme else slot
    return scheme.get(slot)


def apply_brightness(rgb, b):
    """PowerPoint lumMod/lumOff, as python-pptx surfaces it on ColorFormat.brightness."""
    if not rgb or not b:
        return rgb
    if b > 0:
        return tuple(int(round(c + (255 - c) * b)) for c in rgb)
    return tuple(int(round(c * (1 + b))) for c in rgb)


def color_identity(color, master):
    """(label, rgb_or_None, reason_or_None). Never raises on a theme colour — that
    AttributeError, swallowed by hasattr(), is how 0.3.3 saw zero colours."""
    if color is None:
        return None, None, "run states no colour"
    try:
        ctype = color.type
    except Exception:
        return None, None, "run colour is unreadable"
    if ctype is None:
        return None, None, "run colour is inherited, not stated"
    try:
        b = color.brightness
    except Exception:
        b = 0.0
    if ctype == MSO_COLOR_TYPE.RGB:
        try:
            rgb = _hex(str(color.rgb))
        except Exception:
            return None, None, "run declares an RGB colour that cannot be read"
        rgb = apply_brightness(rgb, b)
        return (("#%02X%02X%02X" % rgb) if rgb else "RGB?"), rgb, None
    if ctype == MSO_COLOR_TYPE.SCHEME:
        try:
            nm = color.theme_color.name
        except Exception:
            return "theme:?", None, "run uses a theme colour this build cannot name"
        rgb = apply_brightness(scheme_rgb(master, THEME_NAME_TO_SLOT.get(nm, nm.lower())), b)
        label = f"theme:{nm}" + (f"({'#%02X%02X%02X' % rgb})" if rgb else "")
        return label, rgb, (None if rgb else f"theme colour {nm} is not in the theme part")
    return f"{ctype}", None, f"colour type {ctype} cannot be resolved to RGB"


# ---------------------------------------------------------------- background chain
def fill_rgb(fill, master):
    """(rgb, reason). reason is set when the fill exists but is not a solid colour."""
    try:
        ft = fill.type
    except Exception:
        return None, "fill unreadable"
    if ft is None or ft == MSO_FILL.BACKGROUND:
        return None, None                      # inherit — keep walking
    if ft != MSO_FILL.SOLID:
        return None, f"{ft} fill"
    _, rgb, why = color_identity(fill.fore_color, master)
    return rgb, why


def bg_from_element(el, master):
    """<p:bg> on a slide/layout/master: solid fill or <p:bgRef>."""
    if el is None:
        return None, None
    bg = el.find(f".//{P}bg")
    if bg is None:
        return None, None
    pr = bg.find(f"{P}bgPr")
    if pr is not None:
        solid = pr.find(f"{A}solidFill")
        if solid is not None:
            rgb = _color_from_elem(solid)
            if rgb:
                return rgb, None
            sc = solid.find(f"{A}schemeClr")
            if sc is not None and sc.get("val"):
                return scheme_rgb(master, sc.get("val")), None
            return None, "slide background is a non-solid fill"
        return None, "slide background is a non-solid fill"
    ref = bg.find(f"{P}bgRef")
    if ref is not None:
        rgb = _color_from_elem(ref)
        if rgb:
            return rgb, None
        sc = ref.find(f"{A}schemeClr")
        if sc is not None and sc.get("val"):
            r = scheme_rgb(master, sc.get("val"))
            return r, (None if r else "background references a theme slot not in the theme")
    return None, None


def background_for(shape, ancestors, slide):
    """shape fill -> group ancestors -> slide bg -> layout bg -> master bg."""
    master = slide.slide_layout.slide_master
    for sh in [shape] + list(reversed(ancestors)):
        try:
            rgb, why = fill_rgb(sh.fill, master)
        except (AttributeError, NotImplementedError, ValueError):
            continue
        if rgb:
            return rgb, None
        if why:
            return None, why
    for el, what in ((slide._element, "slide"),
                     (slide.slide_layout._element, "layout"),
                     (master._element, "master")):
        rgb, why = bg_from_element(el, master)
        if rgb:
            return rgb, None
        if why:
            return None, f"{what}: {why}"
    return None, "no solid background anywhere in shape -> slide -> layout -> master"


# ---------------------------------------------------------------- shape walk
def text_frames(shapes, ancestors=()):
    """Yield (text_frame, owning_shape, ancestors). 0.3.3 entered only
    `sh.has_text_frame`, so tables, charts and groups were invisible."""
    for sh in shapes:
        try:
            st = sh.shape_type
        except Exception:
            st = None
        if st == MSO_SHAPE_TYPE.GROUP:
            for item in text_frames(sh.shapes, tuple(ancestors) + (sh,)):
                yield item
            continue
        if getattr(sh, "has_table", False):
            for row in sh.table.rows:
                for cell in row.cells:
                    yield cell.text_frame, sh, ancestors
            continue
        if getattr(sh, "has_chart", False):
            yield None, sh, ancestors           # charts are declared, not measured
            continue
        if sh.has_text_frame:
            yield sh.text_frame, sh, ancestors


def size_pt(run, para):
    for src in (run.font, para.font):
        try:
            if src.size is not None:
                return src.size.pt
        except Exception:
            pass
    return None


colors, fails, extra = set(), [], []
runs_total = runs_colored = 0
measured = 0
unresolved_bg = {}
unresolved_fg = {}
charts = 0

for idx, slide in enumerate(prs.slides, 1):
    master = slide.slide_layout.slide_master
    for tf, shape, anc in text_frames(slide.shapes):
        if tf is None:
            # A chart carries a chart-level text colour that IS readable; its per-series
            # and per-datalabel overrides are not, and are declared below.
            charts += 1
            try:
                label, fg, _ = color_identity(shape.chart.font.color, master)
            except Exception:
                label, fg = None, None
            if label:
                colors.add(label)
                runs_total += 1
                runs_colored += 1
                bg, why_bg = background_for(shape, anc, slide)
                if bg is None:
                    unresolved_bg[why_bg or "unknown"] = unresolved_bg.get(why_bg or "unknown", 0) + 1
                else:
                    measured += 1
                    got = ratio(fg, bg) if fg else None
                    if got is not None and got + 1e-9 < AA_BODY:
                        fails.append(
                            f"slide {idx}: chart text {label} on #{'%02X%02X%02X' % bg} "
                            f"= {got:.2f}:1, WCAG AA needs {AA_BODY}:1 — unreadable")
            continue
        for para in tf.paragraphs:
            for run in para.runs:
                if not run.text.strip():
                    continue
                runs_total += 1
                label, fg, why_fg = color_identity(run.font.color, master)
                if label:
                    colors.add(label)
                    runs_colored += 1
                if fg is None:
                    unresolved_fg[why_fg or "unknown"] = unresolved_fg.get(why_fg or "unknown", 0) + 1
                    continue
                bg, why_bg = background_for(shape, anc, slide)
                if bg is None:
                    unresolved_bg[why_bg or "unknown"] = unresolved_bg.get(why_bg or "unknown", 0) + 1
                    continue
                measured += 1
                pt = size_pt(run, para)
                large = pt is not None and (pt >= 18 or (pt >= 14 and run.font.bold))
                need = AA_LARGE if large else AA_BODY
                got = ratio(fg, bg)
                if got + 1e-9 < need:
                    fails.append(
                        f"slide {idx}: “{run.text.strip()[:40]}” {label} on "
                        f"#{'%02X%02X%02X' % bg} = {got:.2f}:1, WCAG AA needs {need}:1 "
                        f"({'large' if large else 'body'} text"
                        f"{'' if pt is None else f', {pt:g}pt'}) — unreadable")

print(f"opens: yes · {len(prs.slides)} slide(s) · {runs_total} text run(s) · "
      f"distinct text colors: {len(colors)} (enforced: ≤{MAX_COLORS}) · "
      f"contrast measured on {measured}/{runs_total} run(s)")
if colors:
    print("   colors:", ", ".join(sorted(colors)))

if len(colors) > MAX_COLORS:
    fails.append(f"{len(colors)} distinct text colors — the enforced ceiling is "
                 f"{MAX_COLORS} (60/30/10 wants 3 core plus shades): "
                 f"{', '.join(sorted(colors))}")

for why, n in sorted(unresolved_fg.items()):
    extra.append(f"the colour of {n} run(s): {why}")
for why, n in sorted(unresolved_bg.items()):
    extra.append(f"the contrast of {n} run(s): {why}")
if charts:
    extra.append(f"{charts} chart shape(s): the chart-level text colour was read, but "
                 f"per-series, per-point, axis and data-label colour overrides inside "
                 f"the chart part were not")

for x in fails:
    print("  ", x)
emit(extra)

if fails:
    sys.exit(1)
if runs_total == 0:
    print("· the deck contains no text runs — nothing to check (unavailable)")
    sys.exit(2)
if measured == 0:
    print(f"· contrast could not be measured on ANY of {runs_total} run(s) — this gate "
          f"advertises AA contrast and did not verify it here (unavailable, not green)")
    sys.exit(2)
sys.exit(0)
