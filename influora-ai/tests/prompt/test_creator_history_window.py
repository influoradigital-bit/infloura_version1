"""Step 2 of T-CREATOR-CREDITS-SEARCH: what a CREATOR turn replays to the model.

WHY THIS EXISTS. The browser sends the whole visible thread and Spring serves up to
`MeeraSessionService.DEFAULT_HISTORY_LIMIT` (100) messages. Block C is the VOLATILE suffix: never
cached, so every message in it is billed at the full input rate on every turn, twice on a turn that
calls a tool (T-CREATOR-CREDITS-SEARCH/PLAN.md §5).

THE SHAPE THESE TESTS USE IS THE PRODUCTION ONE, and that is the point. The first version of this
file built threads that alternated *from* `user`, so they ended on an assistant turn — and ash proved
by mutation (wiki/ai-review/creator-history-window-ai-review.md, P1-3) that a window which DELETED
THE CREATOR'S LIVE QUESTION whenever the thread ended in `user` — i.e. on every real request — passed
all three tests. A real thread starts with Meera's onboarding greeting (`MeeraSessionService`'s first
persisted row) and ends with the live user message (`MeeraCopilotChat.tsx` appends it last), so
`_live_thread` builds exactly that, and the assertions are on `messages[-1]` and `messages[0]`.
"""

import pytest

from app.config import get_settings
from app.prompt.assembler import assemble_prompt


def _live_thread(turns: int) -> list[dict]:
    """A production-shaped creator thread: greeting, strictly alternating, live user question last.

    `turns` counts everything including the greeting, and must be EVEN: greeting + N exchanges
    alternating user/assistant can only end on the creator's turn for an even total. Odd totals would
    need two assistant turns in a row, which is not a shape production produces.
    """
    assert turns >= 2 and turns % 2 == 0, "a greeting + alternating exchanges is an even count"
    thread = [{"role": "assistant", "content": "greeting-0"}]
    for i in range(1, turns):
        role = "user" if i % 2 == 1 else "assistant"
        thread.append({"role": role, "content": f"{role}-{i}"})
    assert thread[-1]["role"] == "user"
    return thread


def _creator_context(conversation: list[dict]) -> dict:
    return {
        "audience": "CREATOR",
        "workspace_id": "cre_1",
        "prompt_version": "test",
        "creator": {"first_name": "Riya", "creator_language": "hi-IN", "tools_enabled": []},
        "conversation": conversation,
    }


@pytest.fixture
def window(monkeypatch):
    """Set the two ceilings for one test. `get_settings` is lru_cached, so it must be cleared."""

    def _set(turns: int | None = None, char_budget: int | None = None):
        if turns is not None:
            monkeypatch.setenv("CREATOR_HISTORY_TURNS", str(turns))
        if char_budget is not None:
            monkeypatch.setenv("CREATOR_HISTORY_CHAR_BUDGET", str(char_budget))
        get_settings.cache_clear()
        return get_settings()

    yield _set
    get_settings.cache_clear()


def _rendered(prompt) -> str:
    return " ".join(str(m["content"]) for m in prompt.messages)


def test_the_live_user_question_is_always_replayed_last(window):
    """The mutation that passed the old tests: dropping the newest turn on a real request."""
    window(turns=40)
    prompt = assemble_prompt(_creator_context(_live_thread(60)))

    assert prompt.messages[-1]["role"] == "user"
    assert "user-59" in str(prompt.messages[-1]["content"]), "the creator's live question must be last"


def test_block_c_starts_with_a_user_message_despite_the_greeting(window):
    """ash P0-1: Spring's first row is Meera's greeting, and an assistant-first Block C is rejected."""
    window(turns=40)
    short = assemble_prompt(_creator_context(_live_thread(4)))
    assert short.messages[0]["role"] == "user"
    assert "greeting-0" not in _rendered(short), "the leading assistant greeting must be dropped"


@pytest.mark.parametrize("length", [38, 40, 42, 60])
def test_the_turn_ceiling_binds_at_its_boundary(window, length):
    """Pins 39/40/41 so 'only window past 25'-style mutants cannot hide (ash P1-3)."""
    window(turns=40)
    prompt = assemble_prompt(_creator_context(_live_thread(length)))

    # A dropped leading greeting means a full window can render one message short; never more.
    assert len(prompt.messages) <= min(length, 40)
    assert len(prompt.messages) >= min(length, 40) - 1
    assert f"user-{length - 1}" in _rendered(prompt)


def test_one_huge_turn_cannot_outweigh_the_budget(window):
    """ash P1-4: a turn count cannot bound tokens, because nothing bounds one turn's size."""
    window(turns=40, char_budget=5_000)
    thread = _live_thread(10)
    thread[3] = {"role": "user", "content": "x" * 30_000}  # noqa: E501  # a pasted brief sitting in the history

    prompt = assemble_prompt(_creator_context(thread))
    rendered = _rendered(prompt)

    assert "x" * 30_000 not in rendered, "the oversized turn must fall outside the budget"
    assert "user-9" in rendered, "the live question is replayed whatever the budget"


def test_the_newest_turn_is_replayed_even_when_it_alone_busts_the_budget(window):
    window(turns=40, char_budget=100)
    thread = _live_thread(6)
    thread[-1] = {"role": "user", "content": "y" * 5_000}

    prompt = assemble_prompt(_creator_context(thread))

    assert len(prompt.messages) == 1
    assert "y" * 5_000 in str(prompt.messages[0]["content"])


def test_zero_disables_the_window(window):
    window(turns=0, char_budget=0)
    prompt = assemble_prompt(_creator_context(_live_thread(60)))
    # 60 turns minus the dropped leading greeting.
    assert len(prompt.messages) == 59
    assert "user-1" in _rendered(prompt)


def test_a_tiny_window_is_raised_to_the_floor(window):
    """`1` would leave Meera with no history at all; the floor stops a config typo doing that."""
    window(turns=1, char_budget=0)
    prompt = assemble_prompt(_creator_context(_live_thread(20)))
    # 4 newest turns kept; the oldest of them is an assistant turn, so 3 render.
    assert len(prompt.messages) == 3
    assert "user-19" in _rendered(prompt)


def test_a_short_creator_history_is_untouched(window):
    window(turns=40)
    prompt = assemble_prompt(_creator_context(_live_thread(4)))
    assert len(prompt.messages) == 3  # 4 turns, leading greeting dropped


def test_brand_history_is_not_windowed(window):
    """The BRAND path is another lane's; it must keep replaying everything it is sent."""
    window(turns=4, char_budget=100)
    brand_context = {
        "audience": "BRAND",
        "workspace_id": "ws_1",
        "prompt_version": "test",
        "conversation": [{"role": "user", "content": f"turn-{i}"} for i in range(30)],
    }
    prompt = assemble_prompt(brand_context)
    assert len(prompt.messages) == 30
