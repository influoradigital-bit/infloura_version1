package com.influora.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Task #22 — Kabir M-2 / M-9-1 XSS payload regression for shared TextSanitizer. */
class TextSanitizerTest {

    @Test
    @DisplayName("sanitizePlainText: null stays null")
    void nullInput() {
        assertNull(TextSanitizer.sanitizePlainText(null));
    }

    @Test
    @DisplayName("sanitizePlainText: plain text unchanged")
    void plainTextUnchanged() {
        assertEquals("Hello world", TextSanitizer.sanitizePlainText("Hello world"));
    }

    @Test
    @DisplayName("sanitizePlainText: strips script tags")
    void stripsScriptTags() {
        String payload = "<script>alert('xss')</script>Hello";
        String result = TextSanitizer.sanitizePlainText(payload);
        assertEquals("Hello", result);
        assertTrue(result.indexOf('<') < 0);
        assertTrue(result.toLowerCase().indexOf("script") < 0 || result.equals("Hello"));
    }

    @Test
    @DisplayName("sanitizePlainText: strips img onerror event handler tag")
    void stripsEventHandlerTag() {
        String payload = "<img src=x onerror=alert(1)>visible";
        assertEquals("visible", TextSanitizer.sanitizePlainText(payload));
    }

    @Test
    @DisplayName("sanitizePlainText: strips svg onload handler")
    void stripsSvgOnload() {
        String payload = "<svg onload=alert(1)></svg>ok";
        assertEquals("ok", TextSanitizer.sanitizePlainText(payload));
    }

    @Test
    @DisplayName("sanitizePlainText: strips nested script inside div")
    void stripsNestedScript() {
        String payload = "<div><script>document.cookie</script>text</div>";
        assertEquals("text", TextSanitizer.sanitizePlainText(payload));
    }

    @Test
    @DisplayName("sanitizePlainText: strips style blocks")
    void stripsStyleBlock() {
        String payload = "<style>body{background:url(javascript:alert(1))}</style>content";
        assertEquals("content", TextSanitizer.sanitizePlainText(payload));
    }

    @Test
    @DisplayName("sanitizePlainText: decodes entities then strips residual tags")
    void decodesEntitiesAndStripsTags() {
        String payload = "&lt;script&gt;alert(1)&lt;/script&gt;safe";
        assertEquals("safe", TextSanitizer.sanitizePlainText(payload));
    }

    @Test
    @DisplayName("sanitizePlainText: trims outer whitespace")
    void trimsWhitespace() {
        assertEquals("hello", TextSanitizer.sanitizePlainText("  hello  "));
    }

    @Test
    @DisplayName("sanitizeHashtags: cleans each tag and drops blanks")
    void sanitizeHashtags() {
        List<String> input = new ArrayList<>();
        input.add("#fitness");
        input.add("<script>x</script>tag");
        input.add("  ");
        input.add(null);
        input.add("#brand");
        List<String> result = TextSanitizer.sanitizeHashtags(input);
        assertEquals(List.of("#fitness", "tag", "#brand"), result);
    }

    @Test
    @DisplayName("sanitizeHashtags: null list stays null")
    void sanitizeHashtagsNull() {
        assertNull(TextSanitizer.sanitizeHashtags(null));
    }
}
