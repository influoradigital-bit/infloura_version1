"""F-audit-A3 -- proves ClaudeProvider's shared circuit breaker does NOT open
when Claude's vision endpoint rejects a client's bad image data (a provider
4xx), while it still opens on a REAL provider/transport failure.

Five requests carrying "a valid JPEG signature followed by garbage" (see
tests/routes/test_shoot_check.py's mirror-image test for the route-level
half of this fix) each produced a provider-4xx here, and because every
`anthropic.APIError` used to count toward the breaker regardless of cause,
that alone was enough to open the shared frame-check breaker and switch
frame checks off for every OTHER creator on the worker for
`CircuitBreakerConfig.recovery_seconds`.

Drives `ClaudeProvider.complete_with_image` directly with `_client.messages.create`
mocked to raise REAL `anthropic` exception instances (built from a real
`httpx.Response`, not a hand-built stand-in that merely happens to have a
`status_code` attribute) -- so this exercises the exact `except
anthropic.APIError` branch production code runs, not a substitute for it.
"""

from __future__ import annotations

from types import SimpleNamespace
from unittest.mock import AsyncMock

import anthropic
import httpx
import pytest

from app.config import get_settings
from app.providers.claude import ClaudeProvider

_STATUS_TO_EXCEPTION = {
    400: anthropic.BadRequestError,
    401: anthropic.AuthenticationError,
    403: anthropic.PermissionDeniedError,
    422: anthropic.UnprocessableEntityError,
    429: anthropic.RateLimitError,
    500: anthropic.InternalServerError,
}


def _api_status_error(status_code: int) -> anthropic.APIStatusError:
    request = httpx.Request("POST", "https://api.anthropic.com/v1/messages")
    response = httpx.Response(status_code, request=request, json={"error": {"message": "x"}})
    return _STATUS_TO_EXCEPTION[status_code](f"status {status_code}", response=response, body=None)


def _connection_error() -> anthropic.APIConnectionError:
    request = httpx.Request("POST", "https://api.anthropic.com/v1/messages")
    return anthropic.APIConnectionError(message="connection error", request=request)


def _ok_response() -> SimpleNamespace:
    return SimpleNamespace(
        content=[SimpleNamespace(type="text", text="ok")],
        usage=SimpleNamespace(
            input_tokens=1, output_tokens=1, cache_read_input_tokens=0, cache_creation_input_tokens=0
        ),
    )


@pytest.fixture(autouse=True)
def _clear_settings_cache():
    get_settings.cache_clear()
    yield
    get_settings.cache_clear()


def _provider() -> ClaudeProvider:
    return ClaudeProvider()


async def _call(provider: ClaudeProvider):
    return await provider.complete_with_image(
        system="s",
        user_text="u",
        image_bytes=b"x",
        image_media_type="image/jpeg",
        model="claude-test-model",
        max_tokens=10,
    )


async def _fail_n_times(provider: ClaudeProvider, n: int, exc: BaseException) -> None:
    provider._client.messages.create = AsyncMock(side_effect=exc)
    for _ in range(n):
        result = await _call(provider)
        assert result.ok is False


@pytest.mark.asyncio
async def test_five_client_input_400s_do_not_open_the_breaker():
    provider = _provider()
    await _fail_n_times(provider, 5, _api_status_error(400))

    # The breaker must still be CLOSED: a call that would succeed must
    # actually reach the provider, not be short-circuited.
    create = AsyncMock(return_value=_ok_response())
    provider._client.messages.create = create
    result = await _call(provider)

    assert result.ok is True
    create.assert_awaited_once()


@pytest.mark.asyncio
async def test_five_client_input_422s_do_not_open_the_breaker():
    provider = _provider()
    await _fail_n_times(provider, 5, _api_status_error(422))

    create = AsyncMock(return_value=_ok_response())
    provider._client.messages.create = create
    result = await _call(provider)

    assert result.ok is True
    create.assert_awaited_once()


@pytest.mark.asyncio
async def test_five_real_server_errors_still_open_the_breaker():
    """The fix narrows what counts as a failure -- it must not disable the
    breaker for an ACTUAL provider outage."""
    provider = _provider()
    await _fail_n_times(provider, 5, _api_status_error(500))

    create_after_open = AsyncMock(return_value=_ok_response())
    provider._client.messages.create = create_after_open
    result = await _call(provider)

    assert result.ok is False
    assert result.error is not None and result.error.startswith("circuit_open")
    create_after_open.assert_not_awaited()


@pytest.mark.asyncio
async def test_five_connection_errors_still_open_the_breaker():
    """A transport failure (no HTTP response at all, so no status_code) must
    still trip the breaker -- `_is_client_input_error` must fall through to
    False, not silently swallow every failure kind."""
    provider = _provider()
    await _fail_n_times(provider, 5, _connection_error())

    create_after_open = AsyncMock(return_value=_ok_response())
    provider._client.messages.create = create_after_open
    result = await _call(provider)

    assert result.ok is False
    assert result.error is not None and result.error.startswith("circuit_open")
    create_after_open.assert_not_awaited()


@pytest.mark.asyncio
async def test_five_auth_errors_still_open_the_breaker():
    """401/403 are OUR configuration problem, not the caller's bad image --
    a real health signal, deliberately excluded from the client-input set."""
    provider = _provider()
    await _fail_n_times(provider, 5, _api_status_error(401))

    create_after_open = AsyncMock(return_value=_ok_response())
    provider._client.messages.create = create_after_open
    result = await _call(provider)

    assert result.ok is False
    assert result.error is not None and result.error.startswith("circuit_open")
    create_after_open.assert_not_awaited()


@pytest.mark.asyncio
async def test_mixed_client_and_server_failures_only_server_ones_count():
    """4 client-input 400s (never counted) plus 5 real 500s (counted) opens
    the breaker at the 5th 500 -- proving the two failure counters really are
    independent, not that client-input failures are merely discounted."""
    provider = _provider()
    await _fail_n_times(provider, 4, _api_status_error(400))
    await _fail_n_times(provider, 5, _api_status_error(500))

    create_after_open = AsyncMock(return_value=_ok_response())
    provider._client.messages.create = create_after_open
    result = await _call(provider)

    assert result.ok is False
    assert result.error is not None and result.error.startswith("circuit_open")
    create_after_open.assert_not_awaited()
