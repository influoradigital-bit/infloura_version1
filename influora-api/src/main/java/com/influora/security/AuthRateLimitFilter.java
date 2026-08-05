package com.influora.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
    private static final Pattern MEERA_TURN =
            Pattern.compile("^/meera/sessions/[^/]+/messages$");

    private final JwtService jwtService;

    public AuthRateLimitFilter(JwtService jwtService) {
        this.jwtService = jwtService;
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

    /** Requests per {@link #withdrawWindowSeconds}, per creator, for wallet withdrawal (M-K6-4). */
    @Value("${influora.wallet.withdraw-rate-limit-per-window:5}")
    private int creatorWithdrawLimit;

    /** Window (seconds) for the {@code creator-withdraw} bucket only — deliberately NOT {@link #windowSeconds}. */
    @Value("${influora.wallet.withdraw-rate-limit-window-seconds:3600}")
    private long withdrawWindowSeconds;

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
        response.setHeader("X-RateLimit-Limit", String.valueOf(limit));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(remaining));

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
        if (path.equals("/meera/voice/speak") || path.equals("/meera/voice/transcribe")) {
            return "meera-voice";
        }
        if (MEERA_TURN.matcher(path).matches()) {
            return "meera-turn";
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
            case "campaign-apply" -> campaignApplyLimit;
            case "creator-withdraw" -> creatorWithdrawLimit;
            case "meera-turn" -> meeraTurnLimit;
            case "meera-voice" -> meeraVoiceLimit;
            default -> sensitiveLimit;
        };
    }

    /** {@code creator-withdraw} uses its own (default: hourly) window; every other bucket shares {@link #windowSeconds}. */
    private long windowSecondsFor(String bucket) {
        return "creator-withdraw".equals(bucket) ? withdrawWindowSeconds : windowSeconds;
    }

    /**
     * User-keyed buckets are throttled per authenticated identity (JWT {@code sub}), falling back
     * to per-IP keying when no Bearer token is present or it does not parse — see class javadoc.
     * Every other bucket (sensitive/otp/refresh/meta-oauth/tracking) stays IP-keyed, unchanged.
     */
    private String rateLimitKey(HttpServletRequest request, String bucket) {
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
                    "meera-turn",
                    "meera-voice" ->
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
}
