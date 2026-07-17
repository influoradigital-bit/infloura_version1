"""Eval harness (P3) — GS-3 tenant isolation, GS-4 prompt injection, golden-brand
labeling regression, per docs/AI connect/backend/19-AI-ARCHITECT-REVIEW.md §5.

These are CI-safe: no network calls, no live Claude/Gemini/Spring calls, fully
local and deterministic. Runtime target: well under 2 minutes for the whole
`tests/eval/` package.
"""
