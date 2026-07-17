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
