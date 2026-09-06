package com.influora.service;

import com.influora.common.ApiException;
import com.influora.common.TextSanitizer;
import com.influora.common.Ulids;
import com.influora.domain.FestivalEditions;
import com.influora.domain.entity.FestivalEnquiry;
import com.influora.domain.enums.FestivalEnquiryType;
import com.influora.domain.enums.FestivalTier;
import com.influora.repository.FestivalEnquiryRepository;
import com.influora.service.security.AbuseThrottleService;
import com.influora.service.security.FestivalClientIpHasher;
import com.influora.web.dto.FestivalEnquiryDtos.SubmitEnquiryRequest;
import com.influora.web.dto.FestivalEnquiryDtos.SubmitEnquiryResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Public (UNAUTHENTICATED) write path for the Festival Box enquiry form (T-FESTIVALBOX-0905),
 * behind {@code POST /festival-enquiries}.
 *
 * <p><b>This is the only unauthenticated INSERT in the application.</b> Every other permitAll route
 * in {@code SecurityConfig} either reads ({@code /portfolio/*}, {@code /config/public}) or
 * authenticates by HMAC ({@code /webhooks/*}). There is no signature to check here — the caller is
 * a stranger with a browser — so the trust boundary is entirely this class:
 *
 * <ul>
 *   <li><b>Bean validation</b> bounds every field's length/shape before the payload arrives.
 *   <li><b>Cross-field requirements</b> per {@code type} (a BRAND must name a company, a CREATOR
 *       must give a handle) — not expressible in bean validation, enforced in {@link #submit}.
 *   <li><b>A honeypot</b> that answers bots with the same 200 a human gets, storing nothing.
 *   <li><b>A two-key, atomically-enforced throttle</b> (per {@code /64}-hashed source IP, per
 *       plus-tag-canonicalised email — see {@link FestivalClientIpHasher} and {@link
 *       #canonicalizeEmailForThrottle}) backed by {@link AbuseThrottleService}'s upsert counter, so
 *       neither IPv6 address rotation nor plus-addressing nor concurrent requests can bypass it.
 *   <li><b>URL scheme validation</b> so a stored {@code website} can never be a {@code javascript:}
 *       payload that the admin console later renders as a clickable link.
 *   <li><b>Sanitization</b> of every free-text field via {@link TextSanitizer}.
 * </ul>
 *
 * <p>The other public form on this platform, {@code PortfolioService#contact}, had NO throttle at
 * all despite {@code SecurityConfig}'s comment once claiming otherwise (T-FESTIVALBOX-0905 phase 9
 * closed that gap too — an edge {@code AuthRateLimitFilter} bucket plus a per-recipient-creator
 * {@link AbuseThrottleService} cap, the same mechanism this class uses).
 */
@Service
public class FestivalEnquiryService {

    private static final Logger log = LoggerFactory.getLogger(FestivalEnquiryService.class);

    /**
     * Throttle window and caps. Sized for a marketing page, not an API: a real brand submits once,
     * and a genuine "I mistyped my email, let me resend" retry is 2-3 attempts. Six per hour per
     * origin leaves that comfortable while making bulk insertion pointless.
     */
    private static final Duration THROTTLE_WINDOW = Duration.ofHours(1);

    private static final long MAX_PER_IP_PER_WINDOW = 6;
    private static final long MAX_PER_EMAIL_PER_WINDOW = 3;

    /** Mirrors Instagram's allowed handle shape and enforces the VARCHAR(80) column for free. */
    private static final Pattern HANDLE_PATTERN = Pattern.compile("^[a-zA-Z0-9._]{1,80}$");

    /**
     * A self-reported follower count above this is a typo or a joke, not a creator. Bounded here as
     * well as by the column so an absurd value never reaches an admin's roster shortlist.
     */
    private static final long MAX_PLAUSIBLE_FOLLOWERS = 1_000_000_000L;

    /**
     * Fallback when the page does not name an edition — now the shared {@link FestivalEditions}
     * constant rather than a second copy of the literal, since [Kabir H-1] gave the coupon-copy
     * endpoint a real edition registry to check against and two hardcoded spellings of the same
     * default in two services is exactly how they drift apart.
     */
    private static final String DEFAULT_EDITION = FestivalEditions.DEFAULT;

    private final FestivalEnquiryRepository festivalEnquiryRepository;
    private final AbuseThrottleService abuseThrottleService;

    /**
     * [Kabir M-1/M-2] The salted-hash logic that used to live in this class's private {@code
     * hashIp}/{@code normalizeForThrottle} now lives in {@link FestivalClientIpHasher}, because the
     * coupon-copy endpoint needs the identical identity and two copies of a security primitive
     * drift. Behaviour is unchanged — the extracted methods are the same code, and this class's
     * tests still pin the same throttle keys.
     */
    private final FestivalClientIpHasher clientIpHasher;

    public FestivalEnquiryService(
            FestivalEnquiryRepository festivalEnquiryRepository,
            AbuseThrottleService abuseThrottleService,
            FestivalClientIpHasher clientIpHasher) {
        this.festivalEnquiryRepository = festivalEnquiryRepository;
        this.abuseThrottleService = abuseThrottleService;
        this.clientIpHasher = clientIpHasher;
    }

    /**
     * Persist one enquiry from the public page.
     *
     * @param httpRequest used ONLY for {@code getRemoteAddr()} and the user agent. This must NOT
     *     read {@code X-Forwarded-For} directly — a client can put anything in that header, and
     *     trusting it would make the per-IP throttle a no-op.
     *     <p>[SEC: Kabir F-5] {@code getRemoteAddr()} is the real client IP here, but NOT because
     *     forwarded-header handling is switched off — an earlier version of this javadoc claimed
     *     that and had it exactly backwards. {@code application.yml} sets {@code
     *     forward-headers-strategy: native} (and all three prod compose files pin it), which
     *     ENABLES Tomcat's {@code RemoteIpValve}; the valve rewrites {@code getRemoteAddr()} from
     *     {@code X-Forwarded-For}, and it is safe only because it walks that header right-to-left
     *     against the {@code server.tomcat.remoteip.internal-proxies} allowlist, so a
     *     client-injected entry can never win. Caddy/nginx both append the true peer and connect
     *     from addresses inside that allowlist.
     *     <p>The distinction matters operationally: setting the strategy to {@code none} to match
     *     the old comment would make {@code getRemoteAddr()} return the reverse proxy's container
     *     IP for EVERY visitor — one shared hash, turning the 6/hour per-IP cap into a global cap
     *     that locks the public form for everyone after six submissions an hour.
     */
    @Transactional
    public SubmitEnquiryResponse submit(SubmitEnquiryRequest body, HttpServletRequest httpRequest) {
        // VALIDATION RUNS BEFORE THE HONEYPOT CHECK — the ordering is load-bearing, do not "optimise"
        // it by short-circuiting the trap first.
        //
        // [SEC: Kabir F-2] The original version checked the honeypot first, on the reasoning that a
        // bot should never reach validation. That reasoning was wrong, and it handed out a free
        // deterministic oracle: with the honeypot checked first, a deliberately-invalid payload
        // returned 200 WITH the trap field and 400 WITHOUT it. Two requests, no rows written, no
        // throttle consumed — and a crawler could binary-search the form's field names to find the
        // trap, then omit it forever. Validating first collapses both cases to the same 400, so the
        // presence of the honeypot field cannot be detected by flipping one input.
        FestivalEnquiryType type = parseType(body.type());
        String email = requireText(body.email(), "Email is required", "INVALID_EMAIL")
                .toLowerCase(Locale.ROOT);
        String name = sanitize(requireText(body.name(), "Name is required", "INVALID_NAME"), 120);
        String phone = trimToNull(body.phone());
        String message = sanitizeNullable(body.message(), 2000);
        // [Kabir H-1] An UNKNOWN edition falls back to the default here rather than being dropped
        // — the opposite of FestivalCouponCopyService's treatment, on purpose. This row is a real
        // sales lead with a human's name and email attached; discarding it because a stale link or
        // a typo named an edition we do not run would throw away revenue to save one row. The
        // coupon-copy table has no such value per row, so dropping is free there. Storage is
        // bounded here by the throttle and by the fact that each row costs a real enquiry, not by
        // the edition string.
        String edition = blankToDefault(FestivalEditions.normalize(body.edition()), DEFAULT_EDITION);

        // Honeypot: still BEFORE any DB read, so trap traffic cannot be used to probe how many
        // enquiries an address has sent, and still answered with the same success envelope a human
        // gets — a bot that can distinguish rejection from acceptance simply retries without the
        // field. What changed is only that it no longer precedes validation.
        if (body.honeypot() != null && !body.honeypot().isBlank()) {
            log.info("Festival enquiry rejected: honeypot filled");
            return accepted();
        }

        String ipHash = clientIpHasher.hash(httpRequest.getRemoteAddr());
        // [Kabir L-2] The THROTTLE uses every prefix tier; the PROVENANCE column below keeps the
        // single /64 identity. Two different jobs: one has to resist an address rotating inside a
        // customer's allocation, the other is one stable value an admin can group rows by.
        enforceThrottle(clientIpHasher.hashTiers(httpRequest.getRemoteAddr()), email);

        FestivalEnquiry enquiry =
                type == FestivalEnquiryType.BRAND
                        ? buildBrand(body, edition, name, email, phone, message)
                        : buildCreator(body, edition, name, email, phone, message);

        enquiry.applyProvenance(
                sanitizeNullable(body.utmSource(), 120),
                sanitizeNullable(body.utmMedium(), 120),
                sanitizeNullable(body.utmCampaign(), 120),
                ipHash,
                truncate(httpRequest.getHeader("User-Agent"), 300));

        festivalEnquiryRepository.save(enquiry);

        // Log the id and type only. The email is the submitter's personal data and an application
        // log is not the place it belongs — admin reads it from the row, which is access-controlled.
        log.info(
                "Festival enquiry stored id={} type={} edition={}",
                enquiry.getId(),
                type,
                edition);
        return accepted();
    }

    // ----------------------------------------------------------------------------------------
    // Per-type construction
    // ----------------------------------------------------------------------------------------

    private FestivalEnquiry buildBrand(
            SubmitEnquiryRequest body,
            String edition,
            String name,
            String email,
            String phone,
            String message) {
        String company =
                sanitize(
                        requireText(body.company(), "Company name is required", "INVALID_COMPANY"),
                        160);
        return FestivalEnquiry.brand(
                Ulids.newUlid(),
                edition,
                name,
                email,
                phone,
                company,
                normalizeWebsite(body.website()),
                parseTier(body.tier()),
                sanitizeNullable(body.productCategory(), 120),
                message);
    }

    private FestivalEnquiry buildCreator(
            SubmitEnquiryRequest body,
            String edition,
            String name,
            String email,
            String phone,
            String message) {
        String handle =
                normalizeHandle(
                        requireText(
                                body.instagramHandle(),
                                "Instagram handle is required",
                                "INVALID_HANDLE"));
        return FestivalEnquiry.creator(
                Ulids.newUlid(),
                edition,
                name,
                email,
                phone,
                handle,
                validateFollowers(body.followers()),
                sanitizeNullable(body.city(), 80),
                message);
    }

    // ----------------------------------------------------------------------------------------
    // Throttle
    // ----------------------------------------------------------------------------------------

    /**
     * Two independent caps, because either one alone is trivially bypassed: an IP-only cap loses to
     * a mobile network that rotates addresses — or to a residential IPv6 delegation, see {@link
     * #hashIp} — and an email-only cap loses to a script that invents an address per submission, or
     * to plus-addressing, see {@link #canonicalizeEmailForThrottle}. Both are counted against the
     * same fixed hourly window.
     *
     * <p>Answers 429, not a silent success, and does not say WHICH cap tripped — a real person who
     * hits this is retrying too fast and needs to be told to wait; a script gains nothing.
     *
     * <p>[SEC: Kabir red-team, T-FESTIVALBOX-0905 phase 9] This used to be {@code SELECT COUNT(*)}
     * against the IP hash, {@code SELECT COUNT(*)} against the email, then {@code save()} — all
     * inside one {@code @Transactional} method at Spring's default isolation (REPEATABLE READ on
     * MySQL/InnoDB). Under concurrency that is a classic TOCTOU: N parallel requests sharing a key
     * each ran their COUNT against a snapshot taken before any of the N inserts committed, every one
     * read "under the cap", and all N passed. {@link AbuseThrottleService#tryConsume} replaces both
     * reads with one atomic upsert per key (see that class and the {@code V20260905190000}
     * migration), so a second concurrent caller for the SAME key genuinely queues behind the first
     * one's row lock instead of reading a stale count — see
     * {@code FestivalEnquiryServiceThrottleConcurrencyTest} for the real (not mocked) proof.
     *
     * <p>A {@code PESSIMISTIC_WRITE} row lock (as {@code FestivalSponsorProvisioningService} takes)
     * is deliberately NOT used here: that lock protects a multi-step read-modify-DECIDE against a
     * second caller racing between the read and the decision. A throttle counter has no such
     * decision step to protect — "add one, then look at the total" is exactly what the counter
     * table's single upsert statement already gives atomically, with no lock held across a round
     * trip to application code, and no contention held on a row that an anonymous caller can grow
     * the table around.
     *
     * <p><b>Refused attempts consume budget.</b> {@link AbuseThrottleService#tryConsume} always
     * increments before it decides, so a request that reaches this method and is THEN refused by
     * either cap still counts against that key going forward. [Kabir L-3] That is only true because
     * {@code tryConsume} runs in {@code REQUIRES_NEW}: this method refuses by throwing from inside
     * {@code submit}'s transaction, which used to roll the increment back and hand out exactly the
     * free probes this paragraph promises it does not. That is deliberate: honeypot-tripped
     * and bean-validation-rejected requests never reach this method at all (see {@link #submit}), so
     * everything that does reach it has already cost real work (parsing, sanitizing, two throttle
     * checks); letting a refusal here be free — the pre-fix behavior, since the old COUNT queries
     * only ever counted successfully SAVED rows — would hand a script an unlimited number of free
     * probes against the cap boundary.
     */
    private void enforceThrottle(List<String> ipTiers, String email) {
        // [Kabir L-2] One cap per prefix tier, narrowest first. IPv4 yields a single tier, so this
        // loop is the old behaviour unchanged for it; IPv6 yields /64, /56 and /48.
        //
        // The multiplier is what makes the coarse tiers safe to enforce at all. A /48 can be a whole
        // campus or a carrier NAT pool, so reusing the per-/64 cap there would let one abusive host
        // lock out everyone sharing their organisation's allocation — a self-inflicted outage
        // dressed as a security control. Allowing 6, then 24, then 48 per hour keeps a realistic
        // number of genuine co-located submitters clear while cutting an address-rotation attack
        // from ~393,000/hour (6 x 65,536 available /64s in a /48) down to 48.
        long[] capByTier = {
            MAX_PER_IP_PER_WINDOW, MAX_PER_IP_PER_WINDOW * 4, MAX_PER_IP_PER_WINDOW * 8
        };
        for (int i = 0; i < ipTiers.size(); i++) {
            long cap = capByTier[Math.min(i, capByTier.length - 1)];
            if (!abuseThrottleService.tryConsume(
                    "festival-ip:" + ipTiers.get(i), THROTTLE_WINDOW, cap)) {
                // Tier index only — never the hash. A log line pairing a specific origin with a
                // time re-creates the visitor trail the hashing exists to avoid.
                log.warn("Festival enquiry throttled: per-IP cap reached at prefix tier {}", i);
                throw tooManyRequests();
            }
        }

        String canonicalEmail = canonicalizeEmailForThrottle(email);
        if (!abuseThrottleService.tryConsume(
                "festival-email:" + canonicalEmail, THROTTLE_WINDOW, MAX_PER_EMAIL_PER_WINDOW)) {
            log.warn("Festival enquiry throttled: per-email cap reached");
            throw tooManyRequests();
        }
    }

    private static ApiException tooManyRequests() {
        return new ApiException(
                "TOO_MANY_ENQUIRIES",
                "You have sent several enquiries recently. Please try again in an hour, or email us directly.",
                HttpStatus.TOO_MANY_REQUESTS);
    }

    /**
     * Canonicalises an email address for THROTTLE-COUNTING PURPOSES ONLY — never for storage.
     *
     * <p>[SEC: Kabir red-team F-1a, T-FESTIVALBOX-0905 phase 9] Strips a {@code +tag} from the local
     * part before counting: {@code attacker+1@x.com} .. {@code attacker+9999@x.com} are all
     * delivered to the SAME mailbox by any mail transport honouring RFC 5233 sub-addressing
     * (effectively every major provider, not only Gmail), so without this, each variant minted its
     * own fresh {@link #MAX_PER_EMAIL_PER_WINDOW}-per-hour allowance — the per-email cap in name
     * only.
     *
     * <p>Deliberately NOT applied to the STORED {@code email} column — {@link #submit}, {@link
     * #buildBrand} and {@link #buildCreator} all persist the caller's original, unmodified address.
     * A brand who legitimately writes {@code ops+festival@acme.in} must receive admin's reply at
     * that exact address; canonicalising storage would silently rewrite where a real enquiry gets
     * answered.
     *
     * <p>Deliberately does NOT also strip dots from the local part (Gmail's {@code
     * jane.doe@gmail.com} == {@code janedoe@gmail.com} folding). That rule is specific to Gmail's
     * mail transport (and a handful of others, each with their OWN different dot-handling);
     * applying it to every domain would wrongly merge two DISTINCT mailboxes at any provider where a
     * dot is significant — most of them.
     *
     * @param email already lower-cased by {@link #submit} before this is ever called.
     */
    private static String canonicalizeEmailForThrottle(String email) {
        int at = email.indexOf('@');
        if (at < 0) {
            // Unreachable via the real HTTP endpoint — @Email bean validation on
            // SubmitEnquiryRequest already requires an @-containing address before this class ever
            // sees the payload. A unit test that calls submit() directly can still bypass bean
            // validation, so this falls back to the raw string rather than throwing: a throttle-key
            // helper must never be able to turn a validation gap into a 500.
            return email;
        }
        String localPart = email.substring(0, at);
        String domain = email.substring(at + 1);
        int plus = localPart.indexOf('+');
        String canonicalLocalPart = plus >= 0 ? localPart.substring(0, plus) : localPart;
        return canonicalLocalPart + "@" + domain;
    }

    // ----------------------------------------------------------------------------------------
    // Field normalization
    // ----------------------------------------------------------------------------------------

    private static FestivalEnquiryType parseType(String raw) {
        try {
            return FestivalEnquiryType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ApiException(
                    "INVALID_TYPE", "type must be BRAND or CREATOR", HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Tier is optional on the wire: a brand that submits without picking one is recorded as
     * {@link FestivalTier#UNDECIDED} rather than rejected. An unrecognised value IS rejected — it
     * means the page and the enum have drifted, which should be loud, not silently coerced.
     */
    private static FestivalTier parseTier(String raw) {
        String trimmed = trimToNull(raw);
        if (trimmed == null) {
            return FestivalTier.UNDECIDED;
        }
        try {
            return FestivalTier.valueOf(trimmed.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ApiException(
                    "INVALID_TIER",
                    "tier must be GIFTING, FEATURED, TITLE or UNDECIDED",
                    HttpStatus.BAD_REQUEST);
        }
    }

    /** Strips a leading '@' and lower-cases, then enforces Instagram's actual handle shape. */
    private static String normalizeHandle(String raw) {
        String handle = raw.trim().toLowerCase(Locale.ROOT);
        if (handle.startsWith("@")) {
            handle = handle.substring(1);
        }
        if (!HANDLE_PATTERN.matcher(handle).matches()) {
            throw new ApiException(
                    "INVALID_HANDLE",
                    "Instagram handle may only contain letters, numbers, dots and underscores",
                    HttpStatus.BAD_REQUEST);
        }
        return handle;
    }

    /**
     * Accepts a bare domain and a full URL, and rejects everything else.
     *
     * <p>The rejection matters more than the convenience: the admin console renders this value as an
     * anchor, so storing {@code javascript:...} or {@code data:...} here would turn a public form
     * into stored XSS aimed at whoever opens the enquiry. Only http/https are allowed through, and a
     * scheme-less entry is prefixed with https rather than guessed at.
     */
    private static String normalizeWebsite(String raw) {
        String site = trimToNull(raw);
        if (site == null) {
            return null;
        }
        String lower = site.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            if (lower.contains(":")) {
                throw new ApiException(
                        "INVALID_WEBSITE",
                        "Website must be an http:// or https:// address",
                        HttpStatus.BAD_REQUEST);
            }
            site = "https://" + site;
        }
        return truncate(site, 500);
    }

    private static Long validateFollowers(Long followers) {
        if (followers == null) {
            return null;
        }
        if (followers > MAX_PLAUSIBLE_FOLLOWERS) {
            throw new ApiException(
                    "INVALID_FOLLOWERS", "Follower count is not plausible", HttpStatus.BAD_REQUEST);
        }
        return followers;
    }

    // ----------------------------------------------------------------------------------------
    // Small helpers
    // ----------------------------------------------------------------------------------------

    private static SubmitEnquiryResponse accepted() {
        return new SubmitEnquiryResponse(
                true, "Thanks — we have your enquiry. Our team will be in touch within 2 working days.");
    }

    private static String requireText(String value, String message, String code) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            throw new ApiException(code, message, HttpStatus.BAD_REQUEST);
        }
        return trimmed;
    }

    private static String sanitize(String value, int max) {
        return truncate(TextSanitizer.sanitizePlainText(value), max);
    }

    private static String sanitizeNullable(String value, int max) {
        String trimmed = trimToNull(value);
        return trimmed == null ? null : sanitize(trimmed, max);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null ? fallback : value;
    }

    /**
     * Truncates to the column width. Bean validation already bounds every field, but sanitization
     * runs after it and the free-text sanitizer can only shrink a string, never grow it — so this is
     * a belt-and-braces guard that keeps a future sanitizer change from turning into a flush-time
     * failure that discards the whole submission.
     */
    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
