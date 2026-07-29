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
 * <p><b>This controller always returns 202 with an empty body.</b> It exists to catch failures; a
 * report that fails to parse, or is garbage, or is hostile, must never be able to cause one of its
 * own. Every failure mode inside {@link #handle} is caught in {@link #report} and swallowed —
 * dropped server-side, never surfaced to the caller.
 *
 * <p>[SEC: Kabir CR-11 red-team, M-6] <b>The caller can still see a 4xx, and the previous wording
 * here ("never a 4xx") was false one layer up.</b> {@code AuthRateLimitFilter} sits in FRONT of this
 * controller and returns 429 with a JSON error body for this exact path. The invariant this class
 * can honestly promise is scoped to itself, which is what the sentence above now says.
 *
 * <p>Practical impact on the client is nil — it swallows every failure by construction. The cost is
 * diagnostic, and it is worth understanding rather than papering over: reports are dropped precisely
 * when volume is high, which is exactly when a render bug is hitting many users at once — the
 * failure mode CR-11 was built to catch. The alternative (exempting this path from the limiter) is
 * strictly worse: per-IP throttling is this endpoint's only abuse defence, and it is unauthenticated
 * and writes to the log. So the 429 stays and the claim is corrected instead.
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

        // Redaction runs on the free-text fields only (M-5). `pathname`/`buildId`/`userAgent` are
        // structurally constrained and would only lose fidelity to a false positive.
        String message = truncate(redactSecrets(textOf(node, "message")), MAX_MESSAGE);
        String stack = truncate(redactSecrets(textOf(node, "stack")), MAX_STACK);
        String componentStack =
                truncate(redactSecrets(textOf(node, "componentStack")), MAX_COMPONENT_STACK);
        String pathname = truncate(pathOnly(textOf(node, "pathname")), MAX_PATHNAME);
        String buildId = truncate(textOf(node, "buildId"), MAX_BUILD_ID);
        String userAgent = truncate(textOf(node, "userAgent"), MAX_USER_AGENT);

        // [CLIENT_ERROR_REPORT] is the stable, greppable marker the contract requires. Every value
        // is truncated, control-stripped, secret-redacted AND quoted — see quote()'s javadoc for why
        // the quoting is the part that actually stops field forging in this logfmt line.
        log.warn(
                "[CLIENT_ERROR_REPORT] pathname={} buildId={} userAgent={} message={} stack={} componentStack={}",
                quote(pathname),
                quote(buildId),
                quote(userAgent),
                quote(message),
                quote(stack),
                quote(componentStack));
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
     * unauthenticated and anyone can post to it.
     *
     * <p>[SEC: Kabir CR-11 red-team, H-3(iii)] The strip is {@code [\p{Cc}\p{Cf}\p{Zl}\p{Zp}]}, not
     * {@code \p{Cntrl}}. Java's POSIX classes are ASCII-scoped without
     * {@code UNICODE_CHARACTER_CLASS}, so {@code \p{Cntrl}} is only {@code [\x00-\x1F\x7F]} and
     * these survived it:
     * <ul>
     *   <li><b>U+0085 NEL, U+2028, U+2029</b> — not line breaks to {@code BufferedReader}, but they
     *       ARE to {@code java.util.Scanner} and to Java/JS regex in MULTILINE mode, so any parser
     *       in that class sees forged lines.
     *   <li><b>U+202E RIGHT-TO-LEFT OVERRIDE</b> (category {@code Cf}, not {@code Cc}) — a
     *       Trojan-Source line. Everything after it renders reversed in a bidi-aware terminal or log
     *       viewer, so the operator reads something other than what was logged. This one needs no
     *       downstream parser to do harm; it attacks the human reading the log.
     * </ul>
     *
     * <p>Order matters and is deliberate: sanitise first, THEN substring, so truncation cannot
     * bisect an escape and leave a partial artefact.
     */
    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String sanitized = value.replaceAll("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]", " ");
        return sanitized.length() <= maxLength ? sanitized : sanitized.substring(0, maxLength);
    }

    /**
     * Token-shaped redaction over free-text fields before they are logged.
     *
     * <p>[SEC: Kabir CR-11 red-team, M-5] There is no <em>known</em> leak today — the three
     * constructed {@code Error}s in {@code src/lib/api.ts} carry status codes and fixed strings, and
     * React's {@code componentStack} is component display names only. But the property holding that
     * up is "nobody ever throws an {@code Error} whose message contains a secret", which is enforced
     * nowhere, is one careless {@code new Error(`failed: ${JSON.stringify(response)}`)} away from
     * being false, and would deposit the result into a log that anyone on the internet can also
     * write to. This converts an unenforced invariant into an actual control.
     *
     * <p>Deliberately narrow — JWTs, Razorpay keys, and long opaque runs. It is a safety net over
     * accidental inclusion, not a general DLP pass, and it must never become expensive enough to
     * matter on a path that exists to absorb crash traffic.
     */
    private static String redactSecrets(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        return value
                // JWTs — header.payload, enough to be unmistakable and not match ordinary prose.
                .replaceAll("eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}", "[REDACTED_JWT]")
                // Razorpay key/secret ids, live and test.
                .replaceAll("rzp_(live|test)_[A-Za-z0-9]+", "[REDACTED_RZP]")
                // Long opaque hex/base64ish runs — API keys, session ids, signatures.
                .replaceAll("\\b[A-Fa-f0-9]{32,}\\b", "[REDACTED_HEX]");
    }

    /**
     * Quotes a value for the space-delimited {@code key=value} log line.
     *
     * <p>[SEC: Kabir CR-11 red-team, H-3(i)(ii)] Stripping control characters was the wrong control
     * for this log format. It correctly prevents <em>additional lines</em>, but neither a space nor
     * an {@code =} is a control character, and the format — both this marker's own
     * {@code pathname={} buildId={} …} and logback's surrounding
     * {@code level=… correlationId=… logger=… msg=…} — is logfmt.
     *
     * <p>So an unquoted value gave an attacker two primitives. {@code pathname} is logged FIRST and
     * {@code pathOnly} does not touch spaces, so {@code /x buildId=trusted userAgent=Googlebot}
     * forged every later field of this marker. And because the whole line is logfmt, injected
     * content lands inside {@code msg=} — whose value terminates at the first space for Loki's
     * {@code logfmt}, Vector's {@code key_value}, or Fluent Bit — letting an attacker emit a forged
     * {@code correlationId=} (poisoning triage against a real incident id) or make content appear to
     * originate from {@code logger=com.influora.web.AuthController}.
     *
     * <p>Latent rather than live today, because nothing currently parses these logs — but a
     * crash-report sink is exactly the thing someone eventually points a shipper at, and the fix is
     * one wrapper.
     */
    private static String quote(String value) {
        if (value == null) {
            return "-";
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
