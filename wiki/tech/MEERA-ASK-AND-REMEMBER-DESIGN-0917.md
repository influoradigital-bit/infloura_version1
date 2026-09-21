# Meera "Ask and Remember" — Design (0917)

**Owner:** Priya (CTO) | **Status:** DESIGN ONLY, no code | **Branch read:** `feat/meera-creator-phase-e` | **Date:** 2026-09-17
**Origin:** Swapnil, in his words: when the AI isn't sure, it asks about that one thing, gets the answer, stores it, and learns.

All `file:line` citations below come from files opened for this design. Anything not opened is marked **[unverified]**.

---

## 0. Current state (re-checked, not taken from Arjun)

| Claim | Verified? | Evidence |
|---|---|---|
| When there's no rate band, Meera refuses to quote and asks what the brand pays | YES | `CalculateBudgetExecutor.java:141-144` (band null → `insufficient_data`), rationale text `CalculateBudgetExecutor.java:251-263` ("ask the brand what they usually pay…"); persona rail `persona.py:128-143`, `persona.py:222-227` |
| The band is null almost always today | YES (by construction) | Resolver returns null with no `niche_tags` (`CalculateBudgetExecutor.java:227-237`); `niche_tags` only comes from the analyser (`BrandProfile.applyAnalysisResult`, `BrandProfile.java:165-180`; caller `AnalyzeSiteTriggerService.java:326-340`); k-anon floor 5/5 (`BrandContextAssembler.java:263`, `:431`) |
| `BrandProfile` has no budget / usual-rate / pricing field | YES | Every field is listed at `BrandProfile.java:19-71` |
| The persona tells Meera the answer is chat-only | YES | `persona.py:225-227`: use their answer as the budget "for the rest of the conversation… you are not persisting this budget" |
| Chat history is untrusted | YES | Replayed turns are wrapped `_wrap_untrusted(...)`, `assembler.py:716-756` |
| Nothing stores a brand-stated rate/budget | Not fully re-grepped; consistent with everything opened. **[partially verified]** |

**Two extra findings that change the design:**

- **F-A. Re-analysis overwrites everything.** `applyAnalysisResult` replaces catalog, aesthetic, tone, niche and competitors in one go (`BrandProfile.java:171-175`). Any brand-stated value put in those columns would be silently lost the next time the site is analysed. Brand-stated facts therefore **cannot** live in the analyser's columns.
- **F-B. Block B drops price provenance.** The Java side carries `price_source` into the context (`BrandContextAssembler.java:115-116`, defaulting to `inferred` at `:206-209`), but `build_block_b` shows the catalog as `name (INR price)` with no label (`assembler.py:408-415`). The C1 label never reaches the prompt text; only `calculate_budget` sees it. This is a pre-existing gap. It matters here because adding a third price source (`brand_stated`) to an unlabelled line would make the confusion worse. **Fix F-B as part of this build.**

---

## 1. What Meera may learn (Q1)

Rule: store a fact only if (a) the brand is the real authority on it, (b) it changes what Meera says or asks next time, and (c) it cannot become a charged amount without a separate human action (see §8).

| Candidate fact | Store? | Why |
|---|---|---|
| **Typical per-creator budget** (min–max, INR, for one whole collaboration) | **YES** | This is exactly what Meera asks every time (`CalculateBudgetExecutor.java:258-260`). The unit is pinned to "whole collaboration per creator" to match the band's unit (`persona.py:249-252`), so it can't be misread as a per-reel rate. |
| **Typical total campaign budget** (min–max, INR) | **YES, short expiry** | It's the other half of the same question (`CalculateBudgetExecutor.java:259-260`). Store it only as "what you usually spend". A specific campaign's budget belongs in the campaign form. |
| **Product price** (name + price, brand-stated) | **YES, kept apart from the scraped catalog** | Stops Meera guessing prices. Stored with `source=BRAND_STATED` and **never** written into `productCatalogJson`, so the C1 resolver (`CalculateBudgetExecutor.java:122-123`, `:291+`) can still only call a price "scraped" when the analyser found it. |
| **Niche / category** (1–3 tags from a fixed list) | **YES — the most valuable one** | This is the only fact that unlocks real pricing: it is the lookup key for `findRateBandCandidates(niche)` (`CollaborationRepository.java:63-75`, `CalculateBudgetExecutor.java:231-236`, `MeeraContextService.java:490-494`). It chooses **which** real band to read. It never adds a value to the band. |
| **Target platforms** | **YES** | Cheap and useful. `create_campaign` already accepts `platforms` (`schemas.py:214`), and it saves a question on every draft. |
| **Preferred creator size** (nano/micro/mid/macro) | **YES** | Matches the tier thresholds already in the band code (`BrandContextAssembler.java:265-267`). v1 uses it only for wording and filtering. Picking a tier-specific band is v2 (it needs its own k-anon check per tier). |
| Free-text notes ("we hate unboxing videos") | **NO (v1)** | Unbounded text in a system block is a prompt-injection surface, and it can't be validated. Revisit with a length cap and neutralisation. |
| What other agencies or creators charged them | **NO** | It's third-party pricing and unverifiable, and it's the most likely thing to get repeated later as a "market rate". |
| Revenue, margins, GST/PAN, contact details | **NO** | PII/financial data, already on the never-include list (`BrandContextAssembler.java:41-49`). |
| Anything a creator said | **NO** | Wrong tenant and wrong audience. |

---

## 2. Where it lives (Q2)

**Decision: a new table `brand_facts`, one row per fact version. Nothing new on `BrandProfile`, and no change to the analyser's columns.**

Why not add columns to `BrandProfile`:
1. F-A: re-analysis would clobber them (`BrandProfile.java:171-175`).
2. Those columns have no room to record source, when, or which conversation.
3. Edit history (§6) needs version rows. A column can only hold the latest value.

Proposed shape. Migration name follows the existing `V<timestamp>__name.sql` convention, e.g. latest `V20260912120000__backfill_free_subscriptions.sql`.

```
brand_facts
  id                 CHAR(26) PK (ULID)
  workspace_id       CHAR(26) NOT NULL, indexed          -- tenant boundary
  fact_key           ENUM('PER_CREATOR_BUDGET','CAMPAIGN_TOTAL_BUDGET','PRODUCT_PRICE',
                          'NICHE','TARGET_PLATFORMS','CREATOR_TIER') NOT NULL
  subject            VARCHAR(120) NULL                    -- product name for PRODUCT_PRICE only
  value_json         JSON NULL                            -- shape is validated per fact_key; NULL once deleted
  source             ENUM('BRAND_STATED') NOT NULL        -- v1: the ONLY value allowed (see §3)
  conversation_id    CHAR(26) NULL                        -- where it was said
  stated_by_user_id  CHAR(26) NOT NULL                    -- the human who tapped Save
  brand_quote        VARCHAR(200) NULL                    -- the brand's own words, shown on the card
  created_at, confirmed_at, expires_at   TIMESTAMP
  superseded_by      CHAR(26) NULL
  deleted_at         TIMESTAMP NULL
  UNIQUE-ish: at most one live row per (workspace_id, fact_key, subject), enforced in the service
```

**Scraped vs stated: neither overwrites nor merges. They sit side by side.** The analyser's values stay in `brand_profiles`. A single read-side resolver, `BrandFactResolver`, produces the value Meera actually uses, and every value keeps its label:

| Fact | Which value wins | Why |
|---|---|---|
| NICHE, TARGET_PLATFORMS, CREATOR_TIER | brand-stated (live, not expired) **>** scraped | The brand knows its own niche better than a scraper does, and none of these are money. |
| PRODUCT_PRICE | Both shown, each with its own label. Only analyser rows can ever be `scraped`. | Keeps C1 intact. |
| Budgets | brand-stated only (there's no scraped version) | — |

**Niche must come from a fixed list.** The band query matches with `JSON_CONTAINS(cp.categories, JSON_QUOTE(:niche))` (`CollaborationRepository.java:73`). The mocks already disagree on casing: `'Fashion'` in `src/lib/api.ts:6739` vs `'skincare'` in `src/lib/meera-api.ts:648`. JSON string matching in MySQL is case-sensitive **[unverified against the live DB]**. A brand-stated niche that isn't normalised to the creator-category list will quietly never match any band. I did not find a canonical category enum **[unverified; Vikram to locate or create one]**. The save endpoint rejects any niche not on that list.

---

## 3. Provenance (Q3)

1. **The server sets the source; nobody else can.** The `source` column only allows `BRAND_STATED` in v1. The save endpoint has no `source` input, and neither does the model-facing tool (§4). This is the same fix C1 applied when it removed `price_source` from `calculate_budget` (`schemas.py:124-129`). Scraped values stay where the analyser writes them (`AnalyzeSiteTriggerService.java:326-340`). Platform-verified figures (band, outcomes) are **computed live and never stored here** (`BrandContextAssembler.java:252-260`, `:411-454`). So there is no stored "verified" row anyone could forge.
2. **When and where** come from `confirmed_at`, `conversation_id` and `stated_by_user_id`. The server fills them from the authenticated request, not from the card's contents.
3. **Label rules, enforced in code, not just in the persona:**
   - Brand-stated values are **never** rendered by `_render_outcome_digest`. The words "market", "real", "platform" and "verified" appear only in the band and outcome lines (`assembler.py:368`, `:378-381`), and a test enforces that (§10).
   - The `calculate_budget` result keeps `suggestedPerCreatorRate` / `suggestedPoolTotal` for band values only (`CalculateBudgetExecutor.java:131-161`). Brand-stated budgets travel in separate `brandStatedBudgetMin/Max` fields with `rateBasis = "brand_stated"`. That way `ToolResultRenderer.tsx` (the calculate_budget branch at `:487`) can't show them as a quoted rate.
   - A brand-stated number is never shown to another workspace (§5, §7).

---

## 4. How it gets written (Q4)

**Recommendation: a structured confirmation card only. Meera proposes; the brand taps Save; Spring validates and writes. Meera gets NO write tool.**

Why not a server-validated `remember_brand_fact` tool: the server can check the *shape*, but not whether the brand *actually said* it. If the model hears "5 to 10k" and records 50k–100k, the shape is valid. It would also need a new scope added to the on-behalf token (`OnBehalfTokenService.java:68-69`), which widens what Meera can do on the brand's behalf. With a card, the brand sees the exact number before anything is saved, and the write runs on the **brand's own session**, not Meera's token.

Mechanism:
1. **AI:** add a new local display tool, `propose_brand_fact`. It follows the `present_options` pattern exactly: Python-native, writes nothing, echoed to the browser (`schemas.py:59-66`, `:387-430`; loop branch `loop.py:349-354`). It goes in `LOCAL_TOOL_NAMES`, **not** in `TOOL_NAMES` / `TOOL_TIERS` / `TOOL_TO_SPRING_PATH` (`schemas.py:38-85`). That keeps it out of the Spring contract diff-check and puts it in no money tier (`is_money_tool` stays false, `schemas.py:473-474`).
2. **Input schema. It is flat, with no anyOf/oneOf/allOf:**
   ```
   type: object
   properties:
     fact_key:       {type: string, enum: [PER_CREATOR_BUDGET, CAMPAIGN_TOTAL_BUDGET, PRODUCT_PRICE, NICHE, TARGET_PLATFORMS, CREATOR_TIER]}
     amount_min:     {type: number, minimum: 0}
     amount_max:     {type: number, minimum: 0}
     currency:       {type: string, enum: [INR]}
     product_name:   {type: string, maxLength: 120}
     tags:           {type: array, maxItems: 3, items: {type: string, maxLength: 40}}
     brand_quote:    {type: string, maxLength: 200}
   required: [fact_key, brand_quote]
   ```
   Which fields each `fact_key` needs is checked in Python (the card is dropped if invalid) and **again in Spring** on save. The schema can't express those rules without combinators.
3. **Frontend:** the card shows a plain sentence, e.g. "Save for next time: you usually pay ₹5,000–₹10,000 per creator for one collaboration". It has editable fields plus **Save** and **Don't save** buttons. Nothing is stored until the brand taps Save.
4. **Spring:** `POST /api/brand/meera-facts`, authenticated with the user's JWT and scoped to their workspace. It re-validates everything: key-specific shape, `amount_min <= amount_max`, sane INR bounds, niche on the category list, conversation belongs to this workspace (the table was added in `V20260907120000__ai_conversations_tenant_type.sql`; column names **[unverified]**). It sets `source=BRAND_STATED` itself, writes a new row, marks the old one superseded, and writes an audit entry. Role gate: budgets need the same role that can edit a campaign budget **[role enum unverified]**.
5. **Persona:** once `rateBasis` is `insufficient_data` and the brand answers, Meera calls `propose_brand_fact` once. She must never say "saved" or "I'll remember" until the brand has tapped Save. This is the same honesty rule as the reel-URL case (`persona.py:244-248`). If the brand dismisses the card, she doesn't propose it again in that conversation.

---

## 5. How it's read back (Q5)

1. `MeeraContextService` loads live facts with a **workspace-scoped** repository call, `findLiveByWorkspaceId(workspaceId)`. It runs in the same "fetch there, shape here" split as everything else (`BrandContextAssembler.java:253-260`).
2. `ContextResponse` gets a new `brand_stated_facts` array. Each item is `{fact_key, subject, value, stated_on, expires_on, stale}`. It is **not** part of `outcome_digest`. Add it to `CONTEXT_PAYLOAD_FIELDS` (`assembler.py:52-68`) so the CI field diff covers it.
3. `build_block_b` (`assembler.py:388-432`) gets a new `_render_brand_stated_facts` with its own heading:
   `- What this brand has TOLD you (brand-stated, not verified, not a market rate):` followed by lines like `usual budget per creator ₹5,000–₹10,000 (you mentioned this on 3 Sep)`. Every string goes through `_safe`. Numbers are cast to int/Decimal server-side, never passed through as raw text.
4. Wording rules in the persona: "you mentioned you usually budget ₹X", never "creators cost ₹X". Meera may **set it beside** a real band ("you mentioned ₹5–10k; settled collaborations in your niche ran ₹8–15k"), but must keep them as two sentences with two sources, never blended.
5. `calculate_budget` behaviour:
   - band exists → unchanged.
   - no band, stated budget exists → `rateBasis="brand_stated"`, rate/pool fields null, `brandStatedBudgetMin/Max` filled in. The rationale says "confirm it still holds".
   - no band, no stated budget → unchanged `insufficient_data`.
6. **Tenant isolation:** Block B already sits inside a per-workspace cache key (`assembler.py:794-806`) and is fetched fresh on every brand turn (`chat.py:616-622`). Stated facts never touch `findRateBandCandidates` (`CollaborationRepository.java:63-75`), which reads `collaborations.agreed_rate` only. §10 includes an architecture test that forbids any reference from rate-band code to `BrandFact`.
7. Also fix F-B while in here: show `price_source` on each catalog line (`scraped` / `estimated`), and show brand-stated prices on a separate line.

---

## 6. Confirmation, correction, expiry (Q6)

**Seeing and editing:** add a "What Meera remembers" panel in brand settings (`BrandSettingsPage`, routed at `src/App.tsx:26`, `:388`). It lists each fact with its value, "you told Meera on <date> in <conversation link>", and an expiry date, plus **Edit** (writes a new version) and **Forget** actions. In chat, if the brand says "that's wrong", Meera proposes a replacement card. Meera never deletes anything herself.

**Forget:** sets `deleted_at` and clears `value_json` to NULL, so the number is gone. The audit log keeps "fact X forgotten by user Y at T" without the value. Nothing else reads the row, so no downstream purge is needed.

**Expiry** (`expires_at` is set by the server when the fact is saved):

| Fact | Expiry | When expired |
|---|---|---|
| PER_CREATOR_BUDGET | 120 days | Shown as `stale: true` → Meera asks "still around ₹X per creator?" before using it. Dropped from Block B after 2× the expiry. |
| CAMPAIGN_TOTAL_BUDGET | 90 days | Same as above |
| PRODUCT_PRICE | 90 days | Same, and also hidden as soon as the analyser finds a scraped price for the same product name |
| NICHE, TARGET_PLATFORMS, CREATOR_TIER | 365 days | Re-confirm, never auto-drop |

Budgets are also re-asked early (without waiting for expiry) when the brand's last campaign actually spent money outside ±50% of the stated range. That spend figure comes from platform-verified released spend (`BrandContextAssembler.java:257-259`). Meera says: "you mentioned ₹5–10k, your last campaign settled at ₹22k — update it?" This comparison is the one place a verified number corrects a stated one, and it still goes through a card.

---

## 7. The flywheel question (Q7), answered honestly

**Can brand-stated budgets improve pricing for other brands? Not safely as numbers. Not in v1, and I don't plan to allow it.**

- A stated budget measures what one brand is **willing to pay**. It is not what creators actually **charge**, which is what the band measures (`CollaborationRepository.java:49-62`: COMPLETED rows only, disputed and cancelled excluded). Mixing the two corrupts the one signal we have.
- It is unverified and easy to game. One brand can post ₹1 or ₹10 lakh. A competitor could plant numbers to pull a niche's "market" down.
- It breaks the promise in §3. The moment brand A's claim reaches brand B, it has been relabelled as market data, which is exactly what Swapnil's rule forbids.
- A k-anonymity floor of 5 (`BrandContextAssembler.java:263`) is too weak for self-reported money in thin Indian micro-niches, where the handful of brands are often identifiable.

**The honest flywheel is indirect, and it already exists.** A remembered budget means less friction, so more campaigns get funded. More funded campaigns mean more `COMPLETED` collaborations with a real `agreed_rate`, which grows `findRateBandCandidates`. More niches then clear the 5/5 floor, and more brands get a real band. The **niche** fact speeds this up the most: a brand that never ran site analysis becomes band-eligible the moment it states its niche. Remembering helps other brands by producing *settled deals*, never by sharing *claims*.

Possible later step, needing Swapnil's sign-off: an **internal-only** ops aggregate (admin dashboard, k≥10 workspaces, bucketed) of stated vs settled budgets, used for sales and pricing strategy. It must never appear in any prompt or any brand-facing screen.

---

## 8. Money safety (Q8)

A remembered number **cannot** become a charged amount:

1. **No path from the model:** `create_campaign` has no budget, per-reel-rate or slot-cap field. The draft schema's properties are listed in `schemas.py:155-299`, and its description forbids them (`schemas.py:151-153`). The money tools are held back from the model (`schemas.py:456-460`) and from the token scope (`OnBehalfTokenService.java:63-69`). `propose_brand_fact` writes nothing (§4).
2. **No path from the server:** `brand_facts` has no foreign key to campaigns, contracts, the wallet or collaborations. An ArchUnit rule stops any class under the campaign / contract / wallet / payment / collaboration services from importing `BrandFactRepository` or `BrandFactResolver` **[exact package names unverified; Vikram to pin them in the rule]**. Only `MeeraContextService`, `CalculateBudgetExecutor` and the facts controller may read it.
3. **No path from the frontend in v1:** the campaign form and the HYPE form do **not** pre-fill `budgetMin/Max`, `perReelRate` or `slotCap` from facts. A v2 "use my usual budget" button that copies the value visibly into the field is acceptable, because that is the brand's explicit action. v1 ships without it.
4. `calculate_budget` stays read-tier and advisory (`schemas.py:70`; audit `advisory:true` at `CalculateBudgetExecutor.java:182-195`). The new `brand_stated` branch leaves every rate/pool field null.
5. Copy: no brand-facing string in this feature uses the word "escrow". Use "payment", "funds" or "budget".

---

## 9. Build estimate

| Area | Work | Estimate |
|---|---|---|
| **Backend (Vikram)** | Migration + `BrandFact` entity/repo; `BrandFactResolver` (priority order, expiry, stale flag); `POST/GET/PATCH/DELETE /api/brand/meera-facts` with per-key validation, niche list, role gate, conversation-ownership check, audit; `ContextResponse.brand_stated_facts`; `calculate_budget` `brand_stated` branch + DTO fields; stated niche wired into band lookup; ArchUnit rules; tests | **3 days** |
| **AI (Ash)** | `propose_brand_fact` local tool + Python card validator; `_render_brand_stated_facts`; F-B catalog provenance label; `CONTEXT_PAYLOAD_FIELDS`; persona rules (propose once, never claim saved, attribute every stated number, re-ask when stale); `prompt_version` bump; tests | **2 days** |
| **Frontend (Ananya)** | Confirm card in `ToolResultRenderer` (editable, Save / Don't save) + `meera-api.ts` client; `brand_stated` handling in the calculate_budget renderer; settings panel "What Meera remembers" (list/edit/forget/expiry); tests | **2.5 days** |
| Review | Kabir (tenancy + provenance), Kavya QA | 1 day |
| **Total** | | **~8.5 dev-days** |

---

## 10. Tests that prove it

**Provenance / never a market rate**
1. `assembler` unit: a context with stated `PER_CREATOR_BUDGET 5000–10000` and **no** band. Block B has no line containing "market", "real", "platform" or "verified" next to 5000 or 10000, and the number sits only under the "brand-stated, not verified" heading.
2. Same test **with** a band (8000–15000): the band line (`assembler.py:378-381`) contains only 8000/15000, and 5000/10000 never appear on it.
3. `CalculateBudgetExecutorTest`: stated budget, no band → `rateBasis="brand_stated"`, `suggestedPerCreatorRate == null`, `suggestedPoolTotal == null`, `rateMin/rateMax == null`.
4. Save-endpoint test: a request body with `source:"SCRAPED"` or `"PLATFORM_VERIFIED"` is ignored or rejected; the stored row is always `BRAND_STATED`. The DB constraint rejects any other value.
5. C1 regression: a stated `PRODUCT_PRICE` equal to the tool's `product_price` still resolves `price_source` to `inferred`/`brand_stated`, **never** `scraped`.
6. Schema test: `propose_brand_fact` input_schema, walked recursively, has no `anyOf`/`oneOf`/`allOf`, and the full `get_tool_schemas()` payload passes the existing tool-schema check.
7. Frontend: `ToolResultRenderer` with a `brand_stated` result renders no "per collaboration rate" / quoted-budget component. It uses the same `isQuotedBudget` guard noted at `ToolResultRenderer.tsx:14-16`.

**Tenant isolation (must use a real DB; mocks can't prove this)**
8. Testcontainers MySQL: workspaces A and B, A saves a budget. B's `/internal/meera/context` and B's `calculate_budget` contain no value from A. The assert must be **non-vacuous**: first check that A's own context DOES contain it.
9. Rate band: seed 5×5 COMPLETED collaborations, then add 50 stated budgets far outside the range. `buildRateBand` min/median/max are unchanged.
10. ArchUnit: no class in the rate-band path (`CollaborationRepository`, `BrandContextAssembler#buildRateBand`) and no money-path service references `BrandFact*`.
11. Controller: user of workspace B calling `PATCH/DELETE` on A's fact id → 404 (not 403, so no existence leak). A `conversation_id` from another workspace → 400.

**Money safety**
12. E2E/integration: after saving a stated budget, create a campaign via Meera. The draft's `budgetMin/budgetMax/perReelRate/slotCap` are all null.
13. Frontend: the campaign form and HYPE form open with empty money fields when facts exist.

**Honesty / lifecycle**
14. Persona eval: brand dismisses the card, and no later assistant turn says "saved", "remembered" or "I'll remember".
15. Resolver: an expired fact → `stale:true` and Block B says "confirm"; past 2× expiry → absent. Forget → `value_json` NULL, absent from context, and the audit row holds no amount.
16. Niche: an off-list or wrong-case niche is rejected on save. An on-list stated niche makes `resolveRateBand` query with that exact key (spy on the repository argument).

Falsify every gate: each test above must be shown to go RED against a deliberately wrong implementation (for example, rendering stated facts inside `_render_outcome_digest`) before it counts as proof.
