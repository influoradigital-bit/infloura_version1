# Attack Vector 4: Single-Button Fallback

## Scenario

What if ErrorBoundary is later modified to show only ONE button in certain error states?

For example:
- Network errors → show only "Reload page"
- Render errors → show both buttons
- Auth errors → show only "Try again"

## The Question

The structural check (signal 3) requires BOTH button labels to be present to fail:
```python
if RETRY_BUTTON_TEXT in content and RELOAD_BUTTON_TEXT in content:
```

If only one button is present, the structural check does NOT trigger.

This means:
- A crashed route showing only "Reload page" would pass the structural check
- Checks 1-2 (heading text + h1) would still catch it IF the heading is "Something went wrong"
- But if the heading is also different (e.g., "Network error"), all three checks could miss it

## Is This Safe?

It depends on whether showing ONE button alone (without the other) is semantically "the ErrorBoundary fallback" or just "an error state that happens to use one of the same button labels".

If the latter, then only-one-button-present is correct behavior (not the full crash fallback).
If the former, then the check is too narrow.
