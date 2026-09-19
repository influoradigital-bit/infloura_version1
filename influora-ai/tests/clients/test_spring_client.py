"""`SpringInternalClient.call_tool_endpoint` — the HTTP layer, not the loop.

Priya last-call, UF-1 (`PRIYA-LASTCALL-U1-K4-0917.md`, bar item 3, BLOCKING).
`tests/tools/test_loop_creator_dispatch.py` proves `loop.py` PASSES the right
`allow_retry`/`read_timeout_override` values to `call_tool_endpoint` (a
`_RecordingSpring` fake, never the real client) -- nothing anywhere proved the
real client actually HONOURS them. Priya deleted `spring.py` L187-188 (the
`read_timeout_override` is built into an `httpx.Timeout` but never attached to
the request, so it silently falls back to the client-wide 5s `spring_read`)
and separately made L163 ignore `allow_retry` (always retries). All 949 tests
stayed green either way -- this file is what was missing.

Talks to a real `httpx.AsyncClient` wired to an `httpx.MockTransport`, the
same pattern `tests/providers/test_sarvam_tts.py` uses (`_provider_with_response`)
so the client's own `__init__` still builds the real object under test -- a
fake at a higher layer would prove nothing about whether L163/L187-188
actually work.
"""

from __future__ import annotations

import functools

import httpx
import pytest

from app.clients.spring import SpringCallError, SpringInternalClient
from app.config import get_settings


@pytest.fixture(autouse=True)
def _configure_signing_key(monkeypatch):
    """`call_tool_endpoint` mints a real service token via
    `mint_service_token()` on every attempt (`spring.py` L155-156, L178-184) --
    without a signing key configured, that call raises before the HTTP layer
    under test is ever reached."""
    monkeypatch.setenv("SERVICE_TOKEN_SIGNING_KEY", "test-service-token-signing-key-32-bytes-min")
    get_settings.cache_clear()
    yield
    get_settings.cache_clear()


def _client_with_transport(monkeypatch, handler) -> SpringInternalClient:
    """Builds a real `SpringInternalClient` whose `httpx.AsyncClient` is bound
    to a `MockTransport` -- `SpringInternalClient.__init__` (`spring.py`
    L79-91) still runs unmodified and builds the real timeout config; only the
    wire transport is swapped, exactly as `test_sarvam_tts._provider_with_response`
    does for `SarvamProvider`."""
    real_async_client = httpx.AsyncClient
    monkeypatch.setattr(
        "app.clients.spring.httpx.AsyncClient",
        functools.partial(real_async_client, transport=httpx.MockTransport(handler)),
    )
    return SpringInternalClient()


def _call_kwargs(**overrides) -> dict:
    base = dict(
        tool_name="get_brief",
        path="/internal/meera/creator/get_brief",
        payload={"brief_id": "b-1", "workspace_id": "creator-1"},
        onbehalf_jwt="fake-onbehalf-jwt",
        idempotency_key=None,
    )
    base.update(overrides)
    return base


# --------------------------------------------------------- (a) allow_retry


@pytest.mark.asyncio
async def test_a_timing_out_request_makes_exactly_one_call_when_allow_retry_is_false(monkeypatch):
    """The safety property Addition B rests on: a get_brief forward that times
    out must NOT be retried. Exactly one request, and the caller sees
    `network_error` (`spring.py` L190-194), never a raw httpx exception."""
    attempts = {"n": 0}

    def handler(request: httpx.Request) -> httpx.Response:
        attempts["n"] += 1
        raise httpx.ReadTimeout("simulated read timeout", request=request)

    client = _client_with_transport(monkeypatch, handler)

    with pytest.raises(SpringCallError) as exc_info:
        await client.call_tool_endpoint(**_call_kwargs(allow_retry=False))

    assert attempts["n"] == 1
    assert exc_info.value.code == "network_error"


@pytest.mark.asyncio
async def test_control_allow_retry_true_makes_max_retries_plus_one_calls(monkeypatch):
    """Control for the test above: the SAME timing-out handler, only
    `allow_retry=True` differs, makes `settings.retry.max_retries + 1` calls
    (`spring.py` L163). Proves the request-count assertion above is not
    vacuously 1 for every input -- it is 1 specifically BECAUSE
    `allow_retry=False`."""
    attempts = {"n": 0}

    def handler(request: httpx.Request) -> httpx.Response:
        attempts["n"] += 1
        raise httpx.ReadTimeout("simulated read timeout", request=request)

    client = _client_with_transport(monkeypatch, handler)
    expected = get_settings().retry.max_retries + 1

    with pytest.raises(SpringCallError):
        await client.call_tool_endpoint(**_call_kwargs(allow_retry=True))

    assert attempts["n"] == expected
    assert expected > 1, "the control is worthless if max_retries is 0"


# --------------------------------------------------- (b) read_timeout_override


@pytest.mark.asyncio
async def test_b_read_timeout_override_reaches_the_actual_request(monkeypatch):
    """The other half of Addition B: `read_timeout_override=40.0` must reach
    the real HTTP request, not just get built into an unused `httpx.Timeout`
    object (`spring.py` L166-175) that is then never attached
    (L187-188)."""
    seen_timeouts: list[dict] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen_timeouts.append(request.extensions["timeout"])
        return httpx.Response(200, json={"success": True, "data": {"status": "ANALYZED"}})

    client = _client_with_transport(monkeypatch, handler)

    await client.call_tool_endpoint(**_call_kwargs(read_timeout_override=40.0))

    assert len(seen_timeouts) == 1
    assert seen_timeouts[0]["read"] == 40.0


@pytest.mark.asyncio
async def test_no_override_uses_the_client_wide_spring_read_default(monkeypatch):
    """Control for the test above: with no override, the request carries the
    client's OWN constructor-time default (`settings.timeouts.spring_read`,
    5.0 by default) -- proves the assertion above is checking the override
    actually changing the value, not just checking `read` is present."""
    seen_timeouts: list[dict] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen_timeouts.append(request.extensions["timeout"])
        return httpx.Response(200, json={"success": True, "data": {}})

    client = _client_with_transport(monkeypatch, handler)
    expected = get_settings().timeouts.spring_read

    await client.call_tool_endpoint(**_call_kwargs(read_timeout_override=None))

    assert len(seen_timeouts) == 1
    assert seen_timeouts[0]["read"] == expected
    assert expected != 40.0, "the control is worthless if spring_read already happens to be 40.0"
