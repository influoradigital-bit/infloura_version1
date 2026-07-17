# Feature: Campaigns

**Business Purpose** — A campaign is a brand's paid-collaboration brief that creators apply to or are invited into. It is the central object of the marketplace: budgets, deliverable expectations, timelines, and the commercial terms live here. Going live triggers the platform's brand publish fee.

**Who uses it** — Brands (create/manage), creators (browse/apply), admins (ops oversight), and Meera (drafts campaigns via tool calls).

## User Roles
Brand (create/edit/delete/duplicate), Creator (browse/apply), Admin (list/monitor), AI (draft only).

## Permissions
- Create/edit → brand workspace members; delete → OWNER only (DRAFT only); duplicate → OWNER/ADMIN/MANAGER.
- Setting a campaign ACTIVE requires the workspace to be **VERIFIED**.
- Save-as-template → Pro plan (`@RequiresPlan(CAMPAIGN_TEMPLATES)`).

## Business Flow
```
Brand → New Campaign (or Hype) → fill brief (budget/timeline/platforms) → save DRAFT
  → publish (DRAFT→ACTIVE): workspace VERIFIED check + brand publish fee charged (7% Pro / 10% global on budgetMax)
  → creators apply / brand invites → collaborations → deals → contracts → deliverables
```
Campaign types (`CampaignIntentType`): **HYPE** (awareness), **DIRECT** (conversion — requires a connected store), **REVIEW**, **STANDARD**.

## Frontend
- **Pages**: `brand-campaigns`, `brand-new-campaign`, `brand-new-hype-campaign`, `brand-edit-campaign`, `brand-campaign-detail`, `brand-campaign-tracking`; creator `creator-campaigns`, `creator-campaign-detail`.
- **Components**: `brand/campaigns/{campaign-form,campaigns-list,campaign-state-machine}`, `brand/hype-campaign-card`, `creator/{CreatorCampaignCard,CreatorBrowseCampaignCard}`, admin `CampaignTable`.

## Backend
- **Controllers**: `CampaignController` (`/campaigns`), `CreatorCampaignController` (`/creator/campaigns`), `AdminCampaignController`, `CampaignTemplateController`, `CampaignTrackingController`.
- **Services**: `CampaignService`, `CampaignValidator`, `CreatorCampaignService`, `CampaignTemplateService`, `BrandCampaignFeeService` (publish fee), `IntegrationHealthService` (DIRECT store gate).

## Database
`campaigns` (V4, +V30 type, +V50 commission_rate), `campaign_intents` (V13), `campaign_templates` (V20260714150000, 4 seeded SYSTEM presets), `collaborations` (V6). See [../database.md](../database.md).

## APIs
`GET/POST /campaigns`, `GET/PATCH/DELETE /campaigns/{id}`, `POST /campaigns/{id}/duplicate`, `GET /campaigns/{id}/analytics`, `GET/POST/DELETE /campaign-templates`, creator browse/apply, admin list. See [../api.md](../api.md).

## AI
Meera can **draft** a campaign (`create_campaign`, D-tier) — creates a DRAFT campaign + `CampaignIntent` with **budget null** (money not AI-writable), and later **launch** it (`confirm_launch`, C-tier) after DB-verified funded escrow. See [meera-ai.md](meera-ai.md).

## Notifications
`campaign.created` (in-app), and downstream `brand.new_application`, `creator.campaign_live`.

## Dependencies
- **Depends on**: workspaces (tenant + verification), wallet (publish fee), store integrations (DIRECT gate), plan (templates).
- **Depended on by**: collaborations/deals, contracts, deliverables, analytics, tracking, affiliate.

## Connected Files
`CampaignController`, `CreatorCampaignController`, `CampaignService`, `CampaignValidator`, `BrandCampaignFeeService`, `domain/entity/Campaign`, `CampaignIntent`, `CampaignTemplate`, `Collaboration`; frontend campaign pages/components.

## Execution Flow
```
Publish: PATCH /campaigns/{id} status=ACTIVE → CampaignService.update (findByIdForUpdate lock)
  → workspace VERIFIED check → BrandCampaignFeeService.chargeOnPublish (wallet debit → revenue, idempotent)
  → mint brand-leg commission invoice → status ACTIVE → save (same transaction)
```

## Error Handling
`WORKSPACE_NOT_VERIFIED` (403), `CAMPAIGN_NOT_EDITABLE`/`NOT_DELETABLE` (409), `NO_STORE_INTEGRATION` (409, DIRECT), `INSUFFICIENT_WALLET_BALANCE_FOR_PUBLISH` (402), `CAMPAIGN_NOT_OPEN`/`APPLICATION_DEADLINE_PASSED`/`ALREADY_APPLIED` (409, creator apply), `SYSTEM_TEMPLATE_IMMUTABLE` (400).

## Security
Multi-tenant scoping (`findByIdAndWorkspaceId`); private/invite-only campaigns hidden from non-invited creators (404); creator DTOs omit brand-contact/internal metrics.

## Performance
Pessimistic lock only during the publish-fee transaction; browse uses JPA specs + budget-overlap; niche/platform filters are in-memory post-filters (page-scoped totals when active).

## Testing
Backend tests cover create/publish/apply and the fee charge. Regression risks: publish-fee idempotency, VERIFIED gate, DIRECT store gate.

## Production Readiness
- **Health**: 8/10 · **Completion**: ~85%
- **Known issues**: human create-form doesn't yet send `campaign_type` (null = ungated); niche filter is in-memory.
- **Last verified**: 2026-07-15
