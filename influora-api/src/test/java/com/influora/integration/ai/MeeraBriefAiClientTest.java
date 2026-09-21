package com.influora.integration.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.influora.config.CreatorSuggestionAiProperties;
import com.influora.config.JwksSigningKeyProperties;
import com.influora.integration.ai.MeeraBriefAiClient.BriefResult;
import com.influora.security.SpringJwksKeyService;
import com.influora.service.integration.CreatorSuggestionServiceTokenService;
import com.influora.testsupport.TestEcKeys;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * T-MEERA-CREATOR-PHASE-B (SPEC.md &sect;3.8 step 3 / &sect;7.5), B0-40.
 *
 * <p>Two things here are not shape assertions and are the reason the file exists.
 *
 * <p><b>The token is CREATOR-scoped and carries a {@code creator_profile_id}.</b> SPEC.md
 * &sect;3.8 originally said to mirror {@code MeeraVoiceAiClient}, which mints a SERVICE-scoped token
 * with a {@code workspace_id}; the Python route's {@code ENDPOINT_SCOPES} refuses that, so the call
 * would have 403'd every single time and — because the service falls back on any failure — would have
 * looked like a permanently degraded AI rather than a broken client. The token claims are decoded here
 * so that cannot regress silently.
 *
 * <p><b>A cap is not an outage.</b> The route returns HTTP 200 for both, distinguished only by the
 * error code, and the creator must read two different sentences. If those collapsed, a creator who has
 * simply used her allowance would be told Influora is broken.
 */
@ExtendWith(MockitoExtension.class)
class MeeraBriefAiClientTest {

    private static final String CREATOR_PROFILE_ID = "01HCREATORPROFILE12345";
    private static final String RAW_BRIEF = "Glow Cosmetics wants 1 reel. INR 8000.";

    @Mock private HttpClient httpClient;
    @Mock private HttpResponse<String> httpResponse;

    private MeeraBriefAiClient client;

    @BeforeEach
    void setUp() {
        CreatorSuggestionAiProperties props = new CreatorSuggestionAiProperties();
        props.setBaseUrl("http://localhost:8000");

        var tokenProps = new com.influora.config.BrandSafetyServiceTokenProperties();
        tokenProps.setSigningSecret("test-signing-secret-at-least-32-bytes-long!!");
        var jwksProps = new JwksSigningKeyProperties();
        jwksProps.setPrivateKeyPem(TestEcKeys.PRIVATE_KEY_PEM);
        jwksProps.setPublicKeyPem(TestEcKeys.PUBLIC_KEY_PEM);
        jwksProps.setKid("test-kid-brief-client");
        CreatorSuggestionServiceTokenService tokenService =
                new CreatorSuggestionServiceTokenService(
                        tokenProps, new SpringJwksKeyService(jwksProps));

        client = new MeeraBriefAiClient(props, tokenService, httpClient);
    }

    private void respond(int status, String body) throws Exception {
        lenient().when(httpResponse.statusCode()).thenReturn(status);
        lenient().when(httpResponse.body()).thenReturn(body);
        when(httpClient.send(any(HttpRequest.class), any())).thenReturn((HttpResponse) httpResponse);
    }

    @Test
    @DisplayName("happy path parses the shared §2.11 extraction contract")
    void happyPath() throws Exception {
        respond(
                200,
                """
                {"success": true, "data": {
                  "brand_name": "Glow Cosmetics",
                  "product": "Vitamin C serum",
                  "category": "BEAUTY",
                  "deliverables": [{"type": "REEL", "qty": 1}],
                  "budget_inr": 8000,
                  "budget_stated": true,
                  "summary_lines": ["a", "b", "c"]
                }}
                """);

        BriefResult result = client.extract(CREATOR_PROFILE_ID, RAW_BRIEF, "en-IN");

        assertTrue(result.extraction().isPresent());
        assertFalse(result.capReached());
        assertEquals("Glow Cosmetics", result.extraction().get().brandName());
        assertEquals(new BigDecimal("8000"), result.extraction().get().budgetInr());
        assertEquals("REEL", result.extraction().get().deliverables().get(0).type());
    }

    @Test
    @DisplayName("the request carries a CREATOR-scoped token with the creator_profile_id, never a workspace_id")
    void mintsACreatorScopedToken() throws Exception {
        respond(200, "{\"success\": true, \"data\": {\"budget_stated\": false}}");

        client.extract(CREATOR_PROFILE_ID, RAW_BRIEF, "en-IN");

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(captor.capture(), any());
        HttpRequest request = captor.getValue();
        assertTrue(request.uri().toString().endsWith("/internal/brief-extract"));

        String authorization = request.headers().firstValue("Authorization").orElseThrow();
        assertTrue(authorization.startsWith("Bearer "));
        String payload = authorization.substring("Bearer ".length()).split("\\.")[1];
        String claims = new String(Base64.getUrlDecoder().decode(payload));
        assertTrue(claims.contains("\"scope\":\"creator\""), claims);
        assertTrue(claims.contains("\"creator_profile_id\":\"" + CREATOR_PROFILE_ID + "\""), claims);
        assertFalse(
                claims.contains("workspace_id"),
                "a creator has no workspace, and a SERVICE-scoped token cannot open this route");
    }

    @Test
    @DisplayName("the request contract has exactly the three §7.5 fields and no workspace_id")
    void requestContractDropsWorkspaceId() {
        var components =
                com.influora.integration.ai.dto.MeeraBriefAiDtos.ExtractRequest.class
                        .getRecordComponents();
        assertEquals(3, components.length);
        assertTrue(
                java.util.Arrays.stream(components)
                        .noneMatch(c -> c.getName().toLowerCase().contains("workspace")),
                "the Python route is keyed on creator_profile_id; a workspace id there reads as"
                        + " nothing and implies the caller thinks this is a brand surface");
    }

    @Test
    @DisplayName("CREATOR_MONTHLY_CAP_REACHED at HTTP 200 is a CAP, not an outage")
    void capIsDistinguished() throws Exception {
        respond(200, "{\"success\": false, \"error\": {\"code\": \"CREATOR_MONTHLY_CAP_REACHED\"}}");

        BriefResult result = client.extract(CREATOR_PROFILE_ID, RAW_BRIEF, "en-IN");

        assertTrue(result.extraction().isEmpty());
        assertTrue(result.capReached());
    }

    @Test
    @DisplayName("extraction_failed at HTTP 200 is an outage, not a cap")
    void extractionFailedIsAnOutage() throws Exception {
        respond(200, "{\"success\": false, \"error\": {\"code\": \"extraction_failed\"}}");

        BriefResult result = client.extract(CREATOR_PROFILE_ID, RAW_BRIEF, "en-IN");

        assertTrue(result.extraction().isEmpty());
        assertFalse(result.capReached());
    }

    @Test
    @DisplayName("success:true with no data is a contract violation, not a cap")
    void successWithNoData() throws Exception {
        respond(200, "{\"success\": true}");

        BriefResult result = client.extract(CREATOR_PROFILE_ID, RAW_BRIEF, "en-IN");

        assertTrue(result.extraction().isEmpty());
        assertFalse(result.capReached());
    }

    @Test
    @DisplayName("a non-200 (auth, missing id) degrades rather than throwing — the creator's text is already saved")
    void nonTwoHundredDegrades() throws Exception {
        respond(403, "{\"detail\": {\"code\": \"scope_mismatch\"}}");

        BriefResult result = client.extract(CREATOR_PROFILE_ID, RAW_BRIEF, "en-IN");

        assertTrue(result.extraction().isEmpty());
        assertFalse(result.capReached());
    }

    @Test
    @DisplayName("a malformed body degrades rather than throwing")
    void malformedBodyDegrades() throws Exception {
        respond(200, "not json at all");

        BriefResult result = client.extract(CREATOR_PROFILE_ID, RAW_BRIEF, "en-IN");

        assertTrue(result.extraction().isEmpty());
        assertFalse(result.capReached());
    }

    @Test
    @DisplayName("a transport failure degrades rather than throwing")
    void transportFailureDegrades() throws Exception {
        when(httpClient.send(any(HttpRequest.class), any()))
                .thenThrow(new java.io.IOException("connection refused"));

        BriefResult result = client.extract(CREATOR_PROFILE_ID, RAW_BRIEF, "en-IN");

        assertTrue(result.extraction().isEmpty());
        assertFalse(result.capReached());
    }

    @Test
    @DisplayName("a blank profile id or blank text never reaches the wire — no token spend on an empty paste")
    void refusesToCallWithNothingToSay() {
        assertTrue(client.extract(null, RAW_BRIEF, "en-IN").extraction().isEmpty());
        assertTrue(client.extract("  ", RAW_BRIEF, "en-IN").extraction().isEmpty());
        assertTrue(client.extract(CREATOR_PROFILE_ID, "   ", "en-IN").extraction().isEmpty());
        verifyNoInteractions(httpClient);
    }
}
