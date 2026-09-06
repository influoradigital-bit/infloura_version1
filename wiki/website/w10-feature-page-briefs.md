# W10 — feature page briefs (Aditya)

Scope note: `deal-room`, `secure-payments`, and `hype` already exist (`src/pages/features/{deal-room,secure-payments,hype}.tsx`, all three registered in `scripts/marketing-routes.mjs:28-30`). This brief covers the **three new pages Swapnil ordered, in his stated priority order**: Meera, Contracts, Affiliate/Sales Tracking. Seven capabilities are unpaged in total; these three are next.

Every claim below cites the file:line I opened to verify it. Where I could not verify something, it says `UNVERIFIED` rather than asserting it.

Shared constraints (apply to all three pages):
- **F-0342/F-0343** (`src/components/site/proof-points.ts:1-38`, enforced by `.proof-os/gates/F-SEO-marketing-surface.sh`): no stat-shaped numeric literal (`value: <3+ digits>`) and no unsourced "N+ brands/creators/customers" count anywhere under `src/pages/features/*.tsx` (the gate globs that path directly, `F-SEO-marketing-surface.sh:214`). None of the three briefs below propose one. If a page wants a "scoreboard" illustration (the homepage's `TRACKING.scoreboard`, `src/pages/landing.tsx:162-167`, does this with string values like `'4,812'` precisely to dodge the gate's digit-after-colon pattern while showing what the UI looks like), it must stay visibly an example dashboard, not a company-wide traction claim.
- **Vocabulary**: "escrow" is retired from all customer-facing copy (session memory: `project_escrow_word_banned_in_user_copy`; enforced in-repo by the same gate's `TERM` check, `F-SEO-marketing-surface.sh:44`). Use "Secure Payments" / "secured funds" / "protected funds," matching `src/pages/features/secure-payments.tsx` throughout.
- **Schema pattern already established** by the three live feature pages: `getWebPageSchema` + `getQaPageSchema` + `getBreadcrumbListSchema` (all defined `src/lib/seo/schema.ts:492`, `:450`, `:229`) called directly in the page, plus `<FaqSection>` (`src/components/site/FaqSection.tsx`) which emits `FAQPage` JSON-LD itself (`FaqSection.tsx:56`, `getFaqPageSchema` at `schema.ts:207`) from the same array it renders — do not hand-call `getFaqPageSchema` separately, that's the exact duplication `FaqSection`'s own header comment (`FaqSection.tsx:11-25`) says it exists to prevent. `getArticleSchema` (`schema.ts:160`) is **not** for these pages — its own doc comment says "used on each `/blog/:slug` post" (`schema.ts:159`); it's wired at `blog/post.tsx:34` (per Priya's note this session), not appropriate for a product feature page.

---

## 1. Meera (first priority)

**Proposed URL:** `/features/meera` (new; no planning doc precedent — `wiki/website/content-map.md` predates the Meera feature entirely, confirmed by `grep -n "meera" wiki/website/content-map.md` returning zero hits).

### Keyword cluster (India-specific)
Brand marketers researching whether an AI tool can cut campaign-planning time, not a generic "AI chatbot" query — "Meera" alone is a common Indian name and will pull noise, so every non-branded keyword needs a category qualifier.

| Keyword | Intent |
|---|---|
| AI campaign planner for influencer marketing India | Commercial — brand evaluating the category |
| AI tool to find and budget influencers India | Commercial |
| automate influencer campaign brief | Informational → commercial |
| Influora Meera AI (branded) | Navigational — already-aware users |
| AI creator matching influencer marketing | Informational, secondary/LSI |

### H1 and standalone opening sentence
Reuse the exact Q&A already published at `public/llms.txt:29-30` verbatim as the page's canonical/speakable paragraph — this is the acceptance criterion for the llms.txt repoint below, not just a style choice.

- **H1:** "Meet Meera, the AI co-pilot in every brand workspace"
- **Opening sentence (must match `llms.txt:29-30` word for word):** "Meera is Influora's built-in AI campaign co-pilot, included in every brand workspace. She suggests matching creators, works out a budget and per-reel rate from a stated goal, and drafts the campaign. She proposes; a human confirms every step that moves money."

### Schema
`getWebPageSchema` + `getQaPageSchema` (question = `"Who is Meera?"`, answer = the sentence above) + `getBreadcrumbListSchema` + `<FaqSection>`. No `getArticleSchema`.

### What the page must carry so `llms.txt` can be repointed at it
`public/llms.txt:29-30` currently cites `https://influora.in/` for "Who is Meera?" — per Priya's brief this round, that URL is not a reliable citation target until its own work ships. The Meera page becomes the correct repoint target only once:
1. Its `CANONICAL_QUESTION`/`CANONICAL_ANSWER` constants are byte-for-byte identical to `llms.txt:29-30` (same discipline `secure-payments.tsx:95-100` already applies to its own canonical Q&A — the code comment there, `secure-payments.tsx:91-94`, states exactly why: an answer engine that quotes the schema and a reader who reads the page must get the identical sentence).
2. It is added to `INDEXABLE_ROUTES` in `scripts/marketing-routes.mjs` (currently absent) so it's both prerendered and in the sitemap.
That's a two-line change for Ishaan (page copy) + Vikram (routes file) once the page ships; flag to Priya/Arjun when it's ready so the `llms.txt:30` URL gets swapped in the same change.

### Claims the page may make, traced to code
1. *"Every brand workspace comes with Meera built in"* — `src/pages/landing.tsx:139` (already-approved homepage copy). Corroborating: no brand-side Meera flag exists — `influora-api/src/main/resources/application.yml:194-199`'s `meera:` block has exactly one flag, `creator-enabled`, and its own comment (`:195-198`) scopes it explicitly to "every CREATOR-audience Meera surface." I found no equivalent brand-side gate in that file. **UNVERIFIED**: whether this is confirmed live in production today — that's a deploy-status question, not a code-content one; per session memory (`project_meera_campaign_creation_arc`) it was reported DONE+live 2026-07-24, but I did not re-check that live myself this session.
2. *"Suggests matching creators by niche, city, and engagement"* — `landing.tsx:141`.
3. *"Works out a budget and per-reel rate from your goal"* — `landing.tsx:142`.
4. *"Drafts the campaign so you launch in minutes, not days"* — `landing.tsx:143`.
5. *"Proposes the funding step — you confirm every money step"* — `landing.tsx:144`. The code comment directly above this block (`landing.tsx:135-136`) is explicit: *"claims verified against influora-ai + MeeraSessionService: she suggests, drafts, and proposes; a human confirms every money step. Never claim autonomy."* Do not soften this on the new page — no copy implying Meera can send money, sign a contract, or launch a campaign unattended.

### What the page must NOT claim
- **Creator-side Meera.** `application.yml:199`: `creator-enabled: ${MEERA_CREATOR_ENABLED:true}` — code-enabled by default. But per Priya's brief this round, Phase A ships on a commit not yet pushed/deployed and is gated on a live smoke test. The page's audience is brands anyway (matches claim 1's scope), so the simplest safe move is: **don't describe a creator-facing Meera experience on this page at all** until Phase A is confirmed live. If a future revision wants a creator-facing section, it needs a fresh live-status check first, not an inference from this flag alone.
- **Creator sourcing via Meta/Creator Connect.** `application.yml:412`: `creator-marketplace.enabled: ${META_CREATOR_MARKETPLACE_ENABLED:false}` — off by default, and its comment (`:406-408`) says the Instagram scope it needs "cannot even be submitted for App Review yet." This is a different feature from Meera (Meera matches among creators already reachable on Influora; Creator Connect is about *sourcing new* creators from Meta) but they're adjacent enough in a brand's mind that the page should not imply Meera can pull in creators from Instagram/Meta at large.

---

## 2. Contracts (second priority)

**Proposed URL:** `/features/contracts` — matches the pre-existing plan at `wiki/website/content-map.md:329` (`### 2.4 Contracts & Compliance (/features/contracts)`), which already reserved this URL and flagged the keyword slot `[Aditya to supply]` (`content-map.md:333`).

### Keyword cluster (India-specific)
`wiki/website/keywords.md:74` already carries **"influencer contract template India"** as a MEDIUM-volume, ⭐⭐⭐⭐⭐-priority target with a lead-magnet content note — I'm keeping it as primary since it's already vetted, but the rest of that doc predates the escrow→Secure Payments rename (`keywords.md:113` still says "escrow" as a differentiator) so I'm not otherwise trusting its framing.

| Keyword | Intent |
|---|---|
| influencer contract template India | Informational/lead-magnet — wants a downloadable format, pre-purchase research |
| influencer agreement format India | Informational, secondary |
| e-sign influencer contract | Commercial — already evaluating a platform |
| auto-generated contract influencer deal | Commercial, matches actual product mechanic |

### H1 and standalone opening sentence
- **H1:** "Every Influora deal signs a real contract"
- **Opening sentence:** "Influora generates a contract from the deal terms you and the creator agreed to in the Deal Room, and both sides e-sign it separately — the signed record stays attached to that deal, bound to the amount and milestones you actually agreed on."

### Schema
`getWebPageSchema` + `getQaPageSchema` (question ≈ "Does Influora generate a real contract for influencer deals?") + `getBreadcrumbListSchema` + `<FaqSection>`. No `getArticleSchema` (blog-only, see shared constraints).

### Claims the page may make, traced to code
1. **A real, persisted Contract exists per collaboration**, not a UI mock. `influora-api/src/main/java/com/influora/domain/entity/Contract.java:22-96` — fields include `collaborationId`, `workspaceId`, `totalAmount`, `currency`, `status` (`ContractStatus`), `pdfR2Key`. Session memory (`project_contract_flow_fabricated`) records this used to be UI-mock and was fixed 2026-07-23 (commit `50725a9`); the entity/service/event files I opened this session independently confirm the real version exists today.
2. **Two-party e-signature, tracked separately.** `Contract.java:68-84`: `brandSignedAt`/`brandSignerName` and `creatorSignedAt`/`creatorSignerName` are distinct columns — confirms brand and creator each sign independently, not a single "agreed" flag. Corroborated by `ContractPendingSignatureEvent.java` and `ContractSignedEvent.java` (distinct lifecycle events for the two states).
3. **Contract total is bound to the negotiated deal, not arbitrary.** `influora-api/src/main/java/com/influora/service/ContractService.java:270-284`: the contract total may not exceed the collaboration's `agreedRate` when one exists (a named security fix, "[SEC: Kabir MEDIUM-2]" in that comment) — safe to describe as "the contract can't be set above what you actually agreed."
4. **PDF generated and stored per contract.** `Contract.java:44-45` (`pdfR2Key`) + `ContractPdfService.java` (file exists, confirms a PDF-generation service is wired, not just a field).
5. **Milestone-based payment structure.** `PaymentMilestone.java` exists as a distinct entity (confirmed via `find`), and `ContractService.java:263-269` sums milestone amounts into the contract total.
6. **A tamper-evidence mechanism exists.** `Contract.java:47-52`: the `termsJson` column is explicitly documented as *"a SHA-256 tamper hash of the generate request,"* not structured terms — safe to describe generically as "the signed terms are tamper-evident," not safe to describe as "your usage rights and exclusivity clauses are stored as structured data" (see below).

### What the page must NOT claim — corrects a stale planning doc
`wiki/website/content-map.md:341-351` (the existing plan for this page) claims "Auto-Generated Contracts... What's Covered: ... Usage rights ... Exclusivity ... Revision policy" and `content-map.md:359` claims **"TDS auto-deducted (Section 194H/194J for Indian creators)."** I traced both and neither holds up as written:
- **Usage rights / exclusivity / revision limits are not structured, guaranteed fields.** The actual terms storage is `termsText` (`Contract.java:53-64`), documented in its own comment as *"the free-text contract terms as supplied by the caller at generation time... optional... when not supplied, must read back as honestly absent rather than a fabricated value"* (`Contract.java:56-58`, tagged `[F-0283, contract-terms-never-persisted]`). Nothing forces every contract to carry usage-rights/exclusivity/revision-limit language — it's whatever free text was supplied, and can be absent. **Do not claim the platform auto-populates those specific clauses.** Safe alternative framing: "add your own terms — usage rights, exclusivity windows, revision limits — and once both sides sign, they're locked into a tamper-evident record," which is accurate to what's actually optional/caller-supplied.
- **TDS auto-deduction is UNVERIFIED and likely overclaimed.** `influora-api/src/main/java/com/influora/domain/entity/Payout.java:80-85`: `tdsAmount` is described in its own comment as the amount "deducted at source... `null` means no TDS was applied," and `influora-api/src/main/java/com/influora/service/admin/AdminFinanceService.java:179-183` only *validates* a supplied `tdsAmount` is between zero and the payout — "TDS is optional (null = not applied)." I found no service computing a TDS percentage automatically (no dedicated TDS-calculation class turned up under `influora-api/src/main/java/com/influora/service`, though I did not exhaustively check every file). **Do not put "TDS auto-deducted per Section 194H/194J" on this page.** This is also flagged in `wiki/website/seo-rulings-w10.md` as a pre-existing published claim (`llms.txt:39`) that needs Priya/Vikram/Tejas to confirm before it's repeated anywhere else.
- **No sample contract exists yet to link.** `content-map.md:363` proposes a "See sample contract" CTA — I found no redacted/sample PDF asset in the repo. That needs to be produced (Zara/Vikram) before the CTA can ship; don't build the page around a link that doesn't exist yet.

---

## 3. Affiliate / sales tracking (third priority)

**Proposed URL:** `/features/sales-tracking` (matches the vocabulary already used consistently in `public/llms.txt:40` — "Sales tracking" — and in the homepage section, `landing.tsx:159-167` — "TRACKING"). No planning-doc precedent exists (`content-map.md` predates this feature).

### Keyword cluster (India-specific)
Real intent here is a brand asking "how do I know a creator's post actually sold anything," not generic "affiliate marketing" (a much broader, lower-intent term dominated by non-influencer affiliate networks).

| Keyword | Intent |
|---|---|
| influencer sales attribution India | Commercial — brand wants to justify creator spend with real numbers |
| track influencer coupon code sales | Informational → commercial |
| Shopify influencer tracking link India | Commercial, matches the actual integration (`ShopifyIntegrationRepository.java`) |
| influencer campaign ROI tracking | Informational, secondary |

### H1 and standalone opening sentence
Reuse the homepage's already-verified framing (`landing.tsx:157-161`, whose own comment says *"Tracking claims verified against CampaignTrackingController + conversion webhooks: measurement starts at the click — never claim impressions/funnel"*):

- **H1:** "Know exactly what every creator's post sold"
- **Opening sentence:** "Each creator on a campaign gets a unique tracking link and coupon code, and your store reports orders back over a signed webhook — so the numbers are real transactions, not screenshots or estimates."

### Schema
`getWebPageSchema` + `getQaPageSchema` (question ≈ "How does Influora track influencer sales?") + `getBreadcrumbListSchema` + `<FaqSection>`. No `getArticleSchema`.

### Claims the page may make, traced to code
1. **Unique per-creator tracking link + coupon code.** Backing services confirmed present: `influora-api/src/main/java/com/influora/service/tracking/CampaignLinkService.java`, `CouponCodeService.java`, and the `CouponCode` entity (`domain/entity/CouponCode.java`). Public controller: `influora-api/src/main/java/com/influora/web/CampaignTrackingController.java`.
2. **Store reports orders back over a signed webhook.** `influora-api/src/main/java/com/influora/web/ConversionWebhookController.java:33-38` — its own class comment: *"PUBLIC (unauthenticated) REST surface over the Phase 4 tracking services... Every endpoint here is called by a party that is NOT a logged-in Influora brand: a brand's own commerce backend... posting a webhook."* Signature verification is a named, separate class: `com.influora.integration.tracking.webhook.ConversionWebhookSignatureVerifier` (imported at `ConversionWebhookController.java:11`) — confirms "signed webhook" is accurate, not marketing gloss.
3. **Shopify integration exists as a distinct, real repository/entity**, not just the generic webhook path — `ShopifyIntegrationRepository.java` confirmed present. Safe to name Shopify specifically as a supported storefront in copy/keywords.
4. **Measurement starts at the click, not impressions.** Matches the homepage comment's own constraint (`landing.tsx:158`) — do not claim impression- or reach-based measurement anywhere on this page; only link clicks, conversions, coupon redemptions, and attributed revenue (the same four metrics the homepage scoreboard shows, `landing.tsx:163-166`) are backed by a controller.
5. **Per-creator breakdown ("which creator actually sold") exists as a concept** — `landing.tsx:168` ("topCreators" section) — but I did not open the controller/DTO backing that specific breakdown this round; if the new page wants to promise a live per-creator leaderboard UI, that specific claim is **UNVERIFIED** by me and should get a one-line confirmation from Vikram before it ships as a page claim (versus the four aggregate metrics above, which I traced to a controller directly).

### What the page must NOT claim
- **Do not conflate this with Creator Connect / the Meta creator marketplace.** `application.yml:412` (`META_CREATOR_MARKETPLACE_ENABLED`, default `false`) gates a *different* feature — sourcing new creators from Meta/Instagram — not sales tracking, which works on creators already in a deal. The two features share no code path I found, but both involve "affiliate"-adjacent vocabulary and could get merged in a reader's head; keep this page's copy scoped strictly to tracking sales on deals that already exist, and do not imply it can be used to discover or recruit creators.
- **No company-wide sales/revenue totals.** Any "brands have driven ₹X in tracked sales" style stat tile would need a real endpoint per F-0342's rule (`proof-points.ts:34-37`: *"If real, measured traction numbers are wanted in this slot later, they must arrive from an endpoint — not from a literal typed into this file"*) — this page's glob (`src/pages/features/*.tsx`) is directly covered by the gate (`F-SEO-marketing-surface.sh:214`), so a hardcoded stat here fails the build, not just my review.
