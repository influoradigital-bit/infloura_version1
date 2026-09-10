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

from app.clients.spring import (
    SpringCallError,
    SpringInternalClient,
    idempotency_key_for,
)
from app.config import PROMPT_VERSION
from app.providers.claude import ClaudeProvider
from app.routes.analyze_site import perform_site_analysis
from app.tools.creator_schemas import (
    CREATOR_IDEMPOTENT_REQUIRED_TOOLS,
    CREATOR_TOOL_TO_SPRING_PATH,
)
from app.tools.schemas import (
    IDEMPOTENT_REQUIRED_TOOLS,
    PRESENT_OPTIONS,
    TOOL_TO_SPRING_PATH,
    get_tool_schemas,
    is_known_tool,
    is_local_tool,
    is_money_tool,
)

logger = logging.getLogger(__name__)

DEFAULT_MAX_ITERATIONS = 6

# P1 BLANK TURN fix (2026-07-24, wiki/ai-review/meera-blank-turn-ai-review.md
# F2). Priya's rail: the user always gets a real reply or an honest,
# recoverable message -- never silence. Persona-consistent (spoken, one short
# sentence, exactly ONE thing to do). This text is READ ALOUD by TTS (Sarvam),
# so it deliberately carries no punctuation tricks, emoji, or symbols. Lives
# in code, not persona.py -- PROMPT_VERSION does not need bumping for this.
EMPTY_TURN_FALLBACK = "Sorry, I lost my train of thought there. Say that again?"

# ME-2 (BrandF.md §115): schemas.py's get_tool_schemas() no longer offers
# request_payment/confirm_launch, so this should be unreachable in the ordinary
# case -- but if one is ever forwarded anyway (a future scope widening or
# schema edit that reintroduces one without updating schemas.py's exclusion —
# NOT replayed conversation history, which never carries a live tool_use back
# in, see schemas.py's get_tool_schemas docstring) and Spring rejects it on the
# on-behalf gate, this is the second layer: a fixed, persona-consistent,
# TTS-safe decline, same treatment as EMPTY_TURN_FALLBACK, instead of feeding
# the raw Spring error back into `messages` and letting Claude free-improvise a
# response around a 403 it has no way to act on productively.
MONEY_TOOL_SCOPE_DECLINE = (
    "I can't move money on my own here — that step needs your direct approval "
    "from the wallet or deal room."
)


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
    # P1 BLANK TURN fix (F6 observability): the real Anthropic `stop_reason`
    # for the turn that produced this "done" event (e.g. "end_turn",
    # "max_tokens"), so chat.py can log it instead of it being invisible --
    # the missing observability here is exactly what let a 28% blank-turn
    # rate ship unnoticed. None on paths with no underlying provider turn
    # (e.g. iteration_cap).
    stop_reason: str | None = None


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
    # P1 BLANK TURN fix (F2): wider ceiling for the ONE server-side retry that
    # fires when a turn was truncated mid tool_use and came back with no
    # usable text or tool calls. See settings.meera_chat_max_tokens_retry.
    max_tokens_retry: int = 2048
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
    tools: list[dict[str, Any]] | None = None,
) -> AsyncIterator[LoopEvent]:
    """Runs the full function-calling loop for one /chat turn, yielding
    normalized LoopEvents as they occur (text tokens, tool lifecycle, done/error).

    `tools` (Meera for Creators, A4 / SPEC.md §7.2): the tool schemas offered
    to Claude for this turn. `None` (every pre-existing caller) means the full
    BRAND set from `get_tool_schemas()`. CREATOR turns pass the creator set the
    creator's `tools_enabled` grants -- never a money tool, never a brand tool,
    and `[]` when nothing is granted. `assemble_prompt` is the single place
    that decides which; the route only forwards `prompt.tools`.
    """
    messages = list(initial_messages)
    tools = get_tool_schemas() if tools is None else list(tools)
    # SPEC.md §7.3 / Kabir LOW 4 — the PER-TURN gate.
    #
    # `is_known_tool` is a GLOBAL allowlist: since B0 widened it, all six
    # creator names pass it on every turn, brand turns included. Without this
    # set the loop never compares an emitted name against what was actually
    # offered, so a brand turn that emits `draft_reply` (a brief or a pasted
    # DM is untrusted text; getting the model to name a tool is cheap) is
    # forwarded to `/internal/meera/creator/draft_reply` carrying the BRAND's
    # on-behalf JWT. That request fails closed at Spring — a brand's
    # SCOPE_DEFAULT names no creator tool, so `CreatorMeeraToolController`
    # 403s it and nothing leaks — but the boundary then exists only remotely,
    # and the round trip is a free amplifier for whoever controls the injected
    # text. Refuse locally instead: no socket, no JWT on the wire.
    #
    # Built from `tools` because that IS the offer: `assemble_prompt` derives
    # it from the creator's `tools_enabled`, so a creator whose scope grants
    # three tools cannot dispatch the other three either. Empty offer set =
    # nothing dispatchable, which is the correct reading of an empty offer and
    # the direction a missing context must fail in.
    offered_tool_names = {
        t["name"] for t in tools if isinstance(t, dict) and isinstance(t.get("name"), str)
    }
    iterations = 0
    final_usage: dict[str, Any] | None = None
    # P1 BLANK TURN fix (F2): bounded to ONE retry across the entire loop call
    # (not per while-iteration) -- a turn that keeps coming back truncated
    # after already being retried once must fall through to the honest
    # fallback, not loop forever burning tokens.
    retried_empty = False
    effective_max_tokens = ctx.max_tokens

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
        # P1 BLANK TURN fix (F1/F2): set when claude.py reconciles an unclosed
        # tool_use block at message_stop (a max_tokens cut mid input_json_delta).
        turn_truncated = False
        turn_stop_reason: str | None = None

        async for event in claude.stream_turn(
            system_blocks=system_blocks,
            messages=messages,
            tools=tools,
            max_tokens=effective_max_tokens,
            is_cancelled=is_cancelled,
        ):
            # F-02: accumulate usage BEFORE the cancellation check. The
            # provider flushes a partial-usage event on cancel, and returning
            # first threw it away — which is exactly how a disconnect burned
            # real tokens and recorded $0.
            if event.type == "usage":
                final_usage = _accumulate_usage(final_usage, event.usage)
                turn_stop_reason = event.stop_reason or turn_stop_reason

            if is_cancelled and is_cancelled():
                yield LoopEvent(
                    type="error", error_code="client_disconnected", usage=final_usage
                )
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
            elif event.type == "truncated":
                turn_truncated = True
                turn_stop_reason = event.stop_reason
            elif event.type == "usage":
                pass  # already accumulated above (F-02)

        assistant_text = "".join(assistant_text_parts)

        if not pending_tool_calls:
            # ── F-16 ────────────────────────────────────────────────────────
            # `turn_truncated` used to be consulted ONLY on the zero-text
            # branch below. But the persona mandates narrating before every
            # tool call, so the common shape is text("Scanning creators in
            # Mumbai...") followed by a truncation mid `create_campaign`. That
            # landed here with assistant_text non-empty and was reported as
            # finish_reason="stop": zero retries, no error, the narration
            # persisted as the assistant's answer, and the charge kept. The
            # brand is told a campaign is being built; nothing was built and
            # nothing is logged as a failure. Same 28% blank-turn class the
            # fix was written for, now wearing narration.
            #
            # A truncated turn is a truncated turn whether or not it narrated
            # first, so the recovery is checked BEFORE the happy path.
            if turn_truncated and not retried_empty:
                retried_empty = True
                logger.warning(
                    "meera turn truncated mid tool_use stop_reason=%s narrated_chars=%d -- "
                    "retrying once at max_tokens=%d",
                    turn_stop_reason,
                    len(assistant_text),
                    ctx.max_tokens_retry,
                )
                effective_max_tokens = ctx.max_tokens_retry
                continue  # `messages` unchanged; provably pre-tool-forward
            if turn_truncated:
                # Retry already spent and it truncated again. Never report this
                # as a clean stop — the narration is not an answer.
                logger.warning(
                    "meera turn truncated mid tool_use after retry stop_reason=%s", turn_stop_reason
                )
                yield LoopEvent(type="token", text=EMPTY_TURN_FALLBACK)
                yield LoopEvent(
                    type="done",
                    finish_reason="truncated_tool_use",
                    usage=final_usage,
                    stop_reason=turn_stop_reason,
                )
                return

            # No tool calls this round -> final assistant text, we're done.
            if assistant_text:
                messages.append({"role": "assistant", "content": assistant_text})
                yield LoopEvent(
                    type="done", finish_reason="stop", usage=final_usage, stop_reason=turn_stop_reason
                )
                return

            # ── EMPTY MODEL TURN (P1 BLANK TURN fix, F2) ────────────────────
            # Zero text AND zero tool calls this round. Previously this fell
            # straight through to `done finish_reason="stop"` -- a completely
            # silent, indistinguishable-from-success blank turn (traced
            # 2026-07-24, ~28% of Meera turns on large tool payloads like
            # create_campaign). Two-stage recovery:
            # Zero text AND zero tool calls, and NOT a known truncation (that
            # case is handled above, F-16, whether or not the turn narrated
            # first) -- stream an honest, in-persona
            # fallback instead of silence. finish_reason="empty_response" is
            # a NEW, deliberately-distinct value (never "stop") so chat.py can
            # refund the send-time charge instead of persisting/billing a
            # non-answer (F7) and so this failure mode is visible in logs
            # rather than indistinguishable from success.
            yield LoopEvent(type="token", text=EMPTY_TURN_FALLBACK)
            yield LoopEvent(
                type="done",
                finish_reason="empty_response",
                usage=final_usage,
                stop_reason=turn_stop_reason,
            )
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
                result_payload: dict[str, Any] = {
                    "error": "unknown_tool", "message": f"tool {tool_name!r} is not defined"
                }
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

            # The per-turn half of the gate (see `offered_tool_names` above).
            # Runs AFTER `is_known_tool` so an invented name still reports as
            # `unknown_tool`, and BEFORE the local-tool branch so it also
            # covers `analyze_site` / `present_options` — brand-only surface a
            # CREATOR turn must not be able to reach either.
            #
            # THE ONE EXEMPTION is a money tool. `get_tool_schemas()` stopped
            # offering request_payment/confirm_launch (ME-2), yet the loop
            # deliberately keeps forwarding them so Spring's on-behalf
            # rejection can drive MONEY_TOOL_SCOPE_DECLINE below — a shipped,
            # tested behaviour, not an oversight. It is not a hole of the kind
            # this gate closes: a money tool routes to the caller's OWN
            # `/internal/meera/<name>` prefix under the caller's own JWT, so
            # no audience boundary is crossed, and the far end refuses on
            # scope. DO NOT add any other name here — every addition
            # re-opens exactly the cross-audience forward described above.
            if tool_name not in offered_tool_names and not is_money_tool(tool_name):
                logger.warning(
                    "rejected tool call not offered this turn: %s (offered=%s)",
                    tool_name,
                    sorted(offered_tool_names),
                )
                result_payload = {
                    "error": "tool_not_offered",
                    "message": f"tool {tool_name!r} was not offered on this turn",
                }
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
                    except Exception as exc:  # noqa: BLE001 - flywheel logging is best-effort
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
                except Exception as exc:  # noqa: BLE001 - a fetch/classify error must not break the turn
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
                    except Exception as exc:  # noqa: BLE001 - write-back is best-effort
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

            # SPEC.md §7.3. This lookup and `is_known_tool` are ONE edit: the
            # brand map alone would KeyError on a creator tool that
            # `is_known_tool` had just accepted, and this line sits OUTSIDE the
            # `try` below, so that KeyError is unhandled and kills a live
            # stream mid-turn. `.get(...)` then a `.get(...)` (never a bracket
            # subscript on either map) also covers the third case: a name that
            # is known but has no route in either map — a schema shipped ahead
            # of its endpoint — which degrades to an error tool_result the
            # model can narrate instead of a 500.
            path = TOOL_TO_SPRING_PATH.get(tool_name) or CREATOR_TOOL_TO_SPRING_PATH.get(tool_name)
            if not path:
                logger.error("no Spring route for known tool %s — degrading to an error result", tool_name)
                result_payload = {
                    "error": "tool_not_routable",
                    "message": f"tool {tool_name!r} has no endpoint in this deployment",
                }
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

            idempotency_key = None
            # Both idempotency sites consider the creator set — this one and
            # `allow_retry=` below. They are a pair: requiring the key without
            # disabling retry leaves a commit-like creator tool silently
            # retryable, which is the failure this comment exists to prevent.
            if (
                tool_name in IDEMPOTENT_REQUIRED_TOOLS
                or tool_name in CREATOR_IDEMPOTENT_REQUIRED_TOOLS
            ):
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
                    allow_retry=(
                        tool_name not in IDEMPOTENT_REQUIRED_TOOLS
                        and tool_name not in CREATOR_IDEMPOTENT_REQUIRED_TOOLS
                    ),
                )
            except SpringCallError as exc:
                # ME-2 (BrandF.md §115): a money tool rejected on the on-behalf
                # gate is not a "try something else" error the way a validation
                # 400 is — Claude has no productive next move, and feeding the
                # raw error back into `messages` (the general path below) only
                # invited an improvised apology around a 403 it can't act on.
                # This should be rare now that get_tool_schemas() no longer
                # offers these tools at all (see schemas.py), but if one is ever
                # forwarded anyway, decline deterministically and end the turn
                # here — same treatment as EMPTY_TURN_FALLBACK, not routed
                # through Claude.
                #
                # Priya review: OnBehalfAuthResolver's role check runs BEFORE
                # its scope check (requireRole then requireScope), so which of
                # these three codes actually comes back depends on the caller's
                # role, not just SCOPE_DEFAULT excluding the tool — matching
                # only ON_BEHALF_SCOPE_INSUFFICIENT let the other two silently
                # fall through to the freestyle path. All three mean the same
                # thing to the brand: this needs their direct approval, not a
                # narrated retry.
                if is_money_tool(tool_name) and exc.code in (
                    "ON_BEHALF_SCOPE_INSUFFICIENT",
                    "ON_BEHALF_INSUFFICIENT_ROLE",
                    "ON_BEHALF_NOT_A_MEMBER",
                ):
                    logger.warning(
                        "money tool %s scope-rejected for workspace_id=%s — declining deterministically",
                        tool_name,
                        ctx.workspace_id,
                    )
                    yield LoopEvent(type="tool_result", tool_name=tool_name, tool_status="error", tool_result_data={"error": exc.code, "message": exc.message})
                    yield LoopEvent(type="token", text=MONEY_TOOL_SCOPE_DECLINE)
                    yield LoopEvent(
                        type="done", finish_reason="money_tool_scope_declined", usage=final_usage
                    )
                    return

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

            # §7.3: `data` goes to the model AND to the browser byte-for-byte —
            # no per-tool reshaping here, for brand or creator tools. The
            # creator cards (`CreatorToolResultRenderer`, §8.4) render straight
            # off this payload, so a field dropped or renamed here is a card
            # that silently renders empty rather than an error anyone sees.
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
