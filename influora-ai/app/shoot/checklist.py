"""Code-side "Quick checks" for a photo check (spec v2 2026-09-26, Phase 4).

The measurable part of the framing pairs, computed from the face and product boxes that
`app/prompt/frame_check.py` has already validated (`normalize_box`: finite shares 0..1 of the
unmirrored photo as sent). There are no new AI fields and no model text: every line is a fixed
template in `CHECK_LINES`, picked by a deterministic rule below, and every threshold is either
the safe-zone config (`app/shoot/safe_zones.json`) or `ChecklistThresholds`.

| Check id              | Rule                                                                  |
|-----------------------|-----------------------------------------------------------------------|
| head_at_top_edge      | `framing_advice` (a port of the app's `framingVerdict`) on the largest |
|                       | face, with the shot's target, says "lower-phone".                     |
| too_much_headroom     | CU/MCU/MS only: the largest face's eye line (top + 0.4 x height) is    |
|                       | below `eye_line_max` of the 9:16 crop.                                |
| face_at_edge          | The face is within `side` of either edge, or overlaps the rail.       |
| face_in_caption_zone  | The face reaches below the caption line (this covers "crosses the     |
|                       | caption line" and "overlaps the covered bottom", and a face sitting   |
|                       | in the short-CTA band between them).                                  |
| face_in_top_bar       | CU/MCU/MS only: the face overlaps the top area (above `top`).          |
| product_hidden        | The shot has a prop, the model looked (a layout came back) and said   |
|                       | product null. A product box that failed validation is not "hidden".   |
| product_low           | The product box's centre is below the caption line of the crop.       |
| product_too_close     | Product area > `product_area_max` or height > `product_h_max`.        |
| product_over_face     | The product box overlaps the largest face.                            |

- Zone rules (headroom, edge, caption, top bar, product low) are judged on the 9:16 crop the
  app's Reel layout guide shows (`reel_crop`, spec Phase 4 step 1), so they need the photo's
  pixel size (`photo_size`, read from the JPEG/PNG header). Unknown size -> those rules are
  skipped. The other rules use the photo as sent.
- Every face rule says "your face" / "your head", so it reads the LARGEST face and is skipped
  when `others_in_frame` is yes: we never guess which face is the creator's.
- The shot size for the size-gated rules (`shot_size`) is the size the app's live camera guide
  draws for the same set-up: the size words first (`SHOT_SIZE_RULES`, a port of beat-to-shot.ts
  `explicitShotSize`, pinned by a pytest), so a "medium long" or "full shot" beat is MLS / FS here
  too and never gets the top-bar line the guide's own head position would trigger. With no size
  word, the target decides: closeup -> CU, medium -> MS, wide -> FS. No set-up sent, or an
  overhead hands shot -> the size-gated rules are skipped. The TARGET (`shot_target`, the app's
  `targetForShot` words) still drives `framing_advice`, like the app's checker.
- The backlight check ("bright light behind your face") is phone-only: this service has no
  image library, so it is not here.
"""

from __future__ import annotations

import math
import re
import struct
from dataclasses import dataclass

from app.shoot.safe_zones import SafeZones, get_safe_zones

Box = dict[str, float]  # {"x", "y", "w", "h"}: shares 0..1, (x, y) the top-left corner


@dataclass(frozen=True)
class ChecklistThresholds:
    """Every checklist cutoff that is not a safe zone. The framing ones mirror the app's
    `src/lib/shoot-check/metrics.ts` THRESHOLDS (a pytest pins the two to each other)."""

    # metrics.ts: FACE_CLOSEUP_MIN_FRACTION, FACE_MEDIUM_MIN/MAX_FRACTION, FACE_WIDE_MAX_FRACTION.
    face_closeup_min: float = 0.35
    face_medium_min: float = 0.15
    face_medium_max: float = 0.45
    face_wide_max: float = 0.2
    # metrics.ts: HEADROOM_MIN, HEADROOM_MAX, CENTER_TOLERANCE.
    headroom_min: float = 0.05
    headroom_max: float = 0.22
    center_tolerance: float = 0.12
    # The eye line: box top + eye_factor x box height; "a lot of empty space" when it is below
    # eye_line_max of the crop (about y 806 of 1920, a margin under the upper golden line).
    eye_factor: float = 0.4
    eye_line_max: float = 0.42
    # "Very close to the lens".
    product_area_max: float = 0.35
    product_h_max: float = 0.6


THRESHOLDS = ChecklistThresholds()

# The checks in the order the creator reads them (spec Phase 4 table order).
CHECK_IDS: tuple[str, ...] = (
    "head_at_top_edge",
    "too_much_headroom",
    "face_at_edge",
    "face_in_caption_zone",
    "face_in_top_bar",
    "product_hidden",
    "product_low",
    "product_too_close",
    "product_over_face",
)

# The fixed lines. English is the spec's approved text (Phase 4 table). The "hi" lines are
# Hinglish in Latin script, like every other frame-check line (frame_check_render.OK_LINES) --
# PENDING REVIEW, the same review as the 5.3 notices.
CHECK_LINES: dict[str, dict[str, str]] = {
    "head_at_top_edge": {
        "en": "Your head is at the top edge.",
        "hi": "Aapka head frame ke top edge pe hai.",
    },
    "too_much_headroom": {
        "en": "There's a lot of empty space above your head.",
        "hi": "Aapke head ke upar bahut khaali jagah hai.",
    },
    "face_at_edge": {
        "en": "Your face is near the edge; the app's buttons can cover it.",
        "hi": "Aapka face edge ke paas hai; app ke buttons use cover kar sakte hain.",
    },
    "face_in_caption_zone": {
        "en": "Your face sits where the app's captions go.",
        "hi": "Aapka face wahan hai jahan app ke captions aate hain.",
    },
    "face_in_top_bar": {
        "en": "Your face sits where the app's top bar goes.",
        "hi": "Aapka face wahan hai jahan app ka top bar aata hai.",
    },
    "product_hidden": {
        "en": "I can't see the product. Hold it up with the label toward the lens.",
        "hi": "Mujhe product nahi dikh raha. Use upar uthao, label lens ki taraf.",
    },
    "product_low": {
        "en": "The product is low, where captions and buttons go.",
        "hi": "Product neeche hai, jahan captions aur buttons aate hain.",
    },
    "product_too_close": {
        "en": "The product is very close to the lens.",
        "hi": "Product lens ke bahut paas hai.",
    },
    "product_over_face": {
        "en": "The product is covering your face.",
        "hi": "Product aapke face ko cover kar raha hai.",
    },
}

# --- the shot's target and size ---------------------------------------------------------------

# The app's ShotTarget values (src/lib/shoot-check/metrics.ts).
SHOT_TARGETS: tuple[str, ...] = ("closeup", "medium", "wide", "hands-overhead")
# closeup -> CU, medium -> MS, wide -> FS (spec Phase 4); hands-overhead has no face size.
SIZE_FOR_TARGET: dict[str, str] = {"closeup": "CU", "medium": "MS", "wide": "FS"}
# The talking sizes: the eye-line and top-bar rules apply only to these. For MLS, FS and LS the
# head is near the top by geometry, so flagging it would contradict the guide.
TALKING_SIZES: frozenset[str] = frozenset({"CU", "MCU", "MS"})

# The same words, in the same order, as the app's `targetForShot` (beat-to-shot.ts).
_CLOSE_RE = re.compile(r"close", re.IGNORECASE)
_OVERHEAD_RE = re.compile(r"overhead|top.?down|hands", re.IGNORECASE)
_WIDE_RE = re.compile(r"wide|full body", re.IGNORECASE)


# The app's `SHOT_SIZE_RULES` (beat-to-shot.ts), same sources, same order, first match wins. The
# live camera guide sizes a beat with these, so the size-gated checks must too (a pytest reads the
# TS table and compares). JavaScript's /i is re.IGNORECASE; these patterns mean the same in both.
SHOT_SIZE_RULES: tuple[tuple[str, str], ...] = (
    (r"extreme[\s-]*close", "ECU"),
    (r"medium[\s-]*close", "MCU"),
    (r"close", "CU"),
    (r"medium[\s-]*long|3\/4|knees", "MLS"),
    (r"medium|waist", "MS"),
    (r"full[\s-]*(?:body|shot)", "FS"),
    (r"wide|establishing|\blong\b", "LS"),
    (r"overhead|top.?down|hands", "OVERHEAD"),
)
_SHOT_SIZE_RES = tuple((re.compile(src, re.IGNORECASE), size) for src, size in SHOT_SIZE_RULES)


def _setup_text(shot_context: dict[str, str] | None) -> str:
    """The set-up text the app reads: the beat's shot line, else angle and action."""
    if not shot_context:
        return ""
    return shot_context.get("line") or " ".join(
        v for v in (shot_context.get("angle"), shot_context.get("action")) if v
    )


def explicit_shot_size(text: str) -> str | None:
    """The app's `explicitShotSize`: the first size whose words are in `text`, else None."""
    for pattern, size in _SHOT_SIZE_RES:
        if pattern.search(text):
            return size
    return None


def shot_size(shot_context: dict[str, str] | None) -> str | None:
    """The size the live camera guide draws for this set-up (its `shotSizeFor` on the line): the
    size words first, else the target's size (`SIZE_FOR_TARGET`); None with no set-up or for an
    overhead shot, so the size-gated rules are skipped."""
    text = _setup_text(shot_context)
    if not text.strip():
        return None
    size = explicit_shot_size(text)
    if size is not None:
        return None if size == "OVERHEAD" else size
    return SIZE_FOR_TARGET.get(shot_target(shot_context) or "")


def shot_target(shot_context: dict[str, str] | None) -> str | None:
    """The shot's target from its set-up text (the beat's shot line, else angle and action), read
    exactly like the app's `targetForShot`; None when no set-up came with the check. The text is
    untrusted, but only these word matches are read from it."""
    text = _setup_text(shot_context)
    if not text.strip():
        return None
    if _CLOSE_RE.search(text):
        return "closeup"
    if _OVERHEAD_RE.search(text):
        return "hands-overhead"
    if _WIDE_RE.search(text):
        return "wide"
    return "medium"


# A set-up's `prop` that says there is none.
_NO_PROP = frozenset({"", "none", "no", "no prop", "nothing", "n/a", "na", "-", "nil"})


def needs_product(shot_context: dict[str, str] | None) -> bool:
    """True when the set-up names a prop (`shot_context.prop`), so the product should be seen.
    (A `place_prop` step is the other source in the spec; that step kind does not exist yet.)"""
    prop = (shot_context or {}).get("prop") or ""
    return " ".join(prop.split()).casefold().strip(".") not in _NO_PROP


# --- framing (a port of the app's framingVerdict) ---------------------------------------------


def framing_advice(face: Box | None, target: str, t: ChecklistThresholds = THRESHOLDS) -> str:
    """The app's `framingVerdict(face, frame, target).advice`, on a box in shares of the photo
    (the same ratios the app computes from pixels): size first, then headroom, then centring."""
    if target == "hands-overhead":
        return "point-at-hands" if face else "ok"
    if not face:
        return "no-face"
    low, high = {
        "closeup": (t.face_closeup_min, math.inf),
        "medium": (t.face_medium_min, t.face_medium_max),
        "wide": (0.0, t.face_wide_max),
    }.get(target, (0.0, math.inf))
    if face["h"] < low:
        return "come-closer"
    if face["h"] > high:
        return "step-back"
    if face["y"] > t.headroom_max:
        return "lift-phone"
    if face["y"] < t.headroom_min:
        return "lower-phone"
    centre = face["x"] + face["w"] / 2
    if centre < 0.5 - t.center_tolerance:
        return "move-right"
    if centre > 0.5 + t.center_tolerance:
        return "move-left"
    return "ok"


# --- the 9:16 crop (spec Phase 4 step 1) ------------------------------------------------------

REEL_ASPECT = 9 / 16
# A photo whose width / height is within this of 9/16 is used whole (the app's test, exactly).
ASPECT_TOLERANCE = 0.005
CROP_STEP = 0.01
# A tall photo's window puts the largest face's eye line nearest this share of the crop (the
# middle of the approved eye line, y 640 to 733 of 1920).
EYE_LINE_TARGET = 0.36
# A box less than this share inside the crop is dropped; the rest is clipped.
MIN_SHARE_INSIDE = 0.5
_EPS = 1e-9


@dataclass(frozen=True)
class Crop:
    """The 9:16 window, in shares of the photo."""

    x: float
    y: float
    w: float
    h: float


FULL_FRAME = Crop(0.0, 0.0, 1.0, 1.0)


def _area(box: Box) -> float:
    return box["w"] * box["h"]


def largest(boxes: list[Box]) -> Box | None:
    """The largest box by area; the first on a tie."""
    best: Box | None = None
    for box in boxes:
        if best is None or _area(box) > _area(best):
            best = box
    return best


def _inside(box: Box, axis: str, start: float, size: float) -> bool:
    lo, span = (box["x"], box["w"]) if axis == "x" else (box["y"], box["h"])
    return lo >= start - _EPS and lo + span <= start + size + _EPS


def _window_starts(end: float) -> list[float]:
    """Window starts from 0 to `end` in 1% steps, plus `end` itself when the steps miss it (the
    window flush with the right or bottom edge). The app's `windowStarts`, step for step."""
    out: list[float] = []
    k = 0
    while k * CROP_STEP <= end + _EPS:
        # floor(x + 0.5) is JavaScript's Math.round; Python's round() goes half to even.
        out.append(min(math.floor(k * CROP_STEP * 1e6 + 0.5) / 1e6, end))
        k += 1
    if not out or abs(out[-1] - end) > _EPS:
        out.append(end)
    return out


def reel_crop(photo_size: tuple[int, int] | None, faces: list[Box], product: Box | None) -> Crop | None:
    """The 9:16 window the Reel layout guide shows, or None when the photo's size is unknown.

    A port of the app's `cropTo916` (reel-layout.ts); both read the shared fixture
    `src/lib/__fixtures__/reel-crop-cases.json`, so the checks are judged on the window the guide
    draws. Wider than 9:16: slide a full-height window left to right in 1% steps (plus the window
    flush with the right edge) and score it (+1 per face fully inside, +2 when the product is
    fully inside); the best score wins, a tie goes to the more central window. Taller: the same
    vertically, and a tie first goes to the window that puts the largest face's eye line nearest
    `EYE_LINE_TARGET`. Within `ASPECT_TOLERANCE` of 9:16 (on width / height, like the app): the
    whole photo. Fixed order, so the same input always gives the same window."""
    if not photo_size:
        return None
    width, height = photo_size
    if width <= 0 or height <= 0:
        return None
    aspect = width / height
    if abs(aspect - REEL_ASPECT) <= ASPECT_TOLERANCE:
        return FULL_FRAME
    wide = aspect > REEL_ASPECT
    axis, size = ("x", (height * REEL_ASPECT) / width) if wide else ("y", width / REEL_ASPECT / height)
    face = largest(faces)
    best: tuple[float, float, float, float] | None = None  # (start, score, tie, central)
    for start in _window_starts(1.0 - size):
        score = sum(1 for f in faces if _inside(f, axis, start, size))
        if product is not None and _inside(product, axis, start, size):
            score += 2
        central = abs(start + size / 2 - 0.5)
        if not wide and face is not None:
            tie = abs((face["y"] + THRESHOLDS.eye_factor * face["h"] - start) / size - EYE_LINE_TARGET)
        else:
            tie = central
        # The app's comparison exactly: a higher score, else a tie smaller by more than _EPS,
        # else an equal tie and a more central window.
        if (
            best is None
            or score > best[1]
            or (score == best[1] and tie < best[2] - _EPS)
            or (score == best[1] and abs(tie - best[2]) <= _EPS and central < best[3] - _EPS)
        ):
            best = (start, float(score), tie, central)
    start = best[0] if best is not None else 0.0
    if axis == "x":
        return Crop(start, 0.0, size, 1.0)
    return Crop(0.0, start, 1.0, size)


def to_crop(box: Box | None, crop: Crop | None) -> Box | None:
    """`box` in shares of the crop, clipped to it; None when less than half of it is inside."""
    if box is None or crop is None:
        return None
    x0, x1 = max(box["x"], crop.x), min(box["x"] + box["w"], crop.x + crop.w)
    y0, y1 = max(box["y"], crop.y), min(box["y"] + box["h"], crop.y + crop.h)
    if x1 <= x0 or y1 <= y0 or _area(box) <= 0:
        return None
    if (x1 - x0) * (y1 - y0) / _area(box) < MIN_SHARE_INSIDE:
        return None
    if crop == FULL_FRAME:
        return box
    return {
        "x": (x0 - crop.x) / crop.w, "y": (y0 - crop.y) / crop.h,
        "w": (x1 - x0) / crop.w, "h": (y1 - y0) / crop.h,
    }


def _overlaps(a: Box, b: Box) -> bool:
    """True when the two boxes share some area. Touching edges do not count, even when float
    sums land a hair apart (0.4 + 0.2 is 0.6000000000000001)."""
    return (
        min(a["x"] + a["w"], b["x"] + b["w"]) - max(a["x"], b["x"]) > _EPS
        and min(a["y"] + a["h"], b["y"] + b["h"]) - max(a["y"], b["y"]) > _EPS
    )


def rail_box(zones: SafeZones) -> Box:
    """The right-side button rail as a box in shares of the 9:16 frame."""
    return {
        "x": zones.rail_x, "y": zones.rail_y_from,
        "w": 1.0 - zones.rail_x, "h": zones.rail_y_to - zones.rail_y_from,
    }


# --- the rules ---------------------------------------------------------------------------------


def run_checks(
    faces: list[Box],
    product: Box | None,
    *,
    product_said_none: bool = False,
    needs_product: bool = False,
    target: str | None = None,
    size: str | None = None,
    others_in_frame: bool = False,
    photo_size: tuple[int, int] | None = None,
    zones: SafeZones | None = None,
    thresholds: ChecklistThresholds = THRESHOLDS,
) -> list[str]:
    """The check ids that fire, in `CHECK_IDS` order. Pure: boxes and config in, ids out.

    `faces` and `product` are validated boxes of the photo as sent; `product_said_none` is True
    only when the model returned `product: null` (not when its box failed validation). `size` is
    the guide's shot size (`shot_size`); None falls back to the target's size."""
    zones = zones or get_safe_zones()
    t = thresholds
    size = size or SIZE_FOR_TARGET.get(target or "")
    talking = size in TALKING_SIZES
    face = None if others_in_frame else largest(faces)
    crop = reel_crop(photo_size, faces, product)
    crop_face = to_crop(face, crop)
    crop_product = to_crop(product, crop)

    fired: set[str] = set()
    if face is not None and size is not None and framing_advice(face, target or "", t) == "lower-phone":
        fired.add("head_at_top_edge")
    if crop_face is not None:
        if talking and crop_face["y"] + t.eye_factor * crop_face["h"] > t.eye_line_max:
            fired.add("too_much_headroom")
        if (
            crop_face["x"] < zones.side
            or crop_face["x"] + crop_face["w"] > 1.0 - zones.side
            or _overlaps(crop_face, rail_box(zones))
        ):
            fired.add("face_at_edge")
        if crop_face["y"] + crop_face["h"] > zones.caption_line:
            fired.add("face_in_caption_zone")
        if talking and crop_face["y"] < zones.top:
            fired.add("face_in_top_bar")
    if needs_product and product is None and product_said_none:
        fired.add("product_hidden")
    if crop_product is not None and crop_product["y"] + crop_product["h"] / 2 > zones.caption_line:
        fired.add("product_low")
    if product is not None and (
        _area(product) > t.product_area_max or product["h"] > t.product_h_max
    ):
        fired.add("product_too_close")
    if product is not None and face is not None and _overlaps(product, face):
        fired.add("product_over_face")
    return [cid for cid in CHECK_IDS if cid in fired]


def render_checks(ids: list[str], lang: str) -> list[str]:
    """The fixed lines for `ids` in `lang` ("en" or "hi"; anything else is "en"). Unknown ids
    are dropped, so nothing but a `CHECK_LINES` string can ever come out."""
    key = lang if lang in ("en", "hi") else "en"
    return [CHECK_LINES[cid][key] for cid in ids if cid in CHECK_LINES]


# --- the photo's pixel size, from its header (no image library) --------------------------------

_PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
# JPEG start-of-frame markers (baseline, progressive, lossless, arithmetic); not C4, C8, CC.
_JPEG_SOF = frozenset({0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF})
_JPEG_NO_LENGTH = frozenset({0x01, *range(0xD0, 0xD9)})
_EXIF_ORIENTATION_TAG = 0x0112


def _exif_orientation(payload: bytes) -> int | None:
    """The Exif orientation (1..8) from an APP1 payload, or None when there is none."""
    if not payload.startswith(b"Exif\x00\x00") or len(payload) < 14:
        return None
    tiff = payload[6:]
    order = {b"II": "<", b"MM": ">"}.get(tiff[:2])
    if order is None:
        return None
    try:
        (ifd,) = struct.unpack(order + "I", tiff[4:8])
        (count,) = struct.unpack(order + "H", tiff[ifd : ifd + 2])
        for i in range(min(count, 64)):
            entry = tiff[ifd + 2 + 12 * i : ifd + 14 + 12 * i]
            tag, _, _ = struct.unpack(order + "HHI", entry[:8])
            if tag == _EXIF_ORIENTATION_TAG:
                return struct.unpack(order + "H", entry[8:10])[0]
    except struct.error:
        return None
    return None


def photo_size(data: bytes, content_type: str | None) -> tuple[int, int] | None:
    """(width, height) in pixels from a PNG's IHDR or a JPEG's start-of-frame, or None.

    None too when a JPEG's Exif orientation turns it on its side (5..8): its stored width and
    height are swapped against what is shown, and we do not guess which one the model saw. The
    app's own captures are canvas JPEGs with no Exif."""
    if content_type == "image/png":
        if not data.startswith(_PNG_SIGNATURE) or len(data) < 24 or data[12:16] != b"IHDR":
            return None
        width, height = struct.unpack(">II", data[16:24])
        return (width, height) if width > 0 and height > 0 else None
    if content_type != "image/jpeg" or not data.startswith(b"\xff\xd8"):
        return None
    pos, end = 2, len(data)
    rotated = False
    while pos + 4 <= end:
        if data[pos] != 0xFF:
            return None
        marker = data[pos + 1]
        if marker == 0xFF:  # fill byte
            pos += 1
            continue
        if marker in _JPEG_NO_LENGTH:
            pos += 2
            continue
        if marker == 0xDA:  # start of scan before any frame header
            return None
        (length,) = struct.unpack(">H", data[pos + 2 : pos + 4])
        if length < 2:
            return None
        payload = data[pos + 4 : pos + 2 + length]
        if marker == 0xE1 and (_exif_orientation(payload) or 1) >= 5:
            rotated = True
        if marker in _JPEG_SOF:
            if len(payload) < 5:
                return None
            height, width = struct.unpack(">HH", payload[1:5])
            if rotated or width <= 0 or height <= 0:
                return None
            return (width, height)
        pos += 2 + length
    return None
