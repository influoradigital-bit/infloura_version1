package com.influora.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * CR-11 client crash-report sink ({@code wiki/tech/cr-11-client-error-contract.md}). No {@code
 * @WebMvcTest}/{@code MockMvc} harness exists in this codebase — {@link ClientErrorController} is
 * exercised directly as a plain Java call against a {@link MockHttpServletRequest}, same style as
 * every other controller test here (see {@code ConversionWebhookControllerTest} class javadoc).
 *
 * <p>Covers the task brief's required minimum: an unauthenticated report is accepted (202, empty
 * body); an oversized body and a garbage/malformed body both still return 202 and are dropped
 * before ever reaching the WARN log in full; and over-long fields are truncated server-side
 * regardless of the client's own caps.
 */
class ClientErrorControllerTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private ClientErrorController controller;
    private ListAppender<ILoggingEvent> logAppender;
    private Logger controllerLogger;

    @BeforeEach
    void setUp() {
        controller = new ClientErrorController(objectMapper);

        controllerLogger = (Logger) LoggerFactory.getLogger(ClientErrorController.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        controllerLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        controllerLogger.detachAppender(logAppender);
    }

    private static MockHttpServletRequest postRequest(String body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/client-errors");
        if (body != null) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            request.setContent(bytes);
            request.addHeader("Content-Length", String.valueOf(bytes.length));
        }
        request.setContentType("application/json");
        return request;
    }

    @Test
    @DisplayName("A well-formed report with no Authorization header is accepted: 202, empty body")
    void unauthenticatedReport_accepted() {
        String body =
                "{"
                        + "\"message\":\"Cannot read properties of undefined\","
                        + "\"stack\":\"TypeError: ...\\n  at Component (App.tsx:42)\","
                        + "\"componentStack\":\"\\n    in DealRoom\\n    in ErrorBoundary\","
                        + "\"pathname\":\"/deals/abc123\","
                        + "\"buildId\":\"a1b2c3d\","
                        + "\"userAgent\":\"Mozilla/5.0\""
                        + "}";
        // No Authorization header set at all -- this is the point of the test.
        MockHttpServletRequest request = postRequest(body);
        assertNull(request.getHeader("Authorization"));

        ResponseEntity<Void> response = controller.report(request);

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertNull(response.getBody());

        String logged = singleWarnMessage();
        assertTrue(logged.contains("[CLIENT_ERROR_REPORT]"));
        assertTrue(logged.contains("pathname=/deals/abc123"));
        assertTrue(logged.contains("buildId=a1b2c3d"));
    }

    @Test
    @DisplayName("A body over 16 KB still returns 202 and is dropped before any parsing/logging of its content")
    void oversizedBody_returns202_notPersistedOrLoggedInFull() {
        String hugeMessage = "x".repeat(20 * 1024); // 20 KB, well over the 16 KB cap
        String body = "{\"message\":\"" + hugeMessage + "\",\"pathname\":\"/somewhere\"}";
        MockHttpServletRequest request = postRequest(body);

        ResponseEntity<Void> response = controller.report(request);

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertNull(response.getBody());

        String logged = singleWarnMessage();
        assertTrue(logged.contains("[CLIENT_ERROR_REPORT]"));
        assertTrue(logged.contains("dropped"));
        // The oversized payload's own content must never appear in the log.
        assertTrue(!logged.contains("xxxxxxxxxx"));
        assertTrue(!logged.contains("/somewhere"));
    }

    @Test
    @DisplayName("Garbage/malformed JSON still returns 202 and is dropped, never logged in full")
    void malformedJson_returns202_dropped() {
        MockHttpServletRequest request = postRequest("{not: valid json,,,");

        ResponseEntity<Void> response = controller.report(request);

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertNull(response.getBody());

        String logged = singleWarnMessage();
        assertTrue(logged.contains("[CLIENT_ERROR_REPORT]"));
        assertTrue(logged.contains("dropped"));
    }

    @Test
    @DisplayName("A JSON array body (not an object) still returns 202 and is dropped")
    void nonObjectJson_returns202_dropped() {
        MockHttpServletRequest request = postRequest("[1,2,3]");

        ResponseEntity<Void> response = controller.report(request);

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
    }

    @Test
    @DisplayName("An empty body still returns 202 and produces no report log line")
    void emptyBody_returns202_noLog() {
        MockHttpServletRequest request = postRequest("");

        ResponseEntity<Void> response = controller.report(request);

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertTrue(logAppender.list.isEmpty());
    }

    @Test
    @DisplayName("Over-long fields are truncated server-side regardless of what the client sent")
    void overLongFields_areTruncatedServerSide() throws Exception {
        String longMessage = "m".repeat(600); // client cap is 500
        String longStack = "s".repeat(5000); // client cap is 4000
        String longComponentStack = "c".repeat(5000); // client cap is 4000
        String longPathname = "/" + "p".repeat(300); // client cap is 200
        String longBuildId = "b".repeat(100); // client cap is 64
        String longUserAgent = "u".repeat(400); // client cap is 300

        String body =
                objectMapper.writeValueAsString(
                        new java.util.LinkedHashMap<>() {
                            {
                                put("message", longMessage);
                                put("stack", longStack);
                                put("componentStack", longComponentStack);
                                put("pathname", longPathname);
                                put("buildId", longBuildId);
                                put("userAgent", longUserAgent);
                            }
                        });

        ResponseEntity<Void> response = controller.report(postRequest(body));

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());

        String logged = singleWarnMessage();
        // Every truncated field's logged length must be capped, never the raw over-long value.
        assertTrue(!logged.contains("m".repeat(501)));
        assertTrue(!logged.contains("s".repeat(4001)));
        assertTrue(!logged.contains("c".repeat(4001)));
        assertTrue(!logged.contains("p".repeat(201)));
        assertTrue(!logged.contains("b".repeat(65)));
        assertTrue(!logged.contains("u".repeat(301)));
        assertTrue(logged.contains("m".repeat(500)));
    }

    @Test
    @DisplayName("pathname is truncated to the path only -- query string and hash are stripped server-side")
    void pathname_queryAndHashStrippedServerSide() {
        String body = "{\"pathname\":\"/deals/abc?token=secret&deal=42#panel\"}";

        ResponseEntity<Void> response = controller.report(postRequest(body));

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        String logged = singleWarnMessage();
        assertTrue(logged.contains("pathname=/deals/abc "));
        assertTrue(!logged.contains("token=secret"));
        assertTrue(!logged.contains("deal=42"));
    }

    @Test
    @DisplayName("Response body is always empty -- submitted content is never echoed back")
    void response_neverEchoesSubmittedContent() {
        String body = "{\"message\":\"super-secret-marker-xyz\"}";

        ResponseEntity<Void> response = controller.report(postRequest(body));

        assertNull(response.getBody());
    }

    private String singleWarnMessage() {
        assertEquals(1, logAppender.list.size(), "expected exactly one log line");
        ILoggingEvent event = logAppender.list.get(0);
        assertEquals(Level.WARN, event.getLevel());
        return event.getFormattedMessage();
    }
}
