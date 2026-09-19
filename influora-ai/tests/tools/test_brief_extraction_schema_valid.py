"""CI GUARD — the `extract_brief` tool schema must be valid for the Anthropic
Messages API (T-MEERA-CREATOR-PHASE-B §7.5).

Sibling of `tests/tools/test_tool_schema_anthropic_valid.py`, which guards the
Meera chat tools. This schema needs its OWN guard because it is deliberately
outside `TOOL_SCHEMAS`/`get_tool_schemas()` — the very structures that file
iterates — so it is invisible to every assertion there. A combinator in here
would 400 the whole `tools` payload of POST /internal/brief-extract, and since
every failure path of that route returns a deterministic 200, the symptom would
be every paste silently falling back to the regex extractor with no error
anywhere. That is precisely the outage class the sibling file was written for,
one route over.

Ref: MEMORY reference_anthropic_tool_schema_no_combinators.
"""

from __future__ import annotations

from typing import Any

from app.tools.schemas import (
    BRIEF_CATEGORIES,
    BRIEF_DELIVERABLE_TYPES,
    BRIEF_EXCLUSIVITY_SCOPES,
    BRIEF_REGULATED_CATEGORIES,
    BRIEF_SUMMARY_LINES_MAX,
    BRIEF_SUMMARY_LINES_MIN,
    BRIEF_USAGE_CHANNELS,
    get_brief_extraction_schema,
)

# Anthropic tool input_schema is a restricted JSON-Schema subset. These
# combinator keywords are NOT supported and 400 the whole tools payload.
FORBIDDEN_KEYWORDS = ("anyOf", "oneOf", "allOf", "not", "$ref", "if", "then", "else")

VALID_TYPES = {"object", "string", "number", "integer", "boolean", "array", "null"}


def _iter_schema_nodes(node: Any, path: str):
    if isinstance(node, dict):
        yield path, node
        props = node.get("properties")
        if isinstance(props, dict):
            for key, child in props.items():
                yield from _iter_schema_nodes(child, f"{path}.properties.{key}")
        items = node.get("items")
        if isinstance(items, dict):
            yield from _iter_schema_nodes(items, f"{path}.items")
        elif isinstance(items, list):
            for i, child in enumerate(items):
                yield from _iter_schema_nodes(child, f"{path}.items[{i}]")


def test_envelope_is_wellformed():
    schema = get_brief_extraction_schema()
    assert isinstance(schema, dict), "returns a single dict, not a list"
    assert schema.get("name") == "extract_brief"
    assert isinstance(schema.get("description"), str) and schema["description"]
    inner = schema.get("input_schema")
    assert isinstance(inner, dict)
    assert inner.get("type") == "object"
    assert isinstance(inner.get("properties"), dict) and inner["properties"]


def test_no_combinators_anywhere():
    schema = get_brief_extraction_schema()
    for path, node in _iter_schema_nodes(schema["input_schema"], "extract_brief.input_schema"):
        for keyword in FORBIDDEN_KEYWORDS:
            assert keyword not in node, (
                f"{path} uses '{keyword}' — Anthropic's tool input_schema rejects it and 400s "
                f"the WHOLE tools payload. §2.11's nullable fields are expressed as 'absent "
                f"means null' with a single concrete type, never as a union with null."
            )


def test_every_node_is_wellformed():
    schema = get_brief_extraction_schema()
    for path, node in _iter_schema_nodes(schema["input_schema"], "extract_brief.input_schema"):
        node_type = node.get("type")
        assert node_type in VALID_TYPES, f"{path}: missing/invalid 'type' ({node_type!r})"
        if node_type == "array":
            assert "items" in node, f"{path}: array node must declare 'items'"
        required = node.get("required")
        if required is not None:
            assert isinstance(required, list), f"{path}: 'required' must be a list"
            props = set((node.get("properties") or {}).keys())
            missing = [name for name in required if name not in props]
            assert not missing, f"{path}: required names not in properties: {missing}"
        if "enum" in node:
            assert isinstance(node["enum"], list) and node["enum"], (
                f"{path}: 'enum' must be a non-empty list"
            )


def test_every_field_of_the_shared_contract_is_present():
    """§2.11 is a contract shared with Java's `BriefDtos.BriefExtraction`. A field
    the schema does not offer the model is a field the model can never fill, and
    the Java record's component would sit permanently null with nothing failing.
    """
    props = get_brief_extraction_schema()["input_schema"]["properties"]
    expected = {
        "brand_name",
        "product",
        "category",
        "deliverables",
        "budget_inr",
        "budget_stated",
        "barter_only",
        "barter_mrp_inr",
        "deadline",
        "usage_months",
        "usage_perpetual",
        "usage_channels",
        "exclusivity_days",
        "exclusivity_scope",
        "exclusivity_brands",
        "max_revisions",
        "payment_terms",
        "off_platform_payment_hint",
        "disclosure_hidden_hint",
        "claims",
        "regulated_category",
        "vague_deliverables",
        "summary_lines",
    }
    assert set(props) == expected


def test_enums_match_the_java_vocabularies():
    """The pricing and risk vocabularies are owned by Java. A value offered here
    that Java does not know prices at a neutral weight without complaint — which
    is a wrong number on a creator's screen, not an error."""
    props = get_brief_extraction_schema()["input_schema"]["properties"]
    assert props["category"]["enum"] == list(BRIEF_CATEGORIES)
    assert props["deliverables"]["items"]["properties"]["type"]["enum"] == list(
        BRIEF_DELIVERABLE_TYPES
    )
    assert props["usage_channels"]["items"]["enum"] == list(BRIEF_USAGE_CHANNELS)
    assert props["exclusivity_scope"]["enum"] == list(BRIEF_EXCLUSIVITY_SCOPES)
    assert props["regulated_category"]["enum"] == list(BRIEF_REGULATED_CATEGORIES)


def test_summary_lines_bounds_are_stated_to_the_model():
    lines = get_brief_extraction_schema()["input_schema"]["properties"]["summary_lines"]
    assert lines["minItems"] == BRIEF_SUMMARY_LINES_MIN
    assert lines["maxItems"] == BRIEF_SUMMARY_LINES_MAX


def test_brief_extract_model_is_priced():
    """A new model literal would miss PRICING_TABLE (keyed by literal id) and
    `estimate_cost_usd` would raise at billing time on the first real
    extraction."""
    from app.config import BRIEF_EXTRACT_MODEL, TRENDSPARK_MODEL
    from app.costs.pricing import PRICING_TABLE

    assert BRIEF_EXTRACT_MODEL == TRENDSPARK_MODEL
    assert BRIEF_EXTRACT_MODEL in PRICING_TABLE
