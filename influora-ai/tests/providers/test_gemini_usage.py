"""Tests for GeminiProvider's `_usage_from_response` token accounting and
`classify_site`'s structural `response_schema` wiring.

P2 fixes per Ash's AI review (wiki/ai-review/partial-fixes-batch-ai-review.md):
- `_usage_from_response` under-counted spend by reading only
  `candidates_token_count`, ignoring `thoughts_token_count` (billed by Google
  for 2.5-class models when the model "thinks").
- `classify_site` relied on `response_mime_type=application/json` alone, with
  no `response_schema` to structurally constrain the model's output.
"""

from __future__ import annotations

from types import SimpleNamespace

import pytest

from app.providers.gemini import GeminiProvider, _usage_from_response


# ---------------------------------------------------------------------------
# _usage_from_response -- thoughts_token_count accumulation
# ---------------------------------------------------------------------------


def test_usage_from_response_adds_thoughts_tokens_into_output_tokens():
    response = SimpleNamespace(
        usage_metadata=SimpleNamespace(
            prompt_token_count=100,
            candidates_token_count=40,
            thoughts_token_count=15,
        )
    )
    usage = _usage_from_response(response)
    assert usage == {"input_tokens": 100, "output_tokens": 55}


def test_usage_from_response_handles_absent_thoughts_token_count():
    """Most Flash-Lite responses won't carry a thoughts_token_count at all --
    absence must not raise or turn output_tokens into None."""
    response = SimpleNamespace(
        usage_metadata=SimpleNamespace(
            prompt_token_count=100,
            candidates_token_count=40,
            # no thoughts_token_count attribute at all
        )
    )
    usage = _usage_from_response(response)
    assert usage == {"input_tokens": 100, "output_tokens": 40}


def test_usage_from_response_handles_none_thoughts_token_count():
    response = SimpleNamespace(
        usage_metadata=SimpleNamespace(
            prompt_token_count=100,
            candidates_token_count=40,
            thoughts_token_count=None,
        )
    )
    usage = _usage_from_response(response)
    assert usage == {"input_tokens": 100, "output_tokens": 40}


def test_usage_from_response_returns_none_when_usage_metadata_absent():
    """Preserve the existing 'usage may legitimately be absent' contract --
    a response with no usage_metadata at all must still return None, not a
    zeroed-out dict."""
    response = SimpleNamespace(usage_metadata=None)
    assert _usage_from_response(response) is None


def test_usage_from_response_handles_missing_candidates_token_count():
    """candidates_token_count itself absent/None (edge case) must not crash
    the addition -- treated as 0, matching the `or 0` guard."""
    response = SimpleNamespace(
        usage_metadata=SimpleNamespace(
            prompt_token_count=100,
            candidates_token_count=None,
            thoughts_token_count=15,
        )
    )
    usage = _usage_from_response(response)
    assert usage == {"input_tokens": 100, "output_tokens": 15}


# ---------------------------------------------------------------------------
# classify_site -- response_schema wiring
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_classify_site_passes_a_response_schema(monkeypatch):
    """Pins that classify_site's GenerateContentConfig carries a
    response_schema (not just response_mime_type=application/json) -- the
    actual fix, since the SDK constrains structure using this field."""
    captured: dict = {}

    class _FakeModels:
        async def generate_content(self, *, model, contents, config):
            captured["config"] = config
            return SimpleNamespace(
                usage_metadata=None,
                text='{"niche_tags": ["skincare"], "tone_dial": {"formality": 0.5, '
                '"energy": 0.5, "emoji_ok": true, "cultural_context": "en-IN"}, '
                '"brand_color": "#ffffff", "product_catalog": []}',
            )

    class _FakeAio:
        def __init__(self):
            self.models = _FakeModels()

    provider = GeminiProvider.__new__(GeminiProvider)
    provider._client = SimpleNamespace(aio=_FakeAio())
    from app.providers.claude import CircuitBreaker

    provider._breaker = CircuitBreaker(failure_threshold=5, recovery_seconds=30.0)

    result = await provider.classify_site("some sanitized page text")

    assert result.ok is True
    config = captured["config"]
    assert getattr(config, "response_schema", None) is not None
    assert getattr(config, "response_mime_type", None) == "application/json"
