package com.influora.integration.ai;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.config.BrandSafetyServiceTokenProperties;
import com.influora.config.JwksSigningKeyProperties;
import com.influora.security.SpringJwksKeyService;
import com.influora.service.integration.BrandSafetyServiceTokenService;
import com.influora.testsupport.TestEcKeys;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.HttpClientResponseHandler;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link MeeraVoiceAiClient#checkFrame} -- the wire format of the Shoot Check forward.
 *
 * <p>The controller tests mock this client, so they prove nothing about what actually goes over
 * the wire. These tests capture the real {@link HttpPost} and read its bytes: the path, the service
 * token, and the three multipart field names influora-ai's route reads with {@code form.get(...)}.
 * A wrong name there would bind to nothing on the Python side and fail every request, which is the
 * kind of seam bug that passes every test on each side of it.
 */
@ExtendWith(MockitoExtension.class)
class MeeraVoiceAiClientFrameCheckTest {

    private static final String CREATOR_USER_ID = "01HCREATORUSER000000000001";
    private static final byte[] JPEG = new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 7, 7, 7};

    @Mock private CloseableHttpClient httpClient;

    private MeeraVoiceAiClient client;
    private SpringJwksKeyService jwksKeyService;

    @BeforeEach
    void setUp() {
        var tokenProps = new BrandSafetyServiceTokenProperties();
        tokenProps.setSigningSecret("test-signing-secret-at-least-32-bytes-long!!");
        var jwksProps = new JwksSigningKeyProperties();
        jwksProps.setPrivateKeyPem(TestEcKeys.PRIVATE_KEY_PEM);
        jwksProps.setPublicKeyPem(TestEcKeys.PUBLIC_KEY_PEM);
        jwksProps.setKid("test-kid-frame-check");
        jwksKeyService = new SpringJwksKeyService(jwksProps);
        var tokenService = new BrandSafetyServiceTokenService(tokenProps, jwksKeyService);
        client = new MeeraVoiceAiClient("http://localhost:8000", 10, tokenService, httpClient);
    }

    /** Real ES256 verification of the Bearer token a captured {@link HttpPost} carries. */
    private Claims verifiedClaimsOf(HttpPost request) {
        String bearer = request.getFirstHeader("Authorization").getValue().substring("Bearer ".length());
        return Jwts.parser().verifyWith(jwksKeyService.publicKey()).build().parseSignedClaims(bearer).getPayload();
    }

    private static ClassicHttpResponse response(int status, String json) {
        BasicClassicHttpResponse response = new BasicClassicHttpResponse(status);
        if (json != null) {
            response.setEntity(
                    new ByteArrayEntity(json.getBytes(StandardCharsets.UTF_8), ContentType.APPLICATION_JSON));
        }
        return response;
    }

    @SuppressWarnings("unchecked")
    private void respondWith(ClassicHttpResponse response) throws Exception {
        when(httpClient.execute(any(HttpPost.class), any(HttpClientResponseHandler.class)))
                .thenAnswer(invocation -> {
                    HttpClientResponseHandler<Object> handler = invocation.getArgument(1);
                    return handler.handleResponse(response);
                });
    }

    @SuppressWarnings("unchecked")
    private HttpPost capturedRequest() throws Exception {
        ArgumentCaptor<HttpPost> captor = ArgumentCaptor.forClass(HttpPost.class);
        verify(httpClient).execute(captor.capture(), any(HttpClientResponseHandler.class));
        return captor.getValue();
    }

    private static String bodyOf(HttpPost request) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        request.getEntity().writeTo(out);
        return out.toString(StandardCharsets.ISO_8859_1);
    }

    @Test
    @DisplayName("posts to influora-ai's exact frame route with a service bearer token")
    void postsToTheExactRouteWithABearer() throws Exception {
        respondWith(response(200, "{\"fixes\":[],\"settings\":[],\"ok\":[]}"));

        client.checkFrame(CREATOR_USER_ID, JPEG, "image/jpeg", null);

        HttpPost sent = capturedRequest();
        assertEquals("http://localhost:8000/ai/shoot-check/frame", sent.getUri().toString());
        assertTrue(sent.getFirstHeader("Authorization").getValue().startsWith("Bearer "));
    }

    @Test
    @DisplayName("sends exactly the field names the Python route reads: workspace_id, shot_label, image")
    void multipartFieldNamesMatchThePythonRoute() throws Exception {
        respondWith(response(200, "{}"));

        client.checkFrame(CREATOR_USER_ID, JPEG, "image/jpeg", "static overhead");

        String body = bodyOf(capturedRequest());
        assertTrue(body.contains("name=\"workspace_id\""), body);
        assertTrue(body.contains(CREATOR_USER_ID), "the creator's own id is the workspace");
        assertTrue(body.contains("name=\"shot_label\""), body);
        assertTrue(body.contains("static overhead"));
        assertTrue(body.contains("name=\"image\"; filename=\"frame.jpg\""), body);
        assertTrue(body.contains("Content-Type: image/jpeg"), body);
    }

    @Test
    @DisplayName("omits shot_label entirely when there is none, rather than sending an empty field")
    void noShotLabel_fieldOmitted() throws Exception {
        respondWith(response(200, "{}"));

        client.checkFrame(CREATOR_USER_ID, JPEG, "image/jpeg", "   ");

        assertFalse(bodyOf(capturedRequest()).contains("name=\"shot_label\""));
    }

    @Test
    @DisplayName("a 200 body is passed through verbatim")
    void success_passesBodyThrough() throws Exception {
        String json = "{\"fixes\":[\"Step right\"],\"settings\":[\"Grid on\"],\"ok\":[\"Light is even\"]}";
        respondWith(response(200, json));

        MeeraVoiceAiClient.FrameCheckResult result = client.checkFrame(CREATOR_USER_ID, JPEG, "image/jpeg", null);

        assertTrue(result.ok());
        assertArrayEquals(json.getBytes(StandardCharsets.UTF_8), result.jsonBytes());
    }

    @Test
    @DisplayName("a non-2xx from influora-ai is a failure carrying its status, never a pass-through")
    void non2xx_isAFailure() throws Exception {
        respondWith(response(403, "{\"code\":\"CONSENT_REQUIRED\"}"));

        MeeraVoiceAiClient.FrameCheckResult result = client.checkFrame(CREATOR_USER_ID, JPEG, "image/jpeg", null);

        assertFalse(result.ok());
        assertEquals(403, result.status());
    }

    @Test
    @DisplayName("a transport failure is a failure, not an exception at the controller")
    @SuppressWarnings("unchecked")
    void transportFailure_isAFailure() throws Exception {
        when(httpClient.execute(any(HttpPost.class), any(HttpClientResponseHandler.class)))
                .thenThrow(new IOException("connection refused"));

        MeeraVoiceAiClient.FrameCheckResult result = client.checkFrame(CREATOR_USER_ID, JPEG, "image/jpeg", null);

        assertFalse(result.ok());
    }

    @Test
    @DisplayName(
            "the frame check's response timeout outlasts influora-ai's own budget for that call (F-audit-A4)")
    void responseTimeoutOutlastsInfluoraAisRealBudget() throws Exception {
        respondWith(response(200, "{}"));

        client.checkFrame(CREATOR_USER_ID, JPEG, "image/jpeg", null);

        HttpPost sent = capturedRequest();
        long responseTimeoutSeconds = sent.getConfig().getResponseTimeout().toSeconds();
        // influora-ai/app/config.py's ProviderTimeouts for this route: claude_connect (3.0s) +
        // claude_read (30.0s) = a 33.0s worst case before Python itself gives up. Java must wait
        // strictly longer than that, or a merely-slow (not stuck) check fails here while Python
        // keeps running, finishes, and bills the creator for a check Java already told them failed.
        assertTrue(
                responseTimeoutSeconds > 33,
                "response timeout (" + responseTimeoutSeconds + "s) must exceed influora-ai's 33s budget"
                        + " (claude_connect 3.0s + claude_read 30.0s)");
    }

    @Test
    @DisplayName("no image or no workspace: no HTTP call at all")
    @SuppressWarnings("unchecked")
    void missingInputs_noCall() throws Exception {
        assertFalse(client.checkFrame(CREATOR_USER_ID, new byte[0], "image/jpeg", null).ok());
        assertFalse(client.checkFrame(" ", JPEG, "image/jpeg", null).ok());
        verify(httpClient, never()).execute(any(HttpPost.class), any(HttpClientResponseHandler.class));
    }

    // ------------------------------------------------------------------------------------------
    // F-audit-A1 -- checkFrameForCreator: mints WITH userType=CREATOR and forwards a real
    // on-behalf JWT. This is the specific hole A1 names: before this fix NEITHER the creator
    // monthly cap NOR influora-ai's own consent re-check ever applied to a frame check at all.
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("checkFrame (the plain overload): the minted token carries NO userType claim")
    void checkFrame_tokenCarriesNoUserTypeClaim() throws Exception {
        respondWith(response(200, "{}"));

        client.checkFrame(CREATOR_USER_ID, JPEG, "image/jpeg", null);

        assertEquals(null, verifiedClaimsOf(capturedRequest()).get("userType"));
    }

    @Test
    @DisplayName("checkFrameForCreator: mints a token with userType=CREATOR, verified for real (ES256)")
    void checkFrameForCreator_tokenCarriesCreatorUserType() throws Exception {
        respondWith(response(200, "{}"));

        client.checkFrameForCreator(CREATOR_USER_ID, JPEG, "image/jpeg", null, "real-onbehalf-jwt");

        Claims claims = verifiedClaimsOf(capturedRequest());
        assertEquals("CREATOR", claims.get("userType"));
        assertEquals(CREATOR_USER_ID, claims.get("workspace_id"));
        assertEquals("service", claims.get("scope"));
    }

    @Test
    @DisplayName("checkFrameForCreator: the multipart body carries an onbehalf_jwt field with the given value")
    void checkFrameForCreator_includesOnBehalfJwtField() throws Exception {
        respondWith(response(200, "{}"));

        client.checkFrameForCreator(CREATOR_USER_ID, JPEG, "image/jpeg", "static overhead", "real-onbehalf-jwt-value");

        String body = bodyOf(capturedRequest());
        assertTrue(body.contains("name=\"onbehalf_jwt\""), body);
        assertTrue(body.contains("real-onbehalf-jwt-value"), body);
        // every other field this route pins is still present, unaffected by the new field
        assertTrue(body.contains("name=\"shot_label\""), body);
        assertTrue(body.contains("name=\"image\"; filename=\"frame.jpg\""), body);
    }

    @Test
    @DisplayName("checkFrame (the plain overload) never includes an onbehalf_jwt multipart field")
    void checkFrame_neverIncludesOnBehalfJwtField() throws Exception {
        respondWith(response(200, "{}"));

        client.checkFrame(CREATOR_USER_ID, JPEG, "image/jpeg", "static overhead");

        assertFalse(bodyOf(capturedRequest()).contains("onbehalf_jwt"));
    }

    // ---------------------------------------------------------------------------------------
    // V76 -- the optional phone_model part, so the camera settings fit the creator's phone.
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("V76: checkFrameForCreator sends the saved phone as a phone_model text part")
    void checkFrameForCreator_includesPhoneModelPart() throws Exception {
        respondWith(response(200, "{}"));

        client.checkFrameForCreator(
                CREATOR_USER_ID, JPEG, "image/jpeg", "static overhead", "OPPO Reno 14 Pro", "real-onbehalf-jwt-value");

        String body = bodyOf(capturedRequest());
        assertTrue(
                body.contains("Content-Disposition: form-data; name=\"phone_model\"\r\n\r\nOPPO Reno 14 Pro\r\n"), body);
        // the fields this route already pins are unaffected by the new part
        assertTrue(body.contains("name=\"shot_label\""), body);
        assertTrue(body.contains("name=\"onbehalf_jwt\""), body);
        assertTrue(body.contains("name=\"image\"; filename=\"frame.jpg\""), body);
    }

    @Test
    @DisplayName("V76: the phone_model part is written as UTF-8, like shot_label")
    void phoneModelPart_isUtf8() throws Exception {
        respondWith(response(200, "{}"));
        String phone = "Galaxy S24 \u2013 5G";

        client.checkFrameForCreator(CREATOR_USER_ID, JPEG, "image/jpeg", null, phone, "jwt");

        String utf8AsLatin1 = new String(phone.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
        assertTrue(bodyOf(capturedRequest()).contains(utf8AsLatin1));
    }

    @Test
    @DisplayName("V76: no phone_model part at all when the phone is null")
    void nullPhoneModel_partOmitted() throws Exception {
        respondWith(response(200, "{}"));

        client.checkFrameForCreator(CREATOR_USER_ID, JPEG, "image/jpeg", "static overhead", null, "jwt");

        assertFalse(bodyOf(capturedRequest()).contains("phone_model"));
    }

    @Test
    @DisplayName("V76: no phone_model part at all when the phone is blank")
    void blankPhoneModel_partOmitted() throws Exception {
        respondWith(response(200, "{}"));

        client.checkFrameForCreator(CREATOR_USER_ID, JPEG, "image/jpeg", "static overhead", "   ", "jwt");

        assertFalse(bodyOf(capturedRequest()).contains("phone_model"));
    }

    @Test
    @DisplayName("V76: the pre-V76 overloads never send a phone_model part")
    void oldOverloads_neverSendPhoneModel() throws Exception {
        respondWith(response(200, "{}"));

        client.checkFrameForCreator(CREATOR_USER_ID, JPEG, "image/jpeg", "static overhead", "jwt");

        assertFalse(bodyOf(capturedRequest()).contains("phone_model"));
    }
}
