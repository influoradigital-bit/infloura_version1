"""Shoot-guide logic that is not prompt text (spec v2 2026-09-26, Phase 4).

`safe_zones.py` loads the Reel safe zones (`safe_zones.json`, the canonical twin of the app's
`src/lib/shoot-check/safe-zones.json`; a pytest fails if the two differ in any value), and
`checklist.py` turns the validated face and product boxes of a photo check into fixed
"Quick checks" lines. Neither module reads a string the model wrote: they take numbers that
`app/prompt/frame_check.py` has already validated.
"""
