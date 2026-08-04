# F-0041 triage — 99× react-hooks/set-state-in-effect

**Decision (user):** triage + fix only genuine. **Result:** 0 genuine correctness bugs.
**Method:** read the setter + effect context for all 99 flagged sites (eslint react-hooks/set-state-in-effect, src/ via project eslint.config.js).

## Classification (all 99)
| Bucket | Count | Verdict | Evidence |
|--------|-------|---------|----------|
| Constant resets/toggles | 36 | benign | `setX('')/null/true/false` — e.g. useBillingData.ts:52 `setIsLoading(true)` where isLoading inits `true` (no-op on mount) |
| Data-fetch triggers | ~38 | benign | `refresh()/loadX()/fetchX()` on mount/dep-change — standard fetch pattern; the async `setData` in `.then` is NOT flagged |
| Auto-select / URL-sync | ~10 | intentional | `setSelected(list[0].id)` (deliverable-submission:60), `setSelectedDeal(match)` (brand-chat:844) — intended UX |
| Responsive / animation / a11y | ~6 | needs effect | use-mobile `setIsMobile(innerWidth<bp)`, CountUp:43 `setDisplay(value)`, ThinkingState:21 `setCompletedCount(steps.length)` (reduced-motion branch, no-op) |
| External-store sync | 2 | pattern-improvement, not a bug | useAdminSocket.ts:73,106 `setStatus(socket.getStatus())` → `useSyncExternalStore` would be more correct; WebSocket-status refactor risk > benefit |

## Why closed without code changes
- The rule is a React-Compiler forward-compat signal, not a bug detector for this codebase. Every site is a benign fetch/loading/reset, an intentional init/auto-select, or a pattern that legitimately requires an effect.
- Mass-rewriting 75 files of working data-fetch/loading code = large churn + real loading-UX/reset regression risk for zero user-visible correctness gain.
- Closed via `promote.py --unautomatable` (signed) rather than a gate: there is no mechanical fix to enforce, and enabling the rule tree-wide as a gate would be permanently red (F-0015 false-red class).

## Follow-ups (not F-0041)
- useAdminSocket.ts:73,106 → optional `useSyncExternalStore` refactor. Left for opportunistic cleanup, not tracked as a bug.
- If a green `npm run lint` is later required, downgrade `react-hooks/set-state-in-effect` to `warn` in eslint.config.js (a TECH-STACK severity decision).
