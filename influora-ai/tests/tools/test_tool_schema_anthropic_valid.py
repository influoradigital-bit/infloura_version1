"""CI GUARD — every Meera tool schema must be valid for the Anthropic Messages API.

WHY THIS EXISTS (2026-07-24): a `create_campaign` input property shipped as a
JSON-Schema `anyOf` (`target_audience: {anyOf: [string, array]}`). Anthropic's
tool `input_schema` is a RESTRICTED JSON-Schema subset that rejects combinators,
so the API returned HTTP 400 for the ENTIRE tools payload — every Meera turn
failed (surfaced to the client as a generic "provider_timeout"). Nothing in CI
caught it: the existing schema tests assert field presence / enum values but
never validate the schema against Anthropic's accepted shape, and no unit test
calls the real API. It shipped, took Meera fully down on the deploy, and was
only found by driving the live system + reading container logs.

This test closes that gap deterministically — no API key, no network, no cost,
runs in the existing ai-tests.yml on every PR that touches influora-ai/**. It
validates the EXACT payload `get_tool_schemas()` hands to the Claude Messages
API — as of ME-2 (BrandF.md §115), the 4 non-money Spring-contract tools + the
2 local tools; `request_payment`/`confirm_launch` are deliberately excluded
until scope actually grants them (see schemas.py's get_tool_schemas docstring)
— so a combinator or a malformed node can never reach the wire again.

Ref: wiki/ai-review/meera-blank-turn-ai-review.md, MEMORY
reference_anthropic_tool_schema_no_combinators.
"""

from __future__ import annotations

from typing import Any

import pytest

from app.tools.schemas import get_tool_schemas

# Anthropic tool input_schema is a restricted JSON-Schema subset. These
# combinator keywords are NOT supported and 400 the whole tools payload.
FORBIDDEN_KEYWORDS = ("anyOf", "oneOf", "allOf", "not", "$ref", "if", "then", "else")

# Types Anthropic accepts on a schema node.
VALID_TYPES = {"object", "string", "number", "integer", "boolean", "array", "null"}


def _iter_schema_nodes(node: Any, path: str):
    """Yield (path, dict) for every schema-object node, recursing into
    properties / items / nested objects."""
    if isinstance(node, dict):
        yield path, node
        # Recurse into the standard JSON-Schema child positions.
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


def _all_tool_schemas():
    """Every tool exactly as offered to the model, labelled by name."""
    return [(t.get("name", "<unnamed>"), t) for t in get_tool_schemas()]


@pytest.mark.parametrize("name,tool", _all_tool_schemas())
def test_tool_has_wellformed_envelope(name: str, tool: dict[str, Any]):
    """Each tool has a name, a description, and an object input_schema with properties."""
    assert isinstance(tool.get("name"), str) and tool["name"], f"{name}: missing name"
    assert isinstance(tool.get("description"), str) and tool["description"], (
        f"{name}: missing/blank description"
    )
    schema = tool.get("input_schema")
    assert isinstance(schema, dict), f"{name}: input_schema must be a dict"
    assert schema.get("type") == "object", f"{name}: top-level input_schema.type must be 'object'"
    assert isinstance(schema.get("properties"), dict), f"{name}: input_schema.properties must be a dict"


@pytest.mark.parametrize("name,tool", _all_tool_schemas())
def test_no_combinators_anywhere(name: str, tool: dict[str, Any]):
    """THE anyOf-400 guard: no combinator/ref keyword may appear at ANY depth of
    the input_schema. This is the exact class of defect that took Meera down."""
    for path, node in _iter_schema_nodes(tool["input_schema"], f"{name}.input_schema"):
        for kw in FORBIDDEN_KEYWORDS:
            assert kw not in node, (
                f"{path} uses '{kw}' — Anthropic's tool input_schema rejects it and 400s the "
                f"WHOLE tools payload (every Meera turn fails). Use a single concrete type and "
                f"normalize in the executor instead."
            )


@pytest.mark.parametrize("name,tool", _all_tool_schemas())
def test_every_node_is_wellformed(name: str, tool: dict[str, Any]):
    """Each schema node has a known `type`, arrays declare `items`, and every
    entry in `required` actually exists in `properties` — the well-formedness
    Anthropic expects (a malformed node can also 400 the payload)."""
    for path, node in _iter_schema_nodes(tool["input_schema"], f"{name}.input_schema"):
        node_type = node.get("type")
        # Every property/items node must declare a valid type.
        assert node_type in VALID_TYPES, f"{path}: missing/invalid 'type' ({node_type!r})"
        # Arrays must say what their items are.
        if node_type == "array":
            assert "items" in node, f"{path}: array node must declare 'items'"
        # required must reference real properties.
        required = node.get("required")
        if required is not None:
            assert isinstance(required, list), f"{path}: 'required' must be a list"
            props = set((node.get("properties") or {}).keys())
            missing = [r for r in required if r not in props]
            assert not missing, f"{path}: required names not in properties: {missing}"
        # enum values, if present, must be non-empty.
        if "enum" in node:
            assert isinstance(node["enum"], list) and node["enum"], f"{path}: 'enum' must be a non-empty list"


def test_get_tool_schemas_returns_expected_count():
    """Guards against a tool silently dropping out of the offered set (4
    non-money Spring-contract tools + analyze_site + present_options = 6)."""
    schemas = get_tool_schemas()
    names = {t["name"] for t in schemas}
    for required_tool in (
        "show_creators",
        "calculate_budget",
        "create_campaign",
        "get_campaign_performance",
        "analyze_site",
        "present_options",
    ):
        assert required_tool in names, f"tool '{required_tool}' missing from get_tool_schemas()"


def test_get_tool_schemas_excludes_money_tools():
    """ME-2 (BrandF.md §115): request_payment/confirm_launch must NOT be offered
    to Claude — every real on-behalf token today is minted with SCOPE_DEFAULT,
    which excludes both, so offering them only sets up a guaranteed Spring 403
    with nothing productive Claude could do about it. This is the flip side of
    test_get_tool_schemas_returns_expected_count: that guards against an
    ACCIDENTAL drop, this guards against the exclusion silently regressing back
    to "always offered" without anyone noticing."""
    names = {t["name"] for t in get_tool_schemas()}
    assert "request_payment" not in names
    assert "confirm_launch" not in names
