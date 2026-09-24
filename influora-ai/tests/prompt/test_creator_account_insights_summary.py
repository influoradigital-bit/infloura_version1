"""Account insights reach Meera's creator prompt (2026-09-24).

Spring renders `account_insights_summary` on the CREATOR context
(`MeeraContextService#buildAccountInsightsSummary`): the creator's own last-28-day
accounts reached, views, interactions, accounts engaged and profile-link taps.
These tests prove it survives the allow-list into the assembled creator prompt,
that "not available" reaches it too, that the persona states how to use it, and
that a BRAND prompt never renders it even from a contaminated payload.
"""

from __future__ import annotations

import pathlib
import re

from app.config import PROMPT_VERSION
from app.prompt.assembler import (
    ACCOUNT_INSIGHTS_NOT_AVAILABLE_TEXT,
    CREATOR_CONTEXT_PAYLOAD_FIELDS,
    assemble_prompt,
)
from app.prompt.creator_persona import MEERA_CREATOR_PERSONA

SUMMARY = (
    "Last 28 days (27 Aug 2026 to 23 Sept 2026): 12,400 accounts reached, 48,210 views,"
    " 1,930 interactions, 822 accounts engaged."
)


def _flat(text: str) -> str:
    return " ".join(text.split())


def _creator_prompt(**extra):
    creator = {
        "workspace_id": "creator-acc-001",
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
            "workspace_id": "creator-acc-001",
            "audience": "CREATOR",
            "creator": creator,
            "conversation": [{"role": "user", "content": "how is my account doing?"}],
        },
        session_id="s-acc",
    )


def _joined(prompt) -> str:
    return "\n".join(b["text"] for b in prompt.system_blocks)


def test_account_insights_summary_is_on_the_creator_allow_list():
    assert "account_insights_summary" in CREATOR_CONTEXT_PAYLOAD_FIELDS


def test_account_insights_summary_reaches_the_assembled_creator_prompt():
    joined = _joined(_creator_prompt(account_insights_summary=SUMMARY))
    assert "- Your account (from Instagram): " + SUMMARY in joined


def test_missing_key_is_stated_as_not_available_not_dropped():
    joined = _joined(_creator_prompt())
    assert "- Your account (from Instagram): " + ACCOUNT_INSIGHTS_NOT_AVAILABLE_TEXT in joined


def test_java_and_python_not_available_texts_match():
    java = (
        pathlib.Path(__file__).resolve().parents[3]
        / "influora-api/src/main/java/com/influora/service/meera/MeeraContextService.java"
    ).read_text(encoding="utf-8")
    match = re.search(r'ACCOUNT_INSIGHTS_NOT_AVAILABLE\s*=\s*"([^"]+)"', java)
    assert match, "MeeraContextService.ACCOUNT_INSIGHTS_NOT_AVAILABLE not found"
    assert match.group(1) == ACCOUNT_INSIGHTS_NOT_AVAILABLE_TEXT


def test_brand_prompt_never_renders_account_insights_from_a_contaminated_payload():
    prompt = assemble_prompt(
        {
            "workspace_id": "ws-1",
            "audience": "BRAND",
            "brand": {"display_name": "Acme", "account_insights_summary": SUMMARY},
            "conversation": [],
        },
        session_id="s-acc",
    )
    joined = _joined(prompt)
    for fragment in (SUMMARY, "account_insights_summary", "Your account (from Instagram)", "12,400 accounts reached"):
        assert fragment not in joined, fragment


def test_persona_states_how_to_use_account_numbers():
    text = _flat(MEERA_CREATOR_PERSONA)
    assert 'The "Your account" line has their last 28 days' in text
    assert "Quote those numbers exactly as written." in text
    assert 'If it is "not available", say so plainly and follow its reason (connected or not, as the line says); never estimate one.' in text


def test_prompt_version_bumped_for_account_insights():
    date, _, n = PROMPT_VERSION.removeprefix("meera-").rpartition(".")
    assert (date, int(n)) >= ("2026.09.24", 1)
