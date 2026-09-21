"""Creator content knowledge (Swapnil 2026-09-21): Meera answers content and
growth questions from Influora's own knowledge first.

These tests mock nothing but also call no model: they prove the PROMPT is
built right (block present, cached, creator-only, rules stated), not that
Meera answers well.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from app.prompt.assembler import assemble_prompt
from app.prompt.content_knowledge import (
    CREATOR_KNOWLEDGE_TEXT,
    KNOWLEDGE_BLOCK_HEADING,
    KNOWLEDGE_PATH,
    NUMBER_STAT_HOOK_TEMPLATES,
    KnowledgeFileError,
    load_knowledge,
)
from app.prompt.creator_persona import MEERA_CREATOR_PERSONA


def _creator_ctx(**extra) -> dict:
    base = {
        "workspace_id": "creator-fit-001",
        "display_name": "Rohit Verma",
        "first_name": "Rohit",
        "city": "Pune",
        "tier": "MICRO",
        "categories": ["fitness", "lifestyle"],
        "creator_language": "hi-IN",
        "brand_tone": "FRIENDLY",
    }
    base.update(extra)
    return base


def _creator_prompt():
    return assemble_prompt(
        {
            "workspace_id": "creator-fit-001",
            "audience": "CREATOR",
            "creator": _creator_ctx(),
            "conversation": [{"role": "user", "content": "how to grow my channel?"}],
        },
        session_id="s-know",
    )


def _brand_prompt():
    return assemble_prompt(
        {"workspace_id": "ws-1", "audience": "BRAND", "brand": {}, "conversation": []},
        session_id="s-know",
    )


def _flat(text: str) -> str:
    return " ".join(text.split())


# --- the committed file -----------------------------------------------------


def test_committed_knowledge_file_loads_all_62_rows_by_type():
    rows = load_knowledge()
    assert len(rows) == 62
    counts: dict[str, int] = {}
    for r in rows:
        counts[r["data_type"]] = counts.get(r["data_type"], 0) + 1
    assert counts == {
        "camera_angle": 18,
        "storytelling_structure": 10,
        "persuasion_principle": 7,
        "marketing_concept": 7,
        "hook_template": 6,
        "narrative_principle": 6,
        "content_characteristic": 6,
        "platform_strategy": 2,
    }


def test_the_two_number_hook_templates_exist_verbatim_in_the_data():
    templates = {r["template"] for r in load_knowledge() if r["data_type"] == "hook_template"}
    for t in NUMBER_STAT_HOOK_TEMPLATES:
        assert t in templates, t


def test_platform_strategy_rows_are_medium_confidence():
    rows = [r for r in load_knowledge() if r["data_type"] == "platform_strategy"]
    assert rows and all(r["confidence"] == "medium" for r in rows)


# --- the loader rejects malformed files ---------------------------------------


def _write(tmp_path: Path, rows: list) -> Path:
    p = tmp_path / "k.jsonl"
    p.write_bytes(
        b"".join(
            (r if isinstance(r, str) else json.dumps(r, ensure_ascii=False)).encode("utf-8") + b"\n"
            for r in rows
        )
    )
    return p


def _good_row() -> dict:
    return json.loads(KNOWLEDGE_PATH.read_bytes().decode("utf-8").splitlines()[0])


def test_loader_accepts_a_good_row(tmp_path):
    assert len(load_knowledge(_write(tmp_path, [_good_row()]))) == 1


@pytest.mark.parametrize(
    "mutate, message",
    [
        (lambda r: r.pop("purpose"), "missing required field 'purpose'"),
        (lambda r: r.update(data_type="dance_move"), "unknown data_type 'dance_move'"),
        (lambda r: r.update(name="   "), "missing required field 'name'"),
        (lambda r: r.update(confidence="certain"), "unknown confidence 'certain'"),
        (lambda r: r.pop("source"), "missing required field 'source'"),
    ],
)
def test_loader_rejects_a_malformed_row(tmp_path, mutate, message):
    bad = _good_row()
    mutate(bad)
    with pytest.raises(KnowledgeFileError, match=message):
        load_knowledge(_write(tmp_path, [_good_row() | {"name": "Other"}, bad]))


def test_loader_rejects_bad_json_empty_file_and_bad_steps(tmp_path):
    with pytest.raises(KnowledgeFileError, match="invalid JSON"):
        load_knowledge(_write(tmp_path, ["{not json"]))
    with pytest.raises(KnowledgeFileError, match="no rows"):
        load_knowledge(_write(tmp_path, []))
    story = next(r for r in load_knowledge() if r["data_type"] == "storytelling_structure")
    with pytest.raises(KnowledgeFileError, match="'steps'"):
        load_knowledge(_write(tmp_path, [story | {"steps": []}]))
    with pytest.raises(KnowledgeFileError, match="duplicate"):
        load_knowledge(_write(tmp_path, [_good_row(), _good_row()]))


# --- the block is in the creator prompt, cached, and NOT in the brand prompt ---


def test_creator_system_prompt_contains_the_cached_knowledge_block():
    blocks = _creator_prompt().system_blocks
    knowledge = [b for b in blocks if KNOWLEDGE_BLOCK_HEADING in b["text"]]
    assert len(knowledge) == 1
    block = knowledge[0]
    assert block["text"] == CREATOR_KNOWLEDGE_TEXT
    assert block["cache_control"] == {"type": "ephemeral"}
    # Named entries the persona tells Meera to say back.
    for name in ("Before-After-Bridge (BAB)", "Static / locked-off shot", "Problem-Agitate-Solve (PAS)"):
        assert name in block["text"]
    # Tenant-agnostic: no creator data inside the globally cached block.
    assert "Rohit" not in block["text"]
    assert "creator-fit-001" not in block["text"]
    # Anthropic allows at most 4 cache breakpoints; tools carry none here.
    assert sum(1 for b in blocks if "cache_control" in b) <= 4


def test_every_row_reaches_the_knowledge_text():
    for r in load_knowledge():
        name = {
            "camera_angle": "name",
            "storytelling_structure": "framework",
            "persuasion_principle": "principle",
            "marketing_concept": "concept",
            "hook_template": "template",
            "narrative_principle": "principle",
            "content_characteristic": "characteristic",
            "platform_strategy": "platform",
        }[r["data_type"]]
        assert r[name] in CREATOR_KNOWLEDGE_TEXT, r[name]


def test_brand_system_prompt_does_not_contain_the_knowledge_block():
    prompt = _brand_prompt()
    assert len(prompt.system_blocks) == 2
    joined = "\n".join(b["text"] for b in prompt.system_blocks)
    assert KNOWLEDGE_BLOCK_HEADING not in joined
    assert "Before-After-Bridge" not in joined
    assert "Static / locked-off shot" not in joined


# --- the persona states every rule --------------------------------------------


def test_persona_states_knowledge_first_name_entry_name_category_ask_script():
    text = _flat(MEERA_CREATOR_PERSONA)
    assert "Knowledge first." in text
    assert 'Answer from the "Influora content knowledge" block before general knowledge' in text
    assert "Name the category first." in text
    assert "name each entry you use exactly as the knowledge names it" in text
    assert "ask them to paste their last video script as text" in text
    assert "fall back to general knowledge, and say so plainly" in text


def test_persona_states_the_no_invented_number_rule_naming_both_templates():
    text = _flat(MEERA_CREATOR_PERSONA)
    assert "No invented numbers in hooks." in text
    for t in NUMBER_STAT_HOOK_TEMPLATES:
        assert _flat(t) in text, t
    assert "creator's own figure from your context or a number the creator gave you" in text
    assert "use a different template" in text


def test_persona_states_the_no_urgency_rule():
    text = _flat(MEERA_CREATOR_PERSONA)
    assert "No urgency wording." in text
    assert "Scarcity and Commitment & consistency entries shape the STRUCTURE of a video only" in text
    for phrase in ('"Act now"', '"Limited time"', '"Don\'t miss"', '"sirf aaj"'):
        assert phrase in text, phrase


def test_persona_states_platform_rows_are_background():
    text = _flat(MEERA_CREATOR_PERSONA)
    assert "Platform background entries are confidence medium and dated" in text
    assert "never as rules" in text


def test_knowledge_block_marks_the_risky_entries_inline():
    text = CREATOR_KNOWLEDGE_TEXT
    for t in NUMBER_STAT_HOOK_TEMPLATES:
        line = next(line for line in text.splitlines() if t in line)
        assert "NUMBER RULE" in line
    for principle in ("Scarcity", "Commitment & consistency"):
        line = next(line for line in text.splitlines() if line.startswith(f"- {principle}:"))
        assert "STRUCTURE ONLY" in line
    assert "background only, never a rule" in text


# --- real assembly: the creator's category reaches the prompt -----------------


def test_real_assembly_carries_fitness_category_alongside_knowledge_and_rules():
    prompt = _creator_prompt()
    joined = "\n".join(b["text"] for b in prompt.system_blocks)
    assert prompt.audience == "CREATOR"
    assert "- Categories: fitness, lifestyle" in prompt.system_blocks[-1]["text"]  # per-creator Block B
    assert KNOWLEDGE_BLOCK_HEADING in joined
    assert "Name the category first." in _flat(joined)
    # Knowledge block sits before the per-creator block (stable prefix first).
    idx = [i for i, b in enumerate(prompt.system_blocks) if KNOWLEDGE_BLOCK_HEADING in b["text"]][0]
    assert idx < len(prompt.system_blocks) - 1
