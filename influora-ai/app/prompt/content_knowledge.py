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
import re
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
    # v4 (2026-09-22): what the full-script format reads -- the actions to film
    # per category, the starting length per goal, and which structure fits which
    # situation.
    "contextual_action": ("category", "home_actions", "outdoor_actions"),
    "length_guideline": ("goal", "starting_range_seconds", "main_success_signal"),
    "structure_selection_rule": ("situation", "structure", "use_when"),
}

# Fields that are a non-empty list of non-empty strings rather than one string.
LIST_FIELDS: frozenset[str] = frozenset(
    {"steps", "formats", "camera", "home_actions", "outdoor_actions"}
)

# "15-35": a starting range in whole seconds, low before high.
_SECONDS_RANGE = re.compile(r"^(\d+)-(\d+)$")

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
    "contextual_action": "category",
    "length_guideline": "goal",
    "structure_selection_rule": "situation",
}

KNOWN_CONFIDENCE: frozenset[str] = frozenset({"high", "medium", "low", "template"})

# Statistic-slot rule (narrowed 2026-09-22, .22.4). The rule exists to stop an
# invented CLAIM ABOUT THE WORLD -- how many people did something, what
# percentage get something wrong, results others got. A number that is part of
# the creator's own idea ("sirf [duration] minute", "Ye [number] galtiyan": the
# routine's length, how many tips the video covers) is an honest content choice
# and stays free to fill. Detection is by slot shape, not a list of template
# strings, so a future template is caught without anyone listing it:
#   1. a slot NAMED like a statistic: [statistic...], [percent...], [percentage...],
#      [people count...] / [people-count...];
#   2. any slot followed by "%" (a percentage claim);
#   3. a [number]/[count] slot followed by a people word ("[Number] logo ne ...",
#      "[number] people ..."): a claim about how many OTHER people did something.
_STATISTIC_SLOT_PATTERNS: tuple[re.Pattern[str], ...] = (
    re.compile(r"\[(?:statistic|percent(?:age)?|people[\s_-]?count)\b[^\]]*\]", re.IGNORECASE),
    re.compile(r"\[[^\]]*\]\s*%"),
    re.compile(
        r"\[(?:number|count)\b[^\]]*\]\s*(?:log|logo|logon|people|users|creators|viewers)\b",
        re.IGNORECASE,
    ),
)


def has_statistic_slot(template: str) -> bool:
    """True when the template asks for a statistic or a claim about other
    people. Durations and tip/step counts of the creator's own video are not."""
    return any(p.search(template) for p in _STATISTIC_SLOT_PATTERNS)


# Call-to-action rule (audit 2026-09-24, lane B3). The full script allows ONE
# call to action, in the last beat only, while two hook templates end their
# opening line with a comment ask ("What's your take?",
# "Comment mein '[word]' likho -- seedha DM mein milega"). Such a template is
# marked CTA RULE: in a script its opening keeps the first part and the comment
# ask moves to the caption; the last beat keeps the goal's one call to action. Detected by wording, not by a list
# of template strings, so a future template is caught without anyone listing it.
_HOOK_CTA_PATTERNS: tuple[re.Pattern[str], ...] = (
    re.compile(r"\bcomments?\b", re.IGNORECASE),
    re.compile(r"\bDM\b"),
    # "What's your opinion?" / "What's your take?" is the same ask for a reply without the word
    # "comment" (the English hot-take hook that came in from launch, merge of 2026-09-24).
    re.compile(r"\bwhat(?:'|\u2019)?s your (?:opinion|take)\b", re.IGNORECASE),
)


def has_hook_cta(template: str) -> bool:
    """True when the hook template itself asks the viewer to comment or DM."""
    return any(p.search(template) for p in _HOOK_CTA_PATTERNS)


# Persuasion entries that inform STRUCTURE only -- their example wording is
# urgency copy Meera must never write for a creator.
STRUCTURE_ONLY_PRINCIPLES: tuple[str, ...] = ("Scarcity", "Commitment & consistency")

# Narrative entries about outrage and status: aim them at ideas, never people.
IDEAS_ONLY_PRINCIPLES: tuple[str, ...] = ("Status games and moral outrage as engagement drivers",)

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
    if data_type == "length_guideline":
        m = _SECONDS_RANGE.match(row["starting_range_seconds"].strip())
        if not m or int(m.group(1)) >= int(m.group(2)):
            raise KnowledgeFileError(
                f"line {lineno}: length_guideline starting_range_seconds must look like '15-35'"
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
    # A selection rule must point at a structure the block actually defines,
    # by its exact name -- otherwise Meera picks a structure with no steps.
    defined = {r["framework"].strip() for r in _by_type(rows, "storytelling_structure")}
    for r in _by_type(rows, "structure_selection_rule"):
        if r["structure"].strip() not in defined:
            raise KnowledgeFileError(
                f"structure_selection_rule {r['situation']!r} names undefined structure"
                f" {r['structure']!r}"
            )
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

    out += ["", "Hook templates (fill the [slots]; write the hook in the reply language: a Hinglish and an English template of the same type are the same hook, so translate as needed):"]
    for r in _by_type(rows, "hook_template"):
        line = f"- {r['template']} (type: {r['category']}; works on: {r['persuasion_principle']}; goal: {r['goal_fit']})"
        if has_statistic_slot(r["template"]):
            line += (
                " [STATISTIC RULE: never invent this statistic; only a figure from the"
                " creator's own context or one the creator gave you; otherwise use a"
                " different template]"
            )
        if has_hook_cta(r["template"]):
            line += (
                " [CTA RULE: in a script, say only the part before the comment ask in the"
                " opening; the comment ask moves to the caption as the conversation question,"
                " and the last beat keeps the goal's one call to action; never promise the"
                " creator will DM anyone unless they said they will]"
            )
        out.append(line)

    out += ["", "Camera angles and shots:"]
    for r in _by_type(rows, "camera_angle"):
        out.append(f"- {r['name']} ({r['category']}): {r['purpose']} Use when: {r['when_to_use']}")

    out += ["", "Narrative principles:"]
    for r in _by_type(rows, "narrative_principle"):
        line = f"- {r['principle']}: {r['definition']} For video: {r['video_application']}"
        if r["principle"] in IDEAS_ONLY_PRINCIPLES:
            line += (
                " [IDEAS ONLY: never name, shame or target a real individual or brand;"
                " aim outrage and status at ideas, practices or common mistakes]"
            )
        out.append(line)

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

    out += ["", "Which structure to use (situation -> the storytelling structure above):"]
    for r in _by_type(rows, "structure_selection_rule"):
        out.append(f"- {r['situation']} -> {r['structure']}. Use when: {r['use_when']}")

    out += [
        "",
        "Script length by goal (starting range in seconds; a full script's timings add up"
        " to a length inside it):",
    ]
    for r in _by_type(rows, "length_guideline"):
        out.append(
            f"- {r['goal']}: {r['starting_range_seconds'].strip()} seconds."
            f" Success looks like: {r['main_success_signal']}"
        )

    out += [
        "",
        "Actions to film, by category (real actions the creator can do on camera; a person,"
        " shop or place is filmed only with permission):",
    ]
    for r in _by_type(rows, "contextual_action"):
        home = ", ".join(a.strip() for a in r["home_actions"])
        outdoor = ", ".join(a.strip() for a in r["outdoor_actions"])
        out.append(f"- {r['category']}: at home: {home}. Outdoors: {outdoor}.")
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
