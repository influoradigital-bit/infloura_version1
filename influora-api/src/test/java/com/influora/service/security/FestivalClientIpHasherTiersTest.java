package com.influora.service.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * [Kabir L-2] The IPv6 prefix tiers.
 *
 * <p>THE BUG: hashing at {@code /64} correctly stops a host rotating within its own LAN, and stops
 * nothing else. ISPs allocate residential customers a {@code /56} (256 subnets) or a {@code /48}
 * (65,536), so the enquiry form's "6 submissions per hour per origin" was, for an ordinary home
 * connection with a {@code /48}, about 393,000 per hour — reachable by doing nothing cleverer than
 * choosing a different source address for each request. The control existed; the number it actually
 * enforced was four to five orders of magnitude off the number it claimed.
 *
 * <p>These tests pin the identity function, not the caps. Callers own their own caps (see {@code
 * FestivalEnquiryService#enforceThrottle} and {@code FestivalCouponCopyService#recordCopy}), which
 * must be LOOSER at coarser tiers — a {@code /48} can be an entire campus, and applying the
 * per-{@code /64} cap there would let one abusive host lock out everyone sharing the allocation.
 */
class FestivalClientIpHasherTiersTest {

    private final FestivalClientIpHasher hasher =
            new FestivalClientIpHasher("test-salt-at-least-32-characters-long!!");

    @Test
    @DisplayName("[L-2] IPv6 yields one identity per prefix width")
    void ipv6YieldsThreeTiers() {
        List<String> tiers = hasher.hashTiers("2001:db8:1234:5678::1");

        assertEquals(3, tiers.size(), "expected /64, /56 and /48 identities");
        assertEquals(3, tiers.stream().distinct().count(), "tiers must not collide with each other");
    }

    @Test
    @DisplayName("[L-2] THE ATTACK: rotating within a /64 already failed, and still does")
    void differentAddressesInSameSubnetShareEveryTier() {
        List<String> a = hasher.hashTiers("2001:db8:1234:5678::1");
        List<String> b = hasher.hashTiers("2001:db8:1234:5678::dead:beef");

        assertEquals(a, b, "two addresses in one /64 must be one origin at every tier");
    }

    @Test
    @DisplayName("[L-2] THE FIX: rotating to a different /64 inside the same /56 no longer escapes")
    void differentSubnetsInSameSlash56ShareTheCoarserTiers() {
        // Byte 7 differs => a different /64, the same /56. Pre-fix this was a brand-new origin with
        // a brand-new budget; a customer with a /56 got 256 of them.
        List<String> a = hasher.hashTiers("2001:db8:1234:5600::1");
        List<String> b = hasher.hashTiers("2001:db8:1234:56ff::1");

        assertNotEquals(a.get(0), b.get(0), "/64 tier should differ — that is the whole premise");
        assertEquals(a.get(1), b.get(1), "/56 tier must be shared, which is what now bounds them");
        assertEquals(a.get(2), b.get(2), "/48 tier must be shared too");
    }

    @Test
    @DisplayName("[L-2] rotating across /56s inside one /48 is still bounded by the /48 tier")
    void differentSlash56sInSameSlash48ShareTheCoarsestTier() {
        // Byte 6 differs => different /64 AND different /56, same /48. This is the 65,536x case.
        List<String> a = hasher.hashTiers("2001:db8:1234:0001::1");
        List<String> b = hasher.hashTiers("2001:db8:1234:ff01::1");

        assertNotEquals(a.get(0), b.get(0));
        assertNotEquals(a.get(1), b.get(1));
        assertEquals(a.get(2), b.get(2), "/48 is the last line and must hold");
    }

    @Test
    @DisplayName("[L-2] a genuinely different /48 is a genuinely different origin at every tier")
    void differentSlash48sShareNothing() {
        List<String> a = hasher.hashTiers("2001:db8:1111::1");
        List<String> b = hasher.hashTiers("2001:db8:2222::1");

        // The coarse tiers must not become a global bucket — that would make one abuser able to
        // throttle unrelated networks, which is a self-inflicted outage rather than a control.
        assertTrue(a.stream().noneMatch(b::contains), "unrelated /48s must not share any tier");
    }

    @Test
    @DisplayName("[L-2] IPv4 yields exactly one tier — there is no coarser prefix to add")
    void ipv4YieldsOneTier() {
        // Deliberate: a /24 on IPv4 groups unrelated customers behind a shared upstream, and 32
        // bits is small enough that a caller genuinely owns the address it presents. Adding tiers
        // here would create collateral throttling with no rotation benefit.
        assertEquals(1, hasher.hashTiers("203.0.113.7").size());
        assertNotEquals(
                hasher.hashTiers("203.0.113.7"),
                hasher.hashTiers("203.0.113.8"),
                "distinct IPv4 addresses are distinct origins");
    }

    @Test
    @DisplayName("[L-2] the /64 tier is bit-identical to the single-identity hash()")
    void firstTierMatchesLegacyHash() {
        // hash() still backs the enquiry row's provenance column. If the two ever diverged, stored
        // provenance would stop matching the throttle identity and an admin grouping rows by it
        // would be grouping by something that no longer means the same thing.
        assertEquals(hasher.hash("2001:db8:1234:5678::1"), hasher.hashTiers("2001:db8:1234:5678::1").get(0));
        assertEquals(hasher.hash("203.0.113.7"), hasher.hashTiers("203.0.113.7").get(0));
    }

    @Test
    @DisplayName("[L-2] no address yields an EMPTY list — fail open, never a null element")
    void noAddressYieldsEmptyList() {
        // Callers loop over this. A null element would NPE inside a throttle check and turn a
        // missing header into a 500 on a public form; an empty list simply applies no per-origin
        // cap, which is the documented fail-open contract.
        assertTrue(hasher.hashTiers(null).isEmpty());
        assertTrue(hasher.hashTiers("  ").isEmpty());
        // stream().noneMatch, not contains(null): the returned list is immutable, and
        // List.of(...).contains(null) throws NPE by contract rather than returning false. The first
        // version of this line did exactly that and failed against perfectly correct code.
        assertTrue(hasher.hashTiers("203.0.113.7").stream().noneMatch(java.util.Objects::isNull));
    }

    @Test
    @DisplayName("[L-2] an unparseable address still gets its own single best-effort bucket")
    void unparseableAddressGetsOneBucket() {
        List<String> tiers = hasher.hashTiers("not-an-address");

        assertEquals(1, tiers.size());
        assertNotEquals(hasher.hashTiers("also-not-an-address"), tiers);
    }

    @Test
    @DisplayName("[L-2] the salt is applied — two hashers never agree")
    void saltIsApplied() {
        FestivalClientIpHasher other =
                new FestivalClientIpHasher("a-completely-different-salt-32-chars!!!");

        // Without the salt, the identity is a plain hash of an address: the IPv4 space is 2^32 and
        // rainbow-tables in minutes, making a stored "hash" a reversible encoding of the visitor.
        assertNotEquals(other.hashTiers("203.0.113.7"), hasher.hashTiers("203.0.113.7"));
    }
}
