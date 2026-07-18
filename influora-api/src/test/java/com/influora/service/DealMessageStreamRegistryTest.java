package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.influora.domain.enums.DealMessageKind;
import com.influora.domain.enums.DealSenderType;
import com.influora.web.dto.deal.DealDtos.DealMessageResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Registry unit tests — plain in-memory {@code @Component}, no Spring context or MockitoExtension
 * needed. Covers the four behaviors flagged for QA: register-on-open, publish delivers to a
 * registered emitter, a throwing emitter is dropped (never leaked), and each lifecycle callback
 * (completion/timeout/error) deregisters.
 */
class DealMessageStreamRegistryTest {

    private static final String DEAL_ID = "deal1";

    private final DealMessageStreamRegistry registry = new DealMessageStreamRegistry();

    private static DealMessageResponse sampleMessage() {
        return new DealMessageResponse(
                "m1",
                DEAL_ID,
                DealMessageKind.text,
                "u1",
                DealSenderType.creator,
                "Hello",
                null,
                Instant.now(),
                List.of());
    }

    @Test
    @DisplayName("register: a registered emitter receives a subsequent publish")
    void testRegisterAndPublishDelivers() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);

        registry.register(DEAL_ID, emitter);
        registry.publish(DEAL_ID, sampleMessage());

        verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        assertEquals(1, registry.emitterCount(DEAL_ID));
    }

    @Test
    @DisplayName("publish: a dealId with no registered emitters is a silent no-op")
    void testPublishNoEmittersIsNoop() {
        registry.publish("no-such-deal", sampleMessage());
        assertEquals(0, registry.emitterCount("no-such-deal"));
    }

    @Test
    @DisplayName("publish: a throwing emitter is dropped from the registry; other emitters still receive it")
    void testPublishDropsThrowingEmitter() throws Exception {
        SseEmitter deadEmitter = mock(SseEmitter.class);
        SseEmitter liveEmitter = mock(SseEmitter.class);
        doThrow(new IOException("broken pipe")).when(deadEmitter).send(any(SseEmitter.SseEventBuilder.class));

        registry.register(DEAL_ID, deadEmitter);
        registry.register(DEAL_ID, liveEmitter);

        registry.publish(DEAL_ID, sampleMessage());

        verify(liveEmitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        assertEquals(1, registry.emitterCount(DEAL_ID));
    }

    @Test
    @DisplayName("register: onCompletion callback deregisters the emitter — map never leaks")
    void testDeregisterOnCompletion() {
        SseEmitter emitter = mock(SseEmitter.class);
        ArgumentCaptor<Runnable> completionCallback = ArgumentCaptor.forClass(Runnable.class);

        registry.register(DEAL_ID, emitter);
        assertEquals(1, registry.emitterCount(DEAL_ID));

        verify(emitter).onCompletion(completionCallback.capture());
        completionCallback.getValue().run();

        assertEquals(0, registry.emitterCount(DEAL_ID));
    }

    @Test
    @DisplayName("register: onTimeout callback deregisters the emitter — map never leaks")
    void testDeregisterOnTimeout() {
        SseEmitter emitter = mock(SseEmitter.class);
        ArgumentCaptor<Runnable> timeoutCallback = ArgumentCaptor.forClass(Runnable.class);

        registry.register(DEAL_ID, emitter);
        verify(emitter).onTimeout(timeoutCallback.capture());
        timeoutCallback.getValue().run();

        assertEquals(0, registry.emitterCount(DEAL_ID));
    }

    @Test
    @DisplayName("register: onError callback deregisters the emitter — map never leaks")
    void testDeregisterOnError() {
        SseEmitter emitter = mock(SseEmitter.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Consumer<Throwable>> errorCallback = ArgumentCaptor.forClass(Consumer.class);

        registry.register(DEAL_ID, emitter);
        verify(emitter).onError(errorCallback.capture());
        errorCallback.getValue().accept(new RuntimeException("boom"));

        assertEquals(0, registry.emitterCount(DEAL_ID));
    }
}
