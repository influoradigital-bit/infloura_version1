"""Camera knowledge v5 (dataset 5, 2026-09-24): the shooting rows, the phone notes, and
the creator's saved phone.

What this pins:
- the fact-checked phone rows say what the phones really have (no 8K on the Find X8
  Ultra, the Reno 14 Pro's LYT-808), and the report's rules no longer contradict the
  phone's controls or the 50Hz rule;
- `find_phone` only matches the full model name, so a creator on another phone never
  gets these phones' lenses;
- the saved phone reaches Block B (neutralized, with whether our notes cover it) and
  never the BRAND prompt;
- the frame check carries the shooting knowledge but not the phone table.
"""

from __future__ import annotations

import json

import pytest

from app.prompt.assembler import assemble_prompt
from app.prompt.content_knowledge import (
    CREATOR_KNOWLEDGE_ROWS,
    CREATOR_KNOWLEDGE_TEXT,
    PHONE_NOTES_HEADING,
    SHOOTING_HEADING,
    KnowledgeFileError,
    find_phone,
    load_knowledge,
    render_shooting_lines,
)
from app.prompt.creator_persona import MEERA_CREATOR_PERSONA
from app.prompt.frame_check import (
    FRAME_CHECK_SHOOTING_KNOWLEDGE,
    PHONE_UNKNOWN_TEXT,
    build_phone_text,
    build_system_prompt,
)


def _flat(text: str) -> str:
    return " ".join(text.split())


def _rows(data_type: str) -> list[dict]:
    return [r for r in CREATOR_KNOWLEDGE_ROWS if r["data_type"] == data_type]


# --- the rows ---------------------------------------------------------------


def test_phone_rows_carry_the_checked_specs():
    phones = {r["model"]: r for r in _rows("phone_hardware")}
    assert set(phones) == {"Find X8 Ultra", "Reno 14 Pro", "F25 Pro", "A78 5G", "Find N3 Flip"}
    x8 = phones["Find X8 Ultra"]
    assert "no 8K" in x8["max_resolution"]
    assert "8K30" not in json.dumps(x8)
    assert "not available at review" in x8["log_hdr"]
    assert "LYT-808" in phones["Reno 14 Pro"]["sensor"]
    assert "IMX890" not in phones["Reno 14 Pro"]["sensor"]
    a78 = phones["A78 5G"]
    assert a78["max_fps"] == "1080p30"
    assert a78["telephoto"] == "None" and a78["ultrawide"] == "None"
    assert a78["manual_video"].startswith("No")


def test_report_rows_are_medium_confidence_except_physics_and_checked_specs():
    for r in CREATOR_KNOWLEDGE_ROWS:
        if r["data_type"] in ("flicker_rule", "phone_hardware"):
            assert r["confidence"] == "high", r
        elif r.get("further_reading", "").startswith("Smartphone cinematography research report"):
            assert r["confidence"] == "medium", r


def test_no_absolute_rule_contradicts_an_auto_only_phone():
    text = _flat(CREATOR_KNOWLEDGE_TEXT)
    assert "Never use Auto White Balance" not in text
    # One zoom rule, stated against the phone's own optical lenses, not two numbers.
    assert "beyond 2x-5x" not in text
    assert "never zoom digitally past them" in text
    # The 50Hz fix names the shutter speeds that match the pulse.
    flicker = _rows("flicker_rule")[0]
    assert "1/50" in flicker["fix"] and "1/100" in flicker["fix"]


def test_no_indoor_setting_recommends_a_banding_shutter():
    """1/120 and 1/60 band under India's 50Hz lights. Where a row allows 1/120 it must say
    it is for daylight outdoors, unless the situation itself is an outdoor daylight one,
    and a row that can run under mains light must give the 50Hz-safe alternative."""
    for r in _rows("camera_technical_setting") + _rows("night_video_setting"):
        shutter = r["shutter"]
        assert "1/60" not in shutter, r
        if "1/120" in shutter and "outdoor" not in r.get("situation", "").lower():
            assert "outdoors" in shutter, r
            assert "1/100" in shutter, r


def test_loader_rejects_a_phone_row_missing_a_spec(tmp_path):
    row = dict(_rows("phone_hardware")[0])
    del row["log_hdr"]
    path = tmp_path / "k.jsonl"
    path.write_text(json.dumps(row) + "\n", encoding="utf-8")
    with pytest.raises(KnowledgeFileError, match="log_hdr"):
        load_knowledge(path)


# --- phone matching ---------------------------------------------------------


@pytest.mark.parametrize(
    ("typed", "model"),
    [
        ("OPPO Reno 14 Pro 5G", "Reno 14 Pro"),
        ("reno14 pro", "Reno 14 Pro"),
        ("Oppo find x8 ultra", "Find X8 Ultra"),
        ("A78", "A78 5G"),
        ("OPPO F25 Pro", "F25 Pro"),
        ("Find N3 Flip", "Find N3 Flip"),
    ],
)
def test_find_phone_matches_the_full_model(typed, model):
    row = find_phone(CREATOR_KNOWLEDGE_ROWS, typed)
    assert row is not None and row["model"] == model


@pytest.mark.parametrize(
    "typed", ["Reno 14", "Find X8", "iPhone 15", "Redmi Note 13 Pro", "Samsung S24", "", "   ", None]
)
def test_find_phone_never_guesses_another_model(typed):
    assert find_phone(CREATOR_KNOWLEDGE_ROWS, typed) is None


# --- the creator knowledge block and persona ---------------------------------


def test_knowledge_block_renders_the_shooting_section_and_phone_notes():
    text = CREATOR_KNOWLEDGE_TEXT
    assert "Shooting and camera settings" in text
    assert PHONE_NOTES_HEADING in text
    assert text.index("Shooting and camera settings") < text.index(PHONE_NOTES_HEADING)
    for model in ("Find X8 Ultra", "Reno 14 Pro", "A78 5G"):
        assert f"OPPO {model}:" in text


def test_persona_states_the_phone_rule():
    text = _flat(MEERA_CREATOR_PERSONA)
    assert "Phone they film on" in text
    assert "ask once which phone they film on" in text
    assert "My phone" in text
    assert "never say a phone has a feature the notes don't list" in text


def _creator_blocks(**extra) -> str:
    creator = {
        "workspace_id": "creator-cam-001",
        "display_name": "Asha Rao",
        "first_name": "Asha",
        "city": "Mumbai",
        "tier": "MICRO",
        "categories": ["beauty"],
        "creator_language": "en-IN",
        "brand_tone": "FRIENDLY",
        "consent_accepted": True,
        **extra,
    }
    prompt = assemble_prompt(
        {
            "workspace_id": "creator-cam-001",
            "audience": "CREATOR",
            "creator": creator,
            "conversation": [{"role": "user", "content": "what camera settings for my reel?"}],
        },
        session_id="s-cam",
    )
    return "\n".join(b["text"] for b in prompt.system_blocks)


def test_block_b_names_a_saved_phone_in_our_notes():
    text = _creator_blocks(phone_model="oppo reno 14 pro")
    assert "- Phone they film on (saved by them): oppo reno 14 pro (in the Phone notes as OPPO Reno 14 Pro)" in text


def test_block_b_flags_a_saved_phone_we_have_no_notes_for_and_neutralizes_it():
    text = _creator_blocks(phone_model="Galaxy <b>S24</b>")
    line = next(ln for ln in text.splitlines() if ln.startswith("- Phone they film on"))
    assert "not in the Phone notes" in line
    assert "<b>" not in line


def test_block_b_says_not_saved_when_there_is_no_phone():
    assert "- Phone they film on: not saved" in _creator_blocks()


def test_brand_prompt_never_carries_a_phone_model():
    prompt = assemble_prompt(
        {
            "workspace_id": "ws-brand-cam",
            "audience": "BRAND",
            "brand": {"phone_model": "Zebra Phone 77"},
            "conversation": [],
        },
        session_id="s-cam-brand",
    )
    joined = "\n".join(b["text"] for b in prompt.system_blocks)
    assert "Zebra Phone 77" not in joined
    assert "Phone they film on" not in joined


# --- the frame check ----------------------------------------------------------


def test_frame_check_carries_the_shooting_knowledge_but_not_the_phone_table():
    system = build_system_prompt()
    assert FRAME_CHECK_SHOOTING_KNOWLEDGE in system
    assert "India 50Hz lights" in system
    assert PHONE_NOTES_HEADING not in system
    assert "LYT-808" not in system
    assert "Settings must fit the creator's phone" in system


def test_shooting_heading_is_short_and_never_points_at_phone_notes_the_frame_check_lacks():
    # The frame check renders the shooting section without the Phone notes, so nothing in it
    # may send the model looking for them.
    assert SHOOTING_HEADING == "Shooting and camera settings (starting points, not laws):"
    assert FRAME_CHECK_SHOOTING_KNOWLEDGE.count(SHOOTING_HEADING) == 1
    assert "Phone notes" not in FRAME_CHECK_SHOOTING_KNOWLEDGE
    # The heading is short because the two standing rules right under it carry what it used to say.
    rules = [r["rule"] for r in CREATOR_KNOWLEDGE_ROWS if r["data_type"] == "permanent_rule"]
    assert any(
        r.startswith("Only suggest what the creator's phone can actually do.")
        and "tap-to-focus, exposure lock, the brightness slider and moving the phone or the light" in r
        for r in rules
    )
    assert any(r.startswith("Give every camera setting with its one-line reason") for r in rules)
    lines = render_shooting_lines(CREATOR_KNOWLEDGE_ROWS, with_phone_notes=False)
    assert lines[:2] == [SHOOTING_HEADING, "Standing rules:"]


def test_frame_check_phone_text_per_case():
    assert build_phone_text(None) == PHONE_UNKNOWN_TEXT
    assert build_phone_text("  ") == PHONE_UNKNOWN_TEXT
    known = build_phone_text("OPPO A78 5G")
    assert "our notes" in known and "1080p30" in known
    unknown = build_phone_text("Pixel 8 <script>")
    assert "<untrusted_phone_model>" in unknown and "<script>" not in unknown
