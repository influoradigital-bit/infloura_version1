"""Platform-AI Phase 1, W2a (Priya A2 / Ash's binding W2 criteria).

`chat.py` must server-source Block B from `POST /internal/meera/context`
(via `SpringInternalClient.get_meera_context`) and must NEVER let a
client-supplied `body["brand"]` or `body["prompt_version"]` influence the
assembled prompt. On a context-fetch failure it must degrade to an EMPTY
Block B rather than 500ing the turn.

These tests exercise `_fetch_brand_context` directly -- the pure async
helper that owns this contract -- rather than driving the full SSE `chat()`
route, since the shape/failure-mode of the fetch is exactly what's under
test here (the full-route money-path/tool-loop behavior is already covered
by `test_chat_money_path.py` / `test_chat_tool_result_data.py`).
"""

from __future__ import annotations

from unittest.mock import AsyncMock, MagicMock

import pytest

from app.clients.spring import SpringCallError, SpringResponse
from app.routes.chat import _fetch_brand_context

WORKSPACE_ID = "ws-context-source-001"


def _mock_spring_returning(data: dict) -> MagicMock:
    spring = MagicMock()
    spring.get_meera_context = AsyncMock(
        return_value=SpringResponse(status_code=200, data=data, raw={"data": data})
    )
    return spring


@pytest.mark.asyncio
async def test_fetch_brand_context_calls_spring_with_workspace_and_audience():
    spring = _mock_spring_returning({"display_name": "Lumos Skincare"})

    await _fetch_brand_context(
        spring=spring,
        workspace_id=WORKSPACE_ID,
        onbehalf_jwt="jwt-abc",
        request_id="req-1",
        conversation=[],
    )

    spring.get_meera_context.assert_awaited_once_with(
        workspace_id=WORKSPACE_ID, audience="BRAND", onbehalf_jwt="jwt-abc"
    )


@pytest.mark.asyncio
async def test_fetch_brand_context_maps_spring_response_into_assembler_shape():
    spring = _mock_spring_returning(
        {
            "display_name": "Lumos Skincare",
            "niche_tags": ["skincare"],
            "tone_dial": "clinical-friendly",
            "brand_color": "#2C6E49",
            "product_catalog": [{"name": "Serum", "price": 999, "currency": "INR"}],
            "template_digest": [
                {
                    "name": "UGC Content Pack",
                    "campaign_type": "STANDARD",
                    "budget_band": "₹5,000–₹20,000",
                    "key_requirements": "Deliver raw unedited files",
                }
            ],
            "past_campaign_summary": [{"type": "REVIEW", "creator_count": 4, "funded": True}],
            "credit_state": {"mode": "trial", "credits_remaining": 12},
        }
    )

    ctx = await _fetch_brand_context(
        spring=spring,
        workspace_id=WORKSPACE_ID,
        onbehalf_jwt="jwt-abc",
        request_id="req-2",
        conversation=[{"role": "user", "content": "hi"}],
    )

    assert ctx["workspace_id"] == WORKSPACE_ID
    assert ctx["audience"] == "BRAND"
    assert ctx["brand"]["display_name"] == "Lumos Skincare"
    assert ctx["brand"]["template_digest"][0]["name"] == "UGC Content Pack"
    assert ctx["brand"]["past_campaign_summary"][0]["creator_count"] == 4
    assert ctx["credit_state"]["credits_remaining"] == 12
    assert ctx["conversation"] == [{"role": "user", "content": "hi"}]


@pytest.mark.asyncio
async def test_fetch_brand_context_degrades_to_empty_on_spring_call_error():
    spring = MagicMock()
    spring.get_meera_context = AsyncMock(side_effect=SpringCallError(503, "spring_error", "down"))

    ctx = await _fetch_brand_context(
        spring=spring,
        workspace_id=WORKSPACE_ID,
        onbehalf_jwt="jwt-abc",
        request_id="req-3",
        conversation=[],
    )

    # Never raises -- the turn must still proceed with an empty Block B.
    assert ctx["workspace_id"] == WORKSPACE_ID
    assert ctx["audience"] == "BRAND"
    assert ctx["brand"] == {}
    assert ctx["credit_state"] == {}


@pytest.mark.asyncio
async def test_fetch_brand_context_degrades_to_empty_on_unexpected_exception():
    spring = MagicMock()
    spring.get_meera_context = AsyncMock(side_effect=TimeoutError("network hiccup"))

    ctx = await _fetch_brand_context(
        spring=spring,
        workspace_id=WORKSPACE_ID,
        onbehalf_jwt="jwt-abc",
        request_id="req-4",
        conversation=[],
    )

    assert ctx["brand"] == {}
    assert ctx["credit_state"] == {}


@pytest.mark.asyncio
async def test_fetch_brand_context_never_reads_client_brand_or_prompt_version():
    """The whole point of W2a: `_fetch_brand_context` takes NO client body at
    all -- it cannot leak a client-supplied `brand`/`prompt_version` because
    it has no parameter through which one could arrive. This test pins the
    signature contract so a future refactor can't accidentally reintroduce a
    `body` passthrough."""
    import inspect

    params = set(inspect.signature(_fetch_brand_context).parameters)
    assert params == {"spring", "workspace_id", "onbehalf_jwt", "request_id", "conversation"}
