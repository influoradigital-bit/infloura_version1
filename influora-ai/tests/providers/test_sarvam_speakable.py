"""Platform-AI Phase 1, W2b — `speakable()` TTS text normalizer
(app/providers/sarvam.py).

Sarvam reads text literally: rupee amounts, leading '#' on hashtags, and the
bare token "UGC" all mispronounce or garble in speech. `speakable()` runs on
the text actually posted to Sarvam (per-chunk, right before each call — see
`SarvamProvider.speak`), never on what the chat panel shows.
"""

from __future__ import annotations

from app.providers.sarvam import speakable


def test_rupee_range_same_magnitude_reads_naturally():
    assert speakable("Budget: ₹15,000–₹75,000") == "Budget: fifteen to seventy-five thousand rupees"


def test_rupee_range_with_ascii_hyphen():
    assert "fifteen to seventy-five thousand rupees" in speakable("₹15,000-₹75,000")


def test_single_rupee_amount():
    assert speakable("Pool is ₹5,000") == "Pool is five thousand rupees"


def test_rupee_amount_with_decimal():
    assert speakable("Price ₹999.50") == "Price nine hundred ninety-nine point five zero rupees"


def test_hashtag_loses_leading_hash():
    assert speakable("Use #ad and #shopnow in the caption") == "Use ad and shopnow in the caption"


def test_ugc_expanded_to_letters():
    assert speakable("This is a UGC content pack") == "This is a U G C content pack"


def test_ugc_only_matches_whole_word():
    # "UGCX" is not the token "UGC" -- must not partially match.
    assert speakable("UGCX is unrelated") == "UGCX is unrelated"


def test_combined_normalization():
    text = "Great fit for #ugc creators — budget ₹10,000–₹50,000, tag with UGC and #ad"
    result = speakable(text)
    assert "#" not in result
    assert "₹" not in result
    assert "ten to fifty thousand rupees" in result
    assert "U G C" in result


def test_empty_and_none_safe():
    assert speakable("") == ""


def test_plain_text_unaffected():
    text = "Hey, ready to fund and go live?"
    assert speakable(text) == text
