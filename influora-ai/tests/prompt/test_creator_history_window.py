"""Step 2 of T-CREATOR-CREDITS-SEARCH: a CREATOR turn replays only the last N messages.

WHY THIS EXISTS. The browser sends the whole visible thread on every turn and Spring serves up to
`MeeraSessionService.DEFAULT_HISTORY_LIMIT` (100) messages, so Block C grew without bound. Block C is
the VOLATILE suffix: it is never cached, so every message in it is billed at the full input rate on
every turn, twice on a turn that calls a tool. Measured by rohan (PLAN.md §5): that is the difference
between ₹1.31 and ₹5.39 for one chat message.

The window is CREATOR-only on purpose: the BRAND path is another lane's, and its own history
behaviour is unchanged here.
"""

from app.config import get_settings
from app.prompt.assembler import assemble_prompt


def _conversation(n: int) -> list[dict]:
    """n alternating turns, each tagged so the assertion can name which ones survived."""
    return [
        {"role": "user" if i % 2 == 0 else "assistant", "content": f"turn-{i}"}
        for i in range(n)
    ]


def _creator_context(conversation: list[dict]) -> dict:
    return {
        "audience": "CREATOR",
        "workspace_id": "cre_1",
        "prompt_version": "test",
        "creator": {"first_name": "Riya", "creator_language": "hi-IN", "tools_enabled": []},
        "conversation": conversation,
    }


def test_creator_history_is_capped_at_the_configured_window():
    window = get_settings().creator_history_turns
    assert window == 20, "default window changed; update this test and PLAN.md §5 costs"

    prompt = assemble_prompt(_creator_context(_conversation(30)))

    assert len(prompt.messages) == window
    rendered = " ".join(str(m["content"]) for m in prompt.messages)
    assert "turn-29" in rendered, "the newest turn must always be replayed"
    assert "turn-10" in rendered, "the window must reach back N turns"
    assert "turn-9" not in rendered, "turns older than the window must be dropped"
    assert "turn-0" not in rendered


def test_a_short_creator_history_is_untouched():
    prompt = assemble_prompt(_creator_context(_conversation(5)))
    assert len(prompt.messages) == 5


def test_brand_history_is_not_windowed():
    """The BRAND path is out of scope for this change and must keep replaying everything."""
    brand_context = {
        "audience": "BRAND",
        "workspace_id": "ws_1",
        "prompt_version": "test",
        "conversation": _conversation(30),
    }
    prompt = assemble_prompt(brand_context)
    assert len(prompt.messages) == 30
