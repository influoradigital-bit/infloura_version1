# Pipeline & Connection Questions

Q1. When `publish-images.yml` runs on GitHub Actions post-commit, how does it resolve the base image tags and ECR credentials, and what gates in `.proof-os/` block publication before it pushes to a registry that does not yet exist (Hostinger VPS or Utho)?

Q2. The proof-os ledger (`.proof-os/ledger/failures.jsonl`) records task phase transitions (`intake`, `produce`, `gate`, `verdict`), but SHARED_CONTEXT.md coordinates agent work via file paths and owner fields — how does the ledger state prevent concurrent agents from both writing to the same file domain in the shared checkout?

Q3. `docker-compose.yml` injects `INFLUORA_AI_INTERNAL_URL=host.docker.internal:8000` into the n8n container for trend-tag recovery wiring, but the FastAPI service at `influora-ai/app/` runs on the host and defines no startup health check — what ensures the container's `TREND_TAG_INGEST_SECRET` route does not fire before the Python app is ready?

Q4. The `.claude/launch.json` entry for the main dev server should start Vite on port 3000, but `application.yml:133` defaults `INFLUORA_WEB_BASE_URL` to `localhost:5173` — when does this mismatch get resolved (env var, override, or does it break every backend-generated link), and which team member detects it?

Q5. How does the Meta Graph API integration (`influora-api/src/main/java/com/influora/integration/meta/`) handle token refresh when a creator's 60-day Instagram token expires mid-request, given that `MetaTokenRefreshService` runs as background async but `InstagramInsightsClient.getProfile()` is a blocking REST call?

Q6. The Meera on-behalf JWT is verified twice per creator tool call (`resolveForWorkspace` then `requireScope`, both calling `parseOrReject`) according to SHARED_CONTEXT.md — is this double-verification still in `ConfirmLaunchExecutor` post-`92c81be`, or was it removed as a side effect of a perf fix to a different path?

Q7. Flyway migrations own the schema (`influora-api/src/main/resources/db/migration/V*.sql`), but `MeeraPhaseB0BootValidationTest` skips on machines without Docker — what guarantees that a schema change merged without running the validation test will not collide with a concurrent migration on the VPS, and when is this detected?

Q8. The gates in `.proof-os/gates/` (e.g., `build.sh`, `frontend.sh`, `eslint.sage.json`) each have their own entry point and can be invoked independently or as part of a full CI run, but `proof-os/gates/build.sh` line 19 calls `env_issue()` — if `CI=true` is not set, what is the fallback behavior and who is responsible for ensuring it passes before pushing?

Q9. When a creator calls `/creator/briefs` (POST from Wave 3 Meera Phase B0), the request enters `CreatorBriefService` which opens a transaction outside the database boundary for timeouts, calls the Anthropic Claude API, then writes in a separate transaction — if the API times out, does the paste reach the DB (and can be retried), and does the cost tracker record the failed call toward `AI_DAILY_SPEND_CEILING_USD`?

Q10. The `.cursor/rules/` directory mirrors `agents/vikram-backend.md` and `agents/meera-dbdevops.md` at startup, but SHARED_CONTEXT.md is the live task bus that agents edit in-session — how do concurrent agent sessions (both reading SHARED_CONTEXT.md) detect and resolve collisions when one agent publishes a commit that the other is still editing against?

Q11. `influora-ai/app/providers/claude.py` instantiates a Claude provider with prompt caching and circuit breaker, but which .env file holds `ANTHROPIC_API_KEY` in the Docker container on the Hostinger VPS, and does `generate-env.sh` inject `VOICE_AI_BASE_URL` into `application.yml` or only into shell exports?

Q12. The payment escrow pipeline routes through `EscrowService` → `CampaignServiceInvoiceService` → `WalletService`, but `SHARED_CONTEXT.md` documents that "GST Doc#2 loss is log.error-only" in Escrow — when the invoice is lost during release, what tells the creator that their payout stalled, and what manual recovery does an admin need to perform?

Q13. The Razorpay Order API integration creates an order but does not yet persist webhook reconciliation state according to the audit ("`webhook has NO reconciliation fallback`"), so when Razorpay retries a webhook 3 times and Influora misses all of them, which service layer is responsible for polling Razorpay's order status API as the recovery path?

Q14. `MeeraController` dispatches tool executors via `tools_enabled` configuration, but when `scope` is clamped upward on a bad approval level, both the creator and brand can see the same tool result — which gate in `.proof-os/` catches this authorization bypass by auditing all six tool responses against the actor's claim, and does it run pre-deploy or only on audit?

Q15. When `vitest.gates.config.ts` and `.proof-os/gates/vitest.gates.config.ts` differ because they reference different fixture sets (unit vs integration), how does `npm run test:gates` resolve which config to use, and if both exist, which takes precedence: the project-level or the proof-os-level?

