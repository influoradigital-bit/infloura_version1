"""The Meera **creator** tool JSON schemas — SINGLE SOURCE OF TRUTH for the
CREATOR audience (T-MEERA-CREATOR-PHASE-B SPEC.md §7.1).

Deliberately a SEPARATE module from `app/tools/schemas.py`. That file holds the
BRAND set, whose `TOOL_SCHEMAS` / `TOOL_NAMES` / `TOOL_TO_SPRING_PATH` names are
diffed against Java's `MeeraToolName` enum by
`.github/workflows/schema-check.yml`, and `tests/eval/test_prompt_injection.py`
asserts `set(TOOL_NAMES)` is EXACTLY the six brand tools. Adding a creator tool
to any of those three structures breaks the CI diff and that test at the same
time. The creator side gets its own enum on the Java side too
(`CreatorToolName`, §3.2), so neither gate sees these names.

PHASE: B0 ships the six tools below. B1 appends `send_routine_reply`,
`rank_open_campaigns` and `draft_application` (§14.5.a) — do not add them here
before their Spring executors exist. This module is inert until Spring's
`CreatorContextResponse.tools_enabled` actually names a tool: the assembler
degrades to an empty tool set when that list is absent or empty (§7.2), so
shipping a schema early costs nothing, while shipping one whose endpoint does
not exist would have the model propose a call that can only 404.

SCHEMA SHAPE IS A HARD CONSTRAINT, NOT A STYLE (MEMORY
reference_anthropic_tool_schema_no_combinators): Anthropic's tool
`input_schema` is a RESTRICTED JSON-Schema subset. A single `anyOf` / `oneOf` /
`allOf` / `not` / `$ref` / `if`-`then`-`else` anywhere at any depth returns HTTP
400 for the ENTIRE tools payload — which takes down every Meera turn including
brand ones, and surfaces in the logs as a generic "provider_timeout" rather
than a schema error. So: every node carries a single concrete `type`, every
array declares `items`, and normalization happens in the Spring executor, never
in the schema. `tests/tools/test_tool_schema_anthropic_valid.py` validates
every schema in this module on every PR.
"""

from __future__ import annotations

from typing import Any

GET_MY_DEALS = "get_my_deals"
GET_BRIEF = "get_brief"
ESTIMATE_MY_RATE = "estimate_my_rate"
GET_MY_METRICS = "get_my_metrics"
CHECK_DEAL_RISKS = "check_deal_risks"
DRAFT_REPLY = "draft_reply"
GET_TODAYS_TOPICS = "get_todays_topics"

# B0 order matches §3.1's tool catalogue. B1 appends send_routine_reply,
# rank_open_campaigns and draft_application to the END of this tuple.
CREATOR_TOOL_NAMES: tuple[str, ...] = (
    GET_MY_DEALS,
    GET_BRIEF,
    ESTIMATE_MY_RATE,
    GET_MY_METRICS,
    CHECK_DEAL_RISKS,
    DRAFT_REPLY,
    GET_TODAYS_TOPICS,
)

# Every creator tool forwards to `/internal/meera/creator/<name>`
# (`CreatorMeeraToolController`, §3.4) — a different prefix from the brand
# tools' `/internal/meera/<name>`, which is what keeps the two on-behalf scope
# ladders separate.
CREATOR_TOOL_TO_SPRING_PATH: dict[str, str] = {
    name: f"/internal/meera/creator/{name}" for name in CREATOR_TOOL_NAMES
}

# Creator tools whose forward MUST carry an Idempotency-Key and must NOT be
# retried on a transport failure.
#
# EMPTY IN B0, and that is correct rather than an oversight: its only member is
# `send_routine_reply` (the one commit-like creator tool — it sends to a brand),
# which is B1. The constant is declared now so `app/tools/loop.py`'s two
# idempotency sites are widened ONCE, in the same change that widens
# `is_known_tool`, instead of being revisited in B1. The site that is easy to
# miss is the `allow_retry=` argument, and missing it makes a commit-like tool
# silently retryable.
CREATOR_IDEMPOTENT_REQUIRED_TOOLS: tuple[str, ...] = ()

# Creator tools whose forward must NOT be retried on a transport failure, but
# do NOT need an Idempotency-Key -- deliberately a SEPARATE set from
# CREATOR_IDEMPOTENT_REQUIRED_TOOLS above, which conflates "no retry" with
# "needs a dedupe key" for money/state tools. get_brief is a plain read for
# every other purpose, but F1 HIGH (Kavya, Wave U last-call review; Priya
# ruling RULINGS-U-0917.md Addition B) found that retrying it is not safe:
# on a deal's FIRST read, CreatorBriefService.ensurePlatformBrief commits a
# raw NEW row and then makes a blocking AI call
# (CreatorBriefService.analysisBudget(), 30s by default). A retried request
# during that window used to land on the SAME just-committed NEW row and get
# back an untouched "clean" brief with no extraction, no flags and no quote --
# Spring's own fix (BRIEF_STILL_READING / re-analysis on read) closes the
# WRONG-ANSWER half of that, but retrying here still wastes a second full
# timeout wait for no benefit, and removing the retry is what actually
# prevents a creator ever seeing the wrong answer rather than merely a slower
# right one. So: no retry, ever, whatever Spring returns. See
# `get_brief_read` in app/config.py's ProviderTimeouts for the matching
# longer timeout this same fix needs.
CREATOR_NO_RETRY_TOOLS: tuple[str, ...] = (GET_BRIEF,)

# `QuoteDeliverableType` (§4.1) — a PRICING vocabulary, deliberately distinct
# from the persisted `com.influora.domain.enums.DeliverableType` platform names.
# The Spring executor parses case-insensitively and maps the platform names
# (INSTAGRAM_REEL -> REEL etc.), so an imperfect guess here is normalized there
# rather than rejected.
_DELIVERABLE_TYPES = [
    "REEL",
    "STATIC_POST",
    "STORY_SET",
    "SHORT",
    "YT_INTEGRATION",
    "YT_DEDICATED",
    "UGC_ONLY",
    "OTHER",
]

# `RateAddOns` (§4.2) — usage and exclusivity riders, priced as a percentage of
# the package.
_ADD_ON_CODES = [
    "REPOST_30D",
    "PAID_ADS_QUARTER",
    "WHITELISTING",
    "PERPETUITY",
    "EXCLUSIVITY_30D",
]

# `DealDtos.DealTermsDto`'s seven components, camelCase on the wire exactly as
# Java serialises them. `usageChannels` are `UsageChannel` names and
# `exclusivityScope` an `ExclusivityScope` name; `exclusivityBrands` is
# meaningful only when the scope is NAMED_BRANDS.
_DEAL_TERMS_SCHEMA: dict[str, Any] = {
    "type": "object",
    "description": (
        "Structured usage and exclusivity terms for this draft. What they mean depends on "
        "`kind`. For REPLY or DECLINE they REPORT the brand's ask: copy it exactly as the "
        "brand stated it, include a key only when the brief or the brand actually said it, "
        "and never invent a term. For COUNTER they are what the CREATOR is now proposing, "
        "so they are expected to differ from the brand's ask — set each key to the term "
        "being asked for (a 12-month window in place of a perpetual grant, say), not to the "
        "brand's original. Either way, state only terms that were asked for or are being "
        "proposed on purpose; never fill a key in to look complete."
    ),
    "properties": {
        "usageMonths": {
            "type": "integer",
            "minimum": 0,
            "description": "Months the brand may use the content. Omit if perpetual or unstated.",
        },
        "usagePerpetual": {
            "type": "boolean",
            "description": "True only when the brand asked for perpetual usage in so many words.",
        },
        "usageChannels": {
            "type": "array",
            "description": "Where the brand may run the content.",
            "items": {
                "type": "string",
                "enum": ["ORGANIC", "PAID_ADS", "WHITELISTING", "WEBSITE", "OFFLINE"],
            },
        },
        "exclusivityDays": {
            "type": "integer",
            "minimum": 0,
            "description": "Days the creator may not work with the excluded brands or category.",
        },
        "exclusivityScope": {
            "type": "string",
            "enum": ["NONE", "NAMED_BRANDS", "CATEGORY"],
        },
        "exclusivityBrands": {
            "type": "array",
            "description": "Named competitor brands. Only when exclusivityScope is NAMED_BRANDS.",
            "items": {"type": "string"},
        },
        "maxRevisions": {
            "type": "integer",
            "minimum": 0,
            "description": "Revision rounds included in the price.",
        },
    },
}

CREATOR_TOOL_SCHEMAS: list[dict[str, Any]] = [
    {
        "name": GET_MY_DEALS,
        "description": (
            "Read this creator's own deals — status, amounts and the next action on each. "
            "Call it before answering ANY question about a deal, a payment, or what they have "
            "earned; never answer one from memory or from earlier in the chat. Read-only: it "
            "changes nothing and sends nothing to a brand."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "status": {
                    "type": "string",
                    "enum": ["active", "completed", "all"],
                    "description": "Which deals to fetch. Defaults to active when omitted.",
                },
                "limit": {
                    "type": "integer",
                    "minimum": 1,
                    "maximum": 25,
                    "description": "How many deals to return, newest first.",
                },
            },
            "required": [],
        },
    },
    {
        "name": GET_BRIEF,
        "description": (
            "Read one brief — a brief the creator pasted, or the brief behind a platform deal — "
            "with its extraction, its risk flags and its quote. Call it before you summarise a "
            "brief, discuss its terms, or draft any reply about it. Pass exactly one of "
            "brief_id or deal_id: a deal's brief_id comes from an earlier get_my_deals result; "
            "a pasted brief's id can also arrive in the creator's own message, since she may "
            "open a chat about a brief she just pasted and it names the id for you. Passing "
            "both ids is refused, and passing neither is refused. Read-only. "
            "Two different error codes are possible and they are NOT the same thing. "
            "error=BRIEF_STILL_READING means the first analysis is likely still running: tell the "
            "creator so in one short sentence and do NOT call get_brief again this turn — wait "
            "for her next message before trying again. error=BRIEF_ANALYSIS_UNAVAILABLE means the "
            "brief could not be read (it was dismissed before analysis finished, or its stored "
            "record is unreadable) and will NOT heal on retry: do NOT call get_brief again this "
            "turn either, but tell the creator it could not be read and suggest she paste it "
            "again. Never treat that second case as a clean brief — do not summarise terms it "
            "never returned or call the deal safe. When the result comes back with "
            "extraction_source=FALLBACK, tell the creator the summary was read by rule-based "
            "extraction, not by you, and that it may miss things a full reading would catch — "
            "never present a FALLBACK summary as your own reading of the brief."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "brief_id": {
                    "type": "string",
                    "description": (
                        "Id of a pasted brief. Comes from an earlier tool result, or from a "
                        "brief id the creator herself gave in this chat."
                    ),
                },
                "deal_id": {
                    "type": "string",
                    "description": "Id of a platform deal, to read the brief attached to it.",
                },
            },
            "required": [],
        },
    },
    {
        "name": ESTIMATE_MY_RATE,
        "description": (
            "Price a package of deliverables for this creator. Call it before you say ANY rate, "
            "range or counter figure — never price a package yourself. Quote the returned lines "
            "and total exactly as given. The returned anchor is the opening ask and is the only "
            "figure that may go in front of a brand. "
            "The returned floor is the creator's private minimum: NEVER write a floor, or any "
            "number that reveals one, into text addressed to a brand, and never tell a brand a "
            "floor exists. When the quote's provenance says it is a benchmark, say it is a "
            "benchmark estimate rather than what creators like them have actually closed at, "
            "before you say any number."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "deliverables": {
                    "type": "array",
                    "description": "The package to price. One entry per deliverable type.",
                    "items": {
                        "type": "object",
                        "properties": {
                            "type": {
                                "type": "string",
                                "enum": _DELIVERABLE_TYPES,
                                "description": "Pricing type of the deliverable. Use OTHER when nothing else fits.",
                            },
                            "qty": {
                                "type": "integer",
                                "minimum": 1,
                                "description": "How many of this deliverable.",
                            },
                        },
                        "required": ["type", "qty"],
                    },
                },
                "add_ons": {
                    "type": "array",
                    "description": (
                        "Usage or exclusivity riders the brand asked for. Add a code only when the "
                        "brief or the brand actually asked for it — each one raises the price."
                    ),
                    "items": {"type": "string", "enum": _ADD_ON_CODES},
                },
                "brand_budget_inr": {
                    "type": "number",
                    "description": (
                        "The brand's stated budget, when they named one. Context only: it never "
                        "lowers the creator's ask and never changes the floor."
                    ),
                },
                "deal_id": {"type": "string", "description": "Price against this platform deal."},
                "brief_id": {"type": "string", "description": "Price against this pasted brief."},
            },
            "required": ["deliverables"],
        },
    },
    {
        "name": GET_MY_METRICS,
        "description": (
            "Read this creator's latest verified metrics — followers, reach and engagement — as "
            "pre-formatted strings. Call it before quoting any audience number. Quote what comes "
            "back verbatim; never recompute, re-round or estimate a metric. Read-only."
        ),
        "input_schema": {
            "type": "object",
            "properties": {},
            "required": [],
        },
    },
    {
        "name": CHECK_DEAL_RISKS,
        "description": (
            "Run the risk rules over one deal or brief and get back reason codes. Call it on any "
            "brief or offer BEFORE drafting a reply to it, and before telling the creator an "
            "offer looks fine. Explain each flag in one plain sentence, then the action. A flag "
            "the result marks as not dismissible must be mentioned before anything else you say. "
            "Read-only."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "deal_id": {"type": "string", "description": "Check this platform deal."},
                "brief_id": {"type": "string", "description": "Check this pasted brief."},
            },
            "required": [],
        },
    },
    {
        "name": DRAFT_REPLY,
        "description": (
            "Write a reply, counter or decline to a brand and SAVE IT AS A DRAFT. This never "
            "sends: the creator taps to send. Tell them it is drafted and ready to send, and "
            "stop — never claim you sent it. Run check_deal_risks first, and estimate_my_rate "
            "first when the draft names a number. "
            "Rules for `text`, which a brand will read: never put the creator's floor, minimum, "
            "or any wording that reveals one into it; quote the package, never a per-unit price; "
            "never write as if the creator typed it personally, and never deny being Meera if a "
            "brand asks. "
            "`proposed_amount` must be at or above the floor returned by estimate_my_rate. The "
            "only exception is when the creator has said in this conversation that the deal is "
            "strategic and worth taking below their floor — then set strategic_override true and "
            "put their own reason in strategic_reason. Never set it on your own judgement."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "kind": {
                    "type": "string",
                    "enum": ["REPLY", "COUNTER", "DECLINE"],
                    "description": (
                        "REPLY for a plain answer, COUNTER when proposing terms or a number, "
                        "DECLINE to turn it down."
                    ),
                },
                "text": {
                    "type": "string",
                    "description": (
                        "The draft the brand will read, in the creator's voice and language. "
                        "No floors, no per-unit prices, no internal reasoning."
                    ),
                },
                "deal_id": {"type": "string", "description": "The platform deal this reply answers."},
                "brief_id": {"type": "string", "description": "The pasted brief this reply answers."},
                "proposed_amount": {
                    "type": "number",
                    "description": (
                        "The package figure being proposed, for a COUNTER. Use the quote's anchor. "
                        "At or above the floor unless the creator called the deal strategic."
                    ),
                },
                "deal_terms": _DEAL_TERMS_SCHEMA,
                "strategic_override": {
                    "type": "boolean",
                    "description": (
                        "True ONLY when the creator has said this deal is strategic and they "
                        "accept going below their floor. Never set this yourself."
                    ),
                },
                "strategic_reason": {
                    "type": "string",
                    "description": "The creator's own words for why the deal is worth taking below their floor.",
                },
            },
            "required": ["kind", "text"],
        },
    },
    {
        "name": GET_TODAYS_TOPICS,
        "description": (
            "Read the topics Influora's editorial team has put live TODAY for this creator's "
            "categories, and today's date. Call it before suggesting what to post today or "
            "planning a week: it is the only way you can know today's date, and the only source "
            "of what is current. The result's `today` and `weekday` are the server's, in Indian "
            "time -- use them and never your own idea of the date. A topic is a topic, not a "
            "fact: present it as something going around, add no numbers to it, and use the "
            "angles as written. An empty list means nothing is live for them today, which is "
            "normal -- fall back to the content knowledge. Read-only."
        ),
        "input_schema": {
            "type": "object",
            "properties": {},
            "required": [],
        },
    },
]


def all_creator_tool_schemas() -> list[dict[str, Any]]:
    """EVERY schema in this module, unfiltered, in `CREATOR_TOOL_NAMES` order.

    The permissive read, under a name that says so. It exists for the CI
    schema-validity guard (`tests/tools/test_tool_schema_anthropic_valid.py`),
    which must enumerate the full catalogue at COLLECTION time with no feature
    flag in the way, and for tests that want the whole set.

    NEVER call this on a live turn. What a turn may offer is decided by
    `get_creator_tool_schemas(tools_enabled)`, and reaching for "all of them"
    on a request path is how a creator gets handed a tool their approval level
    never granted.
    """
    return list(CREATOR_TOOL_SCHEMAS)


def get_creator_tool_schemas(tools_enabled: list[str] | None) -> list[dict[str, Any]]:
    """The creator tool schemas this turn may offer, in `CREATOR_TOOL_NAMES` order.

    `tools_enabled` is `CreatorContextResponse.tools_enabled` — the names
    Spring's `CreatorToolScopes` granted for this creator's approval level.

    ABSENT MEANS NOTHING, NOT EVERYTHING. `None` and `[]` both return no tools.
    `None` used to mean "no filter, every schema in this module", so a caller
    that had no list — an older Spring, a context fetch that failed open, a new
    call site — got all six creator tools by omission. `assemble_prompt` is
    safe only because it normalises to `[]` before calling; that made the
    filter's own fail-open shape invisible rather than absent. The explicit
    permissive read now has its own name, `all_creator_tool_schemas`.

    An unknown name in `tools_enabled` is ignored rather than raising: Spring
    may ship a tool name before this module has the schema (or the reverse),
    and a deploy-order skew must not take a creator's chat down.
    """
    if not tools_enabled:
        return []
    allowed = set(tools_enabled)
    return [schema for schema in CREATOR_TOOL_SCHEMAS if schema["name"] in allowed]


def is_creator_tool(name: str) -> bool:
    """True for a tool served by `/internal/meera/creator/*`. Read by
    `schemas.is_known_tool` (so the loop does not reject a creator tool as
    unknown) and by `loop.py`'s Spring path lookup — those two must always be
    widened together, see `CREATOR_TOOL_TO_SPRING_PATH`."""
    return name in CREATOR_TOOL_NAMES
