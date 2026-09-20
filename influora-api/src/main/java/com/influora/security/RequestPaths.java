package com.influora.security;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import org.springframework.web.util.UriUtils;

/**
 * The ONE place a servlet filter turns a request into a path it can match on (EV-004).
 *
 * <p>{@link HttpServletRequest#getRequestURI()} is the RAW request line: still percent-encoded,
 * still carrying matrix parameters, duplicate slashes and dot segments, and still prefixed by the
 * (possibly itself encoded) context path. Spring MVC routes on the DECODED, segment-normalised
 * path, so any filter that string-matches the raw URI can be walked around by a request that MVC
 * still dispatches to the same handler. That happened twice in {@link AuthRateLimitFilter}
 * (percent-encoding, Kabir NEW-1; matrix params, CR-11 L-7) and a third time in
 * {@link InternalServiceTokenFilter}, where {@code /api/v1/%69nternal/meera/turns/release} skipped
 * the service-token gate entirely and reached the controller with an ordinary user JWT.
 *
 * <p>{@link #normalize} therefore: percent-decodes repeatedly until stable (so {@code %2569}
 * decodes all the way to {@code i}), maps {@code \} to {@code /}, strips matrix parameters from
 * every segment, drops empty and {@code .} segments, resolves {@code ..}, and drops a trailing
 * slash. {@link #pathWithinApplication} additionally strips the context path. Matching helpers
 * compare case-insensitively on segment boundaries.
 *
 * <p>A path that cannot be normalised (malformed escape, too many encoding layers, control
 * characters, {@code ..} above the root) raises {@link UnnormalisablePathException}. Callers that
 * guard something must fail CLOSED on it — {@link #isUnder} does so for them.
 *
 * <p>{@code RequestPathsArchitectureTest} enforces that every {@code OncePerRequestFilter} in
 * this module that matches on a path goes through this class.
 */
public final class RequestPaths {

    /**
     * {@code server.servlet.context-path}. Stripped only when the container reports an EMPTY
     * context path (unit tests using {@code MockHttpServletRequest}, or a deploy that mounts the
     * app at root) so a {@code /api/v1/...} request URI still yields the application path.
     */
    public static final String APP_CONTEXT_PATH = "/api/v1";

    /** More layers than this is not a real client — it is someone probing the decoder. */
    private static final int MAX_DECODE_PASSES = 4;

    private RequestPaths() {}

    /** Thrown when a path cannot be reduced to a single canonical form. */
    public static final class UnnormalisablePathException extends IllegalArgumentException {
        UnnormalisablePathException(String message) {
            super(message);
        }
    }

    /**
     * Canonical form of a raw path: fully decoded, matrix params stripped, no empty/dot segments,
     * no trailing slash, always starting with {@code /}. Case is preserved.
     */
    public static String normalize(String rawPath) {
        if (rawPath == null) {
            throw new UnnormalisablePathException("null path");
        }
        String decoded = rawPath;
        for (int pass = 0; ; pass++) {
            String next;
            try {
                next = UriUtils.decode(decoded, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                throw new UnnormalisablePathException("malformed percent-escape");
            }
            if (next.equals(decoded)) {
                break;
            }
            if (pass >= MAX_DECODE_PASSES) {
                throw new UnnormalisablePathException("too many encoding layers");
            }
            decoded = next;
        }
        for (int i = 0; i < decoded.length(); i++) {
            if (Character.isISOControl(decoded.charAt(i))) {
                throw new UnnormalisablePathException("control character in path");
            }
        }
        decoded = decoded.replace('\\', '/');

        Deque<String> segments = new ArrayDeque<>();
        for (String segment : decoded.split("/")) {
            int semi = segment.indexOf(';');
            String value = semi < 0 ? segment : segment.substring(0, semi);
            if (value.isEmpty() || value.equals(".")) {
                continue;
            }
            if (value.equals("..")) {
                if (segments.isEmpty()) {
                    throw new UnnormalisablePathException("path escapes root");
                }
                segments.removeLast();
                continue;
            }
            segments.addLast(value);
        }
        return "/" + String.join("/", segments);
    }

    /**
     * The normalised path with the context path removed — the path the application routes on
     * (e.g. {@code /internal/meera/turns/release}).
     */
    public static String pathWithinApplication(HttpServletRequest request) {
        String path = normalize(request.getRequestURI());
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty()) {
            String normalisedContext = normalize(contextPath);
            if (!normalisedContext.equals("/")) {
                return stripPrefix(path, normalisedContext);
            }
        }
        return stripPrefix(path, APP_CONTEXT_PATH);
    }

    /**
     * Whether the request targets {@code prefix} or anything below it, case-insensitively, on a
     * segment boundary ({@code /internal} matches {@code /internal} and {@code /internal/x}, not
     * {@code /internals}). Checks both the normalised request URI and the container's own
     * servlet-path view, and answers {@code true} — fail CLOSED — when either cannot be
     * normalised, so an undecodable path is treated as inside every guarded prefix.
     */
    public static boolean isUnder(HttpServletRequest request, String prefix) {
        try {
            if (pathIsUnder(pathWithinApplication(request), prefix)) {
                return true;
            }
            String servletView = nullToEmpty(request.getServletPath()) + nullToEmpty(request.getPathInfo());
            return !servletView.isEmpty() && pathIsUnder(normalize(servletView), prefix);
        } catch (UnnormalisablePathException e) {
            return true;
        }
    }

    /** Segment-boundary, case-insensitive prefix test on two already-normalised paths. */
    public static boolean pathIsUnder(String normalisedPath, String prefix) {
        String p = normalisedPath.toLowerCase(Locale.ROOT);
        String root = normalize(prefix).toLowerCase(Locale.ROOT);
        if (root.equals("/")) {
            return true;
        }
        return p.equals(root) || p.startsWith(root + "/");
    }

    private static String stripPrefix(String path, String prefix) {
        if (path.equals(prefix)) {
            return "/";
        }
        if (path.startsWith(prefix + "/")) {
            return path.substring(prefix.length());
        }
        return path;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
