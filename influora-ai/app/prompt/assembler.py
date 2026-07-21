"""Three-block prompt assembly for Anthropic prompt caching (the ~65% cost lever).

Block A — STABLE PREFIX (cache_control: ephemeral): persona + 5 tool schemas +
          global rails. Tenant-agnostic. Identical for every brand -> maximum
          cache hit rate.
Block B — PER-BRAND CACHED (cache_control: ephemeral): brand profile, tone dial,
          product catalog, past campaign summary, credit_state summary. Stable
          within a session -> caches across a conversation's ~16 turns. Keyed
          per workspace_id.
Block C — VOLATILE SUFFIX (uncached): conversation history + newest user turn.

Cache-key discipline (Kabir guardrail #4): Block A carries ZERO brand data. All
brand data lives in Block B/C, keyed by (prompt_version, audience, workspace_id,
session_id) — see `cache_key_for`. `audience` is part of the key (Priya's W2
cross-cutting lock #1) so a future CREATOR-audience turn can never collide
with a BRAND turn on the same workspace_id/session_id. This module never lets
per-turn dynamic data leak backwards into Block A/B ordering.

Untrusted content (scraped site text, raw user chat, brand profile text) is
neutralized via `app.prompt.untrusted` and wrapped in `<untrusted_...>`
delimiters so Claude treats it as data, never as instructions (prompt-injection
isolation, §4.6 of the security spec). Both layers are required: delimiters
alone are bypassable by a payload that forges a close tag; neutralization alone
leaves untrusted text undelimited. This module owns NO local copy of that
logic — see `app/prompt/untrusted.py`.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

from app.prompt.persona import get_persona_block, stamp_prompt_version
from app.prompt.untrusted import neutralize_angle_brackets, wrap_untrusted
from app.tools.schemas import get_tool_schemas

# Forbidden brand-context fields — defense in depth. Spring should never send
# these (field-level allow-list on Spring's side), but if one slips through we
# strip it here before it ever reaches a prompt.
# Canonical snake_case field set for POST /internal/meera/context's response
# body (`MeeraContextDtos.ContextResponse` on the Spring side, the W1c seam-
# fixed vocabulary this module already consumes). This is the LIVE half of
# the Python<->Java schema-drift check (.github/workflows/schema-check.yml,
# "Check context payload field names") -- Wave 1 shipped that step as a
# pinned-string guard against a hardcoded EXPECTED list because Python wasn't
# wired to the endpoint yet; now that it is (W2), CI extracts THIS constant
# and diffs it against Java's @JsonProperty set instead of a copy-pasted
# string, so the two sides can never silently drift again. Keep in sync with
# `_fetch_brand_context` in app/routes/chat.py (the one place that reads
# these keys off the live response) and with MeeraContextDtos.ContextResponse.
CONTEXT_PAYLOAD_FIELDS: tuple[str, ...] = (
    "analysis_status",
    "brand_aesthetic",
    "brand_color",
    "competitor_urls",
    "credit_state",
    "display_name",
    "industry",
    "niche_tags",
    "past_campaign_summary",
    "product_catalog",
    "template_digest",
    "tone_dial",
    "website_url",
    "workspace_id",
)

_FORBIDDEN_BRAND_FIELDS = {
    "pan",
    "kyc",
    "bank_account",
    "bank",
    "upi",
    "upi_id",
    "creator_pii",
    "wallet_balance",
    "wallet_balances",
    "escrow_internals",
    "address",
    "addresses",
    "full_name",
    "phone",
    "email",
}


@dataclass(frozen=True)
class AssembledPrompt:
    system_blocks: list[dict[str, Any]]
    messages: list[dict[str, Any]]
    prompt_version: str
    cache_key: str


def _strip_forbidden_fields(brand: dict[str, Any]) -> dict[str, Any]:
    return {k: v for k, v in brand.items() if k.lower() not in _FORBIDDEN_BRAND_FIELDS}


def _wrap_untrusted(label: str, content: str) -> str:
    """Wrap untrusted data (scraped HTML text, raw pasted user content) so the
    model treats it as data-not-instructions.

    Delegates to `app.prompt.untrusted.wrap_untrusted` — the shared hardened
    implementation. The previous local version stripped only an exact-case
    `</untrusted_{label}>` substring, which was bypassable via case variation
    (`</UNTRUSTED_USER_MESSAGE>`) and via split-rejoin
    (`</untr</untrusted_user_message>usted_user_message>`). The shared helper
    neutralizes every `<`/`>` byte first, so no arrangement of the content can
    form a tag boundary at all. Do not reintroduce a second implementation here.
    """
    return wrap_untrusted(label, content)


def build_block_a() -> dict[str, Any]:
    """Stable, tenant-agnostic prefix: persona + tool schema summary + rails.
    Marked ephemeral for Anthropic prompt caching.
    """
    tool_names = ", ".join(t["name"] for t in get_tool_schemas())
    text = (
        get_persona_block()
        + "\n\nAvailable tools (see tool definitions): "
        + tool_names
        + "\n"
    )
    return {"type": "text", "text": text, "cache_control": {"type": "ephemeral"}}


def _safe(value: Any) -> str:
    """Neutralize one brand-supplied scalar before it is interpolated into a
    prompt block. Brand profile text (display name, tone dial, catalog entries,
    campaign summaries) is brand-authored — i.e. untrusted — and lands in a
    *system* block, the highest-trust role in the prompt. Angle-bracket
    neutralization means no field can emit a `<untrusted_*>`-shaped tag, forge
    a persona/tool section, or otherwise fabricate prompt structure.
    """
    return neutralize_angle_brackets(str(value))


def _render_template_digest(template_digest: Any) -> str | None:
    """Renders `template_digest[]` ({name, campaign_type, budget_band,
    key_requirements}) into 1-line-per-template Block-B text.

    W2 gate item (Ash P2-C, binding): `name` and `key_requirements` are
    brand-authored free text (a workspace's own SYSTEM/CUSTOM template row)
    reaching a *system* block — untrusted input exactly like the existing
    brand fields above. Spring does not neutralize prompt-injection at the
    data layer (Kabir's audit confirmed this is intentional and load-bearing
    on this wrapper existing here), so every free-text sub-field is passed
    through `_safe` individually, same as `product_catalog` entries.
    `campaign_type`/`budget_band` are also wrapped defensively even though
    they are normally server-computed enums/ranges — cheap insurance, no
    assumption that Spring's shape can never widen.
    """
    if not isinstance(template_digest, list) or not template_digest:
        return None
    entries = []
    for entry in template_digest:
        if not isinstance(entry, dict):
            continue
        name = _safe(entry.get("name", "?"))
        campaign_type = _safe(entry.get("campaign_type", "?"))
        budget_band = _safe(entry.get("budget_band", "?"))
        key_requirements = entry.get("key_requirements")
        line = f"{name} ({campaign_type}, {budget_band})"
        if key_requirements:
            line += f" — {_safe(key_requirements)}"
        entries.append(line)
    if not entries:
        return None
    return "- Campaign templates available: " + "; ".join(entries)


def _render_past_campaign_summary(past_campaigns: Any) -> str | None:
    """Renders `past_campaign_summary[]` ({type, creator_count, funded}) into
    one Block-B line.

    Fixes the shape bug Meera flagged (assembler.py:136, W2 gate item): Spring
    sends a `List[PastCampaignEntry]`, not a single string — the previous
    `_safe(brand['past_campaign_summary'])` call `str()`-ified the whole list
    (Python's default list repr) instead of rendering it. `type` is
    brand-chosen (via campaign_type/template) free-ish text, so it goes
    through `_safe` too; `creator_count`/`funded` are plain ints/bools, safe
    to interpolate directly.
    """
    if not isinstance(past_campaigns, list) or not past_campaigns:
        return None
    entries = []
    for entry in past_campaigns:
        if not isinstance(entry, dict):
            continue
        campaign_type = _safe(entry.get("type", "?"))
        creator_count = entry.get("creator_count", "?")
        funded = "funded" if entry.get("funded") else "not funded"
        entries.append(f"{campaign_type} x{creator_count} ({funded})")
    if not entries:
        return None
    return "- Past campaigns: " + "; ".join(entries)


def build_block_b(brand_context: dict[str, Any]) -> dict[str, Any]:
    """Per-brand cached block, keyed by workspace_id. Stable within a session.

    Every interpolated brand value is passed through `_safe` — brand text is
    untrusted input reaching a system block (see `app/prompt/untrusted.py`).
    """
    brand = _strip_forbidden_fields(brand_context.get("brand") or {})
    credit_state = brand_context.get("credit_state") or {}
    workspace_id = brand_context.get("workspace_id", "unknown")

    lines = [f"Brand context for workspace {_safe(workspace_id)}:"]
    if "display_name" in brand:
        lines.append(f"- Name: {_safe(brand['display_name'])}")
    if brand.get("niche_tags"):
        lines.append(f"- Niches: {', '.join(_safe(tag) for tag in brand['niche_tags'])}")
    tone_dial = brand.get("tone_dial")
    if tone_dial:
        lines.append(f"- Tone dial: {_safe(tone_dial)}")
    if brand.get("brand_color"):
        lines.append(f"- Brand color: {_safe(brand['brand_color'])}")
    catalog = brand.get("product_catalog")
    if catalog:
        catalog_lines = ", ".join(
            f"{_safe(item.get('name', '?'))} "
            f"({_safe(item.get('currency', 'INR'))} {_safe(item.get('price', '?'))})"
            for item in catalog
        )
        lines.append(f"- Product catalog: {catalog_lines}")
    template_digest_line = _render_template_digest(brand.get("template_digest"))
    if template_digest_line:
        lines.append(template_digest_line)
    past_campaign_line = _render_past_campaign_summary(brand.get("past_campaign_summary"))
    if past_campaign_line:
        lines.append(past_campaign_line)
    if credit_state:
        lines.append(
            f"- Credit state: mode={credit_state.get('mode', 'unknown')}, "
            f"remaining={credit_state.get('credits_remaining', 'unknown')}"
        )

    text = "\n".join(lines)
    return {"type": "text", "text": text, "cache_control": {"type": "ephemeral"}}


def _tool_call_content_block(tool_call: dict[str, Any]) -> dict[str, Any]:
    """Translates one persisted OpenAI-style tool call (`{id, name, input}` or
    `{id, function: {name, arguments}}`) into an Anthropic `tool_use` content
    block. Anthropic's Messages API has no top-level `tool_calls` field —
    assistant tool invocations MUST be `content` blocks of type `tool_use`, or
    the API silently ignores them and the subsequent `tool_result` block has no
    matching `tool_use` to pair with, which Anthropic rejects outright.
    """
    function = tool_call.get("function") if isinstance(tool_call.get("function"), dict) else None
    name = tool_call.get("name") or (function.get("name") if function else None) or ""
    tool_input = tool_call.get("input")
    if tool_input is None and function is not None:
        raw_args = function.get("arguments")
        if isinstance(raw_args, str):
            import json as _json

            try:
                tool_input = _json.loads(raw_args) if raw_args else {}
            except ValueError:
                tool_input = {}
        elif isinstance(raw_args, dict):
            tool_input = raw_args
    return {
        "type": "tool_use",
        "id": tool_call.get("id") or tool_call.get("tool_call_id") or "",
        "name": name,
        "input": tool_input or {},
    }


def _tool_result_content_block(turn: dict[str, Any]) -> dict[str, Any]:
    """Translates one persisted tool-result turn (role == "tool") into an
    Anthropic `tool_result` content block. These are wrapped in a `user`
    message per the Anthropic API (a `tool_result` block is never its own
    top-level message and never lives under `role: assistant`).
    """
    tool_use_id = turn.get("tool_call_id") or turn.get("tool_use_id") or ""
    content = turn.get("content", "")
    result_block: dict[str, Any] = {
        "type": "tool_result",
        "tool_use_id": tool_use_id,
        "content": content if isinstance(content, str) else str(content),
    }
    if turn.get("is_error"):
        result_block["is_error"] = True
    return result_block


def build_block_c_messages(conversation: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Volatile suffix: full conversation history, replayed each turn (stateless
    service — Spring sends the history, Python holds none of it between calls).
    User turns are wrapped as untrusted data. Assistant tool-call history and
    tool-result turns are translated into Anthropic's native `tool_use` /
    `tool_result` content-block shapes (see `_tool_call_content_block` /
    `_tool_result_content_block`) — never passed through as an OpenAI-style
    top-level `tool_calls` array, which Anthropic does not recognize.
    """
    messages: list[dict[str, Any]] = []
    for turn in conversation:
        role = turn.get("role")
        content = turn.get("content", "")
        if role == "user":
            wrapped = _wrap_untrusted("user_message", content)
            messages.append({"role": "user", "content": wrapped})
        elif role == "assistant":
            tool_calls = turn.get("tool_calls")
            if tool_calls:
                assistant_content: list[dict[str, Any]] = []
                if content:
                    assistant_content.append({"type": "text", "text": content})
                for tool_call in tool_calls:
                    assistant_content.append(_tool_call_content_block(tool_call))
                messages.append({"role": "assistant", "content": assistant_content})
            else:
                messages.append({"role": "assistant", "content": content})
        elif role in ("tool", "TOOL"):
            # Persisted tool-result turn — becomes a `user` message carrying a
            # `tool_result` block, paired by `tool_use_id` with the preceding
            # assistant `tool_use` block above.
            messages.append({"role": "user", "content": [_tool_result_content_block(turn)]})
        else:
            # Unknown role — treat conservatively as untrusted user data.
            messages.append({"role": "user", "content": _wrap_untrusted("unknown_role", str(content))})
    return messages


def cache_key_for(prompt_version: str, audience: str, workspace_id: str, session_id: str | None) -> str:
    """Cache key is NEVER global — always (prompt_version, audience, workspace_id,
    session_id).

    `audience` (Priya's cross-cutting lock #1, W2 HARD gate) is part of the key
    because Block B is now server-sourced per workspace+audience: the moment a
    future CREATOR-audience turn shares this cache path (Phase 3, A4), a brand
    turn and a creator turn for the same workspace_id+session_id must never
    collide on the same cached Block B. This is the info barrier's last line,
    not just a correctness nicety — Kabir's W2 re-audit checks this exact
    signature.
    """
    return f"{prompt_version}:{audience}:{workspace_id}:{session_id or 'no-session'}"


def assemble_prompt(brand_context: dict[str, Any], session_id: str | None = None) -> AssembledPrompt:
    """Builds the full three-block prompt for a single /chat turn.

    `brand_context` is the sanitized object Spring sends (see §2 of the AI
    service spec) — already field-allow-listed on Spring's side; this function
    strips any forbidden fields again as defense-in-depth. `audience` defaults
    to "BRAND" (Phase 1 is BRAND-only; A4/CREATOR is Phase 3) but is read from
    `brand_context` so callers can pass it explicitly once CREATOR ships.
    """
    workspace_id = brand_context.get("workspace_id", "unknown")
    audience = brand_context.get("audience") or "BRAND"
    prompt_version = brand_context.get("prompt_version") or stamp_prompt_version()

    block_a = build_block_a()
    block_b = build_block_b(brand_context)
    messages = build_block_c_messages(brand_context.get("conversation") or [])

    return AssembledPrompt(
        system_blocks=[block_a, block_b],
        messages=messages,
        prompt_version=prompt_version,
        cache_key=cache_key_for(prompt_version, audience, workspace_id, session_id),
    )


def wrap_untrusted_scrape(html_text: str) -> str:
    """Public helper for analyze_site.py to wrap scraped page text before it is
    ever placed into a prompt.
    """
    return _wrap_untrusted("scraped_site", html_text)
