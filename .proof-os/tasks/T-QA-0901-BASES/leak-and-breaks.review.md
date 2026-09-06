# Priya (CTO) — fresh-context review: F-0674 / F-0670 / F-0669

Task: T-QA-0901-BASES · Date: 2026-09-05 · Branch: `feat/meera-creator-phase-e` (HEAD `eac5e58`)
Reviewer stance: nothing believed until run. Every verdict below is backed by an executed command,
and each of the three fixes was independently revert-probed by me in this session.

---

## 1. Verdicts

| Finding | Verdict | Anchor |
|---|---|---|
| **F-0674** — public portfolio leaks hidden/anonymised brand names | **FIXED** | `influora-api/src/main/java/com/influora/service/portfolio/PortfolioService.java:682` and `:690` |
| **F-0670** — Accept offered-and-broken on a rate-less invite | **FIXED** (at the cited surface only) | `src/pages/creator-deals.tsx:589`, `:690`, `:719` |
| **F-0669** — brand contract tab fabricates four legal terms | **FIXED** (at the cited surface only) | `src/components/brand/deal-room/deal-contract-tab.tsx:152-161` |

Three blocking issues found that are **not** in any of the three fixes. See §5.

---

## 2. Exit codes — all four required gates

| Command | Exit | Result |
|---|---|---|
| `npx tsc --noEmit` | **0** | clean, no output |
| `npm test` (`vitest run`) | **0** | 185 files / 1080 tests passed |
| `npm run test:live` | **0** | 6 files / 28 tests passed |
| `mvn -o clean -Dtest='PortfolioServiceCollabDisplayModeTest,PortfolioServicePublicVisibilityTest,PortfolioServiceRateCardTest,PortfolioServiceTest' test` | **0** | 34 tests, 0 failures — `BUILD SUCCESS` |

Maven per-class breakdown: CollabDisplayMode 5, PublicVisibility 4, RateCard 5, PortfolioServiceTest 20.

---

## 3. F-0674 — PRIORITY, the live privacy leak — **FIXED**

### What the fix does
`buildCollabs` now takes the persisted `collabDisplayModes` map (`PortfolioService.java:610`) and
enforces it server-side, inside the loop:

- `PortfolioService.java:682` — `if (publicView && "hidden".equals(displayMode)) { continue; }`
  → the row is **dropped from the payload entirely**, not returned carrying a flag the client is
  trusted to honour. This is the correct shape; the defect class was precisely "flag the client
  filters".
- `PortfolioService.java:690` — `boolean anonymize = publicView && "category".equals(displayMode);`
  then `:700` substitutes `anonymizedBrandLabel(workspace)` (`:723`, industry-derived, e.g.
  "Beauty Brand") and **nulls `brandId` and `brandLogoUrl` alongside the name**. Withholding all
  three together is right — anonymising only the name string while returning the workspace id and
  the real logo URL would have been a false fix.

`getMine` passes `publicView=false` (`:235-239`) so both branches are inert there.

### Verified from the RESPONSE, not from comments

I did not accept the shipped test's scope. It asserts absence only within
`page.collabs().toString()`. I wrote my own independent probe
(`PriyaWholePayloadLeakProbeTest`, since deleted) that calls `service.getPublic(USERNAME)` and
serialises the **entire** `PortfolioPageResponse` through Jackson — every field, as the wire bytes —
then asserts:

- `REAL_BRAND_NAME_HIDDEN` ("SecretStartup Confidential") absent from the whole JSON
- `REAL_BRAND_NAME_CATEGORY` ("Sugar Cosmetics") absent from the whole JSON
- both suppressed workspace ids absent from the whole JSON
- **control:** `REAL_BRAND_NAME_VISIBLE` ("Nykaa Fashion") IS present — so the absence assertions
  cannot pass on an empty or blank payload

Result: **passed (exit 0)**. This closes the possibility of a leak through `stats`, `badges`,
`pinnedPosts`, `customLinks` or any field the shipped test's scoped assertion cannot see.

### Revert-probe (mine)

I removed the `continue` at `:682` and forced `anonymize = false` at `:690`, then re-ran:

- `PortfolioServicePublicVisibilityTest` → **Tests run: 4, Failures: 2** (`BUILD FAILURE`), failing
  exactly on `getPublic_hiddenCollab_isAbsentFromResponse` and
  `getPublic_categoryCollab_realBrandNameReplacedByAnonymisedLabel`.
- My own whole-payload probe → **Failures: 1** (`BUILD FAILURE`).

Both bite. Source file restored byte-for-byte (diff stat back to `302 ++++/----`, zero probe
markers remaining).

### getMine is not over-fixed — confirmed
`PortfolioServicePublicVisibilityTest.getMine_stillReturnsRealNamesForEveryCollab` asserts the
creator's own view returns **all 3** collabs with `REAL_BRAND_NAME_HIDDEN` and
`REAL_BRAND_NAME_CATEGORY` intact, plus their `displayMode` values so the editor can render the
control. Green. Creators are not blinded to their own data. **Not OVER-IMPLEMENTED.**

### Public attack surface is fully covered
`SecurityConfig.java:168-170` opens exactly two anonymous portfolio routes:
`GET /portfolio/*` and `POST /portfolio/*/contact`. `PortfolioController:41` routes the former
straight to `getPublic`. The only other public creator route, `GET /public/creators/*/verified`,
is served by `PublicCreatorService`, which touches collaborations only for a `.size()` count
(`PublicCreatorService.java:93`) and emits no brand name. No sibling anonymous leak.

### Observation (not a defect, wants a ruling)
`computeStats(completed, ...)` at `:612` runs on the **unfiltered** list, so a hidden collab still
contributes to the public `completedCampaigns` / `repeatBrands` counts. No identifying string is
emitted (my whole-payload probe proves that), so this is not the F-0674 leak. But a creator who
hides a collab may reasonably expect it not to inflate their public counts either. Flagging for a
product ruling, not blocking.

---

## 4. F-0670 — Accept on a rate-less invite — **FIXED at `creator-deals.tsx`**

### Is Accept genuinely no longer offered-and-broken?
Yes, at this surface. `src/pages/creator-deals.tsx:589` defines
`const hasAgreedRate = deal.budget > 0;`, consumed at `:690` as
`disabled={actionLoading || !hasAgreedRate}`.

**I verified the load-bearing chain rather than trusting the comment** (both claims it makes are
true, and I opened each definition):

- `DealService.java:2136` passes `collaboration.getAgreedRate()` verbatim into the `dealValue` slot
  of `DealResponse` (`DealDtos.java:53`, `BigDecimal dealValue`). Not a proxy, not a campaign budget.
- `creator-deal-mappers.ts:212` — `budget: parseDealAmount(deal.dealValue)`.
- `parseDealAmount` (`creator-deal-mappers.ts:92-96`) returns `0` for both `null` (`Number(null)===0`)
  and `undefined` (`NaN` → not finite → `0`). So a null `agreedRate` reliably yields `budget === 0`.

**Divergence check (the over-restriction risk):** the server guard is
`requireAgreedRateForCommitment` (`DealService.java:1822-1830`) and tests `agreedRate == null`
**only** — so a rate of `0` would pass the server but be blocked by `budget > 0`. I checked whether
`0` is reachable: `agreedRate` has exactly one mutator, `Collaboration.updateAgreedRate`
(`Collaboration.java:268`), called only from `DealService.java:279` (propose) and `:1305` (counter),
both fed by request DTOs carrying `@NotNull @DecimalMin("0.01")` (`DealDtos.java:102`, `:126`).
`Collaboration.invite`/`apply` set it null. So `budget > 0` ⟺ `agreedRate != null` in practice.
**Not OVER-IMPLEMENTED.**

### Is the replacement reachable and explained?
Yes — this is not a silently hidden control:
- `:719` renders a visible sentence in the same CTA row: *"No rate proposed yet — use Counter to
  propose one before accepting."*
- Counter is **not** disabled and is promoted from `variant="outline"` to `variant="default"`
  (`:696`), so the action the creator *can* take becomes the primary button.
- `:388` adds `AGREED_RATE_REQUIRED`-specific toast copy naming the actual fix, for the residual
  race where a rate is cleared between load and click.

`creator-deals.test.tsx:111-133` asserts all four properties: Accept disabled, the explanation text
present, a click on the disabled button dispatching **no** `api.deals.accept` call, and
`Counter` enabled.

### Revert-probe (mine)
Restored `disabled={actionLoading}`. `creator-deals.test.tsx` → **1 failed**
(`expect(element).toBeDisabled()`). Restored; re-ran → **14 passed**.

---

## 5. F-0669 — fabricated legal terms — **FIXED at `deal-contract-tab.tsx`**

### The four literals — I grepped the file myself
`grep -n "14 \* 24\|6 months on social\|As per campaign brief\|revisionCap\|usageRights\|exclusivity\|deadline" src/components/brand/deal-room/deal-contract-tab.tsx`
returns **only two hits, both inside prose comments** (`:122`, `:153`). Zero live occurrences. The
four keys are simply no longer passed (`:152-161`), and `ContractData` marks all four optional
(`contract-generator.ts:28-31`), with `generateContractHTML` rendering "Not specified"
(`:148`, `:150`, `:152`, `:154`). tsc exit 0 confirms the optionality is real.

`deal-contract-tab-honest-terms.test.tsx` drives the real component, the real Download-PDF click,
and inspects the **actual generated HTML** — with two positive controls (`Not specified` present for
all four clauses; `CTR-777` / `Priya Sharma` / `Summer Launch` / `₹50,000` present) that stop the
`not.toContain` assertions from passing vacuously on an empty `capturedHtml`.

### Revert-probe (mine)
Restored all four literals. → **5 of 6 tests failed**, including the positive "Not specified"
control, with the deadline test failing on the correctly-formatted `19/9/2026`. The sixth
(known-fields control) correctly stayed green. Restored; re-ran → **14 passed**.

---

## 6. BLOCKING issues found outside the three fixes

### B-1 (ship-blocker) — both new regression tests are UNTRACKED in git

```
?? influora-api/src/test/java/com/influora/service/portfolio/PortfolioServicePublicVisibilityTest.java
?? src/components/brand/deal-room/__tests__/deal-contract-tab-honest-terms.test.tsx
```

`git ls-files --error-unmatch` fails on both. These are the **only** tests proving F-0674 and
F-0669 are closed, and neither has ever been `git add`-ed. The two sibling portfolio tests
(`PortfolioServiceCollabDisplayModeTest`, `PortfolioServiceRateCardTest`) *are* staged as `A`, so
this is an omission, not a policy.

Every gate in §2 passed **because the files exist on my disk**. Commit this branch as-is and the
privacy-leak regression test ships absent — the exact F-0324 pattern. `git add` both before commit.

### B-2 (real, live) — F-0669 fixed at 1 of 3 `downloadContractPDF` callers

The F-0669 ledger entry names its own miss: *"a shared check that no caller of
`downloadContractPDF` supplies a term the server did not send."* The fix touched one caller. Two
others still fabricate **all four** terms and are both live-reachable:

- `src/components/brand/timeline/panels/contract-panel.tsx:65-68` — `deadline: meta?.deadline || '2024-02-15'`,
  `usageRights: '6 months on social media platforms'`, `exclusivity: 'No exclusivity agreement'`,
  `revisionCap: 2`. Also fabricates `brandName: 'Influora Brand'` and `creatorName: 'Priya Sharma'`.
  Reachable: rendered by `src/components/brand/timeline/event-cards/contract-card.tsx:122`.
- `src/components/creator/deal-room/creator-contract-panel.tsx:83-86` — same four literals, plus the
  same values hardcoded into the on-screen panel at `:266` and `:270`. Reachable: imported by
  `src/pages/creator-chat.tsx:70`.

F-0666 closed `creator-deal-contract-tab.tsx`; `creator-contract-panel.tsx` is a *different* file
and was never in scope for either finding. Net effect: the two-parties-disagree defect F-0669
describes still exists, just between different panels. **Recommend opening a new finding and
promoting a shared gate over every `downloadContractPDF` call site**, which is what the original
`missed_by` asked for.

### B-3 (real, live) — F-0670's dead control survives on a second surface

`src/pages/creator-chat.tsx:2295-2310` renders an Accept button whose only gate is
`disabled={isAcceptingProposal || isDecliningProposal}`. Its container gate is
`showBareInviteResponse` (`:1969-1972`), which requires
`selectedDeal.collaborationStatus === 'INVITED'` — i.e. it renders on *exactly* the population that
`requireAgreedRateForCommitment` 409s. This is worse than the surface just fixed: it is not
"usually" broken, it is **always** broken.

The error path degrades acceptably but not correctly: `describeProposalActionError`
(`creator-chat.tsx:621-655`) has cases for `DEAL_NOT_ACCEPTABLE`, `DEAL_NOT_REJECTABLE` and
`CANNOT_ACCEPT_OWN_OFFER` but **no** `AGREED_RATE_REQUIRED` case, so it falls to
`default: { message: err.message, stale: true }` — the server's copy is reasonable, but
`stale: true` tells the creator to refresh, which will never help.

The F-0670 symptom line "grep AGREED_RATE_REQUIRED src/ returns zero hits" is now satisfied at
one of at least two call sites.

Lower-confidence, same family: `src/pages/brand-campaign-detail.tsx:753-758` accepts a creator's
bid with no rate gate and a generic `Could not accept bid` toast; an APPLIED collaboration carries
no `agreedRate`. Worth a look, not asserted.

Latent, not live: `handleHypeAccept` (`creator-deals.tsx:354-367`) is a second ungated
`api.deals.accept` in the reviewed file, with copy ("The slot may already be filled") that would be
actively wrong on a 409. It is currently unreachable — `hypeInvites` is
`React.useMemo<HypeInvite[]>(() => [], [])` at `:240` — so it is a trap for whoever wires that API,
not a present break.

---

## 7. Bottom line

All three fixes are real, correctly shaped, and defended by tests that I proved bite. F-0674 in
particular is enforced server-side in the right place and does not blind creators to their own data.

**Do not commit yet.** B-1 must be fixed first (`git add` both test files) or the two most important
gates ship absent. B-2 and B-3 are new findings of the same classes, on live surfaces, and should be
opened in the ledger rather than folded silently into these three.

### Working tree left clean
Every probe was reverted from backups; `PriyaWholePayloadLeakProbeTest.java` deleted. Post-review
diff stats match pre-review exactly: `PortfolioService.java` 302, `deal-contract-tab.tsx` 13,
`creator-deals.tsx` 48.
