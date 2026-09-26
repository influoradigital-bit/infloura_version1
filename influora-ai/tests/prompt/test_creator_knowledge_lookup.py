"""The creator knowledge lookup (PROMPT_VERSION .13, 2026-09-24): prompt side.

`get_creator_knowledge` is a LOCAL creator tool: influora-ai's tool loop runs it in-process
(tests/tools/test_creator_knowledge_tool.py pins that half), it is never forwarded to Spring and
it never joins `CREATOR_TOOL_NAMES`, the Spring-backed list Java and the frontend sync against.

What this pins:
- the tool is offered on EVERY creator turn (warn-only `tools_enabled=[]` and a full grant) and
  NEVER on a brand turn;
- its schema: one `topic` string whose enum is exactly `list(LOOKUP_TOPICS)`, required, no extra
  properties, and no anyOf/oneOf/allOf anywhere (Anthropic 400s the whole tools payload on one);
- the always-sent block ends with a "More on request" section naming every topic and telling
  the model to call the tool first, and no longer carries the delivery examples (ex01-ex12) or
  any audio / movement row;
- each topic's LOOKUP_TEXT is non-empty, plain (no "**") and TikTok-free;
- the persona's "Look it up first" rule is present;
- the frame-check prompt did not grow (the lookup is a chat-only surface).
"""

from __future__ import annotations

import re
from typing import Any

import pytest

from app.prompt.assembler import assemble_prompt
from app.prompt.content_knowledge import (
    CREATOR_KNOWLEDGE_ROWS,
    CREATOR_KNOWLEDGE_TEXT,
    LOOKUP_TEXT,
    LOOKUP_TOPICS,
    MORE_ON_REQUEST_HEADING,
    NAME_FIELD,
    render_lookup_section,
)
from app.prompt.creator_persona import MEERA_CREATOR_PERSONA
from app.prompt.frame_check import build_system_prompt
from app.tools.creator_schemas import (
    CREATOR_LOCAL_TOOL_NAMES,
    CREATOR_TOOL_NAMES,
    GET_CREATOR_KNOWLEDGE,
    GET_CREATOR_KNOWLEDGE_SCHEMA,
    creator_local_tool_schemas,
)
from app.tools.schemas import get_tool_schemas, is_known_tool, is_local_tool

TOPICS = [
    "audio",
    "moving_between_spots",
    "delivery_examples",
    # dataset 9 (2026-09-26, shoot guide spec v2 Phase 6): shot planning, then one framing topic
    # per composition category (FRAMING_TOPICS order).
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
    # explainer Reel formats (2026-09-26): one topic for both reel types.
    "reel_formats",
]

# The v8 audio / movement rows: lookup only, never in the always-sent block.
V8_TYPES: tuple[str, ...] = (
    "microphone_selection_rule",
    "mic_distance_rule",
    "lav_placement_rule",
    "audio_noise_rule",
    "audio_diagnostic_rule",
    "phone_audio_capability",
    "audio_movement_scenario",
    "movement_continuity_rule",
    "walking_configuration",
)

# Frame-check system prompt size: 35,313 at v7 (measured on the lookup branch's base,
# 2026-09-24); 37,472 since the grounded photo check of 2026-09-25 added the step/ask response
# shape and the coach question ids; 38,529 since the picks-only photo check (.25.2) spelled out
# the scene values and the ok / cant_tell ids; 38,643 since the review fixes named the coach
# notes that are never a step and asked for a usable value only. The lookup tool is chat-only:
# nothing it moved or added may reach the frame check. +361 since the shoot guide spec v2
# (2026-09-26) rewrote the Instagram Reels / YouTube Shorts export row's safe zones to the
# safe-zone config in words: the frame check renders the export rows. Dataset 9's framing rows
# are lookup only and add nothing here. +336 since the same spec's Phase 4 asks the photo
# check for layout boxes: 126 chars of reply shape plus the 210-char layout rule. +185 since
# owner decision D (2026-09-26) changed the coach bank the photo check may ask from: the
# product_side follow-up row's line, and can_move / prop_ready's new question and "Decides" words.
FRAME_CHECK_CHARS_AT_COACH_BANK = 38_643 + 361 + 336 + 185

_COMBINATORS = ("anyOf", "oneOf", "allOf")


def _creator_prompt(tools_enabled: list[str] | None):
    creator: dict[str, Any] = {"workspace_id": "creator-lookup-001", "display_name": "Asha"}
    if tools_enabled is not None:
        creator["tools_enabled"] = tools_enabled
    return assemble_prompt(
        {
            "workspace_id": "creator-lookup-001",
            "audience": "CREATOR",
            "creator": creator,
            "conversation": [{"role": "user", "content": "which mic should I use?"}],
        },
        session_id="s-lookup",
    )


def _names(tools: list[dict[str, Any]]) -> list[str]:
    return [t["name"] for t in tools]


# --- offered on every creator turn, never on a brand turn -------------------------------


def test_contract_constants():
    assert GET_CREATOR_KNOWLEDGE == "get_creator_knowledge"
    assert list(CREATOR_LOCAL_TOOL_NAMES) == [GET_CREATOR_KNOWLEDGE]
    # Never in the Spring-backed list Java and the frontend sync against.
    assert GET_CREATOR_KNOWLEDGE not in CREATOR_TOOL_NAMES
    assert list(LOOKUP_TOPICS) == TOPICS
    assert is_known_tool(GET_CREATOR_KNOWLEDGE)
    assert is_local_tool(GET_CREATOR_KNOWLEDGE)


@pytest.mark.parametrize("tools_enabled", [None, []], ids=["absent", "empty"])
def test_offered_on_a_warn_only_creator_turn(tools_enabled):
    prompt = _creator_prompt(tools_enabled)
    assert prompt.audience == "CREATOR"
    assert _names(prompt.tools) == [GET_CREATOR_KNOWLEDGE]
    block_a = prompt.system_blocks[0]["text"]
    # Still warn-only: nothing that reads or acts on the account.
    assert "Available tools: get_creator_knowledge (warn-only mode: no account tools)" in block_a
    assert "No account tools on this turn" in block_a
    assert "- get_creator_knowledge: read Influora's own notes" in block_a


def test_offered_on_a_fully_granted_creator_turn_once_and_last():
    prompt = _creator_prompt(list(CREATOR_TOOL_NAMES))
    assert _names(prompt.tools) == [*CREATOR_TOOL_NAMES, GET_CREATOR_KNOWLEDGE]


def test_a_tools_enabled_that_names_it_does_not_duplicate_it():
    prompt = _creator_prompt(["get_my_deals", GET_CREATOR_KNOWLEDGE])
    assert _names(prompt.tools) == ["get_my_deals", GET_CREATOR_KNOWLEDGE]


def test_never_offered_on_a_brand_turn():
    prompt = assemble_prompt(
        {"workspace_id": "ws-brand-lookup", "audience": "BRAND", "brand": {}, "conversation": []},
        session_id="s-brand",
    )
    assert prompt.audience == "BRAND"
    assert GET_CREATOR_KNOWLEDGE not in _names(prompt.tools)
    assert GET_CREATOR_KNOWLEDGE not in _names(get_tool_schemas())
    system_text = "\n".join(b.get("text", "") for b in prompt.system_blocks)
    assert GET_CREATOR_KNOWLEDGE not in system_text
    assert MORE_ON_REQUEST_HEADING not in system_text


# --- the schema ---------------------------------------------------------------------------


def _walk(node: Any):
    if isinstance(node, dict):
        yield node
        for value in node.values():
            yield from _walk(value)
    elif isinstance(node, list):
        for value in node:
            yield from _walk(value)


def test_schema_topic_enum_is_the_lookup_topics_and_has_no_combinators():
    assert creator_local_tool_schemas() == [GET_CREATOR_KNOWLEDGE_SCHEMA]
    schema = GET_CREATOR_KNOWLEDGE_SCHEMA
    assert schema["name"] == GET_CREATOR_KNOWLEDGE
    assert schema["description"].strip()
    input_schema = schema["input_schema"]
    assert input_schema["type"] == "object"
    assert input_schema["required"] == ["topic"]
    assert input_schema["additionalProperties"] is False
    assert list(input_schema["properties"]) == ["topic"]
    topic = input_schema["properties"]["topic"]
    assert topic["type"] == "string"
    assert topic["enum"] == list(LOOKUP_TOPICS)
    for node in _walk(schema):
        for kw in _COMBINATORS:
            assert kw not in node, kw


def test_the_offered_schema_is_the_same_object_shape_the_assembler_sends():
    offered = [t for t in _creator_prompt([]).tools if t["name"] == GET_CREATOR_KNOWLEDGE]
    assert offered == [GET_CREATOR_KNOWLEDGE_SCHEMA]


# --- the always-sent block ------------------------------------------------------------------


def test_always_sent_block_ends_with_more_on_request_naming_every_topic():
    text = CREATOR_KNOWLEDGE_TEXT
    at = text.index(MORE_ON_REQUEST_HEADING)
    section = text[at:]
    assert "call the get_creator_knowledge tool with that topic" in MORE_ON_REQUEST_HEADING
    for topic, description in LOOKUP_TOPICS.items():
        assert f"- {topic}: {description}" in section, topic
    # It is the LAST section: nothing but the topic lines follows the heading.
    assert section.rstrip("\n").splitlines() == [MORE_ON_REQUEST_HEADING] + [
        f"- {t}: {d}" for t, d in LOOKUP_TOPICS.items()
    ]


def test_always_sent_block_has_no_delivery_examples_and_no_audio_or_movement_rows():
    text = CREATOR_KNOWLEDGE_TEXT
    assert not re.search(r"\bex(0[1-9]|1[0-2])\b", text)
    assert "Delivery examples (synthetic" not in text
    lookup_only = [r for r in CREATOR_KNOWLEDGE_ROWS if r["data_type"] in V8_TYPES + ("delivery_example",)]
    assert len(lookup_only) == 54, "the lookup-only rows vanished -- this check would pass vacuously"
    for r in lookup_only:
        # Whole-token match: the mic distance "5 m" is not a leak inside "15 minutes".
        name = str(r[NAME_FIELD[r["data_type"]]])
        assert re.search(rf"(?<![\w.]){re.escape(name)}(?!\w)", text) is None, r
    for heading in ("Audio: getting a clean voice", "Which mic:", "Walking setups:", "Moving between two spots in one reel ("):
        assert heading not in text, heading


# --- LOOKUP_TEXT ------------------------------------------------------------------------------


@pytest.mark.parametrize("topic", TOPICS)
def test_each_lookup_text_is_non_empty_plain_and_tiktok_free(topic: str):
    text = LOOKUP_TEXT[topic]
    assert render_lookup_section(topic) == text
    assert len(text.strip().splitlines()) > 3
    assert "**" not in text
    assert "tiktok" not in text.lower()


def test_lookup_text_has_exactly_the_topics_and_unknown_is_none():
    assert list(LOOKUP_TEXT) == TOPICS
    assert render_lookup_section("lighting") is None
    assert render_lookup_section("") is None
    assert render_lookup_section(None) is None  # type: ignore[arg-type]


def test_each_topic_carries_its_rows():
    assert LOOKUP_TEXT["delivery_examples"].count("\n- ex") == 12
    assert "Which mic:" in LOOKUP_TEXT["audio"]
    assert "10-second tests" in LOOKUP_TEXT["audio"]
    assert "Walking setups:" in LOOKUP_TEXT["moving_between_spots"]
    assert "Safety:" in LOOKUP_TEXT["moving_between_spots"]


# --- persona and frame check ------------------------------------------------------------------


def test_persona_has_the_look_it_up_first_rule():
    text = " ".join(MEERA_CREATOR_PERSONA.split())
    assert "Look it up first." in text
    assert "call get_creator_knowledge with that topic before answering" in text
    assert "Never guess those from general knowledge." in text


def test_frame_check_prompt_did_not_grow():
    system = build_system_prompt()
    assert len(system) <= FRAME_CHECK_CHARS_AT_COACH_BANK
    assert MORE_ON_REQUEST_HEADING not in system
    assert GET_CREATOR_KNOWLEDGE not in system
    for text in LOOKUP_TEXT.values():
        assert text.splitlines()[0] not in system
