"""Structured product-fact extraction from RAW (pre-sanitization) HTML.

Runs on the RAW HTML `guarded_fetch` already returned (SSRF-safe, already
fetched -- this module makes ZERO new network calls, ever). Parses static
markup that Shopify/Wix/most D2C storefronts emit server-side even when the
*visible* DOM is otherwise client-rendered (React/Vue hydration):

1. JSON-LD `<script type="application/ld+json">` -> schema.org `Product` /
   `Offer` / `AggregateOffer`, including `@graph` and `itemListElement` nests
   (both common Shopify/Wix patterns -- one script tag, many entities).
2. OpenGraph / meta tags -> `og:title`, `product:price:amount`,
   `product:price:currency`, `og:price:amount`.
3. Microdata `itemprop="name"/"price"/"priceCurrency"` as a last-resort
   fallback.

Priority order above is authoritative-first: JSON-LD is the richest/most
reliable signal, so if it yields anything, OpenGraph/microdata are skipped.

Stdlib only (`re` + `json`) -- no new dependency added. No HTML parser
(bs4/lxml) is in requirements.txt today; regex-scoped `<script type=
"application/ld+json">` extraction + `json.loads` on the captured block is
sufficient here because we only need the content of well-delimited script
tags, not general DOM traversal.

Every parse step is wrapped so a malformed/unexpected block is skipped, never
raised -- this module must NEVER crash `perform_site_analysis`. Zero
structured data found => `extract_structured_facts` returns `[]`, and the
caller falls back to today's fully-inferred (model-guessed) behavior exactly
as before this change landed -- pure additive improvement, never a
regression.
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass

_JSON_LD_RE = re.compile(
    r'<script[^>]*type\s*=\s*["\']application/ld\+json["\'][^>]*>(.*?)</script>',
    re.IGNORECASE | re.DOTALL,
)
_META_TAG_RE = re.compile(r"<meta\s+[^>]*>", re.IGNORECASE)
_META_PROPERTY_RE = re.compile(r'(?:property|name)\s*=\s*["\']([^"\']+)["\']', re.IGNORECASE)
_META_CONTENT_RE = re.compile(r'content\s*=\s*["\']([^"\']*)["\']', re.IGNORECASE)

_PRODUCT_TYPES = {"product"}
_OFFER_TYPES = {"offer", "aggregateoffer"}

# Sane cap mirroring other prompt-input caps in this service (e.g.
# trendspark_max_videos) -- a malicious/huge JSON-LD blob can't blow up the
# prompt or the response.
_MAX_PRODUCTS = 25
_MAX_NAME_CHARS = 200

# C3 (Kabir P1-B audit): a hostile or simply broken JSON-LD/OpenGraph/
# microdata `price` must never flow through as a "scraped" (trusted) fact.
# Negative prices are nonsensical and absurdly large ones (e.g. a JSON-LD
# `price` field carrying a phone number, a timestamp, or a deliberately
# huge value) would otherwise reach calculate_budget labeled "scraped" --
# the same authority as a real, human-set price. Zero IS allowed through
# (a genuinely free item is a real, sane fact) -- only reject the
# impossible/implausible range, don't second-guess real prices.
_MAX_SANE_PRICE = 1_000_000_000.0


@dataclass(frozen=True)
class ScrapedProduct:
    """A product fact recovered from static HTML structured data -- a FACT,
    not a model guess. `source` records which signal it came from, for
    debugging/telemetry only (downstream consumers only need price_source
    scraped|inferred, set by the analyze_site.py merge step)."""

    name: str
    price: float | None
    currency: str | None
    source: str  # "json_ld" | "opengraph" | "microdata"


def _sane_price(price: float | None) -> float | None:
    """Clamps a coerced price to a sane range -- negative and absurdly large
    values are dropped (return None) rather than allowed through as a
    "scraped" (trusted) fact. Zero is left as-is (a free item is real)."""
    if price is None:
        return None
    if price < 0:
        return None
    if price > _MAX_SANE_PRICE:
        return None
    return price


def _coerce_price(value: object) -> float | None:
    if value is None:
        return None
    if isinstance(value, bool):  # bool is an int subclass -- reject explicitly
        return None
    if isinstance(value, (int, float)):
        return _sane_price(float(value))
    if isinstance(value, str):
        cleaned = re.sub(r"[^\d.]", "", value)
        if not cleaned:
            return None
        try:
            return _sane_price(float(cleaned))
        except ValueError:
            return None
    return None


def _extract_offer(offer: dict) -> tuple[float | None, str | None]:
    price = _coerce_price(offer.get("price"))
    currency = offer.get("priceCurrency")
    if price is None:
        spec = offer.get("priceSpecification")
        if isinstance(spec, dict):
            price = _coerce_price(spec.get("price"))
            currency = currency or spec.get("priceCurrency")
    currency_str = str(currency).strip()[:8] if currency else None
    return price, (currency_str or None)


def _walk_json_ld_node(node: object, out: list[ScrapedProduct]) -> None:
    if len(out) >= _MAX_PRODUCTS:
        return
    if isinstance(node, list):
        for item in node:
            if len(out) >= _MAX_PRODUCTS:
                return
            _walk_json_ld_node(item, out)
        return
    if not isinstance(node, dict):
        return

    # @graph / itemListElement nests -- common Shopify/Wix pattern: one
    # <script> tag carrying many entities (Organization, WebSite, Product...).
    graph = node.get("@graph")
    if isinstance(graph, list):
        _walk_json_ld_node(graph, out)

    item_list = node.get("itemListElement")
    if isinstance(item_list, list):
        _walk_json_ld_node(item_list, out)

    raw_type = node.get("@type")
    type_values = raw_type if isinstance(raw_type, list) else [raw_type]
    types = {str(t).strip().lower() for t in type_values if t}

    if types & _PRODUCT_TYPES:
        name = node.get("name")
        offers = node.get("offers")
        price = currency = None
        if isinstance(offers, dict):
            price, currency = _extract_offer(offers)
        elif isinstance(offers, list):
            for offer in offers:
                if isinstance(offer, dict):
                    price, currency = _extract_offer(offer)
                    if price is not None:
                        break
        if isinstance(name, str) and name.strip():
            out.append(
                ScrapedProduct(
                    name=name.strip()[:_MAX_NAME_CHARS], price=price, currency=currency, source="json_ld"
                )
            )
    elif types & _OFFER_TYPES:
        # A bare Offer/AggregateOffer without a wrapping Product -- rarer, but
        # some themes emit it standalone with an itemOffered.name.
        item_offered = node.get("itemOffered")
        name = item_offered.get("name") if isinstance(item_offered, dict) else None
        if isinstance(name, str) and name.strip():
            price, currency = _extract_offer(node)
            out.append(
                ScrapedProduct(
                    name=name.strip()[:_MAX_NAME_CHARS], price=price, currency=currency, source="json_ld"
                )
            )


def extract_json_ld_products(raw_html: str) -> list[ScrapedProduct]:
    """Parses every `application/ld+json` script block. Each block is parsed
    independently -- one malformed block never blocks the others."""
    products: list[ScrapedProduct] = []
    for match in _JSON_LD_RE.finditer(raw_html):
        if len(products) >= _MAX_PRODUCTS:
            break
        block = match.group(1).strip()
        if not block:
            continue
        try:
            data = json.loads(block)
        except (ValueError, TypeError):
            continue  # malformed JSON-LD -- skip this block only, never crash
        try:
            _walk_json_ld_node(data, products)
        except Exception:
            continue  # unanticipated shape -- skip, defense-in-depth
    return products[:_MAX_PRODUCTS]


_OG_WANTED_PROPS = frozenset(
    {"og:title", "product:price:amount", "product:price:currency", "og:price:amount", "og:price:currency"}
)


def extract_opengraph_facts(raw_html: str) -> dict[str, str]:
    facts: dict[str, str] = {}
    for tag in _META_TAG_RE.findall(raw_html):
        prop_match = _META_PROPERTY_RE.search(tag)
        content_match = _META_CONTENT_RE.search(tag)
        if not prop_match or not content_match:
            continue
        prop = prop_match.group(1).strip().lower()
        if prop in _OG_WANTED_PROPS and prop not in facts:
            facts[prop] = content_match.group(1)
    return facts


def extract_opengraph_product(raw_html: str) -> ScrapedProduct | None:
    facts = extract_opengraph_facts(raw_html)
    name = facts.get("og:title")
    price = _coerce_price(facts.get("product:price:amount") or facts.get("og:price:amount"))
    currency = facts.get("product:price:currency") or facts.get("og:price:currency")
    if not name or not name.strip():
        return None
    return ScrapedProduct(name=name.strip()[:_MAX_NAME_CHARS], price=price, currency=currency, source="opengraph")


_MICRODATA_NAME_RE = re.compile(r'itemprop\s*=\s*["\']name["\'][^>]*>([^<]{1,200})', re.IGNORECASE)
_MICRODATA_PRICE_RE = re.compile(
    r'itemprop\s*=\s*["\']price["\'][^>]*(?:content\s*=\s*["\']([^"\']{0,40})["\']|>([^<]{1,40}))',
    re.IGNORECASE,
)
_MICRODATA_CURRENCY_RE = re.compile(
    r'itemprop\s*=\s*["\']priceCurrency["\'][^>]*(?:content\s*=\s*["\']([^"\']{0,10})["\']|>([^<]{1,10}))',
    re.IGNORECASE,
)


def extract_microdata_product(raw_html: str) -> ScrapedProduct | None:
    name_match = _MICRODATA_NAME_RE.search(raw_html)
    price_match = _MICRODATA_PRICE_RE.search(raw_html)
    if not name_match and not price_match:
        return None
    name = name_match.group(1).strip() if name_match else None
    price = None
    if price_match:
        price = _coerce_price(price_match.group(1) or price_match.group(2))
    currency = None
    currency_match = _MICRODATA_CURRENCY_RE.search(raw_html)
    if currency_match:
        currency = (currency_match.group(1) or currency_match.group(2) or "").strip() or None
    if not name and price is None:
        return None
    return ScrapedProduct(name=(name or "product")[:_MAX_NAME_CHARS], price=price, currency=currency, source="microdata")


def extract_structured_facts(raw_html: str) -> list[ScrapedProduct]:
    """Priority order: JSON-LD > OpenGraph > microdata -- the first source
    that yields anything wins (never blended, to keep provenance simple and
    avoid duplicate/conflicting facts for the same product). Returns `[]`
    (never raises) on any parse failure or when the page carries no
    structured data at all.
    """
    if not raw_html:
        return []

    try:
        json_ld_products = extract_json_ld_products(raw_html)
    except Exception:
        json_ld_products = []
    if json_ld_products:
        return json_ld_products

    try:
        og_product = extract_opengraph_product(raw_html)
    except Exception:
        og_product = None
    if og_product:
        return [og_product]

    try:
        micro_product = extract_microdata_product(raw_html)
    except Exception:
        micro_product = None
    if micro_product:
        return [micro_product]

    return []
