# Priya gate pass 4 — Meera for Creators, Phase A (2026-09-03)

Result: 9 of 10 positive. Every round-3 negative re-verified closed in the files; Priya re-ran the targeted suites herself
(mvn -o test-compile clean; 191 targeted Java tests green; influora-ai 265/265 across prompt/costs/security; vitest 60/60 on the
creator components, hooks and settings pages; tsc --noEmit clean). Hand-diffed the 27 @JsonProperty names on CreatorContextResponse
against the 27-entry Python allow-list (exact match) and all 20 CreatorAgentPreferences columns against V73 + the three follow-up
migrations (all present, correct types, NOT NULL defaults). Everything marked positive is "live smoke pending" (no Docker, no MySQL,
no running backend on the build machine; MeeraCreatorPhaseABootValidationTest skips by design here).

Note: the scratchpad questions file was wiped by a session restart, so Priya reconstructed the ten questions from SPEC.md A1-A10 and
the builders' in-code "Priya Qn" markers. Wording is hers; scope matches the tester's original ten.

| # | Topic | Verdict | Summary |
|---|-------|---------|---------|
| 1 | Day-one onboarding greeting persisted, in the creator's language | NEGATIVE | Greeting persisted correctly (ASSISTANT AiMessage, 0 credits, rollup bumped) but hardcoded English; never reads creator_language (V73 default hi-IN). Frontend Hindi greeting at MeeraCopilotChat.tsx:72 is now unreachable. Fix: pass prefs.getCreatorLanguage() into startOrResumeForCreator, emit the Hindi copy for "hi*", add hi-IN test cases. Area: backend. |
| 2 | Creator can reach and hold a conversation: routes, voice, full context, rollback flag | POSITIVE | /creator/meera/voice/speak and /voice/transcribe mirror the brand routes, rate-limited; frontend voice hooks report supported for creators; agency_name reaches the prompt and is stripped from brand payloads; MEERA_CREATOR_ENABLED guards all creator agent, Meera and public verified routes with 404 FEATURE_DISABLED, declared in application.yml, env.example and both deploy compose files; frontend degrades calmly. |
| 3 | DPDP consent gate blocks before any persistence; re-consent on notice change | POSITIVE | requireConsent runs first in Spring on every data-touching route; Python check fails closed as defence in depth; consent_version compared against CURRENT_CONSENT_VERSION; V20260903160000 backfills v1. |
| 4 | Info barrier | POSITIVE | InfoBarrierTest 1/1, InfoBarrierRuntimeTest 3/3; brand path never touches CreatorAgentPreferencesRepository; creator Block B allow-listed; brand deny-list includes floors, identity, agency_name; identity is two booleans only. |
| 5 | Structured deal terms and end-brand fields, legacy rows | POSITIVE | V72 additive with DB defaults; applyDealTermsIfPresent leaves absent terms untouched; DealServiceTest 66/66, CampaignMapperTest 2/2; frontend types match NON_NULL DTO. |
| 6 | DPDP data rights: list, export, delete conversations; write side populates the list | POSITIVE | recordTurnForUser on greeting and on CREATOR assistant writeback; ownership via MeeraCreatorConversationRepository scoped to the creator; UI table, JSON export, AlertDialog delete. |
| 7 | Per-creator monthly AI spend cap | POSITIVE | Gate before provider call, Redis-atomic hold, ai_monthly_cap_usd override written by Spring and read by Python; worker_guard refuses >1 worker without Redis; /readyz reports creator_cap_scope. |
| 8 | Settings round-trip and reach the prompt; timezone and currency | POSITIVE | working_hours_timezone and floor_currency persisted with ZoneId/Currency validation, rendered in settings and prompt; TS interface declares the three fields and omits the server-owned pair on PUT; roundtrip test 2/2. |
| 9 | Migrations on stock MySQL 8, schema matches entities, legacy rows survive | POSITIVE | V73/V74 carry ENGINE/CHARSET/COLLATE clause; boot-validate Testcontainers test exists and asserts the container collation is not unicode_ci; legacy-row tests pass; hand column diff exact. |
| 10 | Public verified metrics safe, honest, throttled | POSITIVE | Allow-list DTO, NON_NULL boxed metrics, nullable TS types with dash fallbacks, IP-keyed 30/window bucket, GET-scoped permitAll, behind the feature flag. |

Caveats recorded by Priya (not code defects): all positives are live-smoke pending; target/surefire-reports carried three failures
from a concurrent session's 12:45 run (ConversionTrackingServiceTest, WooCommerceWebhookControllerTest, KabirShopifySiblingProbeTest).
Meera's later full run (2256 tests) shows only the first two, both in files untouched by this task; the Kabir probe class no longer
exists in the source tree, so that report is stale.
