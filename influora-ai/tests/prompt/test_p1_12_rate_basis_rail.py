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
