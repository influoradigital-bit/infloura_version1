package com.influora.service.tracking;

import com.influora.common.ApiException;
import com.influora.common.SlugUtils;
import com.influora.common.Ulids;
import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.UtmCampaign;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.UtmCampaignRepository;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Generates UTM-tagged tracking links for each creator in a campaign (Phase 4 UTM/Coupon Tracking,
 * VIKRAM_BACKEND_IMPLEMENTATION_SPEC.md §5.1). Pure-Java slice: no external dependency, no
 * shortener integration, no conversion/webhook wiring.
 *
 * <p>[SCOPE CUT] Coupons (V24 {@code coupon_codes}), Shopify/WooCommerce webhooks, and affiliate
 * campaigns are deliberately deferred to later slices — nothing here writes {@code
 * conversion_count}/{@code revenue_attributed}; those columns exist in the V23 schema for a later
 * pass to populate.
 *
 * <p><b>Workspace authorization ("resolve-then-scope"):</b> mirrors the pattern {@code
 * DeliverableMetricService#getCampaignAnalytics} and {@code MetricsAuthorizationService} already
 * established in this codebase (the exact class of concern Kabir flagged on the metrics
 * repositories) — never trust a caller-supplied {@code campaignId}/{@code collaborationId}/{@code
 * creatorProfileId} directly. {@link #createTrackingLink} resolves the campaign via {@code
 * campaignRepository.findByIdAndWorkspaceId(campaignId, workspaceId)} FIRST; only once that lookup
 * proves the calling workspace owns the campaign do we proceed to resolve the collaboration and
 * creator, and we additionally verify the collaboration actually belongs to that campaign and that
 * the creator profile actually corresponds to that collaboration's creator — otherwise one
 * workspace could mint a valid-looking tracking link for another workspace's campaign, or pair a
 * campaign with a creator/collaboration it has no relationship with.
 *
 * <p>{@link #recordClick} is intentionally NOT workspace-scoped — it is called from a public
 * tracking pixel/redirect endpoint (the visitor clicking the link, not an authenticated brand
 * request), so there is no workspace principal to check. It scopes only by the UTM row's own id,
 * which is unguessable (ULID) and was itself only ever created after the authorization check above.
 */
@Service
public class CampaignLinkService {

    private final UtmCampaignRepository utmCampaignRepository;
    private final CampaignRepository campaignRepository;
    private final CollaborationRepository collaborationRepository;
    private final CreatorProfileRepository creatorProfileRepository;

    public CampaignLinkService(
            UtmCampaignRepository utmCampaignRepository,
            CampaignRepository campaignRepository,
            CollaborationRepository collaborationRepository,
            CreatorProfileRepository creatorProfileRepository) {
        this.utmCampaignRepository = utmCampaignRepository;
        this.campaignRepository = campaignRepository;
        this.collaborationRepository = collaborationRepository;
        this.creatorProfileRepository = creatorProfileRepository;
    }

    /**
     * Generate (or return the existing) unique tracking URL for a creator's participation in a
     * campaign owned by {@code workspaceId}.
     *
     * @param workspaceId the calling brand's workspace — MUST actually own {@code campaignId}
     * @param campaignId the campaign to generate a link for
     * @param collaborationId the collaboration pairing the campaign with the creator
     * @param creatorProfileId the creator the link is being generated for
     * @param baseUrl the destination URL the tracking params are appended to
     * @param platform the platform the creator will post the link on (e.g. "instagram") — lower-
     *     cased into {@code utm_source}
     * @throws ApiException {@code CAMPAIGN_NOT_FOUND} (404) if {@code campaignId} does not exist or
     *     does not belong to {@code workspaceId} — deliberately not distinguished from a genuinely
     *     missing campaign, so a workspace cannot probe for the existence of other workspaces'
     *     campaigns
     * @throws ApiException {@code COLLABORATION_NOT_FOUND} (404) if {@code collaborationId} does not
     *     exist
     * @throws ApiException {@code COLLABORATION_CAMPAIGN_MISMATCH} (403) if the collaboration
     *     belongs to a different campaign than {@code campaignId}
     * @throws ApiException {@code CREATOR_NOT_FOUND} (404) if {@code creatorProfileId} does not
     *     exist
     * @throws ApiException {@code CREATOR_COLLABORATION_MISMATCH} (403) if the creator profile does
     *     not correspond to the collaboration's creator
     */
    @Transactional
    public UtmCampaign createTrackingLink(
            String workspaceId,
            String campaignId,
            String collaborationId,
            String creatorProfileId,
            String baseUrl,
            String platform) {

        // Workspace-ownership check: campaign must belong to the calling workspace. This lookup IS
        // the authorization check — resolve-then-scope, never trust the caller-supplied campaignId
        // on its own.
        Campaign campaign =
                campaignRepository
                        .findByIdAndWorkspaceId(campaignId, workspaceId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "CAMPAIGN_NOT_FOUND", "Campaign not found", HttpStatus.NOT_FOUND));

        Collaboration collaboration =
                collaborationRepository
                        .findById(collaborationId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "COLLABORATION_NOT_FOUND",
                                                "Collaboration not found",
                                                HttpStatus.NOT_FOUND));

        if (!collaboration.getCampaignId().equals(campaign.getId())) {
            throw new ApiException(
                    "COLLABORATION_CAMPAIGN_MISMATCH",
                    "This collaboration does not belong to the given campaign",
                    HttpStatus.FORBIDDEN);
        }

        CreatorProfile creator =
                creatorProfileRepository
                        .findById(creatorProfileId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "CREATOR_NOT_FOUND", "Creator not found", HttpStatus.NOT_FOUND));

        // Collaboration.creatorId references users(id); CreatorProfile.userId is the matching
        // reference — this is the join used throughout the codebase to go from a collaboration's
        // creator to their creator profile (see V6 FK style).
        if (!creator.getUserId().equals(collaboration.getCreatorId())) {
            throw new ApiException(
                    "CREATOR_COLLABORATION_MISMATCH",
                    "This creator does not match the given collaboration",
                    HttpStatus.FORBIDDEN);
        }

        return utmCampaignRepository
                .findByCampaignIdAndCreatorProfileId(campaign.getId(), creator.getId())
                .orElseGet(
                        () -> buildAndSaveTrackingLink(campaign, collaboration, creator, baseUrl, platform));
    }

    private UtmCampaign buildAndSaveTrackingLink(
            Campaign campaign,
            Collaboration collaboration,
            CreatorProfile creator,
            String baseUrl,
            String platform) {
        String utmSource = platform == null ? "" : platform.toLowerCase();
        String utmMedium = "influencer";
        String utmCampaign = SlugUtils.slugify(campaign.getTitle());
        String utmContent = SlugUtils.slugify(creator.getDisplayName());

        String fullUrl = buildTrackingUrl(baseUrl, utmSource, utmMedium, utmCampaign, utmContent);

        UtmCampaign entity =
                UtmCampaign.builder()
                        .id(Ulids.newUlid())
                        .campaignId(campaign.getId())
                        .collaborationId(collaboration.getId())
                        .creatorProfileId(creator.getId())
                        .baseUrl(baseUrl)
                        .utmSource(utmSource)
                        .utmMedium(utmMedium)
                        .utmCampaign(utmCampaign)
                        .utmContent(utmContent)
                        .fullTrackingUrl(fullUrl)
                        .build();

        return utmCampaignRepository.save(entity);
    }

    /**
     * Appends UTM query params to {@code baseUrl}, using {@code &} as the separator if {@code
     * baseUrl} already has a query string (contains {@code ?}), otherwise {@code ?}. Every param
     * value is URL-encoded.
     */
    private static String buildTrackingUrl(
            String baseUrl, String utmSource, String utmMedium, String utmCampaign, String utmContent) {
        String separator = baseUrl.contains("?") ? "&" : "?";
        return baseUrl
                + separator
                + "utm_source="
                + encode(utmSource)
                + "&utm_medium="
                + encode(utmMedium)
                + "&utm_campaign="
                + encode(utmCampaign)
                + "&utm_content="
                + encode(utmContent);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    /**
     * Record a click on a tracking link. Called by the (not-yet-built) conversion tracking
     * webhook/pixel endpoint — not workspace-scoped, see class javadoc.
     *
     * <p>Unique-visitor deduplication is intentionally simplified — every non-null {@code
     * visitorId} increments {@code uniqueVisitors}. A production implementation would deduplicate
     * (e.g. via a Redis HyperLogLog or a seen-visitor set) before incrementing; that is out of scope
     * for this pure-Java foundation slice.
     *
     * @throws ApiException {@code UTM_NOT_FOUND} (404) if {@code utmCampaignId} does not exist
     */
    @Transactional
    public void recordClick(String utmCampaignId, String visitorId) {
        UtmCampaign utm =
                utmCampaignRepository
                        .findById(utmCampaignId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "UTM_NOT_FOUND", "Tracking link not found", HttpStatus.NOT_FOUND));

        utm.incrementClickCount();

        if (visitorId != null && !visitorId.isBlank()) {
            utm.incrementUniqueVisitors();
        }

        utmCampaignRepository.save(utm);
    }
}
