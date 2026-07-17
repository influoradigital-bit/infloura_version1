# Influora — Full Dependency Inventory

> Generated 2026-07-16 from the real manifests in the repo:
> `package.json` (frontend), `influora-ai/requirements.txt` + `requirements-dev.txt` (Python), `influora-api/pom.xml` (Java).
> Versions below are exactly what those files pin.

Influora is three services in three languages. Each has its own dependency set, installed with its own tool:

| Service | Folder | Language | Install command | Manifest |
|---|---|---|---|---|
| Web SPA | `src/` (root) | TypeScript / React 19 | `npm ci` | `package.json` + `package-lock.json` |
| AI service | `influora-ai/` | Python 3.13 | `pip install -r requirements.txt` | `requirements.txt` |
| Core API | `influora-api/` | Java 21 | `mvn clean package` (downloads to `~/.m2`) | `pom.xml` |

---

## 1. Python (AI service — `influora-ai/`)

Runtime: **Python 3.13** (Docker base `python:3.13-slim`). Installed from `requirements.txt`.

### Production libraries (`requirements.txt`)

| Library | Version | What it's for |
|---|---|---|
| `fastapi` | 0.115.6 | The web framework the whole AI service is built on (routes, request/response, dependency injection). |
| `uvicorn[standard]` | 0.34.0 | The ASGI server that actually runs FastAPI. The `[standard]` extra pulls in `uvloop`, `httptools`, and `websockets` for speed. |
| `httpx[http2]` | 0.28.1 | Async HTTP client. Used for every outbound call — to Claude/Gemini/Sarvam and to the Spring internal API. `[http2]` enables HTTP/2. |
| `anthropic` | 0.42.0 | Official Claude SDK (Meera's main reasoning model). |
| `google-genai` | 0.8.0 | Official Google Gemini SDK (secondary model). |
| `playwright` | 1.49.1 | Headless Chromium automation, used by `/analyze-site` to fetch and render brand-supplied URLs. **Also needs the browser binary** — the Docker build runs `playwright install --with-deps chromium` separately (see §1.2). |
| `pyjwt[crypto]` | 2.10.1 | Verifies inbound JWTs against Spring's JWKS, and signs the internal service-token JWT. `[crypto]` adds the `cryptography` backend needed for EC/RSA (ES256/RS256). |
| `pydantic` | 2.10.4 | Data validation and settings models. All config flows through Pydantic models in `app/config.py`. |
| `python-multipart` | 0.0.20 | Parses `multipart/form-data` — required for file-upload endpoints. |

### Dev / test only (`requirements-dev.txt`)

These are **never** in the production image (the Dockerfile only installs `requirements.txt`).

| Library | Version | What it's for |
|---|---|---|
| `pytest` | 8.3.4 | Test runner (`influora-ai/tests/`, including the eval harness). |
| `pytest-asyncio` | 0.25.2 | Lets pytest run `async def` tests (FastAPI is async). |

### 1.2 The extra install step you can't skip

`playwright` (the pip package) is just the driver. The actual browser is a separate download:

```bash
python -m playwright install --with-deps chromium
```

This is ~1 GB (Chromium + system libs). Do it after `pip install`. On the server this happens inside the Docker build.

---

## 2. Node / TypeScript (Web SPA — root project)

Runtime: **Node 20** (Docker base `node:20-alpine`), **TypeScript 5.7.3**, bundled by **Vite 6**. Installed from `package.json` via `npm ci`.

### Core framework & routing

| Library | Version | Purpose |
|---|---|---|
| `react` / `react-dom` | ^19 | The UI framework (React 19). |
| `react-router-dom` | ^7.15.0 | Client-side routing. **This is an SPA, not Next.js** — despite `next.config.mjs` / `next-env.d.ts` being present. |

### Build & tooling (devDependencies)

| Library | Version | Purpose |
|---|---|---|
| `vite` | ^6.0.0 | Dev server + production bundler. |
| `@vitejs/plugin-react` | ^4.3.0 | React support for Vite. |
| `typescript` | 5.7.3 | Type checking / compilation. |
| `@types/node`, `@types/react`, `@types/react-dom` | ^22 / ^19 | Type definitions. |

### Styling

| Library | Version | Purpose |
|---|---|---|
| `tailwindcss` | ^4.2.0 | Utility-first CSS framework (v4). |
| `@tailwindcss/postcss` | ^4.2.0 | Tailwind's PostCSS plugin. |
| `postcss` | ^8.5 | CSS processing pipeline. |
| `autoprefixer` | ^10.4.20 | Adds vendor prefixes. |
| `tw-animate-css` | 1.3.3 | Animation utilities for Tailwind. |
| `class-variance-authority` | ^0.7.1 | Typed component style variants. |
| `clsx` | ^2.1.1 | Conditional className joining. |
| `tailwind-merge` | ^3.3.1 | De-duplicates conflicting Tailwind classes. |

### UI component primitives (Radix + shadcn-style)

`@radix-ui/react-*` — accessible headless primitives. The project uses a large set (all recent versions): `accordion`, `alert-dialog`, `aspect-ratio`, `avatar`, `checkbox`, `collapsible`, `context-menu`, `dialog`, `dropdown-menu`, `hover-card`, `label`, `menubar`, `navigation-menu`, `popover`, `progress`, `radio-group`, `scroll-area`, `select`, `separator`, `slider`, `slot`, `switch`, `tabs`, `toast`, `toggle`, `toggle-group`, `tooltip`, plus the umbrella `radix-ui` ^1.4.3.

Supporting UI:

| Library | Version | Purpose |
|---|---|---|
| `lucide-react` | ^0.564.0 | Icon set. |
| `cmdk` | 1.1.1 | Command-palette component. |
| `sonner` | ^1.7.1 | Toast notifications. |
| `vaul` | ^1.1.2 | Drawer component. |
| `embla-carousel-react` | 8.6.0 | Carousels. |
| `input-otp` | 1.4.2 | OTP input boxes. |
| `react-day-picker` | 9.13.2 | Date picker. |
| `react-resizable-panels` | ^2.1.7 | Resizable split panes. |

### State, data & forms

| Library | Version | Purpose |
|---|---|---|
| `@tanstack/react-query` | ^5.100.10 | Server-state / data fetching + caching. |
| `zustand` | ^5.0.13 | Client state store. |
| `react-hook-form` | ^7.54.1 | Form state & validation. |
| `@hookform/resolvers` | ^3.9.1 | Bridges react-hook-form to Zod. |
| `zod` | ^3.24.1 | Runtime schema validation. |

### Animation & 3D

| Library | Version | Purpose |
|---|---|---|
| `framer-motion` | ^12.38.0 | Component animation. |
| `gsap` + `@gsap/react` | ^3.15.0 / ^2.1.2 | Timeline animation. |
| `lenis` | ^1.3.23 | Smooth scrolling. |
| `three` | ^0.184.0 | WebGL / 3D engine. |
| `@react-three/fiber` | ^9.6.1 | React renderer for Three.js. |
| `@react-three/drei` | ^10.7.7 | Helpers for react-three-fiber. |
| `@splinetool/react-spline` + `runtime` | ^4.1.0 / ^1.12.94 | Embeds Spline 3D scenes. |

### Misc

| Library | Version | Purpose |
|---|---|---|
| `recharts` | 2.15.0 | Charts / graphs. |
| `date-fns` | 4.1.0 | Date utilities. |
| `@vercel/analytics` | 1.6.1 | Web analytics. |

### Testing (devDependencies)

| Library | Version | Purpose |
|---|---|---|
| `vitest` | ^3.2.7 | Unit test runner. |
| `@testing-library/react` | ^16.3.2 | Component testing. |
| `@testing-library/jest-dom` | ^6.9.1 | DOM matchers. |
| `@testing-library/user-event` | ^14.6.1 | Simulated user interactions. |
| `jsdom` | ^25.0.1 | Headless DOM for tests. |
| `lighthouse` | ^12.8.2 | Performance auditing (`npm run lh:meera`). |
| `puppeteer-core` | ^25.3.0 | Drives Chrome for Lighthouse. |

> Note: the repo has **both** `package-lock.json` and `pnpm-lock.yaml`. CI and the Dockerfile use **`npm ci`** against `package-lock.json` — use npm, not pnpm, to stay consistent.

---

## 3. Java (Core API — `influora-api/`)

Runtime: **Java 21** (Temurin), **Spring Boot 3.3.5**, built with **Maven 3.9+**. There is **no Maven wrapper (`mvnw`)** in this project — you need Maven installed on your machine (or use the Docker build, which brings its own).

### Spring Boot starters

| Dependency | Purpose |
|---|---|
| `spring-boot-starter-web` | REST controllers, the embedded Tomcat server. |
| `spring-boot-starter-actuator` | Health/metrics endpoints (`/actuator/health`, `/api/v1/health`). |
| `spring-boot-starter-data-jpa` | ORM / database access (Hibernate). |
| `spring-boot-starter-data-redis` | Redis integration. |
| `spring-boot-starter-validation` | Bean validation (`@Valid`). |
| `spring-boot-starter-security` | Authentication & authorization. |
| `spring-boot-starter-test` (test) | JUnit, Mockito, Spring test support. |

### Auth / JWT

| Dependency | Version | Purpose |
|---|---|---|
| `io.jsonwebtoken:jjwt-api` / `jjwt-impl` / `jjwt-jackson` | 0.12.6 | Issue & verify JWTs (access/refresh tokens, JWKS publishing). |

### Database & migrations

| Dependency | Version | Purpose |
|---|---|---|
| `com.mysql:mysql-connector-j` | (managed) | MySQL JDBC driver. |
| `org.flywaydb:flyway-core` + `flyway-mysql` | (managed) | Database schema migrations — runs 56 migrations on first boot. |

### Storage, payments, IDs, PDFs

| Dependency | Version | Purpose |
|---|---|---|
| `software.amazon.awssdk:s3` | 2.29.0 | S3 SDK — used against **Cloudflare R2** (S3-compatible) for file storage. |
| `com.razorpay:razorpay-java` | 1.4.6 | Payments & payouts (Razorpay / RazorpayX). |
| `com.github.librepdf:openpdf` | 1.3.30 | PDF generation — invoices, contracts, reports (`com.lowagie.text.*`). |
| `com.github.f4b6a3:ulid-creator` | 5.2.3 | ULID generation for entity IDs. |

### Scheduling

| Dependency | Version | Purpose |
|---|---|---|
| `net.javacrumbs.shedlock:shedlock-spring` + `shedlock-provider-jdbc-template` | 5.16.0 | Distributed lock so scheduled jobs run once across multiple instances. |

### Test-only

| Dependency | Version | Purpose |
|---|---|---|
| `org.testcontainers:testcontainers` / `junit-jupiter` / `mysql` | 1.19.8 (from parent BOM) | Spins up a real MySQL in Docker for integration tests. |

> Most Spring/Flyway/MySQL versions are **not** pinned in `pom.xml` — they're inherited from `spring-boot-starter-parent:3.3.5`'s dependency management. Only the third-party libs above carry explicit versions.

---

## 4. Infrastructure services (via Docker)

Not "libraries," but the app needs these running:

| Service | Image | Used by | Local dev? |
|---|---|---|---|
| MySQL 8 | `mysql:8.0` | Java API (primary DB) | **Required** |
| ClamAV | `clamav/clamav:1.3` | Java API malware scan | Prod profile only (dev uses a no-op) |
| Redis | `redis:7-alpine` | Java API + Python spend counter | Optional locally (prod compose only) |

The repo's `docker-compose.yml` starts **MySQL + ClamAV** for local dev. Redis is only in the production compose (`BLUEPRINT/10 §6`).

---

See `RUN-LOCAL.md` for exactly how to install and start all three.
