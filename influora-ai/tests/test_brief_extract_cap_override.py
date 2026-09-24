"""T-CREATOR-CREDITS-V2 -- acceptance criterion A45 (SPEC.md section 12, ruling C22).

With CREATOR_CREDITS_ENABLED on, Spring sends `brief_monthly_cap_usd` (the $12.00 brief backstop)
on POST /internal/brief-extract. The route must enforce `max(config, provided)`:

  * a provided cap ABOVE the process default raises this creator's brief allowance (a paying
    creator is not stopped by the old $0.25/month default);
  * a provided cap BELOW the default never lowers it;
  * no provided cap (flag off) leaves today's behaviour exactly as it is.

Driven through the real `check_creator_spend_gate` and the real in-memory spend ledger, so the
test fails if the override is dropped, applied as a plain replacement, or passed somewhere the
gate does not read. Auth (`verify_creator_token`) and Claude are mocked.
"""

from __future__ import annotations

import json
from datetime import date, timedelta
from decimal import Decimal
from typing import Any
from unittest.mock import AsyncMock, MagicMock, patch

import pytest
from fastapi import Request

from app.config import get_settings
from app.costs import spend_tracker
from app.providers.claude import ClaudeToolResult
from app.routes import brief_extract as brief_extract_route

CREATOR_PROFILE_ID = "creator-profile-cap-override-001"
SPEND_KEY = f"{CREATOR_PROFILE_ID}:brief"
CONFIG_CAP_USD = "0.25"

_DEADLINE_DATE = date.today() + timedelta(days=30)
_DEADLINE = _DEADLINE_DATE.isoformat()

RAW_BRIEF = (
    "Hi! This is Glow Cosmetics. We'd love 1 reel and 1 story set for our new "
    f"Vitamin C serum. Budget is 8000 INR, live by {_DEADLINE}. 60 days category "
    "exclusivity, organic usage only, 50% advance."
)

VALID_TOOL_INPUT: dict[str, Any] = {
    "brand_name": "Glow Cosmetics",
    "product": "Vitamin C serum",
    "category": "BEAUTY",
    "deliverables": [{"type": "REEL", "qty": 1}, {"type": "STORY_SET", "qty": 1}],
    "budget_inr": 8000,
    "budget_stated": True,
    "barter_only": False,
    "deadline": _DEADLINE,
    "usage_perpetual": False,
    "usage_channels": ["ORGANIC"],
    "exclusivity_days": 60,
    "exclusivity_scope": "CATEGORY",
    "exclusivity_brands": [],
    "payment_terms": "50% advance",
    "off_platform_payment_hint": False,
    "disclosure_hidden_hint": False,
    "claims": [],
    "vague_deliverables": False,
    "summary_lines": [
        "Glow Cosmetics wants 1 reel + 1 story set for a Vitamin C serum",
        "Budget stated: 8,000",
        f"Deadline {_DEADLINE_DATE.day} {_DEADLINE_DATE.strftime('%b')}",
        "60 days category exclusivity",
        "Organic usage only",
    ],
}

CAP_BODY = {"success": False, "error": {"code": brief_extract_route.CAP_ERROR_CODE}}


def _make_request(body: dict[str, Any]) -> Request:
    body_bytes = json.dumps(body).encode()

    async def receive():
        return {"type": "http.request", "body": body_bytes, "more_body": False}

    scope = {
        "type": "http",
        "method": "POST",
        "path": "/internal/brief-extract",
        "headers": [(b"authorization", b"Bearer creator-token-FAKE")],
        "query_string": b"",
        "client": ("test", 0),
    }
    return Request(scope, receive)


def _body(**overrides: Any) -> dict[str, Any]:
    body = {
        "creator_profile_id": CREATOR_PROFILE_ID,
        "raw_text": RAW_BRIEF,
        "creator_language": "en",
    }
    body.update(overrides)
    return body


async def _call(body: dict[str, Any]) -> tuple[Any, AsyncMock, AsyncMock]:
    """Runs the route with the REAL creator gate, wrapped in a spy so the cap it received can be
    read back. Returns (response, claude mock, gate spy)."""
    claude = AsyncMock()
    claude.complete_with_forced_tool = AsyncMock(
        return_value=ClaudeToolResult(ok=True, tool_input=VALID_TOOL_INPUT, error=None, usage=None)
    )
    gate_spy = AsyncMock(side_effect=spend_tracker.check_creator_spend_gate)
    with patch.object(brief_extract_route, "verify_creator_token", MagicMock(return_value=None)), \
         patch.object(brief_extract_route, "_get_claude", return_value=claude), \
         patch.object(brief_extract_route, "check_creator_spend_gate", gate_spy):
        response = await brief_extract_route.brief_extract(
            _make_request(body), authorization="Bearer creator-token-FAKE"
        )
    return response, claude, gate_spy


@pytest.fixture(autouse=True)
async def _reset(monkeypatch):
    monkeypatch.delenv("REDIS_URL", raising=False)
    monkeypatch.setenv("BRIEF_EXTRACT_MONTHLY_CAP_USD", CONFIG_CAP_USD)
    monkeypatch.setenv("AI_RESERVATION_PER_CALL_USD", "0.02")
    get_settings.cache_clear()
    await spend_tracker.reset_for_testing()
    yield
    monkeypatch.delenv("BRIEF_EXTRACT_MONTHLY_CAP_USD", raising=False)
    monkeypatch.delenv("AI_RESERVATION_PER_CALL_USD", raising=False)
    get_settings.cache_clear()
    await spend_tracker.reset_for_testing()


@pytest.mark.asyncio
async def test_provided_cap_raises_limit():
    """A45 / C22: the brief-extract gate enforces max(config, provided)."""
    assert get_settings().brief_extract_monthly_cap_usd == pytest.approx(float(CONFIG_CAP_USD))

    # This creator has already spent $1.00 of brief extraction this month -- 4x the $0.25 default.
    await spend_tracker.record_creator_spend(Decimal("1.00"), SPEND_KEY)

    # 1. Flag off (no override): today's behaviour, the default cap refuses with the 200 cap body
    #    and no provider call.
    response, claude, gate = await _call(_body())
    assert response == CAP_BODY
    claude.complete_with_forced_tool.assert_not_awaited()
    assert float(gate.await_args.kwargs["cap_usd"]) == pytest.approx(0.25)

    # 2. Flag on: Spring's $12.00 backstop RAISES the limit, so the same creator is served.
    response, claude, gate = await _call(_body(brief_monthly_cap_usd="12.00"))
    assert response.get("success") is True, f"provided cap 12.00 did not raise the limit: {response}"
    claude.complete_with_forced_tool.assert_awaited_once()
    assert float(gate.await_args.kwargs["cap_usd"]) == pytest.approx(12.00)
    assert gate.await_args.args[0] == SPEND_KEY
    assert gate.await_args.args[1] == "CREATOR"

    # A numeric (non-string) override is honoured the same way.
    response, claude, gate = await _call(_body(brief_monthly_cap_usd=12))
    assert response.get("success") is True
    assert float(gate.await_args.kwargs["cap_usd"]) == pytest.approx(12.0)

    # 3. A provided cap BELOW the default never lowers it: max(0.25, 0.10) == 0.25.
    await spend_tracker.reset_for_testing()
    await spend_tracker.record_creator_spend(Decimal("0.15"), SPEND_KEY)  # under 0.25 (+0.02 hold), over 0.10
    response, claude, gate = await _call(_body(brief_monthly_cap_usd="0.10"))
    assert response.get("success") is True, f"a lower provided cap lowered the default: {response}"
    claude.complete_with_forced_tool.assert_awaited_once()
    assert float(gate.await_args.kwargs["cap_usd"]) == pytest.approx(0.25)

    # A zero / negative override must not disable the cap either (the gate treats <= 0 as "off").
    for disabling in ("0", "-5"):
        response, claude, gate = await _call(_body(brief_monthly_cap_usd=disabling))
        assert float(gate.await_args.kwargs["cap_usd"]) == pytest.approx(0.25), disabling

    # 4. The raised limit is still a limit: past $12.00 the creator is refused.
    await spend_tracker.reset_for_testing()
    await spend_tracker.record_creator_spend(Decimal("12.00"), SPEND_KEY)
    response, claude, gate = await _call(_body(brief_monthly_cap_usd="12.00"))
    assert response == CAP_BODY
    claude.complete_with_forced_tool.assert_not_awaited()

    # 5. An unparseable override is ignored, never a crash and never "no cap".
    response, claude, gate = await _call(_body(brief_monthly_cap_usd="twelve"))
    assert response == CAP_BODY
    assert float(gate.await_args.kwargs["cap_usd"]) == pytest.approx(0.25)
