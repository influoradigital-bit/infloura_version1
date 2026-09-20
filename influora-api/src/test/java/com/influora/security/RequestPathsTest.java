package com.influora.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

/** EV-004: unit contract of the shared path normaliser. */
class RequestPathsTest {

    @ParameterizedTest(name = "[{index}] {0} -> {1}")
    @CsvSource(
            delimiter = '|',
            value = {
                "/internal/meera/turns/release | /internal/meera/turns/release",
                "/%69nternal/meera | /internal/meera",
                "/%2569nternal/meera | /internal/meera",
                "/%252569nternal/meera | /internal/meera",
                "/%2Finternal/meera | /internal/meera",
                "//internal//meera/// | /internal/meera",
                "/./internal/./meera | /internal/meera",
                "/%2E/internal | /internal",
                "/a/b/../../internal/meera | /internal/meera",
                "/internal;p=1/meera;jsessionid=x | /internal/meera",
                "/internal%3Bx/meera | /internal/meera",
                "/%5Cinternal\\meera | /internal/meera",
                "/INTERNAL/Meera/ | /INTERNAL/Meera",
                "/ | /",
                "'' | /",
                "/wallet/%77ithdraw | /wallet/withdraw",
                "/client-errors;x=1 | /client-errors",
            })
    void normalises(String raw, String expected) {
        assertEquals(expected, RequestPaths.normalize(raw));
    }

    @ParameterizedTest(name = "[{index}] {0} is unnormalisable")
    @ValueSource(strings = {"/%zz/internal", "/%", "/../internal", "/a/../../internal", "/%00internal", "/%25252525252569"})
    void rejectsUnnormalisable(String raw) {
        assertThrows(RequestPaths.UnnormalisablePathException.class, () -> RequestPaths.normalize(raw));
    }

    @Test
    void stripsTheContainerContextPathEvenWhenEncoded() {
        MockHttpServletRequest r = new MockHttpServletRequest("POST", "/api/%761/%69nternal/meera");
        r.setContextPath("/api/%761");
        assertEquals("/internal/meera", RequestPaths.pathWithinApplication(r));

        MockHttpServletRequest decodedCtx = new MockHttpServletRequest("POST", "/api/%761/internal/meera");
        decodedCtx.setContextPath("/api/v1");
        assertEquals("/internal/meera", RequestPaths.pathWithinApplication(decodedCtx));
    }

    @Test
    void emptyContextPathFallsBackToTheAppContextPath() {
        assertEquals(
                "/wallet/withdraw",
                RequestPaths.pathWithinApplication(new MockHttpServletRequest("POST", "/api/v1/wallet/withdraw")));
        assertEquals(
                "/wallet/withdraw",
                RequestPaths.pathWithinApplication(new MockHttpServletRequest("POST", "/wallet/withdraw")));
        assertEquals("/", RequestPaths.pathWithinApplication(new MockHttpServletRequest("GET", "/api/v1")));
        assertEquals(
                "/api/v1x/a",
                RequestPaths.pathWithinApplication(new MockHttpServletRequest("GET", "/api/v1x/a")));
    }

    @Test
    void isUnderMatchesOnSegmentBoundaryCaseInsensitively() {
        assertTrue(RequestPaths.pathIsUnder("/internal", "/internal"));
        assertTrue(RequestPaths.pathIsUnder("/Internal/meera", "/internal"));
        assertFalse(RequestPaths.pathIsUnder("/internals/x", "/internal"));
        assertFalse(RequestPaths.pathIsUnder("/portfolio/internal", "/internal"));
    }

    @Test
    void isUnderFailsClosedOnUnnormalisablePaths() {
        assertTrue(RequestPaths.isUnder(new MockHttpServletRequest("GET", "/api/v1/%zz"), "/internal"));
        assertFalse(RequestPaths.isUnder(new MockHttpServletRequest("GET", "/api/v1/campaigns"), "/internal"));
    }

    @Test
    void isUnderAlsoConsultsTheContainerServletPath() {
        MockHttpServletRequest r = new MockHttpServletRequest("GET", "/api/v1/somewhere-else");
        r.setContextPath("/api/v1");
        r.setServletPath("/internal/meera/context");
        assertTrue(RequestPaths.isUnder(r, "/internal"));
    }
}
