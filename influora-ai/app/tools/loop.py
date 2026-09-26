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

import json
import logging
import re
import unicodedata
from collections.abc import AsyncIterator
from dataclasses import dataclass
from typing import Any

from app.clients.spring import (
    SpringCallError,
    SpringInternalClient,
    idempotency_key_for,
)
from app.config import PROMPT_VERSION, get_settings
from app.planner.week_plan import enrich_week_plan
from app.prompt.content_knowledge import LOOKUP_TOPICS, render_lookup_section
from app.prompt.untrusted import wrap_untrusted
from app.providers.claude import ClaudeProvider
from app.routes.analyze_site import perform_site_analysis
from app.tools.creator_schemas import (
    CHECK_DEAL_RISKS,
    CREATOR_IDEMPOTENT_REQUIRED_TOOLS,
    CREATOR_NO_RETRY_TOOLS,
    CREATOR_TOOL_TO_SPRING_PATH,
    GET_BRIEF,
    GET_CREATOR_KNOWLEDGE,
    GET_MY_CONTENT_PATTERNS,
    GET_MY_DEALS,
    GET_TODAYS_TOPICS,
    PLAN_MY_WEEK,
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
    creator's `tools_enabled` grants -- never a money tool, never a brand tool
    -- plus the local get_creator_knowledge, which every creator turn carries.
    `assemble_prompt` is the single place that decides which; the route only
    forwards `prompt.tools`.
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
            # CREATOR turn must not be able to reach either — and, the other
            # way round, `get_creator_knowledge`, which only creator turns
            # offer, so a BRAND turn that emits it is refused right here.
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
                # get_creator_knowledge — creator-only knowledge lookup
                # (PROMPT_VERSION .13). Reads Influora's OWN static knowledge
                # file, rendered at import by content_knowledge.py: no Spring
                # call, no JWT on the wire, no creator data. Its text is ours,
                # not brand/creator-written, so it goes back to the model
                # unwrapped (unlike the `<untrusted_*>` splits in
                # `_model_copy_of_tool_result`). A missing or unknown topic is
                # an is_error result naming the valid topics -- never a raise.
                if tool_name == GET_CREATOR_KNOWLEDGE:
                    topic = tool_input.get("topic") if isinstance(tool_input, dict) else None
                    # Only {"topic"} is accepted, matching the schema's
                    # additionalProperties: false -- anything else is refused, not ignored.
                    extra_keys = set(tool_input) - {"topic"} if isinstance(tool_input, dict) else set()
                    knowledge: str | None = None
                    if isinstance(topic, str) and topic in LOOKUP_TOPICS and not extra_keys:
                        try:
                            knowledge = render_lookup_section(topic)
                        except Exception as exc:  # noqa: BLE001 - a lookup must not break the turn
                            logger.warning(
                                "local tool %s failed for topic=%s: %s",
                                tool_name,
                                topic,
                                type(exc).__name__,
                            )
                            knowledge = None
                    if not knowledge:
                        result_payload = {"error": "unknown_topic", "topics": list(LOOKUP_TOPICS)}
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
                    tool_result_blocks.append(
                        {
                            "type": "tool_result",
                            "tool_use_id": tool_use_id,
                            "content": _safe_json({"topic": topic, "knowledge": knowledge}),
                        }
                    )
                    # The BROWSER's copy is the topic only: the creator app
                    # shows one work-trail step for this tool ("Checking
                    # Influora's notes on audio", keyed on `topic`) and never a
                    # card, so shipping the knowledge text over SSE would be
                    # dead weight. NOTE routes/chat.py counts any "ok"
                    # tool_result as delivered output when deciding whether an
                    # otherwise empty turn keeps its charge.
                    yield LoopEvent(
                        type="tool_result",
                        tool_name=tool_name,
                        tool_status="ok",
                        tool_result_data={"topic": topic},
                    )
                    continue

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

            # F1 HIGH fix (RULINGS-U-0917.md Addition B): get_brief is not a pure
            # read (see CREATOR_NO_RETRY_TOOLS's own comment) and needs both a
            # longer read timeout AND no retry -- widening the timeout alone
            # would not have fixed the wrong-answer bug, only made it rarer.
            # A single conditional expression, keyed on the one tool that needs
            # an override today (Kavya U-1 re-review, H1: this used to describe
            # a dict-keyed-by-tool_name design that was never written). If a
            # second tool ever needs its own override, replace this with a
            # dict keyed by tool_name rather than stacking a second ternary.
            read_timeout_override = (
                get_settings().timeouts.get_brief_read if tool_name == GET_BRIEF else None
            )

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
                        and tool_name not in CREATOR_NO_RETRY_TOOLS
                    ),
                    read_timeout_override=read_timeout_override,
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

            # §7.3: the BROWSER's copy of `data` (the `tool_result_data` yielded
            # below) is never reshaped, for brand or creator tools -- the creator
            # cards (`CreatorToolResultRenderer`, §8.4) render straight off this
            # payload, so a field dropped or renamed here is a card that silently
            # renders empty rather than an error anyone sees. Since K-3 the
            # MODEL's copy is a separate value and may be split into a trusted
            # part plus an `<untrusted_brand_written>` wrapper for get_brief,
            # check_deal_risks and get_my_deals -- see `_model_copy_of_tool_result`.
            data = response.data or {}
            action = data.get("action") if isinstance(data, dict) else None
            if action == "AWAIT_HUMAN_CONFIRM" or data.get("status") == "PENDING_CONFIRM":
                # Do NOT loop to "done" automatically -- surface as canvas state.
                should_stop_after_pending = True

            tool_result_blocks.append(
                {
                    "type": "tool_result",
                    "tool_use_id": tool_use_id,
                    # K-3: the MODEL's copy only -- may wrap brand-written fields in
                    # <untrusted_brand_written> for get_brief/check_deal_risks/get_my_deals.
                    # `data` itself is never touched (see _model_copy_of_tool_result's own
                    # docstring) -- the LoopEvent below still carries the original object.
                    "content": _model_copy_of_tool_result(tool_name, data),
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


# K-3 KC-1 (Kabir, "Last call — K-3" §Conditions, KABIR-CONSENT-0917.md): TRUSTED allow-lists,
# not brand-written deny-lists. The first version of this named the brand-written keys and
# trusted everything else by default -- Kabir's adversarial probe added an unlisted top-level
# `last_brand_message` to a get_brief payload and an unlisted per-deal `last_message_preview` to
# a get_my_deals payload, and BOTH landed OUTSIDE the wrapper, because a deny-list trusts an
# unknown key by construction. Wave D adds exactly this kind of field. Naming what IS trusted and
# wrapping everything else -- including any future, unrecognised key -- means a field nobody has
# written a rule for yet still defaults to wrapped, not defaults to trusted.
#
# get_brief: `quote` is the only container kept trusted, because it is computed entirely by
# Influora (RateQuoteService.java L330-351: Rendered.money values, the creator's own currency
# preference, fixed constants, a provenance built from a count) -- no brand budget label reaches
# it, only a BigDecimal. Everything else not named here (starting with `extraction` and `flags`,
# but also any field Wave D adds) is wrapped.
_TRUSTED_KEYS_GET_BRIEF = ("brief_id", "source", "status", "deal_id", "quote", "extraction_source")
# check_deal_risks: `highest_severity` (a severity name), `target` (DEAL/BRIEF) and `target_id`
# (an id) are the only fields that are not the `flags` array a rule wrote text into.
_TRUSTED_KEYS_CHECK_DEAL_RISKS = ("highest_severity", "target", "target_id")

# get_todays_topics: the ONLY trusted keys are the two the server computes itself.
# `topics` and every field in it -- title, angles, category, sensitivity -- is text typed
# straight into the `content_topics` table by hand, which never passed through any app-side
# validation, so it is exactly as untrusted as a brand's own words in a brief (Ash, AI
# review 2026-09-23, P0-2). Wrapped as `editorial`, not `brand_written`: the persona names
# both, and a creator reading the reply should not be told a brand wrote it.
_TRUSTED_KEYS_GET_TODAYS_TOPICS = ("today", "weekday")

# plan_my_week: everything the SERVER computed (dates, the posting pattern from the
# creator's own posts, their categories) plus the festival calendar from this repo is
# trusted; `topics` is the hand-typed table again, so it rides in the wrapper, and so does
# any key Spring does not send today.
_TRUSTED_KEYS_PLAN_MY_WEEK = ("today", "days", "pattern", "categories", "seasons")
# get_my_deals: two allow-lists -- top-level result fields, and per-deal fields. `deals` itself
# is handled specially below (split per deal), not listed as trusted or wrapped whole.
_TRUSTED_KEYS_GET_MY_DEALS = ("active_count", "completed_count")
# Every field CreatorToolDtos.DealSummary carries (influora-api .../CreatorToolDtos.java L29-43)
# EXCEPT brand_name (workspace.getName(), GetMyDealsExecutor.java L138-143) and campaign_title
# (campaign.getTitle(), L182) -- both confirmed brand-authored by Kabir's read of the executor.
# status/status_label/next_action are Influora's own fixed vocabulary (statusLabel L247-262,
# nextAction L202-223, itself only interpolating a Rendered.date); amount/next_deadline are
# Rendered.money/Rendered.date; currency is a 3-char column (Collaboration.java L44-45) that
# cannot carry an instruction; the rest are ids, a count and two booleans.
_TRUSTED_DEAL_FIELDS_GET_MY_DEALS = (
    "deal_id",
    "status",
    "status_label",
    "amount",
    "amount_value",
    "currency",
    "next_action",
    "next_deadline",
    "secured",
    "unread_count",
    "has_pending_offer",
    "brief_id",
)

# F-1771 (MEDIUM, latent -- Priya "Last call -- K-3" F2): the allow-lists above only
# classify TOP-LEVEL and per-deal keys. `quote` sits in _TRUSTED_KEYS_GET_BRIEF as a
# container trusted WHOLE, so an unknown key one level inside it (Kabir/Priya's probe:
# `quote.brand_budget_note`) rode along outside the wrapper -- nobody had written a rule
# for it, and a container trusted whole trusts everything inside it by construction,
# which is the same deny-list shape KC-1 removed at the top level, one level down. These
# three pin PackageQuote/QuoteLine/AddOnLine (CreatorToolDtos.java) field-for-field; see
# _is_fully_trusted_quote below and the drift test in
# test_k3_dto_field_classification_drift.py -- which goes red if Java adds a field here
# that isn't named on either side, but ONLY for a component with a snake_case
# @JsonProperty name (an unannotated or camelCase field is invisible to its regex parse --
# R5, Priya "Last call -- K-3 re-check" 0918). ai-tests.yml's path filter runs this file on
# a PR whose diff touches influora-ai/** OR
# influora-api/src/main/java/com/influora/web/dto/meera/** (added K-3 round 4, Priya
# "Last call -- K-3 round 4" 0918, so a Java-only PR that only edits CreatorToolDtos.java
# still runs the drift test).
_TRUSTED_KEYS_QUOTE = (
    "lines",
    "add_ons",
    "bundle_discount",
    "bundle_discount_value",
    "total",
    "total_value",
    "anchor",
    "anchor_value",
    "floor_total",
    "floor_total_value",
    "range_min",
    "range_max",
    "currency",
    "payment_schedule",
    "revision_rounds",
    "provenance",
    "provenance_sample_size",
    "recommended_move",
    "scope_down_offer",
    "withheld",
    "withheld_reason",
)
_TRUSTED_KEYS_QUOTE_LINE = (
    "type",
    "qty",
    "unit_price",
    "unit_price_value",
    "line_total",
    "line_total_value",
    "below_floor",
)
_TRUSTED_KEYS_ADD_ON = ("code", "label", "amount", "amount_value", "basis")

# get_my_content_patterns (Meera intelligence v1, spec 4.4/4.5): every field is computed by
# Spring from the creator's OWN stored post readings (CreatorIntelligenceService, rendered by
# GetMyContentPatternsExecutor) plus Meta's own permalinks and fixed vocabulary (metric names,
# REEL/CAROUSEL/POST/OTHER, "weekday evening", "Reels and videos"). No brand text and no
# editorial text ever reaches it. The ONE field a person wrote is PostReading.caption_first_line
# (the creator's OWN caption, 2026-09-26), which is never trusted: see _UNTRUSTED_KEYS_CONTENT_POST.
# Everything else is trusted -- but only field-for-field,
# one allow-list per Java record (CreatorToolDtos.GetMyContentPatternsResult / BaselineMetric /
# PostReading / WorkingPattern / Evidence), so an element carrying a key nobody classified pulls
# its WHOLE list into the wrapper (the F-1771 rule: a container is never trusted whole).
# tests/tools/test_content_patterns_real_payload.py parses the Java @JsonProperty names and fails
# on any field that lands on neither side.
_TRUSTED_KEYS_GET_MY_CONTENT_PATTERNS = (
    "available",
    "reason",
    "enough_data",
    "settled_posts",
    "unsettled_posts",
    "min_posts_needed",
    "lookback_days",
    "as_of",
    "baseline",
    "best_posts",
    "weak_posts",
    "what_works",
    "followed_recommendations",
    "note",
)
_TRUSTED_KEYS_CONTENT_EVIDENCE = ("type", "sample_size", "post_ids", "baseline_sample_size")
_TRUSTED_KEYS_CONTENT_BASELINE = ("metric", "median", "evidence")
_TRUSTED_KEYS_CONTENT_POST = (
    "post_id",
    "post_type",
    "posted_date",
    "posted_time",
    "window",
    "permalink",
    "reach",
    "reach_vs_usual",
    "engagement_rate",
    "evidence",
)
# 2026-09-26 (wiki/decisions/2026-09-26-creator-own-caption-to-meera.md, Swapnil): PostReading's
# ONE untrusted field -- the first line of the creator's OWN caption for that post, cut by Spring
# to 100 characters with @handles and links removed. A person wrote it and it can say anything
# ("ignore your rules ..."), so it is never trusted: the model copy takes it OUT of the post,
# re-applies the same cut here (`_caption_first_line_for_model`, defence in depth if Spring ever
# regresses; the rules mirror influora-api CreatorOwnCaption, so Spring's own output passes
# through unchanged) and hands it over inside `<untrusted_creator_captions>`, keyed by the post's
# `post_id`. Creator-only: get_my_content_patterns is a creator tool, never offered on a brand
# turn, and nothing here logs the text. A caption_first_line that is not a string (or null) is
# left in the post, so that post's WHOLE list fails its allow-list and is wrapped (fail closed).
_UNTRUSTED_KEYS_CONTENT_POST = ("caption_first_line",)
_CONTENT_CAPTION_LISTS = ("best_posts", "weak_posts")
CAPTION_FIRST_LINE_MAX_CHARS = 100
_CAPTION_ELLIPSIS = "\u2026"
_CAPTION_LINE_BREAKS = re.compile(r"\r\n|[\n\r\v\f\x85\u2028\u2029]")
_CAPTION_ZWNJ = "\u200c"
_CAPTION_ZWJ = "\u200d"
# The patterns spell letters out as [A-Za-z] and never use IGNORECASE: Python's Unicode
# IGNORECASE also matches a few non-ASCII letters (the Kelvin sign, the long s), Java's does not,
# and the shared fixture (influora-api src/test/resources/creator-own-caption-cases.json) holds
# the two cleaners to identical output.
_CAPTION_EMAIL = re.compile(r"[A-Za-z0-9_.+-]+@[A-Za-z0-9_-]+(?:\.[A-Za-z0-9_-]+)+")
# A scheme or www. link, up to the next (Unicode) space, so a no-break space ends it too.
_CAPTION_URL = re.compile(r"(?:[A-Za-z][A-Za-z0-9+.-]*://|[Ww][Ww][Ww]\.)\S+")
# A bare link with a path (youtu.be/abc, example.co.uk/sale): any dotted name whose last label is
# two or more letters, followed by a slash.
_CAPTION_BARE_LINK_WITH_PATH = re.compile(
    r"(?<![A-Za-z0-9_@.])[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}/\S*"
)
# A bare domain with no path (shop.nike.de): only common top-level domains, so ordinary text with
# a missing space after a full stop is not taken for a link.
_CAPTION_BARE_DOMAIN_TLDS = (
    "com|in|io|co|net|org|me|ee|ly|app|link|gl|to|shop|store|site|xyz|bio|page|gg|tv"
    "|be|uk|ai|de|us|info|club|live|online|fm|biz"
)
_CAPTION_BARE_DOMAIN = re.compile(
    r"(?<![A-Za-z0-9_@.])[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*\.(?:"
    + "|".join(
        "".join(f"[{c}{c.upper()}]" for c in tld) for tld in _CAPTION_BARE_DOMAIN_TLDS.split("|")
    )
    + r")(?![A-Za-z0-9_.])"
)
# An @handle after an at sign, a full-width at sign (U+FF20) or a small at sign (U+FE6B), even
# glued to the word before it.
_CAPTION_HANDLE = re.compile("[@\uff20\ufe6b][A-Za-z0-9._]+")
_TRUSTED_KEYS_CONTENT_PATTERN = (
    "kind",
    "label",
    "posts",
    "median_reach",
    "reach_vs_usual",
    "median_engagement_rate",
    "engagement_vs_usual",
    "beats_on",
    "evidence",
)
# Slice 2 (spec 8.4): CreatorToolDtos.FollowedGroup -- how many recommendations of one source
# (PLAN_MY_WEEK | CHALLENGE | SCRIPT_CARD) the creator was given and posted, and the median reach
# of the ones she posted vs her usual. All server-computed counts and a pre-written percentage from
# her own post readings; `evidence.post_ids` are the matched posts (dropped from the model copy).
_TRUSTED_KEYS_CONTENT_FOLLOWED = (
    "source",
    "recommended",
    "followed",
    "median_reach_vs_usual",
    "evidence",
)
# The list containers of the result and the per-element allow-list each one is held to.
_CONTENT_PATTERNS_LISTS: dict[str, tuple[str, ...]] = {
    "baseline": _TRUSTED_KEYS_CONTENT_BASELINE,
    "best_posts": _TRUSTED_KEYS_CONTENT_POST,
    "weak_posts": _TRUSTED_KEYS_CONTENT_POST,
    "what_works": _TRUSTED_KEYS_CONTENT_PATTERN,
    "followed_recommendations": _TRUSTED_KEYS_CONTENT_FOLLOWED,
}
# Spec 4.5 (token control): the model copy drops every `evidence.post_ids` -- up to 150 ids per
# baseline metric -- and keeps `sample_size` / `baseline_sample_size`, which is what Meera quotes
# ("based on 8 of your Reels"). The browser's copy (`LoopEvent.tool_result_data`) keeps the ids.
_MODEL_DROPPED_EVIDENCE_KEYS = frozenset({"post_ids"})


_JSON_SCALAR_TYPES = (str, int, float, bool, type(None))


def _is_json_scalar(value: Any) -> bool:
    """A JSON leaf value with nowhere left to hide a dict or a list. Every trusted key's
    value must be one of these (R2, see `_split_trusted_scalar` below) -- the handful of
    containers Influora's own DTOs are known to send (`quote`, `lines`, `add_ons`, `deals`)
    are the only exemptions, and each of those is validated structurally in its own right
    instead of by this scalar check.
    """
    return isinstance(value, _JSON_SCALAR_TYPES)


def _is_fully_trusted_quote(quote: Any) -> bool:
    """F-1771 (R2, Priya "Last call -- K-3 re-check" 0918): `quote` stays OUTSIDE the
    wrapper only if (a) every key inside it, and inside each `lines[]`/`add_ons[]` entry, is
    one PackageQuote/QuoteLine/AddOnLine is known to carry -- Influora-computed, never brand
    text (Priya's read of RateQuoteService; see the module docstring above) -- AND (b) every
    one of those keys' values is itself a JSON scalar (`_is_json_scalar`), except `lines` and
    `add_ons` themselves, which are containers validated structurally by (a)+(b) applied to
    their own elements one level down, not scalar-checked directly. That combination is what
    makes this "every key, every depth this container ever has" rather than depth in the
    abstract: once every non-container key's value is confirmed scalar, there is no further
    depth left for anything to hide in. Before R2 this function only checked (a) -- a dict
    slipped under a recognised key's name (Kabir/Priya's probe: `quote.total` holding a dict,
    or `quote.lines[0].type` holding one) passed the key-set check and rode along trusted; R2
    closes exactly that gap.

    Either failure -- an unrecognised key, OR a recognised key holding a non-scalar,
    non-container value -- pulls the ENTIRE quote container into the wrapper, rather than
    reconstructing a partial, still-shaped quote with just the offending leaf spliced out.
    Rebuilding a partial nested structure per unknown/non-scalar field is exactly the
    piecemeal, easy-to-get-wrong filtering KC-1 rejected at the top level; wrapping the whole
    container is that same allow-list default (unclassified defaults to wrapped) applied one
    level down, and it is safe by construction -- nothing Spring sends today trips it, which
    is why F-1771 is latent, not live.
    """
    if not isinstance(quote, dict) or not set(quote.keys()) <= set(_TRUSTED_KEYS_QUOTE):
        return False
    for key, value in quote.items():
        if key in ("lines", "add_ons"):
            continue
        if not _is_json_scalar(value):
            return False
    lines = quote.get("lines")
    if lines is not None:
        if not isinstance(lines, list):
            return False
        for line in lines:
            if not isinstance(line, dict) or not set(line.keys()) <= set(_TRUSTED_KEYS_QUOTE_LINE):
                return False
            if not all(_is_json_scalar(v) for v in line.values()):
                return False
    add_ons = quote.get("add_ons")
    if add_ons is not None:
        if not isinstance(add_ons, list):
            return False
        for add_on in add_ons:
            if not isinstance(add_on, dict) or not set(add_on.keys()) <= set(_TRUSTED_KEYS_ADD_ON):
                return False
            if not all(_is_json_scalar(v) for v in add_on.values()):
                return False
    return True


def _split_trusted_scalar(
    data: dict[str, Any],
    trusted_keys: tuple[str, ...],
    container_keys: frozenset[str] = frozenset(),
) -> tuple[dict[str, Any], dict[str, Any]]:
    """R2 (Priya "Last call -- K-3 re-check" 0918, F-1771 finding R2): extends KC-1's
    allow-list rule one level further down than a bare key-NAME check can reach. A trusted
    key's name is not enough -- G4-G8/C1/M3/M4/M6 all probed a recognised key (`quote.total`,
    top-level `status`, `target`, per-deal `next_action`, ...) holding a dict or a list
    instead of the scalar every Java DTO field of that name actually is. Every field a
    `_TRUSTED_*` tuple names is a `String`/`BigDecimal`/`int`/`boolean` (never a nested record
    or a collection) EXCEPT the handful of known containers passed in `container_keys`
    (`quote`, for get_brief -- `deals` never reaches this helper; it has its own dedicated
    per-element split below) -- those are validated structurally by their own dedicated
    check instead of by `_is_json_scalar` here. Anything else -- an unrecognised key, or a
    recognised one holding a non-scalar, non-container value -- moves to the untrusted
    bucket: the allow-list default is "wrapped", not "trusted because the name matched and
    nobody checked the shape."
    """
    trusted: dict[str, Any] = {}
    brand: dict[str, Any] = {}
    for key, value in data.items():
        if key not in trusted_keys:
            brand[key] = value
        elif key in container_keys or _is_json_scalar(value):
            trusted[key] = value
        else:
            brand[key] = value
    return trusted, brand


def _strip_post_ids(value: Any) -> Any:
    """A new copy of `value` with every `post_ids` key removed from every dict at any depth.
    Used on the WRAPPED part of a get_my_content_patterns model copy, so the token control of
    spec 4.5 holds even for a list that failed its allow-list; never mutates `value`."""
    if isinstance(value, dict):
        return {
            k: _strip_post_ids(v) for k, v in value.items() if k not in _MODEL_DROPPED_EVIDENCE_KEYS
        }
    if isinstance(value, list):
        return [_strip_post_ids(v) for v in value]
    return value


def _caption_invisible_removed(line: str) -> str:
    """Control characters (Cc: C0, DEL and C1) become a space; format characters (Cf: bidi
    overrides, zero-width spaces, ...) are removed without a space, so they can neither split a
    word nor hide a handle or link. The joiners U+200C/U+200D are kept only after a non-ASCII
    character, where they hold an emoji sequence or an Indic word together -- influora-api
    CreatorOwnCaption.invisibleRemoved, rule for rule."""
    out: list[str] = []
    prev = -1
    for ch in line:
        category = unicodedata.category(ch)
        if category == "Cc":
            out.append(" ")
        elif category == "Cf":
            if ch in (_CAPTION_ZWNJ, _CAPTION_ZWJ) and prev > 0x7F:
                out.append(ch)
        else:
            out.append(ch)
        prev = ord(ch)
    return "".join(out)


def _clean_caption_line(line: str) -> str:
    """One caption line with invisible characters handled (`_caption_invisible_removed`), e-mail
    addresses, links and @handles replaced by a space, and whitespace collapsed -- influora-api
    CreatorOwnCaption.clean, rule for rule."""
    out = _caption_invisible_removed(line)
    out = _CAPTION_EMAIL.sub(" ", out)
    out = _CAPTION_URL.sub(" ", out)
    out = _CAPTION_BARE_LINK_WITH_PATH.sub(" ", out)
    out = _CAPTION_BARE_DOMAIN.sub(" ", out)
    out = _CAPTION_HANDLE.sub(" ", out)
    return " ".join(out.split())


def _caption_is_regional_indicator(ch: str) -> bool:
    return 0x1F1E6 <= ord(ch) <= 0x1F1FF


def _caption_joined(cps: str, i: int) -> bool:
    """Whether a cut between cps[i - 1] and cps[i] would split one visible character: a combining
    mark stays on what it marks, a joiner keeps both neighbours, a skin-tone modifier or tag stays
    on its emoji, a virama keeps the next letter, a flag's regional indicators stay a pair --
    influora-api CreatorOwnCaption.joined, rule for rule."""
    prev, cur = cps[i - 1], cps[i]
    if unicodedata.category(cur) in ("Mn", "Mc", "Me"):
        return True
    if cur in (_CAPTION_ZWNJ, _CAPTION_ZWJ) or prev in (_CAPTION_ZWNJ, _CAPTION_ZWJ):
        return True
    if 0x1F3FB <= ord(cur) <= 0x1F3FF or 0xE0020 <= ord(cur) <= 0xE007F:
        return True
    if unicodedata.category(prev) == "Mn" and unicodedata.name(prev, "").endswith(" SIGN VIRAMA"):
        return True
    if _caption_is_regional_indicator(cur) and _caption_is_regional_indicator(prev):
        run = 0
        k = i - 1
        while k >= 0 and _caption_is_regional_indicator(cps[k]):
            run += 1
            k -= 1
        return run % 2 == 1
    return False


def _caption_truncated(line: str) -> str | None:
    """At most CAPTION_FIRST_LINE_MAX_CHARS code points, the cut backed off to the start of a
    character the reader sees as one, with a closing ellipsis."""
    if len(line) <= CAPTION_FIRST_LINE_MAX_CHARS:
        return line
    end = CAPTION_FIRST_LINE_MAX_CHARS - 1
    while end > 0 and _caption_joined(line, end):
        end -= 1
    kept = line[:end].rstrip()
    return kept + _CAPTION_ELLIPSIS if kept else None


def _caption_says_something(line: str) -> bool:
    """A line of only dots, dashes or other spacers says nothing (a letter, a digit or a symbol
    such as an emoji does)."""
    return any(unicodedata.category(ch)[0] in "LN" or unicodedata.category(ch) == "So" for ch in line)


def _caption_first_line_for_model(value: Any) -> str | None:
    """The model's copy of one `caption_first_line`: the FIRST line (leading blank lines are not
    a line), cleaned (`_clean_caption_line`) and cut (`_caption_truncated`). If that first line
    says nothing once cleaned -- only an @handle, a link or a spacer -- the answer is None: a
    later line is never sent in its place. Spring (CreatorOwnCaption.firstLine) already does
    exactly this, so its output passes through unchanged; repeating it here means a Spring
    regression still cannot hand Meera a second line, a link, an e-mail or someone's @handle.
    None when nothing is left (or `value` is not a string)."""
    if not isinstance(value, str):
        return None
    for raw in _CAPTION_LINE_BREAKS.split(value):
        if not " ".join(_caption_invisible_removed(raw).split()):
            continue  # a blank line before the first line is not a line
        line = _clean_caption_line(raw)
        if not line or not _caption_says_something(line):
            return None  # the first line said nothing: never fall through to line 2
        return _caption_truncated(line)
    return None


def _split_post_captions(items: Any) -> tuple[Any, list[dict[str, Any]]]:
    """(the posts without their `caption_first_line`, [{post_id, caption_first_line}, ...]).

    Never mutates `items`: every post that carried a caption comes back as a new dict. A post
    whose caption is neither a string nor null, or whose `post_id` is not a string, is returned
    UNCHANGED -- the caption key then fails `_TRUSTED_KEYS_CONTENT_POST` and the whole list is
    wrapped as unclassified, never trusted. A non-list is returned as it is, with no captions."""
    if not isinstance(items, list):
        return items, []
    rest: list[Any] = []
    captions: list[dict[str, Any]] = []
    for item in items:
        if not isinstance(item, dict) or "caption_first_line" not in item:
            rest.append(item)
            continue
        value = item["caption_first_line"]
        post_id = item.get("post_id")
        if not (value is None or isinstance(value, str)) or not isinstance(post_id, str):
            rest.append(item)
            continue
        rest.append({k: v for k, v in item.items() if k not in _UNTRUSTED_KEYS_CONTENT_POST})
        line = _caption_first_line_for_model(value)
        if line is not None:
            captions.append({"post_id": post_id, "caption_first_line": line})
    return rest, captions


def _content_evidence_for_model(evidence: Any) -> dict[str, Any] | None:
    """The model's copy of one `Evidence`: `post_ids` dropped, `type` / `sample_size` /
    `baseline_sample_size` kept. None when the shape is not the Java record's (an unknown key,
    a non-scalar value, `post_ids` not a list of scalars) -- the caller then wraps the list."""
    if not isinstance(evidence, dict) or not set(evidence) <= set(_TRUSTED_KEYS_CONTENT_EVIDENCE):
        return None
    out: dict[str, Any] = {}
    for key, value in evidence.items():
        if key in _MODEL_DROPPED_EVIDENCE_KEYS:
            if not isinstance(value, list) or not all(_is_json_scalar(v) for v in value):
                return None
            continue
        if not _is_json_scalar(value):
            return None
        out[key] = value
    return out


def _content_list_for_model(items: Any, element_keys: tuple[str, ...]) -> list[Any] | None:
    """The model's copy of one of get_my_content_patterns' four lists, element by element.
    Every element must be a dict whose keys are all in `element_keys`, whose values are
    scalars, except `evidence` (checked by `_content_evidence_for_model`) and `beats_on` (a list
    of strings, WorkingPattern only). Any miss returns None and the WHOLE list is wrapped."""
    if not isinstance(items, list):
        return None
    out: list[Any] = []
    for item in items:
        if not isinstance(item, dict) or not set(item) <= set(element_keys):
            return None
        element: dict[str, Any] = {}
        for key, value in item.items():
            if key == "evidence":
                evidence = _content_evidence_for_model(value)
                if evidence is None:
                    return None
                element[key] = evidence
            elif key == "beats_on":
                if not isinstance(value, list) or not all(isinstance(v, str) for v in value):
                    return None
                element[key] = list(value)
            elif _is_json_scalar(value):
                element[key] = value
            else:
                return None
        out.append(element)
    return out


def _model_copy_of_tool_result(tool_name: str, data: Any) -> str:
    """The JSON string the MODEL reads for a creator tool's result. The ONLY thing this may
    change — `LoopEvent.tool_result_data` (what the browser card renders) must stay the exact
    `data` object passed in, at every call site, so the card never sees escaped or restructured
    text. This function never mutates `data`; every dict it returns is a new one.

    K-3: `neutralize_angle_brackets` alone (which is all the original get_brief mechanism did)
    does not address this threat — a JSON string inside a `tool_result` block has no delimiter
    for escaping to protect, so "ignore previous instructions" reaches the model exactly as
    written whether or not its angle brackets are escaped. `wrap_untrusted` supplies BOTH the
    delimiter (which the persona now names explicitly, `creator_persona.py`) and the
    neutralisation (so the brand's text cannot forge its OWN closing tag and escape the wrapper)
    together, which escaping alone cannot do.

    Scoped to get_brief, check_deal_risks and get_my_deals — the three creator tools whose
    result carries brand-authored free text today (Priya's audit, round 3 §5 point 4;
    estimate_my_rate's `PackageQuote` carries none, and Block A/B context fields are a separate
    surface this item does not touch). plan_my_week and get_todays_topics wrap editorial text;
    get_my_content_patterns (intelligence v1) is trusted field-for-field and has every
    `evidence.post_ids` dropped for token control, while the browser's copy keeps them; its one
    person-written field, each post's `caption_first_line` (the creator's own caption), is moved
    into an `<untrusted_creator_captions>` block keyed by post_id. Every
    other tool, and a non-dict payload (an error shape, or `None`), passes through as plain
    `_safe_json` — unchanged from before this fix.
    """
    if not isinstance(data, dict):
        return _safe_json(data)

    if tool_name == GET_BRIEF:
        # R2: `quote` is the one known container among this tool's trusted keys -- every
        # OTHER trusted key (brief_id, source, status, deal_id, extraction_source) must be
        # a scalar (G7, G8) or it moves to `brand` here, same as an unrecognised key.
        trusted, brand = _split_trusted_scalar(
            data, _TRUSTED_KEYS_GET_BRIEF, container_keys=frozenset({"quote"})
        )
        quote = trusted.get("quote")
        if quote is not None and not _is_fully_trusted_quote(quote):
            # F-1771: an unrecognised key, or a recognised key holding a non-scalar value
            # (G4-G6) -- the nested-container gap -- moves the WHOLE container into the
            # wrapper instead of leaking it, or part of it, trusted.
            brand["quote"] = trusted.pop("quote")
        if not brand:
            return _safe_json(data)
        return _safe_json(trusted) + "\n" + wrap_untrusted("brand_written", _safe_json(brand))

    if tool_name == PLAN_MY_WEEK:
        # The festival calendar is attached here, against the dates Spring sent -- never a date
        # this process or the model worked out. `enrich_week_plan` returns `data` untouched when
        # there is no usable date to enrich against.
        enriched = enrich_week_plan(data)
        if not isinstance(enriched, dict):
            return _safe_json(enriched)
        trusted = {k: v for k, v in enriched.items() if k in _TRUSTED_KEYS_PLAN_MY_WEEK}
        editorial = {k: v for k, v in enriched.items() if k not in _TRUSTED_KEYS_PLAN_MY_WEEK}
        if not editorial:
            return _safe_json(trusted)
        return _safe_json(trusted) + "\n" + wrap_untrusted("editorial", _safe_json(editorial))

    if tool_name == GET_MY_CONTENT_PATTERNS:
        # Spec 4.4/4.5: trusted field-for-field, `evidence.post_ids` dropped everywhere. The four
        # lists are the only containers; each is rebuilt element by element (never trusted
        # whole), and one that fails its allow-list moves, whole, into the wrapper.
        trusted, unclassified = _split_trusted_scalar(
            data,
            _TRUSTED_KEYS_GET_MY_CONTENT_PATTERNS,
            container_keys=frozenset(_CONTENT_PATTERNS_LISTS),
        )
        # 2026-09-26: the creator's own caption lines leave the posts BEFORE the allow-list check,
        # so they are never trusted; they go to the model only inside their own wrapper below.
        captions: dict[str, list[dict[str, Any]]] = {}
        for key in _CONTENT_CAPTION_LISTS:
            if key in trusted:
                trusted[key], found = _split_post_captions(trusted[key])
                if found:
                    captions[key] = found
        for key, element_keys in _CONTENT_PATTERNS_LISTS.items():
            if key not in trusted:
                continue
            cleaned = _content_list_for_model(trusted[key], element_keys)
            if cleaned is None:
                unclassified[key] = trusted.pop(key)
            else:
                trusted[key] = cleaned
        parts = [_safe_json(trusted)]
        if unclassified:
            parts.append(
                wrap_untrusted("unclassified", _safe_json(_strip_post_ids(unclassified)))
            )
        if captions:
            # ensure_ascii=False: a Hindi or emoji caption reaches Meera as its own letters, not
            # as \u escapes. Line breaks were removed above, so the block stays one JSON line.
            parts.append(
                wrap_untrusted("creator_captions", json.dumps(captions, ensure_ascii=False))
            )
        return "\n".join(parts)

    if tool_name == GET_TODAYS_TOPICS:
        # Everything except the server-computed date goes in the wrapper, `topics` included --
        # there is no per-topic trusted field worth splitting out (an id alone tells the model
        # nothing), so the whole list is editorial text.
        trusted, editorial = _split_trusted_scalar(data, _TRUSTED_KEYS_GET_TODAYS_TOPICS)
        if not editorial:
            return _safe_json(data)
        return _safe_json(trusted) + "\n" + wrap_untrusted("editorial", _safe_json(editorial))

    if tool_name == CHECK_DEAL_RISKS:
        # R2: none of this tool's trusted keys is a known container -- `target` holding a
        # dict (C1) is exactly as untrusted as an unrecognised key.
        trusted, brand = _split_trusted_scalar(data, _TRUSTED_KEYS_CHECK_DEAL_RISKS)
        if not brand:
            return _safe_json(data)
        return _safe_json(trusted) + "\n" + wrap_untrusted("brand_written", _safe_json(brand))

    if tool_name == GET_MY_DEALS:
        deals = data.get("deals")
        data_without_deals = {k: v for k, v in data.items() if k != "deals"}
        # Unknown TOP-LEVEL keys (not "deals", not in the trusted set) are brand-written by
        # default too -- KC-1's allow-list applies at both levels, not only per-deal. R2:
        # neither `active_count` nor `completed_count` is a known container, so
        # `_split_trusted_scalar` (no `container_keys`) folds a non-scalar value under
        # either name (M4) into `other_top_level` the same way it folds an unrecognised key.
        trusted_top, other_top_level = _split_trusted_scalar(
            data_without_deals, _TRUSTED_KEYS_GET_MY_DEALS
        )
        # F-1771(b): GetMyDealsResult.deals is a Java List -- a "deals" value that IS
        # present but is not a list (Kabir/Priya's probe: a dict) is not a shape Spring
        # ever sends, and the allow-list default applies to SHAPE too, not only to field
        # names. Fold it into the brand bucket instead of letting it ride along in
        # `trusted` untouched.
        if "deals" in data and deals is not None and not isinstance(deals, list):
            brand_other = dict(other_top_level)
            brand_other["deals"] = deals
            return (
                _safe_json(trusted_top)
                + "\n"
                + wrap_untrusted("brand_written", _safe_json({"_other": brand_other}))
            )

        if not deals:
            if not other_top_level:
                return _safe_json(data)
            trusted = dict(trusted_top)
            if "deals" in data:
                trusted["deals"] = deals
            return (
                _safe_json(trusted)
                + "\n"
                + wrap_untrusted("brand_written", _safe_json({"_other": other_top_level}))
            )

        brand_by_deal: dict[str, Any] = {}
        trusted_deals: list[Any] = []
        for i, deal in enumerate(deals):
            if not isinstance(deal, dict):
                # F-1771(b): a non-dict element (Kabir/Priya's probe shape) is not a
                # DealSummary either -- wrap it whole, keyed by position like a dict
                # deal's brand fields, with a placeholder left in `trusted_deals` so
                # positions still line up for the model to match a wrapped entry back
                # to its slot.
                brand_by_deal[str(i)] = {"_value": deal}
                trusted_deals.append(None)
                continue
            # R2: a recognised per-deal key holding a non-scalar value (M3: `next_action` a
            # dict; M6: `status` a list) is exactly as untrusted as an unlisted per-deal key
            # -- none of DealSummary's trusted fields is itself a container.
            trusted_deal, brand_fields = _split_trusted_scalar(
                deal, _TRUSTED_DEAL_FIELDS_GET_MY_DEALS
            )
            if brand_fields:
                # Keyed by POSITION, not `deal_id` (Kabir, "Re-check — K-3 conditions" LOW #4):
                # two deals sharing an id, or a deal with none, collided under a deal_id key and
                # one deal's brand fields were silently overwritten -- lost from the model's copy
                # entirely, not merely leaked. `trusted_deals` is the same list in the same order,
                # so the model matches a wrapped entry back to its trusted deal by position in the
                # (unchanged-order) `deals` array, and every deal's brand fields survive.
                brand_by_deal[str(i)] = brand_fields
            trusted_deals.append(trusted_deal)

        brand: dict[str, Any] = dict(brand_by_deal)
        if other_top_level:
            brand["_other"] = other_top_level
        if not brand:
            return _safe_json(data)
        trusted = dict(trusted_top)
        trusted["deals"] = trusted_deals
        return _safe_json(trusted) + "\n" + wrap_untrusted("brand_written", _safe_json(brand))

    return _safe_json(data)
