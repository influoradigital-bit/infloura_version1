"""A7(c) — Meera for Creators Phase A info barrier, Python side (spec §3.7).

Two directions, both structural (not "the model promised"):

- CREATOR Block B never carries PAN / GSTIN value / Aadhaar digits / dispute
  text, even when a contaminated payload includes them. Only the two identity
  booleans are rendered, as words, so not even the key names leak.
- BRAND Block B never carries a creator's rate floors, even when a
  contaminated payload includes them at the top level OR nested under
  `brand`. Uses the spec's 9999 / 9,999 canary.

Plus the vocabulary + tool rails that make the creator persona safe to ship:
the persona text itself contains no banned word, lists no brand tool, and
CREATOR turns are assembled with an empty tool set.
"""

from __future__ import annotations

import json

from app.prompt.assembler import (
    assemble_prompt,
    build_block_a_creator,
    build_block_b,
    build_block_b_creator,
)
from app.prompt.creator_persona import (
    CREATOR_BANNED_WORDS,
    MEERA_CREATOR_PERSONA,
    get_creator_persona,
)
from app.tools.schemas import get_tool_schemas

PAN = "ABCDE1234F"
GSTIN = "27ABCDE1234F1Z5"
AADHAAR_LAST4 = "1234"
DISPUTE_TEXT = "Brand claims the reel was never delivered and wants a refund"


def _creator_context(**extra) -> dict:
    base = {
        "workspace_id": "creator-user-001",
        "audience": "CREATOR",
        "display_name": "Test Creator",
        "first_name": "Test",
        "city": "Pune",
        "tier": "MICRO",
        "categories": ["Fashion", "Beauty"],
        "creator_language": "hi-IN",
        "brand_tone": "FRIENDLY",
        "floors": {"reel_floor": "1,200", "story_set_floor": "800", "post_floor": "1,500"},
        "metrics_summary": {
            "followers": "12,400 followers",
            "reach_30d": "45,600 reach (30 days)",
            "engagement_rate": "3.2% engagement",
        },
        "deals_summary": {"active_count": 2, "completed_count": 8, "total_earned_inr": "18,500"},
        "approval_level": 0,
        "represented": False,
        "identity": {"kyc_done": True, "gstin_present": True},
    }
    base.update(extra)
    return base


# ---------------------------------------------------------------------------
# Creator Block B: identity minimisation
# ---------------------------------------------------------------------------


def test_creator_block_b_never_exposes_sensitive_identity():
    """Spec §3.7 test 1, verbatim intent: a contaminated payload carrying
    PAN/GSTIN/Aadhaar must not leak any of them into the prompt."""
    context = _creator_context(
        pan=PAN,
        gstin=GSTIN,
        aadhaar_last4=AADHAAR_LAST4,
        dispute_text=DISPUTE_TEXT,
    )

    block_text = json.dumps(build_block_b_creator(context))

    assert PAN not in block_text
    assert GSTIN not in block_text
    assert AADHAAR_LAST4 not in block_text
    assert "refund" not in block_text.lower()
    assert "kyc_done" not in block_text  # the key name must not leak either
    assert "gstin_present" not in block_text
    assert "KYC: done" in block_text
    assert "GST: registered" in block_text


def test_creator_block_b_identity_object_contributes_only_its_two_booleans():
    """Even a wider `identity` object (a future DTO widening, a debug field)
    yields exactly one rendered line built from the two documented booleans."""
    context = _creator_context(
        identity={
            "kyc_done": False,
            "gstin_present": False,
            "pan": PAN,
            "gstin": GSTIN,
            "aadhaar_last4": AADHAAR_LAST4,
            "selfie_url": "https://cdn.example/selfie.jpg",
        }
    )

    block_text = build_block_b_creator(context)["text"]

    assert PAN not in block_text
    assert GSTIN not in block_text
    assert AADHAAR_LAST4 not in block_text
    assert "selfie" not in block_text
    assert "KYC: not done, GST: not registered" in block_text


def test_creator_block_b_ignores_every_field_outside_the_allow_list():
    """Allow-list, not deny-list: an unknown key with a distinctive value is
    simply never rendered."""
    context = _creator_context(
        bank_account="9876543210",
        upi_id="test@upi",
        phone="+919876543210",
        email="test@example.com",
        wallet_balance="99,999",
    )

    block_text = build_block_b_creator(context)["text"]

    for canary in ("9876543210", "test@upi", "test@example.com", "99,999"):
        assert canary not in block_text


# ---------------------------------------------------------------------------
# Brand Block B: floors never cross the barrier
# ---------------------------------------------------------------------------


def test_brand_block_b_never_exposes_creator_floors_top_level():
    """Spec §3.7 test 2: a brand context contaminated with creator floors."""
    context = {
        "workspace_id": "ws-brand-001",
        "display_name": "Brand Inc",
        "floors": {"reel_floor": "9,999", "story_set_floor": "8,888"},
    }

    block_text = json.dumps(build_block_b(context))

    assert "9,999" not in block_text
    assert "9999" not in block_text
    assert "8,888" not in block_text
    assert "8888" not in block_text


def test_brand_block_b_never_exposes_creator_floors_nested_under_brand():
    """The stricter case: floors placed exactly where build_block_b DOES read
    fields from. `_FORBIDDEN_BRAND_FIELDS` must strip them."""
    context = {
        "workspace_id": "ws-brand-001",
        "brand": {
            "display_name": "Brand Inc",
            "floors": {"reel_floor": "9,999", "story_set_floor": "8,888"},
            "reel_floor": "9,999",
            "identity": {"kyc_done": True, "pan": PAN},
            "approval_level": 2,
        },
    }

    block_text = json.dumps(build_block_b(context))

    assert "Brand Inc" in block_text  # the legitimate field still renders
    assert "9,999" not in block_text
    assert "9999" not in block_text
    assert "8,888" not in block_text
    assert "8888" not in block_text
    assert PAN not in block_text
    assert "kyc" not in block_text.lower()


def test_brand_assembled_prompt_never_contains_creator_floor_canary():
    """End-to-end through `assemble_prompt` for the BRAND audience: the whole
    system prompt (Block A + Block B) is free of the floor canary."""
    prompt = assemble_prompt(
        {
            "workspace_id": "ws-brand-001",
            "audience": "BRAND",
            "brand": {"display_name": "Brand Inc", "floors": {"reel_floor": "9,999"}},
            "floors": {"reel_floor": "9,999"},
            "creator": {"floors": {"reel_floor": "9,999"}},
            "conversation": [],
        },
        session_id="s-1",
    )
    system_text = json.dumps(prompt.system_blocks)
    assert "9,999" not in system_text
    assert "9999" not in system_text


# ---------------------------------------------------------------------------
# Creator persona rails: vocabulary + no tools
# ---------------------------------------------------------------------------


def test_creator_persona_contains_no_banned_word():
    """The vocabulary rule is enforced on the prompt TEXT, not just requested
    of the model: the banned word is structurally absent from the persona."""
    text = MEERA_CREATOR_PERSONA.lower()
    for word in CREATOR_BANNED_WORDS:
        assert word not in text
    assert "secure payments" in text
    assert "secured funds" in text


def test_creator_persona_formatted_for_a_creator_contains_no_banned_word():
    text = get_creator_persona(_creator_context()).lower()
    for word in CREATOR_BANNED_WORDS:
        assert word not in text


def test_creator_block_a_lists_no_brand_tool():
    """Phase A creator turns are conversational only: Block A must not even
    name a brand tool, let alone offer it."""
    block_a_text = build_block_a_creator()["text"]
    for tool in get_tool_schemas():
        assert tool["name"] not in block_a_text
    assert "none in this phase" in block_a_text


def test_creator_turn_is_assembled_with_an_empty_tool_set():
    prompt = assemble_prompt(
        {
            "workspace_id": "creator-user-001",
            "audience": "CREATOR",
            "creator": _creator_context(),
            "conversation": [{"role": "user", "content": "hi"}],
        },
        session_id="s-1",
    )
    assert prompt.audience == "CREATOR"
    assert prompt.tools == []


def test_brand_turn_still_carries_the_full_tool_set():
    prompt = assemble_prompt(
        {"workspace_id": "ws-brand-001", "audience": "BRAND", "brand": {}, "conversation": []},
        session_id="s-1",
    )
    assert prompt.audience == "BRAND"
    assert [t["name"] for t in prompt.tools] == [t["name"] for t in get_tool_schemas()]


def test_creator_and_brand_cache_keys_never_collide_for_the_same_ids():
    creator = assemble_prompt(
        {"workspace_id": "same-id", "audience": "CREATOR", "creator": {}, "conversation": []},
        session_id="same-session",
    )
    brand = assemble_prompt(
        {"workspace_id": "same-id", "audience": "BRAND", "brand": {}, "conversation": []},
        session_id="same-session",
    )
    assert creator.cache_key != brand.cache_key
    assert ":CREATOR:" in creator.cache_key
    assert ":BRAND:" in brand.cache_key
