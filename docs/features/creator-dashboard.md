# Feature: Creator Dashboard

**Business Purpose** — The creator's home base: browse and apply to campaigns, manage deals, submit deliverables, connect Instagram, view analytics, edit the public portfolio, manage coupons/affiliate earnings, and withdraw money. It is the creator-side counterpart to the brand dashboard.

**Who uses it** — Authenticated creators.

## User Roles
Creator. Guarded by `CreatorProtectedRoute` (localStorage `creator_token`). Pages self-wrap `CreatorLayout`.

## Permissions
A creator acts only on their own resources (identity from JWT; `CreatorContextService.requireCreatorProfile`). No workspace roles.

## Business Flow
```
Creator login → onboarding (socials → profile → tour) → dashboard/deals
  → browse campaigns → apply → negotiate deal → sign contract → submit deliverables
  → report metrics / mark posted → get paid (escrow release) → withdraw
```

## Frontend
- **Layout**: `components/creator/creator-layout.tsx`.
- **Routes** (guarded): `/creator/onboarding`, `/deals`, `/wallet`, `/profile`, `/settings`, `/chat`, `/portfolio`, `/analytics`, `/campaigns` (+/:id), `/disputes`, `/reviews`, `/coupons`, `/affiliate`; unguarded `/creator/settings/meta/callback`.
- **Pages**: `creator-deals` (unified hub replacing inbox/active), `creator-campaigns`, `creator-portfolio-editor`, `creator-analytics`, `creator-wallet`, `creator-coupons`, `creator-affiliate-earnings`, etc.
- **Hooks**: `creator/*` (coupons, affiliate-earnings, service-invoices, tax-identity), `analytics/*`.

## Backend
`Creator*Controller` family: `CreatorCampaignController`, `CreatorDeliverableController`, `CreatorAnalyticsController`, `CreatorReviewController`, `CreatorDisputeController`, `CreatorCouponController`, `CreatorAffiliateEarningController`, `CreatorInvoicingController`, `CreatorPlatformFeeController`, `MeCreatorProfileController`, `PortfolioController`, `WalletController`, `MetaOAuthController`.

## Database
`creator_profiles`, `collaborations`, `deliverables`, `wallets`, `meta_oauth_tokens`, `reviews`, `coupon_codes`, `affiliate_earnings`, etc.

## APIs
Creator-scoped endpoints across [../api.md](../api.md) (deals, deliverables, analytics, wallet, coupons, affiliate, reviews, disputes, portfolio, meta OAuth).

## AI
Not directly (Meera is brand-side). Creator content is scored by AI brand-safety indirectly.

## Notifications
Creator receives `creator.proposal_received`, `creator.campaign_live` (escrow funded), payout events, contract-signed, etc.

## Dependencies
- **Depends on**: auth, campaigns/deals, wallet/escrow, Meta integration (analytics/portfolio), reviews.
- **Depended on by**: nothing (top-level shell).

## Connected Files
`components/creator/creator-layout.tsx`, `pages/creator-*`, the `Creator*Controller` classes, `CreatorContextService`.

## Execution Flow
```
Route render → CreatorProtectedRoute → page (self-wraps CreatorLayout) → hook → api → Creator*Controller → service (requireCreatorProfile) → DB
```

## Error Handling
`WRONG_USER_TYPE` for non-creators; several surfaces mock-backed; `NOT_IMPLEMENTED` on content-performance and coupons banners.

## Security
Self-scoped access only; Meta OAuth callback route deliberately unguarded so Meta's redirect isn't bounced.

## Performance
Lazy 3D on portfolio; pagination on lists.

## Testing
Multiple creator page tests exist. Regression risks: self-scoping, deal/deliverable state.

## Production Readiness
- **Health**: 7/10 · **Completion**: ~78%
- **Known issues**: mock surfaces (dashboard/chat/inbox/wallet); affiliate earnings placeholder; coupons "not implemented" banner in places.
- **Last verified**: 2026-07-15
