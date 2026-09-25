"""The app's Shoot Check seam fixture is this parser's real output, and stays that way.

`src/lib/__fixtures__/shoot-check-frame-bodies.json` holds the JSON body `parse_frame_check_reply`
returns for four realistic model replies; the app's `meera-api.shoot-check-seam.test.ts` feeds each
one through `parseShootCheckFrameBody` and checks lang, retake, step labels and settings parts
arrive. That only proves the seam while the fixture matches what this side writes today, so this
test rebuilds the bodies and fails when they drift. After an intended change to the body, rewrite
the fixture from influora-ai/:

    python tests/prompt/test_frame_check_app_fixture.py --write
"""

from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Any

SERVICE_ROOT = Path(__file__).resolve().parents[2]  # influora-ai/
if str(SERVICE_ROOT) not in sys.path:
    sys.path.insert(0, str(SERVICE_ROOT))

from app.prompt.frame_check import parse_frame_check_reply, resolve_phone  # noqa: E402

FIXTURE = SERVICE_ROOT.parent / "src" / "lib" / "__fixtures__" / "shoot-check-frame-bodies.json"


def _reply(lang: str, scene: dict[str, str], steps: list[dict[str, str]], ok=(), cant_tell=(), ask=None) -> str:
    return json.dumps(
        {"lang": lang, "scene": scene, "steps": steps, "ok": list(ok), "cant_tell": list(cant_tell), "ask": ask}
    )


CASES: dict[str, dict[str, Any]] = {
    "bedroom_window_behind_en_a78": {
        "phone": "OPPO A78 5G",
        "raw": _reply(
            "en",
            {"usable": "yes", "place": "bedroom", "light": "window", "light_side": "behind_you",
             "background": "bright_window_behind", "phone_height": "below_eyes",
             "framing": "chest_up", "others_in_frame": "no"},
            [
                {"kind": "move_you", "note": "Window behind creator toward phone", "side": "none"},
                {"kind": "move_phone", "note": "Eye-level"},
                {"kind": "settings", "note": "Talking Head (Window light)"},
            ],
            ok=["framing_fits", "background_clean"],
            cant_tell=["audio", "room_behind_phone"],
            ask={"id": "can_move"},
        ),
    },
    "kitchen_tube_light_hi_no_phone": {
        "phone": None,
        "raw": _reply(
            "hi",
            {"usable": "yes", "place": "kitchen", "light": "tube_light", "light_side": "above",
             "background": "busy", "phone_height": "eye_level", "framing": "waist_up",
             "others_in_frame": "no"},
            [
                {"kind": "move_light", "note": "Kitchen/corridor mixed lights"},
                {"kind": "move_you", "note": "Kitchen"},
                {"kind": "settings", "note": "Food close-up (restaurant or home)"},
            ],
            ok=["phone_at_eye_level"],
            cant_tell=["light_outside_frame"],
            ask={"id": "other_light"},
        ),
    },
    "park_sun_en_find_x8_ultra": {
        "phone": "OPPO Find X8 Ultra",
        "raw": _reply(
            "en",
            {"usable": "yes", "place": "park", "light": "sun", "light_side": "above",
             "background": "clean", "phone_height": "eye_level", "framing": "full_body",
             "others_in_frame": "no"},
            [
                {"kind": "move_you", "note": "Harsh midday sun (sun high, your shadow short and right under you)"},
                {"kind": "settings", "note": "Outdoor Fitness"},
            ],
            ok=["phone_at_eye_level", "background_clean"],
            cant_tell=["audio", "shake_or_motion"],
        ),
    },
    "too_dark_en": {
        "phone": None,
        "raw": _reply(
            "en",
            {"usable": "too_dark", "place": "bedroom", "light": "low_light", "light_side": "unknown",
             "background": "unknown", "phone_height": "unknown", "framing": "unknown",
             "others_in_frame": "no"},
            [{"kind": "move_light", "note": "Ring light"}],
        ),
    },
}


def build_bodies() -> dict[str, dict[str, Any]]:
    """Each case's route body, exactly as `parse_frame_check_reply` returns it."""
    out: dict[str, dict[str, Any]] = {}
    for name, case in CASES.items():
        phone_row = resolve_phone(case["phone"]) if case["phone"] else None
        assert case["phone"] is None or phone_row is not None, f"{name}: {case['phone']!r} did not resolve"
        body = parse_frame_check_reply(case["raw"], phone_row=phone_row).body
        assert body is not None, f"{name}: the parser returned no body"
        out[name] = body
    return out


def _fixture_bytes(bodies: dict[str, dict[str, Any]]) -> bytes:
    text = json.dumps(bodies, ensure_ascii=False, indent=2) + "\n"
    return text.replace("\n", "\r\n").encode("utf-8")


def test_app_fixture_is_the_parsers_current_output():
    assert FIXTURE.is_file(), f"missing {FIXTURE}; run this file with --write"
    assert json.loads(FIXTURE.read_bytes().decode("utf-8")) == build_bodies(), (
        "src/lib/__fixtures__/shoot-check-frame-bodies.json no longer matches parse_frame_check_reply; "
        "if the change is intended, run: python tests/prompt/test_frame_check_app_fixture.py --write"
    )


def test_the_cases_cover_what_the_app_test_relies_on():
    bodies = build_bodies()
    assert {b["lang"] for b in bodies.values()} == {"en", "hi"}
    assert [n for n, b in bodies.items() if b["retake"]] == ["too_dark_en"]
    parts = [p for b in bodies.values() for s in b["steps"] for p in s.get("parts", [])]
    assert any(p["needs_pro"] for p in parts) and any(p["needs_ois"] for p in parts)
    assert all(s.get("label") for b in bodies.values() for s in b["steps"])


if __name__ == "__main__":
    if "--write" not in sys.argv[1:]:
        raise SystemExit("usage: python tests/prompt/test_frame_check_app_fixture.py --write")
    FIXTURE.parent.mkdir(parents=True, exist_ok=True)
    FIXTURE.write_bytes(_fixture_bytes(build_bodies()))
    print(f"wrote {FIXTURE} ({len(CASES)} bodies)")
