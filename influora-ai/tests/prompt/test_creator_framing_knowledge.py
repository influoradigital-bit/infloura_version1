"""Knowledge merge of dataset 9 (shoot guide spec v2, Phase 6 "Knowledge merge", 2026-09-26):
framing rules and worked examples per content category, plus the general shot-planning rows.

What this pins:
- dataset 9's new rows load, by type (162 in the dataset; its TikTok safe-zone row is not taken
  because the knowledge file never names TikTok -> 161), and the 359 rows that were already
  here are unchanged and in their old order (only the export row's safe zones were rewritten,
  pinned against the config in test_export_row_safe_zones.py);
- the 20 compound confidence values were split: the first word is the confidence, the rest is
  `limits`; no row carries a creator handle, TikTok, "exactly", "two fingers" or a 15/25/40%
  zone number;
- a duplicate name fails the load for each of the 8 new types, and so do an unmapped
  composition category, a missing step number and a non-boolean "safe zone documented";
- LOOKUP ONLY: the always-sent block is byte-identical to the pre-merge snapshot except for
  the two planned edits (the export row and the new "More on request" lines), and the new rows
  add nothing to it or to the frame check's shooting section;
- every new topic renders non-empty, carries exactly its rows, and an empty topic fails;
- the playbook-category -> framing-topic table is the spec's, each topic's line names its
  playbook categories, and the tool's single topic enum grew with it;
- the persona's one "Framing first" rule.

Owner decision D (2026-09-26) made one more planned edit to the pre-merge rows: the coach bank's
can_move and prop_ready got new answer options, and a product_side follow-up row sits right after
prop_ready. The rows as they were are kept in fixtures/coach_bank_before_0926.jsonl; every pin
below undoes exactly that edit (and nothing else) before it compares.
"""

from __future__ import annotations

import hashlib
import json
import re
from pathlib import Path
from typing import Any

import pytest

from app.prompt.assembler import assemble_prompt
from app.prompt.content_knowledge import (
    CREATOR_KNOWLEDGE_ROWS,
    CREATOR_KNOWLEDGE_TEXT,
    FRAMING_CATEGORY_TOPICS,
    FRAMING_TOPICS,
    KNOWLEDGE_PATH,
    LOOKUP_TEXT,
    LOOKUP_TOPICS,
    MORE_ON_REQUEST_HEADING,
    NAME_FIELD,
    PLAYBOOK_FRAMING_TOPICS,
    REQUIRED_FIELDS,
    KnowledgeFileError,
    _check_playbook_framing_topics,
    load_knowledge,
    render_knowledge_block,
    render_lookup_section,
    render_lookup_topic,
    render_shooting_lines,
)
from app.prompt.creator_persona import MEERA_CREATOR_PERSONA
from app.tools.creator_schemas import GET_CREATOR_KNOWLEDGE_SCHEMA

FIXTURE = Path(__file__).parent / "fixtures" / "creator_knowledge_every_turn_pre_merge.txt"
# CREATOR_KNOWLEDGE_TEXT at fd8d5d24 (release/0924), snapshotted before any merge edit. Pinned so
# the fixture itself cannot be regenerated from the merged code without this test noticing.
FIXTURE_SHA256 = "d4d93601e17791436b9cba5c9e643b43c9b0d41053ab03229617668540ce98f6"
# The knowledge file at fd8d5d24 (LF line endings), 359 rows.
PRE_MERGE_FILE_SHA256 = "4947a08fdf3a15b9b321a1534f21232491cabf72b3035c154041d0b3bad3c318"
PRE_MERGE_ROWS = 359
OLD_EXPORT_SAFE_ZONES = (
    "Keep face and critical text out of the top 15% (platform UI/logo) and bottom 25%"
    " (captions/username)."
)
EXPORT_PLATFORM = "Instagram Reels / YouTube Shorts"

DATASET_9_COUNTS: dict[str, int] = {
    "category_composition_rule": 109,
    "category_composition_example": 20,
    "action_to_shot_planning_step": 8,
    "shot_size_vocabulary": 7,
    "camera_movement_principle": 6,
    "platform_safe_zone_fact": 4,  # 5 in dataset 9; its TikTok row is not taken
    "lighting_movement_principle": 4,
    "smartphone_perspective_principle": 3,
}
DATASET_9_TYPES = tuple(DATASET_9_COUNTS)
# Lookup-only rows and topics added AFTER dataset 9 (explainer Reel formats, 2026-09-26,
# test_creator_reel_formats.py). They are appended after dataset 9's rows, their topic follows
# dataset 9's topics, and they add one "More on request" line to the always-sent block.
LATER_TYPES = ("reel_format", "reel_format_rule")
LATER_TOPICS = ["reel_formats"]
GENERAL_TYPES = tuple(t for t in DATASET_9_TYPES if not t.startswith("category_composition"))

# Owner decision D (see the module docstring): the coach rows as they were, and the row added.
COACH_BEFORE_PATH = Path(__file__).parent / "fixtures" / "coach_bank_before_0926.jsonl"
COACH_BEFORE_LINES: dict[str, str] = {
    json.loads(line)["id"]: line for line in COACH_BEFORE_PATH.read_text(encoding="utf-8").splitlines() if line.strip()
}
COACH_ADDED_IDS = ("product_side",)


def _is_added_coach_row(row: dict[str, Any]) -> bool:
    return row["data_type"] == "coach_question" and row["id"] in COACH_ADDED_IDS


# The rows without the one row owner decision D added: the order every pre-merge pin was cut on.
ROWS_BEFORE_COACH_ADD = [r for r in CREATOR_KNOWLEDGE_ROWS if not _is_added_coach_row(r)]


def _coach_line(row: dict[str, Any]) -> str:
    """One coach bank line of the always-sent block, built here from the row."""
    return (
        f"- {row['id']}: {row['question_en']} / {row['question_hi']} Options: {' / '.join(row['options'])}"
        f" (Hinglish: {' / '.join(row['options_hi'])}). Decides: {row['resolves']}\n"
    )

NEW_TOPICS = [
    "shot_planning",
    "framing_beauty_grwm",
    "framing_fashion",
    "framing_food_cooking",
    "framing_fitness",
    "framing_tech_product",
    "framing_screen_demo",
    "framing_finance_education",
    "framing_travel_vlog",
    "framing_comedy_lifestyle",
    "framing_groups",
    "framing_motivational",
]

# Spec v2 Phase 6 "Category mapping" table, written out by hand (not read from the module).
SPEC_PLAYBOOK_TABLE: dict[str, tuple[str, ...]] = {
    "Beauty & skincare": ("framing_beauty_grwm",),
    "Fashion": ("framing_fashion",),
    "Food": ("framing_food_cooking",),
    "Fitness": ("framing_fitness",),
    "Tech & gadgets": ("framing_tech_product", "framing_screen_demo"),
    "Personal finance": ("framing_finance_education",),
    "Education": ("framing_finance_education",),
    "Travel": ("framing_travel_vlog",),
    "Comedy & entertainment": ("framing_comedy_lifestyle",),
    "Lifestyle": ("framing_comedy_lifestyle",),
    "Parenting": ("shot_planning",),
    "Wellness": ("shot_planning",),
    "Gaming": ("shot_planning",),
}


def _rows(data_type: str) -> list[dict[str, Any]]:
    return [r for r in CREATOR_KNOWLEDGE_ROWS if r["data_type"] == data_type]


def _dataset_9_rows() -> list[dict[str, Any]]:
    return [r for r in CREATOR_KNOWLEDGE_ROWS if r["data_type"] in DATASET_9_TYPES]


def _write(tmp_path: Path, rows: list[dict[str, Any]]) -> Path:
    p = tmp_path / "k.jsonl"
    p.write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows), encoding="utf-8")
    return p


# --- the rows ----------------------------------------------------------------------------------


def test_dataset_9_rows_load_by_type():
    counts = {t: len(_rows(t)) for t in DATASET_9_TYPES}
    assert counts == DATASET_9_COUNTS
    assert sum(counts.values()) == 161
    later = [r for r in CREATOR_KNOWLEDGE_ROWS if r["data_type"] in LATER_TYPES]
    rows = ROWS_BEFORE_COACH_ADD
    assert len(CREATOR_KNOWLEDGE_ROWS) == len(rows) + len(COACH_ADDED_IDS)
    assert len(rows) == PRE_MERGE_ROWS + 161 + len(later)
    # Appended after the old rows, never mixed in; later lookup-only rows come after them.
    assert all(r["data_type"] not in DATASET_9_TYPES for r in rows[:PRE_MERGE_ROWS])
    assert all(r["data_type"] in DATASET_9_TYPES for r in rows[PRE_MERGE_ROWS : PRE_MERGE_ROWS + 161])
    assert rows[PRE_MERGE_ROWS + 161 :] == later
    # The added coach row sits right after prop_ready, inside the coach bank.
    ids = [r.get("id") for r in CREATOR_KNOWLEDGE_ROWS]
    assert ids.index("product_side") == ids.index("prop_ready") + 1


def test_the_pre_merge_rows_are_unchanged_and_in_order():
    lines = KNOWLEDGE_PATH.read_text(encoding="utf-8").splitlines()
    # Owner decision D: undo the coach bank edit -- drop the added row, restore the two old rows.
    undone: list[str] = []
    restored = set()
    for line in lines:
        row = json.loads(line) if line.strip() else None
        if row is not None and row["data_type"] == "coach_question":
            if row["id"] in COACH_ADDED_IDS:
                continue
            if row["id"] in COACH_BEFORE_LINES:
                line = COACH_BEFORE_LINES[row["id"]]
                restored.add(row["id"])
        undone.append(line)
    assert restored == set(COACH_BEFORE_LINES) == {"can_move", "prop_ready"}
    old = undone[:PRE_MERGE_ROWS]
    export = [i for i, line in enumerate(old) if '"platform_export_setting"' in line and EXPORT_PLATFORM in line]
    assert len(export) == 1
    row = json.loads(old[export[0]])
    # The one planned rewrite (spec Phase 4 "Knowledge fix"); undo it and the bytes are the old file.
    old[export[0]] = old[export[0]].replace(json.dumps(row["safe_zones"], ensure_ascii=False)[1:-1], OLD_EXPORT_SAFE_ZONES)
    digest = hashlib.sha256(("\n".join(old) + "\n").encode("utf-8")).hexdigest()
    assert digest == PRE_MERGE_FILE_SHA256


def test_compound_confidence_was_split_into_confidence_and_limits():
    examples = _rows("category_composition_example")
    assert len(examples) == 20
    for r in examples:
        assert r["confidence"] in ("high", "medium"), r["scenario"]
        assert isinstance(r["limits"], str) and r["limits"].strip(), r["scenario"]
        assert "—" not in r["confidence"]
    assert all(r["confidence"] in ("high", "medium", "low") for r in _dataset_9_rows())


def test_the_loader_rejects_the_unsplit_compound_confidence(tmp_path):
    row = dict(_rows("category_composition_example")[0])
    row.pop("limits")
    row["confidence"] = "medium — practical composition translation"
    with pytest.raises(KnowledgeFileError, match="unknown confidence"):
        load_knowledge(_write(tmp_path, [row]))


def test_no_handle_tiktok_or_banned_wording_in_the_new_rows():
    for r in _dataset_9_rows():
        text = json.dumps(r, ensure_ascii=False)
        assert not re.search(r"(?<![\w.])@\w", text), text[:120]
        low = text.lower()
        for banned in ("tiktok", "exactly", "two fingers", "found in nature", "escrow", "co-pilot"):
            assert banned not in low, (banned, text[:120])
        for pct in ("15%", "25%", "40%"):
            assert not re.search(rf"(?<!\d){re.escape(pct)}", text), (pct, text[:120])


def test_the_ex_tiktok_examples_are_credited_to_no_platform_they_did_not_come_from():
    # Dataset 9 credits five examples to TikTok. The file never names TikTok, and crediting them to
    # Instagram Reels / YouTube Shorts (the export row's platform) would claim a source they do not
    # have, so they read "Vertical short-form", the composition rules' own `platform_scope` value.
    neutral = sorted(r["scenario"] for r in _rows("category_composition_example") if r["platform"] == "Vertical short-form")
    assert neutral == sorted([
        "Head-to-toe outfit reveal",
        "Mixing batter and showing texture",
        "AI image generator prompt-to-result walkthrough",
        "Motivational message filmed while walking outdoors",
        "Deadpan household comedy reveal",
    ])
    assert not [r["scenario"] for r in _rows("category_composition_example") if r["platform"] == EXPORT_PLATFORM]


# --- duplicate names and the other load failures ------------------------------------------------


@pytest.mark.parametrize("data_type", DATASET_9_TYPES)
def test_a_duplicate_name_fails_the_load(tmp_path, data_type):
    row = _rows(data_type)[0]
    assert NAME_FIELD[data_type] in REQUIRED_FIELDS[data_type]
    # Control: the row alone loads, so the failure below is the duplicate and nothing else.
    assert len(load_knowledge(_write(tmp_path, [row]))) == 1
    twin = dict(row)
    other_field = next(f for f in REQUIRED_FIELDS[data_type] if f not in (NAME_FIELD[data_type], "category"))
    twin[other_field] = "Different wording, same name."
    with pytest.raises(KnowledgeFileError, match=f"duplicate {data_type} entry"):
        load_knowledge(_write(tmp_path, [row, twin]))


def test_the_name_fields_are_the_spec_ones():
    assert {t: NAME_FIELD[t] for t in DATASET_9_TYPES} == {
        "category_composition_rule": "id",
        "category_composition_example": "scenario",
        "action_to_shot_planning_step": "step",
        "shot_size_vocabulary": "label",
        "camera_movement_principle": "movement",
        "platform_safe_zone_fact": "platform",
        "lighting_movement_principle": "principle",
        "smartphone_perspective_principle": "principle",
    }


@pytest.mark.parametrize(
    "data_type, change, message",
    [
        ("category_composition_rule", {"category": "gardening"}, "has no framing topic"),
        ("category_composition_example", {"category": "gaming"}, "has no framing topic"),
        ("action_to_shot_planning_step", {"step_number": "1"}, "needs a whole step_number"),
        ("action_to_shot_planning_step", {"step_number": True}, "needs a whole step_number"),
        ("action_to_shot_planning_step", {"step_number": 0}, "needs a whole step_number"),
        ("platform_safe_zone_fact", {"organic_safe_zone_documented": "no"}, "true/false"),
        ("category_composition_example", {"lens": " "}, "missing required field 'lens'"),
    ],
)
def test_the_loader_rejects_a_bad_new_row(tmp_path, data_type, change, message):
    row = dict(_rows(data_type)[0]) | change
    with pytest.raises(KnowledgeFileError, match=message):
        load_knowledge(_write(tmp_path, [row]))


def test_a_missing_step_number_fails(tmp_path):
    row = dict(_rows("action_to_shot_planning_step")[0])
    row.pop("step_number")
    with pytest.raises(KnowledgeFileError, match="needs a whole step_number"):
        load_knowledge(_write(tmp_path, [row]))


# --- lookup only: the always-sent block ----------------------------------------------------------


def _expected_every_turn_text() -> str:
    """The pre-merge snapshot with exactly the two planned edits applied, built here from the
    row and LOOKUP_TOPICS -- not by calling the block renderer."""
    pre = FIXTURE.read_bytes().decode("utf-8")
    row = next(r for r in _rows("platform_export_setting") if r["platform"] == EXPORT_PLATFORM)
    head = f"- {row['platform']} ({row['aspect_ratio']}): safe zones: "
    tail = f" Workflow: {row['workflow']}\n"
    old_line = head + OLD_EXPORT_SAFE_ZONES + tail
    assert pre.count(old_line) == 1
    text = pre.replace(old_line, head + row["safe_zones"] + tail)
    # The "More on request" list is the block's last section: the new topics follow it.
    assert text.endswith(f"- delivery_examples: {LOOKUP_TOPICS['delivery_examples']}\n")
    text += "".join(f"- {t}: {LOOKUP_TOPICS[t]}\n" for t in NEW_TOPICS + LATER_TOPICS)
    # Owner decision D: the two coach lines become the rows' new wording, and the added row's
    # line follows prop_ready's.
    for qid in COACH_BEFORE_LINES:
        old_line = _coach_line(json.loads(COACH_BEFORE_LINES[qid]))
        assert text.count(old_line) == 1, qid
        new_line = _coach_line(next(r for r in _rows("coach_question") if r["id"] == qid))
        if qid == "prop_ready":
            new_line += "".join(
                _coach_line(next(r for r in _rows("coach_question") if r["id"] == added)) for added in COACH_ADDED_IDS
            )
        text = text.replace(old_line, new_line)
    return text


def test_the_fixture_is_the_pre_merge_snapshot():
    assert hashlib.sha256(FIXTURE.read_bytes()).hexdigest() == FIXTURE_SHA256


def test_every_turn_block_is_byte_identical_except_the_two_planned_edits():
    # (Plus one "More on request" line per later lookup topic -- LATER_TOPICS.)
    expected = _expected_every_turn_text()
    assert CREATOR_KNOWLEDGE_TEXT.encode("utf-8") == expected.encode("utf-8")


def test_the_new_rows_add_nothing_to_the_every_turn_block_or_the_frame_check():
    without = [r for r in CREATOR_KNOWLEDGE_ROWS if r["data_type"] not in DATASET_9_TYPES + LATER_TYPES]
    assert len(without) == PRE_MERGE_ROWS + len(COACH_ADDED_IDS)
    assert render_knowledge_block(CREATOR_KNOWLEDGE_ROWS) == render_knowledge_block(without)
    assert render_shooting_lines(CREATOR_KNOWLEDGE_ROWS, with_phone_notes=False) == render_shooting_lines(
        without, with_phone_notes=False
    )


def test_the_more_on_request_list_names_the_new_topics_last():
    section = CREATOR_KNOWLEDGE_TEXT[CREATOR_KNOWLEDGE_TEXT.index(MORE_ON_REQUEST_HEADING) :]
    lines = section.rstrip("\n").splitlines()
    tail = NEW_TOPICS + LATER_TOPICS
    assert [line.split(":", 1)[0] for line in lines[-len(tail) :]] == [f"- {t}" for t in tail]


# --- the lookup topics ---------------------------------------------------------------------------


def test_the_new_topics_are_lookup_topics_in_order():
    assert list(LOOKUP_TOPICS)[-len(NEW_TOPICS + LATER_TOPICS) :] == NEW_TOPICS + LATER_TOPICS
    assert list(FRAMING_TOPICS) == NEW_TOPICS[1:]
    # One framing topic per composition category in the file, and no other.
    categories = {r["category"] for t in ("category_composition_rule", "category_composition_example") for r in _rows(t)}
    assert categories == set(FRAMING_CATEGORY_TOPICS)
    assert len(categories) == 11


@pytest.mark.parametrize("topic", NEW_TOPICS)
def test_each_new_topic_renders_non_empty_and_plain(topic):
    text = LOOKUP_TEXT[topic]
    assert render_lookup_section(topic) == text
    assert len(text.strip().splitlines()) > 5
    assert "**" not in text
    assert "tiktok" not in text.lower()
    assert not re.search(r"(?<![\w.])@\w", text)


@pytest.mark.parametrize("topic", NEW_TOPICS[1:])
def test_each_framing_topic_carries_exactly_its_category_rows(topic):
    category = FRAMING_TOPICS[topic][0]
    text = LOOKUP_TEXT[topic]
    mine = [r for r in _rows("category_composition_rule") if r["category"] == category]
    examples = [r for r in _rows("category_composition_example") if r["category"] == category]
    assert mine and examples
    assert text.count("\n- ") == len(mine) + len(examples)
    for r in mine:
        assert f"- {r['scenario']} [{r['id']}]: {r['rule']}" in text
    for r in examples:
        assert f"- {r['scenario']} ({r['platform']}): " in text
        assert f"Limits: {r['limits']}" in text
    for other in NEW_TOPICS[1:]:
        if other != topic:
            for r in mine:
                assert f"[{r['id']}]" not in LOOKUP_TEXT[other], (other, r["id"])


def test_low_confidence_rules_say_so():
    low = [r for r in _rows("category_composition_rule") if r["confidence"] == "low"]
    assert len(low) == 4
    for r in low:
        topic = FRAMING_CATEGORY_TOPICS[r["category"]]
        line = next(line for line in LOOKUP_TEXT[topic].splitlines() if f"[{r['id']}]" in line)
        assert line.endswith("Confidence: low."), line


def test_shot_planning_carries_every_general_row_and_the_steps_in_order():
    text = LOOKUP_TEXT["shot_planning"]
    general = [r for r in CREATOR_KNOWLEDGE_ROWS if r["data_type"] in GENERAL_TYPES]
    assert len(general) == 32
    for r in general:
        assert f"- {r[NAME_FIELD[r['data_type']]]}: " in text, r
    steps = sorted(_rows("action_to_shot_planning_step"), key=lambda r: r["step_number"])
    at = [text.index(f"- {r['step']}: ") for r in steps]
    assert at == sorted(at)
    assert [r["step_number"] for r in steps] == list(range(1, 9))


def test_platform_ad_numbers_are_never_a_rule_for_normal_posts():
    text = LOOKUP_TEXT["shot_planning"]
    facts = [line for line in text.splitlines() if line.startswith("- ") and "Ads only:" in line]
    assert len(facts) == 4
    for line in facts:
        assert "Safe zone for normal posts: not published." in line
        # Meta's 14/35/6 ad margins only ever appear after "Ads only:".
        assert "35%" not in line.split("Ads only:")[0]
    assert text.count("35%") == sum(line.count("35%") for line in facts) > 0
    assert "an ad safe zone is never a rule for a normal post" in text


def test_an_empty_topic_fails(tmp_path):
    no_fitness_examples = [
        r
        for r in CREATOR_KNOWLEDGE_ROWS
        if not (r["data_type"] == "category_composition_example" and r["category"] == "fitness")
    ]
    with pytest.raises(KnowledgeFileError, match="has no rows"):
        render_lookup_topic(no_fitness_examples, "framing_fitness")
    no_sizes = [r for r in CREATOR_KNOWLEDGE_ROWS if r["data_type"] != "shot_size_vocabulary"]
    with pytest.raises(KnowledgeFileError, match="has no rows"):
        render_lookup_topic(no_sizes, "shot_planning")


# --- playbook mapping, tool enum and persona ------------------------------------------------------


def test_the_playbook_table_is_the_spec_table():
    assert PLAYBOOK_FRAMING_TOPICS == SPEC_PLAYBOOK_TABLE
    playbooks = {r["category"] for r in _rows("category_playbook")}
    assert set(SPEC_PLAYBOOK_TABLE) == playbooks
    for category, topics in SPEC_PLAYBOOK_TABLE.items():
        for topic in topics:
            # The "More on request" line is how the model finds its creator's topic.
            assert f"- {topic}: " in CREATOR_KNOWLEDGE_TEXT
            assert category in LOOKUP_TOPICS[topic], (category, topic)


def test_the_mapping_check_fails_on_a_new_playbook_or_a_line_without_its_category(monkeypatch):
    extra = dict(_rows("category_playbook")[0]) | {"category": "Gardening"}
    with pytest.raises(KnowledgeFileError, match="missing \\['Gardening'\\]"):
        _check_playbook_framing_topics(CREATOR_KNOWLEDGE_ROWS + [extra])
    monkeypatch.setitem(LOOKUP_TOPICS, "framing_fitness", "Framing for gyms.")
    with pytest.raises(KnowledgeFileError, match="does not name its playbook 'Fitness'"):
        _check_playbook_framing_topics(CREATOR_KNOWLEDGE_ROWS)


def test_the_tool_topic_enum_grew_as_one_enum():
    schema = GET_CREATOR_KNOWLEDGE_SCHEMA["input_schema"]
    topic = schema["properties"]["topic"]
    assert topic["type"] == "string"
    assert topic["enum"] == list(LOOKUP_TOPICS)
    assert set(NEW_TOPICS) <= set(topic["enum"])
    assert not re.search(r'"(anyOf|oneOf|allOf)"', json.dumps(GET_CREATOR_KNOWLEDGE_SCHEMA))
    for t in NEW_TOPICS:
        assert f"{t}: " in GET_CREATOR_KNOWLEDGE_SCHEMA["description"]


FRAMING_RULE = (
    "- Framing first. For a shoot plan (Plan my shoot), look up the creator's framing topic"
    ' from the "More on request" list (its line names their category; shot_planning when none'
    " does), and take the shot size, where they stand, the headroom and the text position from it."
)


def test_the_persona_has_the_one_framing_rule_on_creator_turns_only():
    assert FRAMING_RULE in " ".join(MEERA_CREATOR_PERSONA.split())
    # Spec Phase 6: "for a shoot plan". A required lookup on every full script (4-7k characters a
    # turn) waits for the shot-card phase and its before/after eval.
    rule = " ".join(MEERA_CREATOR_PERSONA.split()).split("- Framing first.")[1].split(" - ")[0]
    assert "full script" not in rule
    creator = assemble_prompt(
        {
            "workspace_id": "creator-framing-001",
            "audience": "CREATOR",
            "creator": {"workspace_id": "creator-framing-001", "display_name": "Asha"},
            "conversation": [{"role": "user", "content": "plan my shoot"}],
        },
        session_id="s-framing",
    )
    brand = assemble_prompt(
        {"workspace_id": "ws-brand-framing", "audience": "BRAND", "brand": {}, "conversation": []},
        session_id="s-brand-framing",
    )
    creator_text = " ".join("\n".join(b["text"] for b in creator.system_blocks).split())
    brand_text = "\n".join(b.get("text", "") for b in brand.system_blocks)
    assert FRAMING_RULE in creator_text
    assert "Framing first." not in brand_text
