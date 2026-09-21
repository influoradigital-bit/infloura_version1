package com.influora.domain.enums;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/** Content platform/type for a deliverable slot (09_CREATOR_DELIVERABLES_SPEC.md §3.1). */
public enum DeliverableType {
    INSTAGRAM_POST("Instagram Post"),
    INSTAGRAM_REEL("Instagram Reel"),
    INSTAGRAM_STORY("Instagram Story"),
    INSTAGRAM_CAROUSEL("Instagram Carousel"),
    YOUTUBE_VIDEO("YouTube Video"),
    YOUTUBE_SHORT("YouTube Short"),
    FACEBOOK_POST("Facebook Post"),
    FACEBOOK_REEL("Facebook Reel"),
    TIKTOK_VIDEO("TikTok Video");

    /**
     * How the platform writes this type for a human, used to title the submission slot the creator
     * opens ("YouTube Video #1"). Spelled out per constant rather than derived from the name,
     * because deriving it gets the brand names wrong — a title-case of {@code YOUTUBE_VIDEO} reads
     * "Youtube Video", and of {@code TIKTOK_VIDEO} "Tiktok Video". The SPA keeps the same strings
     * in {@code src/lib/deliverable-slots.ts}.
     */
    private final String displayLabel;

    DeliverableType(String displayLabel) {
        this.displayLabel = displayLabel;
    }

    /**
     * The constant this wire value NAMES, or empty when it names none of them.
     *
     * <p><b>Why this exists.</b> Offer forms used to send their own vocabularies — display labels
     * with spaces ("TikTok Video", "YouTube Short") from the deal-room proposal form, short codes
     * ("REEL", "POST", "VIDEO", "SHORT") from the Discover offer modal. {@code ContractService}
     * parsed them with a {@code valueOf} wrapped in a {@code catch} that returned {@link
     * #INSTAGRAM_REEL}, so every one of those values silently became an Instagram Reel: a brand
     * ordered a YouTube video and the creator was handed a reel slot to fill. Both the forms and
     * this parser are now bound to the same list.
     *
     * <p><b>What it accepts, and what it deliberately refuses.</b> Case, surrounding whitespace
     * and the separator between words are all cosmetic, so {@code "YouTube Short"}, {@code
     * "youtube-short"} and {@code "YOUTUBE_SHORT"} all resolve to {@link #YOUTUBE_SHORT} — that is
     * normalising a value onto the constant it already names, not guessing. A value that names no
     * constant — {@code "REEL"} (which Instagram? Facebook?), {@code "VIDEO"}, {@code "Blog Post"},
     * {@code "Other"} — returns empty so the caller can refuse it out loud. Resolving those would
     * be inventing an order the brand never placed, which is the defect this replaces.
     */
    public static Optional<DeliverableType> fromWireValue(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String normalized =
                raw.trim()
                        .toUpperCase(Locale.ROOT)
                        .replaceAll("[^A-Z0-9]+", "_")
                        .replaceAll("^_+|_+$", "");
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(t -> t.name().equals(normalized)).findFirst();
    }

    /** {@code "INSTAGRAM_POST, INSTAGRAM_REEL, ..."} — for error messages that must be actionable. */
    public static String acceptedValues() {
        return Arrays.stream(values()).map(Enum::name).collect(Collectors.joining(", "));
    }

    /** {@code "Instagram Reel"} — the human form, used to title a materialized slot. */
    public String displayLabel() {
        return displayLabel;
    }
}
