package com.influora.service.meera.tool.creator;

import com.influora.common.Rendered;
import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.DealMessage;
import com.influora.domain.entity.Deliverable;
import com.influora.domain.entity.Workspace;
import com.influora.domain.enums.CollaborationStatus;
import com.influora.domain.enums.CreatorDealStatuses;
import com.influora.domain.enums.DealSenderType;
import com.influora.domain.enums.EscrowStatus;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.CreatorBriefRepository;
import com.influora.repository.DealMessageRepository;
import com.influora.repository.DeliverableRepository;
import com.influora.repository.EscrowHoldRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.service.CreatorAgentPreferencesService;
import com.influora.service.DealService;
import com.influora.web.dto.creator.CreatorAgentDtos.PreferencesResponse;
import com.influora.web.dto.meera.CreatorToolDtos.DealSummary;
import com.influora.web.dto.meera.CreatorToolDtos.GetMyDealsResult;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * T-MEERA-CREATOR-PHASE-B (SPEC.md &sect;3.1/&sect;3.6) — {@code get_my_deals}: the creator's active
 * and recent deals with status, amounts and the one next action each is waiting on.
 *
 * <p>Every string a model will read is rendered here, in the creator's locale, via
 * {@link Rendered} — Python never formats a number (SPEC.md &sect;0.4). Numeric siblings suffixed
 * {@code _value} carry the same figures for the frontend.
 */
@Service
public class GetMyDealsExecutor {

    /** SPEC.md &sect;3.6 — {@code limit} defaults to 10 and is capped at 25. */
    private static final int DEFAULT_LIMIT = 10;

    private static final int MAX_LIMIT = 25;

    private static final String STATUS_ACTIVE = "active";
    private static final String STATUS_COMPLETED = "completed";
    private static final String STATUS_ALL = "all";

    private final CreatorAgentPreferencesService preferencesService;
    private final CollaborationRepository collaborationRepository;
    private final CampaignRepository campaignRepository;
    private final WorkspaceRepository workspaceRepository;
    private final DealMessageRepository dealMessageRepository;
    private final DeliverableRepository deliverableRepository;
    private final EscrowHoldRepository escrowHoldRepository;
    private final CreatorBriefRepository creatorBriefRepository;

    public GetMyDealsExecutor(
            CreatorAgentPreferencesService preferencesService,
            CollaborationRepository collaborationRepository,
            CampaignRepository campaignRepository,
            WorkspaceRepository workspaceRepository,
            DealMessageRepository dealMessageRepository,
            DeliverableRepository deliverableRepository,
            EscrowHoldRepository escrowHoldRepository,
            CreatorBriefRepository creatorBriefRepository) {
        this.preferencesService = preferencesService;
        this.collaborationRepository = collaborationRepository;
        this.campaignRepository = campaignRepository;
        this.workspaceRepository = workspaceRepository;
        this.dealMessageRepository = dealMessageRepository;
        this.deliverableRepository = deliverableRepository;
        this.escrowHoldRepository = escrowHoldRepository;
        this.creatorBriefRepository = creatorBriefRepository;
    }

    /**
     * @param creatorUserId the {@code users.id} off the verified on-behalf JWT — never a body value
     * @param input raw model-proposed tool input; {@code status} ({@code active|completed|all},
     *     default {@code active}) and {@code limit} (default 10, max 25) are the only keys read
     */
    @Transactional(readOnly = true)
    public GetMyDealsResult execute(String creatorUserId, Map<String, Object> input) {
        CreatorProfile profile = preferencesService.requireCreatorProfile(creatorUserId);
        PreferencesResponse prefs = preferencesService.getOrCreatePreferences(creatorUserId);
        Locale locale = localeFor(prefs);

        String statusFilter = normaliseStatus(ToolInput.optionalString(input, "status"));
        int limit = ToolInput.intOr(input, "limit", DEFAULT_LIMIT, 1, MAX_LIMIT);

        // Keyed on users.id — Collaboration.creatorId is a user id, not a creator_profiles.id.
        List<Collaboration> all = collaborationRepository.findByCreatorId(creatorUserId);

        // Counted over EVERY collaboration, before the status filter and before the limit: these
        // two numbers answer "how many deals do I have", which must not change because the model
        // asked for a page of ten. Same definitions the Meera creator context block uses, via the
        // shared CreatorDealStatuses constant, so the two surfaces cannot drift apart.
        int activeCount =
                (int) all.stream().filter(c -> CreatorDealStatuses.ACTIVE.contains(c.getStatus())).count();
        int completedCount =
                (int) all.stream().filter(c -> c.getStatus() == CollaborationStatus.COMPLETED).count();

        List<DealSummary> deals =
                all.stream()
                        .filter(c -> matchesFilter(c, statusFilter))
                        .sorted(
                                Comparator.comparing(
                                                Collaboration::getUpdatedAt,
                                                Comparator.nullsLast(Comparator.naturalOrder()))
                                        .reversed())
                        .limit(limit)
                        .map(c -> toSummary(c, creatorUserId, profile.getId(), locale))
                        .toList();

        return new GetMyDealsResult(deals, activeCount, completedCount);
    }

    private DealSummary toSummary(
            Collaboration collaboration, String creatorUserId, String creatorProfileId, Locale locale) {
        Campaign campaign = campaignRepository.findById(collaboration.getCampaignId()).orElse(null);

        // Brand name via the campaign's workspace — the same lookup DealService#resolveCounterparty
        // performs for a CREATOR viewer (it reads Workspace.getName(); brandProfileRepository is not
        // involved on this branch). Copied rather than called: resolveCounterparty is private, and
        // it returns a five-field Counterparty this payload has no use for.
        //
        // Divergence, deliberate: where resolveCounterparty substitutes the literals "Brand" and
        // "Campaign" for a missing row, this returns null. Those placeholders are fine in a UI that
        // shows them in a greyed slot; handed to a language model they become facts, and Meera says
        // "your deal with Brand" out loud. NON_NULL drops the field instead, and the model says
        // nothing about a name it does not have.
        String brandName = null;
        String campaignWorkspaceId = campaign != null ? campaign.getWorkspaceId() : null;
        if (campaignWorkspaceId != null) {
            Workspace workspace = workspaceRepository.findById(campaignWorkspaceId).orElse(null);
            brandName = workspace != null ? workspace.getName() : null;
        }

        List<DealMessage> messages =
                dealMessageRepository.findByCollaborationIdOrderByCreatedAtAsc(collaboration.getId());
        int unreadCount = DealService.unreadCountFor(messages, creatorUserId);
        DealSenderType lastSender =
                messages.isEmpty() ? null : messages.get(messages.size() - 1).getSenderType();

        // SECURED — the milestone-aware existence check (CR-49/CR-50), NOT
        // findByCollaborationIdAndStatus. That derived finder reads the direct collaboration_id
        // column, which is NULL on every ordinary brand-funded hold, so it reports secured=false on
        // a genuinely funded deal. This is the single most consequential line in this class: a
        // creator told her money is not secured will not start work.
        //
        // Collaboration-wide, not the contract-scoped hasEscrowForContract ternary DealService uses
        // for the deal room's escrowFunded flag. A creator asking Meera "is this deal secured?" is
        // asking whether money is held for the deal, not whether the current contract VERSION's
        // payment plan is funded. The difference only shows after a contract amendment, where a
        // still-FUNDED hold stays bound to the superseded version's milestone: that money is real
        // and unrefunded, so answering "yes" is correct for this question even though F-0656 made
        // the deal room answer "no" for its own, narrower one.
        boolean secured =
                escrowHoldRepository.hasEscrowForCollaboration(
                        collaboration.getId(), Set.of(EscrowStatus.FUNDED));

        List<Deliverable> deliverables =
                deliverableRepository.findByCollaborationIdOrderBySlotIndexAsc(collaboration.getId());
        LocalDate nextDeadline = DealService.nextDeadlineFor(deliverables);

        String briefId =
                creatorBriefRepository
                        .findFirstByCollaborationIdAndCreatorProfileId(
                                collaboration.getId(), creatorProfileId)
                        .map(brief -> brief.getId())
                        .orElse(null);

        return new DealSummary(
                collaboration.getId(),
                brandName,
                campaign != null ? campaign.getTitle() : null,
                collaboration.getStatus().name(),
                statusLabel(collaboration.getStatus()),
                Rendered.money(collaboration.getAgreedRate(), locale),
                collaboration.getAgreedRate(),
                collaboration.getCurrency(),
                nextAction(collaboration.getStatus(), lastSender, secured, nextDeadline, locale),
                Rendered.date(nextDeadline, locale),
                secured,
                unreadCount,
                hasPendingOffer(collaboration.getStatus(), lastSender),
                briefId);
    }

    /**
     * SPEC.md &sect;3.6 — the one thing this deal is waiting on, as a phrase Meera can say verbatim.
     *
     * <p>{@code CONTRACTED} splits on {@code secured} because those are opposite instructions to the
     * creator: with funds held she should start work, without them she should wait and not shoot.
     */
    private static String nextAction(
            CollaborationStatus status,
            DealSenderType lastSender,
            boolean secured,
            LocalDate nextDeadline,
            Locale locale) {
        return switch (status) {
            case INVITED, APPLIED -> "accept or counter";
            case IN_NEGOTIATION ->
                    lastSender == DealSenderType.creator ? "waiting for brand" : "reply to brand";
            case CONTRACT_PENDING -> "sign the contract";
            case CONTRACTED -> secured ? "funds secured, start work" : "waiting for brand to secure funds";
            case IN_PROGRESS ->
                    nextDeadline == null ? "deliver the work" : "deliver by " + Rendered.date(nextDeadline, locale);
            case REVIEW_PENDING -> "waiting for brand review";
            case REVISION_REQUESTED -> "revise and resubmit";
            case DISPUTED -> "in dispute, Meera is in draft-only mode";
            // SHORTLISTED, TERMS_AGREED, COMPLETED and CANCELLED have no action the creator owes.
            // Null, not a filler sentence: NON_NULL drops the key and the model says nothing, which
            // is the honest answer to "what should I do about a completed deal".
            default -> null;
        };
    }

    /**
     * Whether the brand has something on the table that the creator has not answered.
     *
     * <p><b>Derived from the collaboration's own state, not from {@code deal_offer_history}.</b>
     * That table exists (SPEC.md &sect;2.6) but nothing writes to it before the Phase-B negotiation
     * surface lands, so reading it here would make this field permanently false while looking
     * authoritative. The two conditions below are what "a pending offer" means to a creator today:
     * a brand-initiated invite she has not responded to, or a negotiation whose last word was the
     * brand's.
     *
     * <p>{@code APPLIED} is excluded on purpose even though {@link #nextAction} says "accept or
     * counter" for it — the creator applied, so there is no brand offer outstanding.
     */
    private static boolean hasPendingOffer(CollaborationStatus status, DealSenderType lastSender) {
        if (status == CollaborationStatus.INVITED) {
            return true;
        }
        return status == CollaborationStatus.IN_NEGOTIATION && lastSender == DealSenderType.brand;
    }

    /** Sentence-case labels; the raw enum name travels alongside in {@code status}. */
    private static String statusLabel(CollaborationStatus status) {
        return switch (status) {
            case INVITED -> "Invited";
            case APPLIED -> "Applied";
            case SHORTLISTED -> "Shortlisted";
            case IN_NEGOTIATION -> "In negotiation";
            case TERMS_AGREED -> "Terms agreed";
            case CONTRACT_PENDING -> "Contract pending";
            case CONTRACTED -> "Contracted";
            case IN_PROGRESS -> "In progress";
            case REVIEW_PENDING -> "In review";
            case REVISION_REQUESTED -> "Revision requested";
            case COMPLETED -> "Completed";
            case CANCELLED -> "Cancelled";
            case DISPUTED -> "In dispute";
        };
    }

    private static boolean matchesFilter(Collaboration collaboration, String statusFilter) {
        return switch (statusFilter) {
            case STATUS_COMPLETED -> collaboration.getStatus() == CollaborationStatus.COMPLETED;
            case STATUS_ALL -> true;
            default -> CreatorDealStatuses.ACTIVE.contains(collaboration.getStatus());
        };
    }

    /** An unrecognised {@code status} degrades to {@code active}, never to an error. */
    private static String normaliseStatus(String raw) {
        if (raw == null) {
            return STATUS_ACTIVE;
        }
        String lower = raw.toLowerCase(Locale.ROOT);
        return switch (lower) {
            case STATUS_COMPLETED, STATUS_ALL -> lower;
            default -> STATUS_ACTIVE;
        };
    }

    private static Locale localeFor(PreferencesResponse prefs) {
        String tag = prefs == null ? null : prefs.creatorLanguage();
        return tag == null || tag.isBlank() ? Rendered.DEFAULT_LOCALE : Locale.forLanguageTag(tag);
    }
}
