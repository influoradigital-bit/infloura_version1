package com.influora.service.creatorcopilot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.influora.domain.entity.MetaAuthPath;
import com.influora.integration.meta.client.FacebookPageClient;
import com.influora.integration.meta.client.MetaGraphApiClient;
import com.influora.integration.meta.dto.InstagramShortLivedTokenResponse;
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
import org.springframework.context.ApplicationEventPublisher;

/**
 * F-0818 — the {@code INSTAGRAM_LOGIN} connect path.
 *
 * <p>Separate from {@link CreatorMetaOAuthServiceTest} because that class stubs the FACEBOOK_LOGIN
 * exchange chain in {@code @BeforeEach}; under Mockito strict stubs those stubs would be reported
 * as unnecessary by every test here, which touches none of them.
 *
 * <p><b>What went wrong in production.</b> Instagram's code exchange returns HTTP 200 with the
 * token inside a {@code data} array. The DTO bound top-level fields, so it produced a record of
 * nulls and threw nothing, and {@code connectViaInstagramLogin} handed that null straight to the
 * long-lived exchange. The visible symptom was a 400 from graph.instagram.com — one leg LATER
 * than the actual defect — while {@code instagram-code-exchange failures} sat at zero. Nineteen
 * creator connects over 2026-09-13..15, zero tokens stored. These tests cover both halves of the
 * fix: the body now binds, and a body that does not bind fails on the leg that failed.
 */
@ExtendWith(MockitoExtension.class)
class CreatorMetaOAuthServiceInstagramLoginTest {

    private static final String CREATOR_PROFILE_ID = "01HCREATOR123456789AB";
    private static final String CODE = "ig-auth-code";
    private static final String SHORT_LIVED = "IGAAQ1short";
    private static final String LONG_LIVED = "IGAAQ1long";
    private static final String IG_USER_ID = "17841400000000001";

    /** Meta's documented Business Login response, verbatim in shape. */
    private static final String WRAPPED_BODY =
            """
            {"data":[{"access_token":"IGAAQ1short",
                      "user_id":17841400000000001,
                      "permissions":"instagram_business_basic,instagram_business_manage_insights"}]}
            """;

    @Mock private MetaOAuthService oAuthService;
    @Mock private MetaTokenStorage tokenStorage;
    @Mock private FacebookPageClient facebookPageClient;
    @Mock private MetaGraphApiClient graphApiClient;
    @Mock private ApplicationEventPublisher eventPublisher;

    private CreatorMetaOAuthService service;

    @BeforeEach
    void setUp() {
        service =
                new CreatorMetaOAuthService(
                        oAuthService, tokenStorage, facebookPageClient, graphApiClient, eventPublisher);
    }

    @Test
    @DisplayName(
            "F-0818: a real data-wrapped exchange body connects and stores the Instagram user id")
    void wrappedExchangeBodyConnects() throws Exception {
        // Parsed through Jackson rather than hand-built, so this test fails if the DTO stops
        // understanding the shape Meta actually sends — the whole point of the fix.
        InstagramShortLivedTokenResponse shortLived =
                new ObjectMapper().readValue(WRAPPED_BODY, InstagramShortLivedTokenResponse.class);
        when(oAuthService.exchangeInstagramCodeForToken(CODE)).thenReturn(shortLived);
        when(oAuthService.exchangeInstagramForLongLivedToken(SHORT_LIVED))
                .thenReturn(new MetaTokenResponse(LONG_LIVED, "bearer", 5_184_000L));

        ConnectResult result = service.connect(CREATOR_PROFILE_ID, CODE, MetaAuthPath.INSTAGRAM_LOGIN);

        assertTrue(result.connected(), "a successful Business Login exchange is a connected result");
        assertEquals("business", result.accountType());
        assertEquals(
                List.of("instagram_business_basic", "instagram_business_manage_insights"),
                result.grantedScopes());
        verify(tokenStorage)
                .storeCreatorToken(
                        eq(CREATOR_PROFILE_ID),
                        eq(LONG_LIVED),
                        any(),
                        eq(List.of("instagram_business_basic", "instagram_business_manage_insights")),
                        eq(IG_USER_ID),
                        eq(MetaAuthPath.INSTAGRAM_LOGIN));
        // There is no Facebook Page on this path; calling for one would be a bug of its own.
        verifyNoInteractions(facebookPageClient);
    }

    @Test
    @DisplayName("F-0818: a null access_token fails on the CODE exchange, not one leg later")
    void nullAccessTokenIsRefusedBeforeTheLongLivedExchange() {
        when(oAuthService.exchangeInstagramCodeForToken(CODE))
                .thenReturn(new InstagramShortLivedTokenResponse(null, null, List.of()));

        MetaApiException thrown =
                assertThrows(
                        MetaApiException.class,
                        () -> service.connect(CREATOR_PROFILE_ID, CODE, MetaAuthPath.INSTAGRAM_LOGIN));

        assertTrue(
                thrown.getMessage().contains("access_token"),
                "the error must name the leg that actually failed; production spent two days"
                        + " reading a graph.instagram.com 400 as a wrong-endpoint problem: "
                        + thrown.getMessage());
        verify(oAuthService, never()).exchangeInstagramForLongLivedToken(any());
        verify(tokenStorage, never())
                .storeCreatorToken(anyString(), anyString(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("F-0818: a blank access_token is refused too, since urlEncode of blank is no token")
    void blankAccessTokenIsRefused() {
        when(oAuthService.exchangeInstagramCodeForToken(CODE))
                .thenReturn(new InstagramShortLivedTokenResponse("   ", IG_USER_ID, List.of()));

        assertThrows(
                MetaApiException.class,
                () -> service.connect(CREATOR_PROFILE_ID, CODE, MetaAuthPath.INSTAGRAM_LOGIN));

        verify(oAuthService, never()).exchangeInstagramForLongLivedToken(any());
    }

    @Test
    @DisplayName("F-0818: nothing is stored when the exchange body cannot be understood")
    void nothingIsStoredOnAnUnparseableBody() throws Exception {
        InstagramShortLivedTokenResponse empty =
                new ObjectMapper()
                        .readValue("{\"data\":[]}", InstagramShortLivedTokenResponse.class);
        when(oAuthService.exchangeInstagramCodeForToken(CODE)).thenReturn(empty);

        assertThrows(
                MetaApiException.class,
                () -> service.connect(CREATOR_PROFILE_ID, CODE, MetaAuthPath.INSTAGRAM_LOGIN));

        verify(tokenStorage, never())
                .storeCreatorToken(anyString(), anyString(), any(), any(), any(), any());
        verifyNoInteractions(eventPublisher);
    }
}
