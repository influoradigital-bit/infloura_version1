# Folder Structure

Every major folder, why it exists, and how modules communicate. Repo root is a Vite frontend project with the Spring Boot backend nested under `influora-api/`.

## Repository root

```
/
├── src/                    React SPA source (the frontend app)
├── influora-api/           Spring Boot backend (Maven, com.influora)
├── trendspark/             n8n workflow + theme taxonomy (trend pipeline ops)
├── public/                 static frontend assets served as-is
├── styles/                 global CSS
├── docker/                 nginx.conf and container assets
├── ci/                     lighthouse-meera.mjs (perf CI script)
├── e2e/, test-results/, playwright-report/   Playwright scaffolding
├── scripts/                utility scripts
├── Dockerfile              frontend multi-stage build (node → nginx)
├── docker-compose.yml      local MySQL 8
├── vite.config.ts          Vite config (alias @, dev proxy /api/v1→8080, prod-mode guard)
├── package.json            frontend deps + scripts (dev/build/lint)
├── tsconfig*.json          TypeScript config (excludes dead app/ scaffold)
└── .github/workflows/      CI pipelines
```

## Frontend (`src/`)

```
src/
├── main.tsx                entry — createRoot → App
├── App.tsx                 route table + auth guards + layouts
├── pages/                  ~74 route components (kebab-case); brand pages are thin wrappers
│   ├── blog/ legal/ features/   marketing content pages
│   └── admin-console.tsx    nested admin router
├── components/             ~193 components
│   ├── ui/                 shadcn/Radix primitives + T1–T9 money/trust primitives
│   ├── 3d/                 three.js/R3F canvases (+ fallbacks)
│   ├── motion/             framer-motion components (+ reduced-motion branches)
│   ├── brand/ creator/     role dashboards; deal-room/ and timeline/ sub-sets
│   ├── feature/meera/      conversational AI workspace
│   ├── shared/ site/ analytics/ campaigns/ trendspark/
├── admin/                  self-contained admin mini-app
│   ├── pages/ components/  admin screens (users/campaigns/finance/support/moderation/disputes/billing)
│   ├── services/           api-contracts.ts (own client), websocket.ts
│   ├── hooks/ types/ utils/
├── hooks/                  data hooks (analytics/, brand/, creator/, trendspark/, root)
├── lib/                    api.ts (HTTP client), auth-session.ts, store.ts (zustand), utils, mappers, seo/, motion-config, demo/mock data
├── data/                   Meera copy/mock, motion tokens, stage config
├── content/                blog/ (markdown) + legal/ (8 policy docs)
├── types/                  domain types + speech shim
└── test/                   vitest setup (not fully wired)
```

**Communication**: page → hook (`{data,loading,error,refresh}` or react-query) → `api.<resource>.<method>()` → `HttpClient` (mock/live) → Spring. Zustand stores hold cross-component UI/domain state. The admin app uses its own client, not `lib/api.ts`. See [frontend.md](frontend.md).

## Backend (`influora-api/`)

```
influora-api/
├── pom.xml                 Maven (Spring Boot 3.3.5, deps)
└── src/main/
    ├── java/com/influora/
    │   ├── InfluoraApiApplication.java   @SpringBootApplication, @EnableScheduling/Async
    │   ├── web/            62 @RestControllers + web/dto/<domain>/
    │   ├── service/        business logic (+ admin/ analytics/ billing/ meera/ notification/
    │   │                    payout/ portfolio/ scoring/ tracking/ trendspark/ verification/)
    │   ├── repository/     Spring Data JPA + JPA Specifications
    │   ├── domain/
    │   │   ├── entity/     ~70 @Entity (rich, state-transition methods)
    │   │   └── enums/      ~90 enums (vocabulary; several mirror frontend unions)
    │   ├── security/       filters, JWT/JWKS, cookies, TOTP, plan gating, mesh auth
    │   ├── integration/    ai/ meta/ razorpay/ shopify/ woocommerce/ tracking/ storage/ msg91/
    │   ├── job/            @Scheduled background jobs
    │   ├── config/         @Configuration + @ConfigurationProperties
    │   └── common/         ApiResponse, PasswordPolicy, TextSanitizer, ProofObjectKeys, ...
    └── resources/
        ├── application.yml (+ -dev, -prod)
        ├── db/migration/   Flyway V*.sql (~74 migrations)
        └── common-passwords.txt
```

**Communication**: `web` → `service` → `repository` → `domain`. `security` filters wrap requests; `integration` clients are called by services; `job` classes call services on a schedule; `config` binds properties. Authorization and tenant scoping happen in `service`, not `web`. See [backend.md](backend.md).

## TrendSpark ops (`trendspark/`)

n8n workflow JSON (`trend-pull-workflow.json`), the `theme-tagger.js` script, and `theme-taxonomy.json` — the controlled theme vocabulary shared with the Java `ThemeMatchService`. This pipeline (not a Java job) populates the `trends` table daily. It is **not** a React app. See [features/trendspark.md](features/trendspark.md).

## Dead / legacy folders to be aware of

- `src/app/` + `next.config.mjs` — dead v0/Next scaffold; `vite build` ignores it and `tsconfig` excludes it. The app is Vite, not Next.js.
- `file_uploads` table (V1) — orphaned; superseded by `deliverables.files_json`.
- `payouts` table / `Payout` entity — dead code; payout state lives on `payment_milestones`.

See [known-limitations.md](known-limitations.md).
