"""Tests for P1-B's structured-extraction wiring in analyze_site.py:

1. `merge_known_products` -- scraped facts always win over the model's
   product_catalog; the model can only ADD products, never override a known
   one's price/currency (money-safety contract, since calculate_budget and
   Meera's "quote the real price" rule consume this data downstream).
2. `perform_site_analysis` no longer hard-fails with `empty_page` when the
   sanitized visible text is empty but structured data (JSON-LD) still
   yielded product facts -- the core P1-B behavior change per Priya's
   render-sandbox ruling #6 (SHARED_CONTEXT.md).
"""

from __future__ import annotations

import time
from decimal import Decimal
from typing import Any
from unittest.mock import AsyncMock, patch

import jwt
import pytest
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa

from app.auth.service_token import reset_jwks_source, set_jwks_source_for_testing
from app.config import get_settings
from app.costs import spend_tracker
from app.prompt.structured_extract import ScrapedProduct
from app.providers.gemini import ClassifyResult
from app.routes import analyze_site as analyze_site_route
from app.routes.analyze_site import merge_known_products, perform_site_analysis

# ---------------------------------------------------------------------------
# merge_known_products -- pure function, no network/provider involved
# ---------------------------------------------------------------------------


def test_merge_known_products_scraped_facts_always_included():
    known = [ScrapedProduct(name="Retinal Night Serum", price=899.0, currency="INR", source="json_ld")]
    merged = merge_known_products(known, model_catalog=[])
    assert merged == [{"name": "Retinal Night Serum", "price": 899.0, "currency": "INR", "price_source": "scraped"}]


def test_merge_known_products_model_cannot_override_a_scraped_price():
    """The core money-safety guarantee: even if the model hallucinates a
    different price for the SAME product name, the scraped fact wins and the
    model's duplicate is dropped entirely (not even kept as a second entry)."""
    known = [ScrapedProduct(name="Retinal Night Serum", price=899.0, currency="INR", source="json_ld")]
    model_catalog = [
        {"name": "Retinal Night Serum", "price": 1.0, "currency": "USD", "price_source": "scraped"},
    ]
    merged = merge_known_products(known, model_catalog)
    assert len(merged) == 1
    assert merged[0]["price"] == 899.0
    assert merged[0]["currency"] == "INR"


def test_merge_known_products_model_can_add_new_products_as_inferred():
    known = [ScrapedProduct(name="Retinal Night Serum", price=899.0, currency="INR", source="json_ld")]
    model_catalog = [{"name": "Retinal Night Serum", "price": 1.0, "currency": "USD"},
                      {"name": "Vitamin C Cream", "price": 749.0, "currency": "INR", "price_source": "inferred"}]
    merged = merge_known_products(known, model_catalog)
    assert len(merged) == 2
    by_name = {p["name"]: p for p in merged}
    assert by_name["Retinal Night Serum"]["price_source"] == "scraped"
    assert by_name["Vitamin C Cream"]["price_source"] == "inferred"
    assert by_name["Vitamin C Cream"]["price"] == 749.0


def test_merge_known_products_forces_inferred_even_if_model_claims_scraped():
    """A model can't self-declare price_source="scraped" for a product that
    was NOT in the known-facts set -- the merge step forces "inferred"
    regardless of what the model's own JSON said, so a compromised/careless
    model response can never masquerade as a verified fact downstream."""
    merged = merge_known_products(known=[], model_catalog=[
        {"name": "Suspicious Item", "price": 1.0, "currency": "INR", "price_source": "scraped"}
    ])
    assert merged == [{"name": "Suspicious Item", "price": 1.0, "currency": "INR", "price_source": "inferred"}]


def test_merge_known_products_handles_none_and_malformed_catalog_entries():
    known = [ScrapedProduct(name="A", price=1.0, currency="INR", source="json_ld")]
    merged = merge_known_products(known, model_catalog=None)
    assert merged == [{"name": "A", "price": 1.0, "currency": "INR", "price_source": "scraped"}]

    merged2 = merge_known_products(known, model_catalog=[None, "not-a-dict", {"price": 5}])  # type: ignore[list-item]
    assert merged2 == [{"name": "A", "price": 1.0, "currency": "INR", "price_source": "scraped"}]


# ---------------------------------------------------------------------------
# perform_site_analysis -- empty visible text, but JSON-LD recovers products
# ---------------------------------------------------------------------------


def _gen_rsa_keypair():
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    private_pem = key.private_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PrivateFormat.PKCS8,
        encryption_algorithm=serialization.NoEncryption(),
    )
    public_pem = key.public_key().public_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    return private_pem, public_pem


LEGIT_PRIVATE_PEM, LEGIT_PUBLIC_PEM = _gen_rsa_keypair()
WORKSPACE_ID = "ws-structured-extract-001"

# Visible body is a bare SPA mount point (near-empty after tag-stripping),
# but JSON-LD carries a real product -- the exact "Shopify SPA" shape this
# fix targets.
SPA_HTML_WITH_JSON_LD = b"""
<html><head>
<script type="application/ld+json">
{"@type": "Product", "name": "Retinal Night Serum",
 "offers": {"@type": "Offer", "price": "899.00", "priceCurrency": "INR"}}
</script>
</head><body><div id="root"></div></body></html>
"""


class _StaticKey:
    def __init__(self, key):
        self.key = key


class _FakeJwksSource:
    def __init__(self, legitimate_public_pem: bytes):
        self._legitimate_public_pem = legitimate_public_pem

    def get_signing_key_from_jwt(self, token: str):
        return _StaticKey(self._legitimate_public_pem)


@pytest.fixture(autouse=True)
async def _install_fake_jwks_and_reset_state(monkeypatch):
    set_jwks_source_for_testing(_FakeJwksSource(LEGIT_PUBLIC_PEM))
    monkeypatch.delenv("AI_SPEND_KILL_SWITCH", raising=False)
    monkeypatch.delenv("AI_DAILY_SPEND_CEILING_USD", raising=False)
    get_settings.cache_clear()
    await spend_tracker.reset_for_testing()
    yield
    reset_jwks_source()
    get_settings.cache_clear()
    await spend_tracker.reset_for_testing()


@pytest.mark.asyncio
async def test_empty_visible_text_but_json_ld_present_does_not_degrade_to_empty_page():
    """P1-B's headline behavior change: a page whose stripped visible text is
    empty (pure SPA mount div) must still classify successfully when
    structured extraction recovered a product -- today's `empty_page` bail
    would have thrown this brand's real product away."""
    mock_result = ClassifyResult(
        ok=True,
        niche_tags=["skincare"],
        tone_dial={"formality": 0.5, "energy": 0.5, "emoji_ok": True, "cultural_context": "en-IN"},
        brand_color=None,
        product_catalog=[],
        usage=None,
    )

    with patch.object(analyze_site_route, "guarded_fetch", return_value=(SPA_HTML_WITH_JSON_LD, "https://brand.test/")):
        with patch.object(analyze_site_route, "_get_gemini") as mock_get_gemini:
            mock_gemini = AsyncMock()
            mock_gemini.classify_site = AsyncMock(return_value=mock_result)
            mock_get_gemini.return_value = mock_gemini

            result = await perform_site_analysis(url="https://brand.test/", workspace_id=WORKSPACE_ID)

    assert result["success"] is True
    catalog = result["data"]["product_catalog"]
    assert catalog == [{"name": "Retinal Night Serum", "price": 899.0, "currency": "INR", "price_source": "scraped"}]

    # classify_site must have been called WITH the known_products fact.
    _, kwargs = mock_gemini.classify_site.call_args
    assert kwargs["known_products"] == [{"name": "Retinal Night Serum", "price": 899.0, "currency": "INR"}]


@pytest.mark.asyncio
async def test_truly_empty_page_with_no_structured_data_still_degrades():
    """No regression: a genuinely empty page (no visible text, no structured
    data at all) still returns the empty_page degrade exactly as before."""
    with patch.object(
        analyze_site_route, "guarded_fetch", return_value=(b"<html><body></body></html>", "https://brand.test/")
    ):
        with patch.object(analyze_site_route, "_get_gemini") as mock_get_gemini:
            result = await perform_site_analysis(url="https://brand.test/", workspace_id=WORKSPACE_ID)

    assert result["success"] is False
    assert result["error"]["code"] == "empty_page"
    mock_get_gemini.assert_not_called()
