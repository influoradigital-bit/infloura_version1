"""T-CREATOR-CREDITS-V2 -- acceptance criteria A40-A44 (SPEC.md section 12).

`influora-api` charges the creator's credits at SEND (`MeeraSessionService#doSendTurn`), keyed on
the server-minted `messageId` that the stream token carries. This file pins what `app/routes/chat.py`
must do with that charge on the CREATOR path:

  A40 (K-04) every creator refusal after token verification gives the charge back through
             `release_turn_credit`, exactly once, keyed on the VERIFIED messageId. Checked two ways:
             at runtime for every refusal we can drive, and statically (AST) for every refusal
             `return`/`raise` in `chat()` so a refusal added later without a release fails here.
  A41 (K-05) a provider failure / empty reply on a creator turn refunds through `release_charge`
             with the VERIFIED conversation id -- never a value read back from the request body.
  A42 (K-16) a creator who reads tokens and disconnects is never refunded.
  A43 (K-17) the model saying "I can't help with that" is not a server-observable refusal: the
             turn is persisted and stays charged.
  A44 (K-22) no creator tool (and nothing influora-ai can call on Spring) can grant credits.

Style follows tests/routes/test_chat_money_path.py and tests/routes/test_chat_creator_audience.py:
`chat()` is driven end to end with `verify_token_async`, Spring, Claude and the tool loop mocked.
"""

from __future__ import annotations

import ast
import inspect
import json
import re
import textwrap
from typing import Any
from unittest.mock import AsyncMock, MagicMock, patch

import pytest
from fastapi import HTTPException, Request

from app.auth.service_token import VerifiedToken
from app.clients.spring import SpringCallError, SpringInternalClient, SpringResponse
from app.config import get_settings
from app.costs import spend_tracker
from app.costs.spend_tracker import SpendCapExceeded
from app.prompt.assembler import assemble_prompt
from app.routes import chat as chat_route
from app.tools import creator_schemas
from app.tools.loop import LoopEvent, ToolLoopCapExceeded

CREATOR_ID = "creator-user-credits-001"
CONVERSATION_ID = "conv-creator-credits-1"
STREAM_MESSAGE_ID = "01HCREATORTURN_SERVER_MINTED_A"
CLIENT_TURN_ID = "client-supplied-turn-id"
ATTACKER_CONVERSATION_ID = "conv-someone-else-entirely"

# The functions `chat()` answers a refusal with. A refusal is a `return <one of these>(...)` or a
# `raise auth_error_to_http(...)` that happens after the token was verified.
REFUSAL_RESPONDERS = {"_error_response", "_creator_cap_response", "_consent_required_response"}
REFUSAL_RAISERS = {"auth_error_to_http"}


# --------------------------------------------------------------------------------------- helpers


def _make_request(body: dict) -> Request:
    body_bytes = json.dumps(body).encode()

    async def receive():
        return {"type": "http.request", "body": body_bytes, "more_body": False}

    scope = {
        "type": "http",
        "method": "POST",
        "path": "/chat",
        "headers": [],
        "query_string": b"",
        "client": ("test", 0),
    }
    return Request(scope, receive)


def _creator_stream_token() -> VerifiedToken:
    """What StreamTokenService.mint issues for a creator turn: bound to one conversation, carrying
    the server-minted messageId the send-time charge is keyed on, and userType=CREATOR."""
    return VerifiedToken(
        workspace_id=CREATOR_ID,
        scope="chat:stream",
        subject="creator-1",
        conversation_id=CONVERSATION_ID,
        claims={"messageId": STREAM_MESSAGE_ID, "userType": "CREATOR"},
    )


def _body(conversation_id: str = CONVERSATION_ID, conversation: list | None = None) -> dict:
    return {
        "workspace_id": CREATOR_ID,
        "conversation_id": conversation_id,
        "stream_token": "irrelevant-because-verify_token_async-is-mocked",
        "onbehalf_jwt": "onbehalf-jwt-FAKE",
        # A client-chosen turn id must never become the refund key (Kabir FAIL 2).
        "turn_id": CLIENT_TURN_ID,
        "conversation": conversation if conversation is not None else [{"role": "user", "content": "hi"}],
    }


def _creator_context(consented: bool = True, **extra) -> dict:
    ctx = {
        "workspace_id": CREATOR_ID,
        "audience": "CREATOR",
        "display_name": "Priya Shah",
        "first_name": "Priya",
        "city": "Pune",
        "tier": "MICRO",
        "categories": ["Fashion"],
        "creator_language": "hi-IN",
        "brand_tone": "FRIENDLY",
        "floors": {"reel_floor": "9,999", "story_set_floor": "800", "post_floor": "1,500"},
        "metrics_summary": {"followers": "12,400 followers"},
        "deals_summary": {"active_count": 1, "completed_count": 2, "total_earned_inr": "5,000"},
        "approval_level": 0,
        "represented": False,
        "identity": {"kyc_done": True, "gstin_present": False},
        "consent_accepted": consented,
    }
    ctx.update(extra)
    return ctx


def _spring(context: dict | None = None, error: Exception | None = None) -> MagicMock:
    spring = MagicMock()
    if error is not None:
        spring.get_meera_context = AsyncMock(side_effect=error)
    else:
        data = context if context is not None else _creator_context()
        spring.get_meera_context = AsyncMock(
            return_value=SpringResponse(status_code=200, data=data, raw={"data": data})
        )
    spring.persist_assistant_message = AsyncMock(
        return_value=SpringResponse(status_code=200, data={}, raw={})
    )
    spring.release_turn_credit = AsyncMock(return_value=SpringResponse(status_code=200, data={}, raw={}))
    return spring


def _blocked_gate(error_code: str) -> MagicMock:
    gate = MagicMock()
    gate.allowed = False
    gate.error_code = error_code
    gate.error_message = "spend gate blocked this call"
    gate.reservation = None
    return gate


class _LoopSpy:
    """Stands in for `run_tool_loop`; records whether a provider turn was ever started."""

    def __init__(self, events: list[LoopEvent] | None = None, raise_after: Exception | None = None):
        self.calls = 0
        # `events or [...]` would silently discard an intentionally EMPTY list (falsy in Python),
        # falling back to the two-event default and never reaching `raise_after` at all -- exactly
        # the bug that let the tool_loop_cap/provider_exception scenarios below pass through a
        # "token"+"done" pair instead of ever raising. `is None` is the only correct sentinel check.
        self._events = events if events is not None else [
            LoopEvent(type="token", text="should never be reached"),
            LoopEvent(type="done", finish_reason="stop", usage=None),
        ]
        self._raise_after = raise_after

    def __call__(self, **kwargs):
        self.calls += 1
        return self._gen()

    async def _gen(self):
        for event in self._events:
            yield event
        if self._raise_after is not None:
            raise self._raise_after


class _ConversationIdReadBackBody(dict):
    """A request body whose `conversation_id` is honest on the FIRST read -- the stream-token
    binding check, which passes -- and names somebody else's conversation on every read after it.

    The body is client-controlled; only the token binding is verified. So any code that reads
    `body["conversation_id"]` again after the binding check is trusting an unverified value, and
    this body makes that visible: the refund would go out for ATTACKER_CONVERSATION_ID."""

    def __init__(self, data: dict):
        super().__init__(data)
        self.conversation_id_reads = 0

    def get(self, key, default=None):
        if key == "conversation_id":
            self.conversation_id_reads += 1
            if self.conversation_id_reads > 1:
                return ATTACKER_CONVERSATION_ID
        return super().get(key, default)

    def __getitem__(self, key):
        if key == "conversation_id":
            self.conversation_id_reads += 1
            if self.conversation_id_reads > 1:
                return ATTACKER_CONVERSATION_ID
        return super().__getitem__(key)


async def _drain(response) -> str:
    chunks = []
    async for chunk in response.body_iterator:
        chunks.append(chunk if isinstance(chunk, str) else chunk.decode())
    return "".join(chunks)


@pytest.fixture(autouse=True)
async def _reset(monkeypatch):
    for var in (
        "AI_SPEND_KILL_SWITCH",
        "WORKSPACE_DAILY_HARD_CAP_USD",
        "AI_CREATOR_MONTHLY_CAP_USD",
        "REDIS_URL",
        "AI_MAX_HISTORY_CHARS",
    ):
        monkeypatch.delenv(var, raising=False)
    monkeypatch.setenv("AI_DAILY_SPEND_CEILING_USD", "999999")
    monkeypatch.setenv("AI_MAX_HISTORY_TURNS", "40")
    get_settings.cache_clear()
    await spend_tracker.reset_for_testing()
    yield
    for var in ("AI_DAILY_SPEND_CEILING_USD", "AI_MAX_HISTORY_TURNS"):
        monkeypatch.delenv(var, raising=False)
    get_settings.cache_clear()
    await spend_tracker.reset_for_testing()


# ------------------------------------------------------------------------------------------ A40

# Every creator refusal that can happen after `verify_token_async` succeeded. Each entry: the
# expected HTTP status and the patches that force the route onto that refusal.
_CREATOR_REFUSALS: list[dict[str, Any]] = [
    {
        "name": "conversation_mismatch",
        "status": 403,
        "body": lambda: _body(conversation_id="conv-not-the-bound-one"),
    },
    {
        "name": "history_too_large",
        "status": 413,
        "body": lambda: _body(
            conversation=[{"role": "user", "content": f"turn {i}"} for i in range(41)]
        ),
    },
    {
        "name": "spend_gate_kill_switch_503",
        "status": 503,
        "gate": lambda: _blocked_gate("AI_KILL_SWITCH_ACTIVE"),
    },
    {
        "name": "workspace_daily_cap_429",
        "status": 429,
        "gate": lambda: _blocked_gate(chat_route.WORKSPACE_CAP_CODE),
    },
    {
        "name": "creator_context_unavailable",
        "status": 503,
        "spring": lambda: _spring(error=RuntimeError("spring is down")),
    },
    {
        "name": "creator_context_unauthorized",
        "status": 403,
        "spring": lambda: _spring(error=SpringCallError(401, "unauthorized", "token rejected")),
    },
    {
        "name": "audience_mismatch",
        "status": 403,
        "spring": lambda: _spring(_creator_context(audience="BRAND")),
    },
    {
        "name": "consent_required",
        "status": 403,
        "spring": lambda: _spring(_creator_context(consented=False)),
    },
    {
        "name": "creator_monthly_usd_cap",
        "status": 429,
        "creator_gate": lambda: AsyncMock(side_effect=SpendCapExceeded(creator_id=CREATOR_ID)),
    },
    {
        "name": "failed_before_stream",
        "status": 503,
        "assemble_prompt": lambda: MagicMock(side_effect=RuntimeError("prompt assembly blew up")),
    },
]


async def _drive_refusal(scenario: dict[str, Any]) -> tuple[int, MagicMock, _LoopSpy]:
    body = scenario.get("body", _body)()
    spring = scenario.get("spring", _spring)()
    loop = _LoopSpy()
    patches = [
        patch.object(chat_route, "verify_token_async", AsyncMock(return_value=_creator_stream_token())),
        patch.object(chat_route, "_get_spring", MagicMock(return_value=spring)),
        patch.object(chat_route, "_get_claude", MagicMock(return_value=MagicMock())),
        patch.object(chat_route, "run_tool_loop", loop),
    ]
    if "gate" in scenario:
        patches.append(patch.object(chat_route, "check_spend_gate", AsyncMock(return_value=scenario["gate"]())))
    if "creator_gate" in scenario:
        patches.append(patch.object(chat_route, "check_creator_spend_gate", scenario["creator_gate"]()))
    if "assemble_prompt" in scenario:
        patches.append(patch.object(chat_route, "assemble_prompt", scenario["assemble_prompt"]()))

    for p in patches:
        p.start()
    try:
        try:
            response = await chat_route.chat(_make_request(body), authorization="Bearer whatever")
            status_code = response.status_code
            if status_code == 200:
                # Not a refusal at all -- drain so a wrongly-started stream is visible below.
                await _drain(response)
        except HTTPException as exc:
            status_code = exc.status_code
    finally:
        for p in reversed(patches):
            p.stop()
    return status_code, spring, loop


def _is_release_early_stmt(stmt: ast.stmt) -> bool:
    return (
        isinstance(stmt, ast.Expr)
        and isinstance(stmt.value, ast.Await)
        and isinstance(stmt.value.value, ast.Call)
        and isinstance(stmt.value.value.func, ast.Name)
        and stmt.value.value.func.id == "release_early"
    )


def _call_name(node: ast.AST | None) -> str | None:
    if isinstance(node, ast.Call) and isinstance(node.func, ast.Name):
        return node.func.id
    return None


def _is_refusal_stmt(stmt: ast.stmt) -> bool:
    if isinstance(stmt, ast.Return):
        return _call_name(stmt.value) in REFUSAL_RESPONDERS
    if isinstance(stmt, ast.Raise):
        return _call_name(stmt.exc) in REFUSAL_RAISERS
    return False


def _refusal_sites_in_chat() -> tuple[list[tuple[int, bool]], int]:
    """Every refusal `return`/`raise` in `chat()` after the token is verified, each paired with
    whether an unconditional `await release_early(...)` runs before it on its own path.

    "Runs before it on its own path" = an `await release_early(...)` statement that is an EARLIER
    SIBLING of the refusal, or of any block enclosing it, inside `chat()`. Nested functions
    (`release_early` itself, `event_stream`) are not refusal sites and are not descended into.
    Returns (sites, verify_end_line)."""
    source = textwrap.dedent(inspect.getsource(chat_route.chat))
    first_line = inspect.getsourcelines(chat_route.chat)[1]
    tree = ast.parse(source)
    func = tree.body[0]
    assert isinstance(func, ast.AsyncFunctionDef) and func.name == "chat"

    verify_end = None
    for node in ast.walk(func):
        if isinstance(node, ast.Try) and any("verify_token_async" in ast.unparse(s) for s in node.body):
            verify_end = node.end_lineno
            break
    assert verify_end is not None, "could not find the verify_token_async try-block in chat()"

    sites: list[tuple[int, bool]] = []

    def visit(stmts: list[ast.stmt], covered_above: bool) -> None:
        covered = covered_above
        for stmt in stmts:
            if isinstance(stmt, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
                continue
            if _is_release_early_stmt(stmt):
                covered = True
            if _is_refusal_stmt(stmt) and stmt.lineno > verify_end:
                sites.append((stmt.lineno + first_line - 1, covered))
            for field in ("body", "orelse", "finalbody"):
                child = getattr(stmt, field, None)
                if isinstance(child, list) and child and isinstance(child[0], ast.stmt):
                    visit(child, covered)
            for handler in getattr(stmt, "handlers", None) or []:
                visit(handler.body, covered)
            for case in getattr(stmt, "cases", None) or []:
                visit(case.body, covered)

    visit(func.body, False)
    return sites, verify_end


@pytest.mark.asyncio
async def test_every_creator_refusal_releases():
    """A40 / K-04. A creator was charged at send. Every way `chat()` refuses that turn after the
    token verified must give the charge back -- once, keyed on the token's messageId (never the
    body's turn_id), for the token's conversation (never the body's) -- and must never start a
    provider turn.

    Part 1 drives every creator refusal at runtime. Part 2 is the grep-derived half: it parses
    `chat()` and requires every refusal return/raise after verification to be preceded, on its own
    path, by `await release_early(...)`, so a refusal added later without a release fails here even
    if nobody adds a runtime scenario for it."""
    failures: list[str] = []

    # ---- Part 1: runtime, one scenario per refusal ------------------------------------------
    for scenario in _CREATOR_REFUSALS:
        status_code, spring, loop = await _drive_refusal(scenario)
        name = scenario["name"]
        if status_code != scenario["status"]:
            failures.append(f"{name}: expected HTTP {scenario['status']}, got {status_code}")
        if loop.calls:
            failures.append(f"{name}: the provider tool loop was started on a refused turn")
        calls = spring.release_turn_credit.await_args_list
        if len(calls) != 1:
            failures.append(f"{name}: release_turn_credit awaited {len(calls)} times, expected exactly 1")
            continue
        kwargs = calls[0].kwargs
        if kwargs.get("turn_id") != STREAM_MESSAGE_ID:
            failures.append(
                f"{name}: released turn_id={kwargs.get('turn_id')!r}, expected the VERIFIED messageId"
            )
        if kwargs.get("conversation_id") != CONVERSATION_ID:
            failures.append(
                f"{name}: released conversation_id={kwargs.get('conversation_id')!r}, "
                f"expected the token-bound {CONVERSATION_ID!r}"
            )
        if spring.persist_assistant_message.await_count:
            failures.append(f"{name}: a refused turn persisted an assistant reply")

    # ---- Part 2: grep-derived, every refusal site in chat() --------------------------------
    sites, _ = _refusal_sites_in_chat()
    # Non-vacuous: the creator path alone has 9 refusal sites today (binding raise, history, spend
    # gate, 3 creator-context returns, consent, monthly cap, failed-before-stream). A parser that
    # found nothing must not pass.
    if len(sites) < 9:
        failures.append(f"grep-derived: found only {len(sites)} refusal sites in chat(); the parser is broken")
    uncovered = [line for line, covered in sites if not covered]
    if uncovered:
        failures.append(
            "grep-derived: refusal return/raise with no preceding `await release_early(...)` at "
            f"app/routes/chat.py lines {uncovered}"
        )
    # Every refusal site is covered => #refusals that release == #refusals.
    released = sum(1 for _, covered in sites if covered)
    if released != len(sites):
        failures.append(f"grep-derived: {released} of {len(sites)} refusal sites release the charge")

    assert not failures, "\n".join(failures)


# ------------------------------------------------------------------------------------------ A41


def _provider_failure_scenarios() -> list[tuple[str, _LoopSpy]]:
    return [
        ("error_event_no_text", _LoopSpy([LoopEvent(type="error", error_code="provider_error")])),
        ("done_with_empty_reply", _LoopSpy([LoopEvent(type="done", finish_reason="stop", usage=None)])),
        ("tool_loop_cap", _LoopSpy([], raise_after=ToolLoopCapExceeded())),
        ("provider_exception", _LoopSpy([], raise_after=RuntimeError("provider blew up"))),
        (
            "empty_response_fallback",
            _LoopSpy(
                [
                    LoopEvent(type="token", text="Sorry, I lost my train of thought. Try again?"),
                    LoopEvent(type="done", finish_reason="empty_response", usage=None),
                ]
            ),
        ),
    ]


@pytest.mark.asyncio
async def test_release_charge_uses_verified_conversation():
    """A41 / K-05, F5. When the provider fails (or answers with nothing) on a creator turn, the
    refund goes to Spring for the conversation the STREAM TOKEN is bound to.

    The body here reports the bound conversation on its first read (so the binding check passes)
    and a different conversation on every later read. A `release_charge` that reads
    `body["conversation_id"]` sends ATTACKER_CONVERSATION_ID to Spring and fails this test."""
    failures: list[str] = []
    for name, loop in _provider_failure_scenarios():
        spring = _spring()
        request = _make_request(_body())
        request.json = AsyncMock(return_value=_ConversationIdReadBackBody(_body()))
        request.is_disconnected = AsyncMock(return_value=False)

        with patch.object(
            chat_route, "verify_token_async", AsyncMock(return_value=_creator_stream_token())
        ), patch.object(chat_route, "_get_spring", MagicMock(return_value=spring)), patch.object(
            chat_route, "_get_claude", MagicMock(return_value=MagicMock())
        ), patch.object(chat_route, "run_tool_loop", loop):
            response = await chat_route.chat(request, authorization="Bearer whatever")
            if response.status_code != 200:
                failures.append(f"{name}: expected the stream to start, got HTTP {response.status_code}")
                continue
            await _drain(response)

        if loop.calls != 1:
            failures.append(f"{name}: tool loop started {loop.calls} times")
        calls = spring.release_turn_credit.await_args_list
        if len(calls) != 1:
            failures.append(f"{name}: release_turn_credit awaited {len(calls)} times, expected 1")
            continue
        kwargs = calls[0].kwargs
        if kwargs.get("conversation_id") != CONVERSATION_ID:
            failures.append(
                f"{name}: refund sent for conversation {kwargs.get('conversation_id')!r}, "
                f"expected the VERIFIED {CONVERSATION_ID!r}"
            )
        if kwargs.get("turn_id") != STREAM_MESSAGE_ID:
            failures.append(f"{name}: refund keyed on {kwargs.get('turn_id')!r}, expected the VERIFIED messageId")
        if spring.persist_assistant_message.await_count:
            failures.append(f"{name}: a failed turn persisted an assistant reply")

    assert not failures, "\n".join(failures)


# ------------------------------------------------------------------------------------------ A42


def _disconnect_after_first_poll() -> AsyncMock:
    """Connected for the first poll (the first token goes out), gone on every poll after."""
    polls = {"n": 0}

    async def _is_disconnected():
        polls["n"] += 1
        return polls["n"] > 1

    return AsyncMock(side_effect=_is_disconnected)


@pytest.mark.asyncio
async def test_disconnect_never_releases_creator():
    """A42 / K-16. A creator who reads the first token and hangs up keeps the charge: no refund,
    whatever the provider does next (finishes, sends an error event, or throws). A refund here is
    the disconnect-farm exploit: read the answer, drop the connection, get the credit back."""
    tails = {
        "then_done": _LoopSpy(
            [
                LoopEvent(type="token", text="Here is your first caption idea"),
                LoopEvent(type="done", finish_reason="stop", usage={"input_tokens": 10, "output_tokens": 5}),
            ]
        ),
        "then_error_event": _LoopSpy(
            [
                LoopEvent(type="token", text="Here is your first caption idea"),
                LoopEvent(type="error", error_code="provider_error"),
            ]
        ),
        "then_provider_raises": _LoopSpy(
            [LoopEvent(type="token", text="Here is your first caption idea")],
            raise_after=RuntimeError("provider died after the first token"),
        ),
    }
    failures: list[str] = []
    for name, loop in tails.items():
        spring = _spring()
        request = _make_request(_body())
        request.is_disconnected = _disconnect_after_first_poll()

        with patch.object(
            chat_route, "verify_token_async", AsyncMock(return_value=_creator_stream_token())
        ), patch.object(chat_route, "_get_spring", MagicMock(return_value=spring)), patch.object(
            chat_route, "_get_claude", MagicMock(return_value=MagicMock())
        ), patch.object(chat_route, "run_tool_loop", loop):
            response = await chat_route.chat(request, authorization="Bearer whatever")
            assert response.status_code == 200
            wire = await _drain(response)

        if "Here is your first caption idea" not in wire:
            failures.append(f"{name}: the first token never reached the client, so this case proves nothing")
        if loop.calls != 1:
            failures.append(f"{name}: tool loop started {loop.calls} times")
        if spring.release_turn_credit.await_count:
            failures.append(f"{name}: release_turn_credit awaited after a client disconnect")

    assert not failures, "\n".join(failures)


# ------------------------------------------------------------------------------------------ A43


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "reply",
    [
        "I can't help with that. But here are three reel hooks for your serum launch: ...",
        # K-17: the model's own words are never a pricing signal, even when they are ALL refusal.
        "I can't help with that.",
    ],
)
async def test_model_refusal_text_is_charged(reply):
    """A43 / K-17. "Refused costs 0" is decided only by server-observable terminal paths, never by
    what the model says. A reply that contains refusal wording is a delivered reply: it is
    persisted under the verified messageId and the send-time charge stays."""
    spring = _spring()
    loop = _LoopSpy(
        [
            LoopEvent(type="token", text=reply),
            LoopEvent(
                type="done",
                finish_reason="stop",
                usage={"input_tokens": 10, "output_tokens": 5},
                stop_reason="end_turn",
            ),
        ]
    )
    request = _make_request(_body())
    request.is_disconnected = AsyncMock(return_value=False)

    with patch.object(
        chat_route, "verify_token_async", AsyncMock(return_value=_creator_stream_token())
    ), patch.object(chat_route, "_get_spring", MagicMock(return_value=spring)), patch.object(
        chat_route, "_get_claude", MagicMock(return_value=MagicMock())
    ), patch.object(chat_route, "run_tool_loop", loop):
        response = await chat_route.chat(request, authorization="Bearer whatever")
        assert response.status_code == 200
        await _drain(response)

    assert loop.calls == 1
    spring.release_turn_credit.assert_not_awaited()
    spring.persist_assistant_message.assert_awaited_once()
    kwargs = spring.persist_assistant_message.await_args.kwargs
    assert kwargs["content"] == reply
    assert kwargs["turn_id"] == STREAM_MESSAGE_ID


# ------------------------------------------------------------------------------------------ A44

_BANNED_TOOL_WORDS = re.compile(r"credit|grant|top[_\-]?up|wallet", re.IGNORECASE)


def test_no_credit_tool_for_creators():
    """A44 / K-22. Nothing a creator's Meera turn can call may move credits. Checks the whole
    creator tool catalogue (not only what one approval level enables), the Spring paths those
    tools forward to, what `assemble_prompt` actually offers a creator turn when Spring asks for
    every tool plus credit-sounding names, and the Spring client influora-ai holds: its only
    credit-touching call is the bounded `release_turn_credit`, and nothing can grant or top up."""
    catalogue = creator_schemas.all_creator_tool_schemas()
    assert catalogue, "empty creator catalogue -- this check would pass vacuously"

    offenders = [s["name"] for s in catalogue if _BANNED_TOOL_WORDS.search(s["name"])]
    offenders += [n for n in creator_schemas.CREATOR_TOOL_NAMES if _BANNED_TOOL_WORDS.search(n)]
    offenders += [
        f"{name} -> {path}"
        for name, path in creator_schemas.CREATOR_TOOL_TO_SPRING_PATH.items()
        if _BANNED_TOOL_WORDS.search(path)
    ]
    assert offenders == [], f"creator tools that can reach credits: {offenders}"

    # Spring enables tools by NAME. A credit-sounding name in tools_enabled must not produce a tool.
    enabled = list(creator_schemas.CREATOR_TOOL_NAMES) + [
        "grant_credits",
        "topup_wallet",
        "add_credit",
        "wallet_topup",
    ]
    prompt = assemble_prompt(
        {
            "workspace_id": CREATOR_ID,
            "audience": "CREATOR",
            "creator": _creator_context(tools_enabled=enabled),
            "conversation": [],
        }
    )
    offered = [t["name"] for t in prompt.tools]
    assert offered, "creator prompt offered no tools -- the name check below would pass vacuously"
    assert [n for n in offered if _BANNED_TOOL_WORDS.search(n)] == []
    for name in ("grant_credits", "topup_wallet", "add_credit", "wallet_topup"):
        assert not creator_schemas.is_creator_tool(name)

    client_calls = [
        name
        for name, member in inspect.getmembers(SpringInternalClient, predicate=inspect.isfunction)
        if not name.startswith("_")
    ]
    grant_like = [n for n in client_calls if re.search(r"grant|top[_\-]?up|wallet|purchase|confirm_paid", n, re.I)]
    assert grant_like == [], f"influora-ai can call a credit-granting Spring endpoint: {grant_like}"
    credit_calls = [n for n in client_calls if "credit" in n.lower()]
    assert credit_calls == ["release_turn_credit"], (
        f"the only credit call influora-ai may make is the bounded release, found {credit_calls}"
    )
