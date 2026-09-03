package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.domain.entity.ExternalCreator;
import com.influora.domain.enums.ExternalCreatorSource;
import com.influora.repository.ExternalCreatorRepository;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * Q5.5 (T-CREATORCONNECT-0902, Medium) — the issue -> verify -> consume round trip that Priya's
 * QA re-review found had zero test coverage, using a REAL {@link InviteTokenService} (not
 * mocked) so the millisecond/second precision bug her review found (fixed alongside this test —
 * see {@link InviteTokenServiceTest}) is actually exercised end to end, not just at the unit
 * boundary of either class alone.
 *
 * <p>{@code AuthService#creatorRegister} does not call {@link RegistrationService#consumeInviteToken}
 * yet — wiring that (and reading the token in {@code creator-register.tsx}) needs {@code
 * AuthService.java} and the frontend page, both outside this work package's file list (see the
 * class javadoc on {@link RegistrationService}). This test proves the piece that IS in scope is
 * actually correct and ready to be called once that wiring lands.
 */
@ExtendWith(MockitoExtension.class)
class RegistrationServiceTest {

    private static final String EXTERNAL_CREATOR_ID = "01HEXTCREATOR000000001";
    private static final String CREATOR_PROFILE_ID = "01HCREATORPROFILE00001";

    @Mock private ExternalCreatorRepository externalCreatorRepository;
    @Mock private ExternalCreatorLinkService externalCreatorLinkService;

    private InviteTokenService inviteTokenService;
    private RegistrationService service;

    @BeforeEach
    void setUp() {
        Environment environment = mock(Environment.class);
        when(environment.acceptsProfiles(any(Profiles.class))).thenReturn(false);
        inviteTokenService = new InviteTokenService("test-secret-at-least-32-characters-long!!", environment);
        service = new RegistrationService(inviteTokenService, externalCreatorRepository, externalCreatorLinkService);
    }

    /** Builds an INVITED row with a realistic (non-exact-second) invitedAt, id set via reflection
     * since the entity builder deliberately has no id-after-build setter for invitedAt/status. */
    private ExternalCreator invitedExternalCreator(String id, Instant invitedAt) throws Exception {
        ExternalCreator external = ExternalCreator.builder().id(id).source(ExternalCreatorSource.ADMIN_IMPORT).igUsername("foodie.mumbai").build();
        external.markInvited("creator@example.com");
        setField(external, "invitedAt", invitedAt);
        return external;
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field f = ExternalCreator.class.getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }

    @Test
    @DisplayName(
            "Q5.5: a freshly issued token for an INVITED row is consumed successfully even though"
                    + " invitedAt carries a non-zero millisecond component (the precision bug)")
    void consumeInviteToken_validTokenForInvitedRow_links() throws Exception {
        Instant invitedAt = Instant.parse("2026-09-03T10:15:30.427Z");
        ExternalCreator external = invitedExternalCreator(EXTERNAL_CREATOR_ID, invitedAt);
        when(externalCreatorRepository.findById(EXTERNAL_CREATOR_ID)).thenReturn(Optional.of(external));

        String token = inviteTokenService.issue(EXTERNAL_CREATOR_ID, invitedAt);

        boolean linked = service.consumeInviteToken(token, CREATOR_PROFILE_ID);

        assertTrue(linked, "a valid, unexpired, INVITED-status token must be consumed");
        verify(externalCreatorLinkService).linkViaVerifiedInvite(EXTERNAL_CREATOR_ID, CREATOR_PROFILE_ID);
    }

    @Test
    @DisplayName(
            "Q5.5: invitedAt read back from the DB truncated to whole seconds (MySQL TIMESTAMP fsp 0)"
                    + " still matches the millisecond-precision Instant the token was issued against")
    void consumeInviteToken_dbTruncatedInvitedAt_stillMatches() throws Exception {
        Instant issuedAgainst = Instant.parse("2026-09-03T10:15:30.999Z");
        Instant dbTruncated = issuedAgainst.truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        ExternalCreator external = invitedExternalCreator(EXTERNAL_CREATOR_ID, dbTruncated);
        when(externalCreatorRepository.findById(EXTERNAL_CREATOR_ID)).thenReturn(Optional.of(external));

        String token = inviteTokenService.issue(EXTERNAL_CREATOR_ID, issuedAgainst);

        boolean linked = service.consumeInviteToken(token, CREATOR_PROFILE_ID);

        assertTrue(linked);
        verify(externalCreatorLinkService).linkViaVerifiedInvite(EXTERNAL_CREATOR_ID, CREATOR_PROFILE_ID);
    }

    @Test
    @DisplayName("Q5.5: a re-invite after the token was issued re-stamps invitedAt, invalidating it")
    void consumeInviteToken_reinvited_tokenRejected() throws Exception {
        Instant originalInvitedAt = Instant.parse("2026-09-03T10:15:30.427Z");
        String token = inviteTokenService.issue(EXTERNAL_CREATOR_ID, originalInvitedAt);

        // Row was re-invited AFTER the token above was issued — invitedAt moved forward.
        ExternalCreator external =
                invitedExternalCreator(EXTERNAL_CREATOR_ID, Instant.parse("2026-09-03T11:00:00.000Z"));
        when(externalCreatorRepository.findById(EXTERNAL_CREATOR_ID)).thenReturn(Optional.of(external));

        boolean linked = service.consumeInviteToken(token, CREATOR_PROFILE_ID);

        assertFalse(linked, "a token superseded by a re-invite must not link");
        verify(externalCreatorLinkService, never()).linkViaVerifiedInvite(anyString(), anyString());
    }

    @Test
    @DisplayName("Q5.5: a null/blank token never throws and never links")
    void consumeInviteToken_blankToken_returnsFalseSafely() {
        assertFalse(service.consumeInviteToken(null, CREATOR_PROFILE_ID));
        assertFalse(service.consumeInviteToken("  ", CREATOR_PROFILE_ID));
        verify(externalCreatorLinkService, never()).linkViaVerifiedInvite(anyString(), anyString());
    }
}
