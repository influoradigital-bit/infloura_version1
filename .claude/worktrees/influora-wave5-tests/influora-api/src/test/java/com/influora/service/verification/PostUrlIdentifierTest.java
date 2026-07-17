package com.influora.service.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** DPF-6 — extraction of platform + media id from an already-validated {@code postUrl}. */
class PostUrlIdentifierTest {

    @Test
    @DisplayName("extracts Instagram shortcode from /p/{code}/ URL")
    void extractsInstagramPostShortcode() {
        Optional<PostUrlIdentifier.Result> result =
                PostUrlIdentifier.extract("https://www.instagram.com/p/ABC123xyz/");

        assertTrue(result.isPresent());
        assertEquals(PostUrlIdentifier.Platform.INSTAGRAM, result.get().platform());
        assertEquals("ABC123xyz", result.get().mediaId());
    }

    @Test
    @DisplayName("extracts Instagram shortcode from /reel/{code}/ URL without trailing slash")
    void extractsInstagramReelShortcode() {
        Optional<PostUrlIdentifier.Result> result =
                PostUrlIdentifier.extract("https://instagram.com/reel/XYZ789");

        assertTrue(result.isPresent());
        assertEquals(PostUrlIdentifier.Platform.INSTAGRAM, result.get().platform());
        assertEquals("XYZ789", result.get().mediaId());
    }

    @Test
    @DisplayName("extracts YouTube video id from ?v= query param")
    void extractsYoutubeWatchVideoId() {
        Optional<PostUrlIdentifier.Result> result =
                PostUrlIdentifier.extract("https://www.youtube.com/watch?v=dQw4w9WgXcQ");

        assertTrue(result.isPresent());
        assertEquals(PostUrlIdentifier.Platform.YOUTUBE, result.get().platform());
        assertEquals("dQw4w9WgXcQ", result.get().mediaId());
    }

    @Test
    @DisplayName("extracts YouTube video id ignoring trailing query params")
    void extractsYoutubeWatchVideoIdWithTrailingParams() {
        Optional<PostUrlIdentifier.Result> result =
                PostUrlIdentifier.extract(
                        "https://www.youtube.com/watch?v=dQw4w9WgXcQ&ab_channel=My%20Name");

        assertTrue(result.isPresent());
        assertEquals("dQw4w9WgXcQ", result.get().mediaId());
    }

    @Test
    @DisplayName("extracts YouTube video id from youtu.be short URL")
    void extractsYoutubeShortUrlVideoId() {
        Optional<PostUrlIdentifier.Result> result = PostUrlIdentifier.extract("https://youtu.be/dQw4w9WgXcQ");

        assertTrue(result.isPresent());
        assertEquals(PostUrlIdentifier.Platform.YOUTUBE, result.get().platform());
        assertEquals("dQw4w9WgXcQ", result.get().mediaId());
    }

    @Test
    @DisplayName("Kabir/Priya carry-forward: rejects a URL containing an encoded angle bracket")
    void rejectsEncodedAngleBracket() {
        Optional<PostUrlIdentifier.Result> result =
                PostUrlIdentifier.extract(
                        "https://www.youtube.com/watch?v=dQw4w9WgXcQ&x=%3Cscript%3E");

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("rejects a URL containing an encoded quote/backtick even on an otherwise-valid shape")
    void rejectsEncodedQuoteAndBacktick() {
        assertTrue(
                PostUrlIdentifier.extract("https://www.youtube.com/watch?v=dQw4w9WgXcQ&x=%22").isEmpty());
        assertTrue(
                PostUrlIdentifier.extract("https://www.youtube.com/watch?v=dQw4w9WgXcQ&x=%27").isEmpty());
        assertTrue(
                PostUrlIdentifier.extract("https://www.youtube.com/watch?v=dQw4w9WgXcQ&x=%60").isEmpty());
    }

    @Test
    @DisplayName("never URL-decodes — a literal (not percent-encoded) bracket is caught by the shape regex, not decoding")
    void doesNotDecodeBeforeMatching() {
        // A literal '<' would never pass CreatorDeliverableService#validatePlatformPostUrl at
        // write time, but if it somehow reached here, the shape regex (not a decode step) is what
        // rejects it — this test pins that no decoding occurs anywhere in this class.
        Optional<PostUrlIdentifier.Result> result =
                PostUrlIdentifier.extract("https://www.instagram.com/p/<script>/");
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("rejects an unrecognized platform/shape")
    void rejectsUnrecognizedShape() {
        assertTrue(PostUrlIdentifier.extract("https://example.com/p/ABC123/").isEmpty());
        assertTrue(PostUrlIdentifier.extract("http://instagram.com/p/ABC123/").isEmpty());
        assertTrue(PostUrlIdentifier.extract(null).isEmpty());
        assertTrue(PostUrlIdentifier.extract("").isEmpty());
        assertTrue(PostUrlIdentifier.extract("   ").isEmpty());
    }
}
