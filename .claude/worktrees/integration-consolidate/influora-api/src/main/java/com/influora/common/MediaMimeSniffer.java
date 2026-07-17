package com.influora.common;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Magic-byte MIME detection for creator deliverable uploads (Kabir M-19-1). Validates file content
 * independently of the client-supplied {@code Content-Type} header per {@code
 * 12_CREATOR_SECURITY_SPEC.md} §5.1.
 */
public final class MediaMimeSniffer {

    private MediaMimeSniffer() {}

    /**
     * Sniffs the leading bytes of {@code input} and returns a concrete MIME type, or {@code null}
     * when the format is unrecognized.
     */
    public static String detectMimeType(InputStream input) throws IOException {
        byte[] header = input.readNBytes(16);
        if (header.length < 4) {
            return null;
        }
        if (header.length >= 3
                && (header[0] & 0xFF) == 0xFF
                && (header[1] & 0xFF) == 0xD8
                && (header[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (header.length >= 8
                && header[0] == (byte) 0x89
                && header[1] == 0x50
                && header[2] == 0x4E
                && header[3] == 0x47) {
            return "image/png";
        }
        if (header.length >= 6) {
            String gifPrefix = new String(header, 0, 6, StandardCharsets.US_ASCII);
            if ("GIF87a".equals(gifPrefix) || "GIF89a".equals(gifPrefix)) {
                return "image/gif";
            }
        }
        if (header.length >= 12
                && header[0] == 'R'
                && header[1] == 'I'
                && header[2] == 'F'
                && header[3] == 'F'
                && header[8] == 'W'
                && header[9] == 'E'
                && header[10] == 'B'
                && header[11] == 'P') {
            return "image/webp";
        }
        if (header.length >= 12
                && header[4] == 'f'
                && header[5] == 't'
                && header[6] == 'y'
                && header[7] == 'p') {
            String brand = new String(header, 8, 4, StandardCharsets.US_ASCII);
            if ("qt  ".equals(brand)) {
                return "video/quicktime";
            }
            return "video/mp4";
        }
        if ((header[0] & 0xFF) == 0x1A
                && (header[1] & 0xFF) == 0x45
                && (header[2] & 0xFF) == 0xDF
                && (header[3] & 0xFF) == 0xA3) {
            return "video/webm";
        }
        return null;
    }

    public static boolean isAllowedMediaMime(String mime) {
        if (mime == null) {
            return false;
        }
        return mime.startsWith("image/") || mime.startsWith("video/");
    }

    /** Declared multipart {@code Content-Type} must match sniffed bytes (same image/video family). */
    public static boolean mimeTypesCompatible(String declared, String sniffed) {
        if (declared == null || sniffed == null) {
            return false;
        }
        String declaredFamily = mediaFamily(declared);
        String sniffedFamily = mediaFamily(sniffed);
        return declaredFamily != null && declaredFamily.equals(sniffedFamily);
    }

    private static String mediaFamily(String mime) {
        if (mime.startsWith("image/")) {
            return "image";
        }
        if (mime.startsWith("video/")) {
            return "video";
        }
        return null;
    }
}
