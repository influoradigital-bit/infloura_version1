package com.influora.security;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Buffers the request body so it can be read once for HMAC signature verification
 * ({@link InternalRequestVerifier}) and again by the controller's {@code @RequestBody} binding —
 * a plain {@link HttpServletRequest} input stream can only be consumed once.
 */
public class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

    private final byte[] cachedBody;

    public CachedBodyHttpServletRequest(HttpServletRequest request) throws IOException {
        super(request);
        try (var is = request.getInputStream()) {
            this.cachedBody = is.readAllBytes();
        }
    }

    public String getCachedBodyAsString() {
        return new String(cachedBody, StandardCharsets.UTF_8);
    }

    @Override
    public ServletInputStream getInputStream() {
        var byteArrayInputStream = new ByteArrayInputStream(cachedBody);
        return new ServletInputStream() {
            @Override
            public boolean isFinished() {
                return byteArrayInputStream.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener readListener) {}

            @Override
            public int read() {
                return byteArrayInputStream.read();
            }
        };
    }

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
    }
}
