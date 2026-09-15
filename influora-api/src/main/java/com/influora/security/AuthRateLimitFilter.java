package com.influora.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UriUtils;

/**
 * Fixed-window rate limiter for the unauthenticated auth surface (Kabir audit B3): login, register,
 * OTP send/verify, forgot/reset-password and refresh are all enumeration / brute-force / abuse
 * targets and today have no throttle.
 *
 * <p>Also covers the Meta OAuth connect surface (Kabir Meta OAuth Phase 1 review, P1 #3):
 * {@code GET /meta/oauth/authorize} and {@code GET /meta/oauth/callback} are both auth-required but
 * public-facing-shaped — {@code /callback} in particular triggers two outbound Meta token-exchange
 * HTTP calls per hit, making it a real abuse/cost vector for a hammering authenticated client.
 *
 * <p><b>[SEC: Vikram, P5 test-drift fix]</b> Buckets below were exercised by pre-existing test
 * files ({@code AuthRateLimitFilter{Tracking,Shopify,WooCommerce,DeliverableContract,K6}
 * BucketTest}) that never compiled against this class — the tests describe the intended, real
 * surface and were treated as the source of truth for what to build here, not faked to match a
 * lesser implementation:
 *
 * <ul>
 *   <li><b>{@code tracking}</b> (IP-keyed, mirrors {@code sensitive}) — {@code POST
 *       /webhooks/redemption}, {@code /webhooks/conversion}, {@code /webhooks/shopify}, {@code
 *       /webhooks/woocommerce}, and {@code GET /track/click/*} — the only abuse defense these
 *       principal-less public endpoints have (see {@code ConversionWebhookController} javadoc).
 *   <li><b>{@code meta-oauth}</b> extended to also cover {@code GET /shopify/oauth/authorize}/
 *       {@code /callback} and {@code POST /woocommerce/connect} — same OAuth-connect abuse/cost
 *       shape as the existing Meta surface.
 *   <li><b>{@code meera-turn}</b> ({@code POST /meera/sessions/{id}/messages}) and {@code
 *       meera-voice} ({@code POST /meera/voice/speak} TTS and {@code POST /meera/voice/transcribe}
 *       STT, sharing the bucket) — Kabir red-team MEDIUM: all are per-call LLM/voice cost surfaces
 *       that were credit-gated but had no throttle, letting a single authenticated user hammer any
 *       of them with no cost ceiling beyond the (non-decrementing) credit check itself.
 *   <li><b>User-keyed buckets</b> ({@code creator-deliverable-write}, {@code
 *       brand-deliverable-review}, {@code contract-sign}, {@code review-write}, {@code
 *       review-flag}, {@code dispute-open}, {@code discovery-invite}, {@code discovery-search},
 *       {@code campaign-apply}, {@code creator-withdraw}, {@code meera-turn}, {@code
 *       meera-voice}) — keyed by the JWT {@code sub} claim
 *       parsed from the {@code Authorization: Bearer} header (this filter runs BEFORE the real auth
 *       filter in the chain, so it parses the token itself via the injected {@link JwtService}
 *       rather than reading an already-resolved principal), falling back to per-IP keying when no
 *       Bearer token is present or it fails to parse — never throws, never blocks the request on a
 *       parse failure, the downstream auth filter is still the real authorization gate.
 *   <li><b>{@code creator-withdraw}</b> uses its OWN window ({@link #withdrawWindowSeconds}, default
 *       one hour) instead of the shared {@link #windowSeconds} — a withdraw-abuse defense is
 *       meaningfully different in cadence from a login-brute-force defense.
 *   <li><b>{@code admin-coupon-issue}</b> [Kabir M-3] ({@code POST
 *       /admin/campaigns/&#123;campaignId&#125;/coupons}) — user-keyed, own hourly window
 *       ({@link #adminCouponIssueWindowSeconds}). The first bucket here that throttles an ADMIN
 *       action: RBAC settles who may mint discount codes, nothing settled how many, and each call
 *       mints a live redeemable code plus a row. Keyed by admin identity rather than IP so the
 *       bound cannot be reset by moving IP and one admin cannot throttle the team.
 *   <li><b>Percent-encoding</b> — {@code HttpServletRequest#getRequestURI()} returns the RAW,
 *       undecoded path; a request for {@code /wallet/%77ithdraw} would otherwise silently bypass
 *       every literal-path bucket match here. {@link #bucketFor} decodes the path before matching
 *       (Kabir NEW-1) so an encoded path segment cannot be used to dodge a bucket.
 *   <li><b>Spoofed {@code X-Forwarded-For}</b> (Kabir CR-11 endpoint red-team, Blocker-1) — client
 *       IP resolution is delegated entirely to Tomcat's {@code RemoteIpValve}
 *       ({@code forward-headers-strategy: native}), which validates the peer against
 *       {@code internal-proxies} and walks XFF right-to-left. The hand-rolled allow-list that used
 *       to live here read the LEFT-most entry and failed open under the {@code framework} strategy
 *       the deploys had switched to — see {@link #clientIp} for exactly how. A spoofed XFF can no
 *       longer move a request into a different or fresh bucket.
 * </ul>
 *
 * <p>Keyed by client IP + coarse endpoint bucket (or by user id for the buckets above). In-memory
 * and therefore <b>per-instance</b> — this is a correct single-node defense and a meaningful speed
 * bump behind a single load balancer, but a horizontally-scaled deploy MUST move this to a shared
 * store (Redis/bucket4j) or enforce it at the edge (WAF / API gateway) so the limit is global.
 * Documented, not silently assumed (M-K6-2, explicitly out of scope here).
 */
@Component
public class AuthRateLimitFilter extends OncePerRequestFilter {

    private static final String CTX = "/api/v1";

    private static final Pattern CREATOR_DELIVERABLE_WRITE =
            Pattern.compile("^/creator/deliverables/[^/]+/(upload|submit|metrics)$");
    private static final Pattern BRAND_DELIVERABLE_REVIEW =
            Pattern.compile("^/deliverables/[^/]+/(approve|revise)$");
    private static final Pattern CONTRACT_SIGN = Pattern.compile("^/contracts/[^/]+/sign$");
    private static final Pattern REVIEW_WRITE = Pattern.compile("^/(creator|brand)/reviews$");
    private static final Pattern REVIEW_FLAG = Pattern.compile("^/[^/]+/reviews/[^/]+/flag$");
    private static final Pattern DISPUTE_OPEN = Pattern.compile("^/deals/[^/]+/disputes$");
    private static final Pattern DISCOVERY_INVITE = Pattern.compile("^/creators/[^/]+/invite$");
    private static final Pattern CAMPAIGN_APPLY =
            Pattern.compile("^/creator/campaigns/[^/]+/apply$");
    /**
     * Fix round 2, item 4 (Priya Q10) — was {@code ^/meera/sessions/[^/]+/messages$}, matching only
     * the BRAND-audience route ({@link com.influora.web.MeeraController}). It never matched {@code
     * POST /creator/meera/sessions/{id}/messages} ({@link com.influora.web.CreatorMeeraController}),
     * so every creator Meera turn — the exact same per-call LLM-cost surface this bucket exists to
     * throttle — was completely unthrottled. The optional {@code (/creator)?} prefix now covers both.
     */
    private static final Pattern MEERA_TURN =
            Pattern.compile("^(/creator)?/meera/sessions/[^/]+/messages$");
    /**
     * Fix round 2, item 4 (Priya Q10) — {@code GET /public/creators/{username}/verified} (A9) is
     * {@code permitAll} and had NO throttle at all: usernames are public/guessable, so without this
     * the entire discoverable creator base could be scraped as fast as the server could answer.
     */
    private static final Pattern PUBLIC_CREATOR_VERIFIED =
            Pattern.compile("^/public/creators/[^/]+/verified$");
    /**
     * T-FESTIVALBOX-0905 phase 9 [Kabir F-3] — {@code POST /portfolio/{username}/contact} was
     * completely unthrottled at the edge: no bucket matched it at all, so an anonymous caller could
     * hit it as fast as the server would answer, and every accepted hit emails a real creator. This
     * IP-keyed bucket (default, since it is not in {@link #isUserKeyedBucket}) is the first of two
     * layers this phase adds — see {@code PortfolioService#contact}'s
     * {@code AbuseThrottleService} per-recipient-creator cap for the second, which bounds the
     * endpoint even against a caller that rotates source IP between requests.
     */
    private static final Pattern PORTFOLIO_CONTACT =
            Pattern.compile("^/portfolio/[^/]+/contact$");

    /**
     * T-MEERA-CREATOR-PHASE-B (SPEC.md &sect;3.4) — the creator Meera tool surface, {@code POST}
     * only. Every route under it runs a real query fan-out on behalf of one creator, so it is the
     * same class of per-call cost as {@link #MEERA_TURN}; this bucket is defence-in-depth behind the
     * turn bucket and the AI spend gate, not the primary ceiling.
     *
     * <p><b>Keyed on the on-behalf JWT subject, never on IP</b> — see
     * {@link #extractOnBehalfSubject}.
     */
    private static final Pattern CREATOR_TOOL = Pattern.compile("^/internal/meera/creator/[^/]+$");

    /**
     * Mirrors {@code InternalServiceTokenFilter#INTERNAL_PREFIX} — the paths behind the
     * service-mesh gate. Used only to decide whether the {@code X-RateLimit-*} headers may be
     * written; see {@link #doFilterInternal}.
     */
    private static final String INTERNAL_PREFIX = "/internal/";

    /**
     * [SEC: Kabir Wave 2, finding 1] Anything longer than this is not a token this service minted
     * (a real on-behalf JWT is ~540 characters) and is rejected before it reaches the JWT parser,
     * so a multi-megabyte body in a header cannot be turned into parser work.
     */
    private static final int MAX_ON_BEHALF_TOKEN_CHARS = 4096;

    /**
     * Bound on {@link #onBehalfSubjectMemo}. Sized for the concurrent-turn count this surface can
     * physically have: each entry is one live on-behalf token, and those expire in 120 seconds
     * ({@code OnBehalfTokenService#MAX_TTL_SECONDS}). It is a hard ceiling, not a target — the map
     * is purged of dead entries and, failing that, dropped wholesale before it can exceed this.
     */
    private static final int ON_BEHALF_MEMO_MAX_ENTRIES = 512;

    /**
     * Ceiling on how long a memoised subject may be reused, independent of the token's own
     * {@code exp}. The entry dies at whichever comes first, so a token minted with an
     * unexpectedly long TTL cannot pin a subject in this map.
     */
    private static final long ON_BEHALF_MEMO_MAX_TTL_MILLIS = 60_000L;

    private final JwtService jwtService;

    /**
     * Used ONLY to read the {@code sub} of the forwarded on-behalf token for the
     * {@code creator-tool} bucket key. Nullable, exactly like {@link #jwtService}: the unit tests
     * for other buckets construct this filter with nulls, and a null here simply degrades that one
     * bucket to IP-keying.
     */
    private final com.influora.service.meera.OnBehalfTokenService onBehalfTokenService;

    public AuthRateLimitFilter(
            JwtService jwtService,
            com.influora.service.meera.OnBehalfTokenService onBehalfTokenService) {
        this.jwtService = jwtService;
        this.onBehalfTokenService = onBehalfTokenService;
    }

    @Value("${influora.auth.rate-limit.enabled:true}")
    private boolean enabled;

    /** Requests per window for sensitive credential endpoints (login/register/reset). */
    @Value("${influora.auth.rate-limit.sensitive-per-window:10}")
    private int sensitiveLimit;

    /** Requests per window for the OTP/email surface (send/verify). */
    @Value("${influora.auth.rate-limit.otp-per-window:5}")
    private int otpLimit;

    /** Requests per window for token refresh. */
    @Value("${influora.auth.rate-limit.refresh-per-window:30}")
    private int refreshLimit;

    /** Requests per window for the Meta/Shopify/WooCommerce OAuth connect surface. */
    @Value("${influora.meta.oauth-rate-limit-per-window:20}")
    private int metaOAuthLimit;

    /** Requests per window for the public tracking/webhook surface (Wave A task A2). */
    @Value("${influora.tracking.rate-limit-per-window:30}")
    private int trackingLimit;

    /**
     * Requests per window, per IP, for the CR-11 client crash-report sink ({@code POST
     * /client-errors}). IP-keyed like {@code tracking}/{@code sensitive}, not user-keyed — the
     * contract requires this endpoint to work with no {@code Authorization} header at all (a crash
     * on the public portfolio page or before login), so per-IP is the only identity available. See
     * {@code ClientErrorController} and {@code wiki/tech/cr-11-client-error-contract.md}.
     */
    @Value("${influora.client-error.rate-limit-per-window:30}")
    private int clientErrorLimit;

    /** Requests per window, per creator, for the deliverable upload/submit/metrics surface. */
    @Value("${influora.creator.deliverable-write-rate-limit-per-window:20}")
    private int creatorDeliverableWriteLimit;

    /** Requests per window, per brand user, for the deliverable approve/revise surface. */
    @Value("${influora.brand.deliverable-review-rate-limit-per-window:20}")
    private int brandDeliverableReviewLimit;

    /** Requests per window, per user, for contract signing. */
    @Value("${influora.contract.sign-rate-limit-per-window:10}")
    private int contractSignLimit;

    /** Requests per window, per user, for review creation (M-K6-1). */
    @Value("${influora.review.write-rate-limit-per-window:10}")
    private int reviewWriteLimit;

    /** Requests per window, per user, for review flagging (M-K6-3). */
    @Value("${influora.review.flag-rate-limit-per-window:10}")
    private int reviewFlagLimit;

    /** Requests per window, per user, for opening a dispute. */
    @Value("${influora.dispute.open-rate-limit-per-window:5}")
    private int disputeOpenLimit;

    /** Requests per window, per user, for creator-discovery invites. */
    @Value("${influora.discovery.invite-rate-limit-per-window:20}")
    private int discoveryInviteLimit;

    /** Requests per window, per user, for creator-discovery search/suggestions (M-K6-5). */
    @Value("${influora.discovery.search-rate-limit-per-window:60}")
    private int discoverySearchLimit;

    /**
     * Requests per window, per IP, for the public unauthenticated verified-metrics page ({@code GET
     * /public/creators/{username}/verified}, A9) — fix round 2, item 4 (Priya Q10). IP-keyed like
     * {@code tracking}/{@code discovery-search}'s unauthenticated fallback, since this route is
     * {@code permitAll} and has no principal at all.
     */
    @Value("${influora.public.creator-verified-rate-limit-per-window:30}")
    private int publicCreatorVerifiedLimit;

    /**
     * Requests per window, per IP, for {@code POST /portfolio/{username}/contact} (T-FESTIVALBOX-0905
     * phase 9, Kabir F-3) — IP-keyed like {@code tracking}/{@code public-creator-verified}, since
     * this route is {@code permitAll} and has no principal at all. Deliberately its OWN bucket
     * rather than sharing {@code tracking}: this endpoint has a real side effect per accepted
     * request (an email sent to a creator) that the webhook/tracking surface does not, so its limit
     * is tuned independently rather than inheriting whatever {@code tracking} happens to be set to.
     */
    @Value("${influora.portfolio.contact-rate-limit-per-window:10}")
    private int portfolioContactLimit;

    /** Requests per window, per user, for campaign apply. */
    @Value("${influora.campaign.apply-rate-limit-per-window:20}")
    private int campaignApplyLimit;

    /**
     * Requests per window, per user, for a Meera chat turn ({@code POST
     * /meera/sessions/{id}/messages}) — Kabir red-team MEDIUM: this is a real per-call LLM-cost
     * surface (credit-gated but not rate-limited) with no throttle before this fix.
     */
    @Value("${influora.meera.turn-rate-limit-per-window:20}")
    private int meeraTurnLimit;

    /**
     * Requests per window, per user, for the Meera voice surface — both TTS ({@code POST
     * /meera/voice/speak}) and STT ({@code POST /meera/voice/transcribe}) share this bucket: each
     * call is a real Sarvam provider cost regardless of the 200-with-fallback response contract, and
     * had no throttle. Same Kabir finding, extended to the voice-INPUT leg.
     */
    @Value("${influora.meera.voice-rate-limit-per-window:30}")
    private int meeraVoiceLimit;

    /**
     * Requests per window, <b>per creator</b>, for the creator Meera tool surface
     * ({@code POST /internal/meera/creator/*}). 60 per creator per window, not 60 per platform —
     * see {@link #extractOnBehalfSubject} for why that distinction is the whole point of this
     * bucket.
     */
    @Value("${influora.meera.creator-tool-rate-limit-per-window:60}")
    private int creatorToolLimit;

    /**
     * T-MEERA-CREATOR-PHASE-B (SPEC.md &sect;3.8) — requests per window, <b>per creator</b>, for
     * {@code POST /creator/briefs}.
     *
     * <p>10 rather than the generic limit because this is the one creator route that spends real money
     * on every call: each paste is an AI extraction billed against the creator's own monthly brief
     * allowance. The window bound and that allowance are two different controls doing two different
     * jobs — the allowance stops a month of cost, this stops a minute of it, and neither substitutes
     * for the other.
     *
     * <p>USER-keyed (see {@link #isUserKeyedBucket}), not IP-keyed. An IP key on a creator route is
     * wrong in both directions: creators behind one mobile carrier NAT would starve each other, and a
     * single creator could reset her own window by changing network.
     */
    @Value("${influora.meera.creator-brief-paste-rate-limit-per-window:10}")
    private int creatorBriefPasteLimit;

    /**
     * [SEC: Kabir Wave 2, finding 1] How many ES256 verifications ONE source address is allowed to
     * <b>fail</b> per {@link #windowSeconds} before this filter stops verifying for that address
     * entirely and keys the bucket by IP instead. See {@link #extractOnBehalfSubject} for why a
     * budget is needed at all and why it is charged on failure rather than on every call.
     *
     * <p>20 is chosen to be unreachable in normal operation. The one legitimate caller
     * (influora-ai) presents tokens this service minted seconds earlier, so its steady-state
     * failure count is zero; 20 leaves room for the brief overlap during a JWKS key rotation
     * without tripping. Setting it to 0 shuts signature verification off on this surface entirely,
     * degrading the bucket to IP-keying rather than taking the API down — the lever to pull during
     * an active flood.
     *
     * <p>Overridable as a Spring property only. Like its sibling
     * {@link #creatorToolLimit} there is deliberately no {@code ${...}} placeholder for it in
     * {@code application.yml}, so there is no environment variable that sets it — do not add one to
     * a compose file and expect it to bind.
     */
    @Value("${influora.meera.creator-tool-verify-failure-budget:20}")
    private int creatorToolVerifyFailureBudget;

    /** Requests per {@link #withdrawWindowSeconds}, per creator, for wallet withdrawal (M-K6-4). */
    @Value("${influora.wallet.withdraw-rate-limit-per-window:5}")
    private int creatorWithdrawLimit;

    /** Window (seconds) for the {@code creator-withdraw} bucket only — deliberately NOT {@link #windowSeconds}. */
    @Value("${influora.wallet.withdraw-rate-limit-window-seconds:3600}")
    private long withdrawWindowSeconds;

    /**
     * [Kabir M-3] Coupons an admin may mint per {@link #adminCouponIssueWindowSeconds}, per admin
     * identity. 30/hour is set to be invisible during real use — issuing coupons is a deliberate,
     * one-at-a-time act while setting up a campaign — while cutting a runaway script or a stolen
     * session from "unlimited live discount codes" to at most 30 before it stops. Configurable, so
     * a genuine bulk-issuance need is a config change rather than a reason to delete the bound.
     */
    @Value("${influora.admin.coupon-issue-rate-limit-per-window:30}")
    private int adminCouponIssueLimit;

    /**
     * Window (seconds) for {@code admin-coupon-issue} only. Hourly like {@code creator-withdraw}
     * and for the same reason: the default 60s window is meaningless for an action nobody performs
     * in bursts — a 60-second window would reset far faster than any abuse would exhaust it.
     */
    @Value("${influora.admin.coupon-issue-rate-limit-window-seconds:3600}")
    private long adminCouponIssueWindowSeconds;

    @Value("${influora.auth.rate-limit.window-seconds:60}")
    private long windowSeconds;

    // [SEC: Kabir CR-11 endpoint red-team, Blocker-1] `influora.security.trusted-proxies` and its
    // cached Set are GONE, not merely unused. They were the hand-rolled allow-list that
    // `ForwardedHeaderFilter` silently defeated, and leaving them here would leave a security
    // control that reads as if it still protects something. Trusted-proxy validation now lives
    // where it belongs: `server.tomcat.remoteip.internal-proxies` in application.yml.
    //
    // The env var `TRUSTED_PROXIES` is still set by deploy/hostinger/*.yml and is now inert. It is
    // left in the compose files deliberately rather than removed in the same change — see the
    // deploy note in that file — so a rollback to a prior image does not lose it.

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    /**
     * [SEC: Kabir Wave 2, finding 1] Token hash &rarr; the subject a completed verification
     * returned. Keyed on {@link #sha256Hex} of the token bytes and <b>never</b> on the token's own
     * unverified {@code sub}: a key derived from an attacker-chosen claim would let a forged token
     * name any creator and read a hit that a real verification put there. The hash is of the exact
     * bytes that were verified, so a hit can only ever return the subject those bytes proved.
     *
     * <p>A miss always verifies. This collapses the repeat cost within one turn (Python calls
     * several tools with the SAME 120-second token) — it is not, and cannot be, the load-shedding
     * mechanism: an attacker sends a different token every request and misses every time. That is
     * what {@link #creatorToolVerifyFailureBudget} is for.
     */
    private final Map<String, SubjectMemo> onBehalfSubjectMemo = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        if (!enabled || !isThrottledMethod(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        String bucket = bucketFor(request);
        if (bucket == null) {
            chain.doFilter(request, response);
            return;
        }

        int limit = limitFor(bucket);
        long windowSecondsForBucket = windowSecondsFor(bucket);
        String key = rateLimitKey(request, bucket);
        long nowSeconds = System.currentTimeMillis() / 1000L;
        Window window =
                windows.compute(
                        key,
                        (k, existing) -> {
                            if (existing == null
                                    || nowSeconds - existing.startSecond >= windowSecondsForBucket) {
                                return new Window(nowSeconds);
                            }
                            return existing;
                        });

        int used = window.count.incrementAndGet();
        int remaining = Math.max(0, limit - used);

        // [SEC: Kabir Wave 2, finding 6] Not on /internal/**. This filter runs BEFORE
        // InternalServiceTokenFilter, so on those paths the headers are written to a caller who
        // has not yet presented a service token — and the creator-tool bucket is keyed on a named
        // creator, so they would report HER remaining quota to whoever holds a copy of her
        // on-behalf token. Withholding them costs the one legitimate caller nothing: influora-ai
        // reads the 429 and the Retry-After, not the running count. Retry-After is deliberately
        // still sent below; it appears only on a response that has already been refused, and the
        // legitimate mesh client needs it to back off.
        if (!isInternalPath(request)) {
            response.setHeader("X-RateLimit-Limit", String.valueOf(limit));
            response.setHeader("X-RateLimit-Remaining", String.valueOf(remaining));
        }

        if (used > limit) {
            long retryAfter = Math.max(1, windowSecondsForBucket - (nowSeconds - window.startSecond));
            response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter));
            response.setStatus(429);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter()
                    .write(
                            "{\"success\":false,\"error\":{\"code\":\"RATE_LIMITED\","
                                    + "\"message\":\"Too many requests. Please try again shortly.\"}}");
            return;
        }

        chain.doFilter(request, response);
    }

    /** POST covers the auth/write surface; GET is needed for OAuth-connect and discovery-search. */
    private static boolean isThrottledMethod(String method) {
        return "POST".equalsIgnoreCase(method) || "GET".equalsIgnoreCase(method);
    }

    /** Returns the rate-limit bucket for the request path, or null if the path is not throttled. */
    private String bucketFor(HttpServletRequest request) {
        String path = stripMatrixParams(decode(stripContext(request.getRequestURI())));
        boolean isGet = "GET".equalsIgnoreCase(request.getMethod());

        if (isGet) {
            if (path.equals("/meta/oauth/authorize")
                    || path.equals("/meta/oauth/callback")
                    || path.equals("/shopify/oauth/authorize")
                    || path.equals("/shopify/oauth/callback")) {
                return "meta-oauth";
            }
            if (path.equals("/track/click") || path.startsWith("/track/click/")) {
                return "tracking";
            }
            if (path.equals("/creators") || path.equals("/creators/search")) {
                return "discovery-search";
            }
            if (PUBLIC_CREATOR_VERIFIED.matcher(path).matches()) {
                return "public-creator-verified";
            }
            return null;
        }

        // Public tracking/webhook surface (Wave A task A2; Shopify/WooCommerce D1/D2) — no
        // workspace principal exists at these call sites, so per-IP throttling is their only
        // abuse defense.
        if (path.equals("/webhooks/redemption")
                || path.equals("/webhooks/conversion")
                || path.equals("/webhooks/shopify")
                || path.equals("/webhooks/woocommerce")) {
            return "tracking";
        }
        // T-FESTIVALBOX-0905 [Priya C2] — the public Festival Box enquiry form. Same trust boundary
        // as the webhook block above (no principal exists; per-IP throttling at the edge is the only
        // volume defense), so it shares the "tracking" bucket rather than getting its own.
        //
        // Registering it here is NOT redundant with FestivalEnquiryService's own two-key throttle.
        // That one lives inside @Transactional and only runs AFTER two COUNT queries, so it caps
        // ROWS WRITTEN while leaving REQUEST VOLUME uncapped — an attacker refused by it still costs
        // ~2 DB round-trips per attempt, forever. This bucket is what bounds the attempts. Missing
        // it also left the service's count-then-insert race unbounded, since nothing upstream
        // limited how many requests could be in flight at once.
        if (path.equals("/festival-enquiries")) {
            return "tracking";
        }
        // T-FESTIVALBOX-0905 phase 6 — the coupon-copy demand-signal tracker. Same trust boundary
        // as the block above: no principal exists (the caller is an anonymous shopper), and unlike
        // /festival-enquiries this endpoint has no honeypot at all, so this edge cap is a larger
        // share of its total abuse defense, not a backstop to a service-level throttle. Shares
        // "tracking" rather than getting its own bucket for the same reason /festival-enquiries
        // does — same shape of public, unauthenticated, high-volume-tolerant write.
        if (path.equals("/festival/coupon-copied")) {
            return "tracking";
        }
        // T-FESTIVALBOX-0905 phase 9 [Kabir F-3] — see PORTFOLIO_CONTACT's javadoc. Checked before
        // the /woocommerce/connect literal below since both are exact-path checks with no shared
        // prefix, so ordering here has no effect on correctness — kept close to the other
        // public/no-principal POST routes above for readability.
        if (PORTFOLIO_CONTACT.matcher(path).matches()) {
            return "portfolio-contact";
        }
        if (path.equals("/woocommerce/connect")) {
            return "meta-oauth";
        }
        if (path.equals("/client-errors")) {
            return "client-errors";
        }
        if (path.equals("/creators/suggestions")) {
            // Kabir NEW-2: same query cost as GET /creators/search — must share that bucket.
            return "discovery-search";
        }
        if (REVIEW_FLAG.matcher(path).matches()) {
            return "review-flag";
        }
        if (REVIEW_WRITE.matcher(path).matches()) {
            return "review-write";
        }
        if (CREATOR_DELIVERABLE_WRITE.matcher(path).matches()) {
            return "creator-deliverable-write";
        }
        if (BRAND_DELIVERABLE_REVIEW.matcher(path).matches()) {
            return "brand-deliverable-review";
        }
        if (CONTRACT_SIGN.matcher(path).matches()) {
            return "contract-sign";
        }
        if (DISPUTE_OPEN.matcher(path).matches()) {
            return "dispute-open";
        }
        if (DISCOVERY_INVITE.matcher(path).matches()) {
            return "discovery-invite";
        }
        if (CAMPAIGN_APPLY.matcher(path).matches()) {
            return "campaign-apply";
        }
        if (path.equals("/wallet/withdraw")) {
            return "creator-withdraw";
        }
        // Priya gate review defect 3 — CreatorMeeraController's mirrored voice routes are the
        // exact same per-call TTS/STT cost surface as the BRAND-audience ones above (MEERA_TURN's
        // "(/creator)? prefix" fix, same rationale): both must share this bucket, not go
        // unthrottled.
        if (path.equals("/meera/voice/speak")
                || path.equals("/meera/voice/transcribe")
                || path.equals("/creator/meera/voice/speak")
                || path.equals("/creator/meera/voice/transcribe")) {
            return "meera-voice";
        }
        if (MEERA_TURN.matcher(path).matches()) {
            return "meera-turn";
        }
        if (CREATOR_TOOL.matcher(path).matches()) {
            return "creator-tool";
        }
        // SPEC.md 3.8 — POST /creator/briefs ONLY. The exact-equality check, rather than a prefix,
        // keeps GET /creator/briefs/{id} and the dismiss route out of a bucket that exists to bound
        // AI spend: reading a brief the creator already paid for costs nothing, and throttling it
        // would only stop her opening what she has.
        if ("POST".equalsIgnoreCase(request.getMethod()) && path.equals("/creator/briefs")) {
            return "creator-brief-paste";
        }

        if (path.equals("/auth/brand/send-email-otp") || path.equals("/auth/brand/verify-email")) {
            return "otp";
        }
        if (path.equals("/auth/refresh")) {
            return "refresh";
        }
        if (path.startsWith("/auth/")) {
            // login, brand/login, brand/register, forgot-password, reset-password, logout
            return "sensitive";
        }
        // [SEC: Priya audit, e60d249 follow-up] POST /me/password (BR-05, in-session password
        // change) re-authenticates the caller against their stored BCrypt hash via
        // AuthService#changePassword — an unconditional passwordEncoder.matches() call is itself a
        // CPU-exhaustion vector, and with no throttle a stolen/leaked access token let an attacker
        // brute-force `currentPassword` at unlimited rate. Shares the "sensitive" bucket (same shape
        // as login: a credential check against a stored hash), deliberately IP-keyed like the rest
        // of that bucket rather than user-keyed — an attacker retrying with a stolen token from a
        // fresh IP must not get a clean rate-limit window. Exact-match only (not a `/me/` prefix) so
        // this cannot accidentally throttle other `/me/...` routes (e.g. GET /me, PATCH /me,
        // DELETE /me/account) that have nothing to do with credential verification.
        if (path.equals("/me/password")) {
            return "sensitive";
        }
        // [Kabir M-3] POST /admin/campaigns/{campaignId}/coupons — admin-issued coupon minting
        // (T-FESTIVALBOX-0905 phase 11). RBAC (SUPER_ADMIN/ADMIN + MFA) controls WHO may call it and
        // is not in question here; what was missing is any bound on HOW MANY times. Every call
        // server-mints a real, immediately-redeemable discount code and inserts a coupon_codes row,
        // so an unthrottled loop is both unbounded table growth and an unbounded supply of live
        // discounts — from a stolen admin session or a scripting mistake, neither of which RBAC
        // distinguishes from legitimate use.
        //
        // USER-KEYED below, unlike the "sensitive" bucket. The opposite reasoning to /me/password:
        // there, IP-keying is right because the threat is an attacker with a stolen token trying
        // credentials from anywhere. Here the actor is a *proven* admin identity and the thing
        // worth bounding is what that identity can mint, which must not be resettable by changing
        // IP. It also means one admin hitting the limit never throttles the rest of the team.
        //
        // Prefix match on the collection path so it covers the POST regardless of campaignId, and
        // deliberately narrow: it must not catch other /admin/campaigns/... routes.
        if (path.startsWith("/admin/campaigns/") && path.endsWith("/coupons")) {
            return "admin-coupon-issue";
        }
        return null;
    }

    private int limitFor(String bucket) {
        return switch (bucket) {
            case "otp" -> otpLimit;
            case "refresh" -> refreshLimit;
            case "meta-oauth" -> metaOAuthLimit;
            case "tracking" -> trackingLimit;
            case "client-errors" -> clientErrorLimit;
            case "creator-deliverable-write" -> creatorDeliverableWriteLimit;
            case "brand-deliverable-review" -> brandDeliverableReviewLimit;
            case "contract-sign" -> contractSignLimit;
            case "review-write" -> reviewWriteLimit;
            case "review-flag" -> reviewFlagLimit;
            case "dispute-open" -> disputeOpenLimit;
            case "discovery-invite" -> discoveryInviteLimit;
            case "discovery-search" -> discoverySearchLimit;
            case "public-creator-verified" -> publicCreatorVerifiedLimit;
            case "portfolio-contact" -> portfolioContactLimit;
            case "campaign-apply" -> campaignApplyLimit;
            case "creator-withdraw" -> creatorWithdrawLimit;
            case "admin-coupon-issue" -> adminCouponIssueLimit;
            case "meera-turn" -> meeraTurnLimit;
            case "meera-voice" -> meeraVoiceLimit;
            case "creator-tool" -> creatorToolLimit;
            case "creator-brief-paste" -> creatorBriefPasteLimit;
            default -> sensitiveLimit;
        };
    }

    /**
     * {@code creator-withdraw} and {@code admin-coupon-issue} each use their own (default: hourly)
     * window; every other bucket shares {@link #windowSeconds}.
     */
    private long windowSecondsFor(String bucket) {
        return switch (bucket) {
            case "creator-withdraw" -> withdrawWindowSeconds;
            case "admin-coupon-issue" -> adminCouponIssueWindowSeconds;
            default -> windowSeconds;
        };
    }

    /**
     * User-keyed buckets are throttled per authenticated identity (JWT {@code sub}), falling back
     * to per-IP keying when no Bearer token is present or it does not parse — see class javadoc.
     * Every other bucket (sensitive/otp/refresh/meta-oauth/tracking) stays IP-keyed, unchanged.
     */
    private String rateLimitKey(HttpServletRequest request, String bucket) {
        // T-MEERA-CREATOR-PHASE-B (SPEC.md 3.4). This bucket needs its OWN key derivation, not an
        // entry in isUserKeyedBucket below, because that path reads the ordinary `Authorization`
        // header — and on /internal/** that header carries the SERVICE token, not the creator. The
        // creator's identity is only in the forwarded on-behalf JWT.
        if ("creator-tool".equals(bucket)) {
            String creatorId = extractOnBehalfSubject(request);
            if (creatorId != null) {
                return "creator:" + creatorId + "|" + bucket;
            }
        }
        if (isUserKeyedBucket(bucket)) {
            String userId = extractUserId(request);
            if (userId != null) {
                return "user:" + userId + "|" + bucket;
            }
        }
        return clientIp(request) + "|" + bucket;
    }

    private static boolean isUserKeyedBucket(String bucket) {
        return switch (bucket) {
            case "creator-deliverable-write",
                    "brand-deliverable-review",
                    "contract-sign",
                    "review-write",
                    "review-flag",
                    "dispute-open",
                    "discovery-invite",
                    "discovery-search",
                    "campaign-apply",
                    "creator-withdraw",
                    "admin-coupon-issue",
                    "meera-turn",
                    "meera-voice",
                    // SPEC.md 3.8 — AI cost, so the identity that must be bounded is the creator's,
                    // not her network's. Reachable from the ordinary `Authorization` header (unlike
                    // "creator-tool" above, which sits on /internal/** where that header carries the
                    // SERVICE token), so it needs no special case beyond this entry.
                    "creator-brief-paste" ->
                    true;
            default -> false;
        };
    }

    /**
     * Parses the {@code sub} claim from the {@code Authorization: Bearer} header, if present and
     * valid. Never throws — an absent, malformed, or expired token simply yields {@code null} (the
     * caller falls back to IP-keying); the real authorization decision is still made downstream by
     * the actual auth filter/{@code AuthPrincipal} resolution, not here.
     */
    /**
     * T-MEERA-CREATOR-PHASE-B (SPEC.md &sect;3.4) — the {@code sub} of the forwarded
     * {@code X-Onbehalf-Authorization} token, or null.
     *
     * <p><b>Why this exists rather than an IP key.</b> Every call to
     * {@code /internal/meera/creator/*} arrives server-to-server from the single influora-ai
     * process, so all of them share one source address. An IP-keyed bucket there is not a per-caller
     * cap at all — it is a platform-wide cap, and the first busy creator of the window starves every
     * other creator on the platform. The identity that must bound this surface is the creator's, and
     * on {@code /internal/**} the only place it appears is this header.
     *
     * <p><b>Why it VERIFIES rather than just decoding.</b> SPEC.md &sect;3.4 allows reading the
     * subject unverified, on the grounds that a bucket key needs to be stable rather than trusted.
     * That is true of the key but not of the consequences: this filter runs BEFORE
     * {@code InternalServiceTokenFilter} in the chain, so an unverified subject is attacker-chosen,
     * and anyone who learned a creator's user id could forge a header and burn that creator's window
     * from outside. Verification is an in-memory EC signature check with no I/O, and it removes the
     * whole class — a forged or expired token yields null here and the request falls back to
     * IP-keying, which is the correct treatment for a caller who has proven nothing.
     *
     * <p>Never throws: a missing service (the unit-test construction path), an absent header, or any
     * parse/verify failure all yield null. The real authorisation decision is made downstream by
     * {@code OnBehalfAuthResolver}, not here.
     *
     * <p><b>[SEC: Kabir Wave 2, finding 1] Why verifying is not enough on its own.</b> The
     * reasoning above is about correctness and it still holds; the defect was the cost of being
     * correct. Measured here, an ES256 verification of a well-formed wrong-signature token costs
     * ~1.2 ms against ~0.005 ms for a request with no header — a ~240x amplifier that an
     * unauthenticated caller could reach, because this filter runs ahead of the mesh gate and the
     * key was derived before the counter was consulted, so a 429 never shed the work. Two bounds
     * now sit in front of the crypto, in this order:
     *
     * <ol>
     *   <li><b>Structural rejects</b> — no header, blank, or longer than
     *       {@link #MAX_ON_BEHALF_TOKEN_CHARS} — never reach the parser at all.
     *   <li><b>{@link #onBehalfSubjectMemo}</b> answers a token this filter has already verified.
     *       This is what makes the legitimate path cheap (one turn reuses one 120-second token
     *       across its tool calls); it does nothing against a flood of distinct tokens.
     *   <li><b>{@link #creatorToolVerifyFailureBudget}</b> is what sheds the flood. Every
     *       verification that FAILS is charged to the calling address; once an address has spent
     *       its budget for the window, this method stops verifying for it and returns null, so its
     *       requests fall to IP-keying and its own {@code creator-tool} window 429s it — at the
     *       cost of a map lookup, not a curve operation.
     * </ol>
     *
     * <p>Charging failures rather than every call is deliberate. A budget on all verifications
     * would be spent by the one legitimate caller — influora-ai is a single address carrying the
     * whole platform's creator traffic — and exhausting it there would drop every creator into a
     * shared IP-keyed window, which is the platform-wide starvation this bucket exists to prevent.
     * A legitimate caller presents tokens this service minted and fails none.
     *
     * <p>What this does not bound: an attacker spread across many source addresses still buys
     * {@link #creatorToolVerifyFailureBudget} verifications per address per window. Per-address
     * shedding is the correct layer here — a global cap would be exhaustible by an attacker
     * precisely to force the legitimate caller onto the IP-keyed fallback — and volumetric
     * distribution is the edge proxy's problem, not this filter's.
     */
    private String extractOnBehalfSubject(HttpServletRequest request) {
        if (onBehalfTokenService == null) {
            return null;
        }
        String header = request.getHeader("X-Onbehalf-Authorization");
        if (header == null || header.isBlank()) {
            return null;
        }
        String token = header.regionMatches(true, 0, "Bearer ", 0, 7) ? header.substring(7) : header;
        token = token.trim();
        if (token.isEmpty() || token.length() > MAX_ON_BEHALF_TOKEN_CHARS) {
            return null;
        }

        String tokenHash = sha256Hex(token);
        if (tokenHash == null) {
            return null;
        }
        long nowMillis = System.currentTimeMillis();
        SubjectMemo memoised = onBehalfSubjectMemo.get(tokenHash);
        if (memoised != null) {
            if (memoised.expiresAtMillis > nowMillis) {
                return memoised.subject;
            }
            onBehalfSubjectMemo.remove(tokenHash, memoised);
        }

        // A verification is about to happen, so the budget is checked HERE — after the memo (a hit
        // costs nothing and must not be gated) and before the curve operation.
        if (verifyBudgetExhausted(request)) {
            return null;
        }

        try {
            Claims claims = onBehalfTokenService.verify(token);
            String subject = claims == null ? null : claims.getSubject();
            if (subject == null) {
                chargeVerifyFailure(request);
                return null;
            }
            rememberSubject(tokenHash, subject, claims.getExpiration(), nowMillis);
            return subject;
        } catch (RuntimeException e) {
            chargeVerifyFailure(request);
            return null;
        }
    }

    /**
     * True once this address has failed {@link #creatorToolVerifyFailureBudget} verifications
     * inside the current window. Read-only — a stale window reads as "not exhausted" and is
     * replaced by the next {@link #chargeVerifyFailure}, which is what rolls the budget over.
     */
    private boolean verifyBudgetExhausted(HttpServletRequest request) {
        Window window = windows.get(verifyFailureKey(request));
        if (window == null) {
            return false;
        }
        if (System.currentTimeMillis() / 1000L - window.startSecond >= windowSeconds) {
            return false;
        }
        return window.count.get() >= creatorToolVerifyFailureBudget;
    }

    /** Charges one failed verification to the calling address's budget for this window. */
    private void chargeVerifyFailure(HttpServletRequest request) {
        long nowSeconds = System.currentTimeMillis() / 1000L;
        windows
                .compute(
                        verifyFailureKey(request),
                        (k, existing) ->
                                existing == null || nowSeconds - existing.startSecond >= windowSeconds
                                        ? new Window(nowSeconds)
                                        : existing)
                .count
                .incrementAndGet();
    }

    /**
     * Namespaced into the same {@link #windows} map as the request buckets rather than a second
     * map: identical fixed-window semantics, identical per-address growth, one thing to reason
     * about. The suffix is not a bucket name returned by {@link #bucketFor}, so it cannot collide
     * with a real bucket's key.
     */
    private String verifyFailureKey(HttpServletRequest request) {
        return clientIp(request) + "|onbehalf-verify-fail";
    }

    /**
     * Stores a verified subject, bounded two ways: the entry dies at the earlier of the token's own
     * {@code exp} and {@link #ON_BEHALF_MEMO_MAX_TTL_MILLIS}, and the map is purged of dead entries
     * — then cleared outright if that was not enough — before it can pass
     * {@link #ON_BEHALF_MEMO_MAX_ENTRIES}. Clearing is safe: the next request re-verifies.
     */
    private void rememberSubject(
            String tokenHash, String subject, java.util.Date tokenExpiry, long nowMillis) {
        long expiresAt = nowMillis + ON_BEHALF_MEMO_MAX_TTL_MILLIS;
        if (tokenExpiry != null) {
            expiresAt = Math.min(expiresAt, tokenExpiry.getTime());
        }
        if (expiresAt <= nowMillis) {
            return;
        }
        if (onBehalfSubjectMemo.size() >= ON_BEHALF_MEMO_MAX_ENTRIES) {
            onBehalfSubjectMemo.values().removeIf(entry -> entry.expiresAtMillis <= nowMillis);
            if (onBehalfSubjectMemo.size() >= ON_BEHALF_MEMO_MAX_ENTRIES) {
                onBehalfSubjectMemo.clear();
            }
        }
        onBehalfSubjectMemo.put(tokenHash, new SubjectMemo(subject, expiresAt));
    }

    /**
     * True for the paths behind {@code InternalServiceTokenFilter}'s service-mesh gate. Normalises
     * the URI the same way {@link #bucketFor} does, so an encoded or matrix-param'd
     * {@code /internal/**} cannot be dressed up as a public path to get the quota headers back.
     */
    private static boolean isInternalPath(HttpServletRequest request) {
        return stripMatrixParams(decode(stripContext(request.getRequestURI())))
                .startsWith(INTERNAL_PREFIX);
    }

    /** SHA-256 of the token bytes, hex. Null only if the JRE has no SHA-256, which cannot happen. */
    private static String sha256Hex(String token) {
        try {
            byte[] hash =
                    MessageDigest.getInstance("SHA-256")
                            .digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
    }


    private String extractUserId(HttpServletRequest request) {
        if (jwtService == null) {
            return null;
        }
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String token = header.substring(7).trim();
        if (token.isEmpty()) {
            return null;
        }
        try {
            Claims claims = jwtService.parseAccessToken(token);
            return claims == null ? null : claims.getSubject();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String stripContext(String uri) {
        if (uri.startsWith(CTX)) {
            return uri.substring(CTX.length());
        }
        return uri;
    }

    /**
     * [Kabir NEW-1] {@code HttpServletRequest#getRequestURI()} returns the RAW, undecoded path —
     * without this, an encoded path segment (e.g. {@code /wallet/%77ithdraw}) would silently bypass
     * every literal-path bucket match in {@link #bucketFor}. Falls back to the raw path unchanged
     * on a malformed escape sequence rather than throwing.
     */
    private static String decode(String path) {
        try {
            return UriUtils.decode(path, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return path;
        }
    }

    /**
     * Strips matrix parameters — everything from the first {@code ;} of each segment.
     *
     * <p>[SEC: Kabir CR-11 endpoint red-team, L-7] Spring Boot 3's {@code PathPatternParser} treats
     * matrix variables as segment metadata, so {@code POST /api/v1/client-errors;x=1} still routes
     * to the controller. But this filter matched the RAW URI, so {@code /client-errors;x=1} failed
     * every {@code .equals()} below, **no bucket was assigned at all**, and the request went
     * through unthrottled.
     *
     * <p>This affects every literal-path bucket here — {@code /wallet/withdraw}, {@code /webhooks/*},
     * {@code /meera/voice/*} — not just the endpoint it was found on. ({@code /auth/} uses
     * {@code startsWith} and was never affected.)
     *
     * <p>It is the same class of gap as the earlier percent-encoding bypass (Kabir NEW-1), which
     * added {@link #decode} but not this. Worth noting why it was ranked LOW at the time and is
     * being fixed now anyway: it was redundant while Blocker-1 handed out unlimited requests
     * outright. Blocker-1 is fixed, so this became the next bypass — a finding's severity is
     * relative to what else is broken, and nothing re-ranks it automatically when its dependency
     * closes.
     */
    private static String stripMatrixParams(String path) {
        if (path.indexOf(';') < 0) {
            return path;
        }
        StringBuilder cleaned = new StringBuilder(path.length());
        for (String segment : path.split("/", -1)) {
            if (cleaned.length() > 0 || path.startsWith("/")) {
                cleaned.append('/');
            }
            int semi = segment.indexOf(';');
            cleaned.append(semi < 0 ? segment : segment.substring(0, semi));
        }
        // split("/", -1) on a leading-slash path yields an empty first element, so the loop above
        // has already emitted the leading '/' — drop the duplicate it also prepends for it.
        String result = cleaned.toString();
        return result.startsWith("//") ? result.substring(1) : result;
    }

    /**
     * The rate-limit bucket key.
     *
     * [SEC: Kabir CR-11 endpoint red-team, Blocker-1] This used to hand-roll X-Forwarded-For
     * parsing behind a comma-separated trusted-proxy allow-list. It was defeated by the deploys
     * setting {@code SERVER_FORWARD_HEADERS_STRATEGY=framework}, and it failed OPEN:
     *
     * <ul>
     *   <li>Spring's {@code ForwardedHeaderFilter} runs at {@code HIGHEST_PRECEDENCE}, ahead of the
     *       Security chain at {@code -100}, and had already overwritten {@code getRemoteAddr()}
     *       with the <em>left-most</em> XFF entry — the spoofable one, because Caddy appends the
     *       true peer rather than replacing the header.
     *   <li>The allow-list check then compared that spoofed value against itself, never matched,
     *       and fell through to {@code return peer} — handing back the attacker's own header as the
     *       bucket key. A different XFF per request meant no limit at all.
     *   <li>It also strips the X-Forwarded-* headers, so nothing downstream could recover the truth.
     * </ul>
     *
     * The blast radius was every IP-keyed bucket, login brute-force included — not just CR-11's
     * endpoint, which is merely where it was found.
     *
     * The fix is upstream, in {@code application.yml}: {@code forward-headers-strategy: native}
     * installs Tomcat's {@code RemoteIpValve}, which validates against {@code internal-proxies} and
     * walks XFF RIGHT-TO-LEFT, landing on the entry our own proxy appended. A client-prepended
     * entry can never win. So {@code getRemoteAddr()} is now the real client IP, and reading it
     * directly is both correct and the only thing that stays correct if the topology changes —
     * parsing the header here a second time would just be a second place to get it wrong.
     *
     * @see AdminAuditLogService#clientIp — same root cause, same fix, worse consequence (forged
     *     forensic records rather than a rate-limit bypass).
     */
    private String clientIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }


    private static final class Window {
        final long startSecond;
        final AtomicInteger count = new AtomicInteger(0);

        Window(long startSecond) {
            this.startSecond = startSecond;
        }
    }

    /**
     * One memoised on-behalf verification: the subject the signature check proved, and the instant
     * past which it must be proved again. See {@link #onBehalfSubjectMemo}.
     */
    private record SubjectMemo(String subject, long expiresAtMillis) {}
}
