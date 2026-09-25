"""The creator write-back carries what Meera recommended (Meera intelligence v1, slice 2, spec 8.3).

Drives `chat()` end to end (real StreamingResponse, drained) with the tool loop scripted and
Spring mocked -- the tests/routes/test_chat_creator_audience.py pattern -- and inspects the one
`persist_assistant_message` call (POST /internal/meera/messages):

- a creator turn that ran plan_my_week and answered with a week plan sends
  `metadata.recommendations` whose items and metadata keys are the keys of the fixture Java
  proved it accepts (tests/fixtures/creator_tools/writeback_recommendations.sample.json), plus
  `prompt_version` and `knowledge_version`;
- the plan dates come from THIS turn's plan_my_week result: without one (or with an errored one)
  nothing is recorded;
- a brand turn never carries recommendations or a knowledge version;
- a failed or refused turn records nothing, and a parser failure never stops the write-back.
"""

from __future__ import annotations

import base64
import json
from typing import Any
from unittest.mock import AsyncMock, MagicMock, patch

import pytest
from fastapi import Request

from app.auth.service_token import VerifiedToken
from app.clients.spring import SpringResponse
from app.config import PROMPT_VERSION, get_settings
from app.costs import spend_tracker
from app.prompt.content_knowledge import CREATOR_KNOWLEDGE_VERSION
from app.recommendations import record
from app.routes import chat as chat_route
from app.tools.creator_schemas import CREATOR_TOOL_NAMES, PLAN_MY_WEEK
from app.tools.loop import LoopEvent
from tests.recommendations.test_record import PERSONA_PLAN, WEEK

SAMPLE = (
    __import__("pathlib").Path(__file__).resolve().parents[1]
    / "fixtures"
    / "creator_tools"
    / "writeback_recommendations.sample.json"
)
CREATOR_ID = "creator-rec-writeback-001"
MESSAGE_ID = "01HMESSAGE_REC_WRITEBACK_AAAA"


def _b64url(obj: dict) -> str:
    return base64.urlsafe_b64encode(json.dumps(obj).encode()).decode().rstrip("=")


def _jwt(user_type: str) -> str:
    return f"{_b64url({'alg': 'HS256'})}.{_b64url({'sub': 'u1', 'userType': user_type})}.sig"


def _request(user_type: str) -> Request:
    body = json.dumps(
        {
            "workspace_id": CREATOR_ID,
            "conversation_id": "conv-rec-1",
            "onbehalf_jwt": _jwt(user_type),
            "conversation": [{"role": "user", "content": "plan my week"}],
        }
    ).encode()

    async def receive():
        return {"type": "http.request", "body": body, "more_body": False}

    request = Request(
        {"type": "http", "method": "POST", "path": "/chat", "headers": [], "query_string": b"", "client": ("t", 0)},
        receive,
    )
    request.is_disconnected = AsyncMock(return_value=False)  # type: ignore[method-assign]
    return request


def _verified(user_type: str) -> VerifiedToken:
    return VerifiedToken(
        workspace_id=CREATOR_ID,
        scope="chat:stream",
        subject="u1",
        conversation_id="conv-rec-1",
        claims={"userType": user_type, "messageId": MESSAGE_ID},
    )


def _spring(user_type: str) -> MagicMock:
    if user_type == "CREATOR":
        context: dict[str, Any] = {
            "workspace_id": CREATOR_ID,
            "audience": "CREATOR",
            "first_name": "Priya",
            "categories": ["Fashion"],
            "consent_accepted": True,
            "tools_enabled": list(CREATOR_TOOL_NAMES),
        }
    else:
        context = {"workspace_id": CREATOR_ID, "display_name": "Acme"}
    spring = MagicMock()
    spring.get_meera_context = AsyncMock(return_value=SpringResponse(status_code=200, data=context, raw={"data": context}))
    spring.persist_assistant_message = AsyncMock(return_value=SpringResponse(status_code=200, data={}, raw={}))
    spring.release_turn_credit = AsyncMock(return_value=SpringResponse(status_code=200, data={}, raw={}))
    return spring


def _loop(*, plan: Any = WEEK, plan_status: str = "ok", text: str = PERSONA_PLAN, end: LoopEvent | None = None):
    async def _run(**_kwargs):
        if plan is not None:
            yield LoopEvent(type="tool_start", tool_name=PLAN_MY_WEEK, tool_input={})
            yield LoopEvent(type="tool_result", tool_name=PLAN_MY_WEEK, tool_status=plan_status, tool_result_data=plan)
        for chunk in (text[: len(text) // 2], text[len(text) // 2 :]):  # a plan line split across tokens
            yield LoopEvent(type="token", text=chunk)
        yield end or LoopEvent(
            type="done", finish_reason="stop", usage={"input_tokens": 10, "output_tokens": 5}, stop_reason="end_turn"
        )

    return _run


async def _run_turn(user_type: str, loop) -> MagicMock:
    spring = _spring(user_type)
    with patch.object(chat_route, "verify_token_async", AsyncMock(return_value=_verified(user_type))), patch.object(
        chat_route, "_get_spring", return_value=spring
    ), patch.object(chat_route, "_get_claude", return_value=MagicMock()), patch.object(
        chat_route, "run_tool_loop", loop
    ):
        response = await chat_route.chat(_request(user_type), authorization=None)
        assert response.status_code == 200
        async for _ in response.body_iterator:
            pass
    return spring


def _metadata(spring: MagicMock) -> dict[str, Any]:
    spring.persist_assistant_message.assert_awaited_once()
    return spring.persist_assistant_message.call_args.kwargs["metadata"]


@pytest.fixture(autouse=True)
async def _reset(monkeypatch):
    for var in ("AI_SPEND_KILL_SWITCH", "WORKSPACE_DAILY_HARD_CAP_USD", "REDIS_URL"):
        monkeypatch.delenv(var, raising=False)
    monkeypatch.setenv("AI_CREATOR_MONTHLY_CAP_USD", "5")
    monkeypatch.setenv("AI_DAILY_SPEND_CEILING_USD", "999999")
    get_settings.cache_clear()
    await spend_tracker.reset_for_testing()
    yield
    get_settings.cache_clear()
    await spend_tracker.reset_for_testing()


# ------------------------------------------------------------------ the happy path


@pytest.mark.asyncio
async def test_a_creator_week_plan_turn_sends_its_recommendations():
    spring = await _run_turn("CREATOR", _loop())
    kwargs = spring.persist_assistant_message.call_args.kwargs
    assert kwargs["turn_id"] == MESSAGE_ID  # source_ref = messageId:line_index on the server
    assert kwargs["content"] == PERSONA_PLAN
    metadata = kwargs["metadata"]
    assert metadata["prompt_version"] == PROMPT_VERSION
    assert metadata["knowledge_version"] == CREATOR_KNOWLEDGE_VERSION
    recs = metadata["recommendations"]
    assert [r["line_index"] for r in recs] == [0, 1, 2, 4, 5, 6]
    assert {r["source"] for r in recs} == {"PLAN_MY_WEEK"}
    assert recs[0]["recommended_for"] == "2026-09-28"


@pytest.mark.asyncio
async def test_the_payload_has_the_shape_java_proved_it_accepts():
    """Every metadata key and every item key is one the checked-in fixture carries (the file
    WritebackRecommendationsFixtureTest deserialises with the application's ObjectMapper), and
    every value has the fixture's type/format."""
    sample = json.loads(SAMPLE.read_text(encoding="utf-8"))
    sample_meta_keys = set(sample["metadata"])
    sample_item_keys = {k for item in sample["metadata"]["recommendations"] for k in item}

    metadata = _metadata(await _run_turn("CREATOR", _loop()))
    assert set(metadata) <= sample_meta_keys
    assert {"prompt_version", "knowledge_version", "recommendations"} <= set(metadata)
    assert isinstance(metadata["knowledge_version"], str) and len(metadata["knowledge_version"]) <= 32
    assert isinstance(metadata["prompt_version"], str) and len(metadata["prompt_version"]) <= 32
    assert 0 < len(metadata["recommendations"]) <= 7
    for item in metadata["recommendations"]:
        assert set(item) == sample_item_keys
        assert item["source"] in {"PLAN_MY_WEEK", "SCRIPT_CARD"}
        assert item["post_type"] in {"REEL", "CAROUSEL", "POST"}
        assert isinstance(item["line_index"], int) and item["line_index"] >= 0
        assert item["recommended_for"] is None or len(item["recommended_for"]) == 10
        for key in ("window_from", "window_to"):
            assert item[key] is None or (len(item[key]) == 5 and item[key][2] == ":")
        for key in ("window_label", "structure_name", "hook_template", "topic", "festival"):
            assert item[key] is None or isinstance(item[key], str)
    # it survives the JSON the client actually signs and sends
    assert json.loads(json.dumps(metadata, sort_keys=True)) == metadata


@pytest.mark.asyncio
async def test_a_script_card_turn_sends_one_script_item():
    script = json.loads((SAMPLE.parent.parent / "meera_scripts.json").read_text(encoding="utf-8"))["cases"][0]["text"]
    metadata = _metadata(await _run_turn("CREATOR", _loop(plan=None, text=script)))
    assert [(r["source"], r["post_type"], r["topic"]) for r in metadata["recommendations"]] == [
        ("SCRIPT_CARD", "REEL", "3 saffron mistakes to avoid")
    ]


# ------------------------------------------------------------------ dates only from this turn's plan


@pytest.mark.asyncio
async def test_a_week_plan_without_a_plan_result_on_the_turn_records_nothing():
    metadata = _metadata(await _run_turn("CREATOR", _loop(plan=None)))
    assert "recommendations" not in metadata
    assert metadata["knowledge_version"] == CREATOR_KNOWLEDGE_VERSION


@pytest.mark.asyncio
async def test_an_errored_plan_result_is_not_a_source_of_dates():
    metadata = _metadata(await _run_turn("CREATOR", _loop(plan_status="error")))
    assert "recommendations" not in metadata


# ------------------------------------------------------------------ never from brand, failed, refused


@pytest.mark.asyncio
async def test_a_brand_turn_never_carries_recommendations():
    metadata = _metadata(await _run_turn("BRAND", _loop()))
    assert "recommendations" not in metadata
    assert "knowledge_version" not in metadata


@pytest.mark.asyncio
async def test_a_turn_whose_provider_failed_after_the_text_records_nothing():
    metadata = _metadata(await _run_turn("CREATOR", _loop(end=LoopEvent(type="error", error_code="provider_error"))))
    assert "recommendations" not in metadata


@pytest.mark.asyncio
async def test_a_refused_turn_records_nothing():
    end = LoopEvent(type="done", finish_reason="stop", usage={"input_tokens": 1, "output_tokens": 1}, stop_reason="refusal")
    metadata = _metadata(await _run_turn("CREATOR", _loop(end=end)))
    assert "recommendations" not in metadata


# ------------------------------------------------------------------ failure isolation


@pytest.mark.asyncio
async def test_a_parser_crash_never_stops_the_write_back(monkeypatch):
    def boom(*_a, **_k):
        raise RuntimeError("parser bug")

    monkeypatch.setattr(record, "build_recommendations", boom)
    spring = await _run_turn("CREATOR", _loop())
    metadata = _metadata(spring)
    assert "recommendations" not in metadata
    assert spring.persist_assistant_message.call_args.kwargs["content"] == PERSONA_PLAN
    spring.release_turn_credit.assert_not_awaited()


@pytest.mark.asyncio
async def test_even_a_crash_in_the_gate_itself_never_stops_the_write_back(monkeypatch):
    monkeypatch.setattr(chat_route, "recommendations_for_turn", MagicMock(side_effect=ValueError("gate bug")))
    spring = await _run_turn("CREATOR", _loop())
    metadata = _metadata(spring)
    assert "recommendations" not in metadata
    assert metadata["knowledge_version"] == CREATOR_KNOWLEDGE_VERSION
