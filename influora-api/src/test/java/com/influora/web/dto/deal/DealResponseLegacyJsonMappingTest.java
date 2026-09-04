package com.influora.web.dto.deal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.influora.web.dto.deal.DealDtos.DealResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Gate fix round 2, item 4 (Priya Q9) — "no test deserializes an old-shape offer JSON with no
 * {@code dealTerms} key." {@code com.influora.service.DealServiceTest} and friends only ever
 * construct a {@code Collaboration} in Java and call {@code DealService.toDealResponse} on it
 * (proving the SERVER SIDE never fabricates a {@code dealTerms} block for a pre-A2 row), which is
 * a different claim from this one: that a client (or a cached/stored response body) from BEFORE
 * {@code dealTerms}
 * existed on the wire still deserializes cleanly into today's {@link DealResponse} record, with
 * the field landing as {@code null} rather than throwing (records fail construction on an unknown
 * REQUIRED constructor arg only if the class demands one — this proves {@code dealTerms} is not
 * effectively required by any Jackson mechanism, e.g. a stray {@code @JsonProperty(required =
 * true)} or a non-nullable primitive that would NPE on unboxing).
 *
 * <p>Uses a plain {@code ObjectMapper()} â€” Jackson (2.12+, and this project runs 2.15+ via Spring
 * Boot 3.3.5's managed BOM) has native record support that reads constructor parameter names
 * straight off the record's own class file, which is always available for a record regardless of
 * the {@code -parameters} javac flag (unlike an ordinary class's constructor). No {@code
 * @JsonProperty} overrides exist on {@link DealResponse}, so its wire shape is exactly its
 * camelCase component names.
 */
class DealResponseLegacyJsonMappingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * A legacy offer response body, shaped exactly as {@code DealService#toDealResponse} would
     * have emitted it before T-MEERA-CREATOR-PHASE-A (SPEC.md 1.1/4.2, A2) added {@code
     * dealTerms} -- the key is entirely absent, not present-and-null.
     */
    private static final String LEGACY_RESPONSE_BODY =
            """
            {
              "id": "deal_legacy_1",
              "campaignId": "camp_1",
              "campaignName": "Legacy Campaign",
              "counterpartyId": "user_1",
              "counterpartyProfileId": "cp_1",
              "counterpartyName": "Jane Creator",
              "counterpartyAvatar": null,
              "counterpartyHandle": "janecreator",
              "counterpartyVerificationStatus": null,
              "status": "ACCEPTED_IS_NOT_A_REAL_STATUS_PLACEHOLDER",
              "dealValue": 5000.00,
              "currency": "INR",
              "lastMessage": "Sounds good",
              "unreadCount": 0,
              "deliverablesDone": 1,
              "deliverablesTotal": 3,
              "contractId": "contract_1",
              "contractStatus": "ACTIVE",
              "escrowFunded": true
            }
            """
                    // status must be a real CollaborationStatus name; kept as a separate replace so the
                    // literal above stays readable as "this key really is just a placeholder to edit".
                    .replace("ACCEPTED_IS_NOT_A_REAL_STATUS_PLACEHOLDER", "CONTRACTED");

    @Test
    @DisplayName(
            "a pre-A2 offer JSON with no dealTerms key deserializes into DealResponse with"
                    + " dealTerms() == null, not a deserialization failure")
    void legacyResponseWithNoDealTermsKeyMapsToNullDealTerms() throws Exception {
        DealResponse response = MAPPER.readValue(LEGACY_RESPONSE_BODY, DealResponse.class);

        assertNull(response.dealTerms());
        assertEquals("deal_legacy_1", response.id());
        assertEquals("camp_1", response.campaignId());
        assertEquals("Jane Creator", response.counterpartyName());
        assertEquals(com.influora.domain.enums.CollaborationStatus.CONTRACTED, response.status());
        assertEquals(new java.math.BigDecimal("5000.00"), response.dealValue());
        assertEquals(3, response.deliverablesTotal());
        assertEquals(true, response.escrowFunded());
    }

    /**
     * Companion case: {@code dealTerms} present but explicitly {@code null} (a client that always
     * writes every key) must also map to {@code null}, not throw -- same guarantee, different
     * wire shape for "no terms".
     */
    @Test
    @DisplayName("dealTerms present-and-explicitly-null also maps to null, not a deserialization failure")
    void explicitNullDealTermsAlsoMapsToNull() throws Exception {
        String body = LEGACY_RESPONSE_BODY.substring(0, LEGACY_RESPONSE_BODY.lastIndexOf('}'))
                + ", \"dealTerms\": null }";

        DealResponse response = MAPPER.readValue(body, DealResponse.class);

        assertNull(response.dealTerms());
    }
}
