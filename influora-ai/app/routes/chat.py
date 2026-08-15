"""POST /chat — the main Meera turn. SSE (text/event-stream).

Auth: Spring-issued service token OR scoped stream token (scope in
{"service", "chat:stream"}). Every failure -> 401/403 before any provider call
(app.auth.service_token handles this).

Event protocol (SSE `event:` types):
    token        {"text": "..."}                          incremental assistant text
    thinking     {"step": "...", "done": false}            T3 log line
    tool_start   {"name": "...", "input": {...}}           canvas glue
    tool_result  {"name": "...", "status": "ok"|"error",
                  "data": {...}}                           stage advance + live-canvas payload
    prompt_meta  {"prompt_version": "..."}
    done         {"finish_reason": "stop"|"pending_human_confirm"|"iteration_cap"|"empty_response"}
    error        {"code": "...", "fallback": "text"}

"empty_response" (P1 BLANK TURN fix, F2/F7, 2026-07-24): the tool loop
exhausted its one bounded retry and still produced no usable text or tool
call. A `token` event carrying an honest, in-persona fallback message IS
streamed before this `done` (Priya's no-silence rail), so `final_text` below
is non-empty -- but nothing is persisted and the send-time charge is
refunded, never billed, for this finish_reason. See FALLBACK_FINISH_REASONS.

Heartbeat `: ping` comment every ~15s keeps the connection warm through proxies.
On client disconnect, in-flight provider calls are cancelled (no wasted tokens).
On final assistant text, the turn is persisted back to Spring via the signed
callback client (Idempotency-Key = turn_id, where turn_id is the stream
token's own VERIFIED `messageId` claim -- never a client-supplied value, see
Kabir red-team FAIL 2 fix below).

Money-path (Wave 2 round 2, Kabir red-team FAILs #1/#2): influora-api now
charges the AI credit at SEND time (before this route is ever reached), keyed
on that same server-minted messageId/turn_id -- this route's job is only to
tell Spring whether the turn's provider call actually SUCCEEDED (call
persist_assistant_message, no charge) or FAILED (call release_turn_credit,
refund the send-time charge). A plain CLIENT DISCONNECT is neither -- the
client already received the streamed tokens, so the charge correctly stays
and neither call is made.
"""

from __future__ import annotations

import asyncio
import json
import logging
import time
import uuid
from decimal import Decimal
from typing import Any

from fastapi import APIRouter, Header, Request, status
from fastapi.responses import StreamingResponse

from app.auth.service_token import AuthError, auth_error_to_http, verify_token_async
from app.clients.spring import SpringCallError, SpringInternalClient
from app.config import CLAUDE_MODEL, get_settings
from app.costs.gate import check_spend_gate
from app.costs.pricing import estimate_cost_usd
from app.costs.spend_tracker import get_workspace_total_today, record_spend, release
from app.prompt.assembler import assemble_prompt
from app.providers.claude import ClaudeProvider
from app.security.redaction import log_event, shape_of
from app.tools.loop import ToolLoopCapExceeded, ToolLoopContext, run_tool_loop

logger = logging.getLogger(__name__)
router = APIRouter()

_claude_provider: ClaudeProvider | None = None
_spring_client: SpringInternalClient | None = None

# P1 BLANK TURN fix (F7, MUST ship in the same commit as loop.py's F2). These
# are finish_reason values GENERATED SERVER-SIDE by run_tool_loop itself to
# mean "the user got an honest apology streamed to them, not a real answer" --
# never a client-influenced value, so this is not spoofable from the request
# body. F2 makes `final_text` non-empty on these turns (the fallback message
# IS streamed text), which would otherwise silently flip them from the
# existing refund path into the persist-and-charge path below. Checked before
# the `if final_text:` branch for exactly that reason.
# F-16: "truncated_tool_use" joins this set. A turn that narrated ("Scanning
# creators in Mumbai...") and then truncated mid `create_campaign` used to be
# reported as finish_reason="stop", so the narration was persisted as the
# assistant's answer and the charge was kept — the brand was told a campaign
# was being built when nothing was built and nothing was logged as a failure.
FALLBACK_FINISH_REASONS = {"empty_response", "truncated_tool_use"}


class _StreamExhausted:
    """Sentinel: the tool-loop generator is finished. F-14 — the heartbeat path
    wraps `__anext__()` in a task, and StopAsyncIteration does not survive a
    task boundary cleanly, so exhaustion is signalled by value instead."""

    __slots__ = ()


_STREAM_EXHAUSTED = _StreamExhausted()


async def _next_event(loop_iter: Any) -> Any:
    """One step of the tool loop, or the exhaustion sentinel. Kept as a task so
    a heartbeat timeout can leave it running instead of cancelling it (F-14)."""
    try:
        return await loop_iter.__anext__()
    except StopAsyncIteration:
        return _STREAM_EXHAUSTED


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


async def _fetch_brand_context(
    *,
    spring: SpringInternalClient,
    workspace_id: str,
    onbehalf_jwt: str,
    request_id: str,
    conversation: list[dict[str, Any]],
) -> dict[str, Any]:
    """Server-sources Block B via `POST /internal/meera/context` (Priya A2).

    Builds the shape `app.prompt.assembler.assemble_prompt` expects (a nested
    `brand` dict, separate `credit_state`, `audience`) from Spring's flat
    `ContextResponse` payload (`display_name`/`tone_dial`/`brand_color`/
    `niche_tags`/`product_catalog`/`template_digest`/`past_campaign_summary`/
    `credit_state` -- the W1c seam-fixed canonical vocabulary). Deliberately
    does NOT read `brand` or `prompt_version` from the client body anywhere --
    those keys are ignored entirely now that Block B is server-sourced.

    Never raises: a context-fetch failure (Spring down, brand not yet
    analyzed, transient network error) degrades to an EMPTY Block B rather
    than 500ing the whole chat turn -- Meera still replies, just without brand
    personalization for that one turn.
    """
    audience = "BRAND"
    try:
        response = await spring.get_meera_context(
            workspace_id=workspace_id, audience=audience, onbehalf_jwt=onbehalf_jwt
        )
        context_data = response.data if isinstance(response.data, dict) else {}
    except SpringCallError as exc:
        log_event(
            logger, logging.WARNING, "meera_context_fetch_failed",
            workspace_id=workspace_id, request_id=request_id,
            fields={"error_code": exc.code, "status_code": exc.status_code},
        )
        context_data = {}
    except Exception as exc:  # noqa: BLE001 - network/timeout/unexpected; never 500 the turn
        log_event(
            logger, logging.WARNING, "meera_context_fetch_failed",
            workspace_id=workspace_id, request_id=request_id,
            fields={"error_type": type(exc).__name__},
        )
        context_data = {}

    brand_fields = {
        "display_name": context_data.get("display_name"),
        "niche_tags": context_data.get("niche_tags"),
        "tone_dial": context_data.get("tone_dial"),
        "brand_color": context_data.get("brand_color"),
        "product_catalog": context_data.get("product_catalog"),
        "template_digest": context_data.get("template_digest"),
        "past_campaign_summary": context_data.get("past_campaign_summary"),
        # Phase 2 moat: carry the server-built outcome digest into the prompt.
        # Without this the digest is generated + serialized by Spring but never
        # reaches build_block_b (brand.get("outcome_digest") was always None).
        "outcome_digest": context_data.get("outcome_digest"),
    }
    # Only carry keys Spring actually sent -- build_block_b's `if "display_name"
    # in brand` presence check must not see a None-valued key just because the
    # context fetch failed or Spring omitted an optional field.
    brand = {k: v for k, v in brand_fields.items() if v is not None}

    return {
        "workspace_id": workspace_id,
        "audience": audience,
        "brand": brand,
        "credit_state": context_data.get("credit_state") or {},
        "conversation": conversation,
    }


@router.post("/chat")
async def chat(request: Request, authorization: str | None = Header(default=None)):
    request_id = str(uuid.uuid4())
    body = await request.json()
    workspace_id = body.get("workspace_id")
    onbehalf_jwt = body.get("onbehalf_jwt") or _strip_bearer(authorization)

    if not workspace_id:
        return _error_response(400, "missing_workspace_id", "workspace_id is required")

    try:
        verified = await verify_token_async(
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

    # SECURITY FIX (Kabir red-team FAIL 2): the write-back/refund idempotency key is now the
    # STREAM TOKEN'S OWN VERIFIED `messageId` claim -- server-minted by
    # StreamTokenService.mint(...) at send-time (influora-api) -- never the client-supplied
    # `body["turn_id"]`. The old code (`turn_id=body.get("turn_id", request_id)` below) let a
    # client pin `turn_id` to a constant across many turns: turn 1 charged, turns 2..N replayed
    # through Spring's `AlreadyCompletedException` short-circuit with `creditsCharged=0` --
    # unlimited turns for one credit. A genuine `chat:stream` token always carries `messageId`
    # (see verified.conversation_id's non-None check just above -- both claims are minted
    # together); the service-token path (Spring-proxied /chat, no per-turn binding, no
    # messageId) falls back to the body-supplied value, matching its pre-existing behavior --
    # that path isn't reachable by an untrusted browser client at all.
    turn_id = verified.claims.get("messageId") or body.get("turn_id", request_id)

    settings = get_settings()

    # P2-17 spend gate: checked before any provider call, mirrors the
    # auth-first pattern above ("any failure -> structured error, zero
    # provider calls, no token spend"). chat.py is one of the 3 in-scope
    # P2-17 call sites (H-25) — previously this route imported nothing from
    # app.costs, so the gate never actually enforced the ceiling/kill-switch
    # on the highest-traffic route.
    # F-05: ONE gate check here covers a tool loop of up to
    # `tool_loop_max_iterations` separate Claude turns, and the old gate held
    # nothing between the check and the recorded spend. Reserve a pessimistic
    # estimate for the whole loop so concurrent turns see this one's in-flight
    # cost instead of every caller reading the same stale total.
    reserve_usd = (
        Decimal(str(settings.ai_reservation_per_call_usd)) * settings.tool_loop_max_iterations
        if settings.ai_reservation_per_call_usd
        else None
    )
    gate = await check_spend_gate(
        workspace_id=workspace_id,
        reserve_usd=reserve_usd,
        # The tool loop can legitimately run for minutes; the 60s default would
        # expire the hold mid-turn and reopen the F-05 race for exactly the slow
        # tail it exists to cover. (spend_tracker documented this contract; the
        # caller never honoured it until Priya's review caught the mismatch.)
        reserve_ttl_seconds=settings.ai_reservation_chat_ttl_seconds,
    )
    if not gate.allowed:
        log_event(
            logger, logging.WARNING, "chat_turn_blocked_spend_gate",
            workspace_id=workspace_id, request_id=request_id,
            fields={"error_code": gate.error_code},
        )
        return _error_response(
            503, gate.error_code or "AI_SPEND_BLOCKED", gate.error_message or "spend gate blocked this call"
        )
    spend_reservation = gate.reservation

    log_event(
        logger,
        logging.INFO,
        "chat_turn_started",
        workspace_id=workspace_id,
        request_id=request_id,
        fields={"conversation_len": shape_of(body.get("conversation"))},
    )

    # Platform-AI Phase 1 (W2a, Priya A2): Block B is now server-sourced from
    # Spring's POST /internal/meera/context, never from the browser body.
    # Any client-supplied `brand`/`prompt_version` key in `body` is IGNORED
    # below -- `brand_context` is built ONLY from this fetch + server-derived
    # fields, so a spoofed client body cannot inject or override the system
    # prompt's brand block. On fetch failure, degrade gracefully: empty Block
    # B, log, never 500 the turn (voice/text chat must still work while a
    # brand's profile is still analyzing, or Spring is briefly unavailable).
    brand_context = await _fetch_brand_context(
        spring=_get_spring(),
        workspace_id=workspace_id,
        onbehalf_jwt=onbehalf_jwt,
        request_id=request_id,
        conversation=body.get("conversation") or [],
    )
    prompt = assemble_prompt(brand_context, session_id=body.get("conversation_id"))
    claude = _get_claude()
    spring = _get_spring()
    loop_ctx = ToolLoopContext(
        workspace_id=workspace_id,
        onbehalf_jwt=onbehalf_jwt,
        max_iterations=settings.tool_loop_max_iterations,
        max_tokens=settings.meera_chat_max_tokens,
        max_tokens_retry=settings.meera_chat_max_tokens_retry,
    )

    async def event_stream():
        # F-05: the reservation is settled or released inside this generator.
        nonlocal spend_reservation
        disconnected = False
        # SECURITY FIX (Kabir FAILs #1/#2): tracks a genuine PROVIDER failure, as opposed to a
        # client disconnect -- the two are deliberately never conflated. Only `provider_failed`
        # (or an empty final reply, checked further down) triggers `release_turn_credit`;
        # `disconnected` NEVER does, since the client already received the tokens it read before
        # hanging up (that's exactly what used to let a disconnect-farm dodge the charge).
        provider_failed = False
        # SECURITY FIX (Kabir red-team residual, LOW): the refund decision used to key
        # ONLY on `final_text`, but a turn can run a data-returning tool (creator lists,
        # budget calcs) whose `tool_result` payload IS streamed to the client below while
        # Claude emits zero narration text -- that turn used to fall into the empty-reply
        # refund path even though the client received real, usable output. Track whether
        # any tool_result was actually DELIVERED (status "ok", data sent) this turn; a
        # "error" tool_result (unknown tool / Spring call failure) carries nothing usable
        # and must not suppress a refund on its own.
        tool_result_delivered = False

        def is_cancelled() -> bool:
            return disconnected

        yield sse_event("prompt_meta", {"prompt_version": prompt.prompt_version})

        last_heartbeat = time.monotonic()
        # F-14: the in-flight `__anext__()` task, kept alive across heartbeats.
        next_event_task: asyncio.Future[Any] | None = None
        assistant_text_accum: list[str] = []
        final_usage: dict[str, Any] | None = None
        finish_reason = "stop"
        # P1 BLANK TURN fix (F6 observability): the real Anthropic stop_reason
        # for the terminal turn, carried through loop.py/claude.py. Previously
        # fetched and discarded at claude.py's message_stop -- logged into
        # ai_spend below so a max_tokens rate per route is finally visible.
        stop_reason: str | None = None

        async def release_charge(reason: str) -> None:
            """Refunds this turn's send-time charge after a genuine provider FAILURE. Never
            called on a client disconnect -- see `provider_failed` note above. Failure to reach
            Spring here is logged, not raised -- a refund that never lands is a lost credit for
            the brand (bad), not a security hole (release is idempotent + guarded server-side, so
            there's nothing unsafe about retrying it later if this call is ever made retryable).
            """
            try:
                await spring.release_turn_credit(
                    conversation_id=body.get("conversation_id", ""),
                    turn_id=turn_id,
                    onbehalf_jwt=onbehalf_jwt,
                )
            except Exception as exc:  # noqa: BLE001 - a lost refund is a bad credit, not a hole
                log_event(
                    logger, logging.WARNING, "release_turn_credit_failed",
                    workspace_id=workspace_id, request_id=request_id,
                    fields={"error_type": type(exc).__name__, "reason": reason},
                )

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
                    # ── F-02 ────────────────────────────────────────────────
                    # Breaking out immediately threw away the provider's usage,
                    # so a client that POSTed /chat, read 200 tokens and hung up
                    # was billed by Anthropic for the full 8k+ cached prefix and
                    # everything generated, while get_global_total_today() never
                    # moved. A loop of connect-and-disconnect therefore drove
                    # unbounded REAL spend with the ceiling reading zero.
                    #
                    # `disconnected` is already True, so is_cancelled() now
                    # returns True and the loop flushes a partial-usage event on
                    # its next step. Give it one bounded chance to do so.
                    if next_event_task is None:
                        next_event_task = asyncio.ensure_future(_next_event(loop_iter))
                    drained, _still_running = await asyncio.wait(
                        {next_event_task}, timeout=settings.sse_heartbeat_seconds
                    )
                    if drained:
                        drained_event = next_event_task.result()
                        next_event_task = None
                        drained_usage = getattr(drained_event, "usage", None)
                        if drained_usage:
                            final_usage = drained_usage
                            stop_reason = getattr(drained_event, "stop_reason", None) or stop_reason
                    else:
                        next_event_task.cancel()
                    finish_reason = "client_disconnected"
                    break

                # ── F-14 ────────────────────────────────────────────────
                # This was `asyncio.wait_for(loop_iter.__anext__(), timeout=…)`.
                # On timeout `wait_for` CANCELS the task, which throws
                # CancelledError into run_tool_loop at its current await point
                # and terminates the generator. The next `__anext__()` then
                # raised StopAsyncIteration and the route `break`ed as if the
                # turn had ended normally: the client received `tool_start` and
                # then nothing — no result, no `done`, no `error` — while the
                # tokens burned went unrecorded and the refund fired on a turn
                # whose Spring write-back had already executed.
                #
                # The trigger is ordinary: a brand pastes a product URL, Claude
                # calls analyze_site, the fetch takes longer than one heartbeat
                # interval (the SSRF timeout alone is 15s per hop, plus Gemini's
                # 20s read). So the heartbeat could never be delivered during a
                # long provider wait — which is its entire purpose.
                #
                # `asyncio.wait` does not cancel on timeout: the pending task
                # survives the ping and is awaited again on the next pass.
                if next_event_task is None:
                    next_event_task = asyncio.ensure_future(_next_event(loop_iter))
                done_tasks, _pending = await asyncio.wait(
                    {next_event_task}, timeout=settings.sse_heartbeat_seconds
                )
                if not done_tasks:
                    last_heartbeat = time.monotonic()
                    yield ": ping\n\n"
                    continue
                event = next_event_task.result()
                next_event_task = None
                if event is _STREAM_EXHAUSTED:
                    break

                if event.type == "token":
                    assistant_text_accum.append(event.text or "")
                    yield sse_event("token", {"text": event.text})
                elif event.type == "thinking":
                    yield sse_event("thinking", {"step": event.text, "done": False})
                elif event.type == "tool_start":
                    yield sse_event("tool_start", {"name": event.tool_name, "input": event.tool_input})
                elif event.type == "tool_result":
                    # Live-canvas payload: tools/loop.py already carries the
                    # Spring tool-result payload on event.tool_result_data --
                    # this was dropped on the floor here, so the canvas never
                    # got real data (creator lists, budget calcs, etc.), only
                    # a name/status pair. Field name is "data" (not "payload"
                    # or "result") because useMeeraStream.ts on the frontend
                    # parses event.data specifically.
                    yield sse_event(
                        "tool_result",
                        {
                            "name": event.tool_name,
                            "status": event.tool_status,
                            "data": event.tool_result_data,
                        },
                    )
                    if event.tool_status == "ok":
                        tool_result_delivered = True
                elif event.type == "done":
                    finish_reason = event.finish_reason or "stop"
                    final_usage = event.usage
                    stop_reason = event.stop_reason
                    yield sse_event("done", {"finish_reason": finish_reason})
                    break
                elif event.type == "error":
                    # F-02: an error event can carry the usage the provider
                    # already billed (notably the client-disconnect flush).
                    if event.usage:
                        final_usage = event.usage
                    yield sse_event("error", {"code": event.error_code, "fallback": "text"})
                    # SECURITY FIX (Kabir red-team follow-up, item 6 / MUST-FIX #2): a
                    # "client_disconnected" error event (emitted by tools/loop.py's own
                    # is_cancelled() check at loop.py:138) must never drive a refund -- that's
                    # the `disconnected` path's job, and `disconnected` is only ever set by
                    # this route's own `request.is_disconnected()` poll above, not by loop
                    # events. Excluding it here is defense-in-depth for the FAIL-1 boundary: if
                    # the loop is ever refactored to yield this error_code through a different
                    # path, it still can't get conflated with a genuine provider failure.
                    if event.error_code != "client_disconnected":
                        provider_failed = True
                    break

                now = time.monotonic()
                if now - last_heartbeat >= settings.sse_heartbeat_seconds:
                    yield ": ping\n\n"
                    last_heartbeat = now

        except ToolLoopCapExceeded:
            yield sse_event("error", {"code": "tool_loop_cap", "fallback": "text"})
            provider_failed = True
        except Exception as exc:  # noqa: BLE001 - provider timeout / circuit open / unexpected
            log_event(
                logger,
                logging.ERROR,
                "chat_turn_failed",
                workspace_id=workspace_id,
                request_id=request_id,
                fields={"error_type": type(exc).__name__},
            )
            yield sse_event("error", {"code": "provider_timeout", "fallback": "text"})
            provider_failed = True

        # P2-17 §3.4: record real spend from this turn's usage, plus the
        # chat-only per-workspace $3/day soft cap (WARNING only, not
        # blocking — see budget-proposals/2026-07-12-ai-spend-ceiling-and-
        # killswitch.md §2/§3.5: chat is the only route with a reliable
        # workspace_id on every call today).
        # F-02: this block runs on EVERY exit path, including a client
        # disconnect — `final_usage` is now populated there too.
        if final_usage:
            try:
                cost_usd = estimate_cost_usd(CLAUDE_MODEL, final_usage)
                spend_today = await record_spend(
                    cost_usd, workspace_id, reservation=spend_reservation
                )
                spend_reservation = None  # F-05: settled
                log_event(
                    logger, logging.INFO, "ai_spend",
                    workspace_id=workspace_id, request_id=request_id,
                    fields={
                        "route": "chat",
                        "model": CLAUDE_MODEL,
                        "cost_usd": str(cost_usd),
                        "spend_today_usd": str(spend_today),
                        # P1 BLANK TURN fix (F6): stop_reason + output_tokens
                        # per turn -- previously invisible, which is why a 28%
                        # blank-turn rate on max_tokens cuts shipped unnoticed.
                        # A "max_tokens" rate per tool is the highest-value AI
                        # health metric this service was missing.
                        "stop_reason": stop_reason,
                        "output_tokens": final_usage.get("output_tokens"),
                        "finish_reason": finish_reason,
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

        # F-05: any path that did not settle the reservation must release it, or
        # this turn's hold sits on the ceiling until it expires.
        if spend_reservation is not None:
            await release(spend_reservation)
            spend_reservation = None

        if disconnected:
            # Kabir FAIL 1 fix: the client already received every `token` event it read before
            # disconnecting, so the send-time charge correctly STAYS -- deliberately no call to
            # release_charge() on this path, ever. This is the entire fix for the disconnect-farm
            # exploit (reading tokens then hanging up used to skip the write-back -- and with it
            # the ONLY place credit used to be charged -- for a free turn).
            log_event(
                logger, logging.INFO, "chat_turn_client_disconnected",
                workspace_id=workspace_id, request_id=request_id,
            )
            return

        # SECURITY FIX (Kabir red-team follow-up, item 6 / MUST-FIX #1, extended by the
        # residual LOW fix above): `final_text` is computed BEFORE branching on
        # `provider_failed`. The old code refunded on `provider_failed` unconditionally,
        # without checking whether usable output had already reached the client -- Claude
        # could stream a full answer (or deliver a tool_result payload with zero narration
        # text), the provider could then throw on a later loop iteration (`except Exception`
        # above sets `provider_failed = True`), and the client kept the output AND the turn
        # got refunded (free turn + wasted credit). Net rule: refund iff NEITHER assistant
        # text NOR a delivered tool_result reached the client, regardless of whether the
        # failure was clean or came after partial/complete output.
        final_text = "".join(assistant_text_accum)

        # P1 BLANK TURN fix (F7, MUST ship in the same commit as loop.py's F2):
        # loop.py's empty-turn guard streams an honest in-persona fallback
        # message on finish_reason="empty_response", so `final_text` below can
        # be non-empty even though nothing real was answered. `real_answer_text`
        # is deliberately narrower than "final_text is truthy" so that case
        # falls through to the tool_result_delivered / fallback-refund checks
        # below instead of silently being persisted and charged like a normal
        # reply -- that would be exactly the billing regression the guardrail
        # must not introduce.
        real_answer_text = final_text if finish_reason not in FALLBACK_FINISH_REASONS else ""

        if real_answer_text:
            # Usable text reached the client -- persist and keep the charge, exactly like the
            # clean-success path, even when provider_failed is True (e.g. the stream completed
            # but a later cleanup/usage-accounting step threw).
            try:
                await spring.persist_assistant_message(
                    conversation_id=body.get("conversation_id", ""),
                    content=real_answer_text,
                    metadata={
                        "prompt_version": prompt.prompt_version,
                        "token_usage": final_usage,
                        "request_id": request_id,
                    },
                    turn_id=turn_id,
                    onbehalf_jwt=onbehalf_jwt,
                )
            except Exception as exc:  # noqa: BLE001 - persistence failure must not break the stream
                log_event(
                    logger, logging.WARNING, "persist_assistant_message_failed",
                    workspace_id=workspace_id, request_id=request_id,
                    fields={"error_type": type(exc).__name__},
                )
            return

        if tool_result_delivered:
            # SECURITY FIX (Kabir red-team residual, LOW): no assistant text was produced,
            # but a tool_result payload (creator list, budget calc, etc.) WAS streamed to the
            # client this turn -- that's usable output the brand paid for, even with zero
            # narration. There's no assistant text to persist, but the charge must not be
            # refunded either: keep it, same as the text-delivered path above.
            #
            # This check is deliberately BEFORE the empty_response fallback check below: a
            # multi-iteration turn can deliver a real tool_result in an earlier iteration and
            # then go empty on a later narration-only iteration (finish_reason="empty_response").
            # The brand already received genuine paid output that turn -- refunding anyway would
            # make it free. Real delivered output always wins over a later empty tail.
            log_event(
                logger, logging.INFO, "chat_turn_tool_result_only_charge_kept",
                workspace_id=workspace_id, request_id=request_id,
            )
            return

        if finish_reason in FALLBACK_FINISH_REASONS:
            # Neither real text nor a delivered tool_result reached the client this turn, and
            # loop.py's empty-turn guard already exhausted its one retry and streamed the honest
            # fallback message -- nothing to persist, and the send-time charge must be refunded,
            # not billed for a non-answer. Logged as its own WARN event (not folded into the
            # generic "empty_reply" reason below) so a `meera_empty_turn` rate is directly
            # queryable -- the whole reason this defect shipped invisibly for a day.
            log_event(
                logger, logging.WARNING, "meera_empty_turn",
                workspace_id=workspace_id, request_id=request_id,
                fields={"finish_reason": finish_reason, "stop_reason": stop_reason},
            )
            await release_charge("empty_reply_fallback")
            return

        # NOTHING usable reached the client -- neither assistant text nor a delivered
        # tool_result: either a genuine provider failure with nothing streamed yet, or a
        # "done" event that produced an empty reply. Both refund the send-time charge --
        # there is nothing to persist either way.
        await release_charge("provider_failure" if provider_failed else "empty_reply")

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
