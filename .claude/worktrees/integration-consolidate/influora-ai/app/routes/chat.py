"""POST /chat — the main Meera turn. SSE (text/event-stream).

Auth: Spring-issued service token OR scoped stream token (scope in
{"service", "chat:stream"}). Every failure -> 401/403 before any provider call
(app.auth.service_token handles this).

Event protocol (SSE `event:` types):
    token        {"text": "..."}                          incremental assistant text
    thinking     {"step": "...", "done": false}            T3 log line
    tool_start   {"name": "...", "input": {...}}           canvas glue
    tool_result  {"name": "...", "status": "ok"|"error"}   stage advance
    prompt_meta  {"prompt_version": "..."}
    done         {"finish_reason": "stop"|"pending_human_confirm"|"iteration_cap"}
    error        {"code": "...", "fallback": "text"}

Heartbeat `: ping` comment every ~15s keeps the connection warm through proxies.
On client disconnect, in-flight provider calls are cancelled (no wasted tokens).
On final assistant text, the turn is persisted back to Spring via the signed
callback client (Idempotency-Key = turn_id).
"""

from __future__ import annotations

import asyncio
import json
import logging
import time
import uuid
from typing import Any

from fastapi import APIRouter, Header, Request, status
from fastapi.responses import StreamingResponse

from app.auth.service_token import AuthError, auth_error_to_http, verify_token
from app.clients.spring import SpringInternalClient
from app.config import CLAUDE_MODEL, get_settings
from app.costs.gate import check_spend_gate
from app.costs.pricing import estimate_cost_usd
from app.costs.spend_tracker import get_workspace_total_today, record_spend
from app.prompt.assembler import assemble_prompt
from app.providers.claude import ClaudeProvider
from app.security.redaction import log_event, shape_of
from app.tools.loop import ToolLoopCapExceeded, ToolLoopContext, run_tool_loop

logger = logging.getLogger(__name__)
router = APIRouter()

_claude_provider: ClaudeProvider | None = None
_spring_client: SpringInternalClient | None = None


def _get_claude() -> ClaudeProvider:
    global _claude_provider
    if _claude_provider is None:
        _claude_provider = ClaudeProvider()
    return _claude_provider


def _get_spring() -> SpringInternalClient:
    global _spring_client
    if _spring_client is None:
        _spring_client = SpringInternalClient()
    return _spring_client


def sse_event(event: str, data: dict[str, Any]) -> str:
    return f"event: {event}\ndata: {json.dumps(data, default=str)}\n\n"


@router.post("/chat")
async def chat(request: Request, authorization: str | None = Header(default=None)):
    request_id = str(uuid.uuid4())
    body = await request.json()
    workspace_id = body.get("workspace_id")
    onbehalf_jwt = body.get("onbehalf_jwt") or _strip_bearer(authorization)

    if not workspace_id:
        return _error_response(400, "missing_workspace_id", "workspace_id is required")

    try:
        verified = verify_token(
            _bearer_from(authorization, body),
            endpoint="chat",
            body_workspace_id=workspace_id,
        )
    except AuthError as exc:
        raise auth_error_to_http(exc) from exc

    # Scoped stream tokens are minted single-use and bound to one
    # conversationId (04-AI-SERVICE-SPEC §1.1 / 02-API-CONTRACT-BRAND §218,
    # §226). A service token has no conversation binding (conversation_id is
    # None) and legitimately spans conversations, so only enforce the match
    # when the verified token actually carries a conversation_id.
    if verified.conversation_id is not None:
        body_conversation_id = body.get("conversation_id")
        if verified.conversation_id != body_conversation_id:
            raise auth_error_to_http(
                AuthError(
                    status.HTTP_403_FORBIDDEN,
                    "conversation_mismatch",
                    "stream token's conversation_id does not match request body conversation_id",
                )
            )

    settings = get_settings()

    # P2-17 spend gate: checked before any provider call, mirrors the
    # auth-first pattern above ("any failure -> structured error, zero
    # provider calls, no token spend"). chat.py is one of the 3 in-scope
    # P2-17 call sites (H-25) — previously this route imported nothing from
    # app.costs, so the gate never actually enforced the ceiling/kill-switch
    # on the highest-traffic route.
    gate = await check_spend_gate()
    if not gate.allowed:
        log_event(
            logger, logging.WARNING, "chat_turn_blocked_spend_gate",
            workspace_id=workspace_id, request_id=request_id,
            fields={"error_code": gate.error_code},
        )
        return _error_response(503, gate.error_code, gate.error_message)

    log_event(
        logger,
        logging.INFO,
        "chat_turn_started",
        workspace_id=workspace_id,
        request_id=request_id,
        fields={"conversation_len": shape_of(body.get("conversation"))},
    )

    prompt = assemble_prompt(body, session_id=body.get("conversation_id"))
    claude = _get_claude()
    spring = _get_spring()
    loop_ctx = ToolLoopContext(
        workspace_id=workspace_id,
        onbehalf_jwt=onbehalf_jwt,
        max_iterations=settings.tool_loop_max_iterations,
    )

    async def event_stream():
        disconnected = False

        def is_cancelled() -> bool:
            return disconnected

        yield sse_event("prompt_meta", {"prompt_version": prompt.prompt_version})

        last_heartbeat = time.monotonic()
        assistant_text_accum: list[str] = []
        final_usage: dict[str, Any] | None = None
        finish_reason = "stop"

        try:
            loop_iter = run_tool_loop(
                claude=claude,
                spring=spring,
                system_blocks=prompt.system_blocks,
                initial_messages=prompt.messages,
                ctx=loop_ctx,
                is_cancelled=is_cancelled,
            ).__aiter__()

            while True:
                if await request.is_disconnected():
                    disconnected = True
                    break

                try:
                    event = await asyncio.wait_for(
                        loop_iter.__anext__(), timeout=settings.sse_heartbeat_seconds
                    )
                except asyncio.TimeoutError:
                    yield ": ping\n\n"
                    continue
                except StopAsyncIteration:
                    break

                if event.type == "token":
                    assistant_text_accum.append(event.text or "")
                    yield sse_event("token", {"text": event.text})
                elif event.type == "thinking":
                    yield sse_event("thinking", {"step": event.text, "done": False})
                elif event.type == "tool_start":
                    yield sse_event("tool_start", {"name": event.tool_name, "input": event.tool_input})
                elif event.type == "tool_result":
                    yield sse_event(
                        "tool_result", {"name": event.tool_name, "status": event.tool_status}
                    )
                elif event.type == "done":
                    finish_reason = event.finish_reason or "stop"
                    final_usage = event.usage
                    yield sse_event("done", {"finish_reason": finish_reason})
                    break
                elif event.type == "error":
                    yield sse_event("error", {"code": event.error_code, "fallback": "text"})
                    break

                now = time.monotonic()
                if now - last_heartbeat >= settings.sse_heartbeat_seconds:
                    yield ": ping\n\n"
                    last_heartbeat = now

        except ToolLoopCapExceeded:
            yield sse_event("error", {"code": "tool_loop_cap", "fallback": "text"})
        except Exception as exc:  # provider timeout / circuit open / unexpected
            log_event(
                logger,
                logging.ERROR,
                "chat_turn_failed",
                workspace_id=workspace_id,
                request_id=request_id,
                fields={"error_type": type(exc).__name__},
            )
            yield sse_event("error", {"code": "provider_timeout", "fallback": "text"})
            return

        # P2-17 §3.4: record real spend from this turn's usage, plus the
        # chat-only per-workspace $3/day soft cap (WARNING only, not
        # blocking — see budget-proposals/2026-07-12-ai-spend-ceiling-and-
        # killswitch.md §2/§3.5: chat is the only route with a reliable
        # workspace_id on every call today).
        if final_usage:
            try:
                cost_usd = estimate_cost_usd(CLAUDE_MODEL, final_usage)
                spend_today = await record_spend(cost_usd, workspace_id)
                log_event(
                    logger, logging.INFO, "ai_spend",
                    workspace_id=workspace_id, request_id=request_id,
                    fields={
                        "route": "chat",
                        "model": CLAUDE_MODEL,
                        "cost_usd": str(cost_usd),
                        "spend_today_usd": str(spend_today),
                    },
                )
                workspace_total = await get_workspace_total_today(workspace_id)
                if workspace_total >= settings.ai_workspace_daily_soft_cap_usd:
                    log_event(
                        logger, logging.WARNING, "ai_spend_workspace_soft_cap_exceeded",
                        workspace_id=workspace_id, request_id=request_id,
                        fields={
                            "workspace_spend_today_usd": str(workspace_total),
                            "soft_cap_usd": settings.ai_workspace_daily_soft_cap_usd,
                        },
                    )
            except ValueError as exc:
                log_event(
                    logger, logging.ERROR, "ai_spend_pricing_error",
                    workspace_id=workspace_id, request_id=request_id,
                    fields={"error": str(exc)},
                )

        if disconnected:
            log_event(
                logger, logging.INFO, "chat_turn_client_disconnected",
                workspace_id=workspace_id, request_id=request_id,
            )
            return

        final_text = "".join(assistant_text_accum)
        if final_text:
            try:
                await spring.persist_assistant_message(
                    conversation_id=body.get("conversation_id", ""),
                    content=final_text,
                    metadata={
                        "prompt_version": prompt.prompt_version,
                        "token_usage": final_usage,
                        "request_id": request_id,
                    },
                    turn_id=body.get("turn_id", request_id),
                    onbehalf_jwt=onbehalf_jwt,
                )
            except Exception as exc:  # persistence failure shouldn't break the stream
                log_event(
                    logger, logging.WARNING, "persist_assistant_message_failed",
                    workspace_id=workspace_id, request_id=request_id,
                    fields={"error_type": type(exc).__name__},
                )

    return StreamingResponse(
        event_stream(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "X-Accel-Buffering": "no",
            "Connection": "keep-alive",
        },
    )


def _bearer_from(authorization: str | None, body: dict[str, Any]) -> str:
    if authorization and authorization.lower().startswith("bearer "):
        return authorization.split(" ", 1)[1].strip()
    # Direct browser->Python SSE may present the scoped stream token as a query
    # param or body field rather than a header; support both per §4 of the spec.
    return body.get("stream_token", "")


def _strip_bearer(authorization: str | None) -> str:
    """Returns the bare token from an `Authorization` header, stripping the
    `Bearer ` scheme prefix. Spring's OnBehalfAuthResolver calls
    jwtService.parseAccessToken() directly on this value — forwarding the
    scheme-prefixed header verbatim produces a malformed-JWT 401 on every
    call that falls back to the header instead of an explicit onbehalf_jwt
    body field."""
    if not authorization:
        return ""
    if authorization.lower().startswith("bearer "):
        return authorization.split(" ", 1)[1].strip()
    return authorization.strip()


def _error_response(status_code: int, code: str, message: str):
    from fastapi.responses import JSONResponse

    return JSONResponse(status_code=status_code, content={"error": {"code": code, "message": message}})
