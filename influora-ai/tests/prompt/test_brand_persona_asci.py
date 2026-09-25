"""Brand Meera flags ASCI influencer-advertising issues (PROMPT_VERSION .25.6, 2026-09-26).

Creator-side Meera already carries these facts (knowledge/video_content_concepts.jsonl
brand_deal_practice rows "Ad disclosure label", "Health and finance content need disclosed
expertise", "Disclosure required even for genuine reviews", and the creator persona's
brand-deal and legal rails). The brand persona now says the same thing to the brand when it
plans or drafts a campaign: technical health or finance claims need a qualified creator who
shows the qualification upfront, every paid or gifted collaboration needs an upfront ad label,
flagged once, never blocking, never a legal ruling, and always pointing to ASCI's current
guidelines.
"""

from __future__ import annotations

import json
from pathlib import Path

from app.config import PROMPT_VERSION
from app.prompt.assembler import assemble_prompt
from app.prompt.creator_persona import MEERA_CREATOR_PERSONA
from app.prompt.persona import MEERA_PERSONA, get_persona_block

SECTION_START = "ASCI check (flag once per campaign, when you plan or draft it):"
SECTION_END = "Completing a campaign after create_campaign returns a DRAFT:"

# The brand persona is the cached Block A prefix and every brand turn pays for it. It was
# 18,387 characters before this rule and 19,375 after; this ceiling keeps the next addition
# from growing it unnoticed.
BRAND_PERSONA_MAX_CHARS = 20_000

KNOWLEDGE = Path(__file__).resolve().parents[2] / "app" / "prompt" / "knowledge" / "video_content_concepts.jsonl"


def _flat(text: str) -> str:
    return " ".join(text.split())


def _section() -> str:
    text = _flat(MEERA_PERSONA)
    return text[text.index(SECTION_START) : text.index(SECTION_END)]


def _system_text(audience: str | None) -> str:
    request: dict = {"workspace_id": "ws-asci", "conversation": [{"role": "user", "content": "hi"}]}
    if audience == "CREATOR":
        request["audience"] = "CREATOR"
        request["creator"] = {"workspace_id": "ws-asci", "first_name": "Asha", "categories": ["Food"]}
    prompt = assemble_prompt(request, session_id="s-asci")
    return _flat("\n".join(b["text"] for b in prompt.system_blocks))


def test_the_rule_is_in_the_brand_system_prompt_before_the_draft_section():
    brand = _system_text(None)
    assert SECTION_START in brand
    assert brand.index(SECTION_START) < brand.index(SECTION_END)
    assert SECTION_START in _flat(get_persona_block())


def test_health_and_finance_claims_need_a_qualified_creator_shown_upfront():
    section = _section()
    for word in ("health", "nutrition", "wellness", "treating, curing or preventing"):
        assert word in section, word
    for word in ("banking", "investment", "insurance", "returns"):
        assert word in section, word
    assert "only from creators who hold the relevant qualification and show it upfront" in section
    assert "other creators should keep to general product information" in section


def test_every_paid_or_gifted_collaboration_needs_the_upfront_ad_label():
    section = _section()
    assert "Every paid or gifted collaboration (barter, free product or other perk)" in section
    assert 'clear, upfront ad label such as "Ad" or "Paid partnership"' in section
    assert "even for a genuine review" in section
    assert "Suggest adding that to the brief" in section
    assert "the description you pass to create_campaign" in section


def test_flagged_once_never_blocks_never_a_legal_ruling_and_points_to_current_guidelines():
    section = _section()
    # The header already says "flag once per campaign", so a bare "once" check could not
    # fail; pin the body sentence that stops Meera repeating the flag every turn.
    assert "Say it once, in one short sentence" in section
    assert "as a helpful check, not a blocker, then carry on with the campaign" in section
    assert "ASCI's guideline for them to verify, not a legal ruling from you" in section
    assert "check ASCI's current guidelines for their product and claims" in section


def test_brand_prompt_never_says_escrow():
    assert "escrow" not in _flat(MEERA_PERSONA).lower()
    assert "escrow" not in _system_text(None).lower()


def test_brand_persona_stays_inside_its_size_budget():
    assert len(MEERA_PERSONA) <= BRAND_PERSONA_MAX_CHARS


def test_the_brand_section_does_not_leak_into_the_creator_prompt():
    assert SECTION_START not in _system_text("CREATOR")
    assert SECTION_START not in _flat(MEERA_CREATOR_PERSONA)


def test_creator_side_asci_wording_is_unchanged():
    creator = _flat(MEERA_CREATOR_PERSONA)
    assert "Never give legal or tax conclusions as fact (GST, TDS, contracts, ASCI disclosure rules)." in creator
    assert (
        "For the ad label, say plainly that ASCI asks for a clear, upfront label on paid posts and point "
        "them to ASCI's current guidelines; do not give it as a legal ruling." in creator
    )


def test_creator_knowledge_rows_are_unchanged():
    rows = {}
    for line in KNOWLEDGE.read_text(encoding="utf-8").splitlines():
        if line.strip():
            row = json.loads(line)
            if row.get("data_type") == "brand_deal_practice":
                rows[row["topic"]] = row["guidance"]
    assert rows["Ad disclosure label"] == (
        "ASCI asks for a clear, upfront disclosure label - such as 'Ad' or 'Paid partnership' - visible "
        "without needing to click 'more'. Use the platform's own paid-partnership tool as well as an "
        "on-screen label. Guidelines are updated periodically, so check ASCI's current version before posting."
    )
    assert rows["Health and finance content need disclosed expertise"] == (
        "For health, nutrition, banking, finance or insurance content, ASCI asks that technical advice or "
        "claims (a treatment, cure, prevention, returns, a specific investment) come only from creators who "
        "hold the relevant qualification and show it upfront; for stocks or investments that includes a SEBI "
        "registration number. General, non-technical mentions usually don't need this. Check ASCI's current "
        "guidelines for your case."
    )
    assert rows["Disclosure required even for genuine reviews"] == (
        "ASCI asks for a disclosure label whenever there's a material connection with a brand (a free "
        "product, payment or other benefit), even when your review is completely genuine. Saying 'I wasn't "
        "paid for this' doesn't replace the label if you did get something. Check ASCI's current guidelines."
    )


def test_prompt_version_bumped_for_the_brand_asci_rule():
    date, _, n = PROMPT_VERSION.removeprefix("meera-").rpartition(".")
    assert (date, int(n)) >= ("2026.09.25", 6)
