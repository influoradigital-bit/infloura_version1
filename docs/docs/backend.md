# Backend

The backend is `influora-api`: a Spring Boot 3.3.5 monolith on Java 21, built with Maven, package root `com.influora`. This document explains its layering, package responsibilities, conventions, and cross-cutting machinery so you can navigate and extend it confidently.

Entry point: `com.influora.InfluoraApiApplication` (`@SpringBootApplication`, `@EnableScheduling`, `@EnableAsync`). HTTP context path: `/api/v1`.

---

## Layering

The code follows a strict **web → service → repository → domain** flow.

```
web/          HTTP controllers + DTOs — the only layer that knows about HTTP
service/      business logic, transactions, orchestration
repository/   Spring Data JPA interfaces + JPA Specifications
domain/       entity/ (JPA @Entity) and enums/
```

Supporting packages:

```
security/     Spring Security filters, JWT, cookies, TOTP, plan gating, service-mesh auth
integration/  outbound clients to external systems (see external-services.md)
job/          @Scheduled background jobs
config/       @Configuration beans + @ConfigurationProperties
common/       reusable utilities: ApiResponse, PasswordPolicy, TextSanitizer, ProofObjectKeys, ...
```

### The web layer

62 `@RestController` classes under `web/`, grouped by audience: public/brand (`CampaignController`, `WalletController`, ...), creator (`Creator*Controller`), admin (`Admin*Controller`), integration webhooks (`RazorpayWebhookController`, `ShopifyWebhookController`, ...), and internal (`MeeraInternalController`). Request/response DTOs live under `web/dto/<domain>/`.

Controllers:

- Take `@AuthenticationPrincipal AuthPrincipal` (or `InternalPrincipal`) rather than reading the security context manually.
- Validate input with `@Valid` on Jakarta-Validation-annotated DTOs.
- Delegate immediately to a service; they contain no business logic.
- Wrap responses in `common.ApiResponse.ok(data, meta?)` — **except admin controllers**, which return raw DTOs and use headers (`X-Total-Count`, `X-Page`, `X-Page-Size`) for pagination. This inconsistency is intentional but flagged in code.

### The service layer

`@Service` classes hold all business rules and own `@Transactional` boundaries. Key patterns:

- **Tenant resolution & authorization happen here, not in filters.** `BrandContextService.requireBrandWorkspace(principal)` / `requireMember` / `requireRole(...)` and `CreatorContextService.requireCreatorProfile(principal)` resolve and authorize the caller. Path-param ids are validated against the caller's tenant (`findByIdAndWorkspaceId`, `findByIdAndCreatorId`); a foreign id returns a generic `NOT_FOUND`.
- **Idempotency** for money and other critical writes: `IdempotencyService.executeOnce(key, workspaceId, scope, action)` uses an insert-first-wins record (`idempotency_keys` table, composite PK `scope:workspaceId:key`) plus domain-table unique constraints.
- **Pessimistic locks** where correctness demands it: `findByIdForUpdate` on wallets, invoice sequences, and the campaign row during the publish-fee charge.
- **Optimistic locks** (`@Version`) on singletons and concurrency-sensitive rows: `PlatformFeeConfig`, `Subscription`, `Dispute`.

Services are organized into sub-packages by domain: `service/admin`, `service/analytics`, `service/billing`, `service/meera` (+ `service/meera/tool`), `service/notification`, `service/payout`, `service/portfolio`, `service/scoring`, `service/tracking`, `service/trendspark`, `service/verification`.

### The repository layer

Spring Data JPA interfaces. Complex/faceted queries use **JPA Specifications** (`CreatorProfileSpecifications`, `CampaignSpecs`, `SupportTicketSpecs`) composed with `combine(...)`. Repositories expose tenant-scoped finders (`findByIdAndWorkspaceId`), locking finders (`findByIdForUpdate`), and atomic updates (`tryDecrement` for AI credits, `revokeAllForUser`, `incrementUsageCount`).

### The domain layer

- `domain/entity/` — ~70 `@Entity` classes. Entities are **rich**: they encode state transitions as methods (`Deliverable.applyApprove()`, `Contract.recordBrandSignature()`, `EscrowHold.markFunded()`, `Dispute.resolve(...)`), guard invalid transitions, and expose no raw setters for sensitive fields (encrypted tokens, balances). Balances are mutated only via `Wallet.applyBalanceDelta(delta)`.
- `domain/enums/` — ~90 enums that are the vocabulary of the system (statuses, types, roles). Several mirror frontend TypeScript unions exactly (kept in lockstep by convention).

---

## Security machinery (`security/`)

Spring Security is configured in `config/SecurityConfig` as a single stateless (`SessionCreationPolicy.STATELESS`) filter chain. Custom filters, in runtime order:

1. `AuthRateLimitFilter` — in-memory fixed-window rate limiting on the unauthenticated auth surface and per-user write buckets.
2. `InternalServiceTokenFilter` — guards `/internal/**` (service JWT + HMAC).
3. `JwtAuthenticationFilter` — Bearer JWT → `AuthPrincipal`.
4. `PlanGateFilter` — resolves the active plan for BRAND principals into request attributes.

Method-level gates: `@RequiresPlan` + `PlanGateInterceptor` (feature entitlement → 402), `AnalyticsUsageCapInterceptor` (Free-tier deep-dive cap → 402). Full detail in [authentication.md](authentication.md) and [authorization.md](authorization.md).

---

## Integration layer (`integration/`)

Outbound clients, each self-contained with its own DTOs, exceptions, and (where relevant) OAuth state store and webhook signature verifier:

`integration/ai` (Brand-Safety + TrendSpark clients), `integration/meta` (OAuth, Graph clients, insights, rate-limit tracker), `integration/razorpay` (`RazorpayClient` via SDK, `RazorpayXClient` via raw HttpClient, `WebhookSignatureVerifier`), `integration/shopify`, `integration/woocommerce`, `integration/tracking`, `integration/storage` (`R2StorageService`), `integration/msg91` (`Msg91EmailClient`).

Common posture: encrypt secrets with AES-256-GCM, never log token bytes/bodies, verify webhook signatures before parsing, fail closed when a required secret is unconfigured, and fall back to deterministic mock stubs in dev when a provider isn't configured. See [external-services.md](external-services.md).

---

## Configuration (`config/`)

`@ConfigurationProperties` classes bind `application.yml` sections: `RazorpayProperties` (`influora.razorpay`), `R2Properties` (`influora.r2`), `MeeraStreamProperties`, `InternalServiceTokenProperties`, `JwksSigningKeyProperties`, `CompanyTaxProperties`, `TrendSparkProperties`, `BrandSafetyAiProperties`, etc.

`SecretsStartupValidator` (`@PostConstruct`) fails the boot outside `dev` if any required secret is missing, still a dev-default, duplicated, or malformed (min 32 bytes; JWKS PEM must parse to EC; MFA key must be exactly 32 bytes; refresh-cookie `secure` must be true). This is why several config prefixes have blank defaults and eager beans that throw on blank keys — a misconfigured prod deploy will not start. See [environment.md](environment.md).

---

## Cross-cutting conventions

- **IDs**: 26-char ULIDs (`ulid-creator`), lexicographically sortable, generated in-app.
- **Money**: `BigDecimal` `DECIMAL(14,2)` in rupees; paise (`long`) only at the Razorpay boundary via `movePointRight(2).longValueExact()`.
- **Errors**: services throw `ApiException(code, message, httpStatus)`; a `GlobalExceptionHandler` maps them to the `ApiResponse` error envelope. Common codes: `*_NOT_FOUND`, `INSUFFICIENT_BALANCE`, `UPGRADE_REQUIRED` (402), `WRONG_USER_TYPE` (403), `*_CONFLICT` (409).
- **Sanitization**: free-text (notes, reasons, messages) passes through `TextSanitizer`.
- **Auditing**: `AuditLogService` writes metadata-only audit rows (never PII/message bodies) for admin and security-sensitive actions.
- **Events**: domain events implement a sealed `NotificationEvent` interface and are handled by `@Async @EventListener` methods in `NotificationListener`.

---

## Testing

- Backend: JUnit 5 + Spring Boot Test + **Testcontainers** (real MySQL) for integration tests. Migration validity is checked in CI (`flyway-validate`, `schema-check`).
- The Java tests are the authoritative examples for many flows (e.g. `MultipartConfigTest` locks the 500MB/1GB upload limits; `BrandSafetyAiClientTest` documents the AI contract).

See [developer-onboarding.md](developer-onboarding.md) for how to run it, and [coding-guidelines.md](coding-guidelines.md) for the conventions to follow when adding code.
