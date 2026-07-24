# Live Post-Deploy Verification — Influora (deploy d697d4b)

- **Target:** http://200.141.1.6
- **Date:** 2026-07-23
- **Deploy under test:** d697d4b — creator nav 2→6 + new Co-pilot page, brand nav 6→12 grouped, creator disputes wiring, Meera M-1b create-campaign draft-persist fix
- **Method:** in-app browser MCP (read_page / get_page_text / javascript / console / network). Headless — no screenshots.
- **Accounts:** creator `demo.creator@influora.com`, brand `demo.brand@influora.com`

## Overall result: PASS (with one new bug found)

All 8 verification items PASSED. The M-1b Meera draft-persist fix works (Drafts **0 → 1**). Creating the draft, however, exposed a **new render crash** on `/brand/campaigns`: a draft campaign persisted with no `budget` object, and the campaigns-list `useMemo` reduce dereferences `budget.max` → error boundary "Something went wrong". Not part of the verification scope but it breaks the brand campaigns list once any budget-less draft exists.

## A) CREATOR

| # | Item | Result | Evidence |
|---|------|--------|----------|
| A1 | Sidebar = exactly Home, Deals, Campaigns, Co-pilot, Analytics, Wallet | **PASS** | read_page nav shows exactly those 6 buttons, in order |
| A1a | Home → renders, no console errors | **PASS** | `/creator/dashboard` — "Good afternoon", balance/deals/pending cards, quick links. onlyErrors: none |
| A1b | Deals → renders, no console errors | **PASS** | `/creator/deals?status=new` — deal list (Demo Brand Co / "QA E2E — Diwali Skincare Reels", Negotiating). onlyErrors: none |
| A1c | Campaigns → renders, no console errors | **PASS** | `/creator/campaigns` — "Find campaigns", filters, live campaign card. onlyErrors: none |
| A1d | Co-pilot → renders, no console errors | **PASS** | `/creator/copilot` — see A2. onlyErrors: none |
| A1e | Analytics → renders, no console errors | **PASS** | `/creator/analytics` — metric cards render in empty-state ("No computed score yet for this creator" — expected for demo creator). onlyErrors: none |
| A1f | Wallet → renders, no console errors | **PASS** | `/creator/wallet` — balances, Payouts/History/Invoices/Tax Docs tabs, "No payouts yet". onlyErrors: none |
| A2 | Co-pilot pre-connect preview | **PASS** | Preview-labelled sample idea "Skincare Routine"; disclaimer "Preview — connect Instagram for ideas personalised to your audience." + "Link Instagram to unlock Co-pilot."; **Connect Instagram** CTA present |
| A3 | `/creator/disputes` — partial-data banner GONE | **PASS** | Regex over full body for `partial data|no dispute-list endpoint` → **false**. Page shows proper "Open a dispute" + "Your disputes / No disputes" empty state |

## B) BRAND

| # | Item | Result | Evidence |
|---|------|--------|----------|
| B4 | Sidebar grouped = 12 (MAIN 7 + MANAGE 5) | **PASS** | MAIN: Home, Meera, Campaigns, Creators, Deals, Messages, Wallet. MANAGE: Pipeline, Contracts, Analytics, Reviews, Disputes |
| B4a | Messages → renders | **PASS** | `/brand/messages` — "0 unread", Demo Creator conversation thread ("hi", QA E2E campaign). onlyErrors: none |
| B4b | Pipeline → renders | **PASS** | `/brand/pipeline` — kanban stages Outreach/Negotiating/Contracted/In Progress/Review/Settled; Contracted=1 (Demo Creator). Minor: card shows "Budget ₹null". onlyErrors: none |
| B4c | Contracts → renders | **PASS** | `/brand/contracts` — status filters + "No contracts yet." empty state. onlyErrors: none |
| B4d | Analytics → renders | **PASS** | `/brand/analytics` — creator roster + metric cards + trend section. Inline note "not authorized to view metrics for that creator" (data-scope, page renders fully). onlyErrors: none |
| B4e | Reviews → renders | **PASS** | `/brand/reviews` — "Rate creators" / "Reviews about you" tabs + empty state. onlyErrors: none |
| B4f | Disputes → renders | **PASS** | `/brand/disputes` — "No disputes" tracking empty state. onlyErrors: none |
| B5 | **M-1b Meera draft-persist** | **PASS** | See below |

### B5 — Meera create-campaign draft-persist (CRITICAL)

- **Before:** `/brand/campaigns` header counts — Total 1, Active 1, **Drafts 0**.
- **Action:** On `/brand/meera` sent: *"Create a draft campaign for my organic skincare line, product price 899, brand awareness, targeting beauty creators — create it now."*
- **Meera response:** "Based on an estimated price of ₹899… Creating the draft now… Draft's live in your dashboard." Rendered a **"Campaign created — DRAFT"** card (suggested pool ₹360 / ₹72 per creator / 5 creators).
- **After (API-confirmed):** `GET /api/v1/campaigns?limit=100` → total **2**, byStatus `{DRAFT: 1, ACTIVE: 1}`. New draft: **"Draft: organic skincare line"**, id `01KY7004XP6M02049JVVZTYY4K`, status `DRAFT`.
- **Result:** Drafts **0 → 1**. `create_campaign` persisted a real draft — no 409. **M-1b fix confirmed working live.**

> Note: the UI Drafts counter could not be re-read from `/brand/campaigns` because that page crashed (see below); the count was verified directly against the campaigns API with the brand token.

## New bug found (out of scope, flagged)

**`/brand/campaigns` list crashes once a budget-less draft exists.**
- Symptom: error boundary "Something went wrong / An unexpected error occurred."
- Console: `TypeError: Cannot read properties of undefined (reading 'max')` in a `useMemo` → `Array.reduce` (`assets/index-DlGNzmIM.js`).
- Root cause: the Meera-created draft persists with **no `budget` object** (API returns the ACTIVE campaign with `budget:{min,max,currency}` but the new DRAFT has none). The campaigns-list budget-range reduce dereferences `budget.max` unconditionally → crash. Consistent with the "₹NaN" (Meera card) and "₹null" (Pipeline card) seen for the same draft.
- Impact: brand cannot open the campaigns list page while any budget-less draft exists. Pipeline page still renders (shows "₹null").

---

# Alignment + M-1c re-verify (deploy be9d93f)

- **Target:** http://200.141.1.6 — bundle `assets/index-C8wgBCJp.js` (new; prior deploy was `index-DlGNzmIM.js`), confirming a fresh deploy is live.
- **Date:** 2026-07-23 (later run, neha)
- **Deploy under test:** be9d93f — adds creator MANAGE nav group, wires brand Help to internal page, and claims to fix M-1c (`/brand/campaigns` crash on budget-less drafts).
- **Method:** in-app browser MCP (read_page / get_page_text / javascript / console / network). Headless — no screenshots. Logged in properly (POST `/api/v1/auth/brand/login` → 200; POST `/api/v1/auth/creator/login` → 200) before checking authed pages.

## Overall result: PARTIAL PASS — M-1c only partly fixed

3 of 4 checks pass cleanly (pipeline, brand Help, creator MANAGE alignment). **M-1c is a partial fix:** the `/brand/campaigns` list no longer crashes, but the budget-less draft does **not** render "No budget set" — it is dropped from the list entirely — and its **detail page still crashes** with the same underlying error.

| # | Item | Result | Evidence |
|---|------|--------|----------|
| 1 | **M-1c — `/brand/campaigns` renders (no crash)** | **PASS** | List route renders. Header: Total Campaigns **2**, Active **1**, Drafts **1**, Total Budget **₹25K**. No "Something went wrong" overlay. |
| 1b | **M-1c — budget-less draft shows "No budget set" in list** | **FAIL** | Draft `Draft: organic skincare line` (id `01KY7004XP6M02049JVVZTYY4K`, API confirms status DRAFT with **no `budget` field**) does **not** render as a card. DOM scan: title `organic skincare` absent; only card link is the ACTIVE campaign + `/new`. Under **Drafts** tab the list is **empty** (Drafts count 1 / ₹0K, zero cards). No "₹NaN" shown, but no "No budget set" either — the card is silently omitted. |
| 1c | **M-1c — draft detail page** | **FAIL** | `/brand/campaigns/01KY7004XP6M02049JVVZTYY4K` → error boundary "Something went wrong". Console: `TypeError: Cannot read properties of undefined (reading 'min')` → `[ErrorBoundary] Uncaught render error`. Same budget-deref bug as before, now on `.min` in the detail path (list `.max` reduce was patched, detail path was not). |
| 2 | **Brand `/brand/pipeline` Contracted card** | **PASS** | Contracted=1 (Demo Creator / "QA E2E — Diwali Skincare Reels") shows **Budget: "No budget set"** — graceful placeholder, **not "₹null"** (regression from prior run fixed). Page renders fully. |
| 3 | **Brand Help → internal `/brand/help`** | **PASS** | `/brand/help` renders internally ("How Influora works" reference page: Campaigns / Deal Rooms / Contracts / Payments & Escrow / Meera sections). Source confirms wiring: `brand-layout.tsx` "Help & Support" dropdown item (desktop L425 + mobile L286) → `handleNavigate('/brand/help')` (internal), route registered in `App.tsx` L309. *(Dropdown could not be opened in headless mode — portal/animation did not mount; verified via direct nav + source.)* **Note:** help page body is still placeholder copy ("TODO: final copy from Nisha"). **Note:** creator Help still opens external `https://help.influora.com` (`creator-layout.tsx` L255) — brand-only fix. |
| 4 | **Creator MANAGE nav group** | **PASS** | Sidebar (JS nav scan): **MAIN** = Home, Deals, Campaigns, Co-pilot, Analytics, Wallet. **MANAGE** = Reviews, Disputes, Coupons, Affiliate. All 4 MANAGE routes render real content: Reviews (`/creator/reviews` — "Rate brands" / "Reviews about you" empty state), Disputes (`/creator/disputes` — funded-escrow dispute flow + empty states), Coupons (`/creator/coupons` — "My Coupons" empty state), Affiliate (`/creator/affiliate` — Sales/Revenue/Commission/Unsettled stat tiles + empty state). No crash overlays. |

### Console-error caveat (important for reading this run)
The browser MCP console buffer is **sticky across navigations** — verified by loading the static `/about` page, which still showed the `reading 'min'` error pair. So `read_console_messages onlyErrors` cannot isolate per-page errors after the detail-page crash polluted the buffer. No **new/distinct** error message appeared on any MANAGE page (buffer only ever contained the single budget-deref pair), and every MANAGE page rendered real content, so Test 4 is a clean PASS on content. The only error signature present anywhere is the pre-existing budget-deref crash from item 1c.

### M-1c bottom line
The reported "list page crash" is fixed (the list renders). But the fix does **not** make the budget-less draft display "No budget set" — the draft card is omitted from the list — and the same `budget` null-deref (`.min`) **still crashes the campaign detail page**. The root cause (Meera persists drafts with no `budget` object) is unaddressed; the frontend guard was applied to the list reduce only, not to the card/detail render paths. Recommend: guard `budget?.min/max` everywhere a campaign is rendered (card, detail, pipeline already handled) and render "No budget set" for drafts.

---

# M-1c round 2 re-verify (fbcbd97)

- **Target:** http://200.141.1.6 — bundle `assets/index-aWdLiXV-.js` (new; round-1 was `index-C8wgBCJp.js`), confirming a fresh deploy is live.
- **Date:** 2026-07-23 (round 2, neha)
- **Deploy under test:** fbcbd97 — M-1c round-2 fix: "sparse budget-less Meera drafts must render everywhere."
- **Method:** in-app browser MCP (read_page / get_page_text / javascript / console / network). Headless — no screenshots. Logged in properly as brand (POST `/api/v1/auth/brand/login` → 200; `GET /api/v1/campaigns?limit=100` → 200 with valid fresh token) before checking authed pages. Draft under test: **"Draft: organic skincare line"**, id `01KY7004XP6M02049JVVZTYY4K`, status `DRAFT`, API confirms **no `budget` field**.

## Overall result: PARTIAL PASS — detail page fixed, list card still dropped

The **detail page crash is now FIXED** (the headline round-2 claim). But the budget-less draft **still does not render as a card in the campaigns list** — it is silently omitted, exactly as in round 1. So "render everywhere" is only half-true: detail = yes, list card = no.

| # | Item | Result | Evidence |
|---|------|--------|----------|
| 1 | **Draft card renders in list (All + Drafts) as "No budget set"** | **FAIL** | List route renders with no crash. Header: Total Campaigns **2**, Active **1**, Drafts **1**, Total Budget **₹25K** (the app *knows* Drafts=1). But the only campaign card in the list is the ACTIVE "QA E2E — Diwali Skincare Reels". DOM scan of the **All** tab: `organic skincare` **absent**, no "No budget set", no "No deadline", no ₹NaN — the draft card is **silently dropped**. Card links present: only `/brand/campaigns/new` and the ACTIVE campaign. (Drafts tab could not be toggled in headless — Radix `<Tabs>` did not switch on synthetic/coordinate/keyboard input — but **All is the superset** and the draft is missing there, so it is missing from Drafts too.) |
| 2 | **Draft detail page opens without error boundary** | **PASS** | `/brand/campaigns/01KY7004XP6M02049JVVZTYY4K` **opens cleanly** — no "Something went wrong" boundary. Renders real content: title "Draft: organic skincare line", **"Budget Used — No budget set / set in campaign wizard"**, **"Budget Breakdown — No budget set yet — add one from the campaign wizard."**, "Collaborators 0 of 0", "Pending Bids 0", "Days Left 0", empty bids list. No NaN, no ₹null. API: `GET /api/v1/campaigns/{id}` → 200 and `/analytics` → 200. **The round-1 `.min` null-deref crash on the detail path is fixed.** |
| 3 | **No new console errors specific to these pages** | **PASS (by render)** | Both pages rendered their real content. `read_console_messages onlyErrors` still shows the round-1 `TypeError: Cannot read properties of undefined (reading 'min')` / `[ErrorBoundary]` pair — but every frame in that stack references the **OLD bundle `index-C8wgBCJp.js`**, not the current `index-aWdLiXV-.js`, proving they are **stale sticky-buffer artifacts from the prior deploy**, not produced by fbcbd97. No new/distinct error attributable to the current bundle appeared. |

### M-1c round-2 bottom line
fbcbd97 **fixes the detail-page crash** (item 2 PASS — "No budget set" placeholders render, no boundary) but **does not fix the list card** (item 1 FAIL — the budget-less draft is still omitted from `/brand/campaigns`, not shown as a "No budget set" card). The list no longer crashes and shows no ₹NaN, but the draft is invisible there. Recommend: in the campaigns-list card map, stop filtering/short-circuiting campaigns with no `budget` and render the same "No budget set / No deadline / 0 creators" placeholder the detail page now uses.

---

# Creator contract visibility (add-on check)

- **Method:** logged in as creator (POST `/api/v1/auth/creator/login` → 200). Deals → "Open chat" on the Demo Brand Co / "QA E2E — Diwali Skincare Reels" deal → Deal Room at `/creator/chat?deal=01KY52585HY09G9CJWP930SJX8` → **Contract** tab. (The Deal Room drawer only mounted after widening the viewport to 1600×900; at 1280 the "Open chat" onclick fired but the panel did not render — same headless portal/animation limitation noted earlier for the brand Help dropdown.) **Nothing was signed or submitted.**

### What the creator sees
1. **Contract tab renders a full "ready-to-sign" contract card** — heading **"Your turn to sign — Demo Brand Co has signed. Review the PDF, then sign to start deliverables."** Fields: **Contract ID `CTR-2024-01KY52585HY09G9CJWP930SJX8`**, Brand "Demo Brand Co", Campaign "QA E2E — Diwali Skincare Reels", **"You receive (est.) ₹0"**. Deal-room stepper shows stage **2 Contract** (after Negotiate, before Fund escrow / Deliver / Pay); deal header badge reads **"Contracted"**.
2. **Yes, there is a sign path:** a "**Type your full legal name to sign**" text field, a **"Sign contract"** button, and a **"Download PDF"** button. State presented = **brand signed, creator pending** ("Your turn to sign").
3. **Visibility gap — the contract is UI-fabricated, not a real backend record.** The Deal Room's own contract fetch **`GET /api/v1/deals/01KY52585HY09G9CJWP930SJX8/contract` → 404 Not Found** (fired twice), and **`GET /api/v1/contracts` → 200 with an empty array**. The deal object (both creator and brand views) returns **`contractId: null`, `contractStatus: null`**, status `TERMS_AGREED`, `escrowFunded: false`, `dealValue: null`. The displayed "Contract ID" is literally `CTR-2024-` + the deal id — a client-derived placeholder. **Brand side is identical:** brand `GET /api/v1/contracts` → empty, brand `GET /api/v1/deals/{id}/contract` → 404, deal `contractId: null`. So **no contract exists on either side** — the brand never created or signed one. The creator UI's "Demo Brand Co has signed / your turn to sign" is **false / mock state**, driven off `deal.status = TERMS_AGREED` while the real contract endpoint 404s. If the creator typed a name and clicked "Sign contract", there is no backend contract to sign against.
4. **Console/network:** `GET /api/v1/deals/{id}/contract → 404 Not Found` is the relevant call; the UI swallows the 404 and renders fabricated contract data anyway. `GET /api/v1/contracts → 200` (empty). No JS console crash on the Contract tab.

### Creator contract bottom line
The creator **appears** to see a brand contract ready to sign, but it is **not real** — no contract exists in the backend for this deal (404 on the contract endpoint, empty `/contracts`, `contractId: null` on both sides). The Deal Room fabricates a "brand has signed — your turn" state and a `CTR-2024-<dealId>` id from the `TERMS_AGREED` deal status. This is a data-integrity/visibility bug: the UI presents a signed-by-brand contract that was never created. Recommend gating the Contract tab's "ready to sign" state on a real `contractId`/`contractStatus` (or a non-404 contract fetch) instead of deriving it from deal status.
