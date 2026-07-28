package com.influora.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * CR-11 client crash-report sink ({@code wiki/tech/cr-11-client-error-contract.md}, LOCKED
 * contract — do not change this shape unilaterally). The SPA's {@code ErrorBoundary} posts here so
 * a render crash is visible on the server instead of only in a devtools console that nobody had
 * open at the moment of blanking.
 *
 * <p><b>Always 202, always empty body, never a 4xx.</b> This endpoint exists to catch failures; a
 * report that fails to parse, or is garbage, or is hostile, must never be able to cause one of its
 * own. Every failure mode inside {@link #handle} is caught in {@link #report} and swallowed —
 * dropped server-side, never surfaced to the caller.
 *
 * <p><b>Auth is optional by design.</b> Not wired into {@code AuthPrincipal}/JWT at all — a crash on
 * the public portfolio page or before login is exactly the crash nobody else can see, and the
 * contract requires this to work with no {@code Authorization} header. See the {@code POST
 * /client-errors} permit in {@code SecurityConfig}.
 *
 * <p><b>Body is read and size-capped manually</b> ({@link #readCapped}) instead of via a
 * {@code @RequestBody} DTO — this endpoint is unauthenticated and anyone can post to it, so the 16 KB
 * cap and JSON parsing both have to happen under our own control, before Spring's
 * {@code HttpMessageConverter}/{@code GlobalExceptionHandler} machinery (which would otherwise turn a
 * malformed body into a 400) ever gets involved.
 *
 * <p><b>The server re-truncates every field</b> ({@link #truncate}) regardless of the client-side
 * caps documented in the contract — those are a courtesy, not a control. Truncation also strips
 * control characters (including CR/LF) so a submitted value can never forge additional WARN log
 * lines. {@code pathname} additionally has anything from the first {@code ?} or {@code #} onward
 * cut server-side ({@link #pathOnly}) as defense in depth for the contract's "pathname only, never
 * the full URL" rule, even though the client is already required to send only
 * {@code location.pathname}.
 *
 * <p>Rate limiting is enforced upstream by {@link com.influora.security.AuthRateLimitFilter}'s
 * {@code client-errors} bucket (per-IP, shared window) — this class has no throttling logic of its
 * own.
 */
@RestController
@RequestMapping("/client-errors")
public class ClientErrorController {

    private static final Logger log = LoggerFactory.getLogger(ClientErrorController.class);

    /** Contract: "Reject > 16 KB bodies." */
    static final int MAX_BODY_BYTES = 16 * 1024;

    static final int MAX_MESSAGE = 500;
    static final int MAX_STACK = 4000;
    static final int MAX_COMPONENT_STACK = 4000;
    static final int MAX_PATHNAME = 200;
    static final int MAX_BUILD_ID = 64;
    static final int MAX_USER_AGENT = 300;

    private final ObjectMapper objectMapper;

    public ClientErrorController(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostMapping
    public ResponseEntity<Void> report(HttpServletRequest request) {
        try {
            handle(request);
        } catch (Exception ex) {
            // Belt-and-suspenders on top of the specific catches inside handle(): whatever went
            // wrong, this endpoint still must not fail. Never logs the exception's own message —
            // it may embed submitted content — only its class name.
            log.warn("[CLIENT_ERROR_REPORT] dropped: unexpected {} while handling report",
                    ex.getClass().getSimpleName());
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }

    private void handle(HttpServletRequest request) throws IOException {
        byte[] body = readCapped(request);
        if (body == null) {
            log.warn("[CLIENT_ERROR_REPORT] dropped: body exceeds {} bytes", MAX_BODY_BYTES);
            return;
        }
        if (body.length == 0) {
            return;
        }

        JsonNode node;
        try {
            node = objectMapper.readTree(body);
        } catch (Exception ex) {
            log.warn("[CLIENT_ERROR_REPORT] dropped: malformed JSON body");
            return;
        }
        if (node == null || !node.isObject()) {
            log.warn("[CLIENT_ERROR_REPORT] dropped: body is not a JSON object");
            return;
        }

        String message = truncate(textOf(node, "message"), MAX_MESSAGE);
        String stack = truncate(textOf(node, "stack"), MAX_STACK);
        String componentStack = truncate(textOf(node, "componentStack"), MAX_COMPONENT_STACK);
        String pathname = truncate(pathOnly(textOf(node, "pathname")), MAX_PATHNAME);
        String buildId = truncate(textOf(node, "buildId"), MAX_BUILD_ID);
        String userAgent = truncate(textOf(node, "userAgent"), MAX_USER_AGENT);

        // [CLIENT_ERROR_REPORT] is the stable, greppable marker the contract requires. Every value
        // logged here is the already-truncated/sanitized copy above, never the raw client bytes.
        log.warn(
                "[CLIENT_ERROR_REPORT] pathname={} buildId={} userAgent={} message={} stack={} componentStack={}",
                pathname, buildId, userAgent, message, stack, componentStack);
    }

    /**
     * Reads at most {@link #MAX_BODY_BYTES} + 1 bytes off the request body — enough to detect an
     * oversized payload without ever buffering more than ~16 KB into memory — and returns
     * {@code null} if the body turns out to be larger than the cap (caller drops it). {@code
     * Content-Length} is checked first as a fast-path short-circuit when present, but is not relied
     * on alone: it can be absent under chunked transfer encoding, or simply wrong, so the
     * byte-counted read is the real enforcement.
     */
    private byte[] readCapped(HttpServletRequest request) throws IOException {
        long declaredLength = request.getContentLengthLong();
        if (declaredLength > MAX_BODY_BYTES) {
            return null;
        }
        byte[] buffer = new byte[MAX_BODY_BYTES + 1];
        int total = 0;
        try (InputStream in = request.getInputStream()) {
            int read;
            while (total < buffer.length && (read = in.read(buffer, total, buffer.length - total)) != -1) {
                total += read;
            }
        }
        if (total > MAX_BODY_BYTES) {
            return null;
        }
        return Arrays.copyOf(buffer, total);
    }

    /**
     * Returns the named field's text value, or {@code null} if it is absent, JSON {@code null}, or
     * not a JSON string. A wrong-typed field (e.g. a number where a string is expected) is dropped
     * silently rather than coerced or allowed to throw.
     */
    private static String textOf(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return (value != null && value.isTextual()) ? value.asText() : null;
    }

    /**
     * Defense in depth for the contract's "pathname only, never the full URL" rule — the client is
     * already required to send only {@code location.pathname}, but the server does not trust that:
     * anything from the first {@code ?} or {@code #} onward is cut regardless of what arrived.
     */
    private static String pathOnly(String value) {
        if (value == null) {
            return null;
        }
        int cut = value.length();
        int query = value.indexOf('?');
        if (query >= 0) {
            cut = Math.min(cut, query);
        }
        int hash = value.indexOf('#');
        if (hash >= 0) {
            cut = Math.min(cut, hash);
        }
        return value.substring(0, cut);
    }

    /**
     * Server-side truncation applied to every field regardless of what the client sent or claimed
     * to cap — client caps in the contract are a courtesy only, since this endpoint is
     * unauthenticated and anyone can post to it. Also strips control characters (including CR/LF)
     * so a submitted value can never forge additional WARN log lines.
     */
    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String sanitized = value.replaceAll("\\p{Cntrl}", " ");
        return sanitized.length() <= maxLength ? sanitized : sanitized.substring(0, maxLength);
    }
}
