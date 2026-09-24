"""Creator Meera's rules must not contradict each other or lean on things she does not have
(audit 2026-09-24, lane B3; evidence in ai.md M1, M3, M4, M8, M9, M10).

Every check here reads the REAL assembled creator system prompt (`assemble_prompt`, all three
system blocks), so what is proven is what the model receives, not just what one module holds.
The tool sets are not typed in by hand: the represented-creator set and the wired set are read
from `CreatorToolScopes.java`, the source Spring builds `tools_enabled` from.
"""

from __future__ import annotations

import re
from pathlib import Path

from app.prompt.assembler import assemble_prompt
from app.prompt.content_knowledge import CREATOR_KNOWLEDGE_TEXT, has_hook_cta

REPO = Path(__file__).resolve().parents[3]
SCOPES_JAVA = REPO / "influora-api/src/main/java/com/influora/service/meera/CreatorToolScopes.java"


def _flat(text: str) -> str:
    return " ".join(text.split())


def _java_wired_tools() -> list[str]:
    source = SCOPES_JAVA.read_text(encoding="utf-8")
    start = source.index("WIRED_TOOL_NAMES =")
    end = source.index(");", start)
    return re.findall(r'"([a-z_]+)"', source[start:end])


def _java_represented_tools() -> list[str]:
    """What `toolNamesForLevel(level, represented=true, ...)` returns: the wired list, in order,
    filtered to SCOPE_REPRESENTED."""
    source = SCOPES_JAVA.read_text(encoding="utf-8")
    start = source.index("SCOPE_REPRESENTED =")
    end = source.index(";", start)
    scope = set(" ".join(re.findall(r'"([^"]*)"', source[start:end])).split())
    return [name for name in _java_wired_tools() if name in scope]


def _system_text(tools: list[str]) -> str:
    prompt = assemble_prompt(
        {
            "workspace_id": "creator-laneb-001",
            "audience": "CREATOR",
            "creator": {
                "workspace_id": "creator-laneb-001",
                "display_name": "Asha Rao",
                "first_name": "Asha",
                "city": "Pune",
                "tier": "NANO",
                "categories": ["Beauty & Skincare"],
                "creator_language": "en-IN",
                "brand_tone": "FRIENDLY",
                "tools_enabled": tools,
            },
            "conversation": [{"role": "user", "content": "plan my week"}],
        },
        session_id="s-laneb",
    )
    return _flat("\n".join(block["text"] for block in prompt.system_blocks))


WIRED = _system_text(_java_wired_tools())
REPRESENTED = _system_text(_java_represented_tools())
WITH_DRAFT = _system_text([*_java_wired_tools(), "draft_reply"])


# --- (1) the length rule names both long formats ---------------------------------


def test_keep_it_short_names_the_week_plan_as_well_as_the_script():
    assert "The exceptions are a full script, a week plan and a profile review" in WIRED
    assert "that layout wins over this rule" in WIRED
    assert "The one exception to length is a full script" not in WIRED


# --- (2) a hook's comment ask never lands in the opening of a script -------------


def test_the_two_comment_hooks_are_marked_and_no_other_hook_is():
    marked = [line for line in CREATOR_KNOWLEDGE_TEXT.splitlines() if "[CTA RULE:" in line]
    assert len(marked) == 2, marked
    # The hot-take hook is launch's English version since the 2026-09-24 merge; "What's your
    # opinion?" is the same comment ask and is marked by wording like the Hinglish one was.
    assert any(line.startswith("- Hot take:") for line in marked)
    assert any("Comment mein '[word]' likho" in line for line in marked)
    assert not has_hook_cta("Yeh share nahi karna tha, par ab chup rehna mushkil hai: [fact].")


def test_the_cta_rule_reaches_the_model_with_the_script_rule_that_explains_it():
    assert "the comment ask moves to the last beat as the one call to action, or to the caption" in WIRED
    assert "never promise the creator will DM anyone unless they said they will" in WIRED
    assert (
        "A hook template the knowledge block marks CTA RULE opens the video with its first part"
        " only." in WIRED
    )


# --- (3) the intake offers only what the formats can deliver ---------------------


def test_the_intake_offers_video_formats_only_and_says_so_about_carousels():
    assert "format (Reel or YouTube Short)" in WIRED
    assert "YouTube Short or carousel" not in WIRED
    assert "your content notes cover short video only, then offer the idea as a Reel" in WIRED


# --- (4) "saved as a draft" only when the draft tool is offered ------------------


def test_no_draft_promise_when_draft_reply_is_not_offered():
    assert "draft_reply" not in _java_wired_tools(), "the audit's premise changed; re-check (4)"
    for text in (WIRED, REPRESENTED):
        assert "tap to send" not in text
        assert "saved as a draft" not in text
        assert "Never say you drafted or saved anything unless a tool" in text
        assert "write the words in the chat for the creator to copy and send themselves" in text


def test_the_draft_promise_is_there_when_draft_reply_is_offered():
    assert "\"I've drafted it, tap to send\" and stop" in WITH_DRAFT


# --- (5) no date tool means say so and ask, never guess --------------------------


def test_a_represented_creator_has_no_date_tool_and_is_told_to_ask():
    represented = _java_represented_tools()
    assert "plan_my_week" not in represented and "get_todays_topics" not in represented
    assert represented, "the represented scope parsed to nothing"
    assert "Available tools: " + ", ".join(represented) in REPRESENTED
    assert (
        "If no tool on this turn gives you the date, say plainly that you cannot see today's date"
        " and ask the creator for it. Never guess it." in REPRESENTED
    )
    assert "With no plan tool on this turn, follow the no-date rule above." in REPRESENTED


# --- (6) no invented statistics or invented first-person results ----------------


def test_the_knowledge_no_longer_models_invented_numbers_or_results():
    for text in (WIRED, REPRESENTED):
        assert "1 million people already tried this" not in text
        assert "I tried this for 30 days" not in text
    assert "A count of other people is used only when the creator gave you that number" in WIRED
    assert "a specific, concrete anecdote from the creator's own life (one they have told you)" in WIRED
    assert "name an experiment the creator has actually done" in WIRED
    assert "Never invent a credential, a figure or a result." in WIRED


def test_the_persona_forbids_scripting_results_the_creator_has_not_told_her():
    assert "No invented results or experiences." in WIRED
    assert "Never script something the creator did, felt or got unless they told you" in WIRED
