# Kabir Red-Team Review — Brand P1-#5 "Deal Room (60% → live)"

**Scope:** `src/pages/brand-chat.tsx` (`handleSendProposal`, contract-tab wiring, shipment warning),
`src/components/brand/deal-room/proposal-form.tsx`, `deal-contract-tab.tsx`, `deal-payments-tab.tsx`,
`shipment-form.tsx`; backend `POST /deals` (`DealController.java:61-67`,
`DealService.createProposal` `DealService.java:113-163`, `DealDtos.CreateDealRequest`
`DealDtos.java:42-50`), `ContractController.java` get/sign/pdf-download-url, `ContractService.java`
`get`/`recordSignature`/`getPdfDownloadUrl`/`requireContract`.

## 1. `handleSendProposal` → `api.deals.create(...)` — server-side re-validation

- `DealController.create()` (`DealController.java:61-67`) is `@Valid @RequestBody CreateDealRequest`.
  `CreateDealRequest` (`DealDtos.java:42-50`) enforces `@NotBlank campaignId/creatorId`,
  `@NotNull @DecimalMin("0.01") amount` — a non-positive/missing amount 400s before the service runs.
- `DealService.createProposal()` (`DealService.java:114-163`):
  - `brandContext.requireBrandWorkspace(principal)` derives the workspace from the JWT, never a
    client param.
  - `requireWorkspaceCampaign(workspace.getId(), body.campaignId())` (line 116, impl 376-391) looks
    up the campaign and throws `CAMPAIGN_NOT_FOUND` 404 if `campaign.getWorkspaceId() !=
    workspace.getId()` — a brand cannot open a deal room against a campaign it doesn't own. Matches
    the `frontend-passed campaignId is opaque, server re-derives ownership` pattern already seen in
    the counter-offer cycle.
  - `validateProposalAmount(campaign, body.amount())` (line 126, impl 393-409) re-checks `> 0` and
    bounds the amount to `[campaign.budgetMin, campaign.budgetMax]` when set — the client-submitted
    `data.budget` is not blindly trusted. Same helper already audited for `counter()`.
  - `creatorProfileRepository.findById(body.creatorId())` 404s `CREATOR_NOT_FOUND` for a bogus
    creator id — no lookup by unvalidated foreign key.
  - `collaborationRepository.existsByCampaignIdAndCreatorId(...)` + a `DataIntegrityViolationException`
    catch around the save both guard against duplicate collaborations for the same
    campaign/creator pair (race-safe via DB unique constraint, not just an app-level check).
  - `body.message()` passes through `persistProposalMessage()` → `TextSanitizer.sanitizePlainText`
    (same sanitizer used elsewhere) before persistence.
- **Verdict on item 1: PASS.** No IDOR against another brand's campaign, amount is server-bounded to
  the campaign's budget range, no unsanitized text persisted.

## 2. Contract-tab wiring — `contractId` sourcing and new call-site exposure

- `selectedDeal.contractId` (`BrandDealRoom.contractId`, `brand-chat.tsx:152`) is populated only via
  `mapDealToBrandDealRoom(deal)` (`brand-chat.tsx:182-206`) from the `Deal` object returned by
  `api.deals.list('brand','all')` (`brand-chat.tsx:630`). That list endpoint
  (`DealController.list` → `DealService.list`) is scoped server-side to
  `collaborationRepository.findByWorkspaceId(workspace.getId())` (`DealService.java:360-361`) — a
  brand can never receive a `contractId` belonging to another brand's collaboration in the first
  place. The frontend never lets the user type/edit a `contractId` directly.
- Even setting aside trustworthy sourcing, the new call site (`fetchContractDetail` →
  `api.contracts.get('brand', contractId)`, `handleSignContract` → `api.contracts.sign(...)`,
  `handleDownloadContractPdf` → `api.contracts.pdfDownloadUrl(...)`) re-hits backend endpoints that
  all resolve to `ContractService.requireContract(contractId, workspaceId)` →
  `contractRepository.findByIdAndWorkspaceId(...)` (`ContractService.java:590-593`, confirmed by
  direct read) for `get` (line 496-498), `recordSignature` (line 250-252), and
  `getPdfDownloadUrl` (line 551-553). A manipulated/guessed `contractId` for a different workspace
  404s regardless of what the client sends — this is the identical scoping already verified in the
  earlier Contracts-page audit, and this new call site doesn't bypass it (no new client-supplied
  `workspaceId` param anywhere; `workspace.getId()` always comes from
  `brandContext.requireBrandWorkspace(principal)`, i.e. the JWT).
- **Verdict on item 2: PASS.** No new exposure — `contractId` is both trustworthily sourced on the
  frontend and independently re-scoped server-side on every use.

## 3. `deal-payments-tab.tsx` — read-only, no cross-workspace leak, no new mutation surface

- `DealPaymentsTab` (`deal-payments-tab.tsx:41-181`) takes `dealValue`, `contractStatus`,
  `deliverablesDone/Total`, and `liveMilestones` as props — no `fetch`/`api.*` calls anywhere in the
  component. It is purely a presentational mapper: `liveMilestones` (sourced by the parent from
  `contractDetail.milestones`, itself scoped by the same `requireContract(contractId, workspaceId)`
  path audited in item 2) is mapped through `mapMilestoneRecordStatus()` to a display-only status
  string; `mapMilestoneRecordStatus` performs no filtering/redaction decisions that could leak data —
  it's a 1:1 enum remap.
  - Confirmed by grep: no `onClick` handler in this file triggers any state-changing call. The only
    interactive element is `<Button asChild><Link to="/brand/wallet">...` — pure client-side
    navigation, not a mutation.
- **Verdict on item 3: PASS.** Read-only as intended; no cross-workspace data path (data arrives
  pre-scoped from the parent's already-audited contract fetch) and no mutation surface added.

## 4. `shipment-form.tsx` / "Not saved to the backend" warning — honesty check

- The warning (`brand-chat.tsx:1742-1755`) is gated on `isApiLive() && shipment` and unconditionally
  states "There's no shipment/shipping-address endpoint yet — this card is local to your browser only
  and the creator won't see it until that API exists." This is accurate — confirmed no
  `POST /deals/:id/shipment` (or equivalent) route exists anywhere in `influora-api`
  (only `DealMessageKind.shipment`, an unrelated chat-message tag, and unused
  `ShipmentCreatedEvent`/`ShipmentReceivedEvent` notification types nothing publishes).
  `handleSubmitShipment` (`brand-chat.tsx:725-732`) only ever mutates local React state
  (`setShipment`) and logs `[local-only, no backend endpoint]` to console — no disguised network call.
- No data leak: the shipping address rendered above the shipment card
  (`shippingAddress` state, `brand-chat.tsx:701-710`) is hardcoded demo data in this component
  (Priya Sharma's address), not fetched from any API — nothing cross-tenant is displayed regardless
  of mode.
- The one soft UX gap: the warning banner is shown once, at shipment-creation time; the persistent
  `ShipmentCard` rendered below it (`brand-chat.tsx:1756-1772`) has an "Update tracking" button
  (`onUpdateTracking`) that also only flips local state — clicking it advances the status pill
  (created → in_transit → delivered) with no accompanying re-statement that this, too, is
  unpersisted. A brand who navigates away and back will see the shipment card gone (state is
  component-local, not persisted), which is the correct failure mode for something that was never
  saved — no false sense of persistence is created beyond the single session. Not a security finding,
  worth a UX note for whoever eventually builds the real endpoint (the update-tracking action should
  probably repeat the "local only" caption too, low priority, cosmetic).
- **Verdict on item 4: PASS.** No leak, no disguised persistence; one low-priority cosmetic
  suggestion (repeat the "local only" notice near the tracking-update control), not a finding.

## Overall Verdict: **PASS**

0 Critical / 0 High / 0 Medium / 0 Low security findings. One non-security, cosmetic UX suggestion
(item 4) for whoever picks up the shipment-endpoint backlog item later. Cross-checked against the
prior Deals-counter (`wiki/errors/brand-p1-2-deals-counter-kabir-redteam.md`) and Contracts-cycle
audits — this cycle's new wiring reuses the same server-side scoping primitives
(`requireWorkspaceCampaign`, `requireContract`/`findByIdAndWorkspaceId`,
`brandContext.requireBrandWorkspace`) rather than introducing a parallel, unaudited path. Safe to
ship.
