package com.influora.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Kabir M-19-1 — magic-byte MIME sniffing for deliverable uploads. */
class MediaMimeSnifferTest {

    private static final byte[] MINIMAL_MP4 =
            new byte[] {
                0x00, 0x00, 0x00, 0x1c, 0x66, 0x74, 0x79, 0x70, 0x69, 0x73, 0x6f, 0x6d, 0x00, 0x00,
                0x00, 0x00
            };

  private static final byte[] MINIMAL_PNG =
      new byte[] {
        (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x00
      };

    @Test
    @DisplayName("detectMimeType: recognizes MP4 ftyp header")
    void detectsMp4() throws Exception {
        assertEquals("video/mp4", MediaMimeSniffer.detectMimeType(new ByteArrayInputStream(MINIMAL_MP4)));
    }

    @Test
    @DisplayName("detectMimeType: recognizes PNG header")
    void detectsPng() throws Exception {
        assertEquals("image/png", MediaMimeSniffer.detectMimeType(new ByteArrayInputStream(MINIMAL_PNG)));
    }

    @Test
    @DisplayName("detectMimeType: returns null for ZIP payload")
    void rejectsZipPayload() throws Exception {
        byte[] zip = new byte[] {0x50, 0x4B, 0x03, 0x04, 0x00, 0x00, 0x00, 0x00};
        assertNull(MediaMimeSniffer.detectMimeType(new ByteArrayInputStream(zip)));
    }

    @Test
    @DisplayName("mimeTypesCompatible: requires same image/video family")
    void mimeFamilyCompatibility() {
        assertTrue(MediaMimeSniffer.mimeTypesCompatible("video/mp4", "video/quicktime"));
        assertTrue(MediaMimeSniffer.mimeTypesCompatible("image/jpeg", "image/png"));
        assertFalse(MediaMimeSniffer.mimeTypesCompatible("video/mp4", "image/png"));
    }
}
