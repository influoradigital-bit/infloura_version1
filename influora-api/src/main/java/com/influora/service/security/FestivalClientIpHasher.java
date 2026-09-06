package com.influora.service.security;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Turns a client address into a stable, salted, non-reversible throttle identity for the public
 * Festival Box endpoints.
 *
 * <h2>Why this is a shared component and not a private method</h2>
 *
 * This logic was private to {@code FestivalEnquiryService}. [Kabir M-1/M-2] gave {@code
 * FestivalCouponCopyService} the same need, and the only two options were to copy it or to extract
 * it. Copying a security primitive is how two copies of it end up disagreeing: one gets the IPv6
 * prefix fix and the other does not, one gets a salt rotation and the other keeps the old one, and
 * the weaker copy becomes the way in. Extracted, both endpoints are provably throttling on the same
 * identity, and a change to how an origin is identified happens once.
 *
 * <h2>What is stored, and what is not</h2>
 *
 * Never the address. The output is {@code SHA-256(salt | normalized-address)} as hex, which goes
 * into {@code abuse_throttle_counters} and nowhere else — in particular NOT into {@code
 * festival_enquiries} or {@code festival_coupon_copies}, whose own migration headers commit to
 * storing nothing that identifies a visitor.
 *
 * <p>The salt is what makes this non-reversible in practice. Without it, the IPv4 space is 2^32 —
 * small enough to rainbow-table completely in minutes, which would make a stored "hash" simply a
 * reversible encoding of the visitor's address. {@code SecretsStartupValidator} refuses to boot on
 * the committed dev default outside the dev profile for exactly that reason.
 */
@Component
public class FestivalClientIpHasher {

    private static final Logger log = LoggerFactory.getLogger(FestivalClientIpHasher.class);

    private final String ipHashSalt;

    public FestivalClientIpHasher(@Value("${influora.festival.ip-hash-salt}") String ipHashSalt) {
        this.ipHashSalt = ipHashSalt;
    }

    /**
     * Hashes {@code remoteAddr} into a throttle identity, or returns {@code null} when there is no
     * usable address.
     *
     * <p><b>{@code null} means "no per-IP throttle for this request", never "deny".</b> Callers must
     * degrade to whatever other bound they have rather than failing the request — a visitor whose
     * address could not be read has done nothing wrong, and refusing them would turn a missing
     * header into an outage.
     *
     * <p>Must be called with {@code HttpServletRequest#getRemoteAddr()}, never with a raw {@code
     * X-Forwarded-For} value. A client can put anything in that header, so trusting it directly
     * would let an attacker mint a fresh throttle bucket per request and make every cap here a
     * no-op. Forwarded headers are resolved upstream by Tomcat's {@code RemoteIpValve} ({@code
     * forward-headers-strategy: native}), which validates the peer before rewriting {@code
     * getRemoteAddr()}.
     */
    public String hash(String remoteAddr) {
        if (remoteAddr == null || remoteAddr.isBlank()) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed =
                    digest.digest(
                            (ipHashSalt + "|" + normalizeForThrottle(remoteAddr))
                                    .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JDK spec, so this is unreachable — but swallowing it into
            // a null keeps a hypothetical JVM without it from making the page unusable.
            log.error("SHA-256 unavailable; per-IP throttle disabled for this request", e);
            return null;
        }
    }

    /**
     * [Kabir L-2] The IPv6 prefix widths this hasher issues identities for, coarsest last.
     *
     * <p>WHY {@code /64} ALONE WAS NOT ENOUGH. A {@code /64} is one LAN, and hashing at that width
     * correctly stops a host from rotating through the addresses of its own subnet. But no ISP
     * hands a customer a single {@code /64} — RIPE-690 and the equivalent guidance elsewhere have
     * residential allocations at {@code /56} (256 subnets) or {@code /48} (65,536). So a
     * {@code /64}-only cap of 6 per hour was, for anyone on a routine home connection with a
     * {@code /48}, a cap of roughly 393,000 per hour, reachable with nothing more exotic than
     * picking a different source address each request. The mitigation existed and the number it
     * enforced was off by four to five orders of magnitude.
     *
     * <p>WHY THE COARSER TIERS NEED THEIR OWN, LOOSER CAPS rather than the same one. A {@code /48}
     * is not one household — it can be an entire campus, office, or carrier NAT pool. Applying the
     * per-{@code /64} cap at {@code /48} would let one abusive host lock out everyone who shares
     * their organisation's allocation. Each tier therefore carries a multiplier chosen so that a
     * plausible number of independent real users behind one prefix stay comfortably under it while
     * the rotation amplification is cut from five orders of magnitude to one. Callers own those
     * numbers; this class only decides the widths.
     */
    private static final int[] IPV6_PREFIX_BITS = {64, 56, 48};

    /**
     * One throttle identity per prefix width, coarsest last, for an address that may be rotating.
     *
     * <p>IPv4 yields exactly one entry — 32 bits is small enough that a caller genuinely owns the
     * whole address it presents, so there is no coarser tier to add and adding one would group
     * unrelated customers behind a shared upstream.
     *
     * <p>Returns an EMPTY list (never null, never a list containing null) when there is no usable
     * address, so a caller loops over nothing and applies no per-origin cap — the same fail-open
     * contract as {@link #hash}. See that method for why fail-open is the right direction.
     */
    public List<String> hashTiers(String remoteAddr) {
        if (remoteAddr == null || remoteAddr.isBlank()) {
            return List.of();
        }

        InetAddress addr;
        try {
            addr = InetAddress.getByName(remoteAddr);
        } catch (UnknownHostException e) {
            // Unparseable literal: one best-effort bucket on the raw text, matching normalizeForThrottle.
            String single = hashPreImage("raw:" + remoteAddr);
            return single == null ? List.of() : List.of(single);
        }

        if (!(addr instanceof Inet6Address)) {
            String single = hashPreImage("v4:" + addr.getHostAddress());
            return single == null ? List.of() : List.of(single);
        }

        byte[] full = addr.getAddress(); // 16 bytes, network byte order
        List<String> tiers = new ArrayList<>(IPV6_PREFIX_BITS.length);
        for (int bits : IPV6_PREFIX_BITS) {
            byte[] prefix = Arrays.copyOf(full, bits / 8);
            String hashed = hashPreImage("v6/" + bits + ":" + HexFormat.of().formatHex(prefix));
            if (hashed != null) {
                tiers.add(hashed);
            }
        }
        return List.copyOf(tiers);
    }

    /** Salted SHA-256 over an already-namespaced pre-image. {@code null} only if SHA-256 is absent. */
    private String hashPreImage(String preImage) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of()
                    .formatHex(digest.digest((ipHashSalt + "|" + preImage).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            log.error("SHA-256 unavailable; per-IP throttle disabled for this request", e);
            return null;
        }
    }

    /**
     * Reduces a client address down to the text that should identify ONE throttle origin: the full
     * address for IPv4 (32 bits is small enough that a caller genuinely owns the whole thing it
     * presents), but only the {@code /64} network prefix for IPv6. The {@code v4:}/{@code
     * v6/64:}/{@code raw:} tags exist only to namespace the three shapes apart in the hash
     * pre-image; they carry no other meaning.
     *
     * <p>[Kabir L-2] {@link #hashTiers} is the rotation-resistant version and is what a per-origin
     * cap should use. This single-identity method remains for callers that need one stable
     * identity rather than a set, and its {@code /64} output is bit-identical to the first tier.
     *
     * <p>{@code InetAddress.getByName} performs a DNS lookup only when given a hostname — this
     * method is only ever called with {@code HttpServletRequest#getRemoteAddr()}'s output, which is
     * always a resolved literal address, never a hostname, so no network call happens here.
     *
     * <p>Falls back to the raw, unparsed string on any failure (a malformed literal, an unexpected
     * format) rather than throwing — this is a throttle-key input, not a validated field, and an
     * address that fails to parse should still get its own best-effort bucket rather than take down
     * the request.
     */
    static String normalizeForThrottle(String remoteAddr) {
        try {
            InetAddress addr = InetAddress.getByName(remoteAddr);
            if (addr instanceof Inet6Address) {
                byte[] full = addr.getAddress(); // 16 bytes, network byte order
                byte[] prefix = Arrays.copyOf(full, 8); // top 64 bits (the network prefix) only
                return "v6/64:" + HexFormat.of().formatHex(prefix);
            }
            return "v4:" + addr.getHostAddress();
        } catch (UnknownHostException e) {
            return "raw:" + remoteAddr;
        }
    }
}
