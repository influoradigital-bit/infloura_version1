"""get_my_audience (owner decision F, Swapnil 2026-09-26): Meera's read-only view of the creator's
OWN audience -- followers, and the people who engaged this month -- registered in every list a
creator tool lives in on this side, held to the Spring DTO's exact shape in the model's copy.

Seams tested against the OTHER side's source, not a hand-built fixture: the Java enum
(`CreatorToolName`) must name every Python creator tool, and the Java records
(`CreatorToolDtos.AudienceSection` / `GetMyAudienceResult` and its share records) must carry
exactly the keys the loop's allow-list trusts.
"""

from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any

import pytest

from app.prompt.assembler import assemble_prompt
from app.prompt.creator_persona import CREATOR_CAPABILITY_LINES, render_creator_capabilities
from app.tools.creator_schemas import (
    CREATOR_IDEMPOTENT_REQUIRED_TOOLS,
    CREATOR_NO_RETRY_TOOLS,
    CREATOR_TOOL_NAMES,
    CREATOR_TOOL_SCHEMAS,
    CREATOR_TOOL_TO_SPRING_PATH,
    GET_MY_AUDIENCE,
    is_creator_tool,
)
from app.tools.loop import (
    _AUDIENCE_SECTION_LISTS,
    _AUDIENCE_SECTION_SCALARS,
    _TRUSTED_KEYS_GET_MY_AUDIENCE,
    _model_copy_of_tool_result,
)
from app.tools.schemas import TOOL_NAMES, get_tool_schemas, is_known_tool

REPO = Path(__file__).resolve().parents[3]
JAVA = REPO / "influora-api" / "src" / "main" / "java" / "com" / "influora"
JAVA_ENUM = JAVA / "domain" / "enums" / "CreatorToolName.java"
JAVA_DTOS = JAVA / "web" / "dto" / "meera" / "CreatorToolDtos.java"
CANARY = "CANARY-audience-91c2"


def _section(available: bool = True) -> dict[str, Any]:
    if not available:
        return {"available": False, "reason": "fewer than 100 engagements this month", "as_of": None,
                "age": [], "gender": [], "top_cities": [], "top_countries": []}
    return {
        "available": True, "reason": None, "as_of": "2026-09-20",
        "age": [{"band": "18-24", "pct": 61}, {"band": "25-34", "pct": 27}],
        "gender": [{"label": "women", "pct": 64}, {"label": "men", "pct": 33}, {"label": "unknown", "pct": 3}],
        "top_cities": ["Mumbai, Maharashtra", "Pune, Maharashtra"],
        "top_countries": [{"code": "IN", "pct": 92}],
    }


def _payload() -> dict[str, Any]:
    return {"followers": _section(), "engaged_this_month": _section(available=False)}


def _schema() -> dict[str, Any]:
    return next(s for s in CREATOR_TOOL_SCHEMAS if s["name"] == GET_MY_AUDIENCE)


# --- registered in every list ------------------------------------------------------------------


def test_the_tool_is_registered_in_every_creator_list():
    assert GET_MY_AUDIENCE == "get_my_audience"
    assert GET_MY_AUDIENCE in CREATOR_TOOL_NAMES
    assert CREATOR_TOOL_TO_SPRING_PATH[GET_MY_AUDIENCE] == "/internal/meera/creator/get_my_audience"
    assert is_creator_tool(GET_MY_AUDIENCE) and is_known_tool(GET_MY_AUDIENCE)
    assert GET_MY_AUDIENCE in CREATOR_CAPABILITY_LINES
    # A plain read: retried like get_my_metrics, never an idempotency key.
    assert GET_MY_AUDIENCE not in CREATOR_NO_RETRY_TOOLS + CREATOR_IDEMPOTENT_REQUIRED_TOOLS


def test_never_a_brand_tool():
    assert GET_MY_AUDIENCE not in TOOL_NAMES
    assert GET_MY_AUDIENCE not in {s["name"] for s in get_tool_schemas()}
    brand = assemble_prompt({"workspace_id": "b-1", "conversation": [{"role": "user", "content": "hi"}]}, session_id="s")
    assert GET_MY_AUDIENCE not in {t["name"] for t in brand.tools}
    assert GET_MY_AUDIENCE not in "\n".join(b["text"] for b in brand.system_blocks)


def test_a_creator_turn_offers_it_only_when_spring_grants_it():
    def tools(enabled: list[str]) -> set[str]:
        prompt = assemble_prompt(
            {
                "workspace_id": "c-1", "audience": "CREATOR",
                "creator": {"workspace_id": "c-1", "first_name": "Asha", "tools_enabled": enabled},
                "conversation": [{"role": "user", "content": "who watches me?"}],
            },
            session_id="s",
        )
        return {t["name"] for t in prompt.tools}

    assert GET_MY_AUDIENCE in tools(["get_my_metrics", GET_MY_AUDIENCE])
    assert GET_MY_AUDIENCE not in tools(["get_my_metrics"])


def test_the_schema_is_a_no_argument_read_without_combinators():
    schema = _schema()
    assert schema["input_schema"] == {"type": "object", "properties": {}, "required": []}
    text = json.dumps(schema)
    for banned in ("anyOf", "oneOf", "allOf", '"not"', "$ref", '"if"'):
        assert banned not in text, banned
    description = schema["description"]
    for phrase in ("followers", "engaged_this_month", "`reason`", "never fill the gap",
                   "the people engaging with your Reels this month", "Read-only"):
        assert phrase in description, phrase
    assert "never guess an age, a gender or a city from their name, their photo or their category" in description


def test_the_capability_bullet_renders_only_when_offered_and_names_no_other_tool():
    bullet = CREATOR_CAPABILITY_LINES[GET_MY_AUDIENCE]
    assert bullet.startswith("- get_my_audience: read who their own audience is")
    for other in CREATOR_TOOL_NAMES:
        if other != GET_MY_AUDIENCE:
            assert other not in bullet, other
    assert bullet in render_creator_capabilities([GET_MY_AUDIENCE])
    assert bullet not in render_creator_capabilities(["get_my_metrics"])


# --- the other side's source ---------------------------------------------------------------------


def _java(path: Path) -> str:
    if not path.is_file():
        pytest.fail(f"{path} not found -- run inside a full-repo checkout (a skip is a vacuous pass)")
    return path.read_text(encoding="utf-8")


def test_every_python_creator_tool_is_a_java_enum_constant():
    source = _java(JAVA_ENUM)
    body = source[source.index("enum CreatorToolName {") : source.index(";", source.index("enum CreatorToolName {"))]
    body = re.sub(r"/\*\*.*?\*/", "", body, flags=re.DOTALL)
    names = set(re.findall(r"\b([a-z][a-z_]+)\b", body))
    assert set(CREATOR_TOOL_NAMES) <= names, set(CREATOR_TOOL_NAMES) - names


def _record_properties(source: str, record: str) -> list[str]:
    match = re.search(rf"record {record}\((.*?)\)\s*\{{", source, re.DOTALL)
    assert match, record
    return re.findall(r'@JsonProperty\("([a-z_]+)"\)', match.group(1))


def test_the_allow_list_is_exactly_the_java_records_keys():
    source = _java(JAVA_DTOS)
    assert _record_properties(source, "GetMyAudienceResult") == list(_TRUSTED_KEYS_GET_MY_AUDIENCE)
    section = _record_properties(source, "AudienceSection")
    assert set(section) == set(_AUDIENCE_SECTION_SCALARS) | set(_AUDIENCE_SECTION_LISTS)
    assert set(_record_properties(source, "AudienceAgeShare")) == _AUDIENCE_SECTION_LISTS["age"]
    assert set(_record_properties(source, "AudienceGenderShare")) == _AUDIENCE_SECTION_LISTS["gender"]
    assert set(_record_properties(source, "AudienceCountryShare")) == _AUDIENCE_SECTION_LISTS["top_countries"]
    assert _AUDIENCE_SECTION_LISTS["top_cities"] is None  # List<String>
    assert re.search(r'@JsonProperty\("top_cities"\) List<String>', source)


# --- the model's copy ----------------------------------------------------------------------------


def test_spring_shaped_payload_is_trusted_whole_and_unwrapped():
    copy = _model_copy_of_tool_result(GET_MY_AUDIENCE, _payload())
    assert "<untrusted" not in copy
    assert json.loads(copy) == _payload()


@pytest.mark.parametrize(
    "mutate, wrapped_key",
    [
        (lambda p: p.update({"note": CANARY}), "note"),
        (lambda p: p["followers"].update({"summary": CANARY}), "followers"),
        (lambda p: p["followers"].update({"reason": {"text": CANARY}}), "followers"),
        (lambda p: p["followers"]["age"].append({"band": "18-24", "pct": 1, "note": CANARY}), "followers"),
        (lambda p: p["followers"]["top_cities"].append({"name": CANARY}), "followers"),
        (lambda p: p["engaged_this_month"].update({"age": CANARY}), "engaged_this_month"),
        (lambda p: p.update({"followers": [CANARY]}), "followers"),
    ],
)
def test_anything_off_the_java_shape_is_wrapped_never_trusted(mutate, wrapped_key):
    payload = _payload()
    mutate(payload)
    copy = _model_copy_of_tool_result(GET_MY_AUDIENCE, payload)
    trusted_text, _, wrapped = copy.partition("\n")
    assert CANARY not in trusted_text
    assert wrapped.startswith("<untrusted_unclassified") and CANARY in wrapped
    assert wrapped_key not in json.loads(trusted_text)
    # The other section is still trusted as sent.
    other = "engaged_this_month" if wrapped_key != "engaged_this_month" else "followers"
    if wrapped_key != "note":
        assert json.loads(trusted_text)[other] == _payload()[other]
