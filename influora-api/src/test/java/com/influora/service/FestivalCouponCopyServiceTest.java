package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.domain.entity.FestivalCouponCopy;
import com.influora.domain.enums.WorkspaceType;
import com.influora.repository.FestivalCouponCopyRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.service.security.AbuseThrottleService;
import com.influora.service.security.FestivalClientIpHasher;
import com.influora.web.dto.FestivalCouponCopyDtos.RecordCouponCopyRequest;
import java.lang.reflect.Field;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Covers {@link FestivalCouponCopyService}'s logic (T-FESTIVALBOX-0905 phase 6): the "recognized
 * sponsor" gate, the atomic-upsert call shape, and — by reflection — that {@link
 * FestivalCouponCopy} carries no visitor-identifying column at all.
 *
 * <p>Plain Mockito over the service, same convention as {@code FestivalEnquiryServiceTest}: this
 * proves the service's LOGIC, not that the native upsert query actually executes correctly against
 * a real database (that is {@code FestivalCouponCopyRepositoryUpsertTest}'s job).
 */
@ExtendWith(MockitoExtension.class)
class FestivalCouponCopyServiceTest {

    @Mock private FestivalCouponCopyRepository repository;
    @Mock private WorkspaceRepository workspaceRepository;
    @Mock private AbuseThrottleService abuseThrottleService;
    @Mock private FestivalClientIpHasher clientIpHasher;

    private static final String REMOTE_ADDR = "203.0.113.7";
    /**
     * Shaped like the real thing — 64 hex chars, and crucially NOT containing the address it stands
     * for. The first version of this constant was the readable "hash-of-203.0.113.7", which made
     * {@code throttleKeyNeverCarriesRawAddress} fail against correct production code: the fixture,
     * not the key builder, was leaking the address. A stand-in for a hash must not be substring-
     * related to its input, or every "the raw value never appears" assertion built on it is
     * meaningless in one direction and a false alarm in the other.
     */
    private static final String ORIGIN_HASH =
            "9f2c4e7a1b8d3f60c5a9e2b7d41f8c3609ab5d7e2f4c1908b6d3a5e7c9f2b4d16";

    private FestivalCouponCopyService service() {
        return new FestivalCouponCopyService(
                repository, workspaceRepository, abuseThrottleService, clientIpHasher);
    }

    /**
     * [Kabir M-1/M-2] Default for tests that are not about the influence cap: a resolvable origin
     * that is within its budget. lenient() because the tests which drop before the cap is reached
     * (unknown edition, unknown sponsor) never consult either collaborator.
     */
    private void originWithinCap() {
        lenient().when(clientIpHasher.hashTiers(REMOTE_ADDR)).thenReturn(java.util.List.of(ORIGIN_HASH));
        lenient()
                .when(abuseThrottleService.tryConsume(anyString(), any(), anyLong()))
                .thenReturn(true);
    }

    private static RecordCouponCopyRequest request(String edition, String sponsorSlug, String code) {
        return new RecordCouponCopyRequest(edition, sponsorSlug, code);
    }

    @Test
    @DisplayName("recognized sponsor: recordCopy is called with edition/sponsorSlug/couponCode and today's UTC date")
    void recognizedSponsor_recordsCopy() {
        originWithinCap();
        when(workspaceRepository.existsBySlugAndType("acme-corp", WorkspaceType.BRAND))
                .thenReturn(true);

        service().recordCopy(request("MUMBAI_FESTIVE_2026", "acme-corp", "FEST15"), REMOTE_ADDR);

        ArgumentCaptor<String> idCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<LocalDate> dayCaptor = ArgumentCaptor.forClass(LocalDate.class);
        verify(repository)
                .recordCopy(
                        idCaptor.capture(),
                        eq("MUMBAI_FESTIVE_2026"),
                        eq("acme-corp"),
                        eq("FEST15"),
                        dayCaptor.capture());
        assertFalse(idCaptor.getValue().isBlank());
        assertTrue(dayCaptor.getValue().isEqual(LocalDate.now(java.time.ZoneOffset.UTC)));
    }

    @Test
    @DisplayName("unrecognized sponsorSlug: no row is written, and the call does not throw")
    void unrecognizedSponsor_storesNothing() {
        when(workspaceRepository.existsBySlugAndType("ghost-brand", WorkspaceType.BRAND))
                .thenReturn(false);

        service().recordCopy(request("MUMBAI_FESTIVE_2026", "ghost-brand", "FEST15"), REMOTE_ADDR);

        verify(repository, never()).recordCopy(anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("two copies for a recognized sponsor call the upsert twice, once per copy")
    void twoCopiesForRecognizedSponsor_callsUpsertTwice() {
        originWithinCap();
        when(workspaceRepository.existsBySlugAndType("acme-corp", WorkspaceType.BRAND))
                .thenReturn(true);
        FestivalCouponCopyService svc = service();

        svc.recordCopy(request("MUMBAI_FESTIVE_2026", "acme-corp", "FEST15"), REMOTE_ADDR);
        svc.recordCopy(request("MUMBAI_FESTIVE_2026", "acme-corp", "FEST15"), REMOTE_ADDR);

        verify(repository, times(2))
                .recordCopy(
                        anyString(),
                        eq("MUMBAI_FESTIVE_2026"),
                        eq("acme-corp"),
                        eq("FEST15"),
                        any());
    }

    // ----------------------------------------------------------------------------------------
    // [Kabir H-1] edition is part of the (edition, sponsor_slug, bucket_day) unique key, so an
    // unvalidated edition meant one new row per request across a 36^64 keyspace on a PUBLIC,
    // UNAUTHENTICATED endpoint. These four tests pin the fix.
    // ----------------------------------------------------------------------------------------

    @Test
    @DisplayName("[H-1] unknown edition: no row written even when the sponsor IS recognized")
    void unknownEdition_storesNothing() {
        // The sponsor repository is deliberately NOT stubbed. Under strict stubs this asserts
        // something the verify() below cannot: that the edition check short-circuits BEFORE the
        // database is touched at all, so garbage traffic costs no query either. If the order were
        // ever flipped, existsBySlugAndType would return Mockito's default false and this test
        // would still pass its verify — hence the unstubbed mock doing the real work here.
        service().recordCopy(request("DELHI_FESTIVE_2027", "acme-corp", "FEST15"), REMOTE_ADDR);

        verify(workspaceRepository, never()).existsBySlugAndType(anyString(), any());
        verify(repository, never()).recordCopy(anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("[H-1] the attack shape: 3 distinct made-up editions on one REAL sponsor write 0 rows")
    void distinctUnknownEditions_writeNoRows() {
        // lenient(), and it is load-bearing. The attack requires a sponsor slug that IS recognized
        // — that is the whole premise (slugs are published on the page, so an attacker has one).
        // Without this stub the mock returns false and the sponsor gate blocks the writes, so the
        // test passes identically against the pre-fix code and proves nothing; falsification caught
        // exactly that. It must be lenient because the fix makes the stub unused: the edition check
        // now short-circuits first, which is the behaviour being asserted.
        lenient()
                .when(workspaceRepository.existsBySlugAndType("acme-corp", WorkspaceType.BRAND))
                .thenReturn(true);
        FestivalCouponCopyService svc = service();

        // Exactly what an attacker holding one published sponsor slug would send: same everything,
        // one character of edition different each time. Pre-fix each of these produced a brand-new
        // row, because each is a distinct unique-key tuple.
        svc.recordCopy(request("MUMBAI_FESTIVE_2026_A", "acme-corp", "FEST15"), REMOTE_ADDR);
        svc.recordCopy(request("MUMBAI_FESTIVE_2027", "acme-corp", "FEST15"), REMOTE_ADDR);
        svc.recordCopy(request("AAAAAAAAAAAAAAAAAAAA", "acme-corp", "FEST15"), REMOTE_ADDR);

        verify(repository, never()).recordCopy(anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("[H-1] a known edition in odd casing is stored canonically, not as sent")
    void knownEditionIsNormalizedBeforeStorage() {
        originWithinCap();
        when(workspaceRepository.existsBySlugAndType("acme-corp", WorkspaceType.BRAND))
                .thenReturn(true);

        // HONEST SCOPE: over HTTP this exact input cannot arrive — RecordCouponCopyRequest's
        // @Pattern ^[A-Z0-9_]{1,64}$ rejects the lowercase and the spaces with a 400 first. This
        // pins the SERVICE's contract, which is that what reaches the database is the canonical
        // constant and never the caller's string. It matters because the obvious lighter fix —
        // `if (!FestivalEditions.isKnown(body.edition())) return;` followed by storing
        // body.edition() — passes every other test in this class while leaving the door open the
        // moment that @Pattern is loosened or a second caller appears. Two spellings of one
        // edition are two rows under one bucket key: every sponsor report silently split in half.
        service().recordCopy(request("  mumbai_festive_2026  ", "acme-corp", "FEST15"), REMOTE_ADDR);

        verify(repository)
                .recordCopy(anyString(), eq("MUMBAI_FESTIVE_2026"), eq("acme-corp"), eq("FEST15"), any());
    }

    @Test
    @DisplayName("[H-1] a null edition is dropped, not NPE'd")
    void nullEdition_storesNothing() {
        // Recognized sponsor for the same reason as the test above — otherwise the sponsor gate,
        // not the null handling, is what stops the write, and the test would pass against code
        // that NPEs on a null edition.
        lenient()
                .when(workspaceRepository.existsBySlugAndType("acme-corp", WorkspaceType.BRAND))
                .thenReturn(true);

        // Bean validation makes this unreachable over HTTP, but the service is a public method and
        // must not depend on its caller for null-safety — FestivalEnquiryService's own edition
        // handling is called with untrimmed nullable input for exactly this reason.
        service().recordCopy(request(null, "acme-corp", "FEST15"), REMOTE_ADDR);

        // any(), NOT anyString(), and that is the entire point of this line. Since Mockito 2,
        // anyString() does not match null — it is `any String` in the type sense, not "any
        // argument". Written with anyString() (as every other never()-verify in this class is,
        // correctly, since their arguments are non-null) this test passed against the pre-fix code
        // that DID call recordCopy with a null edition: the call happened, the matcher simply
        // failed to see it, and never() was satisfied by a verification that could not have
        // matched anything. Falsification caught it; nothing else would have.
        verify(repository, never()).recordCopy(any(), any(), any(), any(), any());
    }

    // ----------------------------------------------------------------------------------------
    // [Kabir M-1 cross-brand poisoning / M-2 copy_count inflation] copy_count is the demand signal
    // Influora reports to a paying sponsor, and it was an unauthenticated `+ 1` with no per-caller
    // bound beyond a 60-second edge bucket that resets forever.
    // ----------------------------------------------------------------------------------------

    @Test
    @DisplayName("[M-2] an origin over its daily cap for this sponsor adds nothing to the counter")
    void originOverCap_doesNotIncrement() {
        when(clientIpHasher.hashTiers(REMOTE_ADDR)).thenReturn(java.util.List.of(ORIGIN_HASH));
        when(abuseThrottleService.tryConsume(anyString(), any(), anyLong())).thenReturn(false);
        lenient()
                .when(workspaceRepository.existsBySlugAndType("acme-corp", WorkspaceType.BRAND))
                .thenReturn(true);

        service().recordCopy(request("MUMBAI_FESTIVE_2026", "acme-corp", "FEST15"), REMOTE_ADDR);

        verify(repository, never()).recordCopy(anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("[M-1] the cap is keyed per SPONSOR, so one visitor copying two sponsors' codes"
            + " does not spend one shared budget")
    void capIsPerSponsor() {
        when(clientIpHasher.hashTiers(REMOTE_ADDR)).thenReturn(java.util.List.of(ORIGIN_HASH));
        when(abuseThrottleService.tryConsume(anyString(), any(), anyLong())).thenReturn(true);
        when(workspaceRepository.existsBySlugAndType(anyString(), eq(WorkspaceType.BRAND)))
                .thenReturn(true);
        FestivalCouponCopyService svc = service();

        svc.recordCopy(request("MUMBAI_FESTIVE_2026", "acme-corp", "FEST15"), REMOTE_ADDR);
        svc.recordCopy(request("MUMBAI_FESTIVE_2026", "other-brand", "OTHER10"), REMOTE_ADDR);

        // Two DIFFERENT throttle keys. Keying on the origin alone would make an engaged shopper —
        // exactly the visitor the page exists to produce — burn one budget across every sponsor and
        // be silently undercounted.
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(abuseThrottleService, times(2)).tryConsume(keyCaptor.capture(), any(), anyLong());
        assertEquals(2, keyCaptor.getAllValues().stream().distinct().count(),
                "throttle keys must differ per sponsor, got: " + keyCaptor.getAllValues());
        assertTrue(keyCaptor.getAllValues().get(0).contains("acme-corp"));
        assertTrue(keyCaptor.getAllValues().get(1).contains("other-brand"));
    }

    @Test
    @DisplayName("[M-1/M-2] the throttle key carries the HASH, never the raw address")
    void throttleKeyNeverCarriesRawAddress() {
        when(clientIpHasher.hashTiers(REMOTE_ADDR)).thenReturn(java.util.List.of(ORIGIN_HASH));
        when(abuseThrottleService.tryConsume(anyString(), any(), anyLong())).thenReturn(true);
        when(workspaceRepository.existsBySlugAndType("acme-corp", WorkspaceType.BRAND))
                .thenReturn(true);

        service().recordCopy(request("MUMBAI_FESTIVE_2026", "acme-corp", "FEST15"), REMOTE_ADDR);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(abuseThrottleService).tryConsume(keyCaptor.capture(), any(), anyLong());
        // The whole point of hashing is that the address never reaches storage. abuse_throttle_
        // counters persists this key, so a raw address here would put visitor data in the database
        // by the back door — the exact thing festival_coupon_copies' migration forbids.
        assertFalse(
                keyCaptor.getValue().contains(REMOTE_ADDR),
                "raw client address leaked into a persisted throttle key: " + keyCaptor.getValue());
        assertTrue(keyCaptor.getValue().contains(ORIGIN_HASH));
    }

    @Test
    @DisplayName("[M-1/M-2] an unresolvable origin fails OPEN — the tap still counts")
    void unresolvableOrigin_stillRecords() {
        // hash() returns null when there is no usable address. A visitor whose address could not be
        // read has done nothing wrong; dropping their tap would corrupt the count downward, which is
        // no better than inflating it. The edge rate limit still applies to them.
        when(clientIpHasher.hashTiers(null)).thenReturn(java.util.List.of());
        when(workspaceRepository.existsBySlugAndType("acme-corp", WorkspaceType.BRAND))
                .thenReturn(true);

        service().recordCopy(request("MUMBAI_FESTIVE_2026", "acme-corp", "FEST15"), null);

        verify(repository).recordCopy(anyString(), eq("MUMBAI_FESTIVE_2026"), eq("acme-corp"), eq("FEST15"), any());
        verify(abuseThrottleService, never()).tryConsume(anyString(), any(), anyLong());
    }

    @Test
    @DisplayName(
            "FestivalCouponCopy stores NO visitor-identifying field — no ip/ipHash/userAgent/session"
                    + " column of any kind")
    void entityStoresNoVisitorData() {
        for (Field field : FestivalCouponCopy.class.getDeclaredFields()) {
            String name = field.getName().toLowerCase(java.util.Locale.ROOT);
            assertFalse(
                    name.contains("ip") || name.contains("agent") || name.contains("session"),
                    "FestivalCouponCopy must never carry a visitor-identifying field, found: " + field.getName());
        }
    }
}
