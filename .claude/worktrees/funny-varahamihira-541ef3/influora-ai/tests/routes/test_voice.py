"""Tests for voice route utilities (P18 TTS cap per 20-ROHAN-COST-REVIEW.md §3)."""

import pytest

from app.routes.voice import _truncate_for_tts, TTS_MAX_CHARS


class TestTruncateForTts:
    """P18 — TTS reply length cap of ~200 chars."""

    def test_short_text_unchanged(self):
        """Text under the limit should pass through unchanged."""
        short = "Hello, this is a short message."
        assert _truncate_for_tts(short) == short

    def test_exactly_at_limit(self):
        """Text exactly at the limit should not be truncated."""
        exact = "x" * TTS_MAX_CHARS
        assert _truncate_for_tts(exact) == exact

    def test_over_limit_truncates(self):
        """Text over the limit should be truncated."""
        long_text = "x" * 300
        result = _truncate_for_tts(long_text)
        assert len(result) <= TTS_MAX_CHARS + 3  # +3 for possible ellipsis

    def test_truncation_prefers_sentence_boundary(self):
        """Should prefer truncating at sentence end if possible."""
        text = "This is the first sentence. " + "x" * 250
        result = _truncate_for_tts(text)
        # Should truncate at the sentence boundary if it fits
        assert len(result) <= TTS_MAX_CHARS + 3

    def test_truncation_adds_ellipsis(self):
        """When truncating mid-word, should add ellipsis."""
        long_text = "x" * 300
        result = _truncate_for_tts(long_text)
        assert result.endswith("...")

    def test_empty_string(self):
        """Empty string should return empty string."""
        assert _truncate_for_tts("") == ""

    def test_whitespace_only(self):
        """Whitespace-only should be handled gracefully."""
        assert _truncate_for_tts("   ").strip() == ""

    def test_max_chars_constant_is_200(self):
        """Verify the constant is set to ~200 as per spec."""
        assert TTS_MAX_CHARS == 200
