"""Creator-tool dispatch through `app.tools.loop` (SPEC.md §7.3).

WHY THIS FILE EXISTS. Accepting creator tools is two edits that must land
together, and the tree had no test that could tell them apart:

  1. `schemas.is_known_tool` — on false the loop yields an `unknown_tool`
     error result and skips the call.
  2. the Spring path lookup in `loop.py` — which sits OUTSIDE the enclosing
     `try`, so a name that passes (1) but is missing from the path maps raises
     an unhandled `KeyError` in the middle of a live SSE stream. The client
     sees the connection die mid-sentence; nothing is logged as a tool error.

Widening (1) alone is therefore strictly worse than not widening it at all,
and every pre-existing loop test only ever exercised brand tools, so it would
have shipped green. These cases pin both halves plus the third state — a name
that is known but has no route in either map, which is exactly what a schema
shipped ahead of its Spring endpoint looks like — and the two idempotency
sites, which must agree with each other.
"""

from __future__ import annotations

from typing import Any
from unittest.mock import patch

import pytest

from app.clients.spring import SpringResponse
from app.providers.claude import ClaudeStreamEvent
from app.tools import loop as loop_module
from app.tools.creator_schemas import (
    CREATOR_IDEMPOTENT_REQUIRED_TOOLS,
    CREATOR_TOOL_NAMES,
    CREATOR_TOOL_TO_SPRING_PATH,
    DRAFT_REPLY,
    GET_MY_DEALS,
    all_creator_tool_schemas,
    get_creator_tool_schemas,
)
from app.tools.loop import ToolLoopContext, run_tool_loop
from app.tools.schemas import (
    ANALYZE_SITE,
    REQUEST_PAYMENT,
    SHOW_CREATORS,
    get_tool_schemas,
    is_known_tool,
)

WORKSPACE_ID = "creator-user-dispatch-001"


class _FakeClaude:
    """One scripted `stream_turn` per tool-loop iteration."""

    def __init__(self, turns: list[list[ClaudeStreamEvent]]):
        self._turns = list(turns)
        self.call_count = 0

    def stream_turn(self, **kwargs: Any):
        events = self._turns[self.call_count]
        self.call_count += 1

        async def _gen():
            for event in events:
                yield event

        return _gen()


class _RecordingSpring:
    """Records every forward and returns a fixed payload."""

    def __init__(self, data: Any = None):
        self.calls: list[dict[str, Any]] = []
        self._data = {"deals": [{"id": "d-1", "status": "ACTIVE"}]} if data is None else data

    async def call_tool_endpoint(self, **kwargs: Any) -> SpringResponse:
        self.calls.append(kwargs)
        return SpringResponse(status_code=200, data=self._data, raw={"data": self._data})


def _ctx() -> ToolLoopContext:
    return ToolLoopContext(workspace_id=WORKSPACE_ID, onbehalf_jwt="fake-jwt", max_iterations=6)


def _turn_calling(tool_name: str, tool_input: dict[str, Any] | None = None):
    return [
        [
            ClaudeStreamEvent(
                type="tool_use",
                tool_name=tool_name,
                tool_input=tool_input or {},
                tool_use_id="tool_1",
            ),
        ],
        [ClaudeStreamEvent(type="text", text="Here are your deals.")],
    ]


async def _run(claude: _FakeClaude, spring: Any, tools: list[dict[str, Any]] | None = None):
    """`tools` is what this turn OFFERED. It defaults to the full creator set
    because that is what `assemble_prompt` hands the loop on a creator turn —
    and since the per-turn gate landed, a creator-tool case run with the
    default `tools=None` (= the BRAND set) would be refused as not-offered,
    which is the gate working, not the dispatch path being exercised. Pass the
    brand set explicitly for the brand cases."""
    return [
        event
        async for event in run_tool_loop(
            claude=claude,
            spring=spring,
            system_blocks=[],
            initial_messages=[],
            ctx=_ctx(),
            tools=all_creator_tool_schemas() if tools is None else tools,
        )
    ]


# --------------------------------------------------------------- the two halves


@pytest.mark.parametrize("tool_name", list(CREATOR_TOOL_NAMES))
def test_every_creator_tool_is_known_and_routable(tool_name: str):
    """Half 1 and half 2 as a pair, statically: a name `is_known_tool` accepts
    must have a Spring path, or `loop.py` KeyErrors mid-stream."""
    assert is_known_tool(tool_name)
    assert CREATOR_TOOL_TO_SPRING_PATH[tool_name] == f"/internal/meera/creator/{tool_name}"


@pytest.mark.asyncio
async def test_a_creator_tool_resolves_to_its_creator_spring_path():
    """The live path: a creator tool call reaches Spring on
    `/internal/meera/creator/<name>` — never the brand `/internal/meera/<name>`
    prefix, which is a different controller behind a different scope ladder."""
    claude = _FakeClaude(_turn_calling(GET_MY_DEALS, {"status": "active"}))
    spring = _RecordingSpring()

    events = await _run(claude, spring)

    assert len(spring.calls) == 1
    assert spring.calls[0]["path"] == "/internal/meera/creator/get_my_deals"
    assert spring.calls[0]["tool_name"] == GET_MY_DEALS
    assert not [e for e in events if e.type == "tool_result" and e.tool_status == "error"]


@pytest.mark.asyncio
async def test_a_known_but_unmapped_tool_degrades_to_an_error_result():
    """The regression this file was written for. A name `is_known_tool`
    accepts but no path map carries — a schema shipped ahead of its Spring
    endpoint — must come back as an error tool_result the model can narrate,
    NOT an unhandled KeyError that kills the stream."""
    claude = _FakeClaude(_turn_calling(GET_MY_DEALS))
    spring = _RecordingSpring()

    # Simulate the skew by emptying the creator path map while the name stays
    # known. Before the §7.3 fix this raised KeyError out of `run_tool_loop`.
    with patch.object(loop_module, "CREATOR_TOOL_TO_SPRING_PATH", {}):
        events = await _run(claude, spring)

    assert spring.calls == []  # nothing was forwarded anywhere
    errors = [e for e in events if e.type == "tool_result" and e.tool_status == "error"]
    assert len(errors) == 1
    assert errors[0].tool_result_data["error"] == "tool_not_routable"
    # The turn still completed: the model got the error back and answered.
    assert [e.type for e in events if e.type == "done"]
    assert claude.call_count == 2


# ------------------------------------------------------------- data pass-through


@pytest.mark.asyncio
async def test_creator_tool_result_data_passes_through_unchanged():
    """§7.3 / §8.4: the frontend renders creator cards straight off this
    payload, so the loop must not reshape, filter or rename a field."""
    payload = {
        "quote": {"total": "INR 12,000", "anchor": "INR 13,800", "provenance": "benchmark, not market data"},
        "lines": [{"type": "REEL", "qty": 1, "amount": "INR 12,000"}],
    }
    claude = _FakeClaude(_turn_calling(GET_MY_DEALS))
    spring = _RecordingSpring(data=payload)

    events = await _run(claude, spring)

    results = [e for e in events if e.type == "tool_result" and e.tool_status == "ok"]
    assert len(results) == 1
    assert results[0].tool_result_data == payload


# ---------------------------------------------------------------- idempotency


@pytest.mark.asyncio
async def test_a_read_creator_tool_carries_no_key_and_may_retry():
    """The five B0 reads and the draft tool are not commit-like: no
    idempotency key, retry allowed."""
    claude = _FakeClaude(_turn_calling(GET_MY_DEALS))
    spring = _RecordingSpring()

    await _run(claude, spring)

    assert spring.calls[0]["idempotency_key"] is None
    assert spring.calls[0]["allow_retry"] is True


@pytest.mark.asyncio
async def test_a_creator_idempotent_tool_is_keyed_and_never_retried():
    """The two idempotency sites must AGREE. Requiring a key while leaving
    `allow_retry` true makes a commit-like tool silently retryable, which is
    the failure mode B1's `send_routine_reply` would hit — it sends to a brand.

    `CREATOR_IDEMPOTENT_REQUIRED_TOOLS` is empty in B0 (its only member is
    B1's `send_routine_reply`), so this patches a real B0 tool into it to
    exercise both sites now rather than discovering the gap in B1.
    """
    assert CREATOR_IDEMPOTENT_REQUIRED_TOOLS == ()  # B0: nothing commit-like yet
    claude = _FakeClaude(_turn_calling(GET_MY_DEALS))
    spring = _RecordingSpring()

    with patch.object(loop_module, "CREATOR_IDEMPOTENT_REQUIRED_TOOLS", (GET_MY_DEALS,)):
        await _run(claude, spring)

    assert spring.calls[0]["idempotency_key"], "commit-like creator tool forwarded with no key"
    assert spring.calls[0]["allow_retry"] is False


# ------------------------------------------------------------------ no bleed


@pytest.mark.asyncio
async def test_brand_tools_still_route_to_the_brand_prefix():
    """Widening the lookup must not have moved the brand tools: `.get()` on the
    brand map is tried first and still wins."""
    claude = _FakeClaude(_turn_calling(SHOW_CREATORS, {"niche": "fashion", "count": 3}))
    spring = _RecordingSpring(data={"creators": []})

    await _run(claude, spring, tools=get_tool_schemas())

    assert spring.calls[0]["path"] == "/internal/meera/show_creators"


@pytest.mark.asyncio
async def test_an_invented_tool_name_is_still_rejected_outright():
    """`is_known_tool` got wider, not open. A name in neither set is still
    refused before any forward."""
    assert not is_known_tool("get_my_floors")
    claude = _FakeClaude(_turn_calling("get_my_floors"))
    spring = _RecordingSpring()

    events = await _run(claude, spring)

    assert spring.calls == []
    errors = [e for e in events if e.type == "tool_result" and e.tool_status == "error"]
    assert len(errors) == 1
    assert errors[0].tool_result_data["error"] == "unknown_tool"


# ------------------------------------------------- the per-turn gate (Kabir LOW 4)


@pytest.mark.asyncio
async def test_a_creator_tool_on_a_brand_turn_is_refused_before_any_http_call():
    """THE regression this gate exists for.

    `is_known_tool` accepts all six creator names on EVERY turn, brand turns
    included. A brand turn's untrusted surface is large (a pasted brief, a
    creator DM, a scraped page), so getting the model to emit `draft_reply` is
    cheap. Before the gate, the loop looked the name up in
    `CREATOR_TOOL_TO_SPRING_PATH` and POSTed
    `/internal/meera/creator/draft_reply` carrying the BRAND's on-behalf JWT.
    Spring refuses it — a brand's default scope names no creator tool — so
    nothing leaked, but the boundary lived entirely on the far side of a
    network call an attacker could trigger at will.

    The assertion that matters is `spring.calls == []`: asserting only that the
    result is an error would still pass while the request was being sent and
    rejected remotely, which is the exact state this test was written to rule
    out.
    """
    claude = _FakeClaude(_turn_calling(DRAFT_REPLY, {"kind": "COUNTER", "text": "hi"}))
    spring = _RecordingSpring()

    # A BRAND turn: the brand tool set is what was offered.
    events = await _run(claude, spring, tools=get_tool_schemas())

    assert spring.calls == [], "creator tool reached Spring on a brand turn"
    errors = [e for e in events if e.type == "tool_result" and e.tool_status == "error"]
    assert len(errors) == 1
    assert errors[0].tool_result_data["error"] == "tool_not_offered"
    # Same shape as `unknown_tool`: the model gets it back and finishes the turn.
    assert [e.type for e in events if e.type == "done"]
    assert claude.call_count == 2


@pytest.mark.asyncio
async def test_a_brand_tool_on_a_creator_turn_is_refused_before_any_http_call():
    """The mirror. A creator turn offers only creator schemas, so a brand tool
    name must not reach `/internal/meera/show_creators` under the creator's
    on-behalf JWT either."""
    claude = _FakeClaude(_turn_calling(SHOW_CREATORS, {"niche": "fashion"}))
    spring = _RecordingSpring()

    events = await _run(claude, spring)  # default = the creator set

    assert spring.calls == []
    errors = [e for e in events if e.type == "tool_result" and e.tool_status == "error"]
    assert errors and errors[0].tool_result_data["error"] == "tool_not_offered"


@pytest.mark.asyncio
async def test_a_local_tool_on_a_creator_turn_is_refused():
    """`analyze_site` and `present_options` are brand product surface and run
    IN-PROCESS, so they never touch Spring and no remote gate would catch them.
    The check therefore has to sit before the local-tool branch, not after."""
    claude = _FakeClaude(_turn_calling(ANALYZE_SITE, {"url": "https://example.com"}))
    spring = _RecordingSpring()

    with patch.object(loop_module, "perform_site_analysis") as fake_fetch:
        events = await _run(claude, spring)  # creator turn

    fake_fetch.assert_not_called()
    errors = [e for e in events if e.type == "tool_result" and e.tool_status == "error"]
    assert errors and errors[0].tool_result_data["error"] == "tool_not_offered"


@pytest.mark.asyncio
async def test_a_tool_outside_the_creators_own_grant_is_refused():
    """The gate reads the OFFER, not the tool family. A creator whose
    `tools_enabled` grants only the reads cannot dispatch `draft_reply` — the
    tool that writes text a brand will read — even though it is a creator tool
    and `is_known_tool` accepts it."""
    reads_only = get_creator_tool_schemas([GET_MY_DEALS])
    assert [s["name"] for s in reads_only] == [GET_MY_DEALS]

    claude = _FakeClaude(_turn_calling(DRAFT_REPLY, {"kind": "REPLY", "text": "sure"}))
    spring = _RecordingSpring()

    events = await _run(claude, spring, tools=reads_only)

    assert spring.calls == []
    errors = [e for e in events if e.type == "tool_result" and e.tool_status == "error"]
    assert errors and errors[0].tool_result_data["error"] == "tool_not_offered"


@pytest.mark.asyncio
async def test_an_empty_offer_dispatches_nothing():
    """The Phase-A degrade. Absent/empty `tools_enabled` yields an empty offer,
    and an empty offer must mean NO capability — the fail-closed direction."""
    claude = _FakeClaude(_turn_calling(GET_MY_DEALS))
    spring = _RecordingSpring()

    events = await _run(claude, spring, tools=[])

    assert spring.calls == []
    errors = [e for e in events if e.type == "tool_result" and e.tool_status == "error"]
    assert errors and errors[0].tool_result_data["error"] == "tool_not_offered"


@pytest.mark.asyncio
async def test_the_money_tool_exemption_is_deliberate_and_narrow():
    """`request_payment` is the ONE name the gate lets through un-offered, so
    that Spring's on-behalf rejection can still drive the deterministic
    MONEY_TOOL_SCOPE_DECLINE (ME-2). Pinned here so the exemption stays
    visible: it is safe only because a money tool routes to the caller's OWN
    brand prefix under the caller's own JWT — no audience boundary is crossed
    — and widening it to any other name re-opens the creator-route forward
    that `test_a_creator_tool_on_a_brand_turn_is_refused_before_any_http_call`
    rules out.
    """
    assert REQUEST_PAYMENT not in {s["name"] for s in get_tool_schemas()}

    claude = _FakeClaude(_turn_calling(REQUEST_PAYMENT, {"amount": 1}))
    spring = _RecordingSpring(data={"ok": True})

    await _run(claude, spring, tools=get_tool_schemas())

    assert spring.calls[0]["path"] == "/internal/meera/request_payment"
