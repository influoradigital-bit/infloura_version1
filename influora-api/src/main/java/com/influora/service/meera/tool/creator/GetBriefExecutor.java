package com.influora.service.meera.tool.creator;

import com.influora.common.ApiException;
import com.influora.domain.entity.CreatorBrief;
import com.influora.domain.entity.CreatorProfile;
import com.influora.repository.CollaborationRepository;
import com.influora.service.CreatorAgentPreferencesService;
import com.influora.service.CreatorBriefService;
import com.influora.web.dto.brief.BriefDtos.BriefAnalysisResponse;
import com.influora.web.dto.meera.CreatorToolDtos.GetBriefResult;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * T-MEERA-CREATOR-PHASE-B (SPEC.md &sect;3.1/&sect;3.6) — {@code get_brief}: one brief, pasted or
 * lifted from a platform deal, with the extraction, risk flags and quote the creator was shown.
 *
 * <p>This is the read half of "paste and read". {@code CreatorBriefService} already stores a pasted
 * brief and its frozen analysis; until this executor existed no chat tool could reach that row, so a
 * creator could paste a brief and Meera still could not see it.
 *
 * <p><b>Not {@code @Transactional} — the one creator executor that is not, and on purpose.</b> The
 * four siblings are {@code @Transactional(readOnly = true)} because they only read. This one can
 * call {@link CreatorBriefService#ensurePlatformBrief}, which on a deal's first read makes a blocking
 * round trip to influora-ai (5s connect + 15s request). That method and {@code paste} were split onto
 * {@code CreatorBriefWriter}'s {@code REQUIRES_NEW} boundaries precisely so no pooled JDBC connection
 * is held across that call; an annotation here would open an outer transaction first and pin a
 * connection for the whole round trip anyway, which is the API-wide availability defect that split
 * fixed. It would also break the re-read below: under MySQL's REPEATABLE READ the outer transaction's
 * snapshot is taken at its first read, so the brief {@code ensurePlatformBrief} commits afterwards is
 * invisible to it and a first-time deal read would 404 on its own brief. Every read below runs in its
 * callee's own short transaction instead. {@code GetBriefExecutorTest} fails if this class or
 * {@link #execute} gains the annotation.
 *
 * <p><b>Ownership, and why every miss is a 404.</b> A brief is read through
 * {@link CreatorBriefService#get}, whose lookup is scoped by the caller's {@code creator_profiles.id}
 * inside the query, and a deal through {@code ensurePlatformBrief}, whose collaboration lookup is
 * scoped by her {@code users.id}. On top of that, a brief that names a collaboration must name one
 * this creator is party to. Each refusal is {@code NOT_FOUND}, never {@code FORBIDDEN}: a 403 would
 * confirm to the caller that the id exists and belongs to someone else.
 *
 * <p>The quote on the result carries the creator's floor. That is correct — it is her own brief, and
 * the only route serving this result is {@code CreatorMeeraToolController}, which refuses every
 * non-CREATOR principal before an executor runs ({@code FloorBarrierTest} permits it on that basis).
 *
 * <p><b>Addition A (Priya ruling RULINGS-U-0917.md &sect;0, closing a gap F1's fix left open).</b>
 * {@code BriefStatus.NEW} is not the only way {@link CreatorBriefService#get} can hand back an
 * empty analysis: a brief {@code dismiss}ed while still NEW keeps {@code extracted_json} NULL
 * forever, and {@code CreatorBriefService#toResponse}/{@code #writeJson} turn an unparseable or
 * unserialisable snapshot into nulls ON PURPOSE (by that method's own javadoc) rather than a 500 —
 * both leave the row's {@code status} at something other than NEW while {@code extraction}/{@code
 * flags} on the response are null. The model reads a null {@code flags} exactly the way it would
 * read an empty one — "no risks" — which is the same wrong-success shape F1 fixed for NEW, just
 * reached a different way. So {@link #execute} refuses whenever {@code extraction} OR {@code
 * flags} is null, whatever the status says. An empty {@code flags} list is a real "no risk flags"
 * and passes untouched.
 */
@Service
public class GetBriefExecutor {

    /**
     * Addition A's refusal code. Deliberately distinct from {@link
     * CreatorBriefService#BRIEF_STILL_READING_CODE}: that one means "ask again shortly", this one
     * means the brief has no usable analysis to show at all (dismissed unread, or a corrupted
     * snapshot) and retrying the same read will not fix it.
     */
    public static final String BRIEF_ANALYSIS_UNAVAILABLE_CODE = "BRIEF_ANALYSIS_UNAVAILABLE";

    private final CreatorAgentPreferencesService preferencesService;
    private final CreatorBriefService creatorBriefService;
    private final CollaborationRepository collaborationRepository;

    public GetBriefExecutor(
            CreatorAgentPreferencesService preferencesService,
            CreatorBriefService creatorBriefService,
            CollaborationRepository collaborationRepository) {
        this.preferencesService = preferencesService;
        this.creatorBriefService = creatorBriefService;
        this.collaborationRepository = collaborationRepository;
    }

    /**
     * @param creatorUserId the {@code users.id} off the verified on-behalf JWT — never a body value
     * @param input exactly one of {@code brief_id} or {@code deal_id}; the names match
     *     {@code influora-ai/app/tools/creator_schemas.py}'s {@code get_brief} schema character for
     *     character, and no other key is read
     */
    public GetBriefResult execute(String creatorUserId, Map<String, Object> input) {
        CreatorProfile profile = preferencesService.requireCreatorProfile(creatorUserId);

        String briefId = ToolInput.optionalString(input, "brief_id");
        String dealId = ToolInput.optionalString(input, "deal_id");

        // SPEC.md §3.6: "exactly one of brief_id or deal_id". Both is refused rather than silently
        // preferring one -- the schema cannot express "one of" (no combinators are allowed in an
        // Anthropic input_schema), so this is the only place the rule is enforced.
        if (briefId != null && dealId != null) {
            throw new ApiException(
                    "AMBIGUOUS_BRIEF_TARGET",
                    "Pass exactly one of brief_id or deal_id, not both",
                    HttpStatus.BAD_REQUEST);
        }
        if (briefId == null && dealId == null) {
            throw new ApiException(
                    "BRIEF_TARGET_REQUIRED",
                    "Pass a brief_id or a deal_id to read",
                    HttpStatus.BAD_REQUEST);
        }

        String resolvedBriefId = briefId;
        if (dealId != null) {
            // Finds or creates the PLATFORM brief for this deal, analysing it on first read.
            // Ownership-scoped inside: another creator's deal id is DEAL_NOT_FOUND before any text
            // is lifted from it or any AI call is made.
            CreatorBrief platformBrief = creatorBriefService.ensurePlatformBrief(profile.getId(), dealId);
            resolvedBriefId = platformBrief.getId();
        }

        // Always re-read from the stored snapshot, on both paths, so a deal's brief and a pasted
        // brief reach the model through the one reader the creator's own brief page uses.
        BriefAnalysisResponse brief = creatorBriefService.get(creatorUserId, resolvedBriefId);

        requireCollaborationIsHers(brief.dealId(), creatorUserId);
        requireReadableAnalysis(brief);

        return new GetBriefResult(
                brief.briefId(),
                brief.source(),
                brief.status(),
                brief.dealId(),
                brief.extraction(),
                brief.flags(),
                brief.quote(),
                brief.extractionSource());
    }

    /**
     * SPEC.md &sect;3.6: "A brief for a collaboration whose {@code creatorId != creatorUserId} → 404."
     *
     * <p>The brief row is already hers (its lookup is profile-scoped), so this guards the other key on
     * it: {@code collaboration_id} is not a foreign key ({@code CreatorBrief}'s javadoc), and until
     * this change {@code ensurePlatformBrief} looked the collaboration up by id alone — so a brief row
     * owned by her is not, by itself, proof that the deal it describes is hers. The refusal is
     * {@code BRIEF_NOT_FOUND}, the same code a brief id that never existed gets.
     *
     * <p><b>A collaboration that no longer exists does not refuse the brief.</b> The same javadoc
     * says why the column is not an FK: the brief is her own record of what she was sent and must
     * survive a brand's collaboration row being cleaned up. A deleted deal has no other creator to
     * leak to, so only a collaboration that exists AND names someone else is refused — which is the
     * rule exactly as &sect;3.6 states it, rather than the stricter "must still be findable as hers".
     */
    private void requireCollaborationIsHers(String collaborationId, String creatorUserId) {
        if (collaborationId == null) {
            return;
        }
        boolean someoneElses =
                collaborationRepository
                        .findById(collaborationId)
                        .map(collaboration -> !creatorUserId.equals(collaboration.getCreatorId()))
                        .orElse(false);
        if (someoneElses) {
            throw new ApiException("BRIEF_NOT_FOUND", "Brief not found", HttpStatus.NOT_FOUND);
        }
    }

    /**
     * Addition A (Priya ruling RULINGS-U-0917.md &sect;0) — see the class javadoc. Runs AFTER
     * ownership is cleared, so a null analysis on someone else's brief still 404s rather than
     * leaking a more specific refusal. An empty {@code flags} list is a real, valid "no flags" and
     * must pass here untouched -- only {@code null} on either field refuses.
     */
    private void requireReadableAnalysis(BriefAnalysisResponse brief) {
        if (brief.extraction() == null || brief.flags() == null) {
            throw new ApiException(
                    BRIEF_ANALYSIS_UNAVAILABLE_CODE,
                    "This brief has no readable analysis",
                    HttpStatus.CONFLICT);
        }
    }
}
