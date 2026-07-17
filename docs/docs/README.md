# Influora — Developer Knowledge Base

> Generated from source code analysis. This documentation is written **for developers joining the project**, not for end users. A new engineer should be able to understand how the system works by reading these files, without first reading the source.
>
> **Last verified:** 2026-07-15 · **Source of truth:** the codebase (`influora-api/` Java backend + `src/` React frontend), never READMEs, comments, or existing `.md` files.

---

## What is Influora?

Influora is an **influencer-marketing marketplace for the Indian market** that connects **Brands** with **Creators** (influencers) and runs the full collaboration lifecycle end-to-end: discovery, campaign creation, deal negotiation, escrow-backed payments, contracts, deliverables, performance analytics, disputes, and GST-compliant invoicing. It layers an AI co-founder ("**Meera**") on top so brands can research, budget, and launch campaigns conversationally, and a trend-nudge engine ("**TrendSpark**") that suggests timely campaigns.

The platform serves three user types — **Brand**, **Creator**, **Admin** — plus two non-human actors: the **AI** (Meera / influora-ai service) and **service-to-service** integrations (Razorpay, Meta, Shopify, WooCommerce, MSG91).

## Technology at a glance

| Layer | Technology |
|---|---|
| Frontend | React 19, Vite 6, TypeScript, React Router v7 (CSR SPA), TanStack Query, Zustand, Tailwind v4, Radix/shadcn, framer-motion, three.js / R3F, Recharts |
| Backend | Java 21 · Spring Boot 3.3.5 (Web, Data JPA, Data Redis, Security, Validation, Actuator), JJWT 0.12, Flyway, MySQL 8, AWS S3 SDK (Cloudflare R2), Razorpay Java SDK, OpenPDF |
| AI service | External Python "influora-ai" (FastAPI) — Meera chat (SSE), Brand-Safety (GARM), TrendSpark phrasing |
| Data | MySQL 8 (InnoDB, utf8mb4), Redis (present, limited use), Cloudflare R2 object storage |
| Payments | Razorpay (orders/subscriptions) + RazorpayX (payouts) |
| Messaging | MSG91 (transactional email only; no SMS) |
| Deploy | Docker: frontend (Vite→nginx), backend (Spring Boot), Python AI; MySQL via compose; GitHub Actions CI |

## Documentation map

### Core reference

| File | What it covers |
|---|---|
| [project-overview.md](project-overview.md) | Business objective, modules, stack, lifecycle |
| [architecture.md](architecture.md) | System, frontend, backend, data, auth, AI, payment architecture + Mermaid diagrams |
| [backend.md](backend.md) | Spring Boot layering, packages, conventions, cross-cutting services |
| [frontend.md](frontend.md) | React app structure, routing, state, API layer, components |
| [database.md](database.md) | Every table: purpose, columns, relationships, migrations |
| [api.md](api.md) | Full REST API catalogue by domain |
| [authentication.md](authentication.md) | JWT/JWKS token model, sessions, OTP, MFA |
| [authorization.md](authorization.md) | Roles, plan gating, workspace membership, service-mesh auth |
| [ai.md](ai.md) | Meera + influora-ai: SSE, tool-call tiers, credits, AI clients |
| [external-services.md](external-services.md) | Razorpay, Meta, Shopify, WooCommerce, MSG91, R2, conversion tracking |
| [deployment.md](deployment.md) | Docker images, CI, profiles, release flow |
| [environment.md](environment.md) | Every environment variable and config property |
| [folder-structure.md](folder-structure.md) | Every major folder and why it exists |
| [security.md](security.md) | Threat model, filters, secrets, known risks |
| [performance.md](performance.md) | Caching, query patterns, jobs, scaling notes |
| [coding-guidelines.md](coding-guidelines.md) | Conventions discovered in the code |
| [developer-onboarding.md](developer-onboarding.md) | How to run it, how requests flow, where to add things |
| [known-limitations.md](known-limitations.md) | Consolidated stubs, gaps, and technical debt |

### Feature documentation (`features/`)

One file per discovered feature, each following a fixed template (business purpose → roles → flow → frontend → backend → database → APIs → AI → notifications → dependencies → files → execution flow → error handling → security → performance → testing → production readiness).

Auth & accounts: [authentication](features/authentication.md) · [workspaces-members](features/workspaces-members.md)

Dashboards: [brand-dashboard](features/brand-dashboard.md) · [creator-dashboard](features/creator-dashboard.md) · [admin-dashboard](features/admin-dashboard.md)

Marketplace: [campaigns](features/campaigns.md) · [marketplace-discovery](features/marketplace-discovery.md) · [collaborations-deals](features/collaborations-deals.md) · [contracts](features/contracts.md) · [creator-profiles-portfolio](features/creator-profiles-portfolio.md) · [deliverables](features/deliverables.md) · [reviews](features/reviews.md)

Money: [wallet](features/wallet.md) · [escrow](features/escrow.md) · [payouts](features/payouts.md) · [billing-subscriptions](features/billing-subscriptions.md) · [platform-fees](features/platform-fees.md) · [invoicing-gst](features/invoicing-gst.md) · [affiliate-coupons](features/affiliate-coupons.md)

Operations: [disputes](features/disputes.md) · [analytics](features/analytics.md) · [notifications](features/notifications.md) · [uploads-storage](features/uploads-storage.md) · [reports-exports](features/reports-exports.md) · [support-tickets](features/support-tickets.md)

AI & integrations: [meera-ai](features/meera-ai.md) · [trendspark](features/trendspark.md) · [meta-integration](features/meta-integration.md) · [shopify-integration](features/shopify-integration.md) · [woocommerce-integration](features/woocommerce-integration.md) · [conversion-tracking](features/conversion-tracking.md)

## How to read this as a new developer

1. Start with [project-overview.md](project-overview.md) and [architecture.md](architecture.md) for the mental model.
2. Read [developer-onboarding.md](developer-onboarding.md) to get it running locally.
3. Read [backend.md](backend.md) / [frontend.md](frontend.md) for the layering and conventions.
4. Dive into the specific `features/` file for whatever you're changing.
5. Consult [known-limitations.md](known-limitations.md) before assuming a feature is fully wired — several flows are intentionally stubbed or half-connected.

## A note on honesty

This codebase is deliberately honest about its own gaps. Analytics returns empty shapes rather than fabricated numbers; brand-safety scores are `NULL` (not `0`) when unscored; the AI never fabricates prices or writes money. Where a flow is only partly wired (subscription webhooks, real payouts, synchronous affiliate accrual), this documentation says so explicitly rather than describing the intended design as if it were live. See [known-limitations.md](known-limitations.md).
