# Kavya Attack Vector 1: DEFAULT_MIN = 1 Bypass

## The Vulnerability

A newly added route (not in `w2_ldjson_baseline.json`) that crashes completely will PASS if it has exactly 1 ld+json block.

## How It Happens

1. Developer adds `/new-feature` route (not in baseline yet)
2. Route crashes during prerender (ErrorBoundary fallback renders)
3. Static template block from `index.html` still injects its 1 Organization schema
4. Gate checks: route not in baseline → DEFAULT_MIN = 1
5. Actual count = 1, baseline = 1, check: 1 >= 1 → **PASS**
6. **CRASHED ROUTE SHIPS TO PRODUCTION**

## This Fixture

- Route: `/new-feature-that-crashes` (deliberately not in baseline)
- Content: Full ErrorBoundary fallback (heading + both buttons)
- ld+json: Exactly 1 block (the static template Organization schema)
- Expected behavior under CURRENT implementation: **PASS** (the bug)
- Expected behavior under CORRECT implementation: **FAIL**

## The Fix Required

`DEFAULT_MIN` must be AT LEAST 2, because:
- Every route inherits 1 static template block
- A route with ONLY that block and no page-level schema is either:
  - A crashed route showing ErrorBoundary, OR
  - A route someone forgot to add schema to
- Both cases should FAIL

Better yet: require explicit baseline entries for all prerendered routes and fail loudly on lookup miss instead of falling back to any default.
