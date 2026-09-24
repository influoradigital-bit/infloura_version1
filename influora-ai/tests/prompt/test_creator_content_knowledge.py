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
    KnowledgeFileError,
    has_statistic_slot,
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


# Hand-pinned oracle, independent of the loader's regex: the three templates
# whose numeric slot invites an invented STATISTIC (v3 row 74 is the third).
NUMBER_STAT_HOOK_TEMPLATES: tuple[str, ...] = (
    "[Number] logo ne yeh try kiya — result dekho",
    "[Number]% log yeh galat karte hain — sahi tareeka yeh hai",
    "Kya aap bhi un [statistic]% logon mein ho jo [common belief] sach maante hain? Asli baat yeh hai.",
)
# Numbers that describe the creator's OWN content (the routine's length, how
# many tips the video covers). Coordinator ruling 2026-09-22: free to fill.
CREATOR_OWN_NUMBER_TEMPLATES: tuple[str, ...] = (
    "[Action], ghar pe/apne shehar mein, bina [barrier], sirf [duration] minute — chalo shuru",
    "Ye [number] galtiyan tumhara [outcome] kharab kar rahi hain",
)

NEW_STORR_PRINCIPLES: dict[str, str] = {
    "Cause-and-effect beats": "Ch. 1.8",
    "Want versus need": "Ch. 3.4",
    "Show, don't tell": "Ch. 1.3",
    "One meaningful detail": "Ch. 1.6",
    "Active hero with a goal": "Ch. 4.0",
}
ADAPTED_SOURCE = "influora_content_team (adapted pattern)"


# --- the committed file -----------------------------------------------------


def test_committed_knowledge_file_loads_every_row_by_type():
    rows = load_knowledge()
    # release/0922: the go-live file (128 = 62 original + 26 go-live + 30 book-derived + 10
    # from dataset_2) plus v4's 44 new rows (47 minus 3 written on both sides), plus v5's 38
    # camera rows from dataset_5 (2026-09-24; its other 104 rows were older copies of rows
    # already here and were not taken).
    assert len(rows) == 210
    counts: dict[str, int] = {}
    for r in rows:
        counts[r["data_type"]] = counts.get(r["data_type"], 0) + 1
    assert counts == {
        "camera_angle": 28,
        "storytelling_structure": 11,
        "persuasion_principle": 7,
        "marketing_concept": 19,
        "hook_template": 22,
        "narrative_principle": 27,
        "content_characteristic": 10,
        "platform_strategy": 5,
        "brand_deal_practice": 5,
        "category_playbook": 13,
        "contextual_action": 11,
        "length_guideline": 6,
        "structure_selection_rule": 8,
        "camera_technical_setting": 8,
        "night_video_setting": 4,
        "lighting_rule": 4,
        "background_rule": 2,
        "subject_positioning_rule": 2,
        "platform_export_setting": 2,
        "failure_case": 4,
        "permanent_rule": 6,
        "flicker_rule": 1,
        "phone_hardware": 5,
    }


def test_the_numeric_slot_templates_exist_verbatim_in_the_data():
    templates = {r["template"] for r in load_knowledge() if r["data_type"] == "hook_template"}
    for t in NUMBER_STAT_HOOK_TEMPLATES + CREATOR_OWN_NUMBER_TEMPLATES:
        assert t in templates, t


def test_the_three_statistic_templates_are_restricted():
    for t in NUMBER_STAT_HOOK_TEMPLATES:
        assert has_statistic_slot(t), t


def test_duration_and_tip_count_templates_are_not_restricted():
    for t in CREATOR_OWN_NUMBER_TEMPLATES:
        assert not has_statistic_slot(t), t


def test_statistic_detection_is_generic_not_a_list():
    templates = [r["template"] for r in load_knowledge() if r["data_type"] == "hook_template"]
    flagged = {t for t in templates if has_statistic_slot(t)}
    assert flagged == set(NUMBER_STAT_HOOK_TEMPLATES)
    # A future template nobody listed is caught by its slot shape alone.
    for new in (
        "Only [percentage] of creators know this",
        "[statistic] fact about sleep",
        "[people count] ne yeh follow kiya",
        "[figure]% galat hain",
        "[Number] people tried this",
        "[count] logon ne dekha",
    ):
        assert has_statistic_slot(new), new
    # The creator's own numbers, and slots with no number at all, stay free.
    for free in (
        "[Duration] mein result",
        "[count] reasons to start",
        "Ye [number] steps follow karo",
        "[Pain point] ka time ya resource nahi hai?",
        "Calling [group]",
    ):
        assert not has_statistic_slot(free), free


def test_no_tiktok_anywhere_in_the_knowledge_file_or_block():
    assert "tiktok" not in KNOWLEDGE_PATH.read_bytes().decode("utf-8").lower()
    assert "tiktok" not in CREATOR_KNOWLEDGE_TEXT.lower()
    platforms = [r["platform"] for r in load_knowledge() if r["data_type"] == "platform_strategy"]
    assert "Instagram Reels / YouTube Shorts (short-form algorithmic feeds)" in platforms


def test_status_and_outrage_row_carries_the_no_real_individuals_guard():
    name = "Status games and moral outrage as engagement drivers"
    row = next(r for r in load_knowledge() if r.get("principle") == name)
    app = row["video_application"]
    assert "Never name, shame or target a real individual or brand" in app
    assert "only at ideas, practices or common mistakes" in app
    assert "this person did X wrong" not in app
    line = next(ln for ln in CREATOR_KNOWLEDGE_TEXT.splitlines() if ln.startswith(f"- {name}:"))
    assert "IDEAS ONLY" in line


def test_five_new_storr_principles_are_present_by_name_with_chapter_credit():
    rows = {r["principle"]: r for r in load_knowledge() if r["data_type"] == "narrative_principle"}
    for name, chapter in NEW_STORR_PRINCIPLES.items():
        assert name in rows, name
        assert rows[name]["further_reading"] == (
            f"The Science of Storytelling by Will Storr (Abrams Press, 2019), {chapter}"
        )
        assert rows[name]["source"] == "influora_content_team"
        assert f"- {name}: " in CREATOR_KNOWLEDGE_TEXT


def test_the_six_adapted_pattern_hooks_carry_the_adapted_source_and_keep_the_original():
    rows = load_knowledge()
    # Found by source, not by file position: release/0922 appends v4's rows after the
    # go-live file, so the six rows that were 70..75 in v4 are 133..138 in the merge.
    adapted = [r for r in rows if r.get("source") == ADAPTED_SOURCE]
    assert len(adapted) == 6
    for r in adapted:
        assert r["data_type"] == "hook_template", r["template"]
        assert r["further_reading"].startswith("Pattern inspired by: "), r["template"]
    # The unconfirmed guides survive only as inspiration, never as a source,
    # and their original wording never reaches the prompt.
    for r in rows:
        assert "Viral Hook Ideas" not in r["source"] and "breezy_content" not in r["source"]
    assert "Did you know [statistic]%" not in CREATOR_KNOWLEDGE_TEXT
    text = KNOWLEDGE_PATH.read_bytes().decode("utf-8")
    assert "video_goal parameter" not in text
    assert "goal_fit field" in text


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
            "contextual_action": "category",
            "length_guideline": "goal",
            "structure_selection_rule": "situation",
            "camera_technical_setting": "situation",
            "night_video_setting": "environment",
            "lighting_rule": "scenario",
            "background_rule": "aspect",
            "subject_positioning_rule": "content_type",
            "platform_export_setting": "platform",
            "failure_case": "symptom",
            "permanent_rule": "rule",
            "flicker_rule": "region",
            "phone_hardware": "model",
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


def test_persona_states_the_no_invented_statistic_rule():
    text = _flat(MEERA_CREATOR_PERSONA)
    assert "No invented statistics in hooks." in text
    assert "Never invent a statistic or a claim about other people's results" in text
    assert (
        "every template the knowledge block marks STATISTIC RULE — may only be filled with the creator's own"
        " figure from your context or a number the creator gave you"
    ) in text
    assert "use a different template" in text
    # The creator's own numbers are explicitly free.
    assert (
        "Numbers that describe the creator's own content, such as how long the routine is or how many tips"
        " or steps the video covers, are fine to choose."
    ) in text
    # The .22.3 broad wording must not survive alongside the narrowed rule.
    assert "No invented numbers in hooks." not in text
    assert "Any hook template with a numeric slot" not in text


def test_persona_never_suggests_tiktok():
    text = _flat(MEERA_CREATOR_PERSONA)
    assert "Never suggest TikTok. It is banned in India." in text
    assert "suggest Instagram Reels or YouTube Shorts" in text
    # TikTok appears in the persona only inside that prohibition.
    assert text.lower().count("tiktok") == 1


def test_persona_states_the_no_real_individuals_guard():
    text = _flat(MEERA_CREATOR_PERSONA)
    assert "Outrage and status only about ideas." in text
    assert "Never name, shame or target a real individual or brand in an idea, hook or script." in text
    assert "only at ideas, practices or common mistakes" in text


def test_question_first_intake_still_present():
    text = _flat(MEERA_CREATOR_PERSONA)
    for phrase in (
        "Content idea intake.",
        "at most 3 short questions in ONE message",
        "Skip override.",
        "One round of questions only.",
        "Never ask what the context already holds.",
    ):
        assert phrase in text, phrase


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
        assert "STATISTIC RULE" in line
    for t in CREATOR_OWN_NUMBER_TEMPLATES:
        line = next(line for line in text.splitlines() if t in line)
        assert "STATISTIC RULE" not in line, t
    # Only the three statistic templates carry the marker.
    assert sum("STATISTIC RULE" in line for line in text.splitlines()) == 3
    assert "NUMBER RULE" not in text
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


def test_every_statistic_slot_template_carries_the_statistic_rule(tmp_path):
    # release/0922: the go-live NUMBER rule (every [Number] slot) was replaced by v4's
    # narrower STATISTIC rule (.22.4): only invented claims about the world are
    # restricted; the creator's own counts ("Ye [number] galtiyan") stay free.
    from app.prompt.content_knowledge import has_statistic_slot

    for t in (r["template"] for r in load_knowledge() if r["data_type"] == "hook_template"):
        line = next(line for line in CREATOR_KNOWLEDGE_TEXT.splitlines() if t in line)
        assert ("STATISTIC RULE" in line) == has_statistic_slot(t), t
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
    assert "STATISTIC RULE" in next(line for line in text.splitlines() if "creators switched" in line)


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
    assert "No invented statistics in hooks." in text


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
