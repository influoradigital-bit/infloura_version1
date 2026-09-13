"""P1-12 — Meera must not quote a creator rate derived from the product price.

Live defect (2026-09-13): a brand pasted a store URL for a ₹4,000 rice cooker.
`analyze_site` had failed, so the price was guessed at ₹5,300, and
`calculate_budget` returned 5,300 x 0.06 = ₹318 per creator for a hands-on
product review. No Indian creator accepts ₹318 for that.

The Java fix removed the multiplier entirely: `CalculateBudgetExecutor` now
quotes the median of real `agreed_rate` values from COMPLETED collaborations in
the brand's niche, and returns NO figures at all when no such band clears the
k-anonymity floor (`rateBasis == "insufficient_data"`).

That backend refusal only holds in front of a brand if the PROMPT carries it —
Meera is perfectly capable of filling a silence with a plausible number. These
tests pin the two halves of the rail that make the refusal survive into the
conversation, because the prompt and the DTO have drifted apart in this repo
before (see F-17 in test_f14_f20_correctness.py, whose own gate was enforcing a
field name that was never on the wire).

Every assertion here fails against the pre-P1-12 persona, which described
calculate_budget as "suggest a pool + per-reel rate from a product price and a
goal" and said nothing about rateBasis.
"""

from __future__ import annotations

from app.prompt.persona import get_persona_block
from app.tools.schemas import CALCULATE_BUDGET, get_tool_schemas


def _calculate_budget_schema() -> dict:
    return next(t for t in get_tool_schemas() if t["name"] == CALCULATE_BUDGET)


def test_persona_names_the_wire_field_that_selects_the_two_paths():
    """rateBasis is what tells Meera whether numbers exist. If the persona never
    names it, she has no way to distinguish a real quote from a refusal."""
    persona = get_persona_block()
    assert "rateBasis" in persona
    assert "platform_rate_band" in persona
    assert "insufficient_data" in persona


def test_persona_forbids_inventing_a_rate_when_there_is_no_band():
    """The refusal path is the DEFAULT in production (the k-anonymity floor needs
    5 distinct creators AND 5 distinct workspaces of settled deals in one niche,
    which the platform does not have yet). A rail that only hedged would lose:
    Meera would hedge and quote anyway, which is exactly what shipped — the live
    message carried "this product price is an ESTIMATE" directly beneath ₹318."""
    persona = get_persona_block()
    lowered = persona.lower()
    assert "insufficient_data" in persona
    # She must be told to ASK, not to approximate.
    assert "usually pay" in lowered
    # And told explicitly that a range/ballpark is not an acceptable substitute
    # for the missing number — "don't state a figure" alone leaves that door open.
    assert "ballpark" in lowered or "bracket" in lowered


def test_persona_severs_the_rate_from_the_product_price():
    """The root cause was a unit error, not a precision error: a percentage of a
    product price is not what a creator charges. If the persona does not say so,
    the model will happily reconstruct the old heuristic in its head on the
    refusal path, where no tool number contradicts it."""
    persona = get_persona_block()
    lowered = persona.lower()
    assert "product price never determines the rate" in lowered
    assert "percentage of a product price" in lowered


def test_persona_does_not_call_the_band_a_per_reel_rate():
    """`Collaboration.agreedRate` is the WHOLE-collaboration figure for one
    creator — `ContractService` rejects a contract whose milestone total exceeds
    it — so it covers every deliverable in the deal. Passing it through as a
    per-reel price would be a new wrong number, not a fix."""
    persona = get_persona_block()
    assert "whole collaboration" in persona.lower()
    # The old wording described the tool itself as producing a per-reel rate.
    assert "pool + per-reel rate" not in persona


def test_tool_description_carries_the_same_two_paths():
    """The description is what Claude reads when deciding HOW to use the result,
    and it is in the tools payload even when the persona block is cached
    separately. Both surfaces must agree or the cheaper one wins by accident."""
    description = _calculate_budget_schema()["description"]
    assert "rateBasis" in description
    assert "insufficient_data" in description
    assert "per-reel" in description  # ...as the thing it is NOT
    assert "whole-collaboration" in description


def test_calculate_budget_input_schema_is_unchanged_by_p1_12():
    """The fix is entirely on the OUTPUT side. The input schema must stay byte-
    identical: `TOOL_NAMES` and this goal enum are what .github/workflows/
    schema-check.yml diffs against the Java side, and that check only compares
    TOP-LEVEL names — a nested change here would pass CI and break at runtime."""
    schema = _calculate_budget_schema()["input_schema"]
    assert sorted(schema["properties"].keys()) == ["goal", "product_price"]
    assert sorted(schema["properties"]["goal"]["enum"]) == [
        "awareness",
        "conversion",
        "launch",
        "review",
    ]
    assert schema["required"] == ["product_price", "goal"]
    # price_source must stay absent — Kabir's C1 defeat was the model self-
    # certifying a guessed price as scraped. P1-12 does not reopen that door.
    assert "price_source" not in schema["properties"]


def test_no_tool_schema_uses_a_json_schema_combinator():
    """anyOf/oneOf/allOf anywhere in an input_schema 400s the ENTIRE tools
    payload for the request, which surfaces to users as `provider_timeout` with
    no indication that a schema is at fault. P1-12 adds optional output fields
    (which are not in input_schema at all), but this guards the whole payload."""
    import json

    blob = json.dumps(get_tool_schemas())
    for combinator in ('"anyOf"', '"oneOf"', '"allOf"'):
        assert combinator not in blob, combinator


# ---------------------------------------------------------------------------
# P1-12b — the PROSE half of the refusal.
#
# The card is only one of two surfaces that showed a number. Above it, Meera's
# spoken reply said: "based on an estimated price around Rs 5,300, I'd put
# roughly Rs 1,600 across five creators at about Rs 320 each." Today that prose
# merely mirrors the tool. After P1-12 the tool returns NO money on the default
# path -- which removes the mirror and leaves a silence a helpful model will
# fill, because Block B (`assembler.py`, the `- Product catalog:` line) still
# shows it a product price it can multiply by anything.
#
# There is no output-side net to catch it: `has_invented_price` is wired to
# `routes/creator_suggestion.py:193` and `routes/trendspark.py:151` and NOT to
# brand chat, so nothing downstream of the model inspects this sentence. The
# prompt is the only layer that can hold, which is why these are prompt
# assertions -- and why they assert the PROHIBITION, not just its vocabulary.
# ---------------------------------------------------------------------------


def _flat_persona() -> str:
    """Persona block, lowercased with runs of whitespace collapsed to one space.

    The persona is a hard-wrapped triple-quoted string, so a sentence that
    reads as one clause on screen contains newlines and leading indentation at
    arbitrary points. Asserting raw substrings against it silently couples
    every test to the current line breaks -- an innocuous re-wrap would go red,
    and worse, a real deletion could go GREEN if the phrase you happened to
    pick sat inside one line. Normalise first, then assert on meaning.
    """
    return " ".join(get_persona_block().lower().split())


def _insufficient_data_rail() -> str:
    """The persona's insufficient_data bullet, normalised.

    Scoped on purpose: a prohibition that lives in the platform_rate_band
    branch, or three screens away in the general rails, is not the sentence
    the model is reading when it has just received a refusal.
    """
    flat = _flat_persona()
    start = flat.index('ratebasis "insufficient_data"')
    end = flat.index("the product price never determines the rate", start)
    return flat[start:end]


def test_refusal_rail_forbids_the_product_price_itself_not_only_the_rate():
    """The pre-fix rail forbade "a rate or a pool total". The live sentence
    carried THREE figures and only two of them are a rate or a pool total: the
    Rs 5,300 is a PRICE, and on this path it is a price the model passed into
    the tool itself (analyze_site has never populated a real catalog in
    production, so priceConfidence is "inferred" by default). Forbidding the
    rate while leaving the price sayable leaves the brand reading an invented
    rupee figure in chat, which is the defect Swapnil saw."""
    rail = _insufficient_data_rail()
    assert "not the product price either" in rail
    assert "no rupee figure at all" in rail


def test_refusal_rail_closes_the_i_supplied_it_loophole():
    """A number the model put into the tool call is not a number the tool gave
    back. Without this, "quote ONLY what the tool returns" is satisfiable by
    echoing your own argument."""
    rail = _insufficient_data_rail()
    assert "a number you supplied is not a number you were given" in rail


def test_refusal_rail_does_not_let_the_estimate_caveat_authorise_a_price():
    """`CalculateBudgetExecutor` appends a C1 caveat telling Meera to phrase the
    price as "based on an estimated price". That caveat is what the live prose
    opened with. It must read as a hedge on a price she is already entitled to
    say, never as permission to say one on the refusal path."""
    flat = _flat_persona()
    assert "never permission to say one" in flat
    assert "you are not entitled to say a price" in flat


def test_refusal_rail_still_asks_what_they_usually_pay():
    """Guard against over-correcting: the approved UX is a refusal that ASKS,
    not a dead end. If a future edit strips the question along with the number,
    this fails."""
    rail = _insufficient_data_rail()
    assert "usually pay" in rail
    assert "the brand states" in rail  # their answer is the one allowed figure
