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
brand data lives in Block B/C, keyed by workspace_id. This module never lets
per-turn dynamic data leak backwards into Block A/B ordering.

Untrusted content (scraped site text, raw user chat) is wrapped in
`<untrusted_...>` delimiters so Claude treats it as data, never as instructions
(prompt-injection isolation, §4.6 of the security spec).
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

from app.prompt.persona import get_persona_block, stamp_prompt_version
from app.tools.schemas import get_tool_schemas

# Forbidden brand-context fields — defense in depth. Spring should never send
# these (field-level allow-list on Spring's side), but if one slips through we
# strip it here before it ever reaches a prompt.
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
    model treats it as data-not-instructions. Never nest raw content that
    contains the closing delimiter unescaped.
    """
    safe_content = content.replace(f"</untrusted_{label}>", "")
    return f"<untrusted_{label}>\n{safe_content}\n</untrusted_{label}>"


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


def build_block_b(brand_context: dict[str, Any]) -> dict[str, Any]:
    """Per-brand cached block, keyed by workspace_id. Stable within a session."""
    brand = _strip_forbidden_fields(brand_context.get("brand") or {})
    credit_state = brand_context.get("credit_state") or {}
    workspace_id = brand_context.get("workspace_id", "unknown")

    lines = [f"Brand context for workspace {workspace_id}:"]
    if "display_name" in brand:
        lines.append(f"- Name: {brand['display_name']}")
    if brand.get("niche_tags"):
        lines.append(f"- Niches: {', '.join(brand['niche_tags'])}")
    tone_dial = brand.get("tone_dial")
    if tone_dial:
        lines.append(f"- Tone dial: {tone_dial}")
    if brand.get("brand_color"):
        lines.append(f"- Brand color: {brand['brand_color']}")
    catalog = brand.get("product_catalog")
    if catalog:
        catalog_lines = ", ".join(
            f"{item.get('name', '?')} ({item.get('currency', 'INR')} {item.get('price', '?')})"
            for item in catalog
        )
        lines.append(f"- Product catalog: {catalog_lines}")
    if brand.get("past_campaign_summary"):
        lines.append(f"- Past campaigns: {brand['past_campaign_summary']}")
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


def cache_key_for(prompt_version: str, workspace_id: str, session_id: str | None) -> str:
    """Cache key is NEVER global — always (prompt_version, workspace_id, session_id)."""
    return f"{prompt_version}:{workspace_id}:{session_id or 'no-session'}"


def assemble_prompt(brand_context: dict[str, Any], session_id: str | None = None) -> AssembledPrompt:
    """Builds the full three-block prompt for a single /chat turn.

    `brand_context` is the sanitized object Spring sends (see §2 of the AI
    service spec) — already field-allow-listed on Spring's side; this function
    strips any forbidden fields again as defense-in-depth.
    """
    workspace_id = brand_context.get("workspace_id", "unknown")
    prompt_version = brand_context.get("prompt_version") or stamp_prompt_version()

    block_a = build_block_a()
    block_b = build_block_b(brand_context)
    messages = build_block_c_messages(brand_context.get("conversation") or [])

    return AssembledPrompt(
        system_blocks=[block_a, block_b],
        messages=messages,
        prompt_version=prompt_version,
        cache_key=cache_key_for(prompt_version, workspace_id, session_id),
    )


def wrap_untrusted_scrape(html_text: str) -> str:
    """Public helper for analyze_site.py to wrap scraped page text before it is
    ever placed into a prompt.
    """
    return _wrap_untrusted("scraped_site", html_text)
