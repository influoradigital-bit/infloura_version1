package com.influora.common;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Caps bytes read from an underlying stream (Kabir M-19-3). Prevents heap / R2 amplification when a
 * multipart part declares a small size but streams more data than allowed.
 */
public final class LimitedInputStream extends FilterInputStream {

    private final long maxBytes;
    private long bytesRead;

    public LimitedInputStream(InputStream in, long maxBytes) {
        super(in);
        if (maxBytes < 0) {
            throw new IllegalArgumentException("maxBytes must be >= 0");
        }
        this.maxBytes = maxBytes;
    }

    @Override
    public int read() throws IOException {
        int b = super.read();
        if (b >= 0) {
            checkLimit(1);
        }
        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int n = super.read(b, off, len);
        if (n > 0) {
            checkLimit(n);
        }
        return n;
    }

    @Override
    public long skip(long n) throws IOException {
        long skipped = super.skip(n);
        if (skipped > 0) {
            checkLimit(skipped);
        }
        return skipped;
    }

    private void checkLimit(long additional) throws IOException {
        bytesRead += additional;
        if (bytesRead > maxBytes) {
            throw new IOException("Stream exceeds maximum allowed size of " + maxBytes + " bytes");
        }
    }
}
