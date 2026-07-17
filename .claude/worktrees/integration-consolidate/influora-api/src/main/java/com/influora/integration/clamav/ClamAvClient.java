package com.influora.integration.clamav;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * S7 (2026-07-15, Priya approval) — minimal {@code clamd} client speaking the raw INSTREAM
 * protocol (https://docs.clamav.net/manual/Usage/Scanning.html#clamd), built on {@code
 * java.net.Socket} only.
 *
 * <p><b>Why hand-rolled instead of a Maven dependency:</b> Priya's approval was for "a ClamAV
 * client dependency", but this repo's verification runs Maven in offline mode ({@code mvn -o}) and
 * no `clamav`/`clamd` artifact is present in the local {@code .m2} cache — adding one as a new
 * {@code <dependency>} would make {@code test-compile} fail immediately in this environment with
 * no way to resolve it, and there was no network access available in this session to fetch one.
 * The INSTREAM protocol is ~15 lines of framing (a 4-byte big-endian chunk-length prefix per chunk,
 * a zero-length chunk to terminate, then a NUL/newline-terminated text reply) — implementing it
 * directly avoids the offline-resolution risk entirely while still talking to a real {@code clamd}
 * daemon. Flagged for Priya: swap in a vetted client library later if/when dependency resolution
 * is available, but functionally this already IS a real ClamAV integration, not a stub.
 */
public class ClamAvClient {

    private static final byte[] INSTREAM_COMMAND = "zINSTREAM\0".getBytes(StandardCharsets.US_ASCII);
    private static final int CHUNK_SIZE = 8192;

    private final String host;
    private final int port;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    public ClamAvClient(String host, int port, int connectTimeoutMs, int readTimeoutMs) {
        this.host = host;
        this.port = port;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
    }

    /**
     * Streams {@code input} to {@code clamd} over INSTREAM and parses the reply. Never buffers the
     * whole file in heap — reads and forwards {@link #CHUNK_SIZE} at a time (same discipline as
     * {@code LimitedInputStream}/{@code R2StorageService#putStream}).
     */
    public ScanResult scan(InputStream input) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
            socket.setSoTimeout(readTimeoutMs);

            OutputStream out = socket.getOutputStream();
            out.write(INSTREAM_COMMAND);
            out.flush();

            byte[] buffer = new byte[CHUNK_SIZE];
            int read;
            while ((read = input.read(buffer)) > 0) {
                writeChunkLength(out, read);
                out.write(buffer, 0, read);
            }
            writeChunkLength(out, 0); // zero-length chunk terminates the stream
            out.flush();

            String response = readResponse(socket.getInputStream());
            return ScanResult.fromClamdResponse(response);
        }
    }

    private static void writeChunkLength(OutputStream out, int length) throws IOException {
        out.write(new byte[] {
            (byte) (length >>> 24), (byte) (length >>> 16), (byte) (length >>> 8), (byte) length
        });
    }

    private static String readResponse(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) > 0) {
            buffer.write(b);
        }
        return buffer.toString(StandardCharsets.US_ASCII).trim();
    }

    /** Outcome of a single INSTREAM scan. Exactly one of clean/infected/error is true. */
    public record ScanResult(boolean clean, boolean infected, String detail) {

        public static ScanResult fromClamdResponse(String response) {
            if (response == null || response.isBlank()) {
                return new ScanResult(false, false, "Empty response from clamd");
            }
            if (response.contains("FOUND")) {
                return new ScanResult(false, true, response);
            }
            if (response.contains("OK")) {
                return new ScanResult(true, false, response);
            }
            return new ScanResult(false, false, "Unrecognized clamd response: " + response);
        }
    }
}
