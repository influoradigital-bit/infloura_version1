# Arjun · assignment board — who solves which error
Task T-ASSIGN-0902 · 2026-09-02 · 230 open failures

**Assignment is a lookup, not a judgment.** `registry.json` declares each service's
jurisdiction, so the owner of a record is derived by matching its `where` path against those
globs — no model opinion enters. 195 of 230 resolve this way. The other 35 need a human
decision and are listed as such rather than guessed at.

Only `kind: producer` and `kind: oracle` services can own a repair. Judgment services
(kavya, kabir, priya, ash, tejas, nisha, aditya) review work; they do not own it. This
matters because **kabir's jurisdiction is `**`** — left in the pool, the security reviewer
would have been assigned all 230 records.

## Load by assignee

| assignee | kind | open records | jurisdiction |
|---|---|---|---|
| **ananya** | producer | **92** | `src/components/**`, `**/*.tsx`, `src/lib/**`, `src/hooks/**`, `src/admin/**` |
| **vikram** | producer | **90** | `app/api/**`, `prisma/**`, `influora-api/**` |
| **dev** | producer | **10** | `scripts/**`, `ci/**`, `.proof-os/gates/**` |
| **zara** | producer | 2 | `wiki/assets/**`, `public/**` |
| **meera** | oracle | 1 | `package.json`, `docker/**`, `Dockerfile`, `deploy/**` |
| *unassigned* | — | **35** | `where` matches no jurisdiction — see below |

Reviewers are assigned by the same registry, not chosen per-task: `src/**` → kavya,
`influora-api/**` → kabir (via `**`), AI paths → ash. A reviewer must be dispatched into a
**fresh context** with the artifact and the `done_when` only, or the review is an `echo` and
scores nothing.

## Do these FIRST — 7 classes are blocking all other work

`promote.py --recurrence` exits 1: these classes have recurred 3+ times with no gate. The OS
refuses further work in a blocked class until a gate exists. Writing one gate closes the
detection for every member at once, which is why these outrank the individual records.

| class | × | gate owner | records |
|---|---|---|---|
| `unreachable-endpoint` | **9** | vikram | F-0416, F-0443–F-0450 |
| `empty-state-misleads` | 4 | ananya | F-0278, F-0349, F-0410, F-0436 |
| `unenforced-limit` | 4 | vikram | F-0399, F-0400, F-0417, F-0418 |
| `contract-drift` | 3 | ananya | F-0408, F-0409, F-0431 |
| `dead-control` | 3 | ananya (2), vikram (1) | F-0405, F-0411, F-0412 |
| `missing-feature` | 3 | vikram | F-0403, F-0413, F-0414 |
| `stale-runtime-copy` | 3 | dev | F-0053, F-0426, F-0428 |

Highest leverage is `unreachable-endpoint` (×9, vikram): a gate that walks every
`@*Mapping` and asserts a client call site would close nine records and catch the tenth
before it ships. `dead-control` needs a *click* test, not a static one — a div styled as a
card passes tsc, eslint, screenshot review and a human skim.

## What each owner is actually holding

**vikram — 90, backend.** Concentrated in endpoints that exist but nothing calls, and limits
that are declared but never enforced. Representative: F-0416 — `POST /wallet/escrow/refund`
is real, authorised, and returns funds to the brand wallet, with no frontend anywhere.
F-0399 — campaign budget is checked per-offer but never summed across the campaign, so N
creators can each be offered the full budget.

**ananya — 92, frontend.** Concentrated in surfaces that render something plausible instead
of the truth. F-0278 — a brand with zero campaigns is told "All caught up!" with a green
tick, which is indistinguishable from a brand that finished its work. F-0405 — four of eight
brand discovery filters never leave the browser.

**dev — 10, tooling.** The proof-os machinery itself, including F-0053: the project-local
`registry_render.py` is a 0.3.x copy that rejects `may_claim: echo` for 7 services. Tooling
that reports the wrong thing about the tooling is worth clearing early.

**zara — 2, assets. meera — 1, deploy config.** Small, independent, unblocked.

## The 35 unassigned — a decision, not a lookup

These carry a `where` that names no file in any jurisdiction. Two distinct kinds, and they
want opposite treatment:

- **9 have no path at all** — the `where` is prose: `"this session: gates/build.sh run via
  device_bash background job"` (F-0026), `"commit 066e7de (frontend lint fix, 13 files)"`
  (F-0051). These cannot be routed until someone rewrites `where` as a path. That is a
  ledger-hygiene task, not an engineering one.
- **26 point at proof-os's own skills and scripts** — `arjun/SKILL.md`, `meera/SKILL.md`,
  `scan.py`, `skills/os-setup/SKILL.md`. No service claims the plugin's own source. Either
  extend `dev`'s jurisdiction to cover it, or accept these are Swapnil's.

## Sequencing

1. **Unblock** — 7 gates, owned vikram (3), ananya (3), dev (1). Nothing else in those
   classes may proceed first.
2. **Then the money and auth paths** — queue rank orders these already.
3. **In parallel, unblocked** — zara (2), meera (1), and the audit findings F-0452–F-0456.
4. **Ledger hygiene** — rewrite the 9 prose `where` values so they can be routed at all.

## Not checked

- Whether an assignee has capacity, or whether 92 records is a reasonable queue for one
  service. This board reports ownership, never throughput.
- Whether each record is still real. Several date from earlier audits, and a record whose
  `where` moved may already be fixed — `recall.py` marks those STALE, and this board does
  not re-verify them.
- Whether a record sitting in one jurisdiction is genuinely fixable there. F-0416 is filed
  against the backend but the missing half is a frontend caller, so some records will need
  a pair rather than an owner.
