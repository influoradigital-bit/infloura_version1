"""Tests for voice route utilities (P18 TTS cap per 20-ROHAN-COST-REVIEW.md §3)."""


from app.routes.voice import TTS_MAX_CHARS, _truncate_for_tts


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
        """When truncating mid-word, should add ellipsis. Uses a string LONGER
        than TTS_MAX_CHARS so it actually truncates (was hardcoded 300, stale
        since TTS_MAX_CHARS was raised 200 -> 500 in 7cb3737 'speak full replies')."""
        long_text = "x" * (TTS_MAX_CHARS + 100)
        result = _truncate_for_tts(long_text)
        assert result.endswith("...")

    def test_empty_string(self):
        """Empty string should return empty string."""
        assert _truncate_for_tts("") == ""

    def test_whitespace_only(self):
        """Whitespace-only should be handled gracefully."""
        assert _truncate_for_tts("   ").strip() == ""

    def test_max_chars_constant_matches_code(self):
        """TTS_MAX_CHARS was deliberately raised 200 -> 500 in 7cb3737 ('speak
        full replies', voice.py:31-34 comment); this test was never updated and
        asserted the stale 200. Pin it to the intended 500 — a change here should
        be a conscious spec decision, not a silent drift."""
        assert TTS_MAX_CHARS == 500
