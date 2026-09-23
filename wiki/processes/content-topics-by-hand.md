# Adding today's topics by hand (no admin screen)

> **For:** Swapnil and whoever else holds database access.
> **Built:** 2026-09-23, commit 357fd90 (`feature/creator-content-knowledge`).
> **Why no screen:** Swapnil's call — "admin can add directly through database for now, no admin
> screen, for fast we can do this". The read-only preview endpoint below replaces it.

Creator Meera reads these rows through her `get_todays_topics` tool and uses them for "what should
I post today" and (later) the week plan. Festivals and special days are NOT here: they live in
`influora-ai/app/planner/events.jsonl`, in the repo.

## The one command you need

```sql
INSERT INTO content_topics
  (category, title, angles, live_from, live_until, region, source_note, sensitivity, status)
VALUES
  ('Beauty',
   'Korean glass-skin routine is trending again',
   'Try it with 3 Indian products\nThe one step most people skip\nWhat it costs in India',
   '2026-09-23', '2026-10-07', 'India', 'seen on IG and YouTube', NULL, 'APPROVED');
```

| Column | Rule |
|---|---|
| `category` | One creator category, or the literal `ALL` for every creator. Matched ignoring case, after trimming. |
| `title` | What the topic is, in the creator's words. Max 200 characters. |
| `angles` | One angle per line, plain text. **Not** JSON. Blank lines are dropped. |
| `live_from` / `live_until` | Plain dates, both ends inclusive. Keep windows short; 2 weeks is a good default. |
| `region` | Free text, defaults to `India`. Not used for filtering yet — it is a note for you. |
| `source_note` | Where you saw it, so a claim can be traced later. Optional. |
| `sensitivity` | A note Meera must follow, e.g. `religious: respectful only`. Optional. |
| `status` | `DRAFT` (default, ignored by Meera), `APPROVED` (served), `REJECTED` (ignored). |

`id`, `created_at` and `updated_at` fill themselves.

## Check what you typed, before a creator sees it

```
GET /admin/content-topics/preview?category=Beauty
GET /admin/content-topics/preview?userId=<a creator's users.id>
```

Admin JWT required (`/admin/**` is `hasRole("ADMIN")` in `SecurityConfig`). It returns, for today
in Indian time: the topics that WOULD be served, the rows that were dropped with their rejection
category, and the categories used for matching. Run it after every insert — a typo in `category`
or a wrong date fails silently, and nothing else will tell you.

## Which categories actually match

Creator categories are free text (`creator_profiles.categories_json`), so `Beauty`, `beauty` and
`Beauty & Skincare` can all exist. `Beauty` will not match a creator who stored
`Beauty & Skincare`. List the real ones before you type (MySQL 8):

```sql
SELECT c.cat, COUNT(*) AS creators
FROM creator_profiles p,
     JSON_TABLE(p.categories, '$[*]' COLUMNS (cat VARCHAR(80) PATH '$')) c
GROUP BY c.cat
ORDER BY creators DESC;
```

## What the code does to your row, whatever you type

- **Only `APPROVED` rows inside their date window are served.** Boundary days count as inside.
- **Every row is screened on the way out**, not on the way in — nothing checks a hand-typed row as
  you insert it. The title and every angle go through the same word filter the trend job uses
  (`TrendHeadlineScreener`). Anything about death, crime, courts, disasters or hate is dropped even
  if it says `APPROVED`. A drop is logged by id and category only; the text is never logged.
- **At most 5 topics** reach one creator per call. A dropped row does not use up a slot.
- **The text is treated as untrusted.** Title, angles, category and the note ride inside an
  `<untrusted_editorial>` block in the prompt, so a row reading "ignore your rules" is data, not an
  instruction.
- **Meera presents it as a topic, not a fact.** She uses your angles as written, adds no numbers of
  her own, never rates a brand's product, and follows the `sensitivity` note.

## Two things to remember

1. **Your rows are not in git.** They exist only in the live database, so a restore from an older
   backup loses them. Keep your `INSERT` statements in a file of your own.
2. **An empty result is normal.** If nothing is live for a creator's category today, Meera falls
   back to the content knowledge. Nothing breaks.

## Not built yet

The 7-day week plan (`plan_my_week`), the festival-file loader and the post-pattern analysis are
the next step, in that order. See `wiki/ai-review/daily-topics-week-plan-ai-review.md`.
