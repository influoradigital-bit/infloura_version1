# Influora — Master Code Blueprint

> **Source of truth:** the working code tree (not planning docs).
> **Compiled by:** Priya (CTO) with Arjun (Eng Lead), Vikram (Backend), Ananya (Frontend), Ash (AI), Kabir (Security).
> **Date:** 13 Jul 2026 · **Repo:** `New Influora`

Influora is an **escrow-protected influencer-marketing marketplace** for India. Brands run campaigns, discover creators, negotiate deals, fund escrow, and pay on verified deliverables. Creators apply/bid, sign contracts, submit work, and withdraw earnings. An AI assistant ("Meera") lets brands run the whole flow in natural language.

---

## The three services

| Service | Folder | Stack | Role |
|---|---|---|---|
| **Web SPA** | `/src` | React 19, Vite 6, TypeScript, Tailwind v4, Zustand, TanStack Query | All UI: brand, creator, admin, marketing |
| **Core API** | `/influora-api` | Java 21, Spring Boot, MySQL + Flyway, Redis, JWT/JWKS, Razorpay, S3/R2 | Business logic, data, payments, integrations |
| **AI service** | `/influora-ai` | Python, FastAPI, Claude + Gemini + Sarvam | Meera reasoner, TrendSpark, brand-safety |

```
Browser (SPA)  ──HTTPS /api/v1 + JWT──►  Spring API  ──►  MySQL
      ▲                                     │  ▲
      └────────── SSE stream ───────────────┘  │ short-lived service token (HMAC)
                                               ▼
                                        Python AI service ──► Claude / Gemini / Sarvam
```

The browser never holds provider keys and never calls the AI service directly for privileged work — Spring brokers it.

---

## Size (code-verified)

| | Count |
|---|---|
| Total application LOC | ~130,166 |
| Frontend files / routes | 366 / 72 |
| Backend controllers / endpoints | 55 / 181 |
| JPA entities / migrations | 59 / 56 |
| Scheduled jobs | 11 |
| AI service files / endpoints | 34 / 6 |
| External integrations | 8 |
| Automated tests | 1,130+ (953 Java, 177 FE, 17 PY suites) |

---

## The blueprint document set

| # | File | What it covers | Lead |
|---|---|---|---|
| 00 | `00-MASTER-BLUEPRINT.md` | This index + system overview | Priya |
| 01 | `01-BRAND-CODE.md` | Brand-side frontend + backend | Ananya / Vikram |
| 02 | `02-CREATOR-CODE.md` | Creator-side frontend + backend | Ananya / Vikram |
| 03 | `03-ADMIN-CODE.md` | Admin console + moderation | Vikram |
| 04 | `04-API-CONNECTION.md` | Full endpoint map + client contract | Vikram |
| 05 | `05-AI-CODE.md` | Meera / TrendSpark AI service | Ash |
| 06 | `06-DEPLOYMENT-AND-API-KEYS.md` | Deploy basic→advanced, every API key, where it goes | Meera (DevOps) |
| 07 | `07-CODE-FLOWCHART-AND-FEATURES.md` | Feature map + flowcharts + worked example | Priya |
| 08 | `08-USER-GUIDE-CAMPAIGN-BID-AI.md` | End-user guide: campaigns, bidding, AI | Nisha |

---

## Core domains (feature areas)

Auth & onboarding · Campaigns (standard + Hype) · Creator discovery & scoring · Deals / Deal Room / messaging · Contracts (PDF) · Escrow & Wallet (Razorpay) · Deliverables + verification · Analytics (Meta metrics) · Affiliate & coupons · Reviews (two-sided) · Disputes · Notifications (in-app + email) · Platform fees · AI (Meera + TrendSpark) · Store integrations (Shopify / WooCommerce) · Admin ops.

See the per-domain files for controllers, entities, pages, and endpoints.

---

## How to read this set

- **Developers onboarding:** start here → `04-API-CONNECTION.md` → your domain file (01/02/03/05).
- **Deploying:** `06-DEPLOYMENT-AND-API-KEYS.md`.
- **Understanding a full flow:** `07-CODE-FLOWCHART-AND-FEATURES.md`.
- **Non-technical / users:** `08-USER-GUIDE-CAMPAIGN-BID-AI.md`.
