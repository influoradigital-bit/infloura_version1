# 🚀 INFLUORA — BUSINESS BLUEPRINT
> Co-founders: Swapnil Maruti × Claude | Date: 2026-07-04
> Grounded in the actual repo (verified against `influora-api/` + `src/`) and the founder-vs-expert debate (`BUSINESS-IDEA-THAT-STANDS.md`).

---

## ⭐ TAGLINE

# **"Create. Collab. Get Paid. Guaranteed."**

Alternates (for A/B testing on landing page):
- **"India's Creator Economy, Now With a Payment Guarantee."**
- **"Where Deals Close and Creators Get Paid."**
- Hype Campaign sub-tagline: **"One Reel Sparks It. A Hundred Creators Make It Viral."**

Why this tagline: every competitor sells *discovery*. Nobody sells *guaranteed payment*. Escrow is our flagship — the tagline must say it in 5 words a 19-year-old creator in Nagpur understands.

---

## 1. WHAT WE ARE BUILDING (One Paragraph)

**Influora is an escrow-backed Campaign Operating System connecting Indian brands with micro-creators (1K–100K followers).** Brands launch campaigns, negotiate in a chat-first Deal Room, sign e-contracts, and pay through escrow — funds lock at signing, release on approval. Creators get an inbox of real, funded opportunities with transparent net earnings and guaranteed payouts. The flagship growth feature is the **Hype Campaign**: when a big creator or brand drops a new reel, hundreds of vetted micro-creators create derivative reels (reactions, duets, POV takes) in a 72-hour window at a flat per-reel rate — manufactured trend velocity, paid instantly from an escrow pool.

---

## 2. TECH STACK (Locked — Priya's domain, verified in repo)

| Layer | Technology | Where in repo | Why |
|---|---|---|---|
| Frontend | **React 18 + Vite + TypeScript** | `src/` (33 pages), `vite.config.ts` | Fast HMR dev loop, SPA speed for chat-first UX; Tailwind + shadcn/ui (`components/ui/`), Framer Motion (`src/components/motion/`), 3D hero (`src/components/3d/`) |
| Backend | **Spring Boot 3.3.5, Java 21** | `influora-api/pom.xml` | Enterprise-grade money handling — transactions, `@Transactional` escrow ops, Spring Security + JWT (`security/JwtService.java`) |
| Database | **MySQL 8 + Flyway migrations** | `influora-api/src/main/resources/db/migration/` V1–V7 | ACID guarantees for wallet/escrow; versioned schema |
| Storage | **Cloudflare R2 (S3 SDK)** | `integration/storage/R2StorageService.java`, V1 migration | Zero-egress-fee media storage — critical when 300 creators upload reels per campaign |
| Auth | **JWT + refresh tokens + email OTP (MSG91)** | V2 + V5 migrations, `AuthService`, `BrandEmailOtpService` | Passwordless-friendly for creators; secure sessions for brands |
| Payments (to build) | **Razorpay Route (split payments) + RazorpayX (payouts)** | M2 milestone | Route handles escrow-style splits natively; X handles 300 micro-payouts/campaign |
| Infra | **Docker Compose** | `docker-compose.yml` | Single-command local + deploy parity |

Backend package layout (exists today): `com.influora.{config, domain.entity, domain.enums, repository, service, security, web, web.dto, integration.storage, common}` — clean layered architecture; every new feature follows this same package pattern.

---

## 3. FEATURE BLUEPRINT — WHAT / WHY / CODE-LEVEL

### ✅ PHASE 0 — ALREADY BUILT (live end-to-end)

#### F1. Dual Auth & Workspaces
- **What:** Brand register/login/forgot-password, email OTP verification, multi-member workspaces with unique slugs.
- **Why:** Agencies manage multiple brands — workspace model (not single-user) from day one.
- **Code:** `AuthController`, `AuthService`, `JwtService`, `WorkspaceController`, `WorkspaceSlugService` | Tables: `users`, `workspaces`, `workspace_members`, `refresh_tokens`, `password_reset_tokens`, `email_otp_challenges` (V2, V5) | Pages: `brand-login.tsx`, `brand-register.tsx`, `brand-forgot-password.tsx`, `creator-login.tsx`, `creator-register.tsx`

#### F2. Onboarding + KYC scaffold
- **What:** Guided brand/creator onboarding; KYC document upload to R2.
- **Why:** PAN-KYC at onboarding is the legal foundation for TDS-clean payouts later — collected on day 1, not bolted on.
- **Code:** `OnboardingController`, `OnboardingService`, `R2StorageService` | Tables: `file_uploads` (V1), workspace KYC docs (V3) | Pages: `brand-onboarding.tsx`, `creator-onboarding.tsx`

#### F3. Campaign CRUD
- **What:** 4-step campaign creation (brief → deliverables → budget → tracking), edit, list, detail.
- **Why:** The campaign is the atomic unit of our GMV — everything (deals, contracts, escrow, hype) hangs off it.
- **Code:** `CampaignController`, `CampaignService`, `web/dto/campaign/` | Table: `campaigns` (V4) | Pages: `brand-new-campaign.tsx`, `brand-campaigns.tsx`, `brand-campaign-detail.tsx`, `brand-edit-campaign.tsx`

#### F4. Creator Discovery
- **What:** Search/filter creators by niche, followers, platform stats; save to lists; public portfolio pages.
- **Why:** Discovery is the brand's entry drug; portfolio pages are the creator's free "link-in-bio" acquisition hook.
- **Code:** `CreatorController`, `CreatorDiscoveryService` | Tables: `creator_profiles`, `platform_stats`, `saved_creators`, `collaborations` (V6, seeded V7) | Pages: `brand-discover.tsx`, `brand-creator-profile.tsx`, `creator-portfolio-public.tsx`, `creator-portfolio-editor.tsx`

---

### 🔨 M1 — CREATOR SIDE COMPLETE + DEAL ROOM (Weeks 1–6)

#### F5. Creator Auth + Instagram OAuth Verification
- **What:** Full creator login parity + **Instagram Graph API OAuth** — pull real follower count, engagement, media list into `platform_stats`.
- **Why:** Kills the fraud problem before Hype launches. Unverified accounts never see Hype invites. This verified pool IS the moat.
- **Code to add:** `integration/instagram/InstagramOAuthService.java`, `InstagramGraphClient.java`; new columns on `platform_stats` (`ig_user_id`, `verified_at`, `token_ref`); migration `V8__instagram_oauth.sql`; endpoint `POST /api/creators/me/instagram/connect`; frontend `creator-profile.tsx` "Verify Instagram" flow.

#### F6. Deal Room (chat-first negotiation) — REAL backend
- **What:** One timeline per collaboration: messages + proposal cards + counter-proposals + contract card + deliverable cards (per `UNIFIED_TIMELINE_PLAN.md`). Currently frontend + mocks.
- **Why:** Deals die in WhatsApp because nothing is structured. Structured proposal cards = machine-readable deal terms = auto-generated contracts. This is the UX competitors don't have.
- **Code to add:** `DealRoomController`, `MessageService`, `ProposalService`; WebSocket via `spring-boot-starter-websocket` (STOMP) for live chat; migration `V9__deal_room.sql` → tables `conversations`, `messages`, `proposals` (status enum: DRAFT/SENT/COUNTERED/ACCEPTED/EXPIRED); wire pages `brand-chat.tsx`, `brand-messages.tsx`, `creator-chat.tsx`, `brand-pipeline.tsx` to real APIs.

---

### 🔨 M2 — MONEY RAILS (Weeks 7–12) — the flagship

#### F7. Escrow Wallet + Contracts
- **What:** Brand funds wallet → escrow locks at contract signing → releases on deliverable approval. E-signed contract auto-generated from accepted proposal terms.
- **Why:** THE product. "Guaranteed" in our tagline is this feature. Also our fee-collection point — clean, automatic, no invoicing chase.
- **Code to add:** `WalletController`, `EscrowService` (all ops `@Transactional` with `SELECT ... FOR UPDATE` on wallet rows — no double-spend), `ContractService` (PDF via OpenPDF, SHA-256 hash stored for tamper-proofing), `integration/razorpay/RazorpayRouteClient.java`; migration `V10__escrow_contracts.sql` → `escrow_holds`, `wallet_transactions` (double-entry ledger: every credit has a debit), `contracts`, `payment_milestones`; wire `brand-wallet.tsx`, `creator-wallet.tsx`, `brand-contracts.tsx`.

#### F8. TDS + Compliance Engine
- **What:** Auto-compute 194C/194R TDS at payout, PAN-validated, quarterly 26Q export; creator sees line-item breakdown (Gross − TDS = Net, platform fee = ₹0 creator-side).
- **Why:** 300 payees/campaign is impossible manually. Compliance-as-code is a B2B selling point ("your finance team does nothing").
- **Code to add:** `TdsCalculatorService`, `ComplianceReportService` (CSV/Excel export endpoint `GET /api/admin/reports/26q?quarter=`); columns on `wallet_transactions`: `tds_amount`, `tds_section`, `pan_verified`.

---

### 🔨 M3 — DELIVERABLES + AI REVIEW (Weeks 13–18)

#### F9. Deliverable Pipeline (2-revision cap)
- **What:** Creator submits via R2 upload → deliverable card in Deal Room → brand approves/requests revision (max 2) → approval triggers escrow release.
- **Why:** The revision cap is a creator-protection feature — scope creep is the #1 creator complaint on every competitor.
- **Code to add:** `DeliverableController`, `DeliverableService`; migration `V11__deliverables.sql` → `deliverables` (status: SUBMITTED/IN_REVIEW/REVISION_1/REVISION_2/APPROVED/AUTO_APPROVED), `revision_requests`; wire `creator-active.tsx`, `creator-deals.tsx`, `brand-deals.tsx`.

#### F10. AI Pre-Screen Service
- **What:** On submission: audio-match vs source reel, brief checklist scoring, **#ad/#collab disclosure detection** (blocks approval if missing — ASCI compliance), repost-fingerprint fraud check. Humans only review flagged ~10–15%.
- **Why:** Makes 300-reel review possible in 3–4 hours instead of 15. Disclosure-blocking makes us the only ASCI-safe platform by default.
- **Code to add:** `service/screening/PreScreenService.java` orchestrating async checks (Spring `@Async` + job table `screening_jobs` in `V12__prescreen.sql`); audio fingerprint via Chromaprint sidecar container in `docker-compose.yml`; verdict enum PASS/FLAG/FAIL on `deliverables.prescreen_verdict`.

---

### 🔥 M4 — HYPE CAMPAIGN (Weeks 19–24) — the flagship FEATURE

#### F11. Hype Campaign Engine
- **What:** New campaign type. Brand sets: source reel URL, audio/hashtag, format lanes (reaction / duet / POV / "I tried it" / regional retelling), 72-hr window, flat per-reel rate (₹500–2,000), pool size (**gated ≤100 reels until 3 runs prove reach telemetry**). Blast to matched verified creators' inboxes → one-tap accept (no negotiation) → staggered randomized posting cohorts → AI pre-screen → 48-hr auto-approve SLA → instant micro-payout per approval.
- **Why:** 100–500 transactions in 72 hrs = GMV machine; a creator's first ₹1,000 converts them into a KYC'd verified account = acquisition funnel; derivative variety (not clones) rides Instagram's trend mechanics instead of tripping originality demotion.
- **Code to add:** `campaigns.type` enum + `HYPE`; migration `V13__hype.sql` → `hype_briefs` (source_url, audio_id, format_lanes JSON, window_hours, per_reel_rate, max_slots), `hype_slots` (creator_id, status: INVITED/ACCEPTED/SUBMITTED/APPROVED/PAID, cohort_index, scheduled_post_at); `HypeCampaignService` (slot allocation with optimistic locking — 300 creators tapping Accept simultaneously), `CohortSchedulerService` (randomized stagger), auto-approve via Spring Scheduler; new pages `brand-new-hype-campaign.tsx`, creator one-tap accept card in `creator-inbox.tsx`.

#### F12. Campaign Analytics + Reach Telemetry
- **What:** Per-reel reach/engagement pulled via Instagram Graph API into `reel_metrics`; campaign dashboard shows aggregate reach, trend velocity, and the expansion criterion (median per-reel reach ≥75% of creator's 30-day baseline).
- **Why:** Turns "did it go viral?" from vibes into evidence — this data is what lets us raise Hype cap from 100 → 300, and it's the proprietary per-creator performance dataset nobody can copy.
- **Code to add:** `MetricsIngestService` (scheduled pull), `reel_metrics` table in V13, `brand-campaign-detail.tsx` analytics tab wired to real data.

---

## 4. PRICING — MARKET STANDARD vs OURS

### What the market charges (reference points)
| Player | Model | Effective cost |
|---|---|---|
| Traditional agencies (India) | 20–30% commission + retainer | Highest; opaque |
| Collabstr / #paid (global) | 15–20% marketplace take | Creator-side fees resented |
| Qoruz / Plixxo (India SaaS) | ₹40K–₹1L+/yr subscriptions | Discovery only — no money rails |
| Meta Creator Marketplace | Free | Discovery only; no escrow, contracts, or compliance |
| One mid-tier influencer reel (10–100K followers, India) | — | ₹2,000–₹15,000 market rate |

### OUR PRICING (brand-side only; creator platform fee = ₹0)
| Product | Price | Rationale |
|---|---|---|
| **Standard Campaign** (Open/Direct) | **₹20,000 flat setup + 15% of escrow pool** | Below agency 20–30%; setup fee filters non-serious brands; min pool ₹1.5L (or min blended fee ₹40K) keeps unit economics positive |
| **Hype Campaign** | **₹25,000 setup + 15% of pool** | Higher setup covers AI pre-screen + cohort ops; 100 reels × ₹1,000 pool = ₹1L → revenue ₹40K/campaign |
| **Brand Retainer** (from month 6) | **₹40,000–₹50,000/quarter** | Waived setup fees, priority creator pool, dedicated support — retention layer, repeat brands are where marketplaces win |
| **Creator side** | **FREE. Forever.** | Creator sees ₹1,000 gross → ₹900 net (TDS only, line-itemed). Supply side liquidity is the moat; never tax it |
| Per-reel Hype rate (guidance to brands) | ₹500–₹2,000/reel | At/below market micro-creator rates because we remove their collection risk — guaranteed escrow pay beats a higher rate they must chase |

**Unit check (expert-validated):** 300-reel × ₹1,000 Hype = ₹65K revenue, ~₹30–35K contribution after review, payout fees, TDS ops, concierge labor. Break-even ~30 campaigns/month at ₹10L burn — retainer layer closes the gap earlier.

---

## 5. GO-TO-MARKET (Cold Start)

1. **Concierge phase (Campaigns 1–5):** hand-recruit 50–150 creators via city creator communities; manual RazorpayX payouts; pitch = *"Fixed price, fixed pool — guaranteed N approved reels or shortfall refunded from escrow."* Brands buy outcomes, not networks.
2. **Every campaign mints supply:** each participant exits as a KYC'd, Instagram-OAuth-verified, performance-rated creator account.
3. **Target vertical first:** D2C brands + Indian export businesses (spices, textiles, handicrafts) — Sage Digital's existing client network is the warm intro channel.
4. **Hype launches only after** 3 concierge campaigns + ~500 verified creators + M2 money rails live.

---

## 6. RISKS & GUARDRAILS

| Risk | Guardrail |
|---|---|
| Instagram originality demotion / CIB flags | Derivative format lanes (never clones), staggered cohorts, density caps, ≤100-reel gate until telemetry proves reach |
| Platform dependency (biggest risk) | Escrow + compliance rails built platform-agnostic from M1; Instagram is a channel, not the foundation |
| Bot/repost farms | OAuth + PAN-KYC + tiered eligibility gate on every Hype invite |
| ASCI liability | Disclosure detection blocks escrow release — automated, zero discretion |
| Unit economics inversion | Pool floor ₹1.5L, concierge labor <30% of campaign revenue, retainers by month 6 |

---

## 7. THE ONE-SLIDE SUMMARY

> **INFLUORA — "Create. Collab. Get Paid. Guaranteed."**
> Escrow-backed Campaign OS for India's 3M+ micro-creators and the brands who need them.
> React+Vite front, Spring Boot+MySQL money rails, Razorpay escrow.
> Brand pays ₹20K + 15%. Creator pays nothing, ever.
> Flagship feature: **Hype Campaigns** — 100 verified micro-creators, 72 hours, one trend, instant payouts.
> Flagship company: **the only platform in India where a creator's payment is guaranteed before they press record.**
