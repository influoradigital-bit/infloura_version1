# T-QUEUE-0820 — Give the items that matter a real exit test

Opened 2026-08-20. Owner: swapnil. Reviewer: kabir.

**done_when:** the items below each carry a written exit test, and `queue.py --next` returns a
runnable item instead of refusing.

---

## 0 · Why this task exists

`queue.py` reports **144 runnable, 145 open, 370 total (225 closed)** — and then refuses to
advance:

```
REFUSED: F-0051 is next by rank but has no gate that can declare it done.
```

**Every open item reads `NO EXIT TEST`.** A backlog where nothing can be declared finished is not
a backlog, it is a list of regrets. Adding a 146th record does not help; giving the top of the
list a way to terminate does.

## 1 · The queue's ranking is not the business ranking

`queue.py` orders by code severity x blast radius. It classes **F-0370** — the live CTAs on
influora.in pointing at `app.influora.io`, which does not resolve, losing every organic signup
right now — as **#44, "cleanup"**, while doc-consistency records sit in the top 15 because they
touch files near the money path.

The OS is measuring what it can measure. It cannot see revenue. **Do not follow queue rank
blindly for business decisions** — use it for code risk, use section 3 for what to do.

---

## 2 · A gate refuted one of my own findings — record it

`.proof-os/gates/schema_helpers_emitted.py` was written to catch **F-0371**
("getFaqPageSchema built and never emitted"). It **exits 0** — the helper IS emitted, at
`src/components/site/FaqSection.tsx`, and all nine exported helpers are referenced by a page.

F-0371 as written is wrong. It came from grepping one file (`src/pages/blog/post.tsx`) and
generalising to the codebase — the same shape as F-0043. Recorded as **F-0372**.

**The narrower gap survives:** `blog/post.tsx` emits `Article` + `BreadcrumbList` only, so an FAQ
section written in post markdown produces no `FaqPage` schema. But only **1 of 7** posts has an
FAQ section today, so this is a small improvement, not the "highest-value AEO gap" it was called.

Lesson worth keeping: **write the gate before recording the finding.** The gate is what tells you
whether the finding is real.

---

## 3 · The items that matter, each with an exit test

Ordered by business impact, not queue rank.

### Revenue is leaking now

| id | what | exit test |
|---|---|---|
| **F-0370** | influora.in Log in / Sign up point at dead `app.influora.io` | a gate that curls the live marketing site and asserts every outbound CTA href resolves DNS and returns 200 or 301 — never NXDOMAIN or 404 |
| **F-0369** | WooCommerce setup screen hardcodes `https://api.influora.com/webhooks/woocommerce` instead of deriving it from `VITE_API_BASE_URL` | a gate asserting no user-facing instruction string in `src/` contains a literal `https://` host that is not read from config |

### Unblocks an entire tier (Meta)

| id | what | exit test |
|---|---|---|
| **F-0366** | Meta app registered against the dead `app.influora.io` privacy URL | a gate that resolves every externally-registered URL (privacy, ToS, deletion, redirect) and fails on non-200 |
| **F-0356** | no Data Deletion / Deauthorize callback exists | both endpoints return Meta's required JSON shape; `MetaOAuthControllerTest` covers both |
| **F-0365** | app in Development Mode, permissions at STANDARD | `devtools_app basic_settings` shows `is_live: true` AND `devtools_app_review privileges` shows `access_level: advanced` |
| **F-0367** | App Secret Proof off; client sends no `appsecret_proof` | `devtools_app security` shows `require_app_secret: true` AND a test asserts the client sends the param |
| **F-0355** | audience demographics use removed `audience_*` metrics — **BLOCKED, needs a live account** | `AudienceDemographicsJobTest` asserts `follower_demographics` with `breakdown` + `timeframe`, and one live call returns a snapshot |

### Structural / cheap

| id | what | exit test |
|---|---|---|
| **F-0358** | `build.mvn.sh` from repo root finds no `pom.xml`, so repo-scoped CHECK covers zero backend | a gate that locates each build oracle's project marker and runs it from that directory; exits 1 if any module is silently uncovered |
| **F-0371** | blog posts emit no `FaqPage` schema (restated per §2) | a gate asserting: if a post's markdown contains an FAQ section, the post page emits `FaqPage` |
| **F-0357** | connect flow states no Facebook Page prerequisite | **already fixed** in commit c186477 — needs a gate before it can close |
| **F-0372** | a finding was recorded from a single-file grep | promotion of the discipline, not a code fix — an unautomatable close signed by a human |

### Not gateable this session

**F-0051** (`unverified-at-runtime`, top of queue) needs a live click-through by neha across
creator-chat, brand-campaign-detail, FlagQueue and deal-room after a fund flow. That is an e2e
test or a human, not a static gate. Until it is one of those, `queue.py --next` will keep
refusing at rank 1 regardless of what is gated below it.

**Decide one of:** write the e2e (`gates/e2e.sh` exists), close it `--unautomatable` with a named
human who accepted it, or reclassify it so it stops blocking the head of the queue.

---

## 4 · Gates written so far

- `.proof-os/gates/schema_helpers_emitted.py` — **exit 0**. Asserts every exported `get*Schema`
  helper is emitted by at least one non-test page. Refuted F-0371 as written.

## 5 · NOT CHECKED

Whether any exit test above is achievable as written — none of them were run except the schema
one. Whether the queue's 144 other records are still real; many predate work that may have fixed
them, and nothing re-verifies a record once opened. Whether `F-0051`'s 13 sites still exhibit the
symptom recorded against commit 066e7de. Business impact ordering in section 3 is judgment made
in-session, not measured — no revenue data was consulted.
