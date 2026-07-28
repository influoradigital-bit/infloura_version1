package com.influora.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
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
 *   <li><b>Trusted-proxy {@code X-Forwarded-For}</b> (Wave-1 S6 / Kabir audit H-4) — {@link
 *       #clientIp} only honors XFF when the direct socket peer is a configured trusted proxy
 *       ({@link #trustedProxiesRaw}); otherwise every IP-keyed bucket keys on the raw socket peer,
 *       so a spoofed XFF header can never be used to evade throttling.
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

    /**
     * [SEC: Wave-1 S6 / Kabir audit H-4] Comma-separated allow-list of trusted reverse-proxy
     * socket addresses (the L4 peer this app actually accepts connections from, e.g. the load
     * balancer / ingress IP). {@code X-Forwarded-For} is honored ONLY when the direct socket peer
     * is in this list. Empty by default: with no trusted proxy configured, the spoofable XFF
     * header is ignored entirely and the socket peer address is used as the rate-limit key —
     * fail-safe. Without this, any client could set {@code X-Forwarded-For: <random>} on every
     * request and trivially evade every IP-keyed bucket above by rotating a header value,
     * defeating the brute-force/enumeration/abuse defense this filter exists for.
     */
    @Value("${influora.security.trusted-proxies:}")
    private String trustedProxiesRaw;

    private volatile Set<String> trustedProxies;

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
        String path = decode(stripContext(request.getRequestURI()));
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
     * [SEC: Wave-1 S6 / Kabir audit H-4] {@code X-Forwarded-For} is trusted ONLY when the direct
     * socket peer ({@link HttpServletRequest#getRemoteAddr()}) is a configured trusted proxy (see
     * {@link #trustedProxiesRaw}). Otherwise the header is ignored and the socket peer is used —
     * a spoofed XFF from an untrusted client can never move a request into a different (or fresh)
     * rate-limit bucket. Fail-safe default: no trusted proxies configured => always use the peer.
     */
    private String clientIp(HttpServletRequest request) {
        String peer = request.getRemoteAddr();
        if (peer != null && trustedProxies().contains(peer)) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                int comma = forwarded.indexOf(',');
                String first = (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
                if (!first.isEmpty()) {
                    // Left-most XFF entry is the originating client as reported by our OWN
                    // trusted proxy. Safe precisely because we only reach here when the socket
                    // peer is that trusted proxy; an untrusted direct caller's XFF is never
                    // consulted.
                    return first;
                }
            }
        }
        return peer;
    }

    /** Lazily parses and caches the trusted-proxy allow-list from {@link #trustedProxiesRaw}. */
    private Set<String> trustedProxies() {
        Set<String> cached = trustedProxies;
        if (cached == null) {
            String raw = trustedProxiesRaw == null ? "" : trustedProxiesRaw;
            cached =
                    Arrays.stream(raw.split(","))
                            .map(String::trim)
                            .filter(s -> !s.isEmpty())
                            .collect(Collectors.toUnmodifiableSet());
            trustedProxies = cached;
        }
        return cached;
    }

    private static final class Window {
        final long startSecond;
        final AtomicInteger count = new AtomicInteger(0);

        Window(long startSecond) {
            this.startSecond = startSecond;
        }
    }
}
