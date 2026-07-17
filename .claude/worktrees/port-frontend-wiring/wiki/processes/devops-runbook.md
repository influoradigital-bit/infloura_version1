# DevOps Runbook

**Owner:** Meera (DB/DevOps) | **Last updated:** 2026-07-11

---

## CI/CD Pipeline

### GitHub Actions Workflows

Location: `.github/workflows/`

| Workflow | File | Trigger | Purpose |
|----------|------|---------|---------|
| Schema Drift Check | `schema-check.yml` | PR touching schemas, push to main | Detect Python ↔ Java schema mismatches |
| Backend Tests | `backend-tests.yml` | PR touching influora-api, push to main | Run Java/Spring Boot test suite |
| AI Service Tests | `ai-tests.yml` | PR touching influora-ai, push to main | Run FastAPI/Python tests |
| Flyway Validation | `flyway-validate.yml` | PR touching db/migration, push to main | Validate migration naming/checksums |
| Lighthouse (existing) | `lighthouse-meera.yml` | Manual/scheduled | Performance metrics |

### Schema Drift Check (Priority 1)

**What it checks:**
1. Python `TOOL_SCHEMAS` tool names match Java `MeeraToolName` enum
2. `calculate_budget.goal` enum matches `create_campaign.campaign_type` enum
3. (Future) Both match Java `CampaignIntentType` enum

**Known failing state (2026-07-11):**
- `calculate_budget.goal`: `["awareness", "conversion", "launch", "review"]` (4 lowercase)
- `create_campaign.campaign_type`: `["DIRECT", "HYPE", "REVIEW"]` (3 uppercase)
- Java canonical: `["HYPE", "DIRECT", "REVIEW", "STANDARD"]` (4 uppercase)

**Fix required:** See `wiki/processes/T4-SCHEMA-DRIFT-FIX-PROPOSAL.md`

**Local test:**
```bash
cd influora-ai
python -c "
import sys, json
sys.path.insert(0, 'app')
from tools.schemas import TOOL_SCHEMAS
calc = next(t for t in TOOL_SCHEMAS if t['name'] == 'calculate_budget')
create = next(t for t in TOOL_SCHEMAS if t['name'] == 'create_campaign')
goal = sorted(calc['input_schema']['properties']['goal']['enum'])
camp_type = sorted(create['input_schema']['properties']['campaign_type']['enum'])
if goal != camp_type:
    print('DRIFT:', goal, '!=', camp_type)
    sys.exit(1)
print('OK')
"
```

### Backend Tests

**What it runs:**
- Maven test suite (~100 Java test files)
- MySQL 8 + Redis services via GitHub Actions
- JUnit report generation

**Environment:**
- Java 21 (Temurin)
- MySQL 8.0
- Redis 7

**Local equivalent:**
```bash
cd influora-api
mvn clean test
```

### AI Service Tests

**What it runs:**
- pytest on `influora-ai/tests/**`
- Coverage report (uploaded to Codecov)
- Includes Kabir's K1 injection regression tests

**Environment:**
- Python 3.11
- pytest + pytest-cov + pytest-asyncio + httpx

**Local equivalent:**
```bash
cd influora-ai
pip install -r requirements.txt pytest pytest-cov pytest-asyncio httpx
pytest tests/ --verbose --cov=app
```

### Flyway Validation

**What it checks:**
1. Migration file naming: `V{number}__{lowercase_description}.sql`
2. Flyway checksums (detects modified migrations)
3. Dry-run applies all migrations
4. Version conflicts on PRs (detects duplicate version numbers)

**Local equivalent:**
```bash
cd influora-api
mvn flyway:info
mvn flyway:validate
```

---

## Database Operations

### Running Migrations (Development)

```bash
cd influora-api
mvn flyway:migrate
mvn flyway:info  # verify applied
```

### Creating a New Migration

1. **Check current version:**
   ```bash
   ls -1 influora-api/src/main/resources/db/migration/ | tail -1
   # Example output: V47__last_migration.sql
   ```

2. **Create next version:**
   ```bash
   # Next version = 48
   touch influora-api/src/main/resources/db/migration/V48__your_description.sql
   ```

3. **Migration template:**
   ```sql
   -- V48__your_description.sql
   --
   -- [Purpose: one-line summary]
   -- [Pattern: upsert-latest | immutable snapshot | event log]
   -- [See: wiki/tech/employees/meera-database-devops-spec.md §T1 for pattern guide]

   CREATE TABLE your_table (
     id VARCHAR(26) PRIMARY KEY,
     -- ... columns ...
     created_at DATETIME(6) NOT NULL,
     INDEX idx_your_table_field (field),
     CONSTRAINT fk_your_table_ref FOREIGN KEY (ref_id)
       REFERENCES other_table(id) ON DELETE CASCADE
   ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
   ```

4. **Document in schema-changes.md:**
   ```markdown
   ## Migration: V48__your_description
   Date: YYYY-MM-DD
   Author: [Agent] (schema) / Meera (ran migration)
   Changes: [summary]
   Status: ✅ Applied successfully
   ```

5. **Run migration:**
   ```bash
   mvn flyway:migrate
   ```

### Migration Rollback

**Development only — NEVER on production:**
```bash
mvn flyway:clean  # DANGER: drops all objects
mvn flyway:migrate  # reapply from scratch
```

**Production:** Write a new UP migration to reverse changes. Never rollback.

---

## Docker Services

### Service List

| Service | Container Name | Port | Purpose |
|---------|---------------|------|---------|
| n8n | `n8n` | 5678 | Workflow automation |
| Postiz | `postiz` | 3000 | Social media scheduling |
| Ollama | (host process) | 11434 | Local LLM (glm4:9b) |
| MySQL | (dev/CI only) | 3306 | Database |
| Redis | (dev/CI only) | 6379 | Cache |

### Common Operations

**Check all services:**
```bash
docker ps
```

**Restart n8n:**
```bash
docker restart n8n
docker logs n8n --tail 50  # check for errors
```

**Restart Postiz:**
```bash
docker restart postiz
docker logs postiz --tail 50
```

**Restart Ollama:**
```bash
# Ollama runs as host process, not Docker
ollama stop
ollama serve
```

**Check Ollama models:**
```bash
ollama list
# Should show: glm4:9b
```

### Troubleshooting

**n8n won't start:**
```bash
docker logs n8n
# Common issues:
# - Port 5678 in use: lsof -i :5678, kill process
# - Volume permission: check /opt/n8n/data ownership
```

**Postiz won't connect:**
```bash
docker logs postiz --tail 100
# Check for:
# - Database connection errors
# - Redis connection errors
# - Missing environment variables
```

**Ollama model not found:**
```bash
ollama pull glm4:9b
```

---

## Deployment

### Vercel (Frontend)

**Production deploy:**
```bash
# Only after Swapnil approves
vercel --prod
```

**Check deployment status:**
```bash
vercel ls
vercel inspect <deployment-url>
```

**Environment variables:**
Set via Vercel dashboard:
- `VITE_API_URL`: Backend API URL
- `VITE_ENV`: `production`

### Railway (Backend — if used)

**Deploy influora-api:**
```bash
# Typically via GitHub push to main
# Railway auto-deploys on merge
```

**Check logs:**
```bash
railway logs
```

**Environment variables:**
Set via Railway dashboard:
- `SPRING_PROFILES_ACTIVE`: `production`
- `SPRING_DATASOURCE_URL`: Production MySQL URL
- `REDIS_URL`: Production Redis URL
- (other Spring config)

---

## Monitoring & Alerts

### Daily Health Check

1. **Docker services running:**
   ```bash
   docker ps | grep -E 'n8n|postiz'
   ```

2. **Database reachable:**
   ```bash
   mysql -u influora -p -e "SELECT 1"
   ```

3. **Redis reachable:**
   ```bash
   redis-cli ping
   # Expected: PONG
   ```

4. **Ollama serving:**
   ```bash
   curl http://localhost:11434/api/tags
   # Should return JSON with models list
   ```

### Error Log Locations

| Service | Log Location |
|---------|-------------|
| Docker containers | `docker logs <container>` |
| Ollama | `~/.ollama/logs/` |
| CI failures | GitHub Actions UI |
| Build errors | `wiki/errors/` (manual documentation) |

### Staleness Alarms (Future)

**ReliabilityStatsJob (T3 — not yet built):**
```sql
SELECT MAX(computed_at) FROM creator_reliability_stats;
-- Alert if > NOW() - INTERVAL 48 HOUR
```

---

## Verification Protocol

See: `wiki/processes/verification-log.md`

**After every Kavya QA pass, run:**

```bash
# Step 1: Install dependencies
npm install

# Step 2: Type check
npx tsc --noEmit

# Step 3: Build
npm run build

# Step 4: Start dev server (background)
npm run dev &
sleep 10

# Step 5: Test API endpoints
curl -s http://localhost:3000/api/products | jq .
curl -s http://localhost:3000/api/health | jq .

# Step 6: Run tests (if exists)
npm run test

# Step 7: Stop dev server
kill %1
```

**Write results to SHARED_CONTEXT.md:**
```markdown
## Meera Verification Report — [timestamp]
Task: [task name]
Files verified: [list]

### Results
npm install: ✅ PASS
tsc --noEmit: ✅ PASS (0 errors)
npm run build: ✅ PASS
API tests: ✅ 200 OK
npm test: ✅ PASS (N/M tests)

### VERDICT: ✅ ALL PASS — Ready for Swapnil review
```

---

## Database Schema Conventions

**From:** `wiki/tech/employees/meera-database-devops-spec.md`

| Rule | Value |
|------|-------|
| Primary keys | `VARCHAR(26)` ULID via `Ulids.newUlid()` |
| Never | `BIGINT AUTO_INCREMENT` |
| Engine | `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci` |
| Timestamp cols | `DATETIME(6)` UTC, set by `Instant.now()`, **no DB default** |
| Money | `DECIMAL(12,2)` line items, `DECIMAL(14,2)` balances |
| Percentages | `DECIMAL(5,2)` |
| JSON | MySQL `JSON`, never JSONB |

---

## Weekly Tasks

- [ ] Monday: Check CI build status (all workflows green?)
- [ ] Tuesday: Review pending migrations (any PRs touching `db/migration/`?)
- [ ] Wednesday: Docker health check (restart if needed)
- [ ] Thursday: Review `wiki/errors/` for new deployment issues
- [ ] Friday: System health report to Arjun (Docker + DB + CI status)

---

## Escalation

**Report to Arjun when:**
- Build fails and fix isn't obvious
- Database migration has unexpected side effects
- Docker service won't start after restart
- Code fails tests even after developer claimed it's fixed
- CI workflow consistently failing (not a code issue)

**NEVER escalate directly to Swapnil** — go through Arjun.

---

## Quick Reference

**CI workflow trigger paths:**
- `.github/workflows/schema-check.yml` → `influora-ai/app/tools/schemas.py`, `influora-api/.../MeeraToolName.java`
- `.github/workflows/backend-tests.yml` → `influora-api/**`
- `.github/workflows/ai-tests.yml` → `influora-ai/**`
- `.github/workflows/flyway-validate.yml` → `influora-api/src/main/resources/db/migration/**`

**Documentation I own:**
- `wiki/processes/verification-log.md` — verification reports
- `wiki/processes/schema-changes.md` — migration history
- `wiki/processes/devops-runbook.md` — this file

**Tools I use:**
- Ollama glm4:9b (local analysis)
- Terminal/bash (builds, tests, curl)
- Prisma CLI (future — currently using Flyway)
- Docker CLI (service management)
- Vercel CLI (deployments)
