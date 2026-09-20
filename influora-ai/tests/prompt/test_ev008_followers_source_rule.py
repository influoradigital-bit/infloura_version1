"""EV-008 - the brand persona must not let Meera present an imported follower count as verified.

show_creators (MeeraToolDtos.CreatorSummary) carries followersSource; its `verified` field is an
identity flag. The persona is the only place the model learns which field backs a "verified" claim.
"""

from app.prompt.persona import get_persona_block


def test_persona_ties_verified_claim_to_followers_source():
    block = get_persona_block()
    assert "followersSource" in block
    assert "ONLY when it is VERIFIED" in block
    assert "IMPORTED, say the count is imported and not" in block
    assert "`verified` field is not about follower numbers" in block
