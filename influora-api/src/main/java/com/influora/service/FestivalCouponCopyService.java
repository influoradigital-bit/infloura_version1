package com.influora.service;

import com.influora.common.Ulids;
import com.influora.domain.FestivalEditions;
import com.influora.domain.enums.WorkspaceType;
import com.influora.repository.FestivalCouponCopyRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.service.security.AbuseThrottleService;
import com.influora.service.security.FestivalClientIpHasher;
import com.influora.web.dto.FestivalCouponCopyDtos.RecordCouponCopyRequest;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Public (UNAUTHENTICATED) write path for the Festival Box coupon-copy tracker (T-FESTIVALBOX-0905
 * phase 6), behind {@code POST /festival/coupon-copied}. See {@code
 * V20260905170000__festival_coupon_copies.sql} for the full design rationale (bounded daily
 * bucket, atomic upsert, no visitor data).
 *
 * <p><b>This is the SECOND unauthenticated INSERT in the application</b> (the first is {@code
 * FestivalEnquiryService}). Unlike that one, this endpoint has no honeypot and is explicitly
 * fire-and-forget — the client calls it from {@code navigator.clipboard.writeText}'s success
 * handler and must never be blocked or told anything useful (see {@code
 * FestivalCouponCopyController}), so there is no server response to hide information in either.
 * The abuse surface is: (1) {@code AuthRateLimitFilter}'s {@code tracking} bucket at the edge,
 * (2) this class's charset/length validation plus the bounded-bucket table shape, and — [Kabir
 * M-1/M-2] — (3) the per-origin daily influence cap in {@link #recordCopy}, which is the only one
 * of the three that bounds how far any single actor can move the NUMBER rather than how fast they
 * can call.
 *
 * <p><b>What {@code copy_count} is and is not.</b> It counts copy taps reported by a browser on a
 * public page. It is not a count of people, it is not deduplicated per visitor, and it is not
 * verifiable: an unauthenticated client is not a trustworthy reporter and no amount of server-side
 * work here can make it one. The cap in {@link #recordCopy} raises forgery from "one curl loop" to
 * "many distinct addresses"; it does not make the number an audited fact. Anyone presenting this
 * to a paying sponsor must present it as what it is — see {@code
 * AdminFestivalMetricsDtos.CouponCopyMetricsResponse}, which carries that caveat to the read side
 * so it cannot be lost between here and a slide.
 *
 * <p><b>[Kabir H-1] {@code edition} is checked too, and this is the fix for a real hole.</b> This
 * javadoc used to say "NO EDITION REGISTRY EXISTS IN THIS BACKEND" and concluded that bean
 * validation's charset/length bound "is the entire defense — there is nothing to look an edition up
 * against." That reasoning was sound about the data model and wrong about the consequence: with
 * only {@code sponsorSlug} checked, one real (published, therefore guessable) sponsor slug plus any
 * string matching {@code ^[A-Z0-9_]{1,64}$} minted a brand-new row per request, because the unique
 * key is {@code (edition, sponsor_slug, bucket_day)} and the edition component was attacker-chosen
 * across a 36^64 keyspace. Unauthenticated, unbounded row growth on production MySQL — from the one
 * endpoint whose entire daily-bucket design exists to prevent that. The migration's claim that
 * growth is "bounded by (recognized sponsors x days), not by request volume" was simply false.
 *
 * <p>The absence of an edition table did not mean there was nothing to check against; it meant the
 * list lived in a constant instead. {@link com.influora.domain.FestivalEditions} is now that list,
 * and an unknown edition is dropped exactly as an unknown sponsor is — same 204, no row. Growth is
 * bounded by (known editions x real sponsors x days), which is what the migration always claimed.
 *
 * <p>{@code sponsorSlug} is different: a provisioned Festival sponsor IS a real {@link
 * com.influora.domain.entity.Workspace} row ({@code FestivalSponsorProvisioningService} creates
 * one per won enquiry), and a workspace {@code slug} is the one finite, backend-owned "sponsor
 * identity" this codebase actually has. So {@code sponsorSlug} is checked against {@link
 * WorkspaceRepository#existsBySlugAndType} (BRAND) before a row is ever written — a well-formed
 * but unrecognized slug still gets the same 204 the caller always gets (never an error the caller
 * can distinguish — see the class-level "no enumeration signal" note below) but produces NO row,
 * which is what keeps the daily-bucket table's growth bounded by (real sponsors x days) rather
 * than by (arbitrary attacker strings x days). This does mean the {@code sponsorSlug} used on the
 * public page ({@code FestivalSponsor.slug} in {@code festival-editions.ts}, currently a
 * hand-authored content id, NOT wired to any workspace) must equal that sponsor's workspace slug
 * for copies to actually be counted — that wiring is outside this task's backend-only scope and is
 * called out here rather than silently assumed.
 *
 * <p><b>No enumeration signal:</b> every well-formed request gets the exact same 204 response
 * whether or not the sponsor is recognized, and whether a row was written or not — the caller
 * cannot tell the two cases apart from the response, timing aside. A malformed request (fails bean
 * validation) is the only case that gets a different (400) response, because that is a client
 * mistake in the request shape, not information about a specific sponsor/edition's existence.
 *
 * <p><b>On that "timing aside" — the timing side-channel here is real and does not matter, and
 * this is why.</b> [Kabir M-1] flagged that a recognized sponsor does DB work while an
 * unrecognized one returns early, so response time distinguishes them and leaks whether a slug is
 * a real BRAND workspace. That is true. It is also moot: {@code GET /workspaces/slug-check} is
 * {@code permitAll} (see {@code SecurityConfig}) and answers exactly that question directly,
 * exactly, and for free — it has to, because onboarding needs live slug-availability. A noisy
 * timing channel is not worth hardening while a loud, exact, documented one is a supported feature
 * next door.
 *
 * <p>Do NOT add constant-time padding or async dispatch here to "close" it: it would add real
 * complexity and a real failure mode (dropped writes on shutdown) to close the weaker of two doors
 * into the same room. If brand-slug enumeration is judged unacceptable, the thing to change is
 * {@code /workspaces/slug-check}, and this endpoint follows from that decision rather than leading
 * it.
 */
@Service
public class FestivalCouponCopyService {

    private static final Logger log = LoggerFactory.getLogger(FestivalCouponCopyService.class);

    /**
     * [Kabir M-1/M-2] How many copy taps ONE origin may contribute to ONE sponsor's counter per
     * day. 20 is set well above any believable human: a shopper taps a code once, occasionally
     * twice if the first paste went astray. Anything past 20 in a day from one address is not a
     * shopper, and the 21st onward is dropped.
     *
     * <p>This is not a rate limit in the usual sense — the edge already has one. It is an
     * INFLUENCE cap: the question it answers is not "is this caller hammering us" but "how much
     * can any single actor move a number Influora sells to a paying sponsor".
     */
    private static final long MAX_COPIES_PER_ORIGIN_PER_SPONSOR_PER_DAY = 20;

    private static final Duration COPY_THROTTLE_WINDOW = Duration.ofDays(1);

    private final FestivalCouponCopyRepository festivalCouponCopyRepository;
    private final WorkspaceRepository workspaceRepository;
    private final AbuseThrottleService abuseThrottleService;
    private final FestivalClientIpHasher clientIpHasher;

    public FestivalCouponCopyService(
            FestivalCouponCopyRepository festivalCouponCopyRepository,
            WorkspaceRepository workspaceRepository,
            AbuseThrottleService abuseThrottleService,
            FestivalClientIpHasher clientIpHasher) {
        this.festivalCouponCopyRepository = festivalCouponCopyRepository;
        this.workspaceRepository = workspaceRepository;
        this.abuseThrottleService = abuseThrottleService;
        this.clientIpHasher = clientIpHasher;
    }

    /**
     * Records one coupon-copy tap. Bean validation (charset + length on every field) has already
     * run by the time this is called — see {@code RecordCouponCopyRequest}. Never throws for an
     * unrecognized sponsor: it simply stores nothing, matching the controller's 204-always
     * contract.
     */
    public void recordCopy(RecordCouponCopyRequest body, String remoteAddr) {
        // [Kabir H-1] Checked BEFORE the sponsor lookup, deliberately: an unknown edition is
        // rejected without touching the database at all, so the cheap check gates the query rather
        // than the other way round. Normalizing (rather than just testing) is what guarantees the
        // stored value is always the canonical constant — a caller sending mixed case must not be
        // able to split one edition's counters across two spellings of the same bucket key.
        String edition = FestivalEditions.normalize(body.edition());
        if (edition == null) {
            // Same silent drop, same 204 as the unrecognized-sponsor path below. Logging the
            // rejected value would let a caller write arbitrary text into the log stream, so only
            // the sponsor slug (already validated charset, and about to be checked against a real
            // registry) is recorded.
            log.info("Coupon copy ignored: unrecognized edition sponsorSlug={}", body.sponsorSlug());
            return;
        }

        if (!workspaceRepository.existsBySlugAndType(body.sponsorSlug(), WorkspaceType.BRAND)) {
            // Deliberately no row, no exception, no distinguishable log level from the success
            // path's INFO line below beyond the message text (never surfaced to the caller) — an
            // unrecognized sponsor must be indistinguishable from a recognized one from OUTSIDE
            // this method.
            log.info("Coupon copy ignored: unrecognized sponsorSlug edition={}", edition);
            return;
        }

        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        // [Kabir M-1 cross-brand poisoning / M-2 copy_count inflation] Cap how much ONE origin can
        // add to THIS sponsor's counter today.
        //
        // The bug both findings describe is the same one seen from two sides. copy_count was an
        // unauthenticated `+ 1` with no per-caller bound at all beyond the edge's 60-second
        // tracking bucket, which resets forever. So:
        //
        //  - M-2: the number is inflatable without limit. The party with the strongest motive is
        //    the sponsor themselves, who is being sold on it.
        //  - M-1: sponsorSlug is chosen by the CALLER and every slug is printed on a public page,
        //    so anyone can attribute copies to ANY sponsor. A brand could inflate a rival's count
        //    into obvious nonsense and discredit the whole report, or quietly pad their own.
        //
        // The throttle key includes the sponsor slug on purpose. Keying on the origin alone would
        // mean a visitor who legitimately copies codes from several sponsors on one page burns one
        // shared budget and gets silently miscounted — punishing the exact engaged shopper the
        // page exists to produce. Per-sponsor, a real visitor never notices this cap.
        //
        // WHAT THIS DOES NOT DO, stated plainly because the number is sold to sponsors: it does not
        // make copy_count trustworthy. A distributed caller with many addresses still moves it, and
        // no client-reported signal from an unauthenticated public page can ever be verified — the
        // browser is not a trusted reporter. This raises the cost from "one curl loop" to "a
        // botnet", which is worth having, but the honest description of the metric is unchanged and
        // is documented on the read side (AdminFestivalMetricsService / CouponCopyMetricsResponse).
        //
        // Fails OPEN when there is no usable address (hash == null): a visitor whose address could
        // not be read has done nothing wrong, and dropping their tap would corrupt the count in the
        // other direction. The edge rate limit still applies to them.
        // [Kabir L-2] Every IPv6 prefix tier, not just /64 — an ISP allocates a customer a /56 or a
        // /48, so a /64-only cap of 20 was really 20 x 65,536 for anyone rotating inside their own
        // allocation. Coarser tiers get proportionally looser caps because a /48 can be a whole
        // office or carrier NAT, and one abusive host there must not silently zero the counts of
        // every real shopper who shares it. IPv4 yields one tier and is unchanged.
        List<String> originTiers = clientIpHasher.hashTiers(remoteAddr);
        for (int i = 0; i < originTiers.size(); i++) {
            long cap = MAX_COPIES_PER_ORIGIN_PER_SPONSOR_PER_DAY * (i == 0 ? 1 : i * 4);
            String throttleKey = "festival-copy:" + originTiers.get(i) + ":" + body.sponsorSlug();
            if (!abuseThrottleService.tryConsume(throttleKey, COPY_THROTTLE_WINDOW, cap)) {
                // Same silent 204 as every other drop here. Logged WITHOUT the origin hash — the
                // point of hashing is that this value never needs to appear anywhere a person
                // reads, and a log line pairing it with a sponsor and a time would re-create a
                // visitor trail the copies table deliberately refuses to keep.
                log.info(
                        "Coupon copy ignored: per-origin daily cap reached edition={} sponsorSlug={}",
                        edition,
                        body.sponsorSlug());
                return;
            }
        }

        festivalCouponCopyRepository.recordCopy(
                Ulids.newUlid(), edition, body.sponsorSlug(), body.couponCode(), today);

        // Sponsor slug and edition only — the coupon code itself is not logged here, consistent
        // with FestivalEnquiryService's "log the id/type, not the personal/business detail" call:
        // there is no id to log (this table has no per-event identity), so the identifying fields
        // are the bucket key.
        log.info(
                "Coupon copy recorded edition={} sponsorSlug={} day={}",
                edition,
                body.sponsorSlug(),
                today);
    }
}
