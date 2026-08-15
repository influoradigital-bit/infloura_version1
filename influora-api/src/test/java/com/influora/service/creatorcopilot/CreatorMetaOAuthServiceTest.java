package com.influora.service.creatorcopilot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.integration.meta.client.FacebookPageClient;
import com.influora.integration.meta.dto.FacebookAccountsListResponse.InstagramBusinessAccount;
import com.influora.integration.meta.dto.MetaPermissionsResponse;
import com.influora.integration.meta.dto.MetaPermissionsResponse.Permission;
import com.influora.integration.meta.dto.MetaTokenResponse;
import com.influora.integration.meta.exception.MetaApiException;
import com.influora.integration.meta.oauth.MetaOAuthService;
import com.influora.integration.meta.oauth.MetaTokenStorage;
import com.influora.service.creatorcopilot.CreatorMetaOAuthService.ConnectResult;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link CreatorMetaOAuthService#connect} — CR-104.
 *
 * <p>Before this fix, {@code connect} stored and returned {@code MetaOAuthService.REQUIRED_SCOPES}
 * unconditionally as {@code grantedScopes}, regardless of what Meta's {@code /me/permissions}
 * actually reports. A creator who declined a permission in the consent dialog still saw it listed
 * as granted. These tests pin the real behavior: {@code grantedScopes} reflects only the entries
 * {@code /me/permissions} reports as {@code "granted"}, and a transient failure of that check
 * degrades to {@code null} ("unknown") rather than fabricating a full grant.
 */
@ExtendWith(MockitoExtension.class)
class CreatorMetaOAuthServiceTest {

    private static final String CREATOR_PROFILE_ID = "01HCREATOR123456789AB";
    private static final String CODE = "auth-code";
    private static final String SHORT_LIVED_TOKEN = "short-lived-token";
    private static final String LONG_LIVED_TOKEN = "long-lived-token";

    @Mock private MetaOAuthService oAuthService;
    @Mock private MetaTokenStorage tokenStorage;
    @Mock private FacebookPageClient facebookPageClient;

    private CreatorMetaOAuthService service;

    @BeforeEach
    void setUp() {
        service = new CreatorMetaOAuthService(oAuthService, tokenStorage, facebookPageClient);

        when(oAuthService.exchangeCodeForToken(CODE))
                .thenReturn(new MetaTokenResponse(SHORT_LIVED_TOKEN, "bearer", 3600L));
        when(oAuthService.exchangeForLongLivedToken(SHORT_LIVED_TOKEN))
                .thenReturn(new MetaTokenResponse(LONG_LIVED_TOKEN, "bearer", 5_184_000L));
        when(facebookPageClient.resolveConnectedInstagram(LONG_LIVED_TOKEN)).thenReturn(null);
    }

    @Test
    @DisplayName("connect: grantedScopes reflects only what /me/permissions reports as granted, not REQUIRED_SCOPES")
    void connect_partiallyDeclinedScopes_reportsOnlyActuallyGrantedOnes() {
        when(facebookPageClient.fetchPermissions(LONG_LIVED_TOKEN))
                .thenReturn(
                        new MetaPermissionsResponse(
                                List.of(
                                        new Permission("instagram_basic", "granted"),
                                        new Permission("instagram_manage_insights", "declined"),
                                        new Permission("pages_show_list", "granted"))));

        ConnectResult result = service.connect(CREATOR_PROFILE_ID, CODE);

        assertEquals(List.of("instagram_basic", "pages_show_list"), result.grantedScopes());
        assertTrue(
                result.grantedScopes().stream().noneMatch("instagram_manage_insights"::equals),
                "declined scope must not appear in grantedScopes");
        assertTrue(
                !result.grantedScopes().equals(MetaOAuthService.REQUIRED_SCOPES),
                "grantedScopes must not silently equal the requested-scope constant");

        verify(tokenStorage)
                .storeCreatorToken(
                        eq(CREATOR_PROFILE_ID),
                        eq(LONG_LIVED_TOKEN),
                        any(),
                        eq(List.of("instagram_basic", "pages_show_list")),
                        isNull());
    }

    @Test
    @DisplayName("connect: /me/permissions failure falls back to null (unknown), never REQUIRED_SCOPES")
    void connect_permissionsCheckFails_fallsBackToNullNotFullGrant() {
        when(facebookPageClient.fetchPermissions(LONG_LIVED_TOKEN))
                .thenThrow(new MetaApiException("Meta API call failed"));

        ConnectResult result = service.connect(CREATOR_PROFILE_ID, CODE);

        assertNull(result.grantedScopes(), "unverifiable grant state must surface as null, not a fabricated list");
        verify(tokenStorage)
                .storeCreatorToken(eq(CREATOR_PROFILE_ID), eq(LONG_LIVED_TOKEN), any(), isNull(), isNull());
    }

    @Test
    @DisplayName("connect: all scopes granted round-trips through storage and the response unchanged")
    void connect_allScopesGranted_roundTrips() {
        when(facebookPageClient.fetchPermissions(LONG_LIVED_TOKEN))
                .thenReturn(
                        new MetaPermissionsResponse(
                                MetaOAuthService.REQUIRED_SCOPES.stream()
                                        .map(scope -> new Permission(scope, "granted"))
                                        .toList()));

        ConnectResult result = service.connect(CREATOR_PROFILE_ID, CODE);

        assertEquals(MetaOAuthService.REQUIRED_SCOPES, result.grantedScopes());
    }

    @Test
    @DisplayName("connect: a business IG account resolution failure does not block granted-scope resolution")
    void connect_igResolutionFails_stillResolvesGrantedScopes() {
        when(facebookPageClient.resolveConnectedInstagram(LONG_LIVED_TOKEN))
                .thenThrow(new MetaApiException("igResolution failed"));
        when(facebookPageClient.fetchPermissions(LONG_LIVED_TOKEN))
                .thenReturn(
                        new MetaPermissionsResponse(List.of(new Permission("instagram_basic", "granted"))));

        ConnectResult result = service.connect(CREATOR_PROFILE_ID, CODE);

        assertEquals(List.of("instagram_basic"), result.grantedScopes());
        assertEquals("personal", result.accountType());
    }

    @Test
    @DisplayName(
            "CR-114: a null expires_in falls back to the ~60-day default, not an already-expired"
                    + " Instant.now()")
    void connect_nullExpiresIn_fallsBackToSixtyDayDefaultNotAlreadyExpired() {
        // Overrides setUp()'s default non-null stub — Meta unexpectedly omits expires_in.
        when(oAuthService.exchangeForLongLivedToken(SHORT_LIVED_TOKEN))
                .thenReturn(new MetaTokenResponse(LONG_LIVED_TOKEN, "bearer", null));
        when(facebookPageClient.fetchPermissions(LONG_LIVED_TOKEN))
                .thenReturn(new MetaPermissionsResponse(List.of(new Permission("instagram_basic", "granted"))));

        service.connect(CREATOR_PROFILE_ID, CODE);

        org.mockito.ArgumentCaptor<java.time.Instant> expiresAtCaptor =
                org.mockito.ArgumentCaptor.forClass(java.time.Instant.class);
        verify(tokenStorage)
                .storeCreatorToken(
                        eq(CREATOR_PROFILE_ID), eq(LONG_LIVED_TOKEN), expiresAtCaptor.capture(), any(), isNull());

        // Before the fix this was Instant.now() (0-second lifetime) — an already-expired token
        // stored as if the connect had succeeded. Assert it lands close to the ~60-day default,
        // not "now" or earlier.
        java.time.Instant expiresAt = expiresAtCaptor.getValue();
        java.time.Instant fiftyNineDaysOut = java.time.Instant.now().plusSeconds(59L * 24 * 60 * 60);
        assertTrue(
                expiresAt.isAfter(fiftyNineDaysOut),
                "expiresAt must fall back to ~60 days out, not an already-expired Instant.now(): was "
                        + expiresAt);
    }
}
