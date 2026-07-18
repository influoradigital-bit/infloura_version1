"""Kavya (QA, BLOCKER): the live-canvas payload chain was dead because
POST /chat's SSE `tool_result` frame only ever emitted `{"name", "status"}` --
`tools/loop.py`'s `LoopEvent.tool_result_data` (the real Spring payload:
creator lists, budget calcs, etc.) was computed and carried on the event but
never serialized into the wire frame. The frontend's useMeeraStream.ts parses
`event.data`, so the canvas never received anything to render.

This test drives `chat()` end-to-end (real StreamingResponse, drained), with
`run_tool_loop` replaced by a small scripted async generator so no real
Claude/Spring calls happen, and asserts the SSE `tool_result` frame's parsed
JSON carries `data` equal to the LoopEvent's `tool_result_data` verbatim.
"""

from __future__ import annotations

import json
from unittest.mock import AsyncMock, patch

import pytest
from fastapi import Request

from app.auth.service_token import VerifiedToken
from app.costs import spend_tracker
from app.routes import chat as chat_route
from app.tools.loop import LoopEvent

WORKSPACE_ID = "ws-tool-result-data-001"


def _make_request(body: dict) -> Request:
    body_bytes = json.dumps(body).encode()

    async def receive():
        return {"type": "http.request", "body": body_bytes, "more_body": False}

    scope = {
        "type": "http",
        "method": "POST",
        "path": "/chat",
        "headers": [],
        "query_string": b"",
        "client": ("test", 0),
    }
    return Request(scope, receive)


def _verified_service_token() -> VerifiedToken:
    # Service token: no conversation_id binding to worry about (see
    # test_chat_conversation_binding.py for that separate gate).
    return VerifiedToken(
        workspace_id=WORKSPACE_ID, scope="service", subject="spring-service", conversation_id=None, claims={}
    )


CREATOR_PAYLOAD = {"creators": [{"id": "c1", "name": "Test Creator", "niche": "fitness"}]}


async def _fake_run_tool_loop(**kwargs):
    """Scripted loop: one tool_start/tool_result pair carrying real payload
    data, then done. No text tokens -> chat.py skips the Spring
    persist_assistant_message call entirely (final_text is empty), so no
    Spring mocking is needed either."""
    yield LoopEvent(type="tool_start", tool_name="show_creators", tool_input={"niche": "fitness", "count": 3})
    yield LoopEvent(
        type="tool_result",
        tool_name="show_creators",
        tool_status="ok",
        tool_result_data=CREATOR_PAYLOAD,
    )
    yield LoopEvent(type="done", finish_reason="stop", usage={"input_tokens": 10, "output_tokens": 5})


def _parse_sse_frames(raw: str) -> list[tuple[str, dict]]:
    """Parses `event: X\\ndata: {...}\\n\\n` blocks into (event_name, data) pairs.
    Skips heartbeat comment lines (`: ping`)."""
    frames: list[tuple[str, dict]] = []
    for block in raw.split("\n\n"):
        block = block.strip()
        if not block or block.startswith(":"):
            continue
        event_name = None
        data = None
        for line in block.split("\n"):
            if line.startswith("event:"):
                event_name = line[len("event:") :].strip()
            elif line.startswith("data:"):
                data = json.loads(line[len("data:") :].strip())
        if event_name is not None and data is not None:
            frames.append((event_name, data))
    return frames


@pytest.mark.asyncio
async def test_tool_result_sse_frame_includes_data(monkeypatch):
    monkeypatch.delenv("AI_SPEND_KILL_SWITCH", raising=False)
    monkeypatch.delenv("AI_DAILY_SPEND_CEILING_USD", raising=False)
    await spend_tracker.reset_for_testing()

    body = {
        "workspace_id": WORKSPACE_ID,
        "conversation_id": "conv-1",
        "service_token": "irrelevant-because-verify_token-is-mocked",
        "conversation": [],
    }
    request = _make_request(body)
    request.is_disconnected = AsyncMock(return_value=False)

    with patch.object(
        chat_route, "verify_token_async", AsyncMock(return_value=_verified_service_token())
    ), patch.object(
        chat_route, "run_tool_loop", _fake_run_tool_loop
    ):
        response = await chat_route.chat(request, authorization="Bearer whatever")

        raw_body = b""
        async for chunk in response.body_iterator:
            raw_body += chunk if isinstance(chunk, bytes) else chunk.encode()

    frames = _parse_sse_frames(raw_body.decode())
    tool_result_frames = [data for name, data in frames if name == "tool_result"]

    assert len(tool_result_frames) == 1
    assert tool_result_frames[0] == {
        "name": "show_creators",
        "status": "ok",
        "data": CREATOR_PAYLOAD,
    }

    # Sanity: tool_start / done frames are still present and unaffected.
    event_names = [name for name, _ in frames]
    assert "tool_start" in event_names
    assert "done" in event_names


@pytest.mark.asyncio
async def test_tool_result_sse_frame_data_is_null_when_no_payload(monkeypatch):
    """An error-path tool_result (e.g. unknown tool, Spring call failure) may
    carry no `tool_result_data` -- the frame must still include a `data` key
    (JSON null), not omit it, so the frontend's parser has a stable shape."""
    monkeypatch.delenv("AI_SPEND_KILL_SWITCH", raising=False)
    monkeypatch.delenv("AI_DAILY_SPEND_CEILING_USD", raising=False)
    await spend_tracker.reset_for_testing()

    async def _fake_loop_no_payload(**kwargs):
        yield LoopEvent(type="tool_result", tool_name="unknown_tool", tool_status="error", tool_result_data=None)
        yield LoopEvent(type="done", finish_reason="stop", usage=None)

    body = {
        "workspace_id": WORKSPACE_ID,
        "conversation_id": "conv-1",
        "service_token": "irrelevant-because-verify_token-is-mocked",
        "conversation": [],
    }
    request = _make_request(body)
    request.is_disconnected = AsyncMock(return_value=False)

    with patch.object(
        chat_route, "verify_token_async", AsyncMock(return_value=_verified_service_token())
    ), patch.object(
        chat_route, "run_tool_loop", _fake_loop_no_payload
    ):
        response = await chat_route.chat(request, authorization="Bearer whatever")

        raw_body = b""
        async for chunk in response.body_iterator:
            raw_body += chunk if isinstance(chunk, bytes) else chunk.encode()

    frames = _parse_sse_frames(raw_body.decode())
    tool_result_frames = [data for name, data in frames if name == "tool_result"]
    assert len(tool_result_frames) == 1
    assert tool_result_frames[0]["data"] is None
