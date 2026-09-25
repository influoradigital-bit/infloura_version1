# Adding today's topics by hand (no admin screen)

> **For:** Swapnil and whoever else holds database access.
> **Built:** 2026-09-23, commit 357fd90 (`feature/creator-content-knowledge`).
> **Updated:** 2026-09-25 - 17 calendar categories, several categories per row, onboarding-option
> names reach only that option, and the preview's per-part `unmatched_categories`.
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
| `category` | A calendar category (`Food`, `Fashion`, ...), several separated by commas (`Fashion, Culture`), or the literal `ALL` for every creator. Case and extra spaces are ignored. See *Which categories actually match*. |
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
GET /admin/content-topics/preview?category=Fashion,%20Culture
GET /admin/content-topics/preview?userId=<a creator's users.id>
```

Admin JWT required (`/admin/**` is `hasRole("ADMIN")` in `SecurityConfig`). It returns, for today
in Indian time: the topics that WOULD be served, the rows that were dropped with their rejection
category, the categories used for matching (`resolved_categories`), and `unmatched_categories`
(parts of today's rows that will not do what they look like; see below). Run it after every insert
— a typo in `category` or a wrong date fails silently, and nothing else will tell you.

`category` is split on commas exactly like a row's category, so `?category=Fashion, Culture`
previews a creator who holds both `Fashion` and `Culture` (`resolved_categories` shows the two
parts). Encode the space as `%20` or leave it out (`?category=Fashion,Culture`); both give the same
split.

## Which categories actually match

Write a topic's `category` in the calendar's own words. Both sides of the match go through one
table (`CreatorCategoryMap` in Java, `influora-ai/app/planner/categories.py` in Python, kept
identical by a test), and the topic reaches the creator when the two sides share a name. Case and
extra spaces do not matter. The two sides are read differently:

- **The creator's side fans out.** A creator's onboarding option becomes every calendar category
  it covers: a `Parenting & Family` creator counts as Parenting, Food and Education, so a `Food`
  topic reaches them.
- **Your topic's side does not.** A calendar word stays itself (`Food`), and a word with ONE
  meaning in the table resolves to it (`Tech` is Technology, `Shopping` is Lifestyle, `Comedy` is
  Entertainment). An onboarding option with several meanings (`Parenting & Family`,
  `Music & Dance`, `Fashion & Lifestyle`, `Finance & Business`, `Art & Photography`,
  `Tech & Gaming`) is kept as that exact name, so it reaches **only creators who picked that
  option** — a `Parenting & Family` topic does not reach every Food and Education creator.

### The words to use

The 17 calendar categories, plus `ALL`:

| Write this in `category` | Reaches creators who picked, in onboarding | Also reaches creators who typed |
|---|---|---|
| `ALL` | everyone | everyone |
| `Food` | Food & Cooking, Parenting & Family | food, cooking |
| `Fitness` | Fitness & Health | fitness, health, gym |
| `Beauty` | Fashion & Lifestyle, Beauty & Skincare | beauty, skincare, makeup |
| `Finance` | Finance & Business | finance, money |
| `Education` | Education & Learning, Parenting & Family | education, study |
| `Gardening` | none of them | gardening |
| `Travel` | Travel & Adventure | travel |
| `Local business` | Finance & Business | local business |
| `DIY or crafts` | Art & Photography | diy or crafts, art, craft, crafts, diy |
| `Technology` | Tech & Gaming | technology, tech, gadgets |
| `Culture` | Fashion & Lifestyle, Entertainment & Comedy, Art & Photography, Music & Dance | culture |
| `Fashion` | Fashion & Lifestyle | fashion |
| `Lifestyle` | Fashion & Lifestyle | lifestyle, shopping |
| `Entertainment` | Entertainment & Comedy, Music & Dance | entertainment, comedy |
| `Gaming` | Tech & Gaming | gaming, games |
| `Music` | Music & Dance | music, dance |
| `Parenting` | Parenting & Family | parenting, family |

`Gardening` is a real calendar word, but no onboarding choice leads to it, so a `Gardening` topic
reaches almost nobody, and the preview flags it (`no creator group reaches it`). Pair it with a
wider word (next section) or skip it.

### Several categories in one row

Separate them with commas: `'Fashion, Culture'`. The row reaches a creator who matches any one of
them. Spaces around the commas do not matter and empty parts are dropped (`'Food,,'` is just
`Food`). If any part is `ALL`, the row goes to everyone, so do not mix `ALL` with other words.

```sql
INSERT INTO content_topics (category, title, angles, live_from, live_until, status)
VALUES ('Fashion, Culture', 'Festive outfits under Rs 1,000', 'Three looks, one budget\nWhat to rent, not buy',
        '2026-10-01', '2026-10-14', 'APPROVED');
```

### Other words go through the same table

- **A single-meaning word resolves.** `Tech` becomes Technology, `Shopping` becomes Lifestyle,
  `Comedy` becomes Entertainment, `Dance` becomes Music, `Family` becomes Parenting. A `Shopping`
  topic therefore reaches `Fashion & Lifestyle` creators (through Lifestyle), and a `Tech` topic
  reaches `Tech & Gaming` creators.
- **An onboarding option's name reaches only that option.** A topic typed `Fashion & Lifestyle`
  reaches creators who picked `Fashion & Lifestyle`, and nobody who only has Beauty or Culture. Use
  it when you mean exactly that group; use the calendar word (`Fashion`) when you mean the subject.

The full list of words is `CATEGORY_MAP` in `influora-ai/app/planner/categories.py`.

A word that is not in the table (`Snacks`, or a typo such as `Fahsion`) maps to nothing. That part
reaches only creators who typed that exact word, which is usually nobody.

### The preview checks every part of every row

`GET /admin/content-topics/preview` returns `unmatched_categories`: one entry per **part** of a row
that is live today (`APPROVED`, inside its dates) and will not do what it looks like. Each entry is
`{ "id": ..., "category": "...", "part": "...", "reason": "..." }`, never the title or angles. The
list is the same whatever `category` or `userId` you preview with, and `unmatched_categories_note`
says in a few lines what each reason means.

| `reason` | What it means | Example |
|---|---|---|
| `not a known category` | The part maps to no calendar category and is not `ALL`: a typo or an off-table word. It reaches only creators who typed that exact word. | `'Food, Snakcs'` lists the part `Snakcs` (the `Food` part still works) |
| `no creator group reaches it` | The part is a calendar category no onboarding option maps to. Today that is only `Gardening`. | `'Culture, Gardening'` lists `Gardening` |
| `ALL with other categories: the row already reaches everyone` | The row has an `ALL` part, so every other part changes nothing. Each non-`ALL` part is listed. | `'ALL, Food'` lists `Food` |

An onboarding option's name (`Parenting & Family`) is not listed: it deliberately reaches the
creators who picked it. A row whose category is blank (`' , '`) is listed once with an empty part.

When a part appears there:

1. `not a known category` is most likely a typo. Fix it with a calendar word from the table above:
   `UPDATE content_topics SET category = 'Fashion' WHERE id = <id>;`
   If the word is deliberate (a niche only some creators typed), check that those creators exist
   (query below). If none do, change it.
2. `no creator group reaches it`: add a wider word next to it, or change it.
3. `ALL with other categories`: set the category to just `ALL`, or drop `ALL` if you meant only
   those groups.
4. Run the preview again. The list should be empty.

What creators actually stored (MySQL 8), for step 1:

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
