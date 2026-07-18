package com.influora.service;

import com.influora.web.dto.deal.DealDtos.DealMessageResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * In-memory fan-out registry backing {@code GET /deals/{dealId}/messages/stream}. Holds one
 * {@link SseEmitter} list per deal (collaboration id) and pushes newly-sent {@link
 * DealMessageResponse} rows to every open connection for that deal — see {@link
 * com.influora.web.DealController#streamMessages} for the authorization gate (must run BEFORE
 * {@link #register}) and {@link com.influora.service.DealService#sendMessage} for the publish
 * call site.
 *
 * <p><b>SINGLE-INSTANCE DESIGN — deliberate MVP scope decision by Priya, not an oversight.</b>
 * Emitters live only in this JVM's heap ({@code ConcurrentHashMap}), never persisted or shared.
 * If the API ever runs with more than one replica behind a load balancer, the brand and creator
 * on the same deal can be routed to different instances; each instance only knows about the
 * emitters registered locally, so a message sent while the counterparty is connected to a
 * *different* instance is silently dropped for them — no error, no retry, the stream just goes
 * quiet until their next poll/reconnect. This is acceptable for the current single-replica
 * deployment. The documented upgrade path when horizontal scaling is needed: move {@link
 * #publish} to a shared pub/sub fan-out (Redis Pub/Sub, or MySQL/Postgres LISTEN/NOTIFY) keyed
 * by dealId, with every instance subscribing and re-broadcasting to its own local emitters only.
 * Sticky sessions alone do not fix this — a client reconnect (mobile background/foreground, wifi
 * flap, deploy rolling restart) can still land on a different instance mid-conversation.
 */
@Component
public class DealMessageStreamRegistry {

    private static final Logger log = LoggerFactory.getLogger(DealMessageStreamRegistry.class);

    /** 30 minutes — matches the SseEmitter timeout the controller constructs. */
    public static final long EMITTER_TIMEOUT_MS = 30L * 60 * 1000;

    private final Map<String, CopyOnWriteArrayList<SseEmitter>> emittersByDealId =
            new ConcurrentHashMap<>();

    /**
     * Registers an already-created emitter for {@code dealId} and wires its lifecycle callbacks
     * (completion/timeout/error all deregister) so the map never leaks a dead emitter. Callers
     * MUST authorize the caller as a party to the deal (see {@code DealService}'s existing
     * ownership check) BEFORE calling this — this method performs no auth of its own.
     */
    public void register(String dealId, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> emitters =
                emittersByDealId.computeIfAbsent(dealId, key -> new CopyOnWriteArrayList<>());
        emitters.add(emitter);

        emitter.onCompletion(() -> deregister(dealId, emitter));
        emitter.onTimeout(() -> deregister(dealId, emitter));
        emitter.onError(ex -> deregister(dealId, emitter));
    }

    /**
     * Sends {@code messageDto} (the SAME DTO shape returned by {@code GET
     * /deals/{dealId}/messages} and by the send-message response) to every open emitter for
     * {@code dealId}. Any emitter whose send throws (client gone, broken pipe, etc.) is dropped
     * from the registry immediately — never left to accumulate.
     */
    public void publish(String dealId, DealMessageResponse messageDto) {
        List<SseEmitter> emitters = emittersByDealId.get(dealId);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name("deal-message").data(messageDto));
            } catch (Exception ex) {
                log.debug("Dropping dead SSE emitter for deal {}: {}", dealId, ex.toString());
                deregister(dealId, emitter);
            }
        }
    }

    /** Test/introspection helper — number of live emitters currently registered for a deal. */
    int emitterCount(String dealId) {
        CopyOnWriteArrayList<SseEmitter> emitters = emittersByDealId.get(dealId);
        return emitters == null ? 0 : emitters.size();
    }

    private void deregister(String dealId, SseEmitter emitter) {
        emittersByDealId.computeIfPresent(
                dealId,
                (key, emitters) -> {
                    emitters.remove(emitter);
                    return emitters.isEmpty() ? null : emitters;
                });
    }
}
