"""Seam link 1 of the photo-check chain: what the REAL route answers is the body Spring is tested on.

The chain (photo check inside Meera's chat, 2026-09-26):

1. influora-ai: `POST /ai/shoot-check/frame` answers `{**parse_frame_check_reply(...).body,
   "fallback": False}` on success (app/routes/shoot_check.py).
2. influora-api: `CreatorMeeraControllerPhotoCheckTest` reads the committed parser fixture
   `src/lib/__fixtures__/shoot-check-frame-bodies.json`, appends `"fallback": false` LAST
   (`routeShaped`), feeds it in as influora-ai's reply, and pins what Spring answers and stores in
   `photo-check-chat-bodies.json` / `meera-history-with-card.json`.
3. The app's vitest reads those two Spring fixtures.

`tests/prompt/test_frame_check_app_fixture.py` proves the parser fixture is the parser's output,
but nothing proved the ROUTE wraps it the way Java assumes: a route that renamed `fallback`,
nested the body, dropped a key or put `fallback` first would leave every per-service test green
while Spring stopped writing the chat pair. This drives the real route function (only the model
and Spring context are stubbed) with each fixture case's model reply and saved phone, and asserts
the answer is exactly the fixture body with `fallback: False` appended, key order included.
"""

from __future__ import annotations

import json

import pytest
from fastapi.responses import JSONResponse

from tests.prompt.test_frame_check_app_fixture import CASES, FIXTURE
from tests.routes.test_shoot_check import (  # noqa: F401 -- _reset is the autouse spend/env reset
    CREATOR_ID,
    _call,
    _claude_text,
    _consented_spring,
    _reset,
)


def _fixture() -> dict:
    assert FIXTURE.is_file(), f"missing {FIXTURE}"
    return json.loads(FIXTURE.read_bytes().decode("utf-8"))


def test_every_fixture_case_is_driven_here():
    assert sorted(_fixture()) == sorted(CASES), "a fixture body without a route run is an unproven seam"


@pytest.mark.asyncio
@pytest.mark.parametrize("name", sorted(CASES))
async def test_the_route_answers_the_fixture_body_plus_fallback_false_last(name):
    case = CASES[name]
    expected = {**_fixture()[name], "fallback": False}

    result = await _call(
        "CREATOR",
        CREATOR_ID,
        claude=_claude_text(case["raw"]),
        spring=_consented_spring(),
        shot_label="0-3s · Talking to camera by the window",
        phone_model=case["phone"],
    )

    assert isinstance(result, dict), f"{name}: the route answered {type(result).__name__}, not the success body"
    # Spring's writer keys on a strict Boolean false (Ash 3): it must be the JSON literal false.
    assert result["fallback"] is False
    assert result == expected
    # Java's routeShaped appends "fallback" as the LAST key; pin the same order here.
    assert list(result) == list(expected)
    # And what goes over the wire parses back to the same tree.
    assert json.loads(JSONResponse(result).body) == expected
