"""Unit tests for app/prompt/creator_suggestion.py's F-0828 output-side backstop
instruction — pure string assertions on the prompt, no app / no provider.

wiki/decisions/2026-09-18-trend-headline-screening.md, "Also required in the
same build": "the suggestion prompt ... tells the model never to build an idea
on a death, crime, riot, disaster or court case, even if one gets through."
"""

from __future__ import annotations

from app.prompt.creator_suggestion import build_system_prompt


def test_system_prompt_instructs_model_never_to_build_on_unsafe_topics():
    prompt = build_system_prompt().lower()

    assert "death" in prompt
    assert "crime" in prompt
    assert "riot" in prompt
    assert "disaster" in prompt
    assert "court" in prompt or "legal" in prompt


def test_system_prompt_instruction_is_unconditional_even_if_trend_text_touches_one():
    # F-0828's wording is "even if the trend text touches one" — pins the
    # instruction as a genuine backstop (survives an ingest-screen miss), not
    # merely a restatement of "the input will already be safe".
    prompt = build_system_prompt().lower()

    assert "even if the trend text" in prompt


def test_system_prompt_still_has_its_pre_existing_rules():
    # Regression guard: the F-0828 addition must not have replaced the
    # existing pet-name / no-invented-facts rules rather than adding to them.
    prompt = build_system_prompt().lower()

    assert "darling" in prompt or "pet-name" in prompt or "pet-names" in prompt
    assert "invent no facts" in prompt
