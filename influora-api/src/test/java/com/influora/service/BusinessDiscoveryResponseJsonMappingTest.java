package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.influora.integration.meta.dto.BusinessDiscoveryResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T-CREATORCONNECT-0902 Q1.1 (Medium) regression pin.
 *
 * <p>The finding: "A field-name or shape mismatch in the DTO would ship undetected." {@link
 * ExternalCreatorServiceTest#lookup_happyPath_parsesAndPersistsBusinessDiscoveryResponse}
 * constructs a {@code BusinessDiscoveryResponse.BusinessDiscovery} directly in Java and stubs
 * {@code InstagramInsightsClient.businessDiscovery(...)} to return it — useful for pinning that a
 * correctly-populated DTO gets persisted correctly, but it never deserializes a single byte of
 * JSON, so it cannot catch a wrong {@code @JsonProperty} name. This test feeds a captured {@code
 * GET /{ig-user-id}?fields=business_discovery{...}} response body (shape per Meta Graph API v25.0
 * docs: {@code business_discovery} nested under the outer {@code id}, with {@code
 * followers_count}/{@code media_count}/{@code profile_picture_url} snake_case) through the SAME
 * {@link ObjectMapper} deserialization path {@code InstagramInsightsClient} uses (Jackson, no
 * custom modules needed — this DTO carries no {@code Instant}/temporal fields), so a renamed or
 * misspelled {@code @JsonProperty} on {@link BusinessDiscoveryResponse} or its nested {@code
 * BusinessDiscovery} record fails THIS test with a null/wrong field instead of shipping silently.
 */
class BusinessDiscoveryResponseJsonMappingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A real {@code business_discovery} response body, shaped exactly per Meta Graph API v25.0
     * docs — the outer object's own {@code id} is the CALLER's ig-user-id, distinct from the
     * nested {@code business_discovery.id} (the TARGET account's ig_account_id). Unknown fields
     * (Meta adds new ones over time; {@code @JsonIgnoreProperties(ignoreUnknown = true)} on both
     * records) are deliberately included to prove that annotation still lets the known fields
     * through. */
    private static final String CAPTURED_RESPONSE_BODY =
            """
            {
              "id": "17841400000099999",
              "business_discovery": {
                "id": "17841400000000123",
                "username": "foodie.mumbai",
                "name": "Foodie Mumbai",
                "biography": "Street food across the city.",
                "profile_picture_url": "https://scontent.cdninstagram.com/v/pic.jpg",
                "followers_count": 42000,
                "media_count": 120,
                "website": "https://foodiemumbai.example",
                "ig_id": 17841400000000123
              }
            }
            """;

    @Test
    @DisplayName(
            "a captured business_discovery Graph response deserializes into the exact fields"
                    + " ExternalCreatorService persists (Q1.1)")
    void deserializesCapturedResponseIntoExpectedFields() throws Exception {
        BusinessDiscoveryResponse response =
                MAPPER.readValue(CAPTURED_RESPONSE_BODY, BusinessDiscoveryResponse.class);

        assertEquals("17841400000099999", response.id());
        assertNotNull(response.businessDiscovery(), "business_discovery must not be null for a real response");

        BusinessDiscoveryResponse.BusinessDiscovery bd = response.businessDiscovery();
        assertEquals("17841400000000123", bd.id());
        assertEquals("foodie.mumbai", bd.username());
        assertEquals("Foodie Mumbai", bd.name());
        assertEquals("Street food across the city.", bd.biography());
        assertEquals("https://scontent.cdninstagram.com/v/pic.jpg", bd.profilePictureUrl());
        assertEquals(42000L, bd.followersCount());
        assertEquals(120L, bd.mediaCount());
    }

    @Test
    @DisplayName("business_discovery absent (no such professional account) deserializes to null, never a stub")
    void deserializesMissingBusinessDiscoveryAsNull() throws Exception {
        BusinessDiscoveryResponse response =
                MAPPER.readValue("{\"id\": \"17841400000099999\"}", BusinessDiscoveryResponse.class);

        assertEquals("17841400000099999", response.id());
        assertEquals(null, response.businessDiscovery());
    }
}
