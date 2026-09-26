"""The photo check's code-written "Set-up seen" (owner decision C, Swapnil 2026-09-26).

`setup_seen` = {light, light_side, place, phone_height, product_side}: what the photo showed
about the set-up, in WORDS, so Meera can use it on later turns like a coach answer for the shot
card. These tests pin that it is written by code only (a canary the model writes anywhere never
reaches it), from the validated scene enums and the validated product box, with the product side
in the creator's OWN left/right (the unmirrored photo's right half is their left -- the same rule
the checklist's framing advice already uses), never a coordinate; and that the older bodies
(retake, fallback, nothing known) do not carry it.
"""

from __future__ import annotations

import json

import pytest

from app.prompt.frame_check import fallback_response, parse_frame_check_reply
from app.prompt.frame_check_render import (
    PRODUCT_CENTRE_TOLERANCE,
    PRODUCT_SIDES,
    SCENE_VALUES,
    SETUP_SEEN_KEYS,
    build_setup_seen,
    product_side_from_box,
    render_setup_seen_line,
)

CANARY = "CANARY-setup-7f3a"
STEP = {"kind": "move_you", "note": "Creator is 30-45 deg to window", "side": "none"}
SCENE = {
    "usable": "yes", "place": "bedroom", "light": "window", "light_side": "your_left",
    "background": "clean", "phone_height": "below_eyes", "framing": "chest_up", "others_in_frame": "no",
}


def _box(x: float, w: float = 0.1) -> dict[str, float]:
    return {"x": x, "y": 0.4, "w": w, "h": 0.2}


def _body(scene=SCENE, layout=None, **extra) -> dict | None:
    reply = {"lang": "en", "scene": scene, "steps": [STEP], "ok": [], "cant_tell": [], "ask": None}
    if layout is not None:
        reply["layout"] = layout
    reply.update(extra)
    return parse_frame_check_reply(json.dumps(reply)).body


def test_setup_seen_is_the_validated_scene_in_words():
    body = _body(layout={"faces": [], "product": _box(0.75)})
    assert body["setup_seen"] == {
        "light": "window", "light_side": "your_left", "place": "bedroom",
        "phone_height": "below_eyes", "product_side": "left",
    }
    assert tuple(body["setup_seen"]) == SETUP_SEEN_KEYS


def test_setup_seen_is_written_by_code_only():
    """Canary everywhere the model can write: a scene value, an extra scene key, a label beside
    the product box, and a `setup_seen` object of its own. None of it reaches the body."""
    scene = {**SCENE, "place": CANARY, "light": f"window {CANARY}", "setup_seen": CANARY}
    body = _body(
        scene=scene,
        layout={"faces": [{**_box(0.6), "label": CANARY}], "product": _box(0.2)},
        setup_seen={"light": CANARY, "place": CANARY, "product_side": CANARY},
    )
    assert CANARY not in json.dumps(body)
    seen = body["setup_seen"]
    # The model's unknown values are unknown (None), never its words.
    assert seen["place"] is None and seen["light"] is None
    assert seen["product_side"] == "right"
    # A product box with the model's words beside its numbers is not a box at all: no side.
    labelled = _body(layout={"faces": [], "product": {**_box(0.2), "side": CANARY}})
    assert CANARY not in json.dumps(labelled) and labelled["setup_seen"]["product_side"] is None


def test_build_setup_seen_reads_only_scene_enum_values():
    """Defence in depth for any caller: a scene that was never normalized still yields only enum
    words -- a free string, a coordinate or an "unknown" is None."""
    raw = {"usable": "yes", "place": CANARY, "light": "window", "light_side": "0.7",
           "phone_height": "unknown", "extra": CANARY}
    assert build_setup_seen(raw, None) == {
        "light": "window", "light_side": None, "place": None, "phone_height": None, "product_side": None,
    }
    assert build_setup_seen({**raw, "usable": "too_dark"}, _box(0.8)) == {
        "light": None, "light_side": None, "place": None, "phone_height": None, "product_side": "left",
    }


def test_every_value_is_an_enum_word_or_none_never_a_number():
    for x in (0.0, 0.1, 0.35, 0.45, 0.5, 0.55, 0.65, 0.9):
        seen = _body(layout={"faces": [], "product": _box(x)})["setup_seen"]
        for key in ("light", "light_side", "place", "phone_height"):
            assert seen[key] is None or (seen[key] in SCENE_VALUES[key] and seen[key] != "unknown"), key
        assert seen["product_side"] in PRODUCT_SIDES
        assert not any(ch.isdigit() for ch in json.dumps(seen)), seen


@pytest.mark.parametrize(
    "x, w, side",
    [
        (0.8, 0.1, "left"),     # centre 0.85: the photo's right half is the creator's left
        (0.05, 0.1, "right"),   # centre 0.10
        (0.45, 0.1, "centre"),  # centre 0.50
        (0.53, 0.1, "centre"),  # centre 0.58: on the tolerance, still centre
        (0.531, 0.1, "left"),   # centre 0.581
        (0.37, 0.1, "centre"),  # centre 0.42
        (0.369, 0.1, "right"),  # centre 0.419
    ],
)
def test_product_side_is_the_creators_own_side_with_a_centre_band(x, w, side):
    assert PRODUCT_CENTRE_TOLERANCE == 0.08
    assert product_side_from_box(_box(x, w)) == side


def test_the_side_rule_is_the_unmirrored_photos_geometry_and_is_mirror_symmetric():
    """The photo is the unmirrored still (what the lens sees), so the photo's right half is the
    creator's LEFT, and mirroring a box swaps the side. Deliberately NOT tied to
    app.shoot.checklist.framing_advice: its move-left for a face in the photo's right half points
    the other way (a separate, older question about the live coach's direction), and a later fix
    there must not flip this rule."""
    right_half = {"x": 0.7, "y": 0.2, "w": 0.2, "h": 0.3}
    assert product_side_from_box(right_half) == "left"
    mirrored = {**right_half, "x": 1 - right_half["x"] - right_half["w"]}
    assert product_side_from_box(mirrored) == "right"
    centred = {**right_half, "x": 0.5 - right_half["w"] / 2}
    assert product_side_from_box(centred) == "centre"


def test_an_invalid_or_missing_product_box_gives_no_side():
    assert _body(layout={"faces": [], "product": {"x": 2, "y": 0.5, "w": 0.1, "h": 0.1}})["setup_seen"]["product_side"] is None
    assert _body(layout={"faces": [], "product": None})["setup_seen"]["product_side"] is None
    assert _body()["setup_seen"]["product_side"] is None
    assert product_side_from_box({"x": True, "w": 0.1}) is None
    assert product_side_from_box("left") is None


def test_nothing_known_means_no_key_and_the_older_body():
    unknown = {k: "unknown" for k in SCENE if k not in ("usable", "others_in_frame")}
    body = _body(scene={"usable": "yes", **unknown, "others_in_frame": "no"})
    assert "setup_seen" not in body
    assert build_setup_seen(None, None) is None
    # A retake body and the fallback never carry it.
    retake = _body(scene={**SCENE, "usable": "too_dark"}, layout={"faces": [], "product": _box(0.8)})
    assert retake["retake"] is True and "setup_seen" not in retake
    assert "setup_seen" not in fallback_response()


def test_the_reference_line_is_words_only_in_the_creators_own_left_and_right():
    seen = _body(layout={"faces": [], "product": _box(0.05)})["setup_seen"]
    line = render_setup_seen_line(seen)
    assert line == (
        "Set-up seen: light: window light from your left; place: bedroom; phone: below your eyes;"
        " product: on your right"
    )
    assert not any(ch.isdigit() for ch in line)
    assert render_setup_seen_line({k: None for k in SETUP_SEEN_KEYS}) is None
    assert render_setup_seen_line({"place": CANARY}) is None  # not a scene value: never rendered
