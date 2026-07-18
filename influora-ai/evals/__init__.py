"""Offline-runnable AI eval harness for influora-ai's three live AI features
(brand-safety GARM classification, analyze-site niche/tone classification,
trend-tag closed-vocab recovery tagging).

See evals/README.md for how to run this. Nothing in this package is imported
by app/ — it is a standalone quality-gate tool, never a runtime dependency.
"""
