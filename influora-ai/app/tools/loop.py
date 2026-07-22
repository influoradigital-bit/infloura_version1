"""Function-calling loop — Claude tool_use -> Spring `/internal/meera/*` -> repeat.

Hard rules encoded here (do not weaken):
- Python NEVER reads an `amount`-shaped field from Claude's tool input and
  forwards it as authoritative. Money tools (`request_payment`) carry
  `display_amount_hint` which is explicitly chat-copy only; Spring ignores it.
- Every money/state forward carries an idempotency key derived from
  `tool_use.id` + `workspace_id` (stable across retries of the same stream).
- If Spring returns a pending-human-confirm result, this loop surfaces it as
  canvas state via a `tool_result` event — it does NOT keep looping to "done"
  on its own.
- Loop-iteration cap (~6) to prevent runaway; on cap, return a graceful
  "let's confirm this step" message instead of erroring hard.
- Unknown tool names are rejected outright (never forwarded anywhere).
"""

from __future__ import annotations

import logging
from collections.abc import AsyncIterator
from dataclasses import dataclass
from typing import Any

from app.clients.spring import SpringCallError, SpringInternalClient, idempotency_key_for
from app.config import PROMPT_VERSION
from app.providers.claude import ClaudeProvider, ClaudeStreamEvent
from app.routes.analyze_site import perform_site_analysis
from app.tools.schemas import (
    IDEMPOTENT_REQUIRED_TOOLS,
    PRESENT_OPTIONS,
    TOOL_TO_SPRING_PATH,
    get_tool_schemas,
    is_known_tool,
    is_local_tool,
)

logger = logging.getLogger(__name__)

DEFAULT_MAX_ITERATIONS = 6


@dataclass
class LoopEvent:
    """Normalized event the SSE route translates into the wire protocol."""

    type: str  # "token" | "thinking" | "tool_start" | "tool_result" | "done" | "error"
    text: str | None = None
    tool_name: str | None = None
    tool_input: dict[str, Any] | None = None
    tool_status: str | None = None
    tool_result_data: Any = None
    finish_reason: str | None = None
    error_code: str | None = None
    fallback: str | None = None
    usage: dict[str, Any] | None = None


class UnknownToolError(Exception):
    pass


class ToolLoopCapExceeded(Exception):
    pass


def _accumulate_usage(
    total: dict[str, Any] | None, new: dict[str, Any] | None
) -> dict[str, Any] | None:
    """Sums two provider usage dicts field-by-field (missing/None treated as
    0), so a multi-tool-call turn bills for EVERY Claude call in the loop,
    not just the last one. Each loop iteration ends with its own `stream_turn`
    call, which yields its own "usage" event -- overwriting `final_usage` each
    time (the previous behavior) meant chat.py's `estimate_cost_usd` only ever
    saw the FINAL iteration's tokens, under-billing every turn that made two or
    more tool calls.

    Returns whichever side is present verbatim if the other is None (so the
    first iteration's usage passes through unchanged), and None only when
    neither side has seen a usage event yet.
    """
    if total is None:
        return new
    if new is None:
        return total
    return {key: (total.get(key) or 0) + (new.get(key) or 0) for key in set(total) | set(new)}


@dataclass
class ToolLoopContext:
    workspace_id: str
    onbehalf_jwt: str
    max_iterations: int = DEFAULT_MAX_ITERATIONS
    # Output-token ceiling per Claude turn in this loop. Kept small for Meera
    # chat (short, spoken replies) — see settings.meera_chat_max_tokens. A hard
    # backstop; the persona does the primary length shaping.
    max_tokens: int = 1024
    # NOTE: no service_token field here by design. The X-Meera-Service-Token
    # is minted fresh per outbound call inside SpringInternalClient
    # (app/auth/service_token_minter.py) — it is never threaded through from
    # the incoming request body. Forwarding a caller-supplied token would mean
    # trusting whatever the browser/attacker put in body.service_token as the
    # mesh credential, which defeats the point of a service-minted token.


async def run_tool_loop(
    *,
    claude: ClaudeProvider,
    spring: SpringInternalClient,
    system_blocks: list[dict[str, Any]],
    initial_messages: list[dict[str, Any]],
    ctx: ToolLoopContext,
    is_cancelled: Any = None,
) -> AsyncIterator[LoopEvent]:
    """Runs the full function-calling loop for one /chat turn, yielding
    normalized LoopEvents as they occur (text tokens, tool lifecycle, done/error).
    """
    messages = list(initial_messages)
    tools = get_tool_schemas()
    iterations = 0
    final_usage: dict[str, Any] | None = None

    while True:
        iterations += 1
        if iterations > ctx.max_iterations:
            yield LoopEvent(
                type="token",
                text="Let's confirm this step before we go further.",
            )
            # Carry the accumulated usage here too -- the normal-stop and
            # pending-human-confirm "done" events both pass usage=final_usage;
            # omitting it on this path meant a turn that hit the iteration cap
            # (i.e. burned the MOST tokens of any turn shape) billed $0.
            yield LoopEvent(type="done", finish_reason="iteration_cap", usage=final_usage)
            raise ToolLoopCapExceeded(f"exceeded {ctx.max_iterations} tool iterations")

        pending_tool_calls: list[dict[str, Any]] = []
        assistant_text_parts: list[str] = []

        async for event in claude.stream_turn(
            system_blocks=system_blocks,
            messages=messages,
            tools=tools,
            max_tokens=ctx.max_tokens,
            is_cancelled=is_cancelled,
        ):
            if is_cancelled and is_cancelled():
                yield LoopEvent(type="error", error_code="client_disconnected")
                return

            if event.type == "text":
                assistant_text_parts.append(event.text or "")
                yield LoopEvent(type="token", text=event.text)
            elif event.type == "tool_use":
                pending_tool_calls.append(
                    {
                        "id": event.tool_use_id,
                        "name": event.tool_name,
                        "input": event.tool_input or {},
                    }
                )
            elif event.type == "usage":
                final_usage = _accumulate_usage(final_usage, event.usage)

        assistant_text = "".join(assistant_text_parts)

        if not pending_tool_calls:
            # No tool calls this round -> final assistant text, we're done.
            if assistant_text:
                messages.append({"role": "assistant", "content": assistant_text})
            yield LoopEvent(type="done", finish_reason="stop", usage=final_usage)
            return

        # Append the assistant turn (text + tool_use blocks) before resolving tools.
        assistant_content: list[dict[str, Any]] = []
        if assistant_text:
            assistant_content.append({"type": "text", "text": assistant_text})
        for call in pending_tool_calls:
            assistant_content.append(
                {"type": "tool_use", "id": call["id"], "name": call["name"], "input": call["input"]}
            )
        messages.append({"role": "assistant", "content": assistant_content})

        tool_result_blocks: list[dict[str, Any]] = []
        should_stop_after_pending = False

        for call in pending_tool_calls:
            tool_name = call["name"]
            tool_input = call["input"]
            tool_use_id = call["id"]

            yield LoopEvent(type="tool_start", tool_name=tool_name, tool_input=_redact_tool_input(tool_input))

            if not is_known_tool(tool_name):
                logger.warning("rejected unknown tool call: %s", tool_name)
                result_payload = {"error": "unknown_tool", "message": f"tool {tool_name!r} is not defined"}
                tool_result_blocks.append(
                    {
                        "type": "tool_result",
                        "tool_use_id": tool_use_id,
                        "content": _safe_json(result_payload),
                        "is_error": True,
                    }
                )
                yield LoopEvent(type="tool_result", tool_name=tool_name, tool_status="error", tool_result_data=result_payload)
                continue

            # Local (Python-native) tools run in-process, NOT forwarded to Spring.
            # analyze_site: SSRF-guarded page fetch + Gemini classify so Meera
            # reads the brand's REAL product/price from a pasted URL instead of
            # guessing. Never raises — any failure comes back as a success:false
            # tool_result the model can react to ("couldn't read that page —
            # tell me the product and price?").
            if is_local_tool(tool_name):
                # present_options — display-only pattern: no fetch, no server
                # action. Echo the options straight back so the browser can
                # render tappable cards; the tool_result handed to Claude is a
                # minimal ack so the loop continues to her one-line reply.
                if tool_name == PRESENT_OPTIONS:
                    options_payload = dict(tool_input) if isinstance(tool_input, dict) else {}
                    result_payload = {"success": True, **options_payload}
                    tool_result_blocks.append(
                        {
                            "type": "tool_result",
                            "tool_use_id": tool_use_id,
                            "content": _safe_json({"success": True}),
                        }
                    )
                    # Phase 2 item 2.3 -- flywheel logging (Meera: Label-to-Moat build plan §2.3,
                    # Priya's Q3 ruling). present_options never reaches Spring via the normal
                    # tool-forward path (it's LOCAL), so this is the one dedicated write-back.
                    # Best-effort, never breaks the turn -- same try/except discipline as the
                    # analyze_site_result write-back below.
                    try:
                        await spring.log_interaction(
                            workspace_id=ctx.workspace_id,
                            event_type="OPTIONS_PRESENTED",
                            session_id=None,
                            tool_name=PRESENT_OPTIONS,
                            campaign_id=None,
                            prompt_version=PROMPT_VERSION,
                            onbehalf_jwt=ctx.onbehalf_jwt,
                        )
                    except Exception as exc:  # flywheel logging is best-effort, never breaks the turn
                        logger.warning(
                            "interaction-log write-back failed for workspace_id=%s"
                            " event=OPTIONS_PRESENTED: %s: %s",
                            ctx.workspace_id,
                            type(exc).__name__,
                            exc,
                        )
                    yield LoopEvent(
                        type="tool_result",
                        tool_name=tool_name,
                        tool_status="ok",
                        tool_result_data=result_payload,
                    )
                    continue

                url = tool_input.get("url") if isinstance(tool_input, dict) else None
                if not url:
                    result_payload = {
                        "success": False,
                        "error": {"code": "missing_url", "message": "no url provided"},
                    }
                    tool_result_blocks.append(
                        {"type": "tool_result", "tool_use_id": tool_use_id, "content": _safe_json(result_payload), "is_error": True}
                    )
                    yield LoopEvent(type="tool_result", tool_name=tool_name, tool_status="error", tool_result_data=result_payload)
                    continue
                try:
                    result_payload = await perform_site_analysis(url=url, workspace_id=ctx.workspace_id)
                except Exception as exc:  # never let a fetch/classify error break the turn
                    logger.warning("local tool %s failed: %s", tool_name, type(exc).__name__)
                    result_payload = {
                        "success": False,
                        "error": {"code": "analyze_failed", "message": "could not read that page"},
                    }

                # H-23 follow-up: analyze_site is local (never forwarded to Spring like every
                # other tool), so its result previously only ever fed back into this turn's
                # Claude reply -- BrandProfile.analysisStatus stayed PENDING forever for a
                # chat-pasted URL. Best-effort write-back onto the SAME persistence path the
                # form/onboarding flow uses (AnalyzeSiteTriggerService#applyChatResult); never
                # lets a callback failure break the chat reply that already happened.
                if isinstance(result_payload, dict) and not result_payload.get("spend_blocked"):
                    try:
                        await spring.persist_analyze_site_result(
                            workspace_id=ctx.workspace_id,
                            url=url,
                            success=bool(result_payload.get("success")),
                            data=result_payload.get("data"),
                            error=result_payload.get("error"),
                            onbehalf_jwt=ctx.onbehalf_jwt,
                        )
                    except Exception as exc:  # write-back is best-effort, never breaks the turn
                        logger.warning(
                            "analyze_site_result write-back failed for workspace_id=%s url=%s: %s: %s",
                            ctx.workspace_id,
                            url,
                            type(exc).__name__,
                            exc,
                        )

                is_err = not (isinstance(result_payload, dict) and result_payload.get("success"))
                tool_result_blocks.append(
                    {"type": "tool_result", "tool_use_id": tool_use_id, "content": _safe_json(result_payload), "is_error": is_err}
                )
                yield LoopEvent(
                    type="tool_result",
                    tool_name=tool_name,
                    tool_status="error" if is_err else "ok",
                    tool_result_data=result_payload,
                )
                continue

            path = TOOL_TO_SPRING_PATH[tool_name]
            idempotency_key = None
            if tool_name in IDEMPOTENT_REQUIRED_TOOLS:
                idempotency_key = idempotency_key_for(tool_use_id, ctx.workspace_id)

            # Forward the tool input AS-PROPOSED. Never treat any amount-shaped
            # field as authoritative -- Spring re-derives every amount itself.
            # We do not strip `display_amount_hint` (it's explicitly allowed as
            # chat-copy-only advisory text per the schema), we simply never read
            # it back out of Spring's response as if it were confirmed.
            forward_payload = dict(tool_input)
            forward_payload["workspace_id"] = ctx.workspace_id

            try:
                response = await spring.call_tool_endpoint(
                    tool_name=tool_name,
                    path=path,
                    payload=forward_payload,
                    onbehalf_jwt=ctx.onbehalf_jwt,
                    idempotency_key=idempotency_key,
                    allow_retry=tool_name not in IDEMPOTENT_REQUIRED_TOOLS,
                )
            except SpringCallError as exc:
                result_payload = {"error": exc.code, "message": exc.message}
                tool_result_blocks.append(
                    {
                        "type": "tool_result",
                        "tool_use_id": tool_use_id,
                        "content": _safe_json(result_payload),
                        "is_error": True,
                    }
                )
                yield LoopEvent(
                    type="tool_result",
                    tool_name=tool_name,
                    tool_status="error",
                    tool_result_data=result_payload,
                )
                continue

            data = response.data or {}
            action = data.get("action") if isinstance(data, dict) else None
            if action == "AWAIT_HUMAN_CONFIRM" or data.get("status") == "PENDING_CONFIRM":
                # Do NOT loop to "done" automatically -- surface as canvas state.
                should_stop_after_pending = True

            tool_result_blocks.append(
                {
                    "type": "tool_result",
                    "tool_use_id": tool_use_id,
                    "content": _safe_json(data),
                }
            )
            yield LoopEvent(type="tool_result", tool_name=tool_name, tool_status="ok", tool_result_data=data)

        messages.append({"role": "user", "content": tool_result_blocks})

        if should_stop_after_pending:
            yield LoopEvent(type="done", finish_reason="pending_human_confirm", usage=final_usage)
            return

        # Otherwise continue the loop so Claude can produce its next text/tool_use.


def _redact_tool_input(tool_input: dict[str, Any]) -> dict[str, Any]:
    """tool_start events go straight to the browser/canvas, so this is not a log
    redaction — it's just a defensive copy. No secrets ever live in tool input.
    """
    return dict(tool_input)


def _safe_json(data: Any) -> str:
    import json as _json

    return _json.dumps(data, default=str)
