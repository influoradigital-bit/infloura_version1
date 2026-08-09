"""Claude provider client — chat + streaming, prompt caching, circuit breaker.

Claude is the brain for chat (§6 routing table): quality matters, and prompt
caching on Block A/B is the ~65% cost lever. This module wraps the Anthropic
SDK with:
- per-call timeout (first-token target 8s, per app/config.py)
- a simple circuit breaker (open on sustained failure -> surfaced error,
  credits NOT consumed, per the resilience table)
- cancellation support (caller passes an asyncio.Event / cancellation checked
  between chunks) so a client disconnect stops wasting provider tokens
"""

from __future__ import annotations

import logging
import time
from collections.abc import AsyncIterator
from dataclasses import dataclass
from typing import Any, cast

import anthropic

from app.config import CLAUDE_MODEL, get_settings

logger = logging.getLogger(__name__)


class CircuitOpenError(Exception):
    """Raised when the breaker is open — caller must surface a degraded error,
    never silently retry into a known-bad provider."""


@dataclass
class _BreakerState:
    consecutive_failures: int = 0
    opened_at: float | None = None


class CircuitBreaker:
    def __init__(self, failure_threshold: int, recovery_seconds: float):
        self._threshold = failure_threshold
        self._recovery = recovery_seconds
        self._state = _BreakerState()

    def before_call(self) -> None:
        if self._state.opened_at is None:
            return
        if time.monotonic() - self._state.opened_at >= self._recovery:
            # half-open: allow a probe call through
            return
        raise CircuitOpenError("circuit open: provider recently failed repeatedly")

    def on_success(self) -> None:
        self._state = _BreakerState()

    def on_failure(self) -> None:
        self._state.consecutive_failures += 1
        if self._state.consecutive_failures >= self._threshold:
            self._state.opened_at = time.monotonic()


@dataclass
class ClaudeStreamEvent:
    type: str  # "text" | "tool_use" | "truncated" | "usage"
    text: str | None = None
    tool_name: str | None = None
    tool_input: dict[str, Any] | None = None
    tool_use_id: str | None = None
    usage: dict[str, Any] | None = None
    # P1 BLANK TURN fix (2026-07-24, wiki/ai-review/meera-blank-turn-ai-review.md
    # F1). Carries the Anthropic `stop_reason` through instead of discarding it
    # (previously read at message_stop and thrown away) -- callers need it to
    # tell a clean "end_turn" apart from a "max_tokens" cut.
    stop_reason: str | None = None
    # Set only on a "truncated" event: the tool name whose `tool_use` block was
    # open but never closed (no content_block_stop) when the turn ended. The
    # partial JSON itself is deliberately NOT carried here -- see the
    # "truncated" emission below for why it must never be salvaged.
    tool_name_partial: str | None = None


@dataclass
class ClaudeToolResult:
    """Result of a single non-streaming turn where Claude was forced to answer
    via exactly one tool call (`tool_choice: {type: "tool", name: ...}`).
    Never raises on provider/parse failure — callers get `ok: bool` and degrade
    gracefully, matching the GeminiProvider result-dataclass convention.
    """

    ok: bool
    tool_input: dict[str, Any] | None = None
    error: str | None = None
    usage: dict[str, Any] | None = None


@dataclass
class ClaudeTextResult:
    """Result of a single non-streaming turn where Claude answers in plain
    text (no tool_choice) — used by cheap phrasing routes (e.g.
    POST /internal/trendspark/nudge) that need one text completion, not
    structured tool_use JSON. Never raises on provider/parse failure; callers
    get `ok: bool` and degrade to a deterministic fallback, same convention
    as `ClaudeToolResult`.
    """

    ok: bool
    text: str | None = None
    error: str | None = None
    usage: dict[str, Any] | None = None


class ClaudeProvider:
    def __init__(self) -> None:
        settings = get_settings()
        self._settings = settings
        self._client = anthropic.AsyncAnthropic(
            api_key=settings.anthropic_api_key,
            timeout=anthropic.Timeout(
                connect=settings.timeouts.claude_connect,
                read=settings.timeouts.claude_read,
                write=settings.timeouts.claude_read,
                pool=settings.timeouts.claude_connect,
            ),
        )
        self._breaker = CircuitBreaker(
            failure_threshold=settings.breaker.failure_threshold,
            recovery_seconds=settings.breaker.recovery_seconds,
        )

    async def stream_turn(
        self,
        *,
        system_blocks: list[dict[str, Any]],
        messages: list[dict[str, Any]],
        tools: list[dict[str, Any]],
        max_tokens: int = 1024,
        is_cancelled: Any = None,
    ) -> AsyncIterator[ClaudeStreamEvent]:
        """Streams one assistant turn. Yields text deltas and any tool_use blocks
        as they complete. `is_cancelled` is an optional zero-arg callable; when it
        returns True, the stream is closed and no further provider tokens are
        consumed (client-disconnect cancellation).
        """
        self._breaker.before_call()
        try:
            # The SDK types `system`/`messages`/`tools` as TypedDict iterables;
            # this service builds them dynamically as plain dicts (Block A/B/C
            # assembly). cast is the narrow, explicit acknowledgement of that
            # boundary rather than an untyped call.
            async with self._client.messages.stream(
                model=CLAUDE_MODEL,
                max_tokens=max_tokens,
                system=cast("Any", system_blocks),
                messages=cast("Any", messages),
                tools=cast("Any", tools),
            ) as stream:
                current_tool: dict[str, Any] | None = None
                # F-02: usage was emitted ONLY at message_stop, so a cancelled
                # stream returned with no usage at all and chat.py's
                # `if final_usage:` guard skipped record_spend entirely. The
                # provider still bills the full cached prefix and every token it
                # generated before the disconnect, so a loop of
                # connect-and-hang-up drove unbounded real spend while
                # get_global_total_today() never moved. These fields are
                # accumulated as the stream runs and flushed on cancellation.
                partial_usage: dict[str, Any] = {}

                def _snapshot_usage(source: Any) -> None:
                    """Merge whatever usage fields this event carries."""
                    if source is None:
                        return
                    for key in (
                        "input_tokens",
                        "output_tokens",
                        "cache_read_input_tokens",
                        "cache_creation_input_tokens",
                    ):
                        value = getattr(source, key, None)
                        if value is not None:
                            partial_usage[key] = value

                async for event in stream:
                    if event.type == "message_start":
                        # Carries input_tokens + both cache-token fields up front.
                        _snapshot_usage(getattr(getattr(event, "message", None), "usage", None))
                    elif event.type == "message_delta":
                        # Carries the running output_tokens count.
                        _snapshot_usage(getattr(event, "usage", None))

                    if is_cancelled and is_cancelled():
                        await stream.close()
                        # Flush what the provider has already billed us for.
                        if partial_usage:
                            partial_usage.setdefault("input_tokens", 0)
                            partial_usage.setdefault("output_tokens", 0)
                            yield ClaudeStreamEvent(
                                type="usage",
                                usage={**partial_usage, "partial": True},
                                stop_reason="client_disconnected",
                            )
                        return

                    if event.type == "content_block_start":
                        block = event.content_block
                        if getattr(block, "type", None) == "tool_use":
                            # getattr, not attribute access: the union also holds
                            # TextBlock, which has neither `id` nor `name`. mypy
                            # flagged exactly this — and an SDK block shape that
                            # ever fails the `type` check would have been an
                            # AttributeError crash mid-stream, not a skipped block.
                            current_tool = {
                                "id": getattr(block, "id", ""),
                                "name": getattr(block, "name", ""),
                                "partial_json": "",
                            }
                    elif event.type == "content_block_delta":
                        delta = event.delta
                        if getattr(delta, "type", None) == "text_delta":
                            yield ClaudeStreamEvent(type="text", text=getattr(delta, "text", ""))
                        elif getattr(delta, "type", None) == "input_json_delta" and current_tool:
                            current_tool["partial_json"] += getattr(delta, "partial_json", "")
                    elif event.type == "content_block_stop" and current_tool:
                        import json as _json

                        try:
                            tool_input = _json.loads(current_tool["partial_json"] or "{}")
                        except ValueError:
                            tool_input = {}
                        yield ClaudeStreamEvent(
                            type="tool_use",
                            tool_name=current_tool["name"],
                            tool_input=tool_input,
                            tool_use_id=current_tool["id"],
                        )
                        current_tool = None
                    elif event.type == "message_stop":
                        final_message = await stream.get_final_message()
                        usage = getattr(final_message, "usage", None)
                        stop_reason = getattr(final_message, "stop_reason", None)

                        if current_tool is not None:
                            # P1 BLANK TURN fix (F1): the API ended the turn
                            # without ever sending content_block_stop for this
                            # tool_use block -- classically a max_tokens cut
                            # mid input_json_delta. Previously this whole tool
                            # call was silently dropped: no event, no log, no
                            # error, and the caller saw an empty turn with no
                            # way to know why (~28% of Meera turns, traced
                            # 2026-07-24). Do NOT parse/salvage
                            # current_tool["partial_json"] here -- the
                            # Anthropic SDK will happily hand back a
                            # partial/best-effort object, and accepting it
                            # would mean silently forwarding a half-built
                            # create_campaign (or worse, a half-built
                            # request_payment) as though the model had fully
                            # committed to it. A truncated tool call MUST be
                            # treated as "no tool call happened", loudly.
                            logger.warning(
                                "claude stream truncated mid tool_use tool=%s "
                                "stop_reason=%s partial_json_len=%d",
                                current_tool["name"],
                                stop_reason,
                                len(current_tool["partial_json"]),
                            )
                            yield ClaudeStreamEvent(
                                type="truncated",
                                stop_reason=stop_reason,
                                tool_name_partial=current_tool["name"],
                            )
                            current_tool = None

                        yield ClaudeStreamEvent(
                            type="usage",
                            usage={
                                "input_tokens": getattr(usage, "input_tokens", None),
                                "output_tokens": getattr(usage, "output_tokens", None),
                                "cache_read_input_tokens": getattr(
                                    usage, "cache_read_input_tokens", None
                                ),
                                "cache_creation_input_tokens": getattr(
                                    usage, "cache_creation_input_tokens", None
                                ),
                            }
                            if usage
                            else None,
                            stop_reason=stop_reason,
                        )
            self._breaker.on_success()
        except CircuitOpenError:
            raise
        except anthropic.APIError:
            self._breaker.on_failure()
            raise
        except Exception:
            self._breaker.on_failure()
            raise

    async def complete_text(
        self,
        *,
        system: str,
        user: str,
        model: str,
        max_tokens: int = 1024,
    ) -> ClaudeTextResult:
        """Single non-streaming turn, plain-text output (no tools). Used by
        cheap, single-shot phrasing calls (Trend-Spark nudge) that just need
        one string back, not a chat stream or a forced tool_use.

        Never raises — provider errors, timeouts, and circuit-open all come
        back as `ok=False` so callers can degrade to a deterministic
        fallback (e.g. trendspark.py's templated nudge) without crashing the
        request. Mirrors `complete_with_forced_tool`'s error-handling shape.
        """
        try:
            self._breaker.before_call()
        except CircuitOpenError as exc:
            return ClaudeTextResult(ok=False, error=f"circuit_open: {exc}")

        try:
            response = await self._client.messages.create(
                model=model,
                max_tokens=max_tokens,
                system=[{"type": "text", "text": system}],
                messages=[{"role": "user", "content": user}],
            )
            self._breaker.on_success()
        except anthropic.APIError as exc:
            self._breaker.on_failure()
            logger.warning("claude complete_text failed: %s", type(exc).__name__)
            return ClaudeTextResult(ok=False, error="provider_error")
        except Exception as exc:  # noqa: BLE001 - provider SDKs raise anything; callers degrade on ok=False
            self._breaker.on_failure()
            logger.warning("claude complete_text failed: %s", type(exc).__name__)
            return ClaudeTextResult(ok=False, error="provider_error")

        text_block = next(
            (block for block in response.content if getattr(block, "type", None) == "text"),
            None,
        )
        text = getattr(text_block, "text", None) if text_block is not None else None

        usage = getattr(response, "usage", None)
        return ClaudeTextResult(
            ok=True,
            text=text,
            usage={
                "input_tokens": getattr(usage, "input_tokens", None),
                "output_tokens": getattr(usage, "output_tokens", None),
                "cache_read_input_tokens": getattr(usage, "cache_read_input_tokens", None),
                "cache_creation_input_tokens": getattr(usage, "cache_creation_input_tokens", None),
            }
            if usage
            else None,
        )

    async def complete_with_forced_tool(
        self,
        *,
        system_blocks: list[dict[str, Any]],
        messages: list[dict[str, Any]],
        tool_schema: dict[str, Any],
        max_tokens: int = 1024,
        model: str = CLAUDE_MODEL,
    ) -> ClaudeToolResult:
        """Single non-streaming turn, forced to answer via exactly one named
        tool (`tool_choice`) so the response is structured JSON, never prose.
        Used by internal/batch-analysis routes (e.g. /internal/brand-safety)
        that need one parseable result per call rather than a chat stream.

        `model` defaults to `CLAUDE_MODEL` (Sonnet) but is overridable per
        call site -- e.g. routes/brand_safety.py passes `BRAND_SAFETY_MODEL`
        (app/config.py) so a future GARM model swap (Haiku-class, gated on
        the GARM eval per that config constant's docstring) doesn't require
        touching this provider method.

        Never raises — provider errors, timeouts, and unparseable tool input
        all come back as `ok=False` so callers can degrade (e.g. return a
        typed 502 without crashing the request).
        """
        try:
            self._breaker.before_call()
        except CircuitOpenError as exc:
            return ClaudeToolResult(ok=False, error=f"circuit_open: {exc}")

        try:
            response = await self._client.messages.create(
                model=model,
                max_tokens=max_tokens,
                system=cast("Any", system_blocks),
                messages=cast("Any", messages),
                tools=cast("Any", [tool_schema]),
                tool_choice=cast("Any", {"type": "tool", "name": tool_schema["name"]}),
            )
            self._breaker.on_success()
        except anthropic.APIError as exc:
            self._breaker.on_failure()
            logger.warning("claude complete_with_forced_tool failed: %s", type(exc).__name__)
            return ClaudeToolResult(ok=False, error="provider_error")
        except Exception as exc:  # noqa: BLE001 - provider SDKs raise anything; callers degrade on ok=False
            self._breaker.on_failure()
            logger.warning("claude complete_with_forced_tool failed: %s", type(exc).__name__)
            return ClaudeToolResult(ok=False, error="provider_error")

        usage = getattr(response, "usage", None)
        usage_dict = {
            "input_tokens": getattr(usage, "input_tokens", None),
            "output_tokens": getattr(usage, "output_tokens", None),
            "cache_read_input_tokens": getattr(usage, "cache_read_input_tokens", None),
            "cache_creation_input_tokens": getattr(usage, "cache_creation_input_tokens", None),
        } if usage else None

        tool_use_block = next(
            (block for block in response.content if getattr(block, "type", None) == "tool_use"),
            None,
        )
        if tool_use_block is None:
            # F-06: this DISCARDED a populated usage object. The call succeeded
            # at HTTP 200 and was billed in full — a brand-safety batch that
            # hits max_tokens costs ~$0.07 and used to record $0, because every
            # caller records spend only on `ok`. Carry the usage so the caller
            # can bill what the provider billed.
            return ClaudeToolResult(
                ok=False, error="no_tool_use_in_response", usage=usage_dict
            )

        return ClaudeToolResult(
            ok=True,
            # getattr: the content union also holds TextBlock, which has no `input`.
            tool_input=dict(getattr(tool_use_block, "input", None) or {}),
            usage=usage_dict,
        )
