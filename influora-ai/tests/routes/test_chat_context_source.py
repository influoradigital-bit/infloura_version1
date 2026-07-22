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
from app.prompt.assembler import build_block_b
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
async def test_fetch_brand_context_carries_outcome_digest_into_assembled_block_b():
    """Regression for the brand-surface audit's #1 BROKEN finding: Spring
    computes+serializes `outcome_digest` and the assembler can render it, but
    `_fetch_brand_context` silently dropped the key from `brand_fields`, so
    `build_block_b`'s `brand.get("outcome_digest")` was always `None` and no
    digest line ever reached a live prompt (audit: brand-feature-audit.md #1,
    brand-ai-feature-audit.md finding #6). This test drives the REAL seam --
    Spring response -> `_fetch_brand_context` -> `build_block_b` -- end to
    end, not just the assembler in isolation (see
    test_assembler_context_wiring.py for that half)."""
    spring = _mock_spring_returning(
        {
            "display_name": "Lumos Skincare",
            "outcome_digest": {
                "campaign_outcomes": [
                    {
                        "type": "REVIEW",
                        "creator_count": 3,
                        "funded": True,
                        "spend_inr": 15000,
                        "verified_reach": 42000,
                        "attributed_revenue_inr": 60000,
                    }
                ],
                "niche_rate_band": {
                    "niche": "skincare",
                    "currency": "INR",
                    "min": 5000,
                    "median": 9000,
                    "max": 18000,
                },
            },
        }
    )

    ctx = await _fetch_brand_context(
        spring=spring,
        workspace_id=WORKSPACE_ID,
        onbehalf_jwt="jwt-abc",
        request_id="req-outcome-digest",
        conversation=[],
    )

    # The dict-shape assertion the omission previously failed silently.
    assert ctx["brand"]["outcome_digest"] is not None
    assert ctx["brand"]["outcome_digest"]["campaign_outcomes"][0]["type"] == "REVIEW"

    # And the string that Meera's live prompt actually receives.
    block_b_text = build_block_b(ctx)["text"]
    assert "Campaign outcomes (platform-verified only)" in block_b_text
    assert "REVIEW x3 (funded, spend ₹15000)" in block_b_text
    assert "verified reach 42000" in block_b_text
    assert "attributed revenue ₹60000" in block_b_text
    assert "Real market rate band for 'skincare': INR 5000–18000 (median 9000)" in block_b_text


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
