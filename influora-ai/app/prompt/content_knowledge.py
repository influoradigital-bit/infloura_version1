"""Influora's own video-content knowledge, rendered as a cached system block
for CREATOR turns only.

Why this exists (Swapnil, 2026-09-21): when a creator asks "how do I grow my
channel?", Meera answers from Influora's content knowledge FIRST -- the
creator's category, then a named storytelling structure, hook template and
camera angles from this file -- and only falls back to general knowledge when
nothing here fits. Standing test (wiki/decisions/2026-09-21-one-ai-that-
reduces-work.md): the point is fewer steps for the creator, not a cleverer
answer. The persona rules that tell the model HOW to use this block live in
`app/prompt/creator_persona.py`; this module only loads, validates and renders.

Data: `app/prompt/knowledge/video_content_concepts.jsonl`. It sits
under `app/prompt/` on purpose: `ci/stale-comment-check.py` watches that prefix
(PROMPT_SOURCES), so editing the data forces a PROMPT_VERSION bump exactly like
a persona edit does -- the rendered text is prompt content.

Fail-loud contract: every row is validated (known `data_type`, required fields
present and non-empty) and the block is rendered at IMPORT time into
`CREATOR_KNOWLEDGE_TEXT`. `assembler.py` imports this module, and
`app.main` imports the chat route that imports the assembler, so a malformed
file stops the service at startup and fails every prompt test -- it can never
fail silently in the middle of a creator's chat.

Tenant-agnostic: zero creator data. Safe to cache globally (same rule as
Block A).
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

KNOWLEDGE_PATH = Path(__file__).parent / "knowledge" / "video_content_concepts.jsonl"

# Fields every row must carry, whatever its type.
_COMMON_REQUIRED: tuple[str, ...] = ("data_type", "confidence", "source")

# Per data_type: the fields the renderer reads. A missing one is a load error,
# not a silently shorter prompt.
REQUIRED_FIELDS: dict[str, tuple[str, ...]] = {
    "camera_angle": ("name", "category", "purpose", "when_to_use"),
    "storytelling_structure": ("framework", "steps", "video_application"),
    "persuasion_principle": ("principle", "definition", "hook_application"),
    "marketing_concept": ("concept", "definition", "video_application"),
    "hook_template": ("template", "category", "persuasion_principle", "goal_fit"),
    "narrative_principle": ("principle", "definition", "video_application"),
    "content_characteristic": ("characteristic", "definition", "video_application"),
    "platform_strategy": ("platform", "note", "video_application", "jab_hook_balance"),
    # 2026-09-21 go-live additions: how a paid brand post is made on Influora, and
    # one playbook per creator category. A playbook's `structure` and `camera`
    # must name entries that exist in this same file (checked at load), so a
    # playbook can never point Meera at a framework or shot that is not here.
    "brand_deal_practice": ("topic", "guidance", "video_application"),
    "category_playbook": ("category", "formats", "hook_angle", "structure", "camera", "never_say"),
}

# Fields that are a non-empty list of non-empty strings rather than one string.
LIST_FIELDS: frozenset[str] = frozenset({"steps", "formats", "camera"})

# The field that names an entry -- what Meera says back to the creator
# ("Before-After-Bridge (BAB)", "Static / locked-off shot").
NAME_FIELD: dict[str, str] = {
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
}

KNOWN_CONFIDENCE: frozenset[str] = frozenset({"high", "medium", "low", "template"})

# The two Hinglish hook templates whose [Number] slot invites an invented
# statistic. The persona names them verbatim; tests assert both appear in the
# data AND in the rule, so a reworded template cannot quietly escape the rule.
# The inline NUMBER RULE marker is applied to EVERY template with a [Number]
# slot (`has_number_slot`), including the English versions, not only these two.
NUMBER_STAT_HOOK_TEMPLATES: tuple[str, ...] = (
    "[Number] logo ne yeh try kiya — result dekho",
    "[Number]% log yeh galat karte hain — sahi tareeka yeh hai",
)

# Persuasion entries that inform STRUCTURE only -- their example wording is
# urgency copy Meera must never write for a creator.
STRUCTURE_ONLY_PRINCIPLES: tuple[str, ...] = ("Scarcity", "Commitment & consistency")

KNOWLEDGE_BLOCK_HEADING = "Influora content knowledge (check this FIRST for content and growth questions)"


class KnowledgeFileError(ValueError):
    """The knowledge file is malformed. Raised at import, never at chat time."""


def _validate_row(row: Any, lineno: int) -> dict[str, Any]:
    if not isinstance(row, dict):
        raise KnowledgeFileError(f"line {lineno}: row is not a JSON object")
    data_type = row.get("data_type")
    if data_type not in REQUIRED_FIELDS:
        raise KnowledgeFileError(f"line {lineno}: unknown data_type {data_type!r}")
    for key in _COMMON_REQUIRED + REQUIRED_FIELDS[data_type]:
        value = row.get(key)
        if key in LIST_FIELDS:
            if not isinstance(value, list) or not value or not all(
                isinstance(s, str) and s.strip() for s in value
            ):
                raise KnowledgeFileError(
                    f"line {lineno}: {data_type} field {key!r} must be a non-empty list of strings"
                )
            continue
        if not isinstance(value, str) or not value.strip():
            raise KnowledgeFileError(
                f"line {lineno}: {data_type} missing required field {key!r}"
            )
    if row["confidence"] not in KNOWN_CONFIDENCE:
        raise KnowledgeFileError(
            f"line {lineno}: unknown confidence {row['confidence']!r}"
        )
    return row


def load_knowledge(path: Path = KNOWLEDGE_PATH) -> list[dict[str, Any]]:
    """Reads and validates every row. Raises `KnowledgeFileError` on the first
    bad row, on a duplicate entry name within a data_type, or on an empty file."""
    rows: list[dict[str, Any]] = []
    seen: set[tuple[str, str]] = set()
    with path.open(encoding="utf-8") as fh:
        for lineno, line in enumerate(fh, start=1):
            if not line.strip():
                continue
            try:
                raw = json.loads(line)
            except json.JSONDecodeError as exc:
                raise KnowledgeFileError(f"line {lineno}: invalid JSON ({exc.msg})") from exc
            row = _validate_row(raw, lineno)
            key = (row["data_type"], row[NAME_FIELD[row["data_type"]]].strip())
            if key in seen:
                raise KnowledgeFileError(f"line {lineno}: duplicate {key[0]} entry {key[1]!r}")
            seen.add(key)
            rows.append(row)
    if not rows:
        raise KnowledgeFileError(f"{path.name}: no rows")
    _check_playbook_references(rows)
    return rows


def _check_playbook_references(rows: list[dict[str, Any]]) -> None:
    """A category playbook may only name a storytelling structure and camera
    shots that exist in this file -- Meera is told to use their exact names."""
    frameworks = {r["framework"].strip() for r in rows if r["data_type"] == "storytelling_structure"}
    shots = {r["name"].strip() for r in rows if r["data_type"] == "camera_angle"}
    for r in rows:
        if r["data_type"] != "category_playbook":
            continue
        if r["structure"].strip() not in frameworks:
            raise KnowledgeFileError(
                f"playbook {r['category']!r}: unknown structure {r['structure']!r}"
            )
        for shot in r["camera"]:
            if shot.strip() not in shots:
                raise KnowledgeFileError(f"playbook {r['category']!r}: unknown camera shot {shot!r}")


def has_number_slot(template: str) -> bool:
    """True for any hook template with a number-shaped slot ([Number], [statistic],
    [percent]), in any language -- a template must not escape the rule by renaming
    its slot."""
    t = template.lower()
    return any(slot in t for slot in ("[number", "[statistic", "[percent"))


def _by_type(rows: list[dict[str, Any]], data_type: str) -> list[dict[str, Any]]:
    return [r for r in rows if r["data_type"] == data_type]


def render_knowledge_block(rows: list[dict[str, Any]]) -> str:
    """Compact plain-text rendering, grouped by type. Drops `further_reading`
    and generic `source` labels (tokens with no effect on the answer); keeps
    the source caveat on platform_strategy rows because that caveat IS the
    reason they are background only."""
    out: list[str] = [
        KNOWLEDGE_BLOCK_HEADING + ".",
        "Each entry starts with its exact name. When you use one, say that name to the creator.",
        "",
        "Category playbooks (find the creator's category here first; obey its Never say line):",
    ]
    for r in _by_type(rows, "category_playbook"):
        out.append(
            f"- {r['category']}: formats: {', '.join(f.strip() for f in r['formats'])}."
            f" Hook angle: {r['hook_angle']} Structure: {r['structure']}."
            f" Camera: {', '.join(c.strip() for c in r['camera'])}. Never say: {r['never_say']}"
        )

    out += ["", "Brand deals on Influora (paid posts for a brand):"]
    for r in _by_type(rows, "brand_deal_practice"):
        out.append(f"- {r['topic']}: {r['guidance']} For video: {r['video_application']}")

    out += ["", "Storytelling structures:"]
    for r in _by_type(rows, "storytelling_structure"):
        steps = " -> ".join(s.strip() for s in r["steps"])
        out.append(f"- {r['framework']}: {steps}. For video: {r['video_application']}")

    out += ["", "Hook templates (fill the [slots]; the Hinglish wording is the template):"]
    for r in _by_type(rows, "hook_template"):
        line = f"- {r['template']} (type: {r['category']}; works on: {r['persuasion_principle']}; goal: {r['goal_fit']})"
        if has_number_slot(r["template"]):
            line += (
                " [NUMBER RULE: only a number from the creator's own context or one the"
                " creator gave you; otherwise use a different template]"
            )
        out.append(line)

    out += ["", "Camera angles and shots:"]
    for r in _by_type(rows, "camera_angle"):
        out.append(f"- {r['name']} ({r['category']}): {r['purpose']} Use when: {r['when_to_use']}")

    out += ["", "Narrative principles:"]
    for r in _by_type(rows, "narrative_principle"):
        out.append(f"- {r['principle']}: {r['definition']} For video: {r['video_application']}")

    out += ["", "Content characteristics:"]
    for r in _by_type(rows, "content_characteristic"):
        out.append(f"- {r['characteristic']}: {r['definition']} For video: {r['video_application']}")

    out += ["", "Persuasion principles:"]
    for r in _by_type(rows, "persuasion_principle"):
        line = f"- {r['principle']}: {r['definition']} In a hook: {r['hook_application']}"
        if r["principle"] in STRUCTURE_ONLY_PRINCIPLES:
            line += (
                " [STRUCTURE ONLY: shape the video with this idea, never write urgency"
                " or pressure wording for the creator]"
            )
        out.append(line)

    out += ["", "Marketing concepts:"]
    for r in _by_type(rows, "marketing_concept"):
        out.append(f"- {r['concept']}: {r['definition']} For video: {r['video_application']}")

    out += [
        "",
        "Platform background (confidence medium, dated -- platform mechanics change;"
        " background only, never a rule):",
    ]
    for r in _by_type(rows, "platform_strategy"):
        out.append(
            f"- {r['platform']} ({r['jab_hook_balance']}): {r['note']} For video:"
            f" {r['video_application']} Caveat: {r['source']}"
        )
    return "\n".join(out) + "\n"


# Rendered once, at import. A malformed file raises here -- at startup.
CREATOR_KNOWLEDGE_ROWS: list[dict[str, Any]] = load_knowledge()
CREATOR_KNOWLEDGE_TEXT: str = render_knowledge_block(CREATOR_KNOWLEDGE_ROWS)


def creator_shared_cache_control() -> dict[str, Any]:
    """`cache_control` for the creator system blocks shared by EVERY creator (Block A and
    this knowledge block). 1-hour TTL unless AI_CREATOR_SHARED_CACHE_TTL=5m. Anthropic
    requires 1-hour entries to come before 5-minute ones, which holds: both shared blocks
    precede the per-creator Block B (5 minutes)."""
    from app.config import get_settings

    if get_settings().ai_creator_shared_cache_ttl == "1h":
        return {"type": "ephemeral", "ttl": "1h"}
    return {"type": "ephemeral"}


def build_creator_knowledge_block() -> dict[str, Any]:
    """The cached system block for CREATOR turns. Never used on the BRAND path."""
    return {
        "type": "text",
        "text": CREATOR_KNOWLEDGE_TEXT,
        "cache_control": creator_shared_cache_control(),
    }
