package com.influora.service.security;

import com.influora.common.Ulids;
import com.influora.repository.AbuseThrottleCounterRepository;
import com.influora.domain.entity.AbuseThrottleCounter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Shared atomic fixed-window throttle (T-FESTIVALBOX-0905 phase 9), backing every abuse cap in the
 * app that must hold under concurrency — not just a single-threaded happy path. See the
 * {@code V20260905190000__abuse_throttle_counters.sql} migration header for the full rationale and
 * {@link AbuseThrottleCounterRepository#increment} for exactly why this is one native
 * {@code INSERT ... ON DUPLICATE KEY UPDATE} rather than a find-then-increment-then-save or a
 * {@code PESSIMISTIC_WRITE} row lock.
 *
 * <p>Callers: {@code FestivalEnquiryService#enforceThrottle} (per-IP-/64 and per-canonical-email
 * caps on the public enquiry form) and {@code PortfolioService#contact} (per-recipient-creator cap,
 * so a caller that rotates source IP is still bounded — the edge {@code AuthRateLimitFilter}
 * bucket handles the single-IP case; this handles the "different IP every request" case). A new
 * caller picks its OWN literal key prefix (e.g. {@code "festival-ip:"}, {@code "festival-email:"},
 * {@code "portfolio-contact:"}) so two unrelated call sites can never collide on the same counter
 * row by accident — the schema does not and cannot enforce that; it is a convention.
 *
 * <p><b>{@link #tryConsume} always increments first, then decides.</b> That is a deliberate choice,
 * not an oversight: an attempt that reaches this call already cost the caller a request (the
 * upstream service has already run bean validation, cross-field checks, and — where one exists — the
 * honeypot check), so a REFUSED attempt still consumes budget for its key. The previous
 * count-then-insert throttle in {@code FestivalEnquiryService} effectively gave refused attempts a
 * free pass (a rejected request wrote no row, so it was never counted by the next check either) —
 * that was never a deliberate design choice, just a side effect of counting saved rows instead of
 * attempts, and this class does not repeat it.
 *
 * <p><b>[Kabir L-3] That paragraph was aspirational, not true, until this fix — and the mechanism
 * that broke it is worth understanding before touching the propagation below.</b> {@code
 * tryConsume} was {@code REQUIRED}, so it JOINED the caller's transaction. Every caller refuses by
 * throwing {@code ApiException} (a {@code RuntimeException}) from inside its own
 * {@code @Transactional} method, which rolls that transaction back — <b>including this increment</b>.
 * So a refused attempt cost the attacker nothing at all: the counter returned to exactly where it
 * had been, which is precisely the "free probes against the cap boundary" this class was written to
 * eliminate. The claim was made in good faith and the code sat one {@code throw} away from
 * contradicting it, with no test able to see the difference because a Mockito test never has a real
 * transaction to roll back.
 *
 * <p>{@code REQUIRES_NEW} fixes it: the increment and its read-back run in their own transaction
 * that COMMITS before {@code tryConsume} returns, so the caller's later rollback cannot undo it.
 * The read-back is still safe because both statements are inside that same new transaction — the
 * warning below about never splitting increment from read is about those two, not about the outer
 * transaction.
 *
 * <p>The cost is honest and worth stating: {@code REQUIRES_NEW} suspends the caller's transaction
 * and takes a SECOND pooled connection for the duration. Callers that invoke this several times per
 * request (the enquiry form runs several tiers) hold two connections briefly each time. That is the
 * same trade {@code IdempotencyService} already makes for its reservations. If the pool is ever
 * sized near the concurrency limit this is a place to look — but a throttle that silently rolls
 * itself back is not a throttle, so correctness wins here.
 */
@Service
public class AbuseThrottleService {

    private final AbuseThrottleCounterRepository repository;

    public AbuseThrottleService(AbuseThrottleCounterRepository repository) {
        this.repository = repository;
    }

    /**
     * Atomically records one attempt against {@code throttleKey} and reports whether it is still
     * within {@code maxPerWindow} for the current fixed {@code window}-sized bucket.
     *
     * <p>[Kabir L-3] {@code REQUIRES_NEW} propagation is deliberate — see the class javadoc for why
     * {@code REQUIRED} silently made every refusal free. The increment and the read-back below run
     * together in this method's OWN transaction, so the read still observes the just-incremented row
     * via InnoDB's "a transaction always sees its own writes" guarantee, and the commit survives the
     * caller throwing. See
     * {@link AbuseThrottleCounterRepository#findByThrottleKeyAndWindowStart} for why that matters and
     * why this method must never split the increment and the read across two transactions.
     *
     * @return {@code true} if this attempt is within the cap (the request may proceed), {@code
     *     false} if the bucket has already reached {@code maxPerWindow} INCLUDING this attempt (the
     *     caller must refuse the request).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean tryConsume(String throttleKey, Duration window, long maxPerWindow) {
        String storedKey = boundKey(throttleKey);
        Instant windowStart = truncateToWindow(Instant.now(), window);
        repository.increment(Ulids.newUlid(), storedKey, windowStart);

        long count =
                repository
                        .findByThrottleKeyAndWindowStart(storedKey, windowStart)
                        .map(AbuseThrottleCounter::getRequestCount)
                        // Unreachable in practice — increment() above just created or updated this
                        // exact row in this same transaction — but fail CLOSED (deny) rather than
                        // silently letting an unbounded caller through if it ever is.
                        .orElse(Long.MAX_VALUE);

        return count <= maxPerWindow;
    }

    /** {@code abuse_throttle_counters.throttle_key} is {@code VARCHAR(255)}. */
    private static final int MAX_KEY_LENGTH = 255;

    /**
     * Reserved for the {@code |h:} marker plus a 64-char SHA-256 hex digest, so a truncated prefix
     * plus the digest always fits inside {@link #MAX_KEY_LENGTH}.
     */
    private static final int HASHED_KEY_PREFIX_BUDGET = MAX_KEY_LENGTH - 64 - 3;

    /**
     * [Kabir L-1] Guarantees the key fits the column, whatever a caller passes.
     *
     * <p>THE BUG: {@code FestivalEnquiryService} builds {@code "festival-email:" + email}. {@code
     * SubmitEnquiryRequest.email} is bounded at {@code @Size(max = 255)} and canonicalisation only
     * strips a {@code +tag}, so any address longer than 240 characters produced a key longer than
     * the column. On MySQL's default strict mode that is a {@code DataIntegrityViolationException}
     * — an unauthenticated 500 on a public form, triggerable by anyone who can type a long address
     * that still satisfies {@code @Email}. On a non-strict deployment it is worse than a 500: MySQL
     * silently TRUNCATES, so two different long addresses collapse onto one counter and throttle
     * each other.
     *
     * <p>Fixed HERE rather than at the call site on purpose. This class owns the column, so it owns
     * the bound; a caller that has to know the storage width in order to build a key safely is a
     * caller that will eventually get it wrong — and the next one will not even know to ask. Every
     * present and future caller is now safe by construction.
     *
     * <p>Long keys are HASHED, never truncated. Truncation makes distinct keys collide, which
     * silently merges two victims' budgets — a correctness bug that looks like nothing. The
     * readable prefix is kept ahead of the digest so a key is still recognisable when someone is
     * reading rows during an incident.
     */
    private static String boundKey(String throttleKey) {
        if (throttleKey == null || throttleKey.length() <= MAX_KEY_LENGTH) {
            return throttleKey;
        }
        try {
            String digest =
                    HexFormat.of()
                            .formatHex(
                                    MessageDigest.getInstance("SHA-256")
                                            .digest(throttleKey.getBytes(StandardCharsets.UTF_8)));
            return throttleKey.substring(0, HASHED_KEY_PREFIX_BUDGET) + "|h:" + digest;
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is JDK-mandated, so unreachable. Falling back to plain truncation accepts the
            // collision risk described above rather than letting a throttle check throw and take
            // down the request it was supposed to be protecting — failing to throttle is bad, but a
            // throttle that 500s the endpoint is the outage the whole finding is about.
            return throttleKey.substring(0, MAX_KEY_LENGTH);
        }
    }

    /**
     * Floors {@code instant} down to the start of its fixed {@code window}-sized bucket (e.g. the
     * top of the clock hour for an hourly window) — NOT a sliding "now minus window" boundary. See
     * the migration header's "FIXED HOUR BUCKETS, NOT A SLIDING WINDOW" note for the trade this
     * makes (a short-lived double burst across a boundary can pass) in exchange for being atomically
     * checkable in a single upsert instead of a range scan.
     */
    private static Instant truncateToWindow(Instant instant, Duration window) {
        long windowSeconds = window.getSeconds();
        long epochSeconds = instant.getEpochSecond();
        long bucketStartSeconds = epochSeconds - Math.floorMod(epochSeconds, windowSeconds);
        return Instant.ofEpochSecond(bucketStartSeconds);
    }
}
