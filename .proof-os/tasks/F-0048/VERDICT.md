# F-0048 verdict — unused locals/imports

**Result: 173 → 8** unused-vars (95% removed). Ananya's run was **interrupted by an
API error** mid-sweep; independently verified the resulting state below — not
trusted from the partial report.

## Independent verification (post-interruption)
- `tsc -p tsconfig.json --noEmit` — **exit 0** (tree compiles).
- `vitest run` — **313/313** (behavior guard for 165 removals across 54 files).
- diff shape — **54 files, +57 / −277 (net −220)**, removal-shaped; no file has
  suspicious insertions → no logic changes, pure dead-code removal.
- rule delta — **no new error rule** appeared; total src errors 317 → 151.

## The 8 that remain (correctly skipped per the protocol)
| Site | Why left |
|------|----------|
| `src/app/layout.tsx:6,7` `_geist`, `_geistMono` | Next.js font scaffolding in a Vite app; `_`-prefixed. Likely a dead route file — remove whole-file separately, not piecemeal. |
| `MeeraChatPanel.tsx:671` `_lang` | `_`-prefixed intentional keep. |
| `FundEscrowButton.tsx:117` `onFunded` | Dead optional prop: declared `:42`, never called, no parent passes it. **Removable** (drop prop + destructure) — safe tail cleanup, not a bug. |
| `contract-card.tsx:14` `currentUserType`; `deliverable-card.tsx:41` `id`; `creator-chat.tsx:1406` `revisionNotes` | Props/destructures Ananya left as borderline — verify each is not a signature/API contract before removing. |
| `contract-generator.ts:178` `filename` | Assigned-but-unread; skipped under the side-effect rule. Check RHS is pure, then remove. |

## Close
Closed via `--unautomatable`: the bulk dead unused-locals are removed and verified,
but a "0 unused-vars" gate would be wrong — the `_`-prefixed entries are intentional
and the rest need per-site judgment. There is no clean mechanical 0-target here.
Going forward, `npm run lint` in CI is the right enforcement (a project decision).

**Uncommitted:** these 54 files are working-tree changes, not yet committed.
