"""Explainer Reel formats (Swapnil 2026-09-26, PROMPT_VERSION .25.8): what Influora learned from a
creator-education Reel audit pack -- only the Reels whose audio was transcribed -- as two
LOOKUP-ONLY knowledge types, so Meera can suggest a proven explainer Reel STRUCTURE and never
another creator's script.

What this pins:
- the data contract: `reel_format` and `reel_format_rule` rows, their required fields and name
  fields; `beats`, `avoid` and `adapt` are lists on reel_format only ("avoid" stays a string on
  lav_placement_rule); 4 to 8 beats; every `adapt` item reads "Category: one-line idea";
  `confidence` is optional on these two types and still checked when present;
- the loader fails loud on a missing or blank field, a wrong shape and a duplicate name;
- LOOKUP ONLY: both types render into the one topic "reel_formats", never into the always-sent
  block, whose text is the pre-change block plus exactly one "More on request" line;
- the topic, the tool's enum and the local tool run in-process on it;
- the committed rows (once they land): 6-8 formats, 10-15 rules, evidence only from the 10
  transcribed Reels, no performance promise, no simplified claim taught as fact, a child never
  required;
- the one persona line (adapt, never copy) and the version bump.

The small fixture below (2 formats + 2 rules) is synthetic and generic: it is not taken from the
audit pack and never enters the knowledge file.
"""

from __future__ import annotations

import hashlib
import json
import re
from pathlib import Path
from typing import Any
from unittest.mock import AsyncMock, MagicMock

import pytest

from app.config import PROMPT_VERSION
from app.prompt.assembler import assemble_prompt
from app.prompt.content_knowledge import (
    CONFIDENCE_OPTIONAL_TYPES,
    CREATOR_KNOWLEDGE_ROWS,
    CREATOR_KNOWLEDGE_TEXT,
    LOOKUP_TEXT,
    LOOKUP_TOPICS,
    MORE_ON_REQUEST_HEADING,
    NAME_FIELD,
    REEL_FORMATS_HEADING,
    REQUIRED_FIELDS,
    TYPE_LIST_FIELDS,
    KnowledgeFileError,
    load_knowledge,
    render_knowledge_block,
    render_lookup_section,
    render_lookup_topic,
    render_shooting_lines,
)
from app.prompt.creator_persona import MEERA_CREATOR_PERSONA
from app.providers.claude import ClaudeStreamEvent
from app.tools.creator_schemas import GET_CREATOR_KNOWLEDGE, GET_CREATOR_KNOWLEDGE_SCHEMA
from app.tools.loop import ToolLoopContext, run_tool_loop

REEL_TYPES = ("reel_format", "reel_format_rule")
TOPIC = "reel_formats"

# CREATOR_KNOWLEDGE_TEXT at 7c25c02f (release/0924), before the Reel formats: sha256 and length.
PRE_REEL_BLOCK_SHA256 = "b035d0da8c1df345d9e4000c03df92780f1dd57ada6e208a6459f3adf644cd48"
PRE_REEL_BLOCK_LEN = 105_546
# Owner decision D (2026-09-26) changed the always-sent coach bank after that snapshot: can_move
# and prop_ready got new options and product_side follows prop_ready. `_undo_coach_bank_edit`
# puts the old two lines back (fixtures/coach_bank_before_0926.jsonl) and drops the added one,
# so the pin still proves nothing ELSE in the block moved.
COACH_BEFORE_PATH = Path(__file__).parent / "fixtures" / "coach_bank_before_0926.jsonl"
COACH_ADDED_IDS = ("product_side",)


def _coach_line(row: dict[str, Any]) -> str:
    return (
        f"- {row['id']}: {row['question_en']} / {row['question_hi']} Options: {' / '.join(row['options'])}"
        f" (Hinglish: {' / '.join(row['options_hi'])}). Decides: {row['resolves']}\n"
    )


def _undo_coach_bank_edit(text: str) -> str:
    rows = {r["id"]: r for r in CREATOR_KNOWLEDGE_ROWS if r["data_type"] == "coach_question"}
    for qid in COACH_ADDED_IDS:
        assert text.count(_coach_line(rows[qid])) == 1, qid
        text = text.replace(_coach_line(rows[qid]), "")
    before = [json.loads(line) for line in COACH_BEFORE_PATH.read_text(encoding="utf-8").splitlines() if line.strip()]
    assert {r["id"] for r in before} == {"can_move", "prop_ready"}
    for old in before:
        new_line = _coach_line(rows[old["id"]])
        assert text.count(new_line) == 1, old["id"]
        text = text.replace(new_line, _coach_line(old))
    return text

# The audit pack's Reels whose audio was transcribed, and the ones that were not.
TRANSCRIBED = {"01", "02", "06", "07", "10", "11", "12", "13", "14", "15"}
NOT_TRANSCRIBED = {"03", "04", "05", "08", "09", "16"}

PERSONA_LINE = (
    "- Reel formats. When a creator wants a Reel that explains or teaches one concept (explainer,"
    " tutorial, how-it-works), or asks for such a format, look up reel_formats; its beats refine the"
    " Grab-Story-CTA or Three-act choice. Adapt the structure to the creator's own topic, words and"
    " language; never copy another creator's script, captions or look."
)

# The source creator's own on-screen captions and the audited Reels' simplified technical claims:
# none may reach a committed row, so Meera never repeats them as if they were structure or fact.
VERBATIM_SOURCE_FRAGMENTS = ("step 1 - logic", "step 2 - tool", "ye kya hai", "wait.. to be mindblown")
SIMPLIFIED_CLAIMS = (
    "99%", "mkv", "kelvin", "lossless", "alpha channel", "bit depth", "h.264", "h.265", "1/200",
    "270 gb",
)

FIXTURE_SOURCE = "Synthetic test fixture, not from any account"


def _format(name: str, **change: Any) -> dict[str, Any]:
    row = {
        "data_type": "reel_format",
        "format_name": name,
        "best_for": "Explaining one concept a beginner mixes up.",
        "hook": "Ask the question the viewer already has.",
        "beats": ["Question", "Common wrong answer", "Correct answer", "Show it", "Follow ask"],
        "layout": "Example on top, the person explaining below.",
        "pacing": "One idea per short beat.",
        "text_style": "Two to four words on screen per beat.",
        "cta": "One follow ask after the answer.",
        "avoid": ["Two concepts in one Reel", "Answering before the question lands"],
        "adapt": ["Food: why dough rises", "Finance: saving versus investing"],
        "evidence": "Synthetic fixture row.",
        "source": FIXTURE_SOURCE,
    }
    row.update(change)
    return row


def _rule(name: str, **change: Any) -> dict[str, Any]:
    row = {
        "data_type": "reel_format_rule",
        "rule": name,
        "why": "The viewer can follow one change at a time.",
        "source": FIXTURE_SOURCE,
    }
    row.update(change)
    return row


def _fixture() -> list[dict[str, Any]]:
    return [
        _format("Fixture question format"),
        _format(
            "Fixture contrast format",
            hook="Which one is it -- X or Y?",
            beats=["Name both", "Show X", "Show Y", "Say the difference"],
        ),
        _rule("Fixture rule one"),
        _rule("Fixture rule two", why="Keeps the frame stable while the example changes"),
    ]


def _write(tmp_path: Path, rows: list[dict[str, Any]]) -> Path:
    p = tmp_path / "k.jsonl"
    p.write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows), encoding="utf-8")
    return p


def _non_reel_rows() -> list[dict[str, Any]]:
    return [r for r in CREATOR_KNOWLEDGE_ROWS if r["data_type"] not in REEL_TYPES]


def _real(data_type: str) -> list[dict[str, Any]]:
    return [r for r in CREATOR_KNOWLEDGE_ROWS if r["data_type"] == data_type]


# --- the data contract and the loader ------------------------------------------------------------


def test_the_contract_fields_and_names():
    assert REQUIRED_FIELDS["reel_format"] == (
        "format_name", "best_for", "hook", "beats", "layout", "pacing", "text_style", "cta", "avoid",
        "adapt", "evidence",
    )
    assert REQUIRED_FIELDS["reel_format_rule"] == ("rule", "why")
    assert NAME_FIELD["reel_format"] == "format_name"
    assert NAME_FIELD["reel_format_rule"] == "rule"
    assert TYPE_LIST_FIELDS["reel_format"] == {"beats", "avoid", "adapt"}
    assert set(CONFIDENCE_OPTIONAL_TYPES) == set(REEL_TYPES)


def test_the_fixture_loads(tmp_path):
    rows = load_knowledge(_write(tmp_path, _fixture()))
    assert [r["data_type"] for r in rows] == ["reel_format", "reel_format", "reel_format_rule", "reel_format_rule"]


@pytest.mark.parametrize(
    "make, field",
    [(_format, f) for f in REQUIRED_FIELDS["reel_format"] + ("source",)]
    + [(_rule, f) for f in REQUIRED_FIELDS["reel_format_rule"] + ("source",)],
)
def test_a_missing_field_fails_the_load(tmp_path, make, field):
    row = make("Fixture name")
    # Control: the whole row loads, so each failure below is the one field and nothing else.
    assert len(load_knowledge(_write(tmp_path, [row]))) == 1
    row.pop(field)
    with pytest.raises(KnowledgeFileError, match=re.escape(repr(field))):
        load_knowledge(_write(tmp_path, [row]))
    row[field] = [] if field in TYPE_LIST_FIELDS.get(row["data_type"], ()) else "  "
    with pytest.raises(KnowledgeFileError, match=re.escape(repr(field))):
        load_knowledge(_write(tmp_path, [row]))


@pytest.mark.parametrize(
    "change, message",
    [
        ({"beats": "Question -> Answer -> Demo -> Follow"}, "'beats' must be a non-empty list"),
        ({"avoid": "Two concepts in one Reel"}, "'avoid' must be a non-empty list"),
        ({"adapt": ["Food: why dough rises", " "]}, "'adapt' must be a non-empty list"),
        ({"beats": ["Question", "Answer", "Follow"]}, "needs 4-8 beats, has 3"),
        ({"beats": [f"Beat {i}" for i in range(9)]}, "needs 4-8 beats, has 9"),
        ({"adapt": ["why dough rises, for food creators"]}, "must read 'Category: one-line idea'"),
        ({"adapt": [": no category"]}, "must read 'Category: one-line idea'"),
        ({"confidence": "certain"}, "unknown confidence 'certain'"),
    ],
)
def test_the_loader_rejects_a_bad_format_row(tmp_path, change, message):
    with pytest.raises(KnowledgeFileError, match=re.escape(message)):
        load_knowledge(_write(tmp_path, [_format("Fixture name", **change)]))


def test_the_edge_beat_counts_load(tmp_path):
    for n in (4, 8):
        row = _format(f"Fixture {n}", beats=[f"Beat {i}" for i in range(n)])
        assert load_knowledge(_write(tmp_path, [row]))[0]["beats"] == row["beats"]


def test_confidence_is_optional_on_the_reel_types_only(tmp_path):
    assert "confidence" not in _fixture()[0]
    for conf in ("high", "medium"):
        assert load_knowledge(_write(tmp_path, [_rule("Fixture name", confidence=conf)]))
    with pytest.raises(KnowledgeFileError, match="unknown confidence 'certain'"):
        load_knowledge(_write(tmp_path, [_rule("Fixture name", confidence="certain")]))
    other = dict(next(r for r in CREATOR_KNOWLEDGE_ROWS if r["data_type"] == "permanent_rule"))
    other.pop("confidence")
    with pytest.raises(KnowledgeFileError, match="missing required field 'confidence'"):
        load_knowledge(_write(tmp_path, [other]))


def test_avoid_is_still_a_string_on_lav_placement_rule(tmp_path):
    lav = dict(next(r for r in CREATOR_KNOWLEDGE_ROWS if r["data_type"] == "lav_placement_rule"))
    assert isinstance(lav["avoid"], str)
    assert len(load_knowledge(_write(tmp_path, [lav]))) == 1


@pytest.mark.parametrize("make", [_format, _rule])
def test_a_duplicate_name_fails_the_load(tmp_path, make):
    row = make("Fixture name")
    twin = make("Fixture name ", evidence="Other wording.") if make is _format else make(
        "Fixture name ", why="Other wording."
    )
    with pytest.raises(KnowledgeFileError, match=f"duplicate {row['data_type']} entry"):
        load_knowledge(_write(tmp_path, [row, twin]))


# --- rendering: lookup only -------------------------------------------------------------------


def test_the_fixture_renders_into_the_topic():
    text = render_lookup_topic(_fixture(), TOPIC)
    lines = text.splitlines()
    assert lines[0] == REEL_FORMATS_HEADING
    assert (
        "- Fixture question format: Best for: Explaining one concept a beginner mixes up."
        " Hook: Ask the question the viewer already has."
        " Beats: Question -> Common wrong answer -> Correct answer -> Show it -> Follow ask."
        " Layout: Example on top, the person explaining below. Pacing: One idea per short beat."
        " On-screen text: Two to four words on screen per beat. CTA: One follow ask after the answer."
        " Avoid: Two concepts in one Reel; Answering before the question lands."
        " Adapt: Food: why dough rises; Finance: saving versus investing."
        " Evidence: Synthetic fixture row."
    ) in lines
    # A hook that ends in "?" keeps it, with no stray full stop.
    assert "Hook: Which one is it -- X or Y? Beats: Name both -> Show X -> Show Y -> Say the difference." in text
    assert "- Fixture rule one: The viewer can follow one change at a time." in lines
    assert "- Fixture rule two: Keeps the frame stable while the example changes." in lines
    # Formats, then rules, then each source once.
    at = [text.index(s) for s in ("Formats (", "Rules for any explainer Reel:", "Where these come from (")]
    assert at == sorted(at)
    assert text.count(FIXTURE_SOURCE) == 1
    assert text.count("\n- ") == 5


def test_the_heading_says_copy_the_structure_and_bounds_the_advice():
    h = REEL_FORMATS_HEADING
    assert "Copy the structure, never the script" in h
    assert "never a promise of views, reach or retention" in h
    assert "technical claims are not facts to teach" in h
    assert "any second person or an on-screen question" in h
    assert "a child on camera only with a parent's or guardian's consent" in h
    # Meera has no way to check a fact: she names what the creator must check, never "checked".
    assert "You cannot verify facts" in h
    assert "never present one as checked" in h
    # Observed lengths are not targets; attribution never claims the formats as hers or proven.
    assert "Pacing is what the source Reels ran, not a target" in h
    assert "never yours or proven" in h
    low = h.lower()
    for banned in ("viral", "more views", "escrow", "tiktok"):
        assert banned not in low, banned


@pytest.mark.parametrize("drop", REEL_TYPES)
def test_an_empty_section_fails(drop):
    rows = [r for r in _fixture() if r["data_type"] != drop]
    with pytest.raises(KnowledgeFileError, match="has no rows"):
        render_lookup_topic(rows, TOPIC)


def test_the_reel_rows_add_nothing_to_the_every_turn_block_or_the_frame_check():
    base = _non_reel_rows()
    with_fixture = base + _fixture()
    assert render_knowledge_block(with_fixture) == render_knowledge_block(base) == CREATOR_KNOWLEDGE_TEXT
    assert render_shooting_lines(with_fixture, with_phone_notes=False) == render_shooting_lines(
        base, with_phone_notes=False
    )
    for r in _fixture():
        assert r[NAME_FIELD[r["data_type"]]] not in CREATOR_KNOWLEDGE_TEXT
    assert REEL_FORMATS_HEADING not in CREATOR_KNOWLEDGE_TEXT


def test_the_every_turn_block_is_the_pre_change_block_plus_one_line():
    line = f"- {TOPIC}: {LOOKUP_TOPICS[TOPIC]}\n"
    assert CREATOR_KNOWLEDGE_TEXT.endswith(line)
    pre = _undo_coach_bank_edit(CREATOR_KNOWLEDGE_TEXT[: -len(line)])
    assert len(pre) == PRE_REEL_BLOCK_LEN
    assert hashlib.sha256(pre.encode("utf-8")).hexdigest() == PRE_REEL_BLOCK_SHA256


# --- the topic and the tool ------------------------------------------------------------------------


def test_the_topic_is_listed_last_with_its_line():
    assert list(LOOKUP_TOPICS)[-1] == TOPIC
    description = LOOKUP_TOPICS[TOPIC]
    assert description.startswith("Explainer Reel formats that teach one concept:")
    for word in ("question and answer", "A-vs-B", "analogy", "technique lists", "result-tease", "what to avoid"):
        assert word in description, word
    section = CREATOR_KNOWLEDGE_TEXT[CREATOR_KNOWLEDGE_TEXT.index(MORE_ON_REQUEST_HEADING) :]
    assert f"- {TOPIC}: {description}" in section.splitlines()


def test_the_tool_enum_and_description_carry_the_topic():
    schema = GET_CREATOR_KNOWLEDGE_SCHEMA["input_schema"]
    assert schema["properties"]["topic"]["enum"] == list(LOOKUP_TOPICS)
    assert TOPIC in schema["properties"]["topic"]["enum"]
    assert f"{TOPIC}: " in GET_CREATOR_KNOWLEDGE_SCHEMA["description"]
    assert render_lookup_section(TOPIC) == LOOKUP_TEXT[TOPIC]
    assert LOOKUP_TEXT[TOPIC].startswith(REEL_FORMATS_HEADING + "\n")


class _FakeClaude:
    def __init__(self, turns: list[list[ClaudeStreamEvent]]):
        self._turns = list(turns)
        self.calls: list[dict[str, Any]] = []

    def stream_turn(self, **kwargs: Any):
        self.calls.append(kwargs)
        events = self._turns[len(self.calls) - 1]

        async def _gen():
            for event in events:
                yield event

        return _gen()


async def test_the_local_tool_returns_the_topic_in_process():
    prompt = assemble_prompt(
        {
            "workspace_id": "creator-reel-formats",
            "audience": "CREATOR",
            "creator": {"workspace_id": "creator-reel-formats", "tools_enabled": []},
            "conversation": [{"role": "user", "content": "explainer reel ka format batao"}],
        },
        session_id="s-reel-formats",
    )
    claude = _FakeClaude(
        [
            [ClaudeStreamEvent(type="tool_use", tool_name=GET_CREATOR_KNOWLEDGE, tool_input={"topic": TOPIC}, tool_use_id="t1")],
            [ClaudeStreamEvent(type="text", text="Here is a structure.")],
        ]
    )
    spring = MagicMock()
    spring.call_tool_endpoint = AsyncMock(side_effect=AssertionError("forwarded to Spring"))
    _ = [
        e
        async for e in run_tool_loop(
            claude=claude,
            spring=spring,
            system_blocks=[],
            initial_messages=[],
            ctx=ToolLoopContext(workspace_id="creator-reel-formats", onbehalf_jwt="fake-jwt", max_iterations=4),
            tools=prompt.tools,
        )
    ]
    assert len(claude.calls) == 2
    results = [
        b
        for m in claude.calls[1]["messages"]
        if m["role"] == "user" and isinstance(m["content"], list)
        for b in m["content"]
        if isinstance(b, dict) and b.get("type") == "tool_result"
    ]
    assert len(results) == 1 and not results[0].get("is_error")
    content = results[0]["content"]
    payload = json.loads(content if isinstance(content, str) else content[0]["text"])
    assert payload == {"topic": TOPIC, "knowledge": LOOKUP_TEXT[TOPIC]}
    spring.call_tool_endpoint.assert_not_awaited()


# --- the committed rows ------------------------------------------------------------------------------


def test_the_committed_rows_meet_the_contract_counts():
    formats, rules = _real("reel_format"), _real("reel_format_rule")
    assert 6 <= len(formats) <= 8, len(formats)
    assert 10 <= len(rules) <= 15, len(rules)
    # Exact counts of the rows that landed (2026-09-26): 7 formats from the 10 transcribed Reels,
    # 15 rules. A row added or dropped must update this pin on purpose.
    assert (len(formats), len(rules)) == (7, 15)
    text = LOOKUP_TEXT[TOPIC]
    for r in formats + rules:
        assert f"- {r[NAME_FIELD[r['data_type']]].strip().rstrip('.')}: " in text, r
    assert text.count("\n- ") == len(formats) + len(rules) + len({r["source"].strip() for r in formats + rules})


def test_the_committed_formats_cite_only_transcribed_reels_and_the_account():
    for r in _real("reel_format"):
        # "Reel 4" and "Reels 11 and 4" count as Reel 04 (a non-transcribed id), padded or not.
        cited = {f"{int(n):02d}" for n in re.findall(r"(?<!\d)(0?[1-9]|1[0-6])(?!\d)", r["evidence"])}
        assert cited, ("evidence names no audited Reel", r["format_name"])
        assert cited <= TRANSCRIBED, (r["format_name"], sorted(cited - TRANSCRIBED))
        assert "transcri" in r["evidence"].lower(), r["format_name"]
    for r in _real("reel_format") + _real("reel_format_rule"):
        assert "Planet in Pixel" in r["source"], r
        assert "@planetinpixel.hindi" in r["source"], r


def test_the_committed_rows_promise_no_performance_and_teach_no_simplified_claim():
    for r in _real("reel_format") + _real("reel_format_rule"):
        low = json.dumps(r, ensure_ascii=False).lower()
        for banned in (
            "viral", "more views", "more reach", "guaranteed", "escrow", "tiktok", "most guess",
            "synthetic training", "similar reels", "yellow",
        ) + SIMPLIFIED_CLAIMS + VERBATIM_SOURCE_FRAGMENTS:
            assert banned not in low, (banned, r[NAME_FIELD[r["data_type"]]])


def test_the_committed_formats_are_templates_not_the_source_look():
    """Hooks are bracketed, language-neutral templates (like the hook_template rows), a learner is
    optional in every layout, the consent wording lives in the heading and the rule only, and the
    rendered topic names the public account but not the internal audit pack."""
    for r in _real("reel_format"):
        name = r["format_name"]
        assert re.search(r"\[[^\]]+\]", r["hook"]), ("hook is not a [placeholder] template", name)
        assert "learner optional" in r["layout"], name
        assert "consent" not in r["layout"].lower(), name
    ctas = [r["cta"].lower() for r in _real("reel_format")]
    assert sum("card" in c for c in ctas) <= 2, ctas
    text = LOOKUP_TEXT[TOPIC]
    assert "Influora audit pack" not in text
    assert "- Planet in Pixel - Hindi (@planetinpixel.hindi), Instagram 2026\n" in text
    assert text.count("consent") == 2, text.count("consent")


def test_a_child_is_never_required():
    for r in _real("reel_format") + _real("reel_format_rule"):
        low = json.dumps(r, ensure_ascii=False).lower()
        if re.search(r"\b(child|children|kid|kids|bachcha|bachche)\b", low):
            assert "consent" in low, r[NAME_FIELD[r["data_type"]]]
            assert "second person" in low or "on-screen question" in low, r[NAME_FIELD[r["data_type"]]]


# --- persona and version ------------------------------------------------------------------------------


def _system_text(audience: str) -> str:
    if audience == "CREATOR":
        payload = {
            "workspace_id": "creator-reel-persona",
            "audience": "CREATOR",
            "creator": {"workspace_id": "creator-reel-persona", "display_name": "Asha"},
            "conversation": [{"role": "user", "content": "tutorial reel structure?"}],
        }
    else:
        payload = {"workspace_id": "ws-brand-reel", "audience": "BRAND", "brand": {}, "conversation": []}
    prompt = assemble_prompt(payload, session_id="s-reel-persona")
    return " ".join("\n".join(b.get("text", "") for b in prompt.system_blocks).split())


def test_the_persona_line_says_look_up_adapt_and_never_copy():
    persona = " ".join(MEERA_CREATOR_PERSONA.split())
    assert PERSONA_LINE in persona
    assert persona.count("reel_formats") == 1
    assert "Adapt the structure to the creator's own topic, words and language" in PERSONA_LINE
    assert "never copy another creator's script, captions or look" in PERSONA_LINE
    # The Reel structures it refines are the always-sent block's own names.
    for name in ("Grab-Story-CTA", "Three-act"):
        assert name in CREATOR_KNOWLEDGE_TEXT, name
    assert PERSONA_LINE in _system_text("CREATOR")
    assert "Reel formats." not in _system_text("BRAND")


def test_prompt_version_bumped_for_reel_formats():
    # .25.8 shipped reel_formats; .25.9 (the Meera intelligence merge) keeps it.
    assert PROMPT_VERSION == "meera-2026.09.25.9"
