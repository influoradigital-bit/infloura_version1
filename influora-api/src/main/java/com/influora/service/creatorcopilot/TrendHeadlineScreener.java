package com.influora.service.creatorcopilot;

/**
 * T-GOLIVE-0918 [vikram · 2026-09-18] — public bridge onto {@link CreatorNudgeService}'s
 * deterministic word filter, for {@code TrendPullJob} (com.influora.job) per
 * wiki/decisions/2026-09-18-trend-headline-screening.md step 1 ("the existing
 * {@code UnsafeHeadlineTopic} filter"). {@code CreatorNudgeService.isQuotableInCreatorCopy} and
 * {@code .firstUnsafeTopic} are deliberately {@code static} but package-private (see that class's
 * own javadoc: kept visible only to same-package evasion tests, not exposed as public API), and
 * {@code CreatorNudgeService.java} is owned by another lane on this go-live run (T-GOLIVE-0918)
 * — edits to it are out of scope here. Rather than widen its own visibility (an edit to a file
 * this lane must not touch) or re-implement the term list a second time (the exact "fourth copy
 * of the vocabulary" drift risk the job-design doc for this feature warns against), this class
 * sits in the SAME package so it can call the two package-private static methods directly, and
 * exposes them under public signatures for {@code com.influora.job.TrendPullJob} to call. Source
 * of the requirement: {@code .proof-os/tasks/T-COPILOT-ON-0910/job-design.md} step 13 ("call its
 * public static isQuotableInCreatorCopy/firstUnsafeTopic only") — flagged to the reviewer because
 * neither method is actually public on {@code CreatorNudgeService} today; this file is the
 * least-footprint way to honor that instruction without touching the owned file.
 *
 * <p>No logic here — every call is a direct pass-through, so behavior (including all F-0825/
 * F-0827/F-0832/F-0837 Unicode-evasion handling) is byte-for-byte {@code CreatorNudgeService}'s.
 */
public final class TrendHeadlineScreener {

    private TrendHeadlineScreener() {}

    /** True only if {@code text} may appear, verbatim or paraphrased, in creator-facing copy.
     * Fails closed on {@code null}/blank/all-invisible text — see
     * {@code CreatorNudgeService#isQuotableInCreatorCopy} javadoc. */
    public static boolean isSafeForCreatorCopy(String text) {
        return CreatorNudgeService.isQuotableInCreatorCopy(text);
    }

    /** Category name (e.g. {@code "DEATH"}, {@code "CRIME"}) that disqualified {@code text}, or
     * {@code null} if nothing matched (including for null/blank input — {@code null} here does
     * NOT mean "safe", callers must gate on {@link #isSafeForCreatorCopy} instead). Used only for
     * rejection logging BY CATEGORY — never log the headline text itself (F-0786). */
    public static String rejectionCategory(String text) {
        CreatorNudgeService.UnsafeHeadlineTopic topic = CreatorNudgeService.firstUnsafeTopic(text);
        return topic == null ? null : topic.name();
    }
}
