package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.FestivalEnquiry;
import com.influora.domain.enums.FestivalEnquiryStatus;
import com.influora.domain.enums.FestivalEnquiryType;
import com.influora.domain.enums.FestivalTier;
import com.influora.repository.FestivalEnquiryRepository;
import com.influora.service.security.AbuseThrottleService;
import com.influora.service.security.FestivalClientIpHasher;
import com.influora.web.dto.FestivalEnquiryDtos.SubmitEnquiryRequest;
import com.influora.web.dto.FestivalEnquiryDtos.SubmitEnquiryResponse;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Covers the abuse controls on the ONLY unauthenticated INSERT in the application
 * (T-FESTIVALBOX-0905). {@code SecurityConfig}'s permitAll comment claims this endpoint has a real
 * throttle — these tests are what make that claim checkable rather than another comment that lies.
 *
 * <p>Note what these tests can and cannot see: they are Mockito tests over the service, so they
 * prove the service's LOGIC (a honeypot short-circuits, a cap throws, a scheme is rejected). They do
 * NOT validate the entity against the migration — a column-name drift between {@code
 * FestivalEnquiry} and {@code V20260905130000} would pass every assertion here and fail only at boot
 * under {@code ddl-auto=validate}. Column names are checked by diffing those two files, not here.
 */
@ExtendWith(MockitoExtension.class)
class FestivalEnquiryServiceTest {

    private static final String SALT = "test-salt-at-least-32-characters-long!!";

    @Mock private FestivalEnquiryRepository repository;
    @Mock private AbuseThrottleService abuseThrottleService;

    private FestivalEnquiryService service;
    private MockHttpServletRequest httpRequest;

    @BeforeEach
    void setUp() {
        service = new FestivalEnquiryService(repository, abuseThrottleService, new FestivalClientIpHasher(SALT));
        httpRequest = new MockHttpServletRequest();
        httpRequest.setRemoteAddr("203.0.113.9");
        httpRequest.addHeader("User-Agent", "Mozilla/5.0 (test)");
        // Default: every throttle check passes. `lenient()` because several tests below fail
        // validation BEFORE ever reaching enforceThrottle (e.g. an unknown `type`), so this stub is
        // never consulted in those tests — strict stubbing would otherwise flag it as unnecessary.
        lenient()
                .when(abuseThrottleService.tryConsume(anyString(), any(Duration.class), anyLong()))
                .thenReturn(true);
    }

    // ------------------------------------------------------------------------------------------
    // Happy paths
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("brand submission persists the brand half and leaves the creator half null")
    void brandSubmissionPersistsBrandHalf() {
        SubmitEnquiryResponse response = service.submit(brand().build(), httpRequest);

        assertTrue(response.received());
        FestivalEnquiry saved = captureSaved();

        assertEquals(FestivalEnquiryType.BRAND, saved.getType());
        assertEquals(FestivalEnquiryStatus.NEW, saved.getStatus());
        assertEquals("Rangoli Threads", saved.getCompany());
        assertEquals(FestivalTier.FEATURED, saved.getTier());
        // The creator half must stay null, not ""/0 — admin distinguishes "not asked" from
        // "answered with nothing", and the DTO renders null as an em dash.
        assertNull(saved.getInstagramHandle());
        assertNull(saved.getFollowers());
        assertNull(saved.getCity());
    }

    @Test
    @DisplayName("creator submission normalizes the handle and leaves the brand half null")
    void creatorSubmissionNormalizesHandle() {
        service.submit(creator().instagramHandle("@Priya.Styles").build(), httpRequest);

        FestivalEnquiry saved = captureSaved();
        assertEquals(FestivalEnquiryType.CREATOR, saved.getType());
        assertEquals("priya.styles", saved.getInstagramHandle());
        assertNull(saved.getCompany());
        assertNull(saved.getTier());
    }

    @Test
    @DisplayName("email is lower-cased so the per-email throttle cannot be evaded by casing")
    void emailIsLowerCased() {
        service.submit(brand().email("Ops@Rangoli.IN").build(), httpRequest);
        assertEquals("ops@rangoli.in", captureSaved().getEmail());
    }

    @Test
    @DisplayName("a brand that skips the tier picker is recorded UNDECIDED, not rejected")
    void missingTierBecomesUndecided() {
        service.submit(brand().tier(null).build(), httpRequest);
        assertEquals(FestivalTier.UNDECIDED, captureSaved().getTier());
    }

    // ------------------------------------------------------------------------------------------
    // Honeypot
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a filled honeypot returns the same success a human gets, and stores nothing")
    void honeypotStoresNothingButLooksSuccessful() {
        SubmitEnquiryResponse response =
                service.submit(brand().honeypot("http://spam.example").build(), httpRequest);

        // Indistinguishable from the real thing — a bot that could tell would just retry.
        assertTrue(response.received());
        assertNotNull(response.message());
        verify(repository, never()).save(any());
        // And it short-circuits BEFORE the throttle touches anything, so honeypot traffic cannot be
        // used to probe how many enquiries an address has already sent, and does not consume budget.
        verifyNoInteractions(abuseThrottleService);
    }

    @Test
    @DisplayName("an invalid payload is refused identically whether or not the honeypot is filled")
    void honeypotIsNotADetectableOracle() {
        // [SEC: Kabir F-2] This test previously asserted the OPPOSITE — that a filled honeypot won
        // over validation, so an invalid payload returned 200 with the trap field and 400 without
        // it. That difference WAS the vulnerability: two requests, no rows written and no throttle
        // consumed, and a crawler could identify the trap field by name and then omit it forever.
        //
        // Both requests below must now fail the SAME way. If someone moves the honeypot check back
        // above validation, the first assertion starts returning 200 and this test fails.
        SubmitEnquiryRequest withTrap = brand().honeypot("x").type("NONSENSE").build();
        SubmitEnquiryRequest withoutTrap = brand().type("NONSENSE").build();

        ApiException a = assertThrows(ApiException.class, () -> service.submit(withTrap, httpRequest));
        ApiException b = assertThrows(ApiException.class, () -> service.submit(withoutTrap, httpRequest));

        assertEquals(b.getCode(), a.getCode());
        assertEquals(b.getStatus(), a.getStatus());
        assertEquals("INVALID_TYPE", a.getCode());
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("a filled honeypot on an OTHERWISE VALID payload still looks like success")
    void honeypotOnValidPayloadStillLooksSuccessful() {
        // The trap still works for its actual job: a bot that fills every field of a well-formed
        // submission gets the same 200 a human does, and nothing is stored.
        assertTrue(service.submit(brand().honeypot("x").build(), httpRequest).received());
        verify(repository, never()).save(any());
    }

    // ------------------------------------------------------------------------------------------
    // Throttle
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("the per-IP cap refuses a submission once AbuseThrottleService reports it over budget")
    void perIpCapRejects() {
        when(abuseThrottleService.tryConsume(startsWith("festival-ip:"), any(Duration.class), anyLong()))
                .thenReturn(false);

        SubmitEnquiryRequest req = brand().build();
        ApiException e = assertThrows(ApiException.class, () -> service.submit(req, httpRequest));

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, e.getStatus());
        assertEquals("TOO_MANY_ENQUIRIES", e.getCode());
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("the per-email cap refuses a submission once AbuseThrottleService reports it over budget")
    void perEmailCapRejects() {
        // IP check passes (default stub), only the email-keyed call is over budget.
        when(abuseThrottleService.tryConsume(startsWith("festival-email:"), any(Duration.class), anyLong()))
                .thenReturn(false);

        SubmitEnquiryRequest req = brand().build();
        ApiException e = assertThrows(ApiException.class, () -> service.submit(req, httpRequest));

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, e.getStatus());
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("the IP throttle key is a salted hash, never the address itself")
    void ipIsHashedNotStored() {
        service.submit(brand().build(), httpRequest);

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        // Two calls happen per submit (IP check, then email check) — capture both and pick the
        // IP-prefixed one; this is not asserting call COUNT, only inspecting what key was used.
        verify(abuseThrottleService, atLeastOnce())
                .tryConsume(key.capture(), any(Duration.class), anyLong());
        String ipKey =
                key.getAllValues().stream()
                        .filter(k -> k.startsWith("festival-ip:"))
                        .findFirst()
                        .orElseThrow();

        // "festival-ip:" + SHA-256 hex, and emphatically not the address.
        assertTrue(ipKey.matches("^festival-ip:[0-9a-f]{64}$"));
        assertFalse(ipKey.contains("203.0.113"));
    }

    @Test
    @DisplayName("a missing client address degrades to email-only throttling, never a failed submit")
    void missingRemoteAddrStillSubmits() {
        httpRequest.setRemoteAddr(null);

        assertTrue(service.submit(brand().build(), httpRequest).received());
        verify(abuseThrottleService, never())
                .tryConsume(startsWith("festival-ip:"), any(Duration.class), anyLong());
        verify(abuseThrottleService)
                .tryConsume(startsWith("festival-email:"), any(Duration.class), anyLong());
        verify(repository).save(any());
    }

    @Test
    @DisplayName(
            "F-1a: plus-addressed variants of the same mailbox share ONE email-throttle key, but the"
                    + " STORED email is exactly what the submitter typed")
    void plusAddressingSharesOneThrottleKeyButStoresTypedAddress() {
        service.submit(brand().email("attacker+1@x.com").build(), httpRequest);
        FestivalEnquiry first = captureSaved();

        ArgumentCaptor<String> firstKeys = ArgumentCaptor.forClass(String.class);
        verify(abuseThrottleService, atLeastOnce())
                .tryConsume(firstKeys.capture(), any(Duration.class), anyLong());
        String firstEmailKey =
                firstKeys.getAllValues().stream()
                        .filter(k -> k.startsWith("festival-email:"))
                        .findFirst()
                        .orElseThrow();

        // Stubbing from setUp() (return true for any key) still applies — only invocation history
        // is cleared, so the next submit()'s captured keys/saved entity aren't mixed with this one's.
        clearInvocations(abuseThrottleService, repository);
        service.submit(brand().email("attacker+2@x.com").build(), httpRequest);
        FestivalEnquiry second = captureSaved();

        ArgumentCaptor<String> secondKeys = ArgumentCaptor.forClass(String.class);
        verify(abuseThrottleService, atLeastOnce())
                .tryConsume(secondKeys.capture(), any(Duration.class), anyLong());
        String secondEmailKey =
                secondKeys.getAllValues().stream()
                        .filter(k -> k.startsWith("festival-email:"))
                        .findFirst()
                        .orElseThrow();

        assertEquals(
                firstEmailKey,
                secondEmailKey,
                "attacker+1@x.com and attacker+2@x.com must count against the SAME budget");
        assertEquals("festival-email:attacker@x.com", firstEmailKey);

        // The stored address is untouched — a brand writing ops+festival@acme.in must be reachable
        // at that exact address, so canonicalisation is for the throttle key only.
        assertEquals("attacker+1@x.com", first.getEmail());
        assertEquals("attacker+2@x.com", second.getEmail());
    }

    @Test
    @DisplayName(
            "F-1b: two IPv6 clients in the same /64 share ONE IP-throttle key; a different /64 does"
                    + " not")
    void ipv6Slash64Grouping() {
        httpRequest.setRemoteAddr("2001:db8:0:0:1111:2222:3333:4444");
        service.submit(brand().build(), httpRequest);
        String keyA = capturedIpKey();

        // Stubbing from setUp() (return true for any key) still applies — only invocation history
        // is cleared, so the next submit()'s captured keys aren't mixed with this one's.
        clearInvocations(abuseThrottleService);
        // Same /64 network prefix (first 4 hextets), totally different interface identifier.
        httpRequest.setRemoteAddr("2001:db8:0:0:aaaa:bbbb:cccc:dddd");
        service.submit(brand().build(), httpRequest);
        String keyB = capturedIpKey();

        assertEquals(keyA, keyB, "two addresses inside the same /64 must share one throttle budget");

        // Stubbing from setUp() (return true for any key) still applies — only invocation history
        // is cleared, so the next submit()'s captured keys aren't mixed with this one's.
        clearInvocations(abuseThrottleService);
        // Different /64 (4th hextet differs: 0 -> 1).
        httpRequest.setRemoteAddr("2001:db8:0:1:1111:2222:3333:4444");
        service.submit(brand().build(), httpRequest);
        String keyC = capturedIpKey();

        assertFalse(
                keyA.equals(keyC), "two addresses in DIFFERENT /64s must not share a throttle budget");
    }

    private String capturedIpKey() {
        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(abuseThrottleService, atLeastOnce())
                .tryConsume(keys.capture(), any(Duration.class), anyLong());
        return keys.getAllValues().stream()
                .filter(k -> k.startsWith("festival-ip:"))
                .findFirst()
                .orElseThrow();
    }

    // ------------------------------------------------------------------------------------------
    // Field validation
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a javascript: website is refused, so admin can never render it as a link")
    void javascriptWebsiteRejected() {
        SubmitEnquiryRequest req = brand().website("javascript:alert(1)").build();
        ApiException e = assertThrows(ApiException.class, () -> service.submit(req, httpRequest));

        assertEquals("INVALID_WEBSITE", e.getCode());
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("a scheme-less website is stored as https, not guessed at or dropped")
    void schemelessWebsiteGetsHttps() {
        service.submit(brand().website("rangolithreads.in").build(), httpRequest);
        assertEquals("https://rangolithreads.in", captureSaved().getWebsite());
    }

    @Test
    @DisplayName("a brand with no company is refused (a cross-field rule bean validation cannot express)")
    void brandRequiresCompany() {
        SubmitEnquiryRequest req = brand().company("  ").build();
        ApiException e = assertThrows(ApiException.class, () -> service.submit(req, httpRequest));
        assertEquals("INVALID_COMPANY", e.getCode());
    }

    @Test
    @DisplayName("a creator with no handle is refused")
    void creatorRequiresHandle() {
        SubmitEnquiryRequest req = creator().instagramHandle(null).build();
        ApiException e = assertThrows(ApiException.class, () -> service.submit(req, httpRequest));
        assertEquals("INVALID_HANDLE", e.getCode());
    }

    @Test
    @DisplayName("a handle with illegal characters is refused rather than silently stripped")
    void malformedHandleRejected() {
        SubmitEnquiryRequest req = creator().instagramHandle("priya styles!").build();
        ApiException e = assertThrows(ApiException.class, () -> service.submit(req, httpRequest));
        assertEquals("INVALID_HANDLE", e.getCode());
    }

    @Test
    @DisplayName("an unknown type is refused rather than defaulting to BRAND")
    void unknownTypeRejected() {
        SubmitEnquiryRequest req = brand().type("PARTNER").build();
        ApiException e = assertThrows(ApiException.class, () -> service.submit(req, httpRequest));
        assertEquals("INVALID_TYPE", e.getCode());
    }

    @Test
    @DisplayName("an implausible follower count is refused")
    void implausibleFollowersRejected() {
        SubmitEnquiryRequest req = creator().followers(9_000_000_000L).build();
        ApiException e = assertThrows(ApiException.class, () -> service.submit(req, httpRequest));
        assertEquals("INVALID_FOLLOWERS", e.getCode());
    }

    @Test
    @DisplayName("a blank edition falls back to the current edition rather than being stored empty")
    void blankEditionFallsBack() {
        service.submit(brand().edition("   ").build(), httpRequest);
        assertEquals("MUMBAI_FESTIVE_2026", captureSaved().getEdition());
    }

    @Test
    @DisplayName("[H-1] an UNKNOWN edition still saves the lead, filed under the current edition")
    void unknownEditionStillSavesTheLead() {
        // Deliberately the OPPOSITE of FestivalCouponCopyService, which drops an unknown edition.
        // A dropped copy-tap costs one counter increment; a dropped enquiry costs a named human
        // with an email address who tried to buy sponsorship. A stale link or a hand-edited URL
        // must never lose that, so the edition is corrected rather than the row discarded.
        service.submit(brand().edition("DELHI_FESTIVE_2027").build(), httpRequest);
        assertEquals("MUMBAI_FESTIVE_2026", captureSaved().getEdition());
    }

    @Test
    @DisplayName("[H-1] a known edition in odd casing is stored canonically")
    void knownEditionIsNormalized() {
        service.submit(brand().edition(" mumbai_festive_2026 ").build(), httpRequest);
        assertEquals("MUMBAI_FESTIVE_2026", captureSaved().getEdition());
    }

    @Test
    @DisplayName("utm parameters are stored so marketing can attribute the enquiry")
    void utmIsStored() {
        service.submit(brand().build(), httpRequest);

        FestivalEnquiry saved = captureSaved();
        assertEquals("instagram", saved.getUtmSource());
        assertEquals("bio-link", saved.getUtmMedium());
        assertEquals("festive-01", saved.getUtmCampaign());
    }

    // ------------------------------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------------------------------

    private FestivalEnquiry captureSaved() {
        ArgumentCaptor<FestivalEnquiry> captor = ArgumentCaptor.forClass(FestivalEnquiry.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    private static Req brand() {
        return new Req()
                .type("BRAND")
                .edition("MUMBAI_FESTIVE_2026")
                .name("Anita Desai")
                .email("ops@rangoli.in")
                .phone("+91 98200 11223")
                .company("Rangoli Threads")
                .website("https://rangolithreads.in")
                .tier("FEATURED")
                .productCategory("Apparel")
                .message("Interested in the festive edition.")
                .utm("instagram", "bio-link", "festive-01");
    }

    private static Req creator() {
        return new Req()
                .type("CREATOR")
                .edition("MUMBAI_FESTIVE_2026")
                .name("Priya S")
                .email("priya@example.com")
                .instagramHandle("priya.styles")
                .followers(42_000L)
                .city("Mumbai")
                .message("Would love to be in the room.");
    }

    /**
     * Mutable builder for the immutable request record. Exists because {@link SubmitEnquiryRequest}
     * is a 17-component record: without it every test that varies ONE field would restate the other
     * sixteen positionally, which is exactly how a fixture ends up asserting something other than
     * what its name claims.
     */
    private static final class Req {
        private String type;
        private String edition;
        private String name;
        private String email;
        private String phone;
        private String company;
        private String website;
        private String tier;
        private String productCategory;
        private String instagramHandle;
        private Long followers;
        private String city;
        private String message;
        private String utmSource;
        private String utmMedium;
        private String utmCampaign;
        private String honeypot;

        Req type(String v) {
            this.type = v;
            return this;
        }

        Req edition(String v) {
            this.edition = v;
            return this;
        }

        Req name(String v) {
            this.name = v;
            return this;
        }

        Req email(String v) {
            this.email = v;
            return this;
        }

        Req phone(String v) {
            this.phone = v;
            return this;
        }

        Req company(String v) {
            this.company = v;
            return this;
        }

        Req website(String v) {
            this.website = v;
            return this;
        }

        Req tier(String v) {
            this.tier = v;
            return this;
        }

        Req productCategory(String v) {
            this.productCategory = v;
            return this;
        }

        Req instagramHandle(String v) {
            this.instagramHandle = v;
            return this;
        }

        Req followers(Long v) {
            this.followers = v;
            return this;
        }

        Req city(String v) {
            this.city = v;
            return this;
        }

        Req message(String v) {
            this.message = v;
            return this;
        }

        Req utm(String source, String medium, String campaign) {
            this.utmSource = source;
            this.utmMedium = medium;
            this.utmCampaign = campaign;
            return this;
        }

        Req honeypot(String v) {
            this.honeypot = v;
            return this;
        }

        SubmitEnquiryRequest build() {
            return new SubmitEnquiryRequest(
                    type,
                    edition,
                    name,
                    email,
                    phone,
                    company,
                    website,
                    tier,
                    productCategory,
                    instagramHandle,
                    followers,
                    city,
                    message,
                    utmSource,
                    utmMedium,
                    utmCampaign,
                    honeypot);
        }
    }
}
