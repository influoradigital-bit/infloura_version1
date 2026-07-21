"""Platform-AI Phase 1, W2a (Priya A3 / Ash's STANDARD-enum DERIVE ruling).

`create_campaign` gains an optional `template_id` so Spring's already-shipped
template-copy executor path (W1b, `CreateCampaignExecutor`) is reachable from
a real Meera turn. `campaign_type` stays required and its enum stays
`HYPE|DIRECT|REVIEW` -- STANDARD is never AI-selectable; Spring derives it
from the template row when `template_id` is present (executor already does
this, W1). Backward compatibility: old bodies without `template_id` must
still validate against this schema shape (it's optional/additive).
"""

from __future__ import annotations

from app.tools.schemas import CREATE_CAMPAIGN, TOOL_SCHEMAS, get_tool_schemas


def _create_campaign_schema() -> dict:
    return next(t for t in TOOL_SCHEMAS if t["name"] == CREATE_CAMPAIGN)


def test_create_campaign_has_optional_template_id():
    schema = _create_campaign_schema()
    props = schema["input_schema"]["properties"]
    assert "template_id" in props
    assert props["template_id"]["type"] == "string"


def test_template_id_is_not_required():
    schema = _create_campaign_schema()
    assert "template_id" not in schema["input_schema"]["required"]


def test_required_fields_unchanged_backward_compat():
    schema = _create_campaign_schema()
    assert schema["input_schema"]["required"] == ["product_name", "campaign_type", "creator_count"]


def test_campaign_type_enum_does_not_widen_to_include_standard():
    """Ash's DERIVE ruling: STANDARD is a template-row-only type. Widening the
    AI-facing enum would let the model pick a type it should never choose on
    its own -- Spring derives STANDARD from the template row instead."""
    schema = _create_campaign_schema()
    enum = schema["input_schema"]["properties"]["campaign_type"]["enum"]
    assert set(enum) == {"HYPE", "DIRECT", "REVIEW"}
    assert "STANDARD" not in enum


def test_get_tool_schemas_includes_template_id_on_create_campaign():
    schemas = get_tool_schemas()
    create_campaign = next(t for t in schemas if t["name"] == CREATE_CAMPAIGN)
    assert "template_id" in create_campaign["input_schema"]["properties"]
