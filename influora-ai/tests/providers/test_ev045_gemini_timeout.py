"""EV-045 — a hung Gemini call is abandoned at the deadline, not waited on.

The defect: `ProviderTimeouts.gemini_connect` / `.gemini_read` had been
declared in app/config.py since the file was written and were read by NOTHING
(`grep -rn 'gemini_connect|gemini_read' app/` matched only their own
definitions), while every other provider in the package honoured its own
timeouts. The Gemini client was a bare `genai.Client(api_key=...)`, so a
provider that accepted the connection and then went silent held
`/analyze-site` — and the request thread serving it — for as long as it
stayed silent.

No real provider is called here. The stub sleeps locally, in-process.
"""

from __future__ import annotations

import asyncio
import dataclasses
import time
from types import SimpleNamespace

import pytest

from app.config import get_settings
from app.providers import gemini as gemini_module
from app.providers.claude import CircuitBreaker
from app.providers.gemini import GeminiProvider

# Far longer than any deadline under test: if the guard is absent, the test
# does not "run slowly", it hangs for this long and the assertion on elapsed
# time fails loudly.
HUNG_PROVIDER_SECONDS = 30.0

DEADLINE_SECONDS = 0.2


def _provider_with_stub(monkeypatch, sleep_seconds: float) -> GeminiProvider:
    """A provider whose only outbound call is a local `asyncio.sleep`."""
    started: dict[str, float] = {}

    class _HungModels:
        async def generate_content(self, *, model, contents, config):
            started["at"] = time.monotonic()
            await asyncio.sleep(sleep_seconds)
            return SimpleNamespace(usage_metadata=None, text="{}")

    provider = GeminiProvider.__new__(GeminiProvider)
    provider._client = SimpleNamespace(aio=SimpleNamespace(models=_HungModels()))
    provider._breaker = CircuitBreaker(failure_threshold=5, recovery_seconds=30.0)

    # Drive the REAL `_deadline_seconds()` wiring rather than patching it out:
    # split the budget across connect+read exactly as production does, so a
    # regression that stops reading either field is caught here.
    base = get_settings()
    tight = dataclasses.replace(
        base,
        timeouts=dataclasses.replace(
            base.timeouts,
            gemini_connect=DEADLINE_SECONDS / 2,
            gemini_read=DEADLINE_SECONDS / 2,
        ),
    )
    monkeypatch.setattr(gemini_module, "get_settings", lambda: tight)
    return provider


@pytest.mark.asyncio
async def test_a_hung_provider_is_abandoned_at_the_deadline(monkeypatch):
    provider = _provider_with_stub(monkeypatch, HUNG_PROVIDER_SECONDS)

    started = time.monotonic()
    result = await provider.classify_site("some sanitized page text")
    elapsed = time.monotonic() - started

    assert result.ok is False
    assert result.error == "provider_error"
    # The number that matters: we came back at the deadline, not at the
    # provider's. Generous upper bound for a loaded CI box, still two orders of
    # magnitude below HUNG_PROVIDER_SECONDS.
    assert elapsed < 5.0, f"waited {elapsed:.1f}s on a hung provider"


@pytest.mark.asyncio
async def test_the_cleanup_path_is_bounded_too(monkeypatch):
    """Both `generate_content` call sites, not just the one someone remembered."""
    provider = _provider_with_stub(monkeypatch, HUNG_PROVIDER_SECONDS)

    started = time.monotonic()
    result = await provider.cleanup_transcript("kal meeting hai na")
    elapsed = time.monotonic() - started

    assert result.ok is False
    assert elapsed < 5.0, f"waited {elapsed:.1f}s on a hung provider"


@pytest.mark.asyncio
async def test_a_timeout_counts_as_a_provider_failure_for_the_breaker(monkeypatch):
    """A hung endpoint is exactly what the breaker exists to stop hammering, so
    a timeout must not be silently forgiven."""
    provider = _provider_with_stub(monkeypatch, HUNG_PROVIDER_SECONDS)

    before = provider._breaker._state.consecutive_failures
    await provider.classify_site("page text")

    assert provider._breaker._state.consecutive_failures == before + 1


@pytest.mark.asyncio
async def test_a_fast_provider_is_not_cut_off(monkeypatch):
    """FALSIFICATION: a guard that simply failed every call would pass every
    test above. A call that finishes inside the budget must still succeed."""
    provider = _provider_with_stub(monkeypatch, 0.0)

    result = await provider.classify_site("page text")

    assert result.ok is True
