# Wave E4 final adversarial re-confirmation — Kabir (capstone close-out)

## Scope

Second-pass adversarial re-attack of my own two Wave E4 capstone findings, after Kavya's QA
(performed by the orchestrator directly, per `wiki/errors/wave-e4-hmac-and-redirect-fix-QA.md`,
following 3 failed sub-agent dispatch attempts) approved both fixes with two non-blocking
advisories. I re-read the actual source directly (not the QA writeup alone) and additionally ran
standalone `java.net.URI` probes to empirically settle the scheme-parsing advisory rather than
relying on stated belief.

Files re-read directly: `ConversionWebhookController.java`, `ConversionWebhookSignatureVerifier.java`,
`ConversionWebhookSecretService.java`, `CampaignLinkService.java`, `CampaignLinkServiceTest.java`.

## Finding 1 (HMAC on ConversionWebhookController) — RECONFIRMED APPROVED

Independently re-verified, not just re-read the QA's claims:

- **Trust boundary**: `verifySignatureOrReject` is the sole gate; `decryptSecretForWorkspace`
  returns `null` (never throws) on missing config, `verify()` fails closed on null/blank secret or
  signature — collapses to one `INVALID_WEBHOOK_SIGNATURE`/401 for all three causes. Confirmed by
  direct read of `ConversionWebhookSignatureVerifier.verify` (lines 43-52) and
  `ConversionWebhookSecretService.decryptSecretForWorkspace` (lines 153-161).
- **Constant-time compare**: `constantTimeEquals` in the verifier does length-check-then-XOR-accumulate
  over every char — standard, correct.
- **Encryption**: AES-256-GCM, 32-byte key enforced at startup (`decodeKey` throws if not 32 bytes),
  random 12-byte IV per encryption, IV prepended to ciphertext before base64 — correct GCM usage,
  matches the codebase's established pattern.
- **Two-hop workspace resolution**: `resolveWorkspaceForUtmCampaign` (lines 309-319) is a clean
  `Optional` chain, returns `null` on either hop failing, never throws — cannot be used to
  distinguish "UTM id missing" from "campaign missing" from "workspace missing."

### Advisory 1 resolution — parse-before-verify ordering

Verdict: **low-risk, confirmed by direct inspection, not blocking.**

The only pre-verification parsing is `MAPPER.readValue(rawJson, XxxWebhookRequest.class)` against a
locally-instantiated, default-configuration `ObjectMapper` (`private static final ObjectMapper
MAPPER = new ObjectMapper();`, line 144) deserializing into a fixed-shape Java record with no
polymorphic typing, no `@JsonTypeInfo`, no custom deserializers, and no enabled default-typing
anywhere in this class. The classic deserialization gadget-chain risk that motivates "verify raw
bytes before touching them" requires either polymorphic/default typing or attacker-reachable class
graphs — neither condition holds here. This is structurally different from an attacker choosing the
target class; the target class is hardcoded per endpoint. I looked for but did not find any app-level
`server.max-http-request-header-size`/body-size override specific to this controller — it inherits
whatever platform-wide default the embedded server (and `ShopifyWebhookController`/
`WooCommerceWebhookController`, which have the same unbounded-body exposure ahead of their own
verify-first parsing of headers, though not body) already carries. That means this fix introduces
no *new* DoS surface beyond what already exists platform-wide; it is not a regression specific to
this change.

On the documentation-accuracy sub-question: yes, the javadoc's claim to "mirror the PROVEN
discipline... verify the RAW request body BEFORE any parsing/dispatch" is factually inaccurate as
written — the code demonstrably parses (partially, via full Jackson deserialization) before
verifying. I recommend fixing the javadoc for the same reason I'd flag any other javadoc/code
mismatch on security-critical code: the next engineer who reads "mirrors X exactly" and greps for
the verify-first pattern elsewhere will be misled about this endpoint's actual ordering guarantee,
even though the ordering itself is justified. This is a documentation-hygiene fix, not a rework
requirement — does not block sign-off.

### Advisory 2 (scheme-check case/whitespace coverage) — resolved empirically

I did not take "URI is believed to normalize case" on faith. I compiled and ran a standalone
`java.net.URI` probe (see commands below) against the exact inputs in question:

- `new URI("JavaScript:alert(1)")` → `getScheme()` returns `"JavaScript"` **verbatim, NOT
  case-normalized by URI itself.** The QA's stated belief that URI normalizes case is factually
  wrong in isolation — however, this does not matter for `validateBaseUrl`'s actual correctness,
  because the code never relies on URI-level normalization. Line 255-256 explicitly does
  `scheme.toLowerCase(Locale.ROOT).equals("http")` / `.equals("https")` — the lowercasing is done
  in application code, not assumed from the parser. `JavaScript`/`DATA`/`FILE`/any-case-variant all
  correctly fail this explicit check and are rejected.
- Whitespace-padded schemes (`" javascript:..."`, `"\tjavascript:..."`, embedded tab
  `"java\tscript:..."`) all throw `URISyntaxException` at `new URI(...)` construction — caught by
  the existing `catch (URISyntaxException e)` block (line 249) and rejected as `INVALID_BASE_URL`.
  No bypass.
- I additionally probed host-manipulation angles beyond what QA tested: `http:evil.com`,
  `https:evil.com`, `http:///`, `http:///path`, `//evil.com`, `javascript://alert(1)`,
  `javascript:%0aalert(1)`. Every one either has `scheme=null` (rejected by the scheme check) or
  `host=null`/blank (rejected by the subsequent `uri.getHost() == null || isBlank()` check, lines
  261-263). No combination reaches a non-http(s) destination or an empty-host http(s) URL through
  the `Location` header.

**Verdict on Advisory 2: the underlying protection is sound and independently re-verified by me,
not merely re-asserted from the QA's belief.** The test-coverage gap is real (no explicit test
asserts case-variant/whitespace rejection) and worth closing as a documentation/regression-proofing
matter, but I found zero exploitable bypass after actively trying. Non-blocking, as QA said —
now with empirical confirmation instead of "believed to."

Probe commands used (for reproducibility): compiled a throwaway `UriSchemeTest.java`/
`UriSchemeTest2.java` in the scratchpad calling `new URI(input)` and printing `getScheme()`/
`getHost()` for ~25 inputs including case variants, leading/embedded whitespace, encoded
whitespace, protocol-relative (`//evil.com`), and scheme-without-authority (`http:evil.com`,
`https:evil.com`) forms.

## Finding 2 (open redirect) — RECONFIRMED APPROVED

- `validateBaseUrl` is the literal first statement in `createTrackingLink` (line 123), runs before
  any repository access — confirmed by direct read, not inference.
- `buildTrackingUrl` (lines 212-225) is pure string concatenation of UTM query params only — no
  Influora-owned credential, session token, or auth material is ever appended to the redirect
  target, so http being allowed alongside https introduces no credential-leak/mixed-content
  concern, as QA and the original fix javadoc both state.
- No secondary write path to `UtmCampaign.baseUrl`/`fullTrackingUrl` exists — `buildAndSaveTrackingLink`
  is private and only called from `createTrackingLink`'s single `orElseGet`.
- Test file confirms workspace-isolation and not-found coverage exists alongside the scheme
  rejection tests (`CampaignLinkServiceTest`, class javadoc explicitly calls out the
  Kabir-flagged workspace-isolation concern as a priority, line 34-37).

## Final verdict

**Both fixes APPROVED. Wave E4 closed, no blocking findings from this final adversarial pass.**

Both advisories are confirmed non-blocking, with Advisory 2 now upgraded from "believed correct"
to "empirically verified correct against ~25 adversarial inputs including case variants, whitespace
padding, and host-obfuscation tricks, with zero bypass found." Advisory 1 (javadoc overclaim) is a
real but cosmetic documentation-accuracy issue — recommend a follow-up doc-only patch to
`ConversionWebhookController`'s class javadoc (soften "mirrors the PROVEN discipline... verify the
RAW request body BEFORE any parsing/dispatch" to something like "resolves workspace identity via a
necessary, structurally-limited partial parse into a fixed-shape record, then verifies, then trusts
fully" per Wave E4 QA's own suggested wording) but this does not block sign-off or require code
rework.

Full suite (624 tests / 0 failures / 1 known unrelated Docker error) taken as given from Kavya's QA
pass — not independently re-run in this confirmation, since no code changes resulted from this
review.

Routing: Meera for final live-verify. This closes Wave E4.
