package com.influora.service.verification;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * DPF-6 — extracts the platform + platform media identifier from an already-validated {@code
 * Deliverable.postUrl} (see {@code CreatorDeliverableService#validatePlatformPostUrl}, DPF-3/DPF-3b).
 *
 * <p><b>⚠️ CARRY-FORWARD (Kabir/Priya, DPF-3b hardening) — hard constraint, do not violate:</b>
 * {@code postUrl} is stored as an opaque, already-validated string and must NEVER be URL-decoded
 * anywhere server-side (decoding an encoded payload before rendering/parsing would turn a stored,
 * inert encoded string into live injection). This class only ever runs a regex match against the
 * raw string — no {@code URLDecoder}, no {@code decodeURIComponent}-equivalent, ever.
 *
 * <p>As defense-in-depth on top of the write-time validation in {@code
 * CreatorDeliverableService#validatePlatformPostUrl}, this class independently rejects a URL that
 * contains an encoded angle-bracket/quote/backtick ({@code %3C %3E %22 %27 %60}, case-insensitive)
 * before attempting to extract anything — such a URL should never have been persisted, but if one
 * ever is (e.g. a future write path with a weaker guard), verification must refuse to touch it
 * rather than pass it along to a platform API call.
 */
public final class PostUrlIdentifier {

    private PostUrlIdentifier() {}

    /** Platform inferred from the post URL's host/path shape. */
    public enum Platform {
        INSTAGRAM,
        YOUTUBE
    }

    /** The platform and the platform-native identifier extracted from the post URL. */
    public record Result(Platform platform, String mediaId) {}

    private static final Pattern ENCODED_BRACKET =
            Pattern.compile("%3C|%3E|%22|%27|%60", Pattern.CASE_INSENSITIVE);

    // Mirrors CreatorDeliverableService#validatePlatformPostUrl's whitelist exactly — this class
    // never accepts a shape that write-time validation would have rejected.
    private static final Pattern INSTAGRAM_URL =
            Pattern.compile("^https://(www\\.)?instagram\\.com/(?:p|reel)/([A-Za-z0-9_-]+)/?$");
    private static final Pattern YOUTUBE_WATCH_URL =
            Pattern.compile("^https://(www\\.)?youtube\\.com/watch\\?v=([A-Za-z0-9_-]+)(&[A-Za-z0-9_=&%.-]*)?$");
    private static final Pattern YOUTUBE_SHORT_URL = Pattern.compile("^https://youtu\\.be/([A-Za-z0-9_-]+)$");

    /**
     * Extracts the platform + media id, or {@link Optional#empty()} if the URL is unsafe (encoded
     * bracket present) or does not match a recognized platform shape. Never throws, never decodes.
     */
    public static Optional<Result> extract(String postUrl) {
        if (postUrl == null || postUrl.isBlank()) {
            return Optional.empty();
        }
        if (ENCODED_BRACKET.matcher(postUrl).find()) {
            return Optional.empty();
        }

        var ig = INSTAGRAM_URL.matcher(postUrl);
        if (ig.matches()) {
            return Optional.of(new Result(Platform.INSTAGRAM, ig.group(2)));
        }

        var ytWatch = YOUTUBE_WATCH_URL.matcher(postUrl);
        if (ytWatch.matches()) {
            return Optional.of(new Result(Platform.YOUTUBE, ytWatch.group(2)));
        }

        var ytShort = YOUTUBE_SHORT_URL.matcher(postUrl);
        if (ytShort.matches()) {
            return Optional.of(new Result(Platform.YOUTUBE, ytShort.group(1)));
        }

        return Optional.empty();
    }
}
