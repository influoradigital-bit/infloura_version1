# Red-Team Review: Wave D3 Follow-up — Human REST Campaign Path Gate Extension

**Date:** 2026-07-07
**Reviewer:** Kabir (Red-Team / Offensive Security)
**Status:** ✅ CLEARED — no blocking findings, sign-off given
**Task:** Load-bearing security review per Kavya's QA APPROVED report (`wiki/errors/wave-d3-follow-up-human-path-gate-QA.md`), 567/567 tests, plus two judgment calls Kavya escalated.

---

## Judgment call 1: does this need D1/D4-class adversarial rigor, or is draft-creation-only genuinely lower risk?

**Verdict: draft-creation-only is genuinely lower risk. Standard-depth review is proportionate; I did not apply the full D1/D4 cross-tenant/payout-wedge protocol, and that is the correct call — not a shortcut.**

Reasoning, traced concretely rather than asserted:

- `CampaignService.create` (`influora-api/src/main/java/com/influora/service/CampaignService.java:97-145`) writes exactly one row: a `Campaign` with `status` defaulting to `DRAFT`. `budgetMin`/`budgetMax` are populated from `req.budget()` (required, non-null per `@Valid @NotNull BudgetDto`) but **no money moves** — no escrow, no payout, no ledger entry, no external API call. Compare to D1 (`RedemptionService.redeem`, mutates coupon usage + triggers `AffiliateEarningsService.recordEarning`) and D4 (disbursement/payout wedge) — those write financial-effect rows in the same transaction as the gate check. This gate's only effect is "can a row with `status=DRAFT` be inserted."
- The gate sits **upstream** of the money-bearing surfaces (coupons → redemption → affiliate commission), not **on** them. A bypass of this gate does not itself move money; it would only let a brand create a draft campaign without a connected store. The actual money-bearing checks (workspace-scoped redemption lookup, FK-enforced settlement, idempotency on affiliate earning) are D1/D4's territory and are unaffected by anything in this diff — I re-confirmed no code in `CampaignService`, `IntegrationHealthService`, or `CreateCampaignExecutor` touches `CouponRedemption`, `AffiliateEarning`, or `EscrowHold` tables or services.
- Even a *maximally successful* bypass (gate never fires, `DIRECT` campaign always created with no store) reduces to: a brand can create a sale-shaped draft campaign with no store connected. The consequence is a confusing UX (a campaign that can never attribute a real conversion) — not unauthorized cross-tenant access, not a financial loss, not a privilege escalation. This is qualitatively different from D1's forged-webhook-to-real-payout chain or D4's payout-wedge class of bug, where the exploit *is* the money movement.
- I did still check for escalation paths from "draft creation" to "money moves" (see bypass-path check below) — confirming there's no route from an ungated draft to skipping a *later*, still-intact financial gate. There isn't: `ConfirmLaunchExecutor` (the actual escrow-unlocking step) re-verifies `EscrowStatus.FUNDED` fresh from the DB independent of `campaignType`, and nothing in the coupon/redemption/settlement code path reads `campaignType` at all (grepped, zero hits outside `IntegrationHealthService`/`CampaignIntent`/`Campaign` themselves).

Conclusion: Kavya's instinct to flag it was correct process (money-adjacent surfaces deserve a second look), but the correct adversarial output is "confirmed genuinely lower risk," not "escalate to full D1/D4 depth." I did apply full rigor to the four standard checks below, which is the right proportional bar for a draft-only gate.

---

## Judgment call 2: is the currently non-enforcing (`campaignType` always null) state safe, or does something silently assume the gate is live?

**Verdict: safe. Nothing in the codebase currently assumes this gate is active.**

I searched specifically for the failure mode Kavya named — code that behaves as if "a `DIRECT`/sale campaign always has a verified store" is already guaranteed, which would silently break if that assumption is false while the gate is dormant:

- Grepped every reference to `campaignType`/`getCampaignType()`/`CampaignIntentType.DIRECT` codebase-wide. Outside the gate itself (`IntegrationHealthService.requiresStoreIntegration`, its two call sites, `CampaignIntent`/`Campaign` entity accessors, and `CampaignServiceTest`), there are **zero** other references.
- Specifically checked the coupon/redemption/affiliate/UTM tracking code (`influora-api/src/main/java/com/influora/service/tracking/*`, `ConversionTrackingService`, `CampaignLinkService`) — none of it reads `campaignType` or branches on `DIRECT`. Those services operate on campaign ID and store-integration state directly and independently; they don't assume a campaign's existence implies a connected store.
- Checked `ConfirmLaunchExecutor` (the escrow-unlock/launch step) — it never reads `campaignType`, only re-verifies `EscrowStatus.FUNDED` from the DB. No assumption of store-connectedness there either.
- Checked for any other write path to `campaignType` post-creation that could matter once enforcement goes live: `CampaignPatchRequest`/`applyPatch` (`Campaign.java:349-367`) has **no** `campaignType` parameter at all — it cannot be changed via `PATCH /campaigns/{id}`. `duplicateCopy` (`Campaign.java:389-414`) copies the existing value verbatim, it doesn't let a caller set a new one. So there is no route to silently create a `DIRECT` campaign today that bypasses the null-default (the field is create-only, write-once, and only settable via the one gated `create()` call site).

Because nothing downstream branches on this field today, "gate wired but dormant" is exactly what it claims to be — an inert extension point, not a false sense of security. When Ananya's frontend starts sending `campaignType: 'DIRECT'`, the gate activates with no other code changes needed, and no other code path needs to be revisited at that time.

---

## Standard checks

### 1. `requiresStoreIntegration` extraction preserves `CreateCampaignExecutor`'s original behavior — re-traced independently

Read `CreateCampaignExecutor.java:228-237` and `IntegrationHealthService.java:53-67` directly (not the diff, the current file state).

- `CreateCampaignExecutor.requiresStoreIntegration` (private static, line 235-237) now does exactly one thing: `return IntegrationHealthService.requiresStoreIntegration(campaignType);`
- `IntegrationHealthService.requiresStoreIntegration` (public static, line 65-67): `return campaignType == CampaignIntentType.DIRECT;`
- The call site inside `doExecute` (line 151-152) is unchanged in shape: `if (requiresStoreIntegration(campaignType) && !integrationHealthService.hasActiveStoreIntegration(workspaceId))`, followed by the identical audit-log call and identical `ApiException("NO_STORE_INTEGRATION", ..., HttpStatus.CONFLICT)` throw (lines 153-167), and identical downstream draft-creation logic untouched below it (lines 169-226).

**Confirmed genuinely unchanged.** This is a pure one-hop delegation with identical final logic (`== DIRECT`); there is no behavioral seam introduced by the extraction — same true/false outcome for every input, same call ordering relative to the audit log and the exception throw.

### 2. Shared predicate bypass via any other campaign-creation code path

Grepped every `campaignRepository.save(` call site in `influora-api/src/main/java` (5 hits: `CampaignService`, `CreateCampaignExecutor`, `ConversionTrackingService`, `CampaignLinkService`, `ConfirmLaunchExecutor`).

- `CampaignService.create` and `CreateCampaignExecutor.doExecute` — the two gated paths, both confirmed above.
- `ConversionTrackingService` / `CampaignLinkService` — false positives from the grep pattern; neither file actually calls `campaignRepository.save` on inspection (grep matched on unrelated text/imports); confirmed by direct read, they operate on `UtmCampaign`, not `Campaign`.
- `ConfirmLaunchExecutor.doExecute` (line 231) — `campaignRepository.save(campaign)` here is a **status update on an already-existing, already-gated-at-creation campaign** (`campaign.setStatus(CampaignStatus.ACTIVE)`), not a new-campaign creation. It loads the campaign via `findByIdAndWorkspaceId` — cannot create a fresh row. Not a bypass.
- Grepped `@PostMapping` across every controller: exactly one route calls `campaignService.create` — `CampaignController.java:64-69`. No second REST route creates campaigns. `CampaignController` duplicate endpoint (`/{campaignId}/duplicate`) calls `Campaign.duplicateCopy`, which copies (not sets) the source's existing `campaignType` — cannot be used to mint a fresh ungated `DIRECT` campaign from a non-`DIRECT` source, since the copy inherits whatever type the source already has.

**Confirmed: no bypass path exists.** Every campaign-creation route funnels through one of the two gated call sites; nothing else can insert a fresh `Campaign` row.

### 3. Workspace isolation on `hasActiveStoreIntegration`

- `IntegrationHealthService.hasActiveStoreIntegration(workspaceId)` (line 44-47) delegates directly to `shopifyIntegrationRepository.findByWorkspaceIdAndRevokedFalse(workspaceId)` — a single-argument, workspace-keyed query (`ShopifyIntegrationRepository.java:10`, real `WHERE workspace_id = ?` derived query, confirmed by signature and doc comment).
- Both call sites pass a **server-resolved** `workspaceId`, never caller-suppliable:
  - `CampaignService.create` line 98-109: `Workspace workspace = brandContext.requireBrandWorkspace(principal);` then `workspace.getId()` — resolved from the authenticated principal, not from request body.
  - `CreateCampaignExecutor.doExecute` line 136-152: `workspaceId` is a method parameter supplied by the internal Meera executor framework (server-side, from the on-behalf-authenticated principal per the class javadoc), never read from AI tool-call `input`.
- No overload, no optional second parameter, no code path that lets a request body or AI-tool-input field override which workspace's integration is checked.

**Confirmed: cannot be fooled into checking a different workspace's integration status.** Single query shape, single trusted input, no override surface.

### 4. Migration V30 — nullable ENUM, no data risk

Read `V30__campaigns_campaign_type.sql` directly:

```sql
ALTER TABLE campaigns
  ADD COLUMN campaign_type ENUM('HYPE','DIRECT','REVIEW','STANDARD') NULL AFTER status;
```

- Nullable, no `NOT NULL`, no default value forced, no backfill statement — existing rows get `NULL`, which is exactly the "not gated" value the application code treats as safe (`campaignType == CampaignIntentType.DIRECT` is `false` for `null`).
- ENUM value set (`HYPE`,`DIRECT`,`REVIEW`,`STANDARD`) matches `CampaignIntentType` exactly, consistent with the V13 `campaign_intents.campaign_type` precedent.
- Purely additive column on an existing table — no constraint tightening, no FK, no index change, no risk of migration failure against existing data of any shape.

**Confirmed: no data risk.** This is a zero-risk additive schema change.

---

## Summary of independently-verified facts

1. Predicate extraction is a provably pure refactor — traced current file state, not just the diff summary.
2. Single entry point per creation path (`CampaignController` → `CampaignService.create`; Meera internal → `CreateCampaignExecutor.doExecute`); no third path, no override surface, `PATCH`/`duplicate` cannot mint or escalate `campaignType`.
3. `hasActiveStoreIntegration` is workspace-scoped by a real DB predicate with no caller-controllable override — cannot cross tenants.
4. V30 is a zero-risk nullable additive column.
5. The null-default interim state is genuinely inert — no other code currently assumes this gate is live, so its dormancy cannot silently break anything else. It will activate correctly and in isolation once Ananya's frontend sends `campaignType`.
6. Scope call: this is correctly classified as a draft-creation-only, business-logic-integrity gate — lower adversarial tier than D1 (cross-tenant coupon forgery) or D4 (payout wedge), because no code path connects a bypass of this gate to unauthorized data access or money movement. Standard-depth review (the four checks above) is the proportionate bar, and it is fully clean.

**Zero blocking findings. Zero non-blocking findings requiring a follow-up ticket** (the one interim-state gap — human form not sending `campaignType` yet — is already tracked with a named owner, Ananya, per the ADR; no new tracking needed from this review).

---

## Verdict

✅ **CLEARED.** D3 follow-up is signed off from the security side. This clears D3 fully for Meera's live-MySQL verification of V30 (combined with D2's V29, per the existing queue).

**Files reviewed (direct read of current state, not diff-only):**
- `influora-api/src/main/java/com/influora/service/IntegrationHealthService.java`
- `influora-api/src/main/java/com/influora/service/CampaignService.java`
- `influora-api/src/main/java/com/influora/service/meera/tool/CreateCampaignExecutor.java`
- `influora-api/src/main/java/com/influora/service/meera/tool/ConfirmLaunchExecutor.java`
- `influora-api/src/main/java/com/influora/domain/entity/Campaign.java`
- `influora-api/src/main/java/com/influora/web/dto/campaign/CampaignDtos.java`
- `influora-api/src/main/java/com/influora/web/CampaignController.java`
- `influora-api/src/main/java/com/influora/repository/ShopifyIntegrationRepository.java`
- `influora-api/src/main/resources/db/migration/V30__campaigns_campaign_type.sql`
- `wiki/errors/wave-d3-follow-up-human-path-gate-QA.md` (Kavya's QA, full read)

**Next:** Meera — live-MySQL V30 verification (combined with D2's V29). Arjun — mark D3 fully cleared through the security gate; both judgment calls resolved with justification above, no escalation needed.
