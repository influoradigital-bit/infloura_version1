"""The code-side quick checks (spec v2 2026-09-26, Phase 4; `app/shoot/checklist.py`).

Every rule is pinned with a box just inside and just outside its threshold, and every threshold
is read from the config (`get_safe_zones()`, `THRESHOLDS`), never typed here -- so a rule that
drifts from the config, or flips its comparison, fails. A 1080 x 1920 photo is exactly 9:16, so
the crop is the whole frame and a box's crop coordinates are its own.
"""

from __future__ import annotations

import json
import re
import struct
from dataclasses import replace
from pathlib import Path

import pytest

from app.shoot.checklist import (
    CHECK_IDS,
    CHECK_LINES,
    FULL_FRAME,
    SHOT_SIZE_RULES,
    THRESHOLDS,
    Crop,
    framing_advice,
    needs_product,
    photo_size,
    reel_crop,
    render_checks,
    run_checks,
    shot_size,
    shot_target,
    to_crop,
)
from app.shoot.safe_zones import get_safe_zones

REPO = Path(__file__).resolve().parents[3]
METRICS_TS = REPO / "src" / "lib" / "shoot-check" / "metrics.ts"
BEAT_TO_SHOT_TS = REPO / "src" / "lib" / "shoot-check" / "beat-to-shot.ts"
CROP_CASES = REPO / "src" / "lib" / "__fixtures__" / "reel-crop-cases.json"

Z = get_safe_zones()
T = THRESHOLDS
EXACT = (1080, 1920)  # 9:16: the crop is the whole photo
D = 0.001  # the rounding grain of a validated box: "just inside" / "just outside"


def box(x, y, w, h):
    return {"x": x, "y": y, "w": w, "h": h}


# A face that fires nothing on its own for a medium shot: centred, eye line on 0.36, clear of
# every zone, size inside the medium range.
CALM = box(0.4, 0.28, 0.2, 0.2)


def ids(faces, product=None, **kw):
    kw.setdefault("photo_size", EXACT)
    kw.setdefault("target", "medium")
    return run_checks(faces, product, **kw)


def test_the_calm_face_fires_nothing():
    assert ids([CALM]) == []
    assert ids([CALM], box(0.1, 0.3, 0.2, 0.2)) == []


# --- head at the top edge (framing_advice == lower-phone) ----------------------------------------


def test_head_at_top_edge_just_inside_and_outside():
    at_edge = box(0.4, T.headroom_min - D, 0.2, 0.2)
    clear = box(0.4, T.headroom_min + D, 0.2, 0.2)
    assert "head_at_top_edge" in ids([at_edge])
    assert "head_at_top_edge" not in ids([clear])


def test_head_at_top_edge_needs_a_target_and_follows_framing_order():
    at_edge = box(0.4, T.headroom_min - D, 0.2, 0.2)
    assert "head_at_top_edge" not in ids([at_edge], target=None)
    assert "head_at_top_edge" not in ids([at_edge], target="hands-overhead")
    # framingVerdict judges size first: a face too big for a medium shot is "step-back", so the
    # headroom line never fires for it (the app says the same).
    too_big = box(0.3, T.headroom_min - D, 0.4, T.face_medium_max + D)
    assert framing_advice(too_big, "medium") == "step-back"
    assert "head_at_top_edge" not in ids([too_big])


def test_head_at_top_edge_is_judged_on_the_photo_without_a_size():
    at_edge = box(0.4, T.headroom_min - D, 0.2, 0.2)
    assert ids([at_edge], photo_size=None) == ["head_at_top_edge"]


# --- too much headroom (eye line below eye_line_max; CU/MCU/MS only) ------------------------------


def _face_with_eye_at(eye: float, h: float = 0.2):
    return box(0.4, round(eye - T.eye_factor * h, 3), 0.2, h)


def test_too_much_headroom_just_inside_and_outside():
    assert "too_much_headroom" not in ids([_face_with_eye_at(T.eye_line_max - D)])
    assert "too_much_headroom" in ids([_face_with_eye_at(T.eye_line_max + D)])
    assert "too_much_headroom" in ids([_face_with_eye_at(T.eye_line_max + D)], target="closeup")


def test_too_much_headroom_is_size_gated():
    low = _face_with_eye_at(T.eye_line_max + D)
    assert "too_much_headroom" not in ids([low], target="wide")  # FS
    assert "too_much_headroom" not in ids([low], target=None)


# --- face at the edge (side margin, rail) --------------------------------------------------------


def test_face_at_the_left_edge_just_inside_and_outside():
    assert "face_at_edge" in ids([box(Z.side - D, 0.28, 0.2, 0.2)], target=None)
    assert "face_at_edge" not in ids([box(Z.side + D, 0.28, 0.2, 0.2)], target=None)


def test_face_at_the_right_edge_just_inside_and_outside():
    w = 0.2
    assert "face_at_edge" in ids([box(round(1 - Z.side - w + D, 3), 0.28, w, 0.2)], target=None)
    assert "face_at_edge" not in ids([box(round(1 - Z.side - w - D, 3), 0.28, w, 0.2)], target=None)


def test_face_touching_the_rail():
    # Level with the rail, below its top, above the caption line.
    y, h, w = Z.rail_y_from + 0.01, 0.1, 0.2
    assert y + h < Z.caption_line
    assert "face_at_edge" in ids([box(round(Z.rail_x - w + D, 3), y, w, h)], target=None)
    assert "face_at_edge" not in ids([box(round(Z.rail_x - w - D, 3), y, w, h)], target=None)
    # Right of the rail's x but above it: not the rail.
    above = box(round(Z.rail_x - 0.1, 3), round(Z.rail_y_from - 0.2 - D, 3), 0.05, 0.2)
    assert "face_at_edge" not in ids([above], target=None)


# --- face in a UI zone -----------------------------------------------------------------------------


def test_face_reaching_the_caption_line_just_inside_and_outside():
    h = 0.2
    assert "face_in_caption_zone" not in ids([box(0.4, round(Z.caption_line - h - D, 3), 0.2, h)], target=None)
    assert "face_in_caption_zone" in ids([box(0.4, round(Z.caption_line - h + D, 3), 0.2, h)], target=None)


def test_face_in_the_covered_bottom_and_in_the_cta_band():
    assert "face_in_caption_zone" in ids([box(0.4, Z.covered_from, 0.2, 0.1)], target=None)
    band = box(0.1, round(Z.caption_line + D, 3), 0.1, round(Z.covered_from - Z.caption_line - 2 * D, 3))
    assert "face_in_caption_zone" in ids([band], target=None)


def test_face_in_the_top_bar_just_inside_and_outside():
    assert "face_in_top_bar" in ids([box(0.4, round(Z.top - D, 3), 0.2, 0.2)])
    assert "face_in_top_bar" not in ids([box(0.4, round(Z.top + D, 3), 0.2, 0.2)])


def test_a_face_in_the_top_area_is_flagged_for_ms_and_not_for_fs():
    high = box(0.4, round(Z.top - D, 3), 0.2, 0.15)
    assert "face_in_top_bar" in ids([high], target="medium")  # MS
    assert "face_in_top_bar" in ids([high], target="closeup")  # CU
    assert "face_in_top_bar" not in ids([high], target="wide")  # FS
    assert "face_in_top_bar" not in ids([high], target=None)


# --- product rules ---------------------------------------------------------------------------------


def test_product_hidden_needs_a_prop_and_a_null_product():
    assert "product_hidden" in ids([CALM], None, needs_product=True, product_said_none=True)
    assert "product_hidden" not in ids([CALM], None, needs_product=False, product_said_none=True)
    # The product box failed validation: we don't know it's hidden.
    assert "product_hidden" not in ids([CALM], None, needs_product=True, product_said_none=False)
    assert "product_hidden" not in ids([CALM], box(0.1, 0.3, 0.2, 0.2), needs_product=True)


def test_product_low_just_inside_and_outside():
    h = 0.1
    above = box(0.1, round(Z.caption_line - h / 2 - D, 3), 0.2, h)
    below = box(0.1, round(Z.caption_line - h / 2 + D, 3), 0.2, h)
    assert "product_low" not in ids([CALM], above)
    assert "product_low" in ids([CALM], below)


def test_product_too_close_by_area_and_by_height():
    w = 0.7
    small = box(0.0, 0.3, w, round((T.product_area_max - 0.01) / w, 3))
    big = box(0.0, 0.3, w, round((T.product_area_max + 0.01) / w, 3))
    assert "product_too_close" not in ids([], small)
    assert "product_too_close" in ids([], big)
    tall_ok = box(0.0, 0.0, 0.1, round(T.product_h_max - D, 3))
    tall = box(0.0, 0.0, 0.1, round(T.product_h_max + D, 3))
    assert "product_too_close" not in ids([], tall_ok)
    assert "product_too_close" in ids([], tall)


def test_product_over_the_face():
    right_of = box(round(CALM["x"] + CALM["w"], 3), 0.3, 0.1, 0.1)  # touching, not over
    over = box(round(CALM["x"] + CALM["w"] - D, 3), 0.3, 0.1, 0.1)
    assert "product_over_face" not in ids([CALM], right_of)
    assert "product_over_face" in ids([CALM], over)


# --- one face only, the config, the crop ----------------------------------------------------------

# A face that fires every face rule at once for a medium shot (and a product that is low).
BAD_FACE = box(0.0, 0.01, 0.9, 0.99)


def test_others_in_frame_skips_every_single_face_rule():
    product = box(0.02, 0.7, 0.2, 0.2)
    alone = ids([BAD_FACE], product)
    assert {"face_at_edge", "face_in_caption_zone", "face_in_top_bar", "product_over_face"} <= set(alone)
    group = ids([BAD_FACE, CALM], product, others_in_frame=True)
    assert not {"head_at_top_edge", "too_much_headroom", "face_at_edge", "face_in_caption_zone",
                "face_in_top_bar", "product_over_face"} & set(group)
    assert "product_low" in group  # a product rule does not need to know whose face is whose


def test_the_zone_rules_follow_the_config_not_constants():
    face = box(0.4, 0.4, 0.2, 0.22)  # bottom at 0.62
    assert "face_in_caption_zone" not in ids([face], target=None)
    moved = replace(Z, caption_line=0.6)
    assert "face_in_caption_zone" in ids([face], target=None, zones=moved)
    narrow = replace(Z, side=0.45)
    assert "face_at_edge" in ids([face], target=None, zones=narrow)


def test_without_the_photo_size_the_crop_rules_are_skipped():
    assert ids([BAD_FACE], box(0.02, 0.7, 0.2, 0.2), photo_size=None) == ["product_over_face"]


def test_the_crop_is_the_whole_photo_at_9_16():
    assert reel_crop(EXACT, [], None) == FULL_FRAME
    assert reel_crop((720, 1280), [CALM], None) == FULL_FRAME
    assert to_crop(CALM, FULL_FRAME) is CALM
    assert reel_crop(None, [CALM], None) is None


def test_a_wide_photo_crops_around_the_face_and_the_product():
    # 16:9: the 9:16 window is 0.316 of the width. Face on the left, product beside it.
    face = box(0.05, 0.3, 0.1, 0.2)
    product = box(0.2, 0.5, 0.1, 0.15)
    crop = reel_crop((1920, 1080), [face], product)
    assert crop.y == 0.0 and crop.h == 1.0 and crop.w == pytest.approx(0.31640625)
    assert crop.x <= face["x"] and crop.x + crop.w >= product["x"] + product["w"]
    # Repeatable, and every kept box is in crop shares.
    assert reel_crop((1920, 1080), [face], product) == crop
    inside = to_crop(face, crop)
    assert inside["x"] == pytest.approx((face["x"] - crop.x) / crop.w)
    assert inside["w"] == pytest.approx(face["w"] / crop.w)


def test_a_wide_photo_with_nothing_takes_the_central_window():
    crop = reel_crop((1920, 1080), [], None)
    assert abs(crop.x + crop.w / 2 - 0.5) <= 0.005


def test_a_tall_photo_puts_the_eye_line_near_its_target():
    face = box(0.4, 0.3, 0.2, 0.1)  # eye line at 0.34 of the photo
    crop = reel_crop((1080, 2400), [face], None)
    assert crop.x == 0.0 and crop.w == 1.0 and crop.h == pytest.approx(0.8)
    # 0.34 - 0.36 x 0.8 = 0.052: the 1% step nearest it.
    assert crop.y == pytest.approx(0.05)
    eye = (face["y"] + 0.4 * face["h"] - crop.y) / crop.h
    assert abs(eye - 0.36) < 0.01
    # A face too low to reach the target: the lowest window, the nearest it can get.
    low = box(0.4, 0.5, 0.2, 0.1)
    assert reel_crop((1080, 2400), [low], None).y == pytest.approx(0.2)


def test_the_crop_matches_the_shared_fixture_the_app_reads():
    # src/lib/__fixtures__/reel-crop-cases.json is read by reel-layout.test.ts (cropTo916) too, so
    # the checks are judged on the window the Reel layout guide draws.
    cases = json.loads(CROP_CASES.read_text(encoding="utf-8"))["cases"]
    assert len(cases) >= 12
    for case in cases:
        photo = (case["photo"]["width"], case["photo"]["height"])
        crop = reel_crop(photo, case["layout"]["faces"], case["layout"]["product"])
        for key in ("x", "y", "w", "h"):
            assert getattr(crop, key) == pytest.approx(case["crop"][key], abs=1e-9), (case["name"], key)


def test_a_face_at_the_right_edge_of_a_4_5_photo_is_judged_in_the_flush_right_window():
    # The guide's window is flush with the right edge (x 0.296875), so the face is drawn over the
    # side and the rail; the check must say so.
    face = box(0.8, 0.3, 0.2, 0.16)
    assert reel_crop((1080, 1350), [face], None).x == pytest.approx(1 - 1350 * 9 / 16 / 1080)
    assert "face_at_edge" in ids([face], photo_size=(1080, 1350))
    # Within 0.005 of 9:16 on width / height (the app's test): the whole photo.
    assert reel_crop((1080, 1905), [], None) == FULL_FRAME


def test_a_box_mostly_outside_the_crop_is_dropped_and_the_rest_clipped():
    crop = Crop(0.5, 0.0, 0.3, 1.0)
    assert to_crop(box(0.3, 0.2, 0.3, 0.2), crop) is None  # a third inside
    clipped = to_crop(box(0.45, 0.2, 0.2, 0.2), crop)  # three quarters inside
    assert clipped["x"] == 0.0 and clipped["w"] == pytest.approx(0.15 / 0.3)


# --- the shot's target, the prop, the lines ---------------------------------------------------------


@pytest.mark.parametrize(
    ("line", "target"),
    [
        ("Close-up - reacting to the first sip", "closeup"),
        ("Medium close - talking to camera", "closeup"),
        ("Overhead - hands unboxing", "hands-overhead"),
        ("Top-down of the plate", "hands-overhead"),
        ("Wide - walking in", "wide"),
        ("Full body mirror shot", "wide"),
        ("Talking head at the desk", "medium"),
    ],
)
def test_shot_target_reads_the_line_like_the_app(line, target):
    assert shot_target({"line": line}) == target


@pytest.mark.parametrize(
    ("line", "size"),
    [
        ("Extreme close-up of the texture", "ECU"),
        ("Medium close - talking to camera", "MCU"),
        ("Close-up - reacting to the first sip", "CU"),
        ("Medium long shot, 3/4 body", "MLS"),
        ("Knees-up shot", "MLS"),
        ("Medium shot - talking", "MS"),
        ("Waist-up", "MS"),
        ("Full shot, walk in", "FS"),
        ("Full body mirror shot", "FS"),
        ("Establishing long shot", "LS"),
        ("Wide - walking in", "LS"),
        ("Talking head at the desk", "MS"),  # no size word: the target's size
        ("Overhead - hands unboxing", None),  # overhead: no face size
    ],
)
def test_shot_size_is_the_size_the_camera_guide_draws(line, size):
    assert shot_size({"line": line}) == size


def test_shot_size_without_a_set_up_is_none():
    assert shot_size(None) is None
    assert shot_size({"where": "bedroom"}) is None
    assert shot_size({"angle": "Full shot", "action": "walk in"}) == "FS"


def test_the_size_gated_rules_follow_the_guides_size_not_the_target():
    # Head top at y 154 of 1920: inside the top area, where the guide puts an MLS / FS head.
    head_high = box(0.42, 0.08, 0.16, 0.09)
    for line in ("Medium long shot, 3/4 body", "Full shot, walk in", "Establishing long shot"):
        ctx = {"line": line}
        assert shot_target(ctx) == "medium"  # the old words: a talking target
        fired = ids([head_high], target=shot_target(ctx), size=shot_size(ctx))
        assert "face_in_top_bar" not in fired, line
    talking = {"line": "Medium shot - talking"}
    assert "face_in_top_bar" in ids([head_high], target=shot_target(talking), size=shot_size(talking))


def test_the_shot_size_table_is_the_apps_beat_to_shot_ts():
    source = BEAT_TO_SHOT_TS.read_text(encoding="utf-8")
    block = re.search(r"const SHOT_SIZE_RULES[^=]*=\s*\[(.*?)\n\];", source, re.DOTALL)
    assert block, "SHOT_SIZE_RULES not found in beat-to-shot.ts"
    ts_rules = re.findall(r"\[/(.+?)/i, '([A-Z]+)'\]", block.group(1))
    assert ts_rules == list(SHOT_SIZE_RULES)


def test_no_set_up_means_no_target():
    assert shot_target(None) is None
    assert shot_target({}) is None
    assert shot_target({"where": "bedroom"}) is None
    assert shot_target({"angle": "Close-up", "action": "sip"}) == "closeup"


def test_needs_product_reads_the_prop():
    assert needs_product({"prop": "Serum bottle"})
    for none in (None, {}, {"prop": "none"}, {"prop": "No prop."}, {"prop": " N/A "}):
        assert not needs_product(none), none


def test_every_check_has_a_line_in_both_languages_and_none_names_anyone():
    assert set(CHECK_LINES) == set(CHECK_IDS)
    for lines in CHECK_LINES.values():
        assert set(lines) == {"en", "hi"}
        for text in lines.values():
            assert text and "@" not in text
            assert not re.search(r"escrow|co-?pilot|views|reach|engagement|viral", text, re.IGNORECASE)


def test_render_checks_only_ever_returns_fixed_lines():
    assert render_checks(["face_at_edge", "Buy now", "product_low"], "hi") == [
        CHECK_LINES["face_at_edge"]["hi"], CHECK_LINES["product_low"]["hi"],
    ]
    assert render_checks(["face_at_edge"], "fr") == [CHECK_LINES["face_at_edge"]["en"]]


def test_the_order_is_the_table_order():
    fired = ids([BAD_FACE], box(0.02, 0.7, 0.2, 0.2))
    assert fired == [c for c in CHECK_IDS if c in fired]


# --- the framing thresholds are the app's ------------------------------------------------------------


def test_the_framing_thresholds_match_the_apps_metrics_ts():
    source = METRICS_TS.read_text(encoding="utf-8")
    pinned = {
        "FACE_CLOSEUP_MIN_FRACTION": T.face_closeup_min,
        "FACE_MEDIUM_MIN_FRACTION": T.face_medium_min,
        "FACE_MEDIUM_MAX_FRACTION": T.face_medium_max,
        "FACE_WIDE_MAX_FRACTION": T.face_wide_max,
        "HEADROOM_MIN": T.headroom_min,
        "HEADROOM_MAX": T.headroom_max,
        "CENTER_TOLERANCE": T.center_tolerance,
    }
    for name, value in pinned.items():
        found = re.search(rf"^\s*{name}:\s*([\d.]+),", source, re.MULTILINE)
        assert found, f"{name} not found in metrics.ts"
        assert float(found.group(1)) == value, name


# --- the photo's size from its header -------------------------------------------------------------------


def _png(width, height):
    return b"\x89PNG\r\n\x1a\n" + b"\x00\x00\x00\x0d" + b"IHDR" + struct.pack(">II", width, height) + b"\x00" * 50


def _jpeg(width, height, *, orientation=None):
    out = b"\xff\xd8" + b"\xff\xe0" + struct.pack(">H", 16) + b"JFIF\x00" + b"\x00" * 9
    if orientation is not None:
        # Exif APP1: little-endian TIFF, IFD0 with one entry (orientation).
        tiff = b"II*\x00" + struct.pack("<I", 8) + struct.pack("<H", 1)
        tiff += struct.pack("<HHIHH", 0x0112, 3, 1, orientation, 0) + struct.pack("<I", 0)
        payload = b"Exif\x00\x00" + tiff
        out += b"\xff\xe1" + struct.pack(">H", len(payload) + 2) + payload
    sof = b"\x08" + struct.pack(">HH", height, width) + b"\x03" + b"\x00" * 9
    out += b"\xff\xc0" + struct.pack(">H", len(sof) + 2) + sof
    return out + b"\xff\xda" + b"\x00" * 20 + b"\xff\xd9"


def test_photo_size_from_png_and_jpeg_headers():
    assert photo_size(_png(1080, 1920), "image/png") == (1080, 1920)
    assert photo_size(_jpeg(800, 1422), "image/jpeg") == (800, 1422)
    assert photo_size(_jpeg(800, 1422, orientation=1), "image/jpeg") == (800, 1422)


def test_photo_size_unknown_for_a_sideways_exif_or_junk():
    assert photo_size(_jpeg(1422, 800, orientation=6), "image/jpeg") is None
    assert photo_size(_png(0, 1920), "image/png") is None
    assert photo_size(b"\xff\xd8\xff\xe0" + b"\x00" * 200 + b"\xff\xd9", "image/jpeg") is None
    assert photo_size(b"garbage", "image/jpeg") is None
    assert photo_size(_png(1080, 1920), "image/jpeg") is None
    assert photo_size(_jpeg(800, 1422), "image/gif") is None
