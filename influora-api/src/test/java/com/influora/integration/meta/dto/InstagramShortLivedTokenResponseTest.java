package com.influora.integration.meta.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * F-0818 — the Business Login code-exchange body must actually bind.
 *
 * <p>The defect these tests pin was <b>silent</b>. Instagram answers the exchange with HTTP 200,
 * so nothing threw; the old record simply bound three nulls and handed them on. Production logged
 * {@code instagram-code-exchange failures: 0} across nineteen failed creator connects and zero
 * {@code INSTAGRAM_LOGIN} tokens ever stored. A test asserting "no exception" would have passed
 * throughout — so every assertion here is on the VALUES, and the wrapped-body test is the one
 * that goes red the moment top-level-only binding comes back.
 */
class InstagramShortLivedTokenResponseTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /** Exactly what Meta's Business Login documentation returns, user_id as a JSON number. */
    private static final String WRAPPED =
            """
            {"data":[{"access_token":"IGAAQ1short",
                      "user_id":17841400000000001,
                      "permissions":"instagram_business_basic,instagram_business_manage_insights"}]}
            """;

    private InstagramShortLivedTokenResponse parse(String json) throws Exception {
        return mapper.readValue(json, InstagramShortLivedTokenResponse.class);
    }

    @Test
    @DisplayName("F-0818: the data-wrapped Business Login body binds all three fields")
    void dataWrappedBodyIsUnwrapped() throws Exception {
        InstagramShortLivedTokenResponse parsed = parse(WRAPPED);

        assertNotNull(
                parsed.accessToken(),
                "a null access_token here is the production defect: it was passed straight to the"
                        + " long-lived exchange, which answered 'Unsupported request - method type:"
                        + " get' and made the failure look like a wrong endpoint");
        assertEquals("IGAAQ1short", parsed.accessToken());
        assertEquals(
                "17841400000000001",
                parsed.userId(),
                "user_id is a JSON number in this body; on this path it is the ONLY source of the"
                        + " Instagram account id — there is no Page to resolve one from");
        assertEquals(
                java.util.List.of(
                        "instagram_business_basic", "instagram_business_manage_insights"),
                parsed.permissions(),
                "Business Login sends permissions as a comma-separated string, not a JSON array");
    }

    @Test
    @DisplayName("F-0818: the older flat body still binds — the fix accepts both shapes")
    void flatBodyStillParses() throws Exception {
        InstagramShortLivedTokenResponse parsed =
                parse("{\"access_token\":\"IGAAflat\",\"user_id\":\"17841400000000002\"}");

        assertEquals("IGAAflat", parsed.accessToken());
        assertEquals("17841400000000002", parsed.userId());
        assertEquals(java.util.List.of(), parsed.permissions(), "absent permissions is empty, not null");
    }

    @Test
    @DisplayName("F-0818: permissions as a JSON array parses identically to the comma string")
    void permissionsAsArrayParses() throws Exception {
        InstagramShortLivedTokenResponse parsed =
                parse(
                        "{\"access_token\":\"t\",\"user_id\":\"1\",\"permissions\":"
                                + "[\"instagram_business_basic\",\"instagram_business_manage_insights\"]}");

        assertEquals(
                java.util.List.of(
                        "instagram_business_basic", "instagram_business_manage_insights"),
                parsed.permissions());
    }

    @Test
    @DisplayName("F-0818: an empty data array yields nulls rather than throwing out of the parser")
    void emptyDataArrayYieldsNulls() throws Exception {
        InstagramShortLivedTokenResponse parsed = parse("{\"data\":[]}");

        assertNull(
                parsed.accessToken(),
                "the caller's null guard is what must report this, not an index error in Jackson");
        assertNull(parsed.userId());
        assertEquals(java.util.List.of(), parsed.permissions());
    }

    @Test
    @DisplayName("F-0818: an unrecognised body does not throw — it binds nulls for the guard to catch")
    void unknownShapeBindsNulls() throws Exception {
        InstagramShortLivedTokenResponse parsed =
                parse("{\"error_type\":\"OAuthException\",\"code\":400,\"error_message\":\"nope\"}");

        assertNull(parsed.accessToken());
        assertTrue(parsed.permissions().isEmpty());
    }

    @Test
    @DisplayName("F-0818: blank and padded permission entries are dropped, not stored as scopes")
    void blankPermissionEntriesAreDropped() throws Exception {
        InstagramShortLivedTokenResponse parsed =
                parse("{\"access_token\":\"t\",\"permissions\":\"a, b,,\"}");

        assertEquals(java.util.List.of("a", "b"), parsed.permissions());
    }
}
