# Tools Questions

Q1. The Utho deployment README explicitly states "THIS DESIGN WAS NEVER BUILT" and documents that the live box runs influora-api as bare `docker run --network host --env-file` outside compose, not the designed Caddy architecture—what is the recovery procedure when `/usr/local/App/influora/influora.env` diverges from the current running container's environment, and how is this monitored?

Q2. Session management changed from localStorage to in-memory-only access tokens with SameSite=Strict refresh cookies (F-0551)—if Influora or the API ever move to a different registrable domain from influora.in (e.g., staging on influora.app, or API on api.influora.com), every logged-in user silently logs out on reload and cannot recover: what guardrails prevent this misconfiguration at deploy time?

Q3. Vite bundles VITE_API_BASE_URL and VITE_PAYMENTS_IN_ENABLED as static JS at build time, not at container runtime—publish-images.yml passes these as --build-arg, but .env.production also contains the values: which wins, and what forces a rebuild if ops changes VITE_API_BASE_URL without touching the workflow file?

Q4. The Razorpay key-id is marked REPLACE_ME in deploy/utho/generate-env.sh:59 (RAZORPAY_KEY_ID is still unfunded/unprovisioned)—F-0390 enabled VITE_PAYMENTS_IN_ENABLED=true in .env.production, so users now see "Add Funds" instead of "Payments Unavailable": does the backend explicitly fail before issuing the request, or do users see an error only after the UI submits?

Q5. ClamAV container at 1.2 GB is the single largest service in docker-compose—it is @Profile("prod") so every upload fails closed if the container is down or misconfigured, yet the deploy guide says "Move to a dedicated server after concept is proven"—what is the upload failure rate SLA and rollback plan if clamd becomes unresponsive?

Q6. The Utho box has 7.3 GB total RAM with 4 GB available, running Snapsby backend + MySQL 8.4.7 (untested-version warning from Flyway 10.10.0) + Influora's MySQL 8.0 + Redis + ClamAV + n8n—what is the OOM kill sequence, and is there a memory monitor that alerts before the kernel chooses a victim?

Q7. n8n (version 1.62.1) runs the trend-pull workflow daily and injects INFLUORA_AI_INTERNAL_URL via environment ($env in the Code node)—how is the freshness of the trend-tag model guaranteed when the ingestion pipeline crashes mid-run, and does a failed run block the next scheduled run or queue independently?

Q8. Spring Boot actuator endpoint /actuator/health is exposed and health-checked—what is the granularity of liveness detection (HTTP timeout? exception types? DB pool saturation?), and does the orchestration layer (nginx/docker/systemd) replace the container on non-200, or does it wait for human intervention?

Q9. GitHub Actions publish-images.yml builds and pushes influora-web and influora-api to ghcr.io—if the build succeeds but the push to GHCR fails (auth revoked, network partition, rate limit), does the PR merge-blocker catch this, or does the prod Utho box attempt to pull a non-existent tag and serve stale code?

Q10. Flyway migrations are applied at Spring Boot startup with dependency on MySQL 8.0—if a migration hangs (e.g., a `LOCK TABLES` on a live-written table), the boot sequence blocks and the container never becomes healthy: what is the startup timeout, and does Kubernetes/Docker detect this or must an on-call operator manually restart?

Q11. The .proof-os/rc directory contains build state with ledger/failures.jsonl and journal.jsonl—are these version-controlled (gitignored), and if they are gitignored, how do concurrent CI workflows avoid race conditions when writing to these shared state files on the Utho box?

Q12. JWT secrets (JWT_ACCESS_SECRET, JWT_REFRESH_SECRET, MEERA_STREAM_SIGNING_SECRET) are generated once by generate-env.sh and baked into .env—if a secret is compromised and needs rotation, is there a graceful drain-and-restart procedure, or must all refresh tokens be invalidated with a hard logout of every user?

Q13. TRUSTED_PROXIES is hardcoded to 172.16.0.0/12 in generate-env.sh—if nginx or the Docker bridge gateway IP changes (e.g., subnet reconfiguration), the RemoteIpValve trusts client-supplied X-Forwarded-For entries, defeating IP-based rate limits (CR-11 Blocker-1): how is this verified after each deploy?

Q14. Redis is @DeprecatedForeverPatched and holds the daily AI spend ceiling (`AI_DAILY_SPEND_CEILING_USD`) and stream-token replay guard—if Redis becomes unavailable, does the app degrade gracefully to per-process memory (documented in influora-ai/requirements.txt), or does every AI request block until Redis recovers?

Q15. The live influora-api health endpoint is at https://api.influora.in/actuator/health, but .env.production documents that the SPA's actual bundle bakes VITE_API_BASE_URL=https://influora.in/api/v1 (apex, not api.influora.in subdomain), and 5 occurrences exist of api.influora.in with 0 in production code—is the published bundle actually verified to use https://influora.in/api/v1 on every deploy, or is there a risk a rebuild accidentally ships the old hardcoded value?
