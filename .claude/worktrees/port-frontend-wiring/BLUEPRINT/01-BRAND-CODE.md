# Brand Code Blueprint

> Everything a **brand** touches — frontend screens, backend controllers, entities. Sourced from code.
> Leads: Ananya (frontend), Vikram (backend).

A brand signs up, onboards its company, funds a wallet, creates campaigns, discovers creators, negotiates deals, funds escrow, reviews deliverables, and releases payment.

---

## 1. Frontend (React SPA) — `/src`

### Routes (`src/App.tsx`)
```
/brand/login              /brand/register           /brand/forgot-password
/brand/onboarding         /brand/dashboard          /brand/campaigns
/brand/campaigns/new      /brand/campaigns/new/hype  /brand/campaigns/:id
/brand/campaigns/:id/edit /brand/campaigns/:id/tracking
/brand/discover           /brand/creators/:id       /brand/analytics
/brand/analytics/:creatorId
/brand/wallet             /brand/chat               /brand/meera
/brand/contracts          /brand/messages           /brand/settings
/brand/disputes           /brand/reviews
```

### Key page files (`src/pages/`)
`brand-dashboard.tsx`, `brand-campaigns.tsx`, `brand-new-campaign.tsx`, `brand-new-hype-campaign.tsx`, `brand-edit-campaign.tsx`, `brand-campaign-detail.tsx`, `brand-campaign-tracking.tsx`, `brand-discover.tsx`, `brand-creator-profile.tsx`, `brand-creator-analytics.tsx`, `brand-analytics.tsx`, `brand-wallet.tsx`, `brand-chat.tsx`, `brand-meera.tsx`, `brand-contracts.tsx`, `brand-messages.tsx`, `brand-disputes.tsx`, `brand-reviews.tsx`, `brand-settings.tsx`, `brand-onboarding.tsx`.

### Components
`src/components/brand/` — **38 components** (largest domain). Plus shared `campaigns/` (5) and `analytics/` (8).

### State & data
- Global store: `src/lib/store.ts` (Zustand).
- API access: `src/lib/api.ts` brand resource groups — `auth`, `workspaces`, `onboarding`, `campaigns`, `creators`, `deals`, `contracts`, `deliverables`, `wallet`, `analytics`, `notifications`, `brandReviews`, `brandDisputes`, `storeIntegrations`, `trendspark`.
- AI: `src/lib/meera-api.ts` (`brand-meera` screen).

---

## 2. Backend (Spring) — brand-facing controllers

| Controller | Base path | Key actions |
|---|---|---|
| `AuthController` | `/auth` | `/brand/send-email-otp`, `/brand/verify-email`, `/brand/register`, `/brand/login`, `/refresh`, `/logout`, `/forgot-password`, `/reset-password` |
| `OnboardingController` | `/onboarding` | `/brand/company`, `/brand/kyc`, `/brand/complete` |
| `CampaignController` | `/campaigns` | create, `/{id}` get/update/delete, `/{id}/duplicate`, `/{id}/analytics` |
| `CampaignTrackingController` | `/campaigns/{campaignId}` | `/tracking-links`, `/coupons` |
| `CreatorController` | `/creators` | `/search`, `/featured`, `/suggestions`, `/{id}`, `/{id}/save`, `/{id}/invite` |
| `DealController` | `/deals` | `/{id}`, `/{id}/accept`, `/{id}/reject`, `/{id}/counter`, `/{dealId}/messages` |
| `ContractController` | `/contracts` | `/unsigned`, `/{id}`, `/{id}/sign`, `/{id}/pdf-download-url` |
| `BrandDeliverableController` | `/deliverables` | `/{id}`, `/{id}/approve`, `/{id}/revise` |
| `WalletController` | `/wallet` | `/balance`, `/topup`, `/withdraw`, `/transactions` |
| `EscrowController` | `/wallet/escrow` | `/fund`, `/release`, `/refund`, `/payout`, `/{escrowHoldId}` |
| `BrandReviewController` | `/brand/reviews` | list, `/received`, `/{id}/flag` |
| `BrandDisputeController` | `/brand/disputes` | `/list` |
| `BrandPlatformFeeController` | `/brand/platform-fee` | fee lookup |
| `DashboardController` | `/dashboard` | `/actions`, `/pipeline` |
| `TrendSparkController` | `/brand/trendspark` | `/nudge`, `/nudge/{id}/click`, `/nudge/{id}/purchase` |
| `MeeraController` | `/meera` | `/sessions`, `/credits`, `/brand-profile` (AI assistant) |

### Entities the brand owns/uses
`BrandProfile`, `Workspace`, `WorkspaceMember`, `Campaign`, `CampaignIntent`, `Wallet`, `WalletTransaction`, `WalletTopUp`, `EscrowHold`, `PaymentMilestone`, `Contract`, `Collaboration` (deal), `DealMessage`, `Deliverable`, `Review`, `Dispute`, `SavedCreator`, `BrandAiCredit`, `PlatformFeeConfig`.

---

## 3. Brand lifecycle (code path)

```
register/login (AuthController)
  → onboarding company + KYC (OnboardingController)
  → wallet topup via Razorpay (WalletController + RazorpayWebhookController)
  → create campaign (CampaignController)  [standard or Hype]
  → discover/invite creators (CreatorController)
  → deal negotiation: accept / reject / counter (DealController)
  → contract sign (ContractController → OpenPDF)
  → fund escrow (EscrowController.fund)
  → creator submits deliverable → brand approve/revise (BrandDeliverableController)
  → release escrow (EscrowController.release) → payout
  → two-sided review (BrandReviewController)
```

See `07-CODE-FLOWCHART-AND-FEATURES.md` for the full cross-service diagram and `08` for the user-facing version.
