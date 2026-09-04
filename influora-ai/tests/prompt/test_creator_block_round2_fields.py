"""Gate fix round 2 -- the four fields Spring's CreatorContextResponse gained
(`floor_currency`, `working_hours_timezone`, `consent_version`,
`ai_monthly_cap_usd`) and how the creator Block B treats each.

Two are rendered (currency, time zone). Two pass the allow-list -- so the
Java<->Python drift test stays exact -- but must NEVER reach the prompt: the
per-creator AI spend cap is a money figure Meera has no business speaking
about (A8 / Q7), and the DPDP notice version is consent-gate bookkeeping.
"""

from __future__ import annotations

from app.prompt.assembler import (
    CREATOR_CONTEXT_FIELDS_NOT_RENDERED,
    CREATOR_CONTEXT_PAYLOAD_FIELDS,
    build_block_b_creator,
)

_BASE = {
    "workspace_id": "c-r2",
    "audience": "CREATOR",
    "display_name": "Round Two",
    "first_name": "Round",
    "consent_accepted": True,
    "floors": {"reel_floor": "12,000", "story_set_floor": "4,000", "post_floor": "6,000"},
    "working_hours_start": 10,
    "working_hours_end": 19,
    "working_days": [1, 2, 3],
}


def test_floor_currency_defaults_to_inr_and_is_rendered_when_set():
    inr = build_block_b_creator(dict(_BASE))["text"]
    assert "Reel INR 12,000" in inr

    aed = build_block_b_creator({**_BASE, "floor_currency": "AED"})["text"]
    assert "Reel AED 12,000" in aed
    assert "Story set AED 4,000" in aed
    assert "INR 12,000" not in aed


def test_floor_currency_alone_is_visible_even_without_floors():
    ctx = {k: v for k, v in _BASE.items() if k != "floors"}
    text = build_block_b_creator({**ctx, "floor_currency": "AED"})["text"]
    assert "rates are quoted in AED" in text


def test_working_hours_timezone_keeps_ist_for_kolkata_and_names_other_zones():
    default = build_block_b_creator(dict(_BASE))["text"]
    assert "10:00-19:00 IST" in default

    kolkata = build_block_b_creator({**_BASE, "working_hours_timezone": "Asia/Kolkata"})["text"]
    assert "10:00-19:00 IST" in kolkata

    dubai = build_block_b_creator({**_BASE, "working_hours_timezone": "Asia/Dubai"})["text"]
    assert "10:00-19:00 Asia/Dubai" in dubai
    assert "IST" not in dubai


def test_working_hours_timezone_alone_is_visible_without_hours():
    ctx = {k: v for k, v in _BASE.items() if not k.startswith("working_")}
    text = build_block_b_creator({**ctx, "working_hours_timezone": "Asia/Dubai"})["text"]
    assert "not set (time zone Asia/Dubai)" in text


def test_cap_override_and_consent_version_never_reach_the_prompt():
    """A8/Q7 barrier: the creator's monthly AI allowance is a USD figure that
    must never be spoken; the consent notice version is gate bookkeeping."""
    text = build_block_b_creator(
        {**_BASE, "ai_monthly_cap_usd": "1234.56", "consent_version": "dpdp-v9-drift"}
    )["text"]
    assert "1234.56" not in text
    assert "1,234.56" not in text
    assert "dpdp-v9-drift" not in text
    assert "ai_monthly_cap" not in text
    assert "consent_version" not in text


def test_not_rendered_set_is_a_subset_of_the_allow_list():
    assert CREATOR_CONTEXT_FIELDS_NOT_RENDERED <= set(CREATOR_CONTEXT_PAYLOAD_FIELDS)
    assert {"ai_monthly_cap_usd", "consent_version"} <= CREATOR_CONTEXT_FIELDS_NOT_RENDERED
