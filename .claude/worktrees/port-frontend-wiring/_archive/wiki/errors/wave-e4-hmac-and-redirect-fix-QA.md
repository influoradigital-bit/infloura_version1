# Wave E4 capstone findings — QA (performed by orchestrator directly, after 3 consecutive sub-agent dispatch failures on connection errors)

## Context

Three separate attempts to dispatch a Kavya sub-agent for this QA pass all failed with connection errors mid-run, producing zero disk output each time. Rather than continue retrying into what may be a task-specific failure mode, the orchestrator performed this QA directly via Read/Bash, applying the same verification checklist that was given to the sub-agent.

## Finding 1: HMAC signature verification on `ConversionWebhookController` — **APPROVED**

Reviewed: `ConversionWebhookController.java`, `ConversionWebhookSignatureVerifier.java`, `ConversionWebhookSecretService.java`, `ConversionWebhookSecretController.java`, migration V31.

1. **Trust model soundness — PASS.** `ConversionWebhookSecretController.generate`/`revoke` both derive `workspaceId` via `brandContextService.requireBrandWorkspace(principal)` — server-derived from the authenticated principal, never client-suppliable. No path exists for a brand to generate or retrieve another workspace's secret.
2. **Encryption — PASS.** AES-256-GCM, 32-byte key, distinct `influora.conversion-webhook.token-encryption-key` (per `application.yml` comment), matches `WooCommerceIntegrationService`'s discipline exactly. Plaintext secret returned exactly once at generation time, never persisted/logged in recoverable form (`generate()`'s own javadoc states this and the code matches: only `encrypted` is passed to `repository.save`).
3. **Enumeration-oracle discipline — PASS.** `verifySignatureOrReject` throws the identical `INVALID_WEBHOOK_SIGNATURE` (401) whether `workspaceId` is null (unresolved identifier), the workspace has no configured secret (`decryptSecretForWorkspace` returns `null` rather than throwing), or the signature genuinely fails (`ConversionWebhookSignatureVerifier.verify` fails closed on null/blank secret or signature). Confirmed by direct code read, not just the javadoc's claim.
4. **Two-hop workspace resolution — PASS.** `resolveWorkspaceForUtmCampaign` traces `UtmCampaign.campaignId → Campaign.workspaceId`. Confirmed `UtmCampaign` entity genuinely has no direct `workspace_id` column (read the entity directly) — the two-hop join is structurally necessary, not an invented complication. No path lets workspace A's secret verify against workspace B's UTM campaign, since the secret used for verification is resolved from the SAME UTM campaign's owning workspace, not caller-supplied.
5. **Amount-entropy closure claim — PASS, reasoning holds.** A brand with their own valid secret attempting amount-entropy pre-poisoning could only affect their OWN workspace's conversion data (the derived idempotency key is workspace/campaign-scoped downstream in `ConversionTrackingService`) — self-inflicted risk only, same class of reasoning already accepted for D2's weak-secret finding.
6. **Tests — PASS.** `ConversionWebhookControllerTest` (21), `ConversionWebhookSignatureVerifierTest` (10), `ConversionWebhookSecretServiceTest` (13) all pass. Full suite: 624 tests, 0 failures, 1 error (the known, pre-existing, unrelated `DatabaseConstraintIntegrationTest` Docker-environment gap from Wave E3).

### Advisory (non-blocking): javadoc overclaims parse-ordering parity with Shopify/WooCommerce

The class javadoc states this "mirrors the PROVEN discipline... verify the RAW request body BEFORE any parsing/dispatch." In actual code, `redeemCoupon`/`recordConversion` call `parseRedemptionPayload`/`parseConversionPayload` (full Jackson `readValue` deserialization of the entire request record) **before** `verifySignatureOrReject` runs — not after, unlike Shopify/WooCommerce where the raw body is verified first and parsed only after signature success.

This is **structurally necessary here, not an oversight**: unlike Shopify (workspace identity from a header) or WooCommerce (workspace identity from a header), this endpoint's only identity signal (`code` / `utmCampaignId`) lives INSIDE the JSON body, so some parsing before verification is unavoidable to know which secret to check against. The javadoc's "mirrors X exactly" framing is therefore not accurate — it should say "resolves identity via a necessary partial parse, then verifies, then trusts" rather than claiming full ordering parity.

Practical risk is low (Jackson deserializing a small, fixed-shape record is fast and well-hardened against the classic gadget-chain/DoS concerns that motivated the verify-first pattern in the first place), but recommend either (a) fixing the javadoc to accurately describe this architectural necessity, or (b) tightening further by extracting just the identity field via a cheaper mechanism (e.g. a raw JSON path read) before full deserialization, if Kabir/Priya want zero daylight between claim and code. Not blocking.

## Finding 2: Open redirect fix on `/track/click/{utmCampaignId}` — **APPROVED**

Reviewed: `CampaignLinkService.java`'s `validateBaseUrl`/`createTrackingLink`, `CampaignLinkServiceTest.java`.

1. **Validation runs first — PASS.** `validateBaseUrl(request.baseUrl())` is the first statement in `createTrackingLink`, before any repository lookup or persistence.
2. **No bypass path — PASS.** Grepped for other writers of `UtmCampaign.fullTrackingUrl`/`baseUrl` — `buildTrackingUrl` (called only from `createTrackingLink`) is the sole construction site; no update/edit/bulk-import endpoint exists for tracking links in this codebase.
3. **http/https-both-allowed reasoning — sound.** Confirmed via `buildTrackingUrl` that only UTM query parameters are appended to `baseUrl` — no credential, token, or session identifier of Influora's own is ever embedded in the constructed URL, so there is no mixed-content/credential-leak concern that would argue for https-only.
4. **Tests — PASS.** 21 `CampaignLinkServiceTest` cases (9 new) covering `javascript:`, `data:`, `file:`, scheme-less, null, and malformed base URLs, all correctly rejected with `INVALID_BASE_URL`/400; legitimate `http://`/`https://` still accepted. Full suite green (see above).

### Advisory (non-blocking): scheme-check case/variant coverage

Tests cover `javascript:`/`data:`/`file:` in their canonical lowercase form. Recommend a follow-up test asserting rejection of case-variant (`JavaScript:`) and whitespace-padded (` javascript:`) schemes, since `java.net.URI`'s scheme parsing may or may not normalize case before the equality check — worth an explicit assertion rather than relying on incidental `URI` behavior. Did not find evidence of an actual bypass in this review (the implementation compares `uri.getScheme()` case-insensitively per the report), but recommend the test coverage be made explicit for future-proofing. Not blocking.

## Verdict

**Both fixes: APPROVED.** No blocking findings. Two advisories logged (javadoc accuracy on parse-ordering; scheme-check test coverage breadth) — recommend Kabir factor these into his adversarial re-confirm but neither rises to a level requiring rework before proceeding.

Route to Kabir for adversarial re-confirm of both (his own capstone findings), then Meera for live-verify.
