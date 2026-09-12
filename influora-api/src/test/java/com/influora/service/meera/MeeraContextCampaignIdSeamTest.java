package com.influora.service.meera;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.influora.domain.entity.BrandAiCredit;
import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.Workspace;
import com.influora.domain.enums.CampaignIntentType;
import com.influora.domain.enums.CampaignStatus;
import com.influora.domain.enums.CampaignTemplateScope;
import com.influora.domain.enums.EscrowStatus;
import com.influora.repository.BrandProfileRepository;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CampaignTemplateRepository;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.CreatorAgentPreferencesRepository;
import com.influora.repository.CreatorMetricsRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.DeliverableMetricRepository;
import com.influora.repository.EscrowHoldRepository;
import com.influora.repository.UtmCampaignRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.web.dto.meera.MeeraContextDtos.ContextResponse;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * F-18 regression — {@code get_campaign_performance} was structurally uncallable, so Meera told
 * every brand "I can't pull verified numbers" for every campaign, forever.
 *
 * <p><b>The seam this test exercises, and why the existing 77 tests all missed it.</b> The defect
 * did not live in any single Java method — every producer was "correct" in isolation and every
 * existing assertion (on {@code PastCampaignEntry#type}, {@code #creatorCount}, {@code #funded})
 * passed for the entire lifetime of the bug. The defect lived in the SERIALIZED PAYLOAD that
 * crosses into influora-ai: {@code app/prompt/assembler.py} renders the {@code "[id=...]"} marker
 * only when a context entry carries {@code campaign_id} (or {@code campaignId}), {@code
 * persona.py} + {@code app/tools/schemas.py} forbid the model from inventing an id and require it
 * to copy one out of that marker, and neither {@code past_campaign_summary} nor {@code
 * outcome_digest.campaign_outcomes} ever carried an id field at all — so the lookup was always
 * null, the suffix was always {@code ""}, and the tool that exists specifically to stop Meera
 * estimating could never be invoked.
 *
 * <p>So this test asserts on the JSON, not on the record: it drives the real {@link
 * MeeraContextService} + {@link BrandContextAssembler} pair, serializes the response exactly as
 * the {@code /internal/meera/context} endpoint does, and then replays {@code assembler.py}'s own
 * marker-lookup logic ({@link #renderIdMarker}) against the resulting tree. A test that only
 * asserted "the record has a campaignId component" would be satisfied by a field Jackson never
 * emits under the key Python reads — which is precisely the class of bug being fixed.
 *
 * <p><b>Tenant safety (why emitting an id here is safe):</b> the id now travels out through an
 * untrusted model and back in as tool input. {@code GetCampaignPerformanceExecutor#execute}
 * re-resolves it with {@code CampaignRepository#findByIdAndWorkspaceId(campaignId,
 * ctx.workspaceId())} — the workspace leg comes from the server-minted on-behalf JWT
 * ({@code MeeraInternalController#getCampaignPerformance}), never from the model — and returns an
 * identical 404 for "no such campaign" and "another tenant's campaign". The id is a lookup key,
 * never an authorization token.
 */
@ExtendWith(MockitoExtension.class)
class MeeraContextCampaignIdSeamTest {

    private static final String WORKSPACE_ID = "01HWXYZWORKSPACE123456";

    /** The Campaign primary key — the exact identifier {@code findByIdAndWorkspaceId} resolves. */
    private static final String CAMPAIGN_ID = "01HWCAMPAIGNDIWALI0001";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock private WorkspaceRepository workspaceRepository;
    @Mock private BrandProfileRepository brandProfileRepository;
    @Mock private CampaignTemplateRepository templateRepository;
    @Mock private CampaignRepository campaignRepository;
    @Mock private CollaborationRepository collaborationRepository;
    @Mock private EscrowHoldRepository escrowHoldRepository;
    @Mock private DeliverableMetricRepository deliverableMetricRepository;
    @Mock private UtmCampaignRepository utmCampaignRepository;
    @Mock private AICreditService creditService;
    @Mock private Workspace workspace;
    @Mock private CreatorProfileRepository creatorProfileRepository;
    @Mock private CreatorAgentPreferencesRepository creatorAgentPreferencesRepository;
    @Mock private CreatorMetricsRepository creatorMetricsRepository;

    private MeeraContextService service;

    private Campaign campaign;

    @BeforeEach
    void setUp() {
        service =
                new MeeraContextService(
                        workspaceRepository,
                        brandProfileRepository,
                        templateRepository,
                        campaignRepository,
                        collaborationRepository,
                        escrowHoldRepository,
                        deliverableMetricRepository,
                        utmCampaignRepository,
                        creditService,
                        new BrandContextAssembler(),
                        creatorProfileRepository,
                        creatorAgentPreferencesRepository,
                        creatorMetricsRepository);

        campaign =
                Campaign.builder()
                        .id(CAMPAIGN_ID)
                        .workspaceId(WORKSPACE_ID)
                        .title("Diwali Sale")
                        .status(CampaignStatus.COMPLETED)
                        .createdBy("user1")
                        .campaignType(CampaignIntentType.HYPE)
                        .build();
    }

    /** Stubs the BRAND path for a workspace owning exactly {@link #campaign} and no collaborations. */
    private void givenOneFinishedCampaign() {
        when(workspaceRepository.findById(WORKSPACE_ID)).thenReturn(Optional.of(workspace));
        when(workspace.getId()).thenReturn(WORKSPACE_ID);
        when(workspace.getName()).thenReturn("Acme");
        when(brandProfileRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(Optional.empty());
        when(templateRepository.findByScope(CampaignTemplateScope.SYSTEM)).thenReturn(List.of());
        when(templateRepository.findByScopeAndWorkspaceId(CampaignTemplateScope.CUSTOM, WORKSPACE_ID))
                .thenReturn(List.of());
        when(campaignRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(List.of(campaign));
        when(collaborationRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(List.of());
        when(escrowHoldRepository.sumAmountByCampaignIdAndStatus(CAMPAIGN_ID, EscrowStatus.RELEASED))
                .thenReturn(new BigDecimal("50000"));
        when(utmCampaignRepository.findByCampaignId(CAMPAIGN_ID)).thenReturn(List.of());
        when(creditService.getStatus(WORKSPACE_ID))
                .thenReturn(BrandAiCredit.builder().workspaceId(WORKSPACE_ID).creditsRemaining(10).build());
    }

    private JsonNode assembleAndSerialize() {
        ContextResponse response = (ContextResponse) service.assemble(WORKSPACE_ID, "BRAND");
        return MAPPER.valueToTree(response);
    }

    /**
     * Verbatim replay of {@code assembler.py::_render_past_campaign_summary} lines 309-311 (and the
     * identical lookup in {@code _render_outcome_digest}):
     *
     * <pre>
     *   campaign_id = entry.get("campaign_id") or entry.get("campaignId")
     *   suffix = f" [id={_safe(str(campaign_id))}]" if campaign_id else ""
     * </pre>
     *
     * Both spellings are accepted on purpose — Python tolerates either, so this helper must too, or
     * the test would be asserting a Jackson naming strategy rather than the behaviour that matters.
     */
    private static String renderIdMarker(JsonNode entry) {
        JsonNode id = entry.get("campaign_id");
        if (id == null || id.isNull()) {
            id = entry.get("campaignId");
        }
        if (id == null || id.isNull() || id.asText().isEmpty()) {
            return "";
        }
        return " [id=" + id.asText() + "]";
    }

    @Test
    @DisplayName(
            "F-18: the SERIALIZED past_campaign_summary entry carries the campaign id, so"
                    + " assembler.py renders the [id=...] marker the model is required to copy from"
                    + " -- before the fix the entry was {type,creator_count,funded}, the lookup was"
                    + " always null and the marker was always the empty string")
    void testPastCampaignSummaryWireEntryRendersIdMarker() {
        givenOneFinishedCampaign();

        JsonNode entry = assembleAndSerialize().at("/past_campaign_summary/0");

        assertFalse(entry.isMissingNode(), "expected one past_campaign_summary entry on the wire");
        assertEquals(
                " [id=" + CAMPAIGN_ID + "]",
                renderIdMarker(entry),
                "assembler.py renders no marker unless the serialized entry carries campaign_id/campaignId;"
                        + " entry was: "
                        + entry);
    }

    @Test
    @DisplayName(
            "F-18: the SERIALIZED outcome_digest.campaign_outcomes entry carries the campaign id"
                    + " too -- the Phase-2 digest had the same hole as the Phase-1 summary")
    void testOutcomeDigestWireEntryRendersIdMarker() {
        givenOneFinishedCampaign();

        JsonNode entry = assembleAndSerialize().at("/outcome_digest/campaign_outcomes/0");

        assertFalse(entry.isMissingNode(), "expected one outcome_digest.campaign_outcomes entry on the wire");
        assertEquals(
                " [id=" + CAMPAIGN_ID + "]",
                renderIdMarker(entry),
                "assembler.py renders no marker unless the serialized entry carries campaign_id/campaignId;"
                        + " entry was: "
                        + entry);
    }

    @Test
    @DisplayName(
            "F-18: the id on the wire is the Campaign PRIMARY KEY -- the one identifier"
                    + " GetCampaignPerformanceExecutor can resolve via"
                    + " CampaignRepository#findByIdAndWorkspaceId. Emitting any other identifier"
                    + " (title, campaign_type, collaboration id) would turn today's honest refusal"
                    + " into a 404, which is strictly worse")
    void testEmittedIdIsTheCampaignPrimaryKeyTheExecutorResolves() {
        givenOneFinishedCampaign();

        JsonNode root = assembleAndSerialize();
        String fromSummary = root.at("/past_campaign_summary/0/campaign_id").asText();
        String fromDigest = root.at("/outcome_digest/campaign_outcomes/0/campaign_id").asText();

        assertEquals(campaign.getId(), fromSummary);
        assertEquals(campaign.getId(), fromDigest);
        // Both producers must agree: the model may copy the id out of either Block-B line.
        assertEquals(fromSummary, fromDigest);
        // Guard against the "wrong identifier" failure mode explicitly -- these are the values
        // sitting next to the id in the same entry/entity and are NOT resolvable by the executor.
        assertFalse(campaign.getTitle().equals(fromSummary), "emitted the campaign title, not its id");
        assertFalse(
                root.at("/past_campaign_summary/0/type").asText().equals(fromSummary),
                "emitted the campaign_type, not the campaign id");
    }

    @Test
    @DisplayName(
            "F-18: campaign_id uses the snake_case wire spelling the rest of this DTO already"
                    + " uses, alongside the untouched type/creator_count/funded fields -- the"
                    + " existing allow-listed shape is additive, nothing was renamed or dropped")
    void testWireKeysAreAdditiveAndSnakeCase() {
        givenOneFinishedCampaign();

        JsonNode entry = assembleAndSerialize().at("/past_campaign_summary/0");

        assertTrue(entry.has("campaign_id"), "expected the snake_case key Python reads first; got: " + entry);
        assertTrue(entry.has("type"));
        assertTrue(entry.has("creator_count"));
        assertTrue(entry.has("funded"));
        assertEquals("HYPE", entry.get("type").asText());
        assertEquals(0, entry.get("creator_count").asInt());
        assertTrue(entry.get("funded").asBoolean());
    }
}
