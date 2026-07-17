# Influora API (Spring Boot)

MySQL 8 + Cloudflare R2 · Base path `/api/v1`

## Prerequisites

- Java 21
- Maven 3.9+
- Docker (for local MySQL)

## Quick start

```bash
# From repo root
docker compose up -d

cd influora-api
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

Health: `GET http://localhost:8080/api/v1/health`

## Creator discovery (Phase 4)

| Method | Path | Auth |
|--------|------|------|
| GET | `/creators?q&platforms&city&verticals&minFollowers&maxFollowers&minRate&maxRate&page&limit&sortBy` | Brand |
| GET | `/creators/:id` | Brand |
| POST | `/creators/:id/save` | Brand — body `{ "saved": true }` |
| POST | `/creators/:id/invite` | Brand — body `{ "campaignId", "message?" }` |

Dev seed: 5 discoverable creators (`priya.creates@demo.influora.com`, etc.). Password `Password@123` if you log in as creator.

## Campaign endpoints (Phase 3)

| Method | Path | Auth / role |
|--------|------|-------------|
| GET | `/campaigns?page&limit&status&search&sortBy&sortOrder` | Brand member |
| GET | `/campaigns/:id` | Brand member |
| POST | `/campaigns` | Brand member |
| PATCH | `/campaigns/:id` | OWNER, ADMIN, MANAGER |
| DELETE | `/campaigns/:id` | OWNER only (DRAFT) |
| POST | `/campaigns/:id/duplicate` | OWNER, ADMIN, MANAGER |

`status` query: comma-separated (`ACTIVE,DRAFT`) or `ALL`.  
`ACTIVE` requires workspace `verification_status = VERIFIED`.

## Onboarding endpoints (Phase 2)

| Method | Path | Auth |
|--------|------|------|
| POST | `/onboarding/brand/company` | Bearer (brand) |
| POST | `/onboarding/brand/complete` | Bearer (brand) |
| POST | `/onboarding/brand/kyc` | Bearer (brand) |
| GET | `/workspaces/slug-check?slug=` | Optional Bearer |

## Auth endpoints (Phase 1)

| Method | Path | Auth |
|--------|------|------|
| POST | `/auth/brand/register` | No |
| POST | `/auth/brand/login` | No |
| POST | `/auth/refresh` | No |
| POST | `/auth/logout` | Bearer |
| POST | `/auth/forgot-password` | No |
| POST | `/auth/reset-password` | No |
| GET | `/users/me` | Bearer |
| PATCH | `/users/me` | Bearer |

## Example: brand register

```bash
curl -s -X POST http://localhost:8080/api/v1/auth/brand/register \
  -H "Content-Type: application/json" \
  -d "{\"firstName\":\"Ananya\",\"lastName\":\"Sharma\",\"email\":\"ananya@brandco.com\",\"password\":\"SecurePass@123\",\"companyName\":\"BrandCo India\",\"industry\":\"fashion\",\"companySize\":\"6-20\",\"acceptedTerms\":true}"
```

## Configuration

Copy `.env.example` values into your environment or IDE run config.  
**Dev profile** (`application-dev.yml`) disables email verification on login for easier testing.
