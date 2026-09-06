# Kavya Attack Vector 1b: DEFAULT_MIN = 1 Schema Regression Bypass

## The Real Vulnerability

A newly added route (not in baseline) that SHOULD have multiple schema blocks but ships with only 1 will PASS.

This is NOT about crashed routes (checks 1-3 catch those). This is about SCHEMA REGRESSIONS.

## How It Happens

1. Developer adds `/new-product-page` route
2. Route renders correctly (no ErrorBoundary)
3. Developer forgets to add JsonLd components, OR they had them but they broke silently
4. Only the 1 static template Organization block renders
5. Gate checks:
   - Check 1-3 (ErrorBoundary): PASS (no crash)
   - Check 4 (ld+json): route not in baseline → DEFAULT_MIN = 1, actual = 1, 1 >= 1 → PASS
6. **ROUTE WITH MISSING SCHEMA SHIPS**

## A Product Page Should Have

Looking at `/pricing` (baseline = 4):
- 1 static Organization (template)
- 1 WebPage or Product schema
- 1 BreadcrumbList
- 1 FAQPage
= 4 blocks minimum

A new product page with only the template block is a schema regression even if it renders.

## This Fixture

- Route: `/new-product` (not in baseline)
- Content: Real h1, real content, NO ErrorBoundary
- ld+json: Exactly 1 block (only the template Organization)
- Expected under CURRENT: **PASS** (the bug)
- Expected under CORRECT: **FAIL** or require explicit baseline entry

## The Fix

`DEFAULT_MIN` must be higher (at least 2), OR better yet: REQUIRE explicit baseline entries for all routes and exit 2 (GATE UNAVAILABLE) on lookup miss.
