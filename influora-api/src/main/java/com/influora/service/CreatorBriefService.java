package com.influora.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.influora.common.ApiException;
import com.influora.config.CreatorSuggestionAiProperties;
import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.CreatorBrief;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.DealMessage;
import com.influora.domain.enums.BriefSource;
import com.influora.domain.enums.BriefStatus;
import com.influora.domain.enums.DealMessageKind;
import com.influora.integration.ai.MeeraBriefAiClient;
import com.influora.integration.ai.MeeraBriefAiClient.BriefResult;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.CreatorBriefRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.DealMessageRepository;
import com.influora.service.brief.BriefFallbackExtractor;
import com.influora.service.brief.CreatorBriefWriter;
import com.influora.service.rates.RateQuoteService;
import com.influora.service.risk.DealRiskService;
import com.influora.web.dto.brief.BriefDtos.BriefAnalysisResponse;
import com.influora.web.dto.brief.BriefDtos.BriefExtraction;
import com.influora.web.dto.brief.BriefDtos.BriefListItem;
import com.influora.web.dto.creator.CreatorAgentDtos.PreferencesResponse;
import com.influora.web.dto.meera.CreatorToolDtos.PackageQuote;
import com.influora.web.dto.meera.CreatorToolDtos.RiskFlag;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * T-MEERA-CREATOR-PHASE-B (SPEC.md &sect;3.8), B0-42 — paste and read. The capability the whole
 * feature is named for.
 *
 * <p><b>Phase B0 half only.</b> No {@code createSecureLink}, no secure-link listing or revocation.
 * Those are Phase B1, they depend on the {@code creator_secure_links} migration this phase does not
 * ship, and shipping them here would create a brand-visible package payload before the floor strip
 * that has to guard it exists.
 *
 * <p><b>THE ORDER OF STEPS 2 AND 3 IS THE POINT OF THIS CLASS.</b> The raw text is saved BEFORE the
 * AI client is called. A creator pastes a brief once, from a DM she may well then lose; an extraction
 * that fails after the text was only held in a local variable loses her paste along with it. Saving
 * first means {@link BriefFallbackExtractor} always has something to read, a retry is possible, and
 * the worst outcome of an outage is a rule-based summary rather than a lost brief. There is a test
 * that breaks this order deliberately and watches the guarantee fail.
 *
 * <p><b>Ordering alone was not enough, and {@link #paste} is no longer {@code @Transactional}.</b>
 * Saving first inside ONE transaction made the guarantee above look true while being false: the first
 * save was session-local, so a throw from the risk or the quote step — both of which run after it —
 * rolled the paste back too. The raw-text write therefore commits in its own transaction, through
 * {@link CreatorBriefWriter}, a separate bean because self-invocation does not cross the Spring proxy
 * and a {@code @Transactional} private or self-called method silently runs in the caller's
 * transaction. The same split is what keeps the blocking AI round trip OUT of a transaction: it used
 * to hold a pooled JDBC connection for up to 20 seconds (5s connect + 15s request), ten times per
 * rate-limit window per creator, out of a pool shared with every unrelated endpoint — so a slow
 * provider was an API-wide availability incident rather than a degraded reading. Those two timeouts
 * are now set explicitly in {@code application.yml} instead of resting on Java field defaults.
 *
 * <p><b>A degraded analysis is labelled, not disguised.</b> {@code extraction_source} records which
 * extractor ran, and {@code degraded_reason} records why the AI one did not — {@code "cap"} when the
 * creator's own monthly brief allowance is spent, {@code "ai_unavailable"} otherwise. influora-ai
 * returns the same HTTP 200 shape for both (SPEC.md &sect;14.4.a), so
 * {@link MeeraBriefAiClient.BriefResult} is what tells them apart and this service is what carries
 * the distinction to the screen. Collapsing them would tell a creator who has simply used her
 * allowance that Influora is broken.
 *
 * <p><b>The three snapshot columns are frozen on purpose.</b> {@code extracted_json},
 * {@code risk_flags_json} and {@code quote_json} are written together in one
 * {@link CreatorBrief#applyAnalysis} call and are never recomputed on read. The rate model, the risk
 * rules and the creator's own floors all move; a brief she read last week must keep showing what she
 * was actually shown.
 *
 * <p><b>Info barrier (SPEC.md &sect;0.3).</b> Everything this service returns is the creator's own
 * data and {@code quote} legitimately carries her floor. Nothing here is brand-readable, and no
 * method on this class may be called from a brand-principal path.
 */
@Service
public class CreatorBriefService {

    private static final Logger log = LoggerFactory.getLogger(CreatorBriefService.class);

    /** SPEC.md &sect;3.8 — {@code GET /creator/briefs?limit=20}, and a ceiling a caller cannot raise. */
    public static final int DEFAULT_LIST_LIMIT = 20;

    public static final int MAX_LIST_LIMIT = 100;

    /**
     * F1 HIGH (Kavya, last-call review of Wave U item U-1) — the code {@link #get} refuses with
     * when a brief is NEW and still inside {@link #analysisBudget()}. Not a generic "not ready"
     * code: the message is worded for the model to relay, because the first analysis attempt
     * (this thread's own, or another request's) is plausibly still in flight.
     */
    public static final String BRIEF_STILL_READING_CODE = "BRIEF_STILL_READING";

    /**
     * Slack added on top of the configured AI-client timeouts before a NEW brief is treated as
     * abandoned rather than in-flight. The timeouts alone are the provider's own ceiling
     * ({@link MeeraBriefAiClient} enforces them exactly); this adds room for scheduling jitter
     * between the raw-text commit and the AI call actually starting, so a brief is not declared
     * dead at the exact instant the provider would still have delivered it.
     *
     * <p>Priya ruling RULINGS-U-0917.md Addition B: with the default 5s connect + 15s request
     * (application.yml {@code influora.creator-copilot-ai}), this makes {@link #analysisBudget()}
     * 30s. influora-ai's {@code get_brief} read timeout (a separate, named Python setting —
     * {@code app/config.py}'s {@code ProviderTimeouts.get_brief_read}) must clear that whole 30s
     * plus its own margin; it is 40s by default for exactly that reason. Tightening this constant
     * or the two application.yml timeouts without also revisiting influora-ai's setting can leave
     * the Python side timing out BEFORE Spring's own budget expires.
     */
    static final long STILL_READING_SLACK_SECONDS = 10;

    private final CreatorBriefRepository briefRepository;

    /**
     * The two write boundaries of the analysis flow. See the class javadoc, and
     * {@link CreatorBriefWriter}'s own, for why the saves live on a different bean than the reads.
     */
    private final CreatorBriefWriter briefWriter;

    private final CreatorAgentPreferencesService preferencesService;
    private final MeeraBriefAiClient briefAiClient;
    private final BriefFallbackExtractor fallbackExtractor;
    private final DealRiskService dealRiskService;
    private final RateQuoteService rateQuoteService;
    private final CollaborationRepository collaborationRepository;
    private final CampaignRepository campaignRepository;
    private final DealMessageRepository dealMessageRepository;
    private final CreatorProfileRepository creatorProfileRepository;
    private final ObjectMapper objectMapper;

    /**
     * F1 HIGH fix — the same connect/request timeouts {@link MeeraBriefAiClient} enforces on the
     * AI round trip, reused (not duplicated) so {@link #analysisBudget()} tracks them if they are
     * ever tuned down during an incident. See that class's javadoc for why this bean, not a new
     * properties class, is reused a third time.
     */
    private final CreatorSuggestionAiProperties aiProperties;

    public CreatorBriefService(
            CreatorBriefRepository briefRepository,
            CreatorBriefWriter briefWriter,
            CreatorAgentPreferencesService preferencesService,
            MeeraBriefAiClient briefAiClient,
            BriefFallbackExtractor fallbackExtractor,
            DealRiskService dealRiskService,
            RateQuoteService rateQuoteService,
            CollaborationRepository collaborationRepository,
            CampaignRepository campaignRepository,
            DealMessageRepository dealMessageRepository,
            CreatorProfileRepository creatorProfileRepository,
            ObjectMapper objectMapper,
            CreatorSuggestionAiProperties aiProperties) {
        this.briefRepository = briefRepository;
        this.briefWriter = briefWriter;
        this.preferencesService = preferencesService;
        this.briefAiClient = briefAiClient;
        this.fallbackExtractor = fallbackExtractor;
        this.dealRiskService = dealRiskService;
        this.rateQuoteService = rateQuoteService;
        this.collaborationRepository = collaborationRepository;
        this.campaignRepository = campaignRepository;
        this.dealMessageRepository = dealMessageRepository;
        this.creatorProfileRepository = creatorProfileRepository;
        this.objectMapper = objectMapper;
        this.aiProperties = aiProperties;
    }

    /**
     * SPEC.md &sect;3.8 — the paste path. {@code creatorUserId} is a {@code users.id}; the brief is
     * stored against the resolved {@code creator_profiles.id}.
     *
     * <p>Step order, and none of it is interchangeable:
     *
     * <ol>
     *   <li>Resolve the profile and preferences (the creator's language, and the floors the quote and
     *       the risk rules run against).
     *   <li><b>Persist the raw text, and COMMIT it.</b> Status NEW, no extraction yet.
     *   <li>Ask influora-ai to read it. On any refusal, fall back to the deterministic extractor and
     *       record why.
     *   <li>Risk flags, from the creator's own exclusions, blocked brands and floors.
     *   <li>The priced package, from her rate card and floors.
     *   <li>Write all five values back as one snapshot and move to ANALYZED.
     * </ol>
     *
     * <p><b>Not {@code @Transactional}, on purpose</b> — steps 2 and 6 carry their own, and steps 3
     * to 5 must not hold a pooled connection across a blocking provider call. See the class javadoc.
     */
    public BriefAnalysisResponse paste(String creatorUserId, String rawText) {
        CreatorProfile profile = preferencesService.requireCreatorProfile(creatorUserId);
        PreferencesResponse prefs = preferencesService.getOrCreatePreferences(creatorUserId);

        // STEP 2 — BEFORE the AI call, and in its OWN COMMITTED transaction. See the class javadoc:
        // the order alone made an outage a lost paste anyway, because a rollback later in the same
        // transaction took this write with it.
        CreatorBrief brief = briefWriter.saveRawPaste(profile.getId(), rawText);

        return analyse(brief, profile, prefs);
    }

    /**
     * SPEC.md &sect;3.8 — the platform path: a brief lifted from a collaboration that already exists
     * on Influora, so Meera can reason about an on-platform deal with the same machinery she uses for
     * a pasted one.
     *
     * <p><b>Idempotent per collaboration.</b> An existing PLATFORM brief is returned untouched rather
     * than re-analysed. That is not only a cost decision: re-running would overwrite the frozen
     * snapshot with today's rate model, so a creator who reopens a deal would silently see different
     * numbers from the ones she was shown when she decided. A PASTED brief on the same collaboration
     * is deliberately NOT reused (see the repository method's javadoc) — her own pasted revision and
     * the platform reading of the deal are different documents.
     *
     * <p>Takes a {@code creator_profiles.id}, not a user id: its callers (the {@code get_brief} tool
     * executor, a deal page) already hold the profile id and have no user id.
     *
     * <p><b>Not {@code @Transactional}, for the same reason as {@link #paste}</b> — it shares
     * {@link #analyse}, so the same blocking AI call sits in the middle of it. Its two writes go
     * through {@link CreatorBriefWriter}, whose {@code REQUIRES_NEW} boundary also means a
     * transactional caller (a tool executor) cannot silently absorb the raw-text commit.
     */
    public CreatorBrief ensurePlatformBrief(String creatorProfileId, String collaborationId) {
        Optional<CreatorBrief> existing =
                briefRepository.findFirstByCollaborationIdAndCreatorProfileIdAndSource(
                        collaborationId, creatorProfileId, BriefSource.PLATFORM);
        if (existing.isPresent()) {
            return existing.get();
        }

        // OWNERSHIP-SCOPED, and resolved before anything is read off the deal. This used to be
        // findById(collaborationId) alone: any creator could name another creator's deal id and get
        // back a brief built from that deal's campaign text, offer message and agreed rate, stored
        // under her own profile. It was unreachable while this method had no caller; the get_brief
        // tool is its first. Collaboration.creatorId is a users.id, hence profile.getUserId(). A
        // foreign id is DEAL_NOT_FOUND, the same as one that does not exist, so it cannot be probed.
        CreatorProfile profile = requireProfileById(creatorProfileId);
        Collaboration collaboration =
                collaborationRepository
                        .findByIdAndCreatorId(collaborationId, profile.getUserId())
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "DEAL_NOT_FOUND", "Deal not found", HttpStatus.NOT_FOUND));
        PreferencesResponse prefs = preferencesService.getByProfileId(creatorProfileId);

        Campaign campaign =
                collaboration.getCampaignId() == null
                        ? null
                        : campaignRepository.findById(collaboration.getCampaignId()).orElse(null);

        CreatorBrief brief =
                briefWriter.saveRawPlatform(
                        creatorProfileId, collaborationId, platformRawText(collaboration, campaign));

        analyse(brief, profile, prefs);
        return brief;
    }

    /**
     * SPEC.md &sect;3.8 — one brief the creator already has, re-read from its frozen snapshot.
     *
     * <p><b>Not {@code @Transactional} — F1 HIGH fix (Kavya, last-call review of Wave U item
     * U-1).</b> This used to be {@code @Transactional(readOnly = true)}, which was safe only
     * because it never called {@link #analyse}. It now can (see {@link #readOrReanalyse}), and
     * {@code analyse}'s first statement is the same blocking AI round trip {@link #paste} and
     * {@link #ensurePlatformBrief} keep out of a transaction for exactly this reason: an outer
     * transaction here would pin a pooled JDBC connection for the round trip, and under MySQL's
     * REPEATABLE READ its snapshot — taken at THIS method's own read below — would still be unable
     * to see the row {@link CreatorBriefWriter#saveAnalysis} commits afterwards on a fresh
     * connection. The plain repository read in {@link #requireOwnedBrief} still runs inside Spring
     * Data's own short transaction, same as every non-transactional method in this class.
     */
    public BriefAnalysisResponse get(String creatorUserId, String briefId) {
        CreatorProfile profile = preferencesService.requireCreatorProfile(creatorUserId);
        CreatorBrief brief = requireOwnedBrief(profile.getId(), briefId);
        return readOrReanalyse(brief, profile);
    }

    /**
     * F1 HIGH (Kavya, last-call review of Wave U item U-1) — a brief stuck in {@link
     * BriefStatus#NEW} is NOT a successful read. Before this fix, {@link #ensurePlatformBrief}'s
     * idempotent early-return and this method both handed back a raw, unanalysed row — no
     * extraction, no flags, no quote — as a plain SUCCESS whenever the FIRST analysis attempt
     * committed the raw text (see {@link CreatorBriefWriter#saveRawPaste}/{@code saveRawPlatform})
     * and then died before {@link #analyse} reached {@link CreatorBriefWriter#saveAnalysis} — an AI
     * round trip up to {@code connectTimeoutSeconds + requestTimeoutSeconds} away
     * (application.yml {@code influora.creator-copilot-ai}, 5+15s by default). influora-ai's own
     * Spring-read timeout used to be shorter than that, so its retry of the exact same logical read
     * would land on this method mid-flight and get back the untouched NEW row as if it were clean —
     * a creator could be told a deal brief was fine when it was never actually read.
     *
     * <p>Two cases, split on {@link #isWithinAnalysisBudget}:
     *
     * <ul>
     *   <li><b>NEW and young</b> — the first attempt (this thread's own {@link #paste} or {@link
     *       #ensurePlatformBrief} call, or another request's) is plausibly still running. Refuse
     *       with {@link #BRIEF_STILL_READING_CODE} (409) rather than claim a clean read.
     *   <li><b>NEW and stale</b> — the budget has passed, so the first attempt is not "still
     *       running" by any honest accounting; it died (a throw between the raw-text commit and
     *       {@code saveAnalysis}, or a process restart mid-call). Re-run {@link #analyse} now and
     *       return whatever it produces — AI extraction if the provider answers, a labelled
     *       fallback if it does not, but never another silent NEW.
     * </ul>
     *
     * <p><b>Residuals Priya accepted for B0 (RULINGS-U-0917.md &sect;0), left open by design and
     * not closed here:</b>
     *
     * <ol>
     *   <li>Two stale reads at the same moment can both observe "stale" and both run {@link
     *       #analyse} — double AI spend, last {@code saveAnalysis} write wins. Bounded by the
     *       creator's own monthly brief allowance (SPEC.md &sect;7.5).
     *   <li>Two concurrent FIRST reads of a deal (both missing the {@link #ensurePlatformBrief}
     *       {@code findFirst} lookup before either has saved) can create two PLATFORM rows for the
     *       same collaboration — nothing in the schema forbids it ({@code CreatorBriefRepository}'s
     *       finder takes no ordering, and the migration has only a PK and one non-unique index).
     *       The second row heals once analysed; this is not a wrong-success path, only a duplicate.
     *   <li>A brief whose analysis throws on every attempt pays one AI call per read until the
     *       creator's monthly cap switches her to the deterministic fallback extractor. Same bound
     *       as residual 1.
     *   <li>{@code GET /creator/briefs/{id}} (this method) now inherits both the re-analysis and
     *       the 409 — nothing under {@code src/} calls it today, so this is dormant, not live.
     * </ol>
     *
     * <p>Closing residuals 1 and 3 needs no migration — {@code updated_at} already exists and is
     * mapped ({@link CreatorBrief#getUpdatedAt()}) — a conditional
     * {@code UPDATE ... WHERE status='NEW' AND updated_at < :cutoff} would claim the row before
     * re-analysing it. Recommended follow-up, not a condition of this fix; not implemented here.
     */
    private BriefAnalysisResponse readOrReanalyse(CreatorBrief brief, CreatorProfile profile) {
        if (brief.getStatus() != BriefStatus.NEW) {
            return toResponse(brief, null);
        }
        if (isWithinAnalysisBudget(brief)) {
            throw new ApiException(
                    BRIEF_STILL_READING_CODE,
                    "Still reading this brief — wait for the creator's next message before trying"
                            + " again",
                    HttpStatus.CONFLICT);
        }
        PreferencesResponse prefs = preferencesService.getByProfileId(profile.getId());
        return analyse(brief, profile, prefs);
    }

    /**
     * The full latency budget of one analysis attempt, read from the SAME configured timeouts
     * {@link MeeraBriefAiClient} enforces on the AI call (not a second, independent number), plus
     * {@link #STILL_READING_SLACK_SECONDS} of scheduling slack. If those timeouts are tightened
     * during an incident, this ceiling tightens with them automatically.
     */
    private Duration analysisBudget() {
        return Duration.ofSeconds(
                aiProperties.getConnectTimeoutSeconds()
                        + aiProperties.getRequestTimeoutSeconds()
                        + STILL_READING_SLACK_SECONDS);
    }

    /**
     * A brief with no {@code createdAt} (should not happen — the column is {@code nullable =
     * false}) is treated as stale rather than young: refusing to ever re-analyse it would wedge the
     * row forever, which is worse than one extra AI call.
     */
    private boolean isWithinAnalysisBudget(CreatorBrief brief) {
        Instant createdAt = brief.getCreatedAt();
        return createdAt != null && Instant.now().isBefore(createdAt.plus(analysisBudget()));
    }

    /** SPEC.md &sect;3.8 — "my recent briefs", newest first. */
    @Transactional(readOnly = true)
    public List<BriefListItem> list(String creatorUserId, int limit) {
        CreatorProfile profile = preferencesService.requireCreatorProfile(creatorUserId);
        int effective = limit <= 0 ? DEFAULT_LIST_LIMIT : Math.min(limit, MAX_LIST_LIMIT);
        return briefRepository
                .findByCreatorProfileIdOrderByCreatedAtDesc(
                        profile.getId(), PageRequest.of(0, effective))
                .stream()
                .map(
                        brief ->
                                new BriefListItem(
                                        brief.getId(),
                                        brief.getSource() == null ? null : brief.getSource().name(),
                                        brief.getStatus() == null ? null : brief.getStatus().name(),
                                        brief.getBrandNameGuess(),
                                        brief.getExtractionSource(),
                                        brief.getCollaborationId(),
                                        brief.getCreatedAt() == null ? null : brief.getCreatedAt().toString()))
                .toList();
    }

    /**
     * SPEC.md &sect;3.8 — the creator decided this brief was not worth pursuing.
     *
     * <p>Reachable from any status and idempotent: dismissing an already-dismissed brief is a no-op
     * rather than a 409, because the only thing a 409 would tell the caller is that she clicked twice.
     */
    @Transactional
    public void dismiss(String creatorUserId, String briefId) {
        CreatorProfile profile = preferencesService.requireCreatorProfile(creatorUserId);
        CreatorBrief brief = requireOwnedBrief(profile.getId(), briefId);
        if (brief.getStatus() == BriefStatus.DISMISSED) {
            return;
        }
        brief.dismiss();
        briefRepository.save(brief);
    }

    // ------------------------------------------------------------------
    // The analysis pass, shared by both entry points
    // ------------------------------------------------------------------

    /**
     * Steps 3 to 6 of SPEC.md &sect;3.8, over a brief whose raw text is ALREADY PERSISTED.
     *
     * <p>The parameter is the saved entity, not a string, and that is deliberate: it is structurally
     * impossible to call this with text that has not been stored, which is the guarantee the whole
     * fallback path rests on.
     *
     * <p><b>Runs OUTSIDE a transaction.</b> The AI call in the first statement is a blocking HTTP
     * round trip, and the risk and quote steps that follow it read the creator's own tables; each of
     * those reads takes and releases a connection on its own rather than one connection being pinned
     * for the whole method. That is the availability half of the restructure — a hung provider now
     * costs the paste, not the pool. The durability half is that a throw from any of these steps
     * cannot take the already-committed raw text with it.
     */
    private BriefAnalysisResponse analyse(
            CreatorBrief brief, CreatorProfile profile, PreferencesResponse prefs) {

        BriefResult aiResult =
                briefAiClient.extract(profile.getId(), brief.getRawText(), prefs.creatorLanguage());

        BriefExtraction extraction;
        String source;
        String degradedReason;
        if (aiResult != null && aiResult.extraction().isPresent()) {
            extraction = aiResult.extraction().get();
            source = CreatorBrief.EXTRACTION_SOURCE_AI;
            degradedReason = null;
        } else {
            extraction = fallbackExtractor.extract(brief.getRawText());
            source = CreatorBrief.EXTRACTION_SOURCE_FALLBACK;
            degradedReason =
                    aiResult != null && aiResult.capReached()
                            ? BriefAnalysisResponse.DEGRADED_CAP
                            : BriefAnalysisResponse.DEGRADED_AI_UNAVAILABLE;
            log.info(
                    "CreatorBriefService: brief {} read by the deterministic extractor, reason={}",
                    brief.getId(),
                    degradedReason);
        }

        Collaboration collaboration =
                brief.getCollaborationId() == null
                        ? null
                        : collaborationRepository.findById(brief.getCollaborationId()).orElse(null);

        List<RiskFlag> flags =
                dealRiskService.evaluateExtraction(
                        profile, prefs, extraction, collaboration, brief.getRawText());
        PackageQuote quote = rateQuoteService.quoteForExtraction(profile, prefs, extraction);

        briefWriter.saveAnalysis(
                brief,
                extraction.brandName(),
                writeJson(extraction),
                writeJson(flags),
                writeJson(quote),
                source);

        return new BriefAnalysisResponse(
                brief.getId(),
                brief.getSource() == null ? null : brief.getSource().name(),
                brief.getStatus().name(),
                brief.getCollaborationId(),
                extraction,
                flags,
                quote,
                source,
                degradedReason,
                extraction.summaryLines(),
                brief.getCreatedAt() == null ? null : brief.getCreatedAt().toString());
    }

    /**
     * Rebuilds a response from the frozen snapshot on the row. Deliberately does NOT recompute flags
     * or the quote — see the class javadoc on why a brief must keep showing what the creator was
     * actually shown.
     *
     * <p>A snapshot that cannot be parsed yields nulls rather than a 500: the row still carries the
     * raw text and the status, and a creator reopening an old brief must not be met with an error
     * because a DTO shape moved under a stored JSON blob.
     */
    private BriefAnalysisResponse toResponse(CreatorBrief brief, String degradedReason) {
        BriefExtraction extraction = readJson(brief.getExtractedJson(), BriefExtraction.class);
        PackageQuote quote = readJson(brief.getQuoteJson(), PackageQuote.class);
        List<RiskFlag> flags = readFlags(brief.getRiskFlagsJson());
        return new BriefAnalysisResponse(
                brief.getId(),
                brief.getSource() == null ? null : brief.getSource().name(),
                brief.getStatus() == null ? null : brief.getStatus().name(),
                brief.getCollaborationId(),
                extraction,
                flags,
                quote,
                brief.getExtractionSource(),
                degradedReason,
                extraction == null ? null : extraction.summaryLines(),
                brief.getCreatedAt() == null ? null : brief.getCreatedAt().toString());
    }

    /**
     * {@link #ensurePlatformBrief} is handed a {@code creator_profiles.id} and no user id, and
     * {@link DealRiskService#evaluateExtraction} needs the entity rather than the id.
     *
     * <p>Reads the PROFILE repository, never {@code CreatorAgentPreferencesRepository} — the floors on
     * that row are the numbers SPEC.md &sect;0.3's barrier exists to contain, and preferences are
     * reached only through {@link CreatorAgentPreferencesService#getByProfileId}.
     */
    private CreatorProfile requireProfileById(String creatorProfileId) {
        return creatorProfileRepository
                .findById(creatorProfileId)
                .orElseThrow(
                        () ->
                                new ApiException(
                                        "CREATOR_PROFILE_NOT_FOUND",
                                        "Creator profile not found",
                                        HttpStatus.NOT_FOUND));
    }

    /**
     * Ownership-scoped fetch. The profile id is part of the query, so another creator's brief id is
     * indistinguishable from one that does not exist and an id cannot be probed for existence.
     */
    private CreatorBrief requireOwnedBrief(String creatorProfileId, String briefId) {
        return briefRepository
                .findByIdAndCreatorProfileId(briefId, creatorProfileId)
                .orElseThrow(
                        () -> new ApiException("BRIEF_NOT_FOUND", "Brief not found", HttpStatus.NOT_FOUND));
    }

    /**
     * SPEC.md &sect;3.8 — the text the platform path reads: the offer currently on the table plus the
     * campaign's own description of the work and the structured deal terms.
     *
     * <p>The LATEST proposal, not the first: after three counters the first offer is not what either
     * party is discussing, and the extraction feeds a quote about the deal as it stands.
     *
     * <p>Composed as labelled plain text rather than JSON because the extractor on the other end
     * reads briefs, and this must look like one. Every value here is already on Influora (a brand
     * wrote it into a campaign or an offer), so it is not more trusted than a pasted brief — the AI
     * route wraps it as untrusted either way.
     */
    private String platformRawText(Collaboration collaboration, Campaign campaign) {
        StringBuilder sb = new StringBuilder();
        if (campaign != null) {
            append(sb, "Campaign", campaign.getTitle());
            append(sb, "Description", campaign.getDescription());
            append(sb, "Requirements", campaign.getRequirementsJson());
            append(sb, "Brand guidelines", campaign.getBrandGuidelines());
        }
        Optional<DealMessage> proposal =
                dealMessageRepository.findFirstByCollaborationIdAndKindOrderByCreatedAtDesc(
                        collaboration.getId(), DealMessageKind.proposal);
        proposal.ifPresent(message -> append(sb, "Offer message", message.getContent()));
        if (collaboration.getAgreedRate() != null) {
            append(
                    sb,
                    "Offer amount",
                    collaboration.getAgreedRate().toPlainString()
                            + " "
                            + (collaboration.getCurrency() == null ? "INR" : collaboration.getCurrency()));
        }
        append(sb, "Usage rights", collaboration.getUsageRights());
        if (collaboration.isUsagePerpetual()) {
            append(sb, "Usage", "perpetual");
        } else if (collaboration.getUsageMonths() != null) {
            append(sb, "Usage", collaboration.getUsageMonths() + " months usage rights");
        }
        append(sb, "Usage channels", collaboration.getUsageChannels());
        if (collaboration.getExclusivityDays() != null) {
            append(sb, "Exclusivity", collaboration.getExclusivityDays() + " days exclusivity");
        }
        if (collaboration.getExclusivityScope() != null) {
            append(sb, "Exclusivity scope", collaboration.getExclusivityScope().name());
        }
        append(sb, "Exclusivity brands", collaboration.getExclusivityBrands());
        append(sb, "Revisions", collaboration.getMaxRevisions() + " revisions");
        append(sb, "Notes", collaboration.getNotes());
        return sb.toString().trim();
    }

    private static void append(StringBuilder sb, String label, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        sb.append(label).append(": ").append(value).append('\n');
    }

    /**
     * A snapshot column that cannot be serialised is stored as null, not as a thrown exception. The
     * brief itself — the creator's raw text — is already saved by the time this runs, and losing the
     * analysis of a brief is recoverable while failing the request after the row exists leaves her
     * with a brief she cannot open.
     */
    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.error("CreatorBriefService: could not serialise a brief snapshot", e);
            return null;
        }
    }

    private <T> T readJson(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            log.error("CreatorBriefService: could not read a stored brief snapshot", e);
            return null;
        }
    }

    private List<RiskFlag> readFlags(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(
                    json,
                    objectMapper
                            .getTypeFactory()
                            .constructCollectionType(List.class, RiskFlag.class));
        } catch (Exception e) {
            log.error("CreatorBriefService: could not read stored risk flags", e);
            return null;
        }
    }
}
