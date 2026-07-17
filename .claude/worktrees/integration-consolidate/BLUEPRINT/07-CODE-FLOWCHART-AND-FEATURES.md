# Code Flowchart, Features & How Everything Connects

> Feature map + system flowcharts + a full worked example of one campaign end-to-end. Sourced from code.
> Lead: Priya. Diagrams render in any Mermaid-capable Markdown viewer (GitHub, VS Code, Obsidian).

---

## 1. System architecture

```mermaid
flowchart LR
  subgraph Browser
    SPA["React SPA (/src)"]
  end
  subgraph Server
    API["Spring API (/influora-api)\n55 controllers · 181 endpoints"]
    AI["AI service (/influora-ai)\nMeera · TrendSpark"]
    DB[("MySQL 8\n59 entities · 56 migrations")]
    RED[("Redis\ncache + rate limit")]
  end
  subgraph External
    RZP["Razorpay"]
    META["Meta / Instagram"]
    SHOP["Shopify / WooCommerce"]
    MSG["MSG91 (OTP/email)"]
    R2["Cloudflare R2 (media)"]
    LLM["Claude / Gemini / Sarvam"]
  end
  SPA -- "HTTPS /api/v1 + JWT" --> API
  API -- "SSE /stream" --> SPA
  API --> DB
  API --> RED
  API -- "signed service token (HMAC)" --> AI
  AI -- "provider keys (server only)" --> LLM
  AI -- "internal tool calls" --> API
  API --> RZP
  API --> META
  API --> SHOP
  API --> MSG
  API --> R2
```

---

## 2. Feature map (domain → code)

| Feature | Frontend | Backend controller(s) | Entities |
|---|---|---|---|
| Auth + OTP | `*-login/register`, `*-onboarding` | `AuthController`, `OnboardingController` | `User`, `EmailOtpChallenge`, `RefreshToken` |
| Campaigns (+ Hype) | `brand-*-campaign*` | `CampaignController`, `CampaignTrackingController` | `Campaign`, `CampaignIntent` |
| Discovery + scoring | `brand-discover`, `creator-profile` | `CreatorController`, `AnalyticsController` | `CreatorProfile`, `CreatorScore`, `SavedCreator` |
| Deals / bidding | `*-deals`, `*-chat` | `DealController` | `Collaboration`, `DealMessage` |
| Contracts | `brand-contracts` | `ContractController` | `Contract` |
| Escrow + wallet | `*-wallet` | `WalletController`, `EscrowController` | `Wallet`, `EscrowHold`, `PaymentMilestone`, `Payout` |
| Deliverables | `*-campaign-detail` | `Brand/CreatorDeliverableController` | `Deliverable`, `DeliverableMetric` |
| Analytics | `*-analytics` | `CreatorAnalyticsController` | `CreatorMetric`, `AudienceDemographics`, `MediaMetric` |
| Affiliate + coupons | `creator-coupons/-affiliate-earnings` | `CreatorCoupon/AffiliateEarning`, `ConversionWebhook` | `CouponCode`, `CouponRedemption`, `AffiliateEarning`, `UtmCampaign` |
| Reviews | `*-reviews` | `Brand/CreatorReviewController` | `Review` |
| Disputes | `*-disputes` | `Brand/Creator/AdminDisputeController` | `Dispute` |
| Notifications | in-app + email | `NotificationController` + `EmailWorker` | `Notification`, `EmailOutbox` |
| AI (Meera) | `brand-meera` | `MeeraController`, `MeeraInternalController` | `AiConversation`, `AiMessage`, `MeeraToolCall`, `BrandAiCredit` |
| TrendSpark | `components/trendspark` | `TrendSparkController` | `Trend`, `SnapsbyCatalogVideo` |
| Store integrations | settings | Shopify/Woo connect + webhooks | `ShopifyIntegration`, `WooCommerceIntegration` |
| Admin | `src/admin/*` | 11 admin controllers | `AdminUser`, `ContentFlag`, `SupportTicket`, `AdminAuditLog` |

---

## 3. Core happy-path flow (brand hires creator)

```mermaid
sequenceDiagram
  participant B as Brand (SPA)
  participant API as Spring API
  participant C as Creator (SPA)
  participant RZP as Razorpay
  B->>API: register/login + KYC (Auth/Onboarding)
  B->>API: POST /campaigns (create campaign)
  B->>API: GET /creators/search (discover)
  B->>API: POST /creators/{id}/invite
  C->>API: POST /creator/campaigns/{id}/apply  (BID)
  Note over B,C: /deals negotiation
  C->>API: POST /deals/{id}/counter
  B->>API: POST /deals/{id}/accept
  B->>API: POST /contracts/{id}/sign
  C->>API: POST /contracts/{id}/sign
  B->>API: POST /wallet/escrow/fund
  API->>RZP: create order / capture
  RZP-->>API: /webhooks/razorpay (paid)
  C->>API: POST /creator/deliverables/{id}/submit
  API-->>B: SSE deliverable.submitted
  B->>API: POST /deliverables/{id}/approve
  B->>API: POST /wallet/escrow/release
  API->>RZP: payout to creator (RazorpayX)
  API-->>B: SSE payment.released
  B->>API: POST /brand/reviews  (two-sided review)
  C->>API: POST /creator/reviews
```

---

## 4. AI-driven flow (brand runs a campaign by chatting with Meera)

```mermaid
sequenceDiagram
  participant B as Brand (brand-meera)
  participant API as Spring /meera
  participant AI as AI service
  participant LLM as Claude/Gemini
  B->>API: POST /meera/sessions/{id}/messages ("find 5 fashion creators, ₹50k")
  API->>AI: signed service token + prompt
  AI->>LLM: reason
  AI->>API: tool show_creators (via /internal/meera)
  API-->>AI: candidate creators
  AI->>API: tool calculate_budget
  AI->>API: tool create_campaign (writes real Campaign)
  AI->>API: tool request_payment  -> returns PENDING_CONFIRM (no money moves)
  AI-->>API: stream tokens
  API-->>B: SSE stream (assistant reply + "confirm to launch?")
  B->>API: human confirms -> confirm_launch
```
Key guardrail: **the AI can never move money.** `request_payment` only ever returns `PENDING_CONFIRM`; a human `confirm_launch` is required (enforced in `MeeraInternalController`).

---

## 5. Worked example — "Nykaa runs a Diwali campaign"

1. **Onboard.** Nykaa (brand) registers, verifies email OTP (MSG91), completes company KYC. Admin verifies KYC (`/admin/brands/{id}/verify-kyc`).
2. **Fund wallet.** Adds ₹2,00,000 via Razorpay (`/wallet/topup` → `/webhooks/razorpay`).
3. **Create campaign.** "Diwali Glow — 10 reels" via `/campaigns` (or by chatting with Meera).
4. **Discover.** Searches fashion/beauty creators (`/creators/search`); scores come from `ScoreCalculationJob` off Meta metrics. Invites 8.
5. **Bid.** Creator @riya applies (`/creator/campaigns/{id}/apply`) proposing ₹18,000/reel. Nykaa counters ₹15,000 (`/deals/{id}/counter`); @riya accepts.
6. **Contract.** Both sign (`/contracts/{id}/sign`); PDF generated (OpenPDF), downloadable via `/contracts/{id}/pdf-download-url`.
7. **Escrow.** Nykaa funds ₹15,000 into escrow (`/wallet/escrow/fund`). Money is held, not paid.
8. **Deliver.** @riya posts the reel, submits it + metrics (`/creator/deliverables/{id}/submit`, `/mark-posted`). `DeliverableVerificationJob` checks it; Nykaa gets SSE `deliverable.submitted`.
9. **Approve + pay.** Nykaa approves (`/deliverables/{id}/approve`) → releases escrow (`/wallet/escrow/release`) → RazorpayX payout to @riya's bank minus `PLATFORM_FEE_PERCENT`.
10. **Affiliate bonus.** @riya's coupon `RIYA20` drives sales; `ConversionWebhookController` records redemptions → `AffiliateEarning`; `AffiliateSettlementJob` pays out.
11. **Review.** Both leave reviews (`/brand/reviews`, `/creator/reviews`).

Every step above maps to a real endpoint in `04-API-CONNECTION.md`.

---

## 6. Background jobs that keep it running (11 `@Scheduled`)

`ScoreCalculationJob`, `MetricsPollingJob`, `AudienceDemographicsJob`, `MetaTokenRefreshService`, `DeliverableVerificationJob`, `DeliverableCleanupJob`, `AffiliateEarningReconciliationJob`, `AffiliateSettlementJob`, `StaleTokenCleanupJob`, `EmailWorker`, plus the app scheduler. These compute creator scores, refresh Meta tokens, verify deliverables, reconcile affiliate money, and drain the email outbox.
