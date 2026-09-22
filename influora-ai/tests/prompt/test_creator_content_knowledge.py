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


def test_committed_knowledge_file_loads_every_row_by_type():
    rows = load_knowledge()
    assert len(rows) == 128  # 62 original + 26 go-live + 30 book-derived + 10 from dataset_2
    counts: dict[str, int] = {}
    for r in rows:
        counts[r["data_type"]] = counts.get(r["data_type"], 0) + 1
    assert counts == {
        "camera_angle": 28,
        "storytelling_structure": 11,
        "persuasion_principle": 7,
        "marketing_concept": 16,
        "hook_template": 16,  # 6 Hinglish + 6 English versions + 4 from dataset_2
        "narrative_principle": 18,
        "content_characteristic": 10,
        "platform_strategy": 4,
        "brand_deal_practice": 5,
        "category_playbook": 13,
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
    # Shared by every creator -> 1-hour TTL since 2026-09-22 (cost fix 1).
    assert block["cache_control"] == {"type": "ephemeral", "ttl": "1h"}
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
            "brand_deal_practice": "topic",
            "category_playbook": "category",
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


# --- go-live additions (2026-09-21): brand deals, category playbooks ----------


def _playbook(**extra) -> dict:
    row = {
        "data_type": "category_playbook",
        "category": "Test niche",
        "formats": ["Tutorial"],
        "hook_angle": "Name the pain.",
        "structure": "Before-After-Bridge (BAB)",
        "camera": ["Close-up"],
        "never_say": "No medical claims.",
        "confidence": "high",
        "source": "test",
    }
    row.update(extra)
    return row


def _refs() -> list[dict]:
    rows = load_knowledge()
    return [
        next(r for r in rows if r.get("framework") == "Before-After-Bridge (BAB)"),
        next(r for r in rows if r.get("name") == "Close-up"),
    ]


def test_playbook_must_name_a_structure_and_shots_that_exist(tmp_path):
    assert len(load_knowledge(_write(tmp_path, _refs() + [_playbook()]))) == 3
    with pytest.raises(KnowledgeFileError, match="unknown structure"):
        load_knowledge(_write(tmp_path, _refs() + [_playbook(structure="Hero's Journey")]))
    with pytest.raises(KnowledgeFileError, match="unknown camera shot"):
        load_knowledge(_write(tmp_path, _refs() + [_playbook(camera=["Drone shot"])]))
    with pytest.raises(KnowledgeFileError, match="'formats'"):
        load_knowledge(_write(tmp_path, _refs() + [_playbook(formats=[])]))


def test_every_number_slot_template_carries_the_number_rule(tmp_path):
    for t in (r["template"] for r in load_knowledge() if r["data_type"] == "hook_template"):
        if "[number" in t.lower():
            line = next(line for line in CREATOR_KNOWLEDGE_TEXT.splitlines() if t in line)
            assert "NUMBER RULE" in line
    # A NEW template with a [Number] slot gets the rule too, not only the two named ones.
    from app.prompt.content_knowledge import render_knowledge_block

    hook = {
        "data_type": "hook_template",
        "category": "social_proof",
        "template": "Did you know [statistic]% of creators switched?",
        "persuasion_principle": "social_proof",
        "goal_fit": "conversion",
        "confidence": "template",
        "source": "test",
    }
    text = render_knowledge_block(load_knowledge(_write(tmp_path, [hook])))
    assert "NUMBER RULE" in next(line for line in text.splitlines() if "creators switched" in line)


def test_playbooks_and_brand_deals_render_before_the_general_entries():
    text = CREATOR_KNOWLEDGE_TEXT
    assert text.index("Category playbooks") < text.index("Brand deals on Influora") < text.index("Storytelling structures:")
    for r in load_knowledge():
        if r["data_type"] == "category_playbook":
            assert f"- {r['category']}: " in text
            assert r["never_say"] in text


def test_brand_deal_entries_keep_the_payment_promise_and_never_say_escrow():
    deals = " ".join(
        _flat(r["guidance"] + " " + r["video_application"])
        for r in load_knowledge()
        if r["data_type"] == "brand_deal_practice"
    ).lower()
    assert "bank transfer" in deals
    assert "2 working days" in deals
    assert "3 working days" in deals
    assert "escrow" not in _flat(CREATOR_KNOWLEDGE_TEXT).lower()


def test_no_leftover_research_references_invented_numbers_or_competitor_names():
    text = _flat(CREATOR_KNOWLEDGE_TEXT).lower()
    for banned in ("earlier research", "1 million", "modash", "famekeeda", "confluencr", "metricool"):
        assert banned not in text, banned


def test_persona_states_the_playbook_and_brand_deal_rules():
    text = _flat(MEERA_CREATOR_PERSONA)
    assert "Use the category playbook." in text
    assert '"Never say" line is a hard rule' in text
    assert "ASCI asks for a clear, upfront label" in text
    assert "every other template with a [Number], [statistic] or [percent] slot" in text


# --- "ask first, only what's unknown" (Swapnil 2026-09-21) --------------------


def test_persona_states_the_ask_first_rule():
    text = _flat(MEERA_CREATOR_PERSONA)
    assert "Ask first, only what's unknown." in text
    # One goal question first, capped at three, one per message.
    assert "ask ONE short question first" in text
    assert "Ask at most three questions in total" in text
    assert "one per message" in text
    # Never re-ask what the context or conversation already holds.
    assert "Never ask for anything already in your context" in text
    assert "already answered in this conversation" in text
    # A specific request is served first; questions never block help.
    assert "never make them answer questions before they get help" in text


def test_ask_first_rule_sits_before_knowledge_first_and_reaches_only_creators():
    text = _flat(MEERA_CREATOR_PERSONA)
    assert text.index("Ask first, only what's unknown.") < text.index("Knowledge first.")
    creator = _flat("\n".join(b["text"] for b in _creator_prompt().system_blocks))
    brand = _flat("\n".join(b["text"] for b in _brand_prompt().system_blocks))
    assert "Ask first, only what's unknown." in creator
    assert "Ask first, only what's unknown." not in brand
