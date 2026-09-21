package com.influora.service.brief;

import com.influora.common.Ulids;
import com.influora.domain.entity.CreatorBrief;
import com.influora.repository.CreatorBriefRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * T-MEERA-CREATOR-PHASE-B (SPEC.md &sect;3.8) — the transaction boundaries of the paste flow, and the
 * reason they are on a SEPARATE bean.
 *
 * <p><b>This class exists so that the AI call is not inside a transaction.</b>
 * {@code CreatorBriefService.paste} used to be one {@code @Transactional} method with a blocking HTTP
 * round trip to influora-ai in the middle of it, which meant a pooled JDBC connection was held for
 * the whole call. The timeouts are {@code CreatorSuggestionAiProperties}' 5s connect + 15s request, so
 * a hung provider held a connection for up to 20 seconds per paste, ten pastes per rate-limit window
 * per creator, and the pool is shared with every unrelated endpoint. A slow AI provider was therefore
 * an availability incident for the whole API, not a degraded brief reading.
 *
 * <p><b>It also makes the class javadoc on {@code CreatorBriefService} true.</b> That javadoc promises
 * the raw text survives an outage. Inside a single transaction it did not: the first
 * {@code save} was session-local, so a throw from the risk or the quote step — both of which run
 * AFTER it — rolled the paste back with everything else, and the creator lost the brief the promise is
 * about. No Mockito-shaped test can see that, because a mocked repository records the {@code save}
 * that a real rollback would erase. Splitting the raw-text write into its own committed transaction is
 * what turns the promise into a fact.
 *
 * <p><b>Why a separate bean and not {@code @Transactional} on a second private method.</b>
 * Self-invocation does not go through the Spring proxy: a method called from another method on the
 * same bean silently runs in the caller's transaction, or in none, whatever its annotation says. The
 * same trap applies to a non-public method. A distinct bean is the only structure where the boundary
 * is actually there in production, rather than merely documented.
 *
 * <p><b>{@link Propagation#REQUIRES_NEW} on both writes, deliberately.</b> {@code paste} has no
 * transaction of its own today, so {@code REQUIRED} would behave identically — but
 * {@code ensurePlatformBrief} is reachable from tool executors that may be transactional, and the
 * durability guarantee must not quietly depend on who calls. {@code REQUIRES_NEW} means the raw text
 * is committed when this method returns regardless of the caller, which is precisely the promise.
 */
@Service
public class CreatorBriefWriter {

    private final CreatorBriefRepository briefRepository;

    public CreatorBriefWriter(CreatorBriefRepository briefRepository) {
        this.briefRepository = briefRepository;
    }

    /**
     * SPEC.md &sect;3.8 step 2 — the creator's raw text, COMMITTED, before anything reads it. Status
     * NEW, no extraction. Everything after this point can fail without losing her paste.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CreatorBrief saveRawPaste(String creatorProfileId, String rawText) {
        return briefRepository.save(CreatorBrief.paste(Ulids.newUlid(), creatorProfileId, rawText));
    }

    /** The platform-path equivalent of {@link #saveRawPaste}, same boundary and same reason. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CreatorBrief saveRawPlatform(
            String creatorProfileId, String collaborationId, String rawText) {
        return briefRepository.save(
                CreatorBrief.platform(Ulids.newUlid(), creatorProfileId, collaborationId, rawText));
    }

    /**
     * SPEC.md &sect;3.8 step 6 — the frozen snapshot, written as one call on an entity that is
     * detached by now (its own transaction closed in {@link #saveRawPaste}). {@code save} merges it,
     * so this is an UPDATE of the row that already exists rather than a second insert.
     *
     * <p>A failure here loses the ANALYSIS, never the brief: the row is already committed with its raw
     * text and status NEW, which {@link BriefFallbackExtractor} can read on a retry.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CreatorBrief saveAnalysis(
            CreatorBrief brief,
            String brandNameGuess,
            String extractedJson,
            String riskFlagsJson,
            String quoteJson,
            String extractionSource) {
        brief.applyAnalysis(brandNameGuess, extractedJson, riskFlagsJson, quoteJson, extractionSource);
        return briefRepository.save(brief);
    }
}
