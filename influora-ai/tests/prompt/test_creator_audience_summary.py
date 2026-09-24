"""Creator audience knowledge (Swapnil 2026-09-21): creator Meera knows the
creator's OWN audience.

Spring renders a compact `audience_summary` string on the CREATOR context
(`MeeraContextService#buildAudienceSummary`). These tests prove it survives
the Python allow-list into the assembled creator prompt, that "not available"
reaches it too, that the persona states the rule, and that a BRAND prompt
never renders it even from a contaminated payload.
"""

from __future__ import annotations

from app.config import PROMPT_VERSION
from app.prompt.assembler import (
    AUDIENCE_NOT_AVAILABLE_TEXT,
    CREATOR_CONTEXT_PAYLOAD_FIELDS,
    assemble_prompt,
)
from app.prompt.creator_persona import MEERA_CREATOR_PERSONA

SUMMARY = (
    "Age: 18-24 61%, 25-34 33%. Gender: women 58%, men 36%, unspecified 6%."
    " Top cities: Mumbai, Maharashtra / Pune, Maharashtra / Delhi, Delhi."
    " As of 12 Aug 2026."
)
# Byte-identical to Java's MeeraContextService.AUDIENCE_NOT_AVAILABLE.
JAVA_NOT_AVAILABLE = "not available (Instagram not connected, or no audience snapshot yet)"


def _flat(text: str) -> str:
    return " ".join(text.split())


def _creator_prompt(**extra):
    creator = {
        "workspace_id": "creator-aud-001",
        "audience": "CREATOR",
        "display_name": "Asha Rao",
        "first_name": "Asha",
        "city": "Pune",
        "tier": "MICRO",
        "categories": ["fitness"],
        "creator_language": "en-IN",
        "brand_tone": "FRIENDLY",
    }
    creator.update(extra)
    return assemble_prompt(
        {
            "workspace_id": "creator-aud-001",
            "audience": "CREATOR",
            "creator": creator,
            "conversation": [{"role": "user", "content": "how do I grow my channel?"}],
        },
        session_id="s-aud",
    )


def _joined(prompt) -> str:
    return "\n".join(b["text"] for b in prompt.system_blocks)


def test_audience_summary_is_on_the_creator_allow_list():
    assert "audience_summary" in CREATOR_CONTEXT_PAYLOAD_FIELDS


def test_audience_summary_reaches_the_assembled_creator_prompt():
    prompt = _creator_prompt(audience_summary=SUMMARY)
    block_b = prompt.system_blocks[-1]["text"]
    assert f"- Your audience (from Instagram): {SUMMARY}" in block_b


def test_not_available_from_java_reaches_the_creator_prompt():
    prompt = _creator_prompt(audience_summary=JAVA_NOT_AVAILABLE)
    block_b = prompt.system_blocks[-1]["text"]
    assert f"- Your audience (from Instagram): {JAVA_NOT_AVAILABLE}" in block_b
    assert "Top cities" not in block_b


def test_missing_key_is_stated_as_not_available_not_dropped():
    assert AUDIENCE_NOT_AVAILABLE_TEXT == JAVA_NOT_AVAILABLE
    block_b = _creator_prompt().system_blocks[-1]["text"]
    assert f"- Your audience (from Instagram): {JAVA_NOT_AVAILABLE}" in block_b


def test_brand_prompt_never_renders_audience_even_from_a_contaminated_payload():
    prompt = assemble_prompt(
        {
            "workspace_id": "ws-1",
            "audience": "BRAND",
            "brand": {"display_name": "Acme", "audience_summary": SUMMARY},
            "conversation": [],
        },
        session_id="s-aud",
    )
    joined = _joined(prompt)
    # "Mumbai" alone is not checked: the brand persona legitimately names the city.
    for fragment in (SUMMARY, "audience_summary", "Your audience", "18-24 61%", "women 58%", "Top cities"):
        assert fragment not in joined, fragment


def test_persona_states_the_audience_rule():
    text = _flat(MEERA_CREATOR_PERSONA)
    assert "Use their audience too." in text
    assert (
        'For growth, content, hook and script questions, use the "Your audience" line'
        " in your context alongside the content knowledge"
    ) in text
    assert 'pick the hook language and the "Unity" or "Buyer persona targeting" framing' in text
    assert 'If the audience is "not available", say so plainly and suggest they connect Instagram' in text
    assert "Never state an audience fact that is not in that line" in text


def test_persona_audience_rule_reaches_the_creator_system_prompt():
    assert "Use their audience too." in _flat(_joined(_creator_prompt(audience_summary=SUMMARY)))


def test_prompt_version_bumped_for_audience_knowledge():
    # .21.2 introduced the audience rule; .22.2 (content-idea intake), then .22.3 (knowledge v3), .22.4 (statistic rule), .22.5 (knowledge v4 + script format), .22.6 (script review), .23.1 (daily topics tool), .23.2 (week plan), .23.3 (frame check route), .24.1 (audit lane B: week plan, prompt contradictions), .24.2 (camera knowledge v5 + saved phone), .24.3 (outdoor light + delivery knowledge v6), .24.4 (delivery examples v6.1).
    assert PROMPT_VERSION == "meera-2026.09.24.4"
