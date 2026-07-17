"""P2-17: production AI-spend ceiling / kill-switch support package.

See wiki/decisions/budget-proposals/2026-07-12-ai-spend-ceiling-and-killswitch.md
for the full spec. `pricing.py` turns raw provider token usage into a dollar
estimate; `spend_tracker.py` accumulates those estimates per UTC day (and,
for the chat route, per workspace) so `check_spend_gate()` (see
`app/costs/gate.py`) can decide whether a new provider-bound request may
proceed.
"""

from __future__ import annotations
