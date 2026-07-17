# Feature: Platform Fees

**Business Purpose** — Influora's take-rate. Three distinct fee concepts: a **creator-side commission** deducted at escrow release (15% default), a **brand-side fee** charged at campaign publish (10% global / 7% Pro), and an **AI campaign-intent fee** (15% percent) added to the AI-proposed budget preview. Fees are the marketplace's core monetization.

**Who uses it** — Applied automatically to money flows; brands/creators can view their rate; admins configure it.

## User Roles
Brand (view brand fee), Creator (view commission), Admin SUPER_ADMIN (configure, MFA).

## Permissions
View → any brand/creator. Edit → SUPER_ADMIN + MFA.

## Business Flow
```
Campaign publish → brand fee = budgetMax * brandFeeBps/10000 (brand wallet → revenue)
Escrow release → commission = gross * defaultFeeBps/10000 (clearing → revenue) → net to creator
Admin edits fee config (optimistic-locked singleton) → new rate applies going forward
```

## Frontend
- **Components**: admin `finance/FeeControlPanel`, brand/creator fee views, `ui/fee-breakdown` (T5).
- **API**: brand/creator platform-fee endpoints; admin `financeApi.feeConfig`.

## Backend
- **Controllers**: `BrandPlatformFeeController`, `CreatorPlatformFeeController`, `PlatformFeeAdminController`.
- **Services**: `PlatformFeeService` (`resolveCreatorFeeBps`, `split`, `deductAtRelease`), `BrandCampaignFeeService` (`resolveBrandFeeBps`, `chargeOnPublish`), `PlatformFeeAdminService`, `AmountDerivationService` (AI percent fee).

## Database
`platform_fee_config` (V41/V42/V44 — **singleton `id='default'`**, `default_fee_bps=1500`, `brand_fee_bps=1000`, min/max, `version`). See [../database.md](../database.md).

## APIs
`GET /brand/platform-fee`, `GET /creator/platform-fee`, `GET/PUT /admin/finance/fee-config` (+`/history`).

## AI
`AmountDerivationService.deriveForCampaignIntent` adds `platformFeePercent` (15.00, config `PLATFORM_FEE_PERCENT`) to `price*count` for the AI budget preview — a **separate** percent, not the bps fee.

## Notifications
None.

## Dependencies
- **Depends on**: billing (Pro override), wallet ledger.
- **Depended on by**: escrow release (commission), campaign publish (brand fee), invoicing (commission invoices).

## Connected Files
`PlatformFeeService`, `BrandCampaignFeeService`, `PlatformFeeAdminService`, `AmountDerivationService`, `domain/entity/PlatformFeeConfig`, fee controllers.

## Execution Flow
```
Brand fee: BrandCampaignFeeService.resolveBrandFeeBps → Pro plan feeBps (700) else global brand_fee_bps (1000)
  → fee = budget*bps/10000 HALF_UP → wallet post brand→revenue (idempotent) + brand-leg commission invoice
  → fail-OPEN to 10% on plan-resolution error (can only overcharge Pro, never undercharge Free)
Commission: PlatformFeeService.deductAtRelease → fee = gross*default_fee_bps/10000 → clearing→revenue + creator-leg invoice
```

## Error Handling
`PLATFORM_FEE_CONFIG_MISSING` (500, no Java fallback), `FEE_CONFIG_CONFLICT` (409 optimistic lock), `INVALID_FEE_VALUE`/`INVALID_FEE_RANGE` (400).

## Security
Config is optimistic-locked; admin edits are SUPER_ADMIN + MFA and version-audited; fees derived server-side.

## Performance
Single-row config read; `@Version` prevents lost updates.

## Testing
Fee split/resolution tests. Regression risks: bps math (HALF_UP), Pro override precedence, fail-open behavior.

## Production Readiness
- **Health**: 8/10 · **Completion**: ~85%
- **Known issues**: brand/creator fee **view** endpoints report a hardcoded 10% / `GLOBAL_DEFAULT` source even for a Pro brand actually charged 7% (display drift); the AI percent fee and the bps fee are unrelated 15% values.
- **Last verified**: 2026-07-15
