"""The 5 Meera tool JSON schemas — SINGLE SOURCE OF TRUTH.

This is the only place these schemas are defined. They must match:
- `06-MEERA-PERMISSIONS-MATRIX.md` (R/D/C tiers)
- Spring's `/internal/meera/*` executor contract in `02-API-CONTRACT-BRAND.md`

A CI shared-schema diff-check (separate pipeline stage) compares this file's
output against the Spring executor DTOs so the two sides never drift. Do not
add a sixth tool here without updating both docs and the Spring side first.

Tiers:
- show_creators     (R) read-only, no money
- calculate_budget  (R) read-only, no money
- create_campaign   (D) draft/state-write, no money moves
- request_payment   (C) commit-tier — PROPOSAL only; display_amount_hint is
                        chat-copy only, Spring ignores it for authorization
- confirm_launch    (C) commit-tier — proposal only, Spring verifies FUNDED
"""

from __future__ import annotations

from typing import Any, Literal

ToolTier = Literal["read", "draft", "commit"]

SHOW_CREATORS = "show_creators"
CALCULATE_BUDGET = "calculate_budget"
CREATE_CAMPAIGN = "create_campaign"
REQUEST_PAYMENT = "request_payment"
CONFIRM_LAUNCH = "confirm_launch"

TOOL_NAMES: tuple[str, ...] = (
    SHOW_CREATORS,
    CALCULATE_BUDGET,
    CREATE_CAMPAIGN,
    REQUEST_PAYMENT,
    CONFIRM_LAUNCH,
)

TOOL_TIERS: dict[str, ToolTier] = {
    SHOW_CREATORS: "read",
    CALCULATE_BUDGET: "read",
    CREATE_CAMPAIGN: "draft",
    REQUEST_PAYMENT: "commit",
    CONFIRM_LAUNCH: "commit",
}

# Maps each tool name to the Spring internal endpoint path it forwards to.
TOOL_TO_SPRING_PATH: dict[str, str] = {
    SHOW_CREATORS: "/internal/meera/show_creators",
    CALCULATE_BUDGET: "/internal/meera/calculate_budget",
    CREATE_CAMPAIGN: "/internal/meera/create_campaign",
    REQUEST_PAYMENT: "/internal/meera/request_payment",
    CONFIRM_LAUNCH: "/internal/meera/confirm_launch",
}

# Tools whose forward MUST carry Idempotency-Key = tool_use.id + workspace_id.
# Read tools are safe/idempotent by nature but we still key them for tracing.
IDEMPOTENT_REQUIRED_TOOLS: tuple[str, ...] = (CREATE_CAMPAIGN, REQUEST_PAYMENT, CONFIRM_LAUNCH)

TOOL_SCHEMAS: list[dict[str, Any]] = [
    {
        "name": SHOW_CREATORS,
        "description": "Render matched creators in the canvas. Read-only, no money.",
        "input_schema": {
            "type": "object",
            "properties": {
                "niche": {"type": "string"},
                "count": {"type": "integer", "minimum": 1, "maximum": 100},
                "city": {"type": "string", "description": "optional city filter"},
            },
            "required": ["niche", "count"],
        },
    },
    {
        "name": CALCULATE_BUDGET,
        "description": "Suggest pool + per-reel rate from product price and goal. Read-only, no money.",
        "input_schema": {
            "type": "object",
            "properties": {
                "product_price": {"type": "number"},
                "goal": {
                    "type": "string",
                    "enum": ["awareness", "launch", "conversion", "review"],
                },
            },
            "required": ["product_price", "goal"],
        },
    },
    {
        "name": CREATE_CAMPAIGN,
        "description": (
            "PROPOSE building a campaign from conversation intent. Spring "
            "re-derives all amounts and re-authorizes before creating anything."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "product_name": {"type": "string"},
                "campaign_type": {
                    "type": "string",
                    "enum": ["HYPE", "DIRECT", "REVIEW"],
                },
                "creator_count": {"type": "integer"},
                "creator_ids": {"type": "array", "items": {"type": "string"}},
            },
            "required": ["product_name", "campaign_type", "creator_count"],
        },
    },
    {
        "name": REQUEST_PAYMENT,
        "description": (
            "PROPOSE a payment. NEVER authoritative. Spring re-derives the amount "
            "server-side from persisted state; the human confirms in Razorpay. "
            "The AI-supplied amount (if any) is advisory display text only and is "
            "discarded by Spring."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "campaign_intent_id": {"type": "string"},
                "display_amount_hint": {
                    "type": "number",
                    "description": "for chat copy only; Spring ignores it for authorization",
                },
            },
            "required": ["campaign_intent_id"],
        },
    },
    {
        "name": CONFIRM_LAUNCH,
        "description": (
            "PROPOSE launching (send creator invites) once escrow is funded. "
            "Spring verifies escrow state before acting; produces a pending "
            "action the human confirms."
        ),
        "input_schema": {
            "type": "object",
            "properties": {"campaign_intent_id": {"type": "string"}},
            "required": ["campaign_intent_id"],
        },
    },
]


def get_tool_schemas() -> list[dict[str, Any]]:
    """Returns the tool schemas in the exact shape the Claude Messages API expects
    (`tools=[...]`). This is Block A content — identical for every brand.
    """
    return TOOL_SCHEMAS


def is_known_tool(name: str) -> bool:
    return name in TOOL_NAMES


def is_money_tool(name: str) -> bool:
    return TOOL_TIERS.get(name) == "commit"


# ---------------------------------------------------------------------------
# analyze_creator_content — Wave C task C2 (BrandSafetyScoreService epic).
#
# Deliberately NOT part of TOOL_SCHEMAS/TOOL_NAMES/TOOL_TO_SPRING_PATH above.
# Those structures are the Meera *chat* function-calling contract — the CI
# shared-schema diff-check compares them against Spring's `/internal/meera/*`
# executor DTOs (06-MEERA-PERMISSIONS-MATRIX.md), and this tool has nothing to
# do with that surface: it is never offered to Claude during a /chat turn,
# never forwarded to `/internal/meera/*`, and Claude never decides whether to
# call it. It is used exactly once, forced via `tool_choice`, by
# POST /internal/brand-safety (app/routes/brand_safety.py) purely to make
# Claude return structured JSON instead of prose for one batch of creator
# captions — a structured-output mechanism, not a function-calling tool in
# the agentic sense. Mixing it into TOOL_SCHEMAS would make Claude see it (and
# potentially call it) during ordinary Meera chat turns, and would break the
# Meera/Spring diff-check by introducing a tool with no Spring executor path.
# ---------------------------------------------------------------------------

ANALYZE_CREATOR_CONTENT = "analyze_creator_content"

# GARM (Global Alliance for Responsible Media) brand-safety floor framework
# categories. Fixed list — matches the framework's published taxonomy so the
# Java side's `garm_flags` column values are stable across prompt revisions.
GARM_CATEGORIES: tuple[str, ...] = (
    "adult_explicit_sexual_content",
    "arms_ammunition",
    "crime_harmful_acts_to_individuals",
    "death_injury_military_conflict",
    "hate_speech_acts_of_aggression",
    "illegal_drugs_tobacco_alcohol",
    "obscenity_profanity",
    "spam_or_harmful_content",
    "terrorism",
    "debated_sensitive_social_issues",
)

GARM_RISK_LEVELS: tuple[str, ...] = ("floor", "low", "medium", "high")
CONTENT_SENTIMENTS: tuple[str, ...] = ("positive", "neutral", "negative")

ANALYZE_CREATOR_CONTENT_SCHEMA: dict[str, Any] = {
    "name": ANALYZE_CREATOR_CONTENT,
    "description": (
        "Return a GARM brand-safety classification and sentiment for a batch "
        "of creator content items. One entry in `items` per input content "
        "item, same order as given, no omissions. Every category in "
        "`garm_flags` must be scored, even if the risk is 'floor' (no "
        "concern) — never omit a category to imply safety."
    ),
    "input_schema": {
        "type": "object",
        "properties": {
            "items": {
                "type": "array",
                "description": "One result per input content item, in the same order.",
                "items": {
                    "type": "object",
                    "properties": {
                        "content_id": {
                            "type": "string",
                            "description": "Echo back the caller-supplied id for this item, verbatim.",
                        },
                        "garm_flags": {
                            "type": "array",
                            "description": (
                                "Exactly one entry per GARM category "
                                f"({', '.join(GARM_CATEGORIES)}), every category present."
                            ),
                            "items": {
                                "type": "object",
                                "properties": {
                                    "category": {
                                        "type": "string",
                                        "enum": list(GARM_CATEGORIES),
                                    },
                                    "risk": {
                                        "type": "string",
                                        "enum": list(GARM_RISK_LEVELS),
                                    },
                                    "rationale": {
                                        "type": "string",
                                        "description": "One short sentence grounded in the caption text.",
                                    },
                                },
                                "required": ["category", "risk", "rationale"],
                            },
                        },
                        "content_sentiment": {
                            "type": "string",
                            "enum": list(CONTENT_SENTIMENTS),
                        },
                        "sentiment_score": {
                            "type": "number",
                            "description": "-1.0 (very negative) to 1.0 (very positive).",
                            "minimum": -1.0,
                            "maximum": 1.0,
                        },
                        "brand_safety_score": {
                            "type": "number",
                            "description": (
                                "0-100 aggregate safety score for this item, derived from the "
                                "highest-risk garm_flags entry (100 = no concern / floor risk "
                                "across every category, lower = higher aggregate risk)."
                            ),
                            "minimum": 0,
                            "maximum": 100,
                        },
                        "overall_rationale": {
                            "type": "string",
                            "description": "One or two sentences defending the aggregate score.",
                        },
                    },
                    "required": [
                        "content_id",
                        "garm_flags",
                        "content_sentiment",
                        "sentiment_score",
                        "brand_safety_score",
                        "overall_rationale",
                    ],
                },
            },
        },
        "required": ["items"],
    },
}


def get_analyze_creator_content_schema() -> dict[str, Any]:
    """Returns the analyze_creator_content tool schema in the shape the Claude
    Messages API expects for a single-tool `tools=[...]` call forced via
    `tool_choice`. See the module-level note above for why this is not part
    of `get_tool_schemas()`.
    """
    return ANALYZE_CREATOR_CONTENT_SCHEMA
