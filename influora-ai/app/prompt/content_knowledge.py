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

Data: `app/prompt/knowledge/video_content_concepts.jsonl` (349 rows: 307 through v7 -- v4
2026-09-22 + the 2026-09-21 go-live additions + the 38 v5 camera rows + the v6 outdoor-light
and delivery rows + the v7 lighting and positioning rows of 2026-09-24 -- plus 42 v8 audio and
movement rows of 2026-09-24). Not every row is always sent: 295 rows render into the cached
block `CREATOR_KNOWLEDGE_TEXT` on every creator turn; the other 54 (the 12 delivery examples
and the 42 v8 rows) render into `LOOKUP_TEXT`, which the model fetches per topic with the
local `get_creator_knowledge` tool (`LOOKUP_TOPICS`). The block ends with a "More on request"
list naming each topic, so the model knows what it can look up. It sits
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
    # v5 (2026-09-24): how to SHOOT -- settings per situation, light, background,
    # positioning, failure fixes, standing rules, India's 50Hz flicker, export,
    # and verified notes on specific phones. Rendered as "Shooting and camera
    # settings" below and, in a shorter form, into the Shoot Check frame check
    # (`app/prompt/frame_check.py`). Phone rows are only ever applied to the
    # phone the creator named (`find_phone`); any other phone gets generic advice.
    "camera_technical_setting": (
        "situation", "phone_camera", "distance", "framing", "fps", "shutter", "iso",
        "white_balance", "ev", "stabilization",
    ),
    "night_video_setting": (
        "environment", "lens", "fps", "shutter", "iso", "white_balance", "stabilization", "extra_light",
    ),
    "lighting_rule": ("scenario", "instruction"),
    "background_rule": ("aspect", "definition"),
    "subject_positioning_rule": ("content_type", "instruction"),
    "platform_export_setting": ("platform", "aspect_ratio", "safe_zones", "workflow"),
    "failure_case": ("symptom", "cause", "fix"),
    "permanent_rule": ("rule",),
    "flicker_rule": ("region", "rule", "fix"),
    "phone_hardware": (
        "brand", "model", "sensor", "max_resolution", "max_fps", "manual_video", "ois",
        "telephoto", "ultrawide", "log_hdr",
    ),
    # v6 (2026-09-24): how to SAY the lines -- delivery rules reworded as creator advice,
    # the guardrails that keep them from becoming targets or scores, and synthetic examples
    # of one stressed phrase per line. Rendered as "How to deliver the lines".
    "delivery_rule": ("rule", "when", "advice", "why", "limits"),
    "delivery_guardrails": ("name", "guardrails"),
    "delivery_example": (
        "example_id", "script", "said", "language", "platform", "stress", "pause", "pace", "visual",
    ),
    # v7 (2026-09-24): where to PUT the creator, the phone and the light -- dataset 7's
    # lighting and positioning rows plus four types authored from the lighting guide
    # (fix order, looks by category, mixed light colours, sunset to night). Optional on
    # every v7 row: `limits` (a string, rendered as "Limits: ...") and `refs` (the guide's
    # [n] source numbers, not rendered). Rendered as "Placing the creator, the phone and
    # the light" at the end of the shooting section, so the frame check gets it too.
    "lighting_workflow": ("principle", "definition"),
    "lighting_angle_rule": ("key_angle_from_face", "face_shadow_result", "phone_placement", "use_case"),
    "window_lighting_rule": ("window_position", "result", "instruction"),
    "indian_home_lighting_rule": ("source_type", "guidance"),
    "camera_height_rule": ("camera_position", "perceived_result", "use_case"),
    "background_repair_rule": ("problem", "first_fix", "secondary_fix"),
    "portrait_lighting_pattern": ("pattern", "setup", "caution"),
    "indian_creator_scene_checklist": ("setting", "first_choice", "risk_to_check"),
    "coordinate_system_note": ("principle", "definition"),
    "physics_principle": ("principle", "definition"),
    "lighting_fix_order": ("step", "situation", "first_move", "why"),
    "lighting_look": ("look", "setup", "check", "fits_categories"),
    "mixed_light_rule": ("mix", "recommendation"),
    "sunset_to_night_step": ("stage", "instruction"),
    # v8 (2026-09-24): sound, and moving between two spots in one reel -- dataset 8's rows that
    # dataset 7 did not have. NOT in the always-sent block: they render only into the lookup
    # topics "audio" and "moving_between_spots" (see LOOKUP_TOPICS). Their source document was
    # not supplied, so every row is confidence medium and each topic heading says so once.
    # Optional: `safety_note` on walking_configuration (rendered as "Safety: ..."). Their camera
    # field is `camera_setup`, a string: `camera` is a LIST_FIELDS name (the playbook's shots).
    "microphone_selection_rule": ("capture_route", "use_when", "main_risk", "price_class"),
    "mic_distance_rule": ("mouth_to_mic", "interpretation", "default_action"),
    "lav_placement_rule": ("wardrobe", "placement", "avoid"),
    "audio_noise_rule": ("noise_source", "decision_tree"),
    "audio_diagnostic_rule": ("symptom", "likely_cause", "ten_second_test", "fix"),
    "phone_audio_capability": ("device_family", "known_behavior", "what_to_suggest", "do_not_assume"),
    "audio_movement_scenario": (
        "id", "category", "device", "environment", "microphone", "camera_setup", "audio_problem",
        "recommended_solution", "example_status",
    ),
    "movement_continuity_rule": ("rule", "guidance"),
    "walking_configuration": ("configuration", "camera_setup", "audio", "light_transition"),
}

# Fields that are non-empty lists of non-empty strings, not plain strings.
LIST_FIELDS: frozenset[str] = frozenset(
    {"steps", "formats", "camera", "home_actions", "outdoor_actions", "guardrails"}
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
    "delivery_rule": "rule",
    "delivery_guardrails": "name",
    "delivery_example": "example_id",
    # v7 lighting and positioning rows.
    "lighting_workflow": "principle",
    "lighting_angle_rule": "key_angle_from_face",
    "window_lighting_rule": "window_position",
    "indian_home_lighting_rule": "source_type",
    "camera_height_rule": "camera_position",
    "background_repair_rule": "problem",
    "portrait_lighting_pattern": "pattern",
    "indian_creator_scene_checklist": "setting",
    "coordinate_system_note": "principle",
    "physics_principle": "principle",
    "lighting_fix_order": "situation",
    "lighting_look": "look",
    "mixed_light_rule": "mix",
    "sunset_to_night_step": "stage",
    # v8 audio and movement rows (lookup only).
    "microphone_selection_rule": "capture_route",
    "mic_distance_rule": "mouth_to_mic",
    "lav_placement_rule": "wardrobe",
    "audio_noise_rule": "noise_source",
    "audio_diagnostic_rule": "symptom",
    "phone_audio_capability": "device_family",
    "audio_movement_scenario": "id",
    "movement_continuity_rule": "rule",
    "walking_configuration": "configuration",
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
# opening line with a comment ask ("Aap kis side ho -- comment mein batao",
# "Comment mein '[word]' likho -- seedha DM mein milega"). Such a template is
# marked CTA RULE: in a script its opening keeps the first part and the comment
# ask moves to the last beat or the caption. Detected by wording, not by a list
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
    if "limits" in row and not isinstance(row["limits"], str):
        raise KnowledgeFileError(f"line {lineno}: {data_type} field 'limits' must be a string")
    if "refs" in row:
        refs = row["refs"]
        if not isinstance(refs, list) or not all(
            isinstance(n, int) and not isinstance(n, bool) for n in refs
        ):
            raise KnowledgeFileError(
                f"line {lineno}: {data_type} field 'refs' must be a list of source numbers"
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

    out += [""] + render_shooting_lines(rows)
    out += [""] + render_delivery_lines(rows)
    out += [""] + render_more_on_request_lines()
    return "\n".join(out) + "\n"


DELIVERY_HEADING = (
    "How to deliver the lines (how the creator SAYS a line: stress, pauses, pace, energy,"
    " gestures. Every tip is optional and relative to the creator's own voice):"
)


def render_delivery_lines(rows: list[dict[str, Any]]) -> list[str]:
    """The always-sent v6 delivery rows: guardrails first (they bound every rule below),
    then the rules as advice with their limits. The synthetic examples are NOT here --
    they are the "delivery_examples" lookup topic (`render_delivery_example_lines`)."""
    out: list[str] = [DELIVERY_HEADING]
    for r in _by_type(rows, "delivery_guardrails"):
        out.append(f"{r['name']} (always):")
        out.extend(f"- {g.strip()}" for g in r["guardrails"])
    out += ["", "Delivery rules:"]
    for r in _by_type(rows, "delivery_rule"):
        out.append(f"- {r['rule']}: {r['advice']} Limits: {r['limits']}")
    return out


DELIVERY_EXAMPLES_HEADING = (
    "Delivery examples (synthetic illustrations, not real creator data; any number or result"
    " in them is part of the example, never a fact to reuse). \"/\" marks where the line breaks"
    " into parts; the Stress and Pause here are the same cues a full-script beat carries. The"
    " delivery guardrails in your knowledge block still apply:"
)


def render_delivery_example_lines(rows: list[dict[str, Any]]) -> list[str]:
    """The v6 synthetic delivery examples, one per line -- the "delivery_examples" lookup
    topic. Moved out of the always-sent block on 2026-09-24."""
    out: list[str] = [DELIVERY_EXAMPLES_HEADING]
    for r in _by_type(rows, "delivery_example"):
        line = (
            f"- {r['example_id']} ({r['language']}, {r['platform']}): Say: \"{r['said']}\""
            f" Stress: {r['stress']}. Pause: {r['pause']}. Pace: {r['pace']}. Visual: {r['visual']}."
        )
        note = r.get("note")
        if isinstance(note, str) and note.strip():
            line += f" Note: {note.strip()}"
        out.append(line)
    return out


# Short on purpose: the two standing rules right under it already say "only suggest what
# the creator's phone can actually do" (with the auto-only list) and "give every camera
# setting with its one-line reason". It never points at the Phone notes, because the frame
# check renders this section without them (`with_phone_notes=False`).
SHOOTING_HEADING = "Shooting and camera settings (starting points, not laws):"

PHONE_NOTES_HEADING = (
    "Phone notes (ONLY for these exact models. For any other phone never assume a lens,"
    " 4K/60fps or a manual control -- ask what its camera app offers, or give advice that"
    " works on every phone):"
)


def phone_note_line(r: dict[str, Any]) -> str:
    """One phone_hardware row as a single line. Used by the knowledge block and by
    the frame check when the creator's saved phone matches this row."""
    line = (
        f"{r['brand']} {r['model']}: main camera {r['sensor']}; video: {r['max_resolution']};"
        f" frame rates: {r['max_fps']}; manual video: {r['manual_video']}; OIS: {r['ois']};"
        f" telephoto: {r['telephoto']}; ultrawide: {r['ultrawide']}; LOG/HDR: {r['log_hdr']}."
    )
    notes = r.get("notes")
    if isinstance(notes, str) and notes.strip():
        line += f" {notes.strip()}"
    return line


def render_shooting_lines(rows: list[dict[str, Any]], *, with_phone_notes: bool = True) -> list[str]:
    """The v5 camera rows as plain lines. The creator knowledge block renders all of
    it; the frame check renders it without the per-situation tables it cannot use
    (`with_phone_notes` stays on there too -- the matched phone is named separately)."""
    out: list[str] = [SHOOTING_HEADING, "Standing rules:"]
    for r in _by_type(rows, "permanent_rule"):
        out.append(f"- {r['rule']}")
    for r in _by_type(rows, "flicker_rule"):
        out.append(f"- {r['region']} 50Hz lights: {r['rule']} Fix: {r['fix']}")

    out += ["", "Light:"]
    for r in _by_type(rows, "lighting_rule"):
        out.append(f"- {r['scenario']}: {r['instruction']}")

    out += ["", "Background:"]
    for r in _by_type(rows, "background_rule"):
        line = f"- {r['aspect']}: {r['definition']}"
        fix = r.get("fix")
        if isinstance(fix, str) and fix.strip():
            line += f" Fix: {fix.strip()}"
        out.append(line)

    out += ["", "Where to put the person and the phone:"]
    for r in _by_type(rows, "subject_positioning_rule"):
        out.append(f"- {r['content_type']}: {r['instruction']}")

    out += ["", "When a shot goes wrong (what you see: why. Fix):"]
    for r in _by_type(rows, "failure_case"):
        out.append(f"- {r['symptom']}: {r['cause']} Fix: {r['fix']}")

    out += [
        "",
        "Settings by situation (lens, distance, framing; fps, shutter, ISO, white balance,"
        " exposure; stabilisation -- the numbers need a Pro/manual video mode):",
    ]
    for r in _by_type(rows, "camera_technical_setting"):
        out.append(
            f"- {r['situation']}: {r['phone_camera']} at {r['distance']}, {r['framing']};"
            f" fps {r['fps']}, shutter {r['shutter']}, ISO {r['iso']}, white balance"
            f" {r['white_balance']}, EV {r['ev']}; {r['stabilization']}."
        )

    out += ["", "Night video (same caveat -- the numbers need Pro/manual video):"]
    for r in _by_type(rows, "night_video_setting"):
        out.append(
            f"- {r['environment']}: {r['lens']}; fps {r['fps']}, shutter {r['shutter']},"
            f" ISO {r['iso']}, white balance {r['white_balance']}; {r['stabilization']}."
            f" Extra light: {r['extra_light']}."
        )

    out += ["", "Export for the platform:"]
    for r in _by_type(rows, "platform_export_setting"):
        out.append(
            f"- {r['platform']} ({r['aspect_ratio']}): safe zones: {r['safe_zones']}"
            f" Workflow: {r['workflow']}"
        )

    placement = render_placement_lines(rows)
    if placement:
        out += [""] + placement

    if with_phone_notes:
        out += ["", PHONE_NOTES_HEADING]
        for r in _by_type(rows, "phone_hardware"):
            out.append(f"- {phone_note_line(r)}")
    return out


PLACEMENT_HEADING = (
    "Placing the creator, the phone and the light (fix the scene before the settings;"
    " angles are starting points, not laws):"
)

# The fix-order rows, one situation per line, sorted by `step` (the number is not rendered).
FIRST_MOVE_HEADING = "First move by situation (fix the scene before the settings):"

# "Lighting looks" is the name the creator persona points at -- keep them in step.
LIGHTING_LOOKS_HEADING = (
    "Lighting looks (pick the one that fits the creator's category; a look is a visual"
    " convention, never a promise of views or a guaranteed audience effect):"
)


# Caveats a section heading already states once (PLACEMENT_HEADING: angles are starting
# points; the sunset heading: timing follows the sun, not the clock). A row whose limit is only
# one of these is not repeated line after line -- the review counted 15 copies.
_HEADING_CARRIED_LIMITS: frozenset[str] = frozenset({
    "The exact angle is a starting point to test, not a law.",
    "Timing depends on your location and date, not the clock; recheck your face and background"
    " every few minutes as the sun drops.",
})

SUNSET_HEADING = (
    "Sunset to night, step by step (timing depends on your location and date, not the clock;"
    " recheck your face and background every few minutes as the sun drops):"
)


def _with_limits(line: str, r: dict[str, Any]) -> str:
    """Appends the row's optional `limits` as "Limits: ..." when it has one, unless the
    section heading already carries that exact caveat."""
    limits = r.get("limits")
    if isinstance(limits, str) and limits.strip() and limits.strip() not in _HEADING_CARRIED_LIMITS:
        line += f" Limits: {limits.strip()}"
    return line


def _step_key(r: dict[str, Any]) -> tuple[int, int | str]:
    """Numeric steps in number order; anything else after them, in text order."""
    step = r["step"].strip()
    return (0, int(step)) if step.isdigit() else (1, step)


def render_placement_lines(rows: list[dict[str, Any]]) -> list[str]:
    """The v7 lighting and positioning rows as plain lines, one per row, each starting
    with its name. Order: the workflow (read the light, then move the creator, the phone,
    the light, then the settings) and the first move by situation (sorted by `step`,
    which is not rendered) first, then where the light sits, the phone
    height and background, the looks, sunset to night, the Indian scene checklist and
    the physics behind it all. Empty when the file carries no v7 rows."""
    groups: list[tuple[str, list[str]]] = []

    def group(title: str, lines: list[str]) -> None:
        if lines:
            groups.append((title, lines))

    group(
        "Order of work:",
        [_with_limits(f"- {r['principle']}: {r['definition']}", r)
         for r in _by_type(rows, "lighting_workflow")],
    )
    group(
        FIRST_MOVE_HEADING,
        [
            _with_limits(
                f"- {r['situation']}: first move: {r['first_move']} Why: {r['why']}", r
            )
            for r in sorted(_by_type(rows, "lighting_fix_order"), key=_step_key)
        ],
    )
    group(
        "Left and right (always the creator's own, as they face the phone: \"your left\","
        " \"your right\"):",
        [_with_limits(f"- {r['principle']}: {r['definition']}", r)
         for r in _by_type(rows, "coordinate_system_note")],
    )
    group(
        "Where the main light sits, measured from the creator's face:",
        [
            _with_limits(
                f"- {r['key_angle_from_face']}: {r['face_shadow_result']} Phone:"
                f" {r['phone_placement']} Use when: {r['use_case']}",
                r,
            )
            for r in _by_type(rows, "lighting_angle_rule")
        ],
    )
    group(
        "Window light:",
        [_with_limits(f"- {r['window_position']}: {r['result']} Do this: {r['instruction']}", r)
         for r in _by_type(rows, "window_lighting_rule")],
    )
    group(
        "Lights in an Indian home:",
        [_with_limits(f"- {r['source_type']}: {r['guidance']}", r)
         for r in _by_type(rows, "indian_home_lighting_rule")],
    )
    group(
        "Mixed light colours:",
        [_with_limits(f"- {r['mix']}: {r['recommendation']}", r)
         for r in _by_type(rows, "mixed_light_rule")],
    )
    group(
        "Phone height:",
        [_with_limits(f"- {r['camera_position']}: {r['perceived_result']} Use when: {r['use_case']}", r)
         for r in _by_type(rows, "camera_height_rule")],
    )
    group(
        "Fixing the background (first fix, then the next one):",
        [_with_limits(f"- {r['problem']}: first: {r['first_fix']} Then: {r['secondary_fix']}", r)
         for r in _by_type(rows, "background_repair_rule")],
    )
    group(
        LIGHTING_LOOKS_HEADING,
        [
            _with_limits(
                f"- {r['look']}: {r['setup']} Check: {r['check']} Fits: {r['fits_categories']}", r
            )
            for r in _by_type(rows, "lighting_look")
        ],
    )
    group(
        "Portrait lighting patterns:",
        [_with_limits(f"- {r['pattern']}: {r['setup']} Caution: {r['caution']}", r)
         for r in _by_type(rows, "portrait_lighting_pattern")],
    )
    group(
        SUNSET_HEADING,
        [_with_limits(f"- {r['stage']}: {r['instruction']}", r)
         for r in _by_type(rows, "sunset_to_night_step")],
    )
    group(
        "Indian creator scene checklist (first choice, then what to check):",
        [
            _with_limits(
                f"- {r['setting']}: {r['first_choice']} Check: {r['risk_to_check']}", r
            )
            for r in _by_type(rows, "indian_creator_scene_checklist")
        ],
    )
    group(
        "Why it works:",
        [_with_limits(f"- {r['principle']}: {r['definition']}", r)
         for r in _by_type(rows, "physics_principle")],
    )

    if not groups:
        return []
    out: list[str] = [PLACEMENT_HEADING]
    for i, (title, lines) in enumerate(groups):
        if i:
            out.append("")
        out.append(title)
        out.extend(lines)
    return out


# ---------------------------------------------------------------------------------------
# Lookup topics: knowledge the model fetches with the LOCAL tool `get_creator_knowledge`
# (executed in influora-ai's tool loop, never forwarded to Spring) instead of carrying it on
# every creator turn. Topic -> the one line the "More on request" list and the tool's enum
# description show. Order matters: it is the order of the list and of the tool's enum.
LOOKUP_TOPICS: dict[str, str] = {
    "audio": (
        "Getting a clean voice: which mic, mic distance, where to clip a lav by clothing, fan, AC,"
        " traffic, wind and echo noise, 10-second audio tests, phone audio features, example"
        " scenarios."
    ),
    "moving_between_spots": (
        "Moving between two spots in one reel: cut or walk and talk, walking camera setups, light"
        " and sound changes between spots, safety."
    ),
    "delivery_examples": (
        "Worked examples of stress, pauses and pace for a line (English, Hinglish, Hindi)."
    ),
}

# The v8 rows' source document was not supplied, so nothing in them could be checked. Said
# once per topic heading, not on every row.
LOOKUP_SOURCE_CAVEAT = "Source document not yet supplied; practical starting points."

MORE_ON_REQUEST_HEADING = (
    "More on request (NOT in this block. Before answering a question on one of these topics,"
    " call the get_creator_knowledge tool with that topic and answer from what it returns;"
    " never guess these from memory):"
)


def render_more_on_request_lines() -> list[str]:
    """The last section of the always-sent block: each lookup topic and its one line."""
    return [MORE_ON_REQUEST_HEADING] + [f"- {t}: {d}" for t, d in LOOKUP_TOPICS.items()]


def _lookup_groups(
    heading: str, groups: list[tuple[str, list[str]]]
) -> list[str]:
    """A topic heading, then each group title and its lines, groups separated by a blank
    line. Raises when a group has no rows: a topic section can never quietly go missing."""
    out: list[str] = [heading]
    for title, lines in groups:
        if not lines:
            raise KnowledgeFileError(f"lookup section {title!r} has no rows")
        out += ["", title]
        out.extend(lines)
    return out


def render_audio_lines(rows: list[dict[str, Any]]) -> list[str]:
    """The "audio" lookup topic: the v8 audio rows as readable sections."""
    return _lookup_groups(
        f"Audio: getting a clean voice (starting points, not laws. {LOOKUP_SOURCE_CAVEAT})",
        [
            (
                "Which mic:",
                [
                    f"- {r['capture_route']}: Use when: {r['use_when']} Main risk: {r['main_risk']}"
                    f" Price: {r['price_class']}"
                    for r in _by_type(rows, "microphone_selection_rule")
                ],
            ),
            (
                "Mic distance (mouth to mic):",
                [
                    f"- {r['mouth_to_mic']}: {r['interpretation']} Do this: {r['default_action']}"
                    for r in _by_type(rows, "mic_distance_rule")
                ],
            ),
            (
                "Where to clip the mic (by clothing):",
                [
                    f"- {r['wardrobe']}: {r['placement']} Avoid: {r['avoid']}"
                    for r in _by_type(rows, "lav_placement_rule")
                ],
            ),
            (
                "Noise:",
                [f"- {r['noise_source']}: {r['decision_tree']}" for r in _by_type(rows, "audio_noise_rule")],
            ),
            (
                "10-second tests (what you hear: likely cause. Test. Fix):",
                [
                    f"- {r['symptom']}: {r['likely_cause']} Test: {r['ten_second_test']} Fix: {r['fix']}"
                    for r in _by_type(rows, "audio_diagnostic_rule")
                ],
            ),
            (
                "Phone audio features (only for these phone families; never assume a feature for"
                " any other phone -- ask what its camera app offers):",
                [
                    f"- {r['device_family']}: {r['known_behavior']} Suggest: {r['what_to_suggest']}"
                    f" Do not assume: {r['do_not_assume']}"
                    for r in _by_type(rows, "phone_audio_capability")
                ],
            ),
            (
                "Example scenarios (synthetic examples, not observed data; never present one as"
                " something that happened):",
                [
                    f"- {r['id']} ({r['category']}; {r['device']}; {r['example_status']}): Setting:"
                    f" {r['environment']} Mic: {r['microphone']} Camera: {r['camera_setup']} Problem:"
                    f" {r['audio_problem']} What to do: {r['recommended_solution']}"
                    for r in _by_type(rows, "audio_movement_scenario")
                ],
            ),
        ],
    )


def _walking_line(r: dict[str, Any]) -> str:
    line = (
        f"- {r['configuration']}: Camera: {r['camera_setup']} Audio: {r['audio']}"
        f" Light: {r['light_transition']}"
    )
    safety = r.get("safety_note")
    if isinstance(safety, str) and safety.strip():
        line += f" Safety: {safety.strip()}"
    return line


def render_moving_lines(rows: list[dict[str, Any]]) -> list[str]:
    """The "moving_between_spots" lookup topic: cut-or-continue rules, then walking setups
    with their safety notes."""
    return _lookup_groups(
        "Moving between two spots in one reel (starting points, not laws; a setup's Safety note"
        f" always comes before the shot. {LOOKUP_SOURCE_CAVEAT})",
        [
            (
                "Cut or keep talking (light and sound changes between spots):",
                [f"- {r['rule']}: {r['guidance']}" for r in _by_type(rows, "movement_continuity_rule")],
            ),
            (
                "Walking setups:",
                [_walking_line(r) for r in _by_type(rows, "walking_configuration")],
            ),
        ],
    )


def render_lookup_topic(rows: list[dict[str, Any]], topic: str) -> str:
    """One lookup topic's plain text. Raises KeyError for an unknown topic and
    KnowledgeFileError when the topic renders empty."""
    renderers = {
        "audio": render_audio_lines,
        "moving_between_spots": render_moving_lines,
        "delivery_examples": render_delivery_example_lines,
    }
    lines = renderers[topic](rows)
    if len(lines) < 2:
        raise KnowledgeFileError(f"lookup topic {topic!r} has no rows")
    return "\n".join(lines) + "\n"


def _phone_key(text: str) -> str:
    """Lower-case letters and digits only, with the brand word and "5g" dropped, so
    "OPPO Reno 14 Pro 5G", "reno14 pro" and "Reno-14-Pro" all compare equal."""
    key = re.sub(r"[^a-z0-9]", "", text.lower())
    return key.replace("5g", "").replace("oppo", "")


def find_phone(rows: list[dict[str, Any]], typed: str | None) -> dict[str, Any] | None:
    """The phone_hardware row for the phone the creator typed, or None.

    Match rule: the longest model key contained in the typed key wins, so the
    full model name must be there -- "Reno 14" (no "Pro") does not match
    "Reno 14 Pro". A phone we have no row for returns None, and every caller
    then gives advice that works on any phone -- never another model's specs."""
    if not typed or not typed.strip():
        return None
    key = _phone_key(typed)
    if not key:
        return None
    best: dict[str, Any] | None = None
    best_len = 0
    for r in _by_type(rows, "phone_hardware"):
        model_key = _phone_key(r["model"])
        if model_key and model_key in key and len(model_key) > best_len:
            best, best_len = r, len(model_key)
    return best


# Rendered once, at import. A malformed file raises here -- at startup.
CREATOR_KNOWLEDGE_ROWS: list[dict[str, Any]] = load_knowledge()
CREATOR_KNOWLEDGE_TEXT: str = render_knowledge_block(CREATOR_KNOWLEDGE_ROWS)

# Every lookup topic, rendered once at import. An empty section raises here -- at startup.
LOOKUP_TEXT: dict[str, str] = {
    topic: render_lookup_topic(CREATOR_KNOWLEDGE_ROWS, topic) for topic in LOOKUP_TOPICS
}


def render_lookup_section(topic: str) -> str | None:
    """The plain text for one lookup topic, or None for an unknown or missing topic
    (the tool then returns an unknown_topic error; it never raises at chat time)."""
    if not isinstance(topic, str):
        return None
    return LOOKUP_TEXT.get(topic)


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
