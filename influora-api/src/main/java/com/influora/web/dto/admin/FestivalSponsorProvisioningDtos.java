package com.influora.web.dto.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Wire types for {@code POST /admin/festival-enquiries/{id}/provision} and the returning-sponsor
 * counterpart, {@code POST /admin/festival-enquiries/{id}/link-existing} (T-FESTIVALBOX-0905
 * phase 2 and phase 8), backing {@link com.influora.service.admin.FestivalSponsorProvisioningService}.
 *
 * <p>Raw DTOs, no {@code ApiResponse} envelope — same convention as every other
 * {@code Admin*Controller} response (see {@code AdminFestivalEnquiryDtos}).
 */
public final class FestivalSponsorProvisioningDtos {

    private FestivalSponsorProvisioningDtos() {}

    /**
     * @param enquiryId the {@code festival_enquiries} row that was provisioned.
     * @param userId the new BRAND user created for the enquiry's contact. Has no usable
     *     password yet — the brand must follow the emailed password-set link.
     * @param workspaceId the new brand workspace.
     * @param campaignId the new {@code DRAFT} campaign created under that workspace.
     * @param provisionedAt ISO-8601 instant the provisioning transaction committed.
     */
    public record ProvisionFestivalSponsorResponse(
            String enquiryId,
            String userId,
            String workspaceId,
            String campaignId,
            String provisionedAt) {}

    /**
     * Body for {@code POST /admin/festival-enquiries/{id}/link-existing} (phase 8) — the admin has
     * already resolved (e.g. via {@link ExistingSponsorAccountResponse}) which existing workspace
     * this returning sponsor should be linked to and supplies it explicitly.
     *
     * <p>[Kabir H-2] This id is a CHOICE, not an assertion. The service verifies server-side that
     * the enquiry's own email resolves to an active member of the named workspace before writing
     * anything, and rejects it with {@code 400 WORKSPACE_NOT_LINKED_TO_ENQUIRY} otherwise. The
     * {@code @Size(max = 26)} below is a ULID length bound and not a security control — it says
     * nothing about whether the workspace has anything to do with this enquiry. See {@code
     * FestivalSponsorProvisioningService#linkExisting}.
     */
    public record LinkExistingSponsorRequest(
            @NotBlank(message = "workspaceId is required") @Size(max = 26) String workspaceId) {}

    /**
     * Response for {@code POST /admin/festival-enquiries/{id}/link-existing} (phase 8) — deliberately
     * a distinct shape from {@link ProvisionFestivalSponsorResponse} rather than reusing it with a
     * null {@code userId}: {@code linkedExisting} lets the admin UI render "linked to an existing
     * sponsor" copy instead of "new sponsor account created" without inferring it from a missing
     * field, and there genuinely is no {@code userId} here (no user was created) so a shared shape
     * would need a null field anyway.
     *
     * @param enquiryId the {@code festival_enquiries} row that was linked.
     * @param workspaceId the pre-existing brand workspace this enquiry was linked to.
     * @param campaignId the new {@code DRAFT} campaign created under that workspace for this
     *     edition.
     * @param provisionedAt ISO-8601 instant the link transaction committed.
     * @param linkedExisting always {@code true} — present so a client that happens to handle both
     *     endpoints' responses through one code path can branch on this instead of on shape.
     */
    public record LinkExistingSponsorResponse(
            String enquiryId,
            String workspaceId,
            String campaignId,
            String provisionedAt,
            boolean linkedExisting) {}

    /**
     * Response for the companion read that makes a {@code 409 EMAIL_ALREADY_REGISTERED} refusal
     * from {@code provision} actionable — {@code GET
     * /admin/festival-enquiries/{id}/existing-account}. Tells the admin console which existing
     * workspace the enquiry's email already belongs to, so it can offer "link to the existing
     * workspace instead" and pass that {@code workspaceId} straight into {@link
     * LinkExistingSponsorRequest}.
     *
     * <p>[Kabir M-5] This used to also return the workspace's {@code metaPixelId}, described as "a
     * natural corroborating signal that this is genuinely the same sponsor coming back, not just an
     * email collision". It corroborates nothing. The admin has no prior knowledge of that number to
     * compare it against, so seeing it cannot tell them whether the match is real — while the case
     * the endpoint exists to handle is precisely the one where the match might be a COLLISION, i.e.
     * exactly when the workspace belongs to somebody else. A brand's Meta Pixel id is their own
     * advertising identifier; it should not be handed out on the strength of an unverified email
     * match, to answer a question it cannot answer. {@code hasMetaPixelId} keeps the only part that
     * was ever decision-useful — "this workspace is set up for pixel tracking already", a signal
     * about maturity, not an identifier.
     *
     * @param userId the existing user id that owns the email (never a new/created id).
     * @param workspaceId the existing user's oldest active workspace membership's workspace.
     * @param workspaceName the workspace's display name, so the admin console can show it without
     *     a second round-trip.
     * @param hasMetaPixelId whether that workspace already has a Meta Pixel id configured. A
     *     boolean on purpose — see the note above. The value itself is available to an admin who
     *     legitimately needs it through the brand-detail surface ({@code MetaPixelSection}), which
     *     is scoped to one brand the admin has deliberately opened rather than returned as a side
     *     effect of an email lookup.
     */
    public record ExistingSponsorAccountResponse(
            String userId, String workspaceId, String workspaceName, boolean hasMetaPixelId) {}
}
