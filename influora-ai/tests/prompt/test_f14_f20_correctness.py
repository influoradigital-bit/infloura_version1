"""Regression battery for the correctness findings in the 2026-08-08 deep audit.

F-14 the SSE heartbeat destroys the tool loop it exists to keep alive
F-15 a multi-tool turn permanently bricks the conversation  (see also
     tests/eval/test_prompt_injection.py, where it shares a root cause with F-08)
F-16 a turn truncated mid-tool_use is reported as clean success
F-17 the money caveat rail references a field that does not exist
F-18 get_campaign_performance is uncallable — nothing produces a campaign_id
F-19 "Rs. 499" is parsed as ₹0.50 and stamped as a scraped price
F-20 the nudge validators reject the best outputs and pass invented prices
"""

from __future__ import annotations

import asyncio

import pytest

from app.prompt.assembler import build_block_b
from app.prompt.persona import get_persona_block
from app.prompt.structured_extract import _coerce_price, extract_structured_facts
from app.prompt.validators import _PRICE_RE, _has_forbidden_petname, _statement_count
from app.tools.schemas import get_tool_schemas

# ---------------------------------------------------------------------------
# F-14 — asyncio.wait_for CANCELS the task it times out on
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_f14_a_heartbeat_timeout_does_not_kill_the_generator():
    """The exact mechanism: `asyncio.wait_for(gen.__anext__(), timeout=…)`
    cancels the task on timeout, throwing CancelledError into the generator at
    its current await point and terminating it. The next `__anext__()` then
    raises StopAsyncIteration and the route breaks as if the turn ended
    normally — client gets `tool_start` and then nothing.

    `asyncio.wait` does not cancel. This test asserts the difference directly.
    """

    async def slow_generator():
        yield "tool_start"
        await asyncio.sleep(0.25)  # a slow analyze_site fetch
        yield "tool_result"
        yield "done"

    # The old shape: wait_for kills it.
    gen = slow_generator().__aiter__()
    assert await gen.__anext__() == "tool_start"
    with pytest.raises(asyncio.TimeoutError):
        await asyncio.wait_for(gen.__anext__(), timeout=0.05)
    with pytest.raises(StopAsyncIteration):
        await gen.__anext__()  # the generator is DEAD — no result, no done

    # The fixed shape: the pending task survives the heartbeat.
    from app.routes.chat import _STREAM_EXHAUSTED, _next_event

    gen2 = slow_generator().__aiter__()
    assert await gen2.__anext__() == "tool_start"
    task = asyncio.ensure_future(_next_event(gen2))
    done, _pending = await asyncio.wait({task}, timeout=0.05)
    assert not done, "test premise: the first wait must time out"
    # …ping the client, then keep waiting on the SAME task.
    done, _pending = await asyncio.wait({task}, timeout=1.0)
    assert done, "the tool loop did not survive the heartbeat"
    assert task.result() == "tool_result"
    assert await _next_event(gen2) == "done"
    assert await _next_event(gen2) is _STREAM_EXHAUSTED


@pytest.mark.asyncio
async def test_f14_a_slow_tool_still_delivers_its_result_after_several_heartbeats():
    """Behavioural end-to-end for the heartbeat: a tool that takes longer than
    several heartbeat intervals must still deliver tool_result and done. Under
    the old `wait_for` shape the generator was cancelled on the FIRST timeout
    and the client got tool_start and then silence."""
    from app.routes.chat import _STREAM_EXHAUSTED, _next_event

    heartbeat = 0.05

    async def slow_tool_turn():
        yield "tool_start"
        await asyncio.sleep(heartbeat * 5)  # a slow analyze_site fetch
        yield "tool_result"
        yield "done"

    gen = slow_tool_turn().__aiter__()
    delivered, pings = [], 0
    task = None
    while True:
        if task is None:
            task = asyncio.ensure_future(_next_event(gen))
        done, _pending = await asyncio.wait({task}, timeout=heartbeat)
        if not done:
            pings += 1
            assert pings < 50, "loop never produced another event"
            continue
        event = task.result()
        task = None
        if event is _STREAM_EXHAUSTED:
            break
        delivered.append(event)

    assert delivered == ["tool_start", "tool_result", "done"], delivered
    assert pings >= 3, f"test premise: the tool must outlast several heartbeats (got {pings})"


# ---------------------------------------------------------------------------
# F-16 — truncation was only checked on the zero-text branch
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_f16_a_narrated_then_truncated_turn_is_not_reported_as_a_clean_stop():
    """The persona MANDATES narrating before every tool call, so the common
    shape is text("Scanning creators in Mumbai...") then a truncation mid
    `create_campaign`. That used to land on finish_reason="stop" with zero
    retries: the narration was persisted as the answer and the charge kept,
    while nothing was built and nothing was logged as a failure."""
    from app.providers.claude import ClaudeStreamEvent
    from app.tools.loop import ToolLoopContext, run_tool_loop

    calls = {"n": 0}

    class _TruncatingProvider:
        async def stream_turn(self, **kwargs):
            calls["n"] += 1
            yield ClaudeStreamEvent(type="text", text="Scanning creators in Mumbai...")
            yield ClaudeStreamEvent(
                type="truncated", stop_reason="max_tokens", tool_name_partial="create_campaign"
            )
            yield ClaudeStreamEvent(
                type="usage",
                usage={"input_tokens": 100, "output_tokens": 50},
                stop_reason="max_tokens",
            )

    ctx = ToolLoopContext(
        workspace_id="ws1", onbehalf_jwt="j", max_iterations=6,
        max_tokens=1024, max_tokens_retry=4096,
    )
    events = [
        e async for e in run_tool_loop(
            claude=_TruncatingProvider(), spring=None,
            system_blocks=[], initial_messages=[], ctx=ctx,
        )
    ]
    done = [e for e in events if e.type == "done"]
    assert done, "the loop never terminated"
    assert done[-1].finish_reason != "stop", (
        "a turn truncated mid tool_use is still reported as a clean success"
    )
    assert done[-1].finish_reason == "truncated_tool_use"
    assert calls["n"] == 2, "the wider-ceiling retry never fired for a narrated turn"


def test_f16_truncated_finish_reason_is_refunded_not_billed_as_an_answer():
    from app.routes.chat import FALLBACK_FINISH_REASONS

    assert "truncated_tool_use" in FALLBACK_FINISH_REASONS


# ---------------------------------------------------------------------------
# F-17 — the caveat rail named a field that exists nowhere
# ---------------------------------------------------------------------------


def test_f17_persona_references_the_field_that_actually_exists():
    persona = get_persona_block()
    assert "price_confidence" not in persona, (
        "the money caveat rail still names a field that exists nowhere in the repo"
    )
    assert "price_source" in persona


def test_f17_no_module_anywhere_still_uses_the_phantom_field():
    import pathlib

    app_dir = pathlib.Path(__file__).resolve().parents[2] / "app"
    def _code_lines(path):
        return "\n".join(
            line for line in path.read_text(encoding="utf-8").splitlines()
            if not line.lstrip().startswith("#")
        )

    offenders = [str(p) for p in app_dir.rglob("*.py") if "price_confidence" in _code_lines(p)]
    assert offenders == [], offenders


# ---------------------------------------------------------------------------
# F-18 — nothing produced a campaign_id
# ---------------------------------------------------------------------------


def test_f18_block_b_renders_a_campaign_id_when_the_payload_carries_one():
    block = build_block_b(
        {
            "workspace_id": "ws1",
            "brand": {
                "past_campaign_summary": [
                    {"type": "HYPE", "creator_count": 4, "funded": True, "campaign_id": "camp_001"}
                ],
                "outcome_digest": {
                    "campaign_outcomes": [
                        {"type": "HYPE", "creator_count": 4, "funded": True,
                         "spend_inr": 45000, "campaignId": "camp_002"}
                    ]
                },
            },
        }
    )
    text = block["text"]
    assert "[id=camp_001]" in text
    assert "[id=camp_002]" in text


def test_f18_no_id_in_the_payload_renders_no_marker():
    block = build_block_b(
        {"workspace_id": "ws1",
         "brand": {"past_campaign_summary": [{"type": "HYPE", "creator_count": 4, "funded": True}]}}
    )
    assert "[id=" not in block["text"]


def test_f18_the_tool_says_where_the_id_must_come_from():
    schema = next(s for s in get_tool_schemas() if s["name"] == "get_campaign_performance")
    assert "[id=" in schema["description"]
    assert "Never construct, guess, or infer" in schema["description"]
    assert "never" in get_persona_block().lower()
    assert "[id=...]" in get_persona_block()


# ---------------------------------------------------------------------------
# F-19 — "Rs. 499" -> 0.499, stamped as a scraped fact
# ---------------------------------------------------------------------------


@pytest.mark.parametrize(
    "raw,expected",
    [
        ("Rs. 499", 499.0),          # was 0.499 — a ₹0.50 "scraped fact"
        ("Rs. 1,499.00", 1499.0),    # was None
        ("499-999", None),           # was 499999.0
        ("₹2,50,000", 250000.0),
        ("1499.00", 1499.0),
        ("INR 999", 999.0),
        ("2 x 499", None),
        ("₹1,000 to ₹5,000", None),
    ],
)
def test_f19_price_parsing(raw, expected):
    assert _coerce_price(raw) == expected


def test_f19_aggregate_offer_low_price_is_read():
    """AggregateOffer was accepted as an offer TYPE but its lowPrice/highPrice
    were never read — the standard Shopify variant-range emission — so the
    commonest storefront shape yielded no price at all."""
    html = """
    <script type="application/ld+json">
    {"@type": "Product", "name": "Kumkumadi Oil",
     "offers": {"@type": "AggregateOffer", "lowPrice": "1299.00",
                "highPrice": "2499.00", "priceCurrency": "INR"}}
    </script>
    """
    products = extract_structured_facts(html)
    assert products, "AggregateOffer still yields no price"
    assert products[0].name == "Kumkumadi Oil"
    assert products[0].price == 1299.0
    assert products[0].currency == "INR"


def test_f19_a_currency_prefixed_price_survives_end_to_end():
    html = """
    <script type="application/ld+json">
    {"@type": "Product", "name": "Face Serum",
     "offers": {"@type": "Offer", "price": "Rs. 499", "priceCurrency": "INR"}}
    </script>
    """
    products = extract_structured_facts(html)
    assert products[0].price == 499.0, "a ₹0.50 fact is still being stamped as scraped"


# ---------------------------------------------------------------------------
# F-20 — four defects in the validator layer
# ---------------------------------------------------------------------------


def test_f20_decimals_and_abbreviations_do_not_inflate_the_statement_count():
    """The prompts explicitly ask for real numbers, so counting every `.`
    rejected the highest-quality outputs."""
    good = "This sound is up 2.5x this week. Three creators used it. Want a peek?"
    assert _statement_count(good) == 2
    assert _statement_count("Try it, e.g. on Reels. It works. Want a peek?") == 2
    # Urgency spam must still be rejected.
    assert _statement_count("ACT NOW! Buy it! Limited time!") == 3


def test_f20_the_invented_price_kill_switch_catches_bare_magnitudes():
    """`_PRICE_RE` only caught currency WORDS — "Grab it for 999 today" and
    "Only 2k for a reel" both passed the "invented price kill-switch"."""
    assert _PRICE_RE.search("Grab it for 999 today")
    assert _PRICE_RE.search("Only 2k for a reel")
    assert _PRICE_RE.search("Around 1.5L per campaign")
    assert _PRICE_RE.search("₹15,000 budget")
    # Ordinary prose with small counts is not a price.
    assert not _PRICE_RE.search("Three creators used it this week")
    assert not _PRICE_RE.search("Up 2.5x in 7 days")


def test_f20_the_verb_to_love_is_not_a_pet_name():
    """`_LOVE_VOCATIVE_RE` flagged the VERB: "There is a lot to love!" was
    rejected as a pet-name, which its own docstring says must not happen."""
    assert not _has_forbidden_petname("There is a lot to love!")
    assert not _has_forbidden_petname("Creators love this sound.")
    assert not _has_forbidden_petname("We love it.")
    # Real vocatives still caught.
    assert _has_forbidden_petname("Thanks, love")
    assert _has_forbidden_petname("Hey love, check this out")
    assert _has_forbidden_petname("Nice one, babe")


def test_f20_own_content_regex_no_longer_bans_ordinary_words():
    """`_OWN_CONTENT_FORBIDDEN_RE` banned "video" and "buy" — the sibling route
    removed exactly those two after they forced a near-permanent fallback, and
    the fallback happens AFTER the paid Haiku call, so every rejection is billed
    and thrown away. The fix was never applied here."""
    from app.routes.trendspark import _OWN_CONTENT_FORBIDDEN_RE

    assert not _OWN_CONTENT_FORBIDDEN_RE.search("Post a video today")
    assert not _OWN_CONTENT_FORBIDDEN_RE.search("Buy some time before recording")
    assert _OWN_CONTENT_FORBIDDEN_RE.search("Check it on Snapsby")


def test_f20_round2_a_year_or_a_view_count_is_not_an_invented_price():
    """F-20, round 2 (Priya sign-off review). The first fix widened `_PRICE_RE`
    to any bare 3+ digit magnitude, which rejected the outputs the prompts
    explicitly ask for — and every rejection is a paid Haiku call billed and
    thrown away, which is F-20's own first bullet reintroduced.

    The model is SHOWN video titles and untrusted trend_text and told to mention
    them naturally, so numeric content in the input is expected.
    """
    from app.prompt.validators import has_invented_price

    must_pass = [
        "Your Diwali 2026 Reel could ride this sound. Want a peek?",
        "This audio hit 150k uses this week. Want to try it?",
        "1,200 creators used this sound. Want a peek?",
        "This sound is up 2.5x this week. Three creators used it. Want a peek?",
        "Trending since 2024 across 40,000 posts.",
        "Your Reel hit 12,000 views in 3 days. Want a peek?",
    ]
    for message in must_pass:
        assert not has_invented_price(message), f"good nudge rejected: {message!r}"


def test_f20_round2_a_real_invented_price_is_still_caught():
    from app.prompt.validators import has_invented_price

    must_reject = [
        "Grab it for 999 today",
        "Only 2k for a reel",
        "₹15,000 budget",
        "Around 1.5L per campaign",
        "It costs 2500 per post",
        "15,000 rupees each",
        "budget of 40000",
        "priced at 1,200",
        "Rs. 2k a post",
        "INR 999",
    ]
    for message in must_reject:
        assert has_invented_price(message), f"invented price passed: {message!r}"


def test_f20_round2_the_real_route_validator_accepts_a_numeric_nudge():
    """Drive the route's own validator, not just the regex."""
    import json

    from app.prompt.trendspark import MODE_SNAPSBY
    from app.routes.trendspark import parse_and_validate

    good = "This sound is up 2.5x this week and 1,200 creators used it. Want a peek?"
    result = parse_and_validate(
        json.dumps({"message": good, "video_ids": []}),
        mode=MODE_SNAPSBY, sent_ids=[], max_message_chars=280,
    )
    assert result is not None, "a high-quality numeric nudge is still forced to fallback"
    assert result[0] == good
