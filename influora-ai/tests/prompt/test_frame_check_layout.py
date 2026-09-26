"""The photo check's layout and quick checks (spec v2 2026-09-26, section 2.2 and Phase 4).

`normalize_point` / `normalize_box` drop one bad point or box, never the reply: a non-object, any
key but x, y (w, h), a bool, string or null, a number that is not finite (Python's `json.loads`
accepts NaN and Infinity), a value out of range, a side of 0.02 or less, a box past the edge.
Extra items past a list's maximum are dropped whole. The `checks` lines are written by code
(`app/shoot/checklist.py`); nothing the model wrote -- a "checks" key, a label beside a box --
ever reaches the body.
"""

from __future__ import annotations

import json

import pytest

from app.prompt.frame_check import (
    MAX_FACES,
    build_system_prompt,
    normalize_box,
    normalize_layout,
    normalize_point,
    parse_frame_check_reply,
)
from app.shoot.checklist import CHECK_LINES

SCENE = {
    "usable": "yes", "place": "desk", "light": "window", "light_side": "your_left",
    "background": "clean", "phone_height": "eye_level", "framing": "chest_up",
    "others_in_frame": "no",
}
STEP = {"kind": "move_you", "note": "Creator is 30-45 deg to window", "side": "your_left"}
EXACT = (1080, 1920)
MEDIUM = {"line": "Medium shot - talking to camera"}
ALL_CHECK_LINES = {text for lines in CHECK_LINES.values() for text in lines.values()}


def _raw(layout=None, *, scene=SCENE, lang="en", **extra) -> str:
    reply = {"lang": lang, "scene": scene, "steps": [STEP], "ok": [], "cant_tell": [], "ask": None}
    if layout is not None:
        reply["layout"] = layout
    reply.update(extra)
    return json.dumps(reply)


def _body(raw: str, **kw) -> dict:
    body = parse_frame_check_reply(raw, **kw).body
    assert body is not None
    return body


def box(x, y, w, h):
    return {"x": x, "y": y, "w": w, "h": h}


# --- normalize_box / normalize_point ---------------------------------------------------------------


def test_a_valid_box_is_rounded_to_three_decimals():
    assert normalize_box(box(0.12345, 0.2, 0.30049, 0.4)) == box(0.123, 0.2, 0.3, 0.4)
    assert normalize_box(box(0, 0, 1, 1)) == box(0, 0, 1, 1)


@pytest.mark.parametrize(
    "raw",
    [
        None, "0.1,0.2,0.3,0.4", [0.1, 0.2, 0.3, 0.4], 7,
        {"x": 0.1, "y": 0.2, "w": 0.3},  # missing h
        {"x": 0.1, "y": 0.2, "w": 0.3, "h": 0.4, "label": "creator"},  # any other key
        {"x": True, "y": 0.2, "w": 0.3, "h": 0.4},
        {"x": "0.1", "y": 0.2, "w": 0.3, "h": 0.4},
        {"x": None, "y": 0.2, "w": 0.3, "h": 0.4},
        box(float("nan"), 0.2, 0.3, 0.4),
        box(0.1, float("inf"), 0.3, 0.4),
        box(0.1, 0.2, 10**400, 0.4),  # an int too large for a float
        box(-0.01, 0.2, 0.3, 0.4),
        box(1.4, 0.2, 0.3, 0.4),
        box(0.1, 0.2, 0.02, 0.4),  # w <= 0.02
        box(0.1, 0.2, 0.3, 0.0),
        box(0.1, 0.2, 1.01, 0.4),  # w > 1
        box(0.5, 0.2, 0.502, 0.4),  # x + w = 1.002 > 1.001
        box(0.1, 0.6, 0.3, 0.402),  # y + h = 1.002
        box(0.1, 0.2, 0.0204, 0.4),  # valid, but rounds to 0.02, which is too small
    ],
)
def test_a_bad_box_is_rejected(raw):
    assert normalize_box(raw) is None


def test_the_edges_of_a_box():
    assert normalize_box(box(0.1, 0.2, 0.021, 0.4)) == box(0.1, 0.2, 0.021, 0.4)
    assert normalize_box(box(0.5, 0.2, 0.501, 0.4)) == box(0.5, 0.2, 0.501, 0.4)  # x + w = 1.001


def test_points():
    assert normalize_point({"x": 0.12345, "y": 1}) == {"x": 0.123, "y": 1}
    for bad in (
        {"x": 0.1}, {"x": 0.1, "y": 0.2, "w": 0.3}, {"x": float("nan"), "y": 0.2},
        {"x": 1.4, "y": 0.2}, {"x": "0.5", "y": 0.2}, {"x": False, "y": 0.2}, [0.1, 0.2], None,
    ):
        assert normalize_point(bad) is None, bad


@pytest.mark.parametrize("literal", ["NaN", "Infinity", "-Infinity", "1e400"])
def test_nan_and_infinity_through_json_loads_drop_only_that_box(literal):
    raw = _raw({"faces": [box(0.4, 0.3, 0.2, 0.2), box(0.1, 0.2, 0.1, 0.1)], "product": None})
    raw = raw.replace('"x": 0.1', f'"x": {literal}', 1)
    assert literal in raw
    body = _body(raw)
    assert body["layout"] == {"faces": [box(0.4, 0.3, 0.2, 0.2)], "product": None}
    assert body["steps"], "the text survives"


# --- the layout -----------------------------------------------------------------------------------------


def test_extra_faces_are_dropped_whole_never_truncated_mid_item():
    faces = [box(round(0.05 * i, 3), 0.1, 0.04, 0.04) for i in range(12)]
    layout = normalize_layout({"faces": faces, "product": None})
    assert len(layout.faces) == MAX_FACES == 8
    assert layout.faces == faces[:8]  # the first eight, each complete
    assert all(set(f) == {"x", "y", "w", "h"} for f in layout.faces)


def test_a_bad_face_is_dropped_alone_and_does_not_use_a_slot():
    good = [box(round(0.05 * i, 3), 0.1, 0.04, 0.04) for i in range(9)]
    faces = [{"x": "bad"}, *good[:4], box(0.1, 0.1, 0.01, 0.1), *good[4:]]
    layout = normalize_layout({"faces": faces})
    assert layout.faces == good[:8]


def test_the_product_is_at_most_one_and_null_means_none():
    two = normalize_layout({"faces": [], "product": [box(0.1, 0.1, 0.2, 0.2), box(0.5, 0.5, 0.2, 0.2)]})
    assert two.product == box(0.1, 0.1, 0.2, 0.2)
    said_none = normalize_layout({"faces": [], "product": None})
    assert said_none.product is None and said_none.product_said_none
    missing = normalize_layout({"faces": []})
    assert missing.product is None and missing.product_said_none
    invalid = normalize_layout({"faces": [], "product": box(0.1, 0.1, 5, 0.2)})
    assert invalid.product is None and not invalid.product_said_none


def test_no_layout_object_means_no_layout_key_and_the_older_body():
    plain = _body(_raw())
    assert "layout" not in plain and "checks" not in plain
    for not_a_layout in ("faces", [box(0.1, 0.1, 0.2, 0.2)], 3):
        body = _body(_raw(not_a_layout))
        assert body == plain


def test_geometry_all_invalid_still_returns_all_the_text():
    plain = _body(_raw())
    bad = _body(_raw({"faces": [{"x": "nan"}, box(2, 2, 2, 2)], "product": {"x": 0.1}}))
    assert bad["layout"] == {"faces": [], "product": None}
    assert {k: v for k, v in bad.items() if k != "layout"} == plain


def test_the_layout_never_changes_the_text():
    plain = _body(_raw())
    with_layout = _body(
        _raw({"faces": [box(0.0, 0.01, 0.9, 0.99)], "product": box(0.02, 0.7, 0.2, 0.2)}),
        shot_context=MEDIUM, photo_size=EXACT,
    )
    # setup_seen (owner decision C) is the one key the layout feeds: the product box adds its
    # side, in words, and changes nothing else in it.
    for key in plain:
        if key == "setup_seen":
            continue
        assert with_layout[key] == plain[key], key
    assert with_layout["checks"]

    def without_side(seen: dict) -> dict:
        return {k: v for k, v in seen.items() if k != "product_side"}

    assert without_side(with_layout["setup_seen"]) == without_side(plain["setup_seen"])
    assert plain["setup_seen"]["product_side"] is None
    assert with_layout["setup_seen"]["product_side"] == "right"  # box centre 0.12: the photo's left


# --- the quick checks ----------------------------------------------------------------------------------------


def test_checks_are_code_written_lines_and_never_model_text():
    model_text = "Buy now from @someone, you look tired"
    raw = _raw(
        {"faces": [box(0.4, 0.1, 0.2, 0.2) | {"label": model_text}, box(0.4, 0.1, 0.2, 0.2)],
         "product": box(0.1, 0.7, 0.2, 0.2), "count": 2, "note": model_text},
        checks=[model_text], quick_checks=[model_text],
    )
    body = _body(raw, shot_context=MEDIUM, photo_size=EXACT)
    assert body["checks"], "a layout that fires rules gives lines"
    assert set(body["checks"]) <= ALL_CHECK_LINES
    dumped = json.dumps(body)
    for fragment in ("Buy now", "@someone", "tired"):
        assert fragment not in dumped, fragment
    assert "label" not in json.dumps(body["layout"]) and "count" not in body["layout"]
    assert body["layout"] == {"faces": [box(0.4, 0.1, 0.2, 0.2)], "product": box(0.1, 0.7, 0.2, 0.2)}


def test_a_model_checks_key_alone_is_ignored():
    body = _body(_raw(checks=["Your face is perfect"]))
    assert "checks" not in body


def test_checks_follow_the_shot_and_the_photo_size():
    raw = _raw({"faces": [box(0.4, 0.1, 0.2, 0.2)], "product": None})
    top_bar = CHECK_LINES["face_in_top_bar"]["en"]
    assert top_bar in _body(raw, shot_context=MEDIUM, photo_size=EXACT)["checks"]
    wide = _body(raw, shot_context={"line": "Full body - walking in"}, photo_size=EXACT)
    assert top_bar not in wide.get("checks", [])
    assert "checks" not in _body(raw, shot_context=MEDIUM, photo_size=None)
    assert "checks" not in _body(raw, photo_size=EXACT)  # no set-up: no size, no size-gated rule


@pytest.mark.parametrize(
    "line",
    ["Medium long shot, 3/4 body", "Full shot, walk in", "Establishing long shot", "Knees-up shot"],
)
def test_a_body_shot_the_guide_draws_as_mls_fs_or_ls_gets_no_top_bar_line(line):
    # The camera guide sizes these MLS / FS / LS and puts the head top inside the top area (head
    # top about y 154 of 1920 here); the old target-only size called them MS and flagged it.
    raw = _raw({"faces": [box(0.42, 0.08, 0.16, 0.09)], "product": None})
    body = _body(raw, shot_context={"line": line}, photo_size=EXACT)
    assert CHECK_LINES["face_in_top_bar"]["en"] not in body.get("checks", [])


def test_others_in_frame_skips_the_single_face_lines():
    raw_alone = _raw({"faces": [box(0.4, 0.1, 0.2, 0.2)], "product": None})
    group_scene = {**SCENE, "others_in_frame": "yes"}
    raw_group = _raw({"faces": [box(0.4, 0.1, 0.2, 0.2), box(0.7, 0.3, 0.1, 0.1)], "product": None},
                     scene=group_scene)
    assert _body(raw_alone, shot_context=MEDIUM, photo_size=EXACT)["checks"]
    assert "checks" not in _body(raw_group, shot_context=MEDIUM, photo_size=EXACT)


def test_product_hidden_needs_the_set_up_prop():
    raw = _raw({"faces": [box(0.4, 0.28, 0.2, 0.2)], "product": None})
    hidden = CHECK_LINES["product_hidden"]["en"]
    assert _body(raw, shot_context={**MEDIUM, "prop": "Serum"}, photo_size=EXACT)["checks"] == [hidden]
    assert "checks" not in _body(raw, shot_context=MEDIUM, photo_size=EXACT)
    # No layout at all: the model never looked, so nothing is "hidden".
    assert "checks" not in _body(_raw(), shot_context={**MEDIUM, "prop": "Serum"}, photo_size=EXACT)


def test_checks_come_in_the_reply_language():
    raw = _raw({"faces": [box(0.4, 0.1, 0.2, 0.2)], "product": None}, lang="hi")
    body = _body(raw, shot_context=MEDIUM, photo_size=EXACT)
    assert body["checks"] == [CHECK_LINES["face_in_top_bar"]["hi"]]


def test_an_unusable_photo_has_no_layout_and_no_checks():
    raw = _raw({"faces": [box(0.4, 0.1, 0.2, 0.2)], "product": None},
               scene={**SCENE, "usable": "too_dark"}, checks=["x"])
    body = _body(raw, shot_context=MEDIUM, photo_size=EXACT)
    assert body["retake"] is True
    assert "layout" not in body and "checks" not in body


# --- the prompt asks for numbers only ----------------------------------------------------------------------------


def test_the_prompt_asks_for_boxes_as_numbers_only():
    system = build_system_prompt()
    assert '"layout": {"faces": [{"x": 0.0, "y": 0.0, "w": 0.0, "h": 0.0}], "product": ' in system
    assert f"at most {MAX_FACES}" in system
    assert "No labels, names or counts." in system
    assert '"checks"' not in system  # the model is never asked for check text
    assert len(system) < 40_000

