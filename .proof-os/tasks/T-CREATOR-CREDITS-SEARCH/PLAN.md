# Creator credits + web search: what we build first

**For:** Swapnil
**From:** Arjun (Engineering Lead), with Rohan (cost) and Ash (AI)
**Date:** 2026-09-19
**Branch:** `feat/creator-credits-search`, cut from `release/0919` @ `9bfe7dc`, in the `influora-credits` worktree
**Sources:** a code check of `release/0919` (section 6 lists every file read), `CREDITS-SPEC.md` in `T-MEERA-CREATOR-PHASE-B`, and provider pricing and terms pages read on 2026-09-19.

Nothing here is built yet. This file sets the order, the owner, the proof required for each step ("done when"), and who checks it. Nothing closes on the builder's own word.

---

## 1. Decisions already made (Swapnil, 2026-09-19)

| Rule | Value |
|---|---|
| Credit units | **Decimal, one place** (stored in tenths; the creator sees e.g. "37.5 credits") |
| Monthly free credits | 40, reset on the 1st (not cumulative). Signup grant 30. Both from `CREDITS-SPEC` |
| Chat message | **1.0 credit** |
| Voice message | 2.0 at transcribe + 1.0 for the chat turn = 3.0 total (`CREDITS-SPEC` R5) |
| Paste a brief | 3.0 credits |
| **Web search** | **2.5 credits** per search, charged beyond the free weekly searches |
| **Free weekly searches** | **2 per week** on Gemini + Google Search, no credits deducted, for creators on free credits. Unused ones don't carry over |
| Searches beyond the free 2 | Claude web search, 2.5 credits each |
| Credit packs | ₹149 / 50, ₹249 / 100, ₹649 / 300 (`CREDITS-SPEC` §2.3) |

## 2. Decisions still open (each blocks the step named)

| # | Question | Default if no answer | Owner | Blocks |
|---|---|---|---|---|
| D1 | **Who counts as a "free-credit creator"?** The obvious test, `purchased_balance = 0`, does not work: `CREDITS-SPEC` R2 puts the **signup grant into `purchased_balance`** alongside packs. So the test must read the ledger for a `PACK_PURCHASE` row. | "No pack bought this calendar month" | Swapnil | Step 5 |
| D2 | **Do creators who bought a pack also get the 2 free weekly searches?** If not, buying a pack loses a benefit. | Yes, everyone gets 2 free a week | Swapnil | Step 5 |
| D3 | Weekly reset time | Monday 00:00 IST | Swapnil | Step 5 |
| D4 | Budget: about **$483/month at 1,000 creators** (about ₹1,700/month at 40), above the $300 AI line (section 5) | — (no default; needs a yes) | Swapnil | Going live |

---

## 3. Build order: what we make first

Every step lands behind switches that default to **off**. `CREATOR_CREDITS_ENABLED` already exists in the spec; a new `CREATOR_SEARCH_ENABLED` is added for search. So each step can merge without changing anything creators see.

### Step 0: Checks before any product code (no code change except 0.2)

| # | Check | Owner | Done when | Checked by |
|---|---|---|---|---|
| 0.1 | **Gemini key tier.** Must be the **paid** tier: 1,500 free grounded searches a day, and Google does not use the data. On the free tier it's 500 a day, and Google uses creators' searches to improve its products. | meera | Billing tier recorded in this file, with the date checked | rohan |
| 0.2 | **Anthropic library.** Raise the `anthropic` pin in `influora-ai/requirements.txt` from **0.42.0**, which has no web search, to a current version. **Local runs have been on 0.125.0 from the user site-packages while CI and the Docker image install 0.42.0**, so local pytest has never proved the production library. | vikram | Full pytest green **in a clean venv built from `requirements.txt`**, plus the CI run green, as its own commit | meera |
| 0.3 | **Claude Haiku 4.5 + web search test call.** Anthropic's docs don't list which models support it. | vikram | One real call succeeds, with tokens and cost recorded here. If Haiku isn't supported, record Sonnet 4.5 as the model (₹3.34 per search; still profitable) | ash |
| 0.4 | **Consent.** A search sends the creator's question to Google or Anthropic. Does the v2 notice cover that? | nisha (words), kabir | A yes or no recorded. If no: new wording + version bump, shipped in the **same deploy** as search (the G-1 rule) | kabir |

### Step 1: Amend `CREDITS-SPEC.md` (spec only)

Owner **priya**. It edits the spec in place, not as an appendix. `CREDITS-SPEC` was reviewed at 18/69 wrong and "buildable as corrected", so every change here is re-checked against the code.

- **Decimal units:** `monthly_remaining`, `monthly_allotment`, `purchased_balance` (§2.1, L70-72), ledger amounts, and pack `credits` (§2.3 L116, §2.4 L144) are `INT` today. They stay `INT` but store **tenths**: 40 credits = 400. Every cost becomes a tenths setting: chat 10, voice 20, brief 30, search 25. The API returns decimals.
- **Brief charge point:** §4.3 (L470) still says "Phase B1 `CreatorBriefService.paste` (not yet built)". Paste **shipped in B0**, so the hook goes on today's `CreatorBriefController` → `CreatorBriefService.paste`, with a refund when analysis fails.
- **New K12, "Search":** the charge (25 tenths), the weekly free counter (D1-D3), the new route (Step 5), the refund on failure, and the new ledger entry types `SEARCH_DEBIT`, `SEARCH_REFUND`, `FREE_SEARCH`.
- **R4 still holds:** the credit gate lives in Spring and the USD cap stays in Python as a second fuse. The cap now also counts search fees (Step 5).

**Done when:** priya signs the amended spec, and every changed section is edited in place with a dated note.
**Checked by:** kavya reads the amended spec against the files in section 6 and confirms each cited line.

### Step 2: Quick win, independent of credits. Trim Meera's history

- **Why:** the browser sends every message in the thread, and Spring loads up to **100** (`MeeraSessionService.DEFAULT_HISTORY_LIMIT = 100`, L125). All of it is re-sent at full input price on every Meera message, which makes the worst-case chat message ₹5.39 instead of ₹2.86.
- **Change:** `influora-ai/app/prompt/assembler.py` sends the model only the **last 20 messages**. The UI still shows the whole thread.
- **Owner:** vikram. **Done when:** a test with 30 messages proves the model receives 20, and the test goes red when the trim is removed.
- **Checked by:** ash (answer quality with a shorter memory), then meera (full pytest on the pinned library).

### Step 3: Credits core (`CREDITS-SPEC` K1, K2, K5, K7, K9)

- **What:** 4 migrations storing tenths; `CreatorCreditService` (lazy init + signup grant, debit, refund, monthly reset); the credit balance and ledger reads; `CreatorCreditResetJob`; the kill switch (default off).
- **Reuse:** the brand credit system, `AICreditService` + `BrandAiCredit`. ⚠ Phase-e changed brand credits on 17-18 Sep (`V20260917120000` … `V20260918180000`). Coordinate with that lane before touching shared files.
- **Owner:** vikram. **Done when:** `mvn clean test` is green from a `git archive`; the migrations boot on MySQL 8 (Docker test class); and debiting 2.5 from 40.0 leaves exactly 37.5.
- **Checked by:** kavya, then kabir (money-like ledger: no double debit, refund can't exceed the charge).

### Step 4: Charge points (`CREDITS-SPEC` K3, K4)

| Action | Where it's charged | Cost (tenths) |
|---|---|---|
| Chat message | `MeeraSessionService.doSendTurn`, refused **before** any AI call when out of credits | 10 |
| Voice | `CreatorMeeraController` `POST /creator/meera/voice/transcribe` (L277) | 20 |
| Brief paste | `CreatorBriefController` paste → `CreatorBriefService.paste` (B0 code) | 30 |

- **Refund:** each failed AI call returns its credits (K4).
- **Frontend (ananya):** `MeeraCopilotChat.tsx` today handles only `CREATOR_MONTHLY_CAP_REACHED` (L87). Add the "credits exhausted" message with the pack button.
- **Done when:** each of the three charge points has a test that fails when its charge is removed.
- **Checked by:** kavya, then meera.

### Step 5: Web search (new, K12)

**AI service (vikram, `influora-ai`):**
- **Gemini search route.** `app/providers/gemini.py` has **no** Google Search use today, though `google-genai` 0.8.0 already supports it (`GoogleSearch`, `ThinkingConfig`). Thinking kept low and answer length capped. It returns the answer, the sources, and Google's search-suggestions snippet. Nothing is stored; logs record counts only.
- **Claude search route.** Haiku 4.5 (or Sonnet, per 0.3) with web search, **one search per request** (`max_uses: 1`), citations returned.
- **Router.** Gemini while the platform is under **~1,400 searches a day** (a shared daily counter), then Claude. Brave is an optional later backup.
- **Pricing and spend.** `app/costs/pricing.py` has **no search rows** today. Add Claude search at $10 per 1,000 and Gemini grounding at $35 per 1,000 beyond the free allowance. `record_creator_spend` counts both, so the monthly USD cap sees search fees.

**Backend (vikram, `influora-api`):**
- New `POST /creator/meera/search`: feature flag + consent + rate-limit bucket.
- If a weekly free search is left: use Gemini, no charge, write a `FREE_SEARCH` ledger row.
- Otherwise: debit 25 tenths, use the router, and refund if the search fails.
- Weekly counter columns on the creator credit table (D1-D3).

**Rules from the providers' terms** (read 2026-09-19; these are requirements, not polish):
- **Gemini:** results shown **only to the creator who asked**, with Google's search suggestions displayed, and **never stored or analysed**. So a Gemini result is shown as its own card, **never fed into Meera's chat or saved**.
- **Claude:** **sources must be shown** with the answer.
- Search result text is untrusted: never treated as instructions.

**Done when:**
- Tests prove the weekly free path (no debit), the paid path (exactly 2.5), the refund on failure, and the router switching at the daily limit.
- Each test goes red when its piece is removed.
- One real search per provider is recorded, with tokens and cost.

**Checked by:** kabir (untrusted results, the Google snippet rendered safely, the rate limit, no query text in logs), then ash (both search paths), then meera.

### Step 6: Frontend (ananya, `src/`; `CREDITS-SPEC` K10 + search)

- **Credits:** a balance badge with decimals ("37.5"), a low-balance nudge, and the `/creator/credits` page with the ledger.
- **Search card:** "2 free searches left this week", "2.5 credits" on paid searches, sources, and Google's search suggestions (required by Google's terms).
- **API clients:** calls in **both** `src/lib/api.ts` and `src/lib/meera-api.ts` (the two-API-layers rule).
- **Copy:** no "escrow"; no "PR manager" (the release-merge copy rule).
- **Done when:** tsc clean; vitest tests for each state go red when that state is removed; screenshots at 375px and desktop.
- **Checked by:** kavya, then arjun's browser check.

### Step 7: Credit packs (`CREDITS-SPEC` K6, K8)

- **What:** `GET /creator/credits/packs`, `POST /creator/credits/orders`, the Razorpay webhook branch (receipt prefix `credits:`), and the admin grant.
- **Reuse:** `RazorpayClient.createOrder` and `RazorpayWebhookController` (both exist).
- **Owner:** vikram (backend) and ananya (buy flow). **Done when:** a test order credits exactly the pack amount once, even when the webhook arrives twice.
- **Checked by:** kabir (payment path), then kavya.

### Step 8: Verify and last calls

1. **meera:** `mvn clean test`, pytest in a clean venv on the **pinned** library, tsc and vitest, all from a `git archive` of the final commit. Read the skipped count, not only the exit code.
2. **rohan:** cost check against section 5 using the first real token counts.
3. **priya:** last call against the amended spec.
4. **Deploy and live check:** Swapnil's call. Proof on the live site means one chat, one voice message, one brief, one free search and one paid search, each with the right ledger row.

---

## 4. Rules for every step

- **Off by default:** everything ships behind `CREATOR_CREDITS_ENABLED=false` and `CREATOR_SEARCH_ENABLED=false`.
- **Proof:** tests must go red when the thing they guard is removed. Build from a `git archive` of the commit, not the working tree.
- **Staging:** stage from an explicit list of files; another session works in this repo.
- **Branching:** new work branches from `release/0919`. Do not merge `feat/meera-creator-phase-b0` again; it still uses the old ledger ids.
- **Ledger ids:** B0's ledger ids are F-1765..F-1782. New records take ids from the ledger tool as usual.
- **Search results:** never treated as instructions; Gemini results never stored.

---

## 5. Costs (Rohan, 2026-09-19)

Assumptions: ₹88 = $1; Meera's prompt measured (instructions ~1,770 tokens + tool descriptions ~2,615 + creator profile ~200-500); a typical 20-message history; Claude Sonnet 4.5 for chat, Haiku 4.5 for briefs and paid search; Gemini 2.5 Flash for free search. **These are estimates. Replace them with real token counts from the first live week.**

**Cost per action:**

| Action | Credits | Our cost: typical / worst | Our cost per credit |
|---|---|---|---|
| Chat message | 1.0 | ₹1.31 / ₹5.39 (₹2.86 after Step 2) | ₹1.31 |
| Voice message | 3.0 | ₹2.58 / ₹6.66 | ₹0.86 |
| Paste a brief | 3.0 | ₹0.45 / ₹0.79 | ₹0.15 |
| Free weekly search (Gemini) | 0 | ₹0.12 | — |
| Paid search (Claude Haiku 4.5) | 2.5 | ~₹1.70 (Sonnet ~₹3.34) | ₹0.68 |

**Monthly cost:**

| | Per creator | 1,000 creators |
|---|---|---|
| 2 free searches a week (~8.7/month) | ₹1.04 | ₹1,040 ($12), ~290 searches a day, inside Gemini's free 1,500 |
| Realistic use of the 40 credits | ₹41 | $471 |
| **Total, realistic** | **₹42** | **~$483** |
| Worst case: all 40 credits on chat | ₹216 | $2,451 |

**Paid packs:**
- Revenue per credit after GST: ₹2.53 (Starter), ₹2.11 (Standard), ₹1.83 (Power).
- A chat credit is profitable today on every pack.
- If model prices rose 2.5×, chat would lose ₹0.75-1.44 per credit; raising chat to 2.0 credits is then a settings change.
- A paid search earns ₹4.58-6.33 against a cost of ₹1.70.

---

## 6. Files this plan was checked against (`release/0919` @ `9bfe7dc`)

- **Credit spec:** `.proof-os/tasks/T-MEERA-CREATOR-PHASE-B/CREDITS-SPEC.md`: scope K1-K11 (§1), R2 signup grant into `purchased_balance`, R4, R5, `INT` columns §2.1 L70-72 / §2.3 L116 / §2.4 L144, brief hook §4.3 L470.
- **Brand credits:** `influora-api/src/main/java/com/influora/service/meera/AICreditService.java`, `domain/entity/BrandAiCredit.java`, migrations `V14`, `V16`, `V20260917120000`…`V20260918180000`.
- **Charge points:** `service/meera/MeeraSessionService.java` (`DEFAULT_HISTORY_LIMIT = 100`, L125), `web/CreatorMeeraController.java` (routes L138, L176, L212, L245, L277), `web/CreatorBriefController.java`.
- **Payments:** `integration/razorpay/RazorpayClient.java`, `RazorpayWebhookController.java`.
- **AI service:** `influora-ai/requirements.txt` (`anthropic==0.42.0`, `google-genai==0.8.0`); `app/costs/pricing.py` (no search rows); `app/costs/spend_tracker.py` (`record_creator_spend`, cap default $0.75); `app/providers/gemini.py` (no Google Search, thinking not limited); `app/prompt/assembler.py`.
- **Frontend:** `src/components/creator/MeeraCopilotChat.tsx` (handles only `CREATOR_MONTHLY_CAP_REACHED`, L87).
- **Provider terms and prices:**
  - Gemini: ai.google.dev pricing and terms (grounding free 1,500/day on the paid tier, then $35 per 1,000; results only to the requester, no caching).
  - Claude web search: platform.claude.com (web search $10 per 1,000; citations required).
  - Brave: brave.com/search/api ($5 per 1,000, $5 monthly credit, storage needs a storage-rights plan).
