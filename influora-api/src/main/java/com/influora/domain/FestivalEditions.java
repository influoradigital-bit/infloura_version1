package com.influora.domain;

import java.util.Locale;
import java.util.Set;

/**
 * The editions Influora actually runs (T-FESTIVALBOX-0905). One list, used by every surface that
 * accepts an {@code edition} string from outside.
 *
 * <h2>Why this exists — [Kabir H-1]</h2>
 *
 * {@code POST /festival/coupon-copied} is unauthenticated and writes one counter row per
 * {@code (edition, sponsor_slug, bucket_day)}. Its migration claims the table is "bounded by
 * (recognized sponsors x days), not by request volume" — and that claim was FALSE, because only
 * {@code sponsorSlug} was checked against anything. {@code edition} was accepted as any string
 * matching {@code ^[A-Z0-9_]{1,64}$}, so a caller holding one real (published, guessable) sponsor
 * slug could mint a brand-new row per request across a 36^64 keyspace: unauthenticated disk growth
 * on production MySQL, from the very endpoint whose daily-bucket design existed to prevent exactly
 * that.
 *
 * <p>Checking the edition closes it: rows are now bounded by (known editions x sponsors x days),
 * which is what the migration always said.
 *
 * <h2>Why a constant and not a table</h2>
 *
 * There is deliberately no {@code Edition} entity — see {@code src/content/festival-editions.ts}
 * and {@code wiki/decisions/FESTIVAL-BOX-CMO-ASSESSMENT.md} §5: Edition 01 is run by hand, and the
 * edition/sponsor data model is explicitly deferred until the pilot proves out. A hardcoded set is
 * the honest shape for that: adding an edition is a code change today, which is true, rather than a
 * table that pretends editions are self-service.
 *
 * <p><b>When the edition model ships, delete this class</b> and check against the real table. Until
 * then, adding an edition means adding it here AND to {@code FESTIVAL_EDITIONS} in
 * {@code src/content/festival-editions.ts}. The two are not linked by anything the compiler can
 * see, and the failure is silent in the worst direction: a new edition present only on the frontend
 * renders a page whose every coupon-copy tap is dropped, so the sponsor's Day-20 report reads zero
 * and nothing errors. {@code .proof-os/gates/F-0682-festival-edition-parity.sh} is what stops that
 * — a JUnit test cannot, since the frontend file lives outside this Maven module.
 */
public final class FestivalEditions {

    private FestivalEditions() {}

    /** Kept in sync with {@code FESTIVAL_EDITIONS}' {@code editionKey} values in the frontend. */
    public static final String MUMBAI_FESTIVE_2026 = "MUMBAI_FESTIVE_2026";

    /**
     * Every edition the platform will accept a submission or a copy-tap for.
     *
     * <p>Deliberately NOT including a wildcard or a "dev" escape hatch: an unrecognized edition on
     * a public endpoint is either a typo or an attack, and both are better handled by the caller
     * being told nothing (see each caller's own drop/normalize behaviour) than by a bypass that
     * someone eventually ships enabled.
     */
    public static final Set<String> KNOWN = Set.of(MUMBAI_FESTIVE_2026);

    /** The edition a submission belongs to when it does not name one. */
    public static final String DEFAULT = MUMBAI_FESTIVE_2026;

    /**
     * Case-insensitive, whitespace-tolerant membership check.
     *
     * <p>Tolerant on input, exact on storage: callers normalize through {@link #normalize} so the
     * value that reaches the database is always the canonical constant, never a caller's casing.
     * Two spellings of one edition would split every report in half.
     */
    public static boolean isKnown(String edition) {
        return edition != null && KNOWN.contains(edition.trim().toUpperCase(Locale.ROOT));
    }

    /**
     * Returns the canonical form of {@code edition}, or {@code null} if it is not a known edition.
     * Callers decide what an unknown edition means for them — a coupon-copy tap drops it silently,
     * an enquiry falls back to {@link #DEFAULT} rather than losing a real sales lead.
     */
    public static String normalize(String edition) {
        if (edition == null) {
            return null;
        }
        String canonical = edition.trim().toUpperCase(Locale.ROOT);
        return KNOWN.contains(canonical) ? canonical : null;
    }
}
