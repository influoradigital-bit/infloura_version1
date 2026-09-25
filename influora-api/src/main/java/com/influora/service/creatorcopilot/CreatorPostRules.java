package com.influora.service.creatorcopilot;

import com.influora.domain.enums.ChallengeDayType;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * The post rules every reader of a creator's own {@code media_metrics} shares, in one place
 * (Meera intelligence v1, spec &sect;3.2). Moved here verbatim from {@link
 * CreatorPostingPatternService} ({@code bucketLabel}/{@code daypartOf}) and {@code
 * com.influora.service.CreatorChallengeService} ({@code SETTLING_PERIOD}, {@code mediaTypeMatches},
 * {@code mapBestType}) so {@link CreatorIntelligenceService} reuses them instead of copying them.
 * The move changed no behaviour: the existing posting-pattern and challenge tests are the guard and
 * were not edited.
 */
public final class CreatorPostRules {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** Instagram numbers still grow for ~2 days (CHALLENGE-SPEC.md Backend &sect;6). */
    public static final Duration SETTLING_PERIOD = Duration.ofHours(48);

    private CreatorPostRules() {}

    /**
     * "weekday"/"weekend" x "morning"/"afternoon"/"evening"/"night", both decided from {@code
     * postedAt} converted to Asia/Kolkata -- never from the instant's own (UTC) calendar day or
     * clock time, which can disagree with the IST one near either boundary.
     */
    public static String windowLabel(Instant postedAt) {
        ZonedDateTime ist = postedAt.atZone(IST);
        boolean weekend =
                ist.getDayOfWeek() == DayOfWeek.SATURDAY || ist.getDayOfWeek() == DayOfWeek.SUNDAY;
        return (weekend ? "weekend" : "weekday") + " " + daypartOf(ist.toLocalTime());
    }

    /**
     * morning 05:00-11:59, afternoon 12:00-16:59, evening 17:00-21:59, night 22:00-04:59 (wraps
     * past midnight). Lower bound of each named range is inclusive; 16:59:59.999999999 is the last
     * instant of "afternoon" and 17:00:00 exactly is the first instant of "evening".
     */
    private static String daypartOf(LocalTime time) {
        if (!time.isBefore(LocalTime.of(5, 0)) && time.isBefore(LocalTime.of(12, 0))) {
            return "morning";
        }
        if (!time.isBefore(LocalTime.of(12, 0)) && time.isBefore(LocalTime.of(17, 0))) {
            return "afternoon";
        }
        if (!time.isBefore(LocalTime.of(17, 0)) && time.isBefore(LocalTime.of(22, 0))) {
            return "evening";
        }
        return "night";
    }

    /**
     * VIDEO/REELS -> REEL, CAROUSEL_ALBUM -> CAROUSEL, IMAGE -> POST (facts already verified);
     * null for null or any other value. Meta's {@code media_type} cannot tell a feed video from a
     * Reel (nothing fetches {@code media_product_type}), so VIDEO and REELS are one group.
     */
    public static ChallengeDayType canonicalType(String mediaType) {
        if (mediaType == null) {
            return null;
        }
        return switch (mediaType) {
            case "VIDEO", "REELS" -> ChallengeDayType.REEL;
            case "CAROUSEL_ALBUM" -> ChallengeDayType.CAROUSEL;
            case "IMAGE" -> ChallengeDayType.POST;
            default -> null; // unrecognised media_type -- never guessed
        };
    }

    /** REELS/VIDEO satisfies REEL, CAROUSEL_ALBUM satisfies CAROUSEL, IMAGE satisfies POST --
     * "facts already verified" in CHALLENGE-SPEC.md. */
    public static boolean typeMatches(ChallengeDayType planned, String mediaType) {
        return switch (planned) {
            case REEL -> "VIDEO".equals(mediaType) || "REELS".equals(mediaType);
            case CAROUSEL -> "CAROUSEL_ALBUM".equals(mediaType);
            case POST -> "IMAGE".equals(mediaType);
            case REST -> false;
        };
    }
}
