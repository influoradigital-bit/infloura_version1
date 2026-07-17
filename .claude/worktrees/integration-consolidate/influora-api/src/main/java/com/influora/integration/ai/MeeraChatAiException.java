package com.influora.integration.ai;

/**
 * Wraps any transport/auth/parsing failure talking to influora-ai's {@code POST /chat} endpoint, a
 * non-200 response, or a stream that ended with an {@code error} SSE event / no usable assistant
 * text (Wave 3 task A4). Deliberately a single unchecked exception type, mirroring {@link
 * AnalyzeSiteAiException}/{@link BrandSafetyAiException} — {@code MeeraSessionService#doSendTurn}
 * catches this ONE type and propagates it so the surrounding {@code @Transactional} rolls back the
 * credit consumption and the persisted USER message rather than leaving a half-written turn (no
 * assistant reply, credit already spent) — see that method's javadoc.
 */
public class MeeraChatAiException extends RuntimeException {
    public MeeraChatAiException(String message) {
        super(message);
    }

    public MeeraChatAiException(String message, Throwable cause) {
        super(message, cause);
    }
}
