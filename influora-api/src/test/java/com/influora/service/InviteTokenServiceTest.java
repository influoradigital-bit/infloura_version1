package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * Q5.5 (T-CREATORCONNECT-0902, Medium) — pins the issue/verify round trip at the precision the
 * {@code external_creators.invited_at} column can actually store (MySQL {@code TIMESTAMP}, fsp
 * 0 — whole seconds). Before this fix, {@link InviteTokenService#issue} embedded {@code
 * Instant#toEpochMilli()} while the DB round-trips only whole seconds, so {@code
 * RegistrationService#consumeInviteToken}'s {@code .equals(...)} comparison rejected an
 * invite-token as "superseded by a re-invite" for every {@code invitedAt} that did not happen to
 * land on an exact second boundary — i.e. essentially always. No test previously exercised this
 * round trip at all (Priya's Q5.5 QA re-review).
 *
 * <p>Also pins the CTO-ordered security guard: {@link InviteTokenService#verify} must fail closed
 * (reject every token) whenever the configured secret equals the committed dev default or is
 * shorter than 32 chars, UNLESS the active Spring profile is dev/test — see {@code
 * InviteTokenService}'s {@code failClosed} field javadoc.
 */
class InviteTokenServiceTest {

    private final InviteTokenService service =
            new InviteTokenService("test-secret-at-least-32-characters-long!!", environment(false));

    /** A mocked {@link Environment} that reports (or doesn't) the dev/test profile bypass — the
     * only surface {@link InviteTokenService}'s constructor reads from it. */
    private static Environment environment(boolean acceptsDevOrTest) {
        Environment env = mock(Environment.class);
        when(env.acceptsProfiles(any(Profiles.class))).thenReturn(acceptsDevOrTest);
        return env;
    }

    @Test
    @DisplayName(
            "Q5.5: an invitedAt with a non-zero millisecond component round-trips through issue/verify"
                    + " equal to its whole-second truncation — the DB-storable precision")
    void issueThenVerify_millisecondInvitedAt_roundTripsAtSecondPrecision() {
        // A realistic Instant.now() almost never lands on an exact second — pin one with a
        // non-zero millisecond component explicitly so this test cannot pass by accident.
        Instant invitedAt = Instant.parse("2026-09-03T10:15:30.427Z");
        String token = service.issue("01HEXTCREATOR000000001", invitedAt);

        var parsed = service.verify(token);

        assertTrue(parsed.isPresent(), "a freshly issued token must verify");
        assertEquals("01HEXTCREATOR000000001", parsed.get().externalCreatorId());
        // This is the exact comparison RegistrationService#consumeInviteToken performs (mirrored
        // there with its own truncation as defense-in-depth) — it must hold for the token to ever
        // be usable, not just for the raw millis to differ.
        assertEquals(invitedAt.truncatedTo(ChronoUnit.SECONDS), parsed.get().invitedAt());
    }

    @Test
    @DisplayName(
            "Q5.5: two invitedAt instants that differ only in their millisecond component (as MySQL"
                    + " TIMESTAMP fsp-0 would store them identically) issue tokens whose parsed"
                    + " invitedAt is identical — proving the comparison is now DB-precision-safe")
    void issueThenVerify_differentMillisSameSecond_produceEqualParsedInvitedAt() {
        Instant a = Instant.parse("2026-09-03T10:15:30.001Z");
        Instant b = Instant.parse("2026-09-03T10:15:30.999Z");

        var parsedA = service.verify(service.issue("01HEXTCREATOR000000001", a));
        var parsedB = service.verify(service.issue("01HEXTCREATOR000000001", b));

        assertTrue(parsedA.isPresent() && parsedB.isPresent());
        assertEquals(parsedA.get().invitedAt(), parsedB.get().invitedAt());
    }

    // ── Q5.5 security guard: fail closed on an insecure secret outside dev/test ────────────

    @Test
    @DisplayName(
            "Q5.5 SECURITY: the committed dev-default secret outside the dev/test profile rejects"
                    + " every token — even one it just issued itself with a correct signature")
    void verify_devDefaultSecret_outsideDevOrTestProfile_rejectsEveryToken() {
        InviteTokenService insecure =
                new InviteTokenService(InviteTokenService.DEV_DEFAULT_SECRET, environment(false));

        String token = insecure.issue("01HEXTCREATOR000000001", Instant.now());

        assertFalse(
                insecure.verify(token).isPresent(),
                "a token signed with the public committed default must never verify outside dev/test");
    }

    @Test
    @DisplayName(
            "Q5.5 SECURITY: a secret shorter than 32 chars outside the dev/test profile also rejects"
                    + " every token")
    void verify_shortSecret_outsideDevOrTestProfile_rejectsEveryToken() {
        InviteTokenService insecure = new InviteTokenService("too-short-secret", environment(false));

        String token = insecure.issue("01HEXTCREATOR000000001", Instant.now());

        assertFalse(insecure.verify(token).isPresent());
    }

    @Test
    @DisplayName(
            "Q5.5 SECURITY: the same dev-default secret INSIDE the dev/test profile is allowed"
                    + " (local/dev boot must not be broken by this guard)")
    void verify_devDefaultSecret_insideDevOrTestProfile_isAccepted() {
        InviteTokenService devInstance =
                new InviteTokenService(InviteTokenService.DEV_DEFAULT_SECRET, environment(true));

        String token = devInstance.issue("01HEXTCREATOR000000001", Instant.now());

        assertTrue(
                devInstance.verify(token).isPresent(),
                "the dev-default secret must keep working inside the dev/test profile");
    }

    @Test
    @DisplayName(
            "Q5.5 SECURITY: a real, sufficiently long secret is accepted regardless of profile — the"
                    + " guard only fires for the insecure default/short-secret case")
    void verify_realSecret_outsideDevOrTestProfile_isAccepted() {
        InviteTokenService real =
                new InviteTokenService("a-real-random-secret-at-least-32-characters-long", environment(false));

        String token = real.issue("01HEXTCREATOR000000001", Instant.now());

        assertTrue(real.verify(token).isPresent());
    }
}
