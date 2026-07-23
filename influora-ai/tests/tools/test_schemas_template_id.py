"""Platform-AI Phase 1, W2a (Priya A3 / Ash's STANDARD-enum DERIVE ruling).

`create_campaign` gains an optional `template_id` so Spring's already-shipped
template-copy executor path (W1b, `CreateCampaignExecutor`) is reachable from
a real Meera turn. Backward compatibility: old bodies without `template_id`
must still validate against this schema shape (it's optional/additive).

UPDATE (2026-07-23, Ash-approved create_campaign draft-completeness plan,
Tier 0 #2): the original W2a ruling above kept `campaign_type` REQUIRED with
enum `HYPE|DIRECT|REVIEW` (no STANDARD) so the model was forced to pick a
type and defaulted to HYPE -- the actual root cause of the invisible-draft
bug. That ruling is REVERSED here: `STANDARD` is now IN the AI-facing enum
and `campaign_type` is OPTIONAL (omitted -> Java's `parseCampaignType`
defaults to STANDARD, `CreateCampaignExecutor.java` ~L322-330). The
`template_id`-present DERIVE path this file was written to test is
unaffected -- a template still overrides any AI-supplied `campaign_type`
including STANDARD.
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


def test_required_fields_are_product_name_and_creator_count_only():
    """2026-07-23 fix: campaign_type dropped from `required` so an omitted
    value reaches Java's STANDARD fallback instead of forcing the model to
    pick HYPE/DIRECT/REVIEW every time."""
    schema = _create_campaign_schema()
    assert schema["input_schema"]["required"] == ["product_name", "creator_count"]
    assert "campaign_type" not in schema["input_schema"]["required"]


def test_campaign_type_enum_now_includes_standard():
    """2026-07-23 fix reverses the prior W2a DERIVE-only ruling: STANDARD is
    now a directly AI-selectable value (also reachable via omission + the
    Java-side default), not template-row-only."""
    schema = _create_campaign_schema()
    enum = schema["input_schema"]["properties"]["campaign_type"]["enum"]
    assert set(enum) == {"HYPE", "DIRECT", "REVIEW", "STANDARD"}


def test_tier1_content_fields_present_and_optional():
    schema = _create_campaign_schema()
    props = schema["input_schema"]["properties"]
    required = schema["input_schema"]["required"]
    for field in (
        "title",
        "description",
        "objectives",
        "platforms",
        "content_types",
        "hashtags",
        "target_audience",
    ):
        assert field in props, field
        assert field not in required, field


def test_platforms_and_content_types_enums_match_campaign_form():
    """Byte-for-byte the platformOptions/contentTypeOptions arrays in
    src/components/brand/campaigns/campaign-form.tsx (not the wider TS union
    types in src/lib/types.ts, which include values the form never emits)."""
    schema = _create_campaign_schema()
    props = schema["input_schema"]["properties"]
    assert set(props["platforms"]["items"]["enum"]) == {
        "INSTAGRAM",
        "YOUTUBE",
        "TIKTOK",
        "TWITTER",
        "LINKEDIN",
        "FACEBOOK",
        "TWITCH",
    }
    assert set(props["content_types"]["items"]["enum"]) == {
        "POST",
        "STORY",
        "REEL",
        "VIDEO",
        "LIVE_STREAM",
        "ARTICLE",
        "PODCAST",
    }


def test_no_money_or_date_fields_on_create_campaign():
    """Guardrail (Part C, must never weaken): budget/date fields stay
    human-only and are never part of this tool's input schema."""
    schema = _create_campaign_schema()
    props = schema["input_schema"]["properties"]
    for forbidden in ("budget", "budget_min", "budget_max", "start_date", "end_date", "proposed_budget"):
        assert forbidden not in props, forbidden


def test_product_url_and_product_price_present():
    schema = _create_campaign_schema()
    props = schema["input_schema"]["properties"]
    assert "product_url" in props
    assert "product_price" in props


def test_get_tool_schemas_includes_template_id_on_create_campaign():
    schemas = get_tool_schemas()
    create_campaign = next(t for t in schemas if t["name"] == CREATE_CAMPAIGN)
    assert "template_id" in create_campaign["input_schema"]["properties"]
