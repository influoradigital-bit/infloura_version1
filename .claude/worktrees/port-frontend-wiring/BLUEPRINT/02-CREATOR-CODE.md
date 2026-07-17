# Creator Code Blueprint

> Everything a **creator** touches — frontend screens, backend controllers, entities. Sourced from code.
> Leads: Ananya (frontend), Vikram (backend).

A creator signs up, connects Instagram (Meta), builds a portfolio, applies/bids on campaigns, negotiates deals, signs contracts, submits deliverables, earns from escrow + affiliate coupons, and withdraws.

---

## 1. Frontend (React SPA) — `/src`

### Routes (`src/App.tsx`)
```
/creator/login            /creator/register         /creator/onboarding
/creator/dashboard        /creator/deals            /creator/campaigns
/creator/campaigns/:id    /creator/wallet           /creator/profile
/creator/portfolio        /creator/analytics        /creator/coupons
/creator/chat             /creator/disputes         /creator/reviews
/creator/settings         /creator/settings/meta/callback
/creator/inbox → /creator/deals?status=new (redirect)
/creator/active → /creator/deals?status=in_progress (redirect)
/:handle → public portfolio (CreatorPortfolioPublicPage)
```

### Key page files (`src/pages/`)
`creator-dashboard.tsx`, `creator-deals.tsx`, `creator-campaigns.tsx`, `creator-campaign-detail.tsx`, `creator-wallet.tsx`, `creator-profile.tsx`, `creator-portfolio-editor.tsx`, `creator-portfolio-public.tsx`, `creator-analytics.tsx`, `creator-coupons.tsx`, `creator-affiliate-earnings.tsx`, `creator-chat.tsx`, `creator-inbox.tsx`, `creator-active.tsx`, `creator-disputes.tsx`, `creator-reviews.tsx`, `creator-settings.tsx`, `creator-onboarding.tsx`, `creator-meta-callback.tsx`.

### Components
`src/components/creator/` — **17 components**. Portfolio + analytics widgets shared with `analytics/`.

### API access (`src/lib/api.ts` creator groups)
`auth`, `onboarding`, `creatorProfile`, `creatorCampaigns`, `creatorDeliverables`, `creatorAnalytics`, `contentPerformance`, `wallet`, `portfolio`, `creatorReviews`, `creatorDisputes`, `creatorCoupons`, `affiliateEarnings`, `metaOAuth`, `notifications`.

---

## 2. Backend (Spring) — creator-facing controllers

| Controller | Base path | Key actions |
|---|---|---|
| `AuthController` | `/auth` | `/creator/send-email-otp`, `/creator/verify-email`, `/creator/register`, `/creator/login` |
| `OnboardingController` | `/onboarding` | `/creator/socials`, `/creator/profile`, `/creator/kyc`, `/creator/payout`, `/creator/complete` |
| `MetaOAuthController` | `/meta/oauth` | `/authorize`, `/callback` (Instagram connect) |
| `MeCreatorProfileController` | `/me/creator-profile` | profile read/update |
| `PortfolioController` | `/portfolio/*`, `/me/portfolio` | public portfolio, `/sync`, `/cover`, `/analytics` |
| `CreatorCampaignController` | `/creator/campaigns` | list, `/{id}`, **`/{id}/apply`** (this is the bid) |
| `DealController` | `/deals` | `/{id}/accept`, `/{id}/reject`, `/{id}/counter`, messages |
| `CreatorDeliverableController` | `/creator/deliverables` | `/{id}/status`, `/{id}/submit`, `/{id}/metrics`, `/{id}/mark-posted` |
| `CreatorAnalyticsController` | `/creator/analytics/me` | `/metrics`, `/scores`, `/demographics`, `/media` |
| `WalletController` | `/wallet` | `/balance`, `/withdraw`, `/transactions` |
| `CreatorCouponController` | `/creator/coupons` | affiliate coupon codes |
| `CreatorAffiliateEarningController` | `/creator/affiliate-earnings` | earnings from coupon/UTM conversions |
| `CreatorReviewController` | `/creator/reviews` | list, `/received`, `/{id}/flag` |
| `CreatorDisputeController` | `/creator/disputes` | open/track disputes |
| `CreatorPlatformFeeController` | `/creator/platform-fee` | fee applied to payouts |

### Entities the creator owns/uses
`CreatorProfile`, `User`, `MetaOAuthToken`, `CreatorMetric`, `CreatorScore`, `AudienceDemographics`, `MediaMetric`, `Collaboration`, `DealMessage`, `Deliverable`, `DeliverableMetric`, `CouponCode`, `CouponRedemption`, `AffiliateEarning`, `AffiliateSettlementBatch`, `CreatorBankAccount`, `Payout`, `Wallet`, `WalletTransaction`, `Review`, `Dispute`, `FeaturedCreator`.

---

## 3. Creator lifecycle (code path)

```
register/login (AuthController)
  → onboarding: socials → profile → KYC → payout bank (OnboardingController)
  → connect Instagram (MetaOAuthController) → metrics/scores computed (ScoreCalculationJob, MetricsPollingJob)
  → build public portfolio (PortfolioController)  → live at /{handle}
  → browse + APPLY to campaign = bid (CreatorCampaignController./{id}/apply)
  → deal negotiation: accept / counter (DealController)
  → sign contract (ContractController)
  → submit deliverable + metrics (CreatorDeliverableController)
  → brand approves → escrow released → payout to bank (EscrowController.payout)
  → affiliate coupons drive extra earnings (CouponCode + AffiliateEarning)
  → two-sided review (CreatorReviewController)
```

**"Bidding" in Influora** = a creator *applying* to a campaign with proposed terms, then the offer/counter loop on `/deals`. See `08-USER-GUIDE-CAMPAIGN-BID-AI.md`.
