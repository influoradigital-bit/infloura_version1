"""Tests for app/prompt/structured_extract.py -- P1-B structured product-fact
extraction from raw (pre-sanitization) HTML.

Fixtures below are representative of real-world markup shapes (Shopify
JSON-LD w/ @graph, a static-site JSON-LD Product, OpenGraph-only, microdata
fallback, and a page with zero structured data) -- see
wiki/ai-review/brand-intake-and-trend-sources-ai-review.md P1-B and
SHARED_CONTEXT.md's Priya render-sandbox ruling #6 for why this matters:
JSON-LD recovers product facts on "empty-looking" SPA pages without a
browser.
"""

from __future__ import annotations

import pytest

from app.prompt.structured_extract import (
    ScrapedProduct,
    extract_json_ld_products,
    extract_microdata_product,
    extract_opengraph_product,
    extract_structured_facts,
)

# ---------------------------------------------------------------------------
# JSON-LD -- Shopify-style: sparse visible body (client-rendered), rich
# @graph JSON-LD carrying multiple Product entities server-side.
# ---------------------------------------------------------------------------

SHOPIFY_SPA_HTML = """
<html><head>
<script type="application/ld+json">
{
  "@context": "https://schema.org",
  "@graph": [
    {"@type": "WebSite", "name": "Lumos Skincare"},
    {
      "@type": "Product",
      "name": "Retinal Night Serum",
      "offers": {"@type": "Offer", "price": "899.00", "priceCurrency": "INR"}
    },
    {
      "@type": "Product",
      "name": "Niacinamide 10% Moisturizer",
      "offers": {"@type": "Offer", "price": 649, "priceCurrency": "INR"}
    }
  ]
}
</script>
</head><body><div id="root"></div></body></html>
"""

# Static site -- a single Product JSON-LD block, no @graph.
STATIC_SITE_HTML = """
<html><head>
<meta property="og:title" content="Earthful Organics" />
<script type="application/ld+json">
{"@type": "Product", "name": "Cold-Pressed Groundnut Oil",
 "offers": {"@type": "Offer", "price": "550", "priceCurrency": "INR"}}
</script>
</head><body><p>Earthful Organics -- certified organic staples.</p></body></html>
"""

# ItemList-wrapped products (another common category-page pattern).
ITEM_LIST_HTML = """
<script type="application/ld+json">
{
  "@type": "ItemList",
  "itemListElement": [
    {"@type": "Product", "name": "Chai Blend 250g",
     "offers": {"@type": "Offer", "price": 299, "priceCurrency": "INR"}},
    {"@type": "Product", "name": "Chai Blend 500g",
     "offers": {"@type": "AggregateOffer", "lowPrice": 499, "price": 499, "priceCurrency": "INR"}}
  ]
}
</script>
"""

MALFORMED_JSON_LD_HTML = """
<script type="application/ld+json">{not valid json at all</script>
<script type="application/ld+json">
{"@type": "Product", "name": "Still Recovered", "offers": {"@type": "Offer", "price": 100, "priceCurrency": "INR"}}
</script>
"""

OPENGRAPH_ONLY_HTML = """
<html><head>
<meta property="og:title" content="VoltEdge 65W GaN Charger" />
<meta property="product:price:amount" content="1799" />
<meta property="product:price:currency" content="INR" />
</head><body><div id="app"></div></body></html>
"""

MICRODATA_ONLY_HTML = """
<div itemscope itemtype="https://schema.org/Product">
  <span itemprop="name">Brahmi Focus Drops</span>
  <span itemprop="offers" itemscope itemtype="https://schema.org/Offer">
    <span itemprop="price" content="699">Rs 699</span>
    <span itemprop="priceCurrency" content="INR">INR</span>
  </span>
</div>
"""

NO_STRUCTURED_DATA_HTML = """
<html><head><title>A Brand</title></head>
<body><div id="root"></div><script src="/bundle.js"></script></body></html>
"""


def test_json_ld_graph_recovers_multiple_products():
    products = extract_json_ld_products(SHOPIFY_SPA_HTML)
    names = {p.name for p in products}
    assert names == {"Retinal Night Serum", "Niacinamide 10% Moisturizer"}
    by_name = {p.name: p for p in products}
    assert by_name["Retinal Night Serum"].price == 899.0
    assert by_name["Retinal Night Serum"].currency == "INR"
    assert by_name["Niacinamide 10% Moisturizer"].price == 649.0
    assert all(p.source == "json_ld" for p in products)


def test_json_ld_static_site_single_product():
    products = extract_json_ld_products(STATIC_SITE_HTML)
    assert len(products) == 1
    assert products[0] == ScrapedProduct(
        name="Cold-Pressed Groundnut Oil", price=550.0, currency="INR", source="json_ld"
    )


def test_json_ld_item_list_and_aggregate_offer():
    products = extract_json_ld_products(ITEM_LIST_HTML)
    names = {p.name for p in products}
    assert names == {"Chai Blend 250g", "Chai Blend 500g"}
    prices = {p.name: p.price for p in products}
    assert prices["Chai Blend 250g"] == 299.0
    assert prices["Chai Blend 500g"] == 499.0


def test_malformed_json_ld_block_is_skipped_not_fatal():
    """One bad <script> block must not prevent recovering products from a
    later, well-formed block on the same page."""
    products = extract_json_ld_products(MALFORMED_JSON_LD_HTML)
    assert len(products) == 1
    assert products[0].name == "Still Recovered"


def test_opengraph_product_extraction():
    product = extract_opengraph_product(OPENGRAPH_ONLY_HTML)
    assert product == ScrapedProduct(
        name="VoltEdge 65W GaN Charger", price=1799.0, currency="INR", source="opengraph"
    )


def test_microdata_product_extraction():
    product = extract_microdata_product(MICRODATA_ONLY_HTML)
    assert product is not None
    assert product.name == "Brahmi Focus Drops"
    assert product.price == 699.0
    assert product.currency == "INR"
    assert product.source == "microdata"


def test_extract_structured_facts_priority_json_ld_over_opengraph():
    """A page with both JSON-LD and OpenGraph tags prefers JSON-LD (richer,
    multi-product) and does not also append the OG single-product guess."""
    combined = SHOPIFY_SPA_HTML.replace(
        "<head>", '<head><meta property="og:title" content="Should Not Win" />'
    )
    facts = extract_structured_facts(combined)
    assert {p.name for p in facts} == {"Retinal Night Serum", "Niacinamide 10% Moisturizer"}


def test_extract_structured_facts_falls_back_to_opengraph():
    facts = extract_structured_facts(OPENGRAPH_ONLY_HTML)
    assert len(facts) == 1
    assert facts[0].source == "opengraph"


def test_extract_structured_facts_falls_back_to_microdata():
    facts = extract_structured_facts(MICRODATA_ONLY_HTML)
    assert len(facts) == 1
    assert facts[0].source == "microdata"


def test_extract_structured_facts_empty_when_no_structured_data():
    """Pure additive contract: zero structured data -> empty list, never a
    crash, never a fabricated product -- callers fall back to today's
    fully-inferred (model-guessed) behavior exactly as before P1-B."""
    assert extract_structured_facts(NO_STRUCTURED_DATA_HTML) == []
    assert extract_structured_facts("") == []
    assert extract_structured_facts(None) == []  # type: ignore[arg-type]


def test_extract_structured_facts_never_raises_on_garbage_input():
    garbage = "<script type=\"application/ld+json\">" + ("x" * 5000) + "</script>"
    assert extract_structured_facts(garbage) == []


# ---------------------------------------------------------------------------
# C3 (Kabir P1-B audit): _coerce_price clamps a sane range -- a hostile or
# broken JSON-LD/OpenGraph/microdata price must never flow through as a
# "scraped" (trusted) fact.
# ---------------------------------------------------------------------------

NEGATIVE_PRICE_JSON_LD_HTML = """
<script type="application/ld+json">
{"@type": "Product", "name": "Refund Trap", "offers": {"@type": "Offer", "price": -500, "priceCurrency": "INR"}}
</script>
"""

ABSURD_PRICE_JSON_LD_HTML = """
<script type="application/ld+json">
{"@type": "Product", "name": "Overflow Item", "offers": {"@type": "Offer", "price": 99999999999, "priceCurrency": "INR"}}
</script>
"""

ZERO_PRICE_JSON_LD_HTML = """
<script type="application/ld+json">
{"@type": "Product", "name": "Free Sample", "offers": {"@type": "Offer", "price": 0, "priceCurrency": "INR"}}
</script>
"""


def test_negative_price_is_dropped_not_scraped_as_a_fact():
    products = extract_json_ld_products(NEGATIVE_PRICE_JSON_LD_HTML)
    assert len(products) == 1
    assert products[0].name == "Refund Trap"
    assert products[0].price is None  # negative price never survives as a fact


def test_absurdly_large_price_is_dropped_not_scraped_as_a_fact():
    products = extract_json_ld_products(ABSURD_PRICE_JSON_LD_HTML)
    assert len(products) == 1
    assert products[0].name == "Overflow Item"
    assert products[0].price is None  # > sane cap never survives as a fact


def test_zero_price_is_allowed_through_as_a_real_fact():
    """A genuinely free item is a real, sane fact -- zero must not be
    conflated with 'missing/invalid'."""
    products = extract_json_ld_products(ZERO_PRICE_JSON_LD_HTML)
    assert len(products) == 1
    assert products[0].name == "Free Sample"
    assert products[0].price == 0.0


# ---------------------------------------------------------------------------
# Round-7, Priya advisory 4 — ranges never yield a price, by whichever guard
# ---------------------------------------------------------------------------


@pytest.mark.parametrize(
    "text",
    [
        "499-999",
        "499 – 999",
        "₹1,000 to ₹5,000",
        "1,499.00-2,999.00",
        "10 through 20",
        "499to999",
        "₹2-3k",
        "between 499 and 999",
    ],
)
def test_a_price_range_is_never_coerced_to_a_scraped_fact(text):
    """`_coerce_price` has two guards against ranges — `_RANGE_HINT_RE` and the
    "exactly one price token" rule — and today the token rule alone would catch
    every case (see the comment at structured_extract.py's range guard). These
    pin the BEHAVIOUR rather than either line, so the contract survives a
    tokenizer change that makes one of the two guards stop firing.

    Why it matters: a coerced range becomes a `price_source="scraped"` fact that
    `merge_known_products` lets override the model, and Meera then quotes it to
    a brand as a verified price.
    """
    from app.prompt.structured_extract import _coerce_price

    assert _coerce_price(text) is None


def test_a_single_price_with_range_like_neighbours_still_parses():
    """Control: the guards must not swallow an ordinary price that merely sits
    near a hyphen or the word "to"."""
    from app.prompt.structured_extract import _coerce_price

    assert _coerce_price("₹499") == 499.0
    assert _coerce_price("Rs. 1,499.00") == 1499.0
    assert _coerce_price("starting at 499") == 499.0
