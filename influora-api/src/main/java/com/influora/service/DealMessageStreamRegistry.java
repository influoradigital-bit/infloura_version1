package com.influora.service;

import com.influora.web.dto.deal.DealDtos.DealMessageResponse;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
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
 *
 * <p><b>CR-95 — reconnect replay buffer.</b> {@link #EMITTER_TIMEOUT_MS} forces every emitter to
 * disconnect every 30 minutes; the browser's {@code EventSource} auto-reconnects but there is a
 * backoff window where no emitter is registered for the deal. Anything {@link #publish}ed in that
 * window used to be silently dropped. Each published event now gets a per-deal monotonically
 * increasing id (sent as the SSE {@code id:} field), kept in a small bounded, time-boxed replay
 * buffer ({@link #REPLAY_BUFFER_MAX_EVENTS} / {@link #REPLAY_BUFFER_MAX_AGE_MS}) per deal. A
 * reconnecting client's standard {@code Last-Event-ID} header (read by {@link
 * com.influora.web.DealController#streamMessages} and passed into {@link #register(String,
 * SseEmitter, String)}) is used to replay only the events the client missed before it resumes
 * live streaming. This is a bounded gap-filler, not event sourcing — the buffer, and the deal's
 * entry in this registry, are dropped once both are empty (see {@link #deregister}).
 */
@Component
public class DealMessageStreamRegistry {

    private static final Logger log = LoggerFactory.getLogger(DealMessageStreamRegistry.class);

    /** 30 minutes — matches the SseEmitter timeout the controller constructs. */
    public static final long EMITTER_TIMEOUT_MS = 30L * 60 * 1000;

    /** Replay buffer cap — whichever bound (count or age) is hit first wins. */
    static final int REPLAY_BUFFER_MAX_EVENTS = 200;

    /** 5 minutes — comfortably longer than a normal SSE reconnect backoff. */
    static final long REPLAY_BUFFER_MAX_AGE_MS = 5L * 60 * 1000;

    private final Map<String, DealStream> streamsByDealId = new ConcurrentHashMap<>();

    /** Per-deal live emitters + bounded replay buffer + event-id sequence, guarded by its own monitor. */
    private static final class DealStream {
        final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();
        final Deque<BufferedEvent> replayBuffer = new ArrayDeque<>();
        final AtomicLong nextEventId = new AtomicLong(1);
    }

    private static final class BufferedEvent {
        final long id;
        final long publishedAtMs;
        final DealMessageResponse payload;

        BufferedEvent(long id, long publishedAtMs, DealMessageResponse payload) {
            this.id = id;
            this.publishedAtMs = publishedAtMs;
            this.payload = payload;
        }
    }

    /**
     * Registers an already-created emitter for {@code dealId} with no reconnect replay. Callers
     * MUST authorize the caller as a party to the deal (see {@code DealService}'s existing
     * ownership check) BEFORE calling this — this method performs no auth of its own.
     */
    public void register(String dealId, SseEmitter emitter) {
        register(dealId, emitter, null);
    }

    /**
     * Registers an already-created emitter for {@code dealId}, wires its lifecycle callbacks
     * (completion/timeout/error all deregister) so the map never leaks a dead emitter, and — if
     * {@code lastEventId} is non-blank (the standard SSE {@code Last-Event-ID} reconnect header) —
     * replays any buffered events published after that id BEFORE the emitter is added to the live
     * fan-out list, so nothing published concurrently during the replay can be double-delivered or
     * missed. Callers MUST authorize the caller as a party to the deal (see {@code DealService}'s
     * existing ownership check) BEFORE calling this — this method performs no auth of its own.
     */
    public void register(String dealId, SseEmitter emitter, String lastEventId) {
        DealStream stream = streamsByDealId.computeIfAbsent(dealId, key -> new DealStream());

        synchronized (stream) {
            evictExpired(stream);
            replayMissedEvents(dealId, stream, emitter, lastEventId);
            stream.emitters.add(emitter);
        }

        emitter.onCompletion(() -> deregister(dealId, emitter));
        emitter.onTimeout(() -> deregister(dealId, emitter));
        emitter.onError(ex -> deregister(dealId, emitter));
    }

    /**
     * Sends {@code messageDto} (the SAME DTO shape returned by {@code GET
     * /deals/{dealId}/messages} and by the send-message response) to every open emitter for
     * {@code dealId}, tagging it with the next per-deal sequence id and buffering it for reconnect
     * replay. Any emitter whose send throws (client gone, broken pipe, etc.) is dropped from the
     * registry immediately — never left to accumulate. A no-op if nobody has ever connected for
     * this {@code dealId} (nothing to buffer for).
     */
    public void publish(String dealId, DealMessageResponse messageDto) {
        DealStream stream = streamsByDealId.get(dealId);
        if (stream == null) {
            return;
        }

        long eventId;
        List<SseEmitter> snapshot;
        synchronized (stream) {
            eventId = stream.nextEventId.getAndIncrement();
            stream.replayBuffer.addLast(new BufferedEvent(eventId, System.currentTimeMillis(), messageDto));
            evictExpired(stream);
            // Snapshot the live emitter list BEFORE releasing the lock. A concurrent register()
            // replays buffered events (including the one just added above) and adds its emitter to
            // stream.emitters under the SAME lock — if this method released the lock and then read
            // stream.emitters live, a register() landing in that window would already show the new
            // emitter in the list, and this loop would send it the event a second time on top of the
            // replay it just received. Snapshotting here means the new emitter is either (a) not yet
            // in the list, so it's absent from this snapshot and gets the event only via replay, or
            // (b) already in the list because register() ran and released its lock first, in which
            // case its replay covered events up to a lastEventId strictly older than this one, so a
            // live send here is the correct, only delivery.
            snapshot = new ArrayList<>(stream.emitters);
        }

        for (SseEmitter emitter : snapshot) {
            try {
                emitter.send(
                        SseEmitter.event()
                                .id(Long.toString(eventId))
                                .name("deal-message")
                                .data(messageDto));
            } catch (Exception ex) {
                log.debug("Dropping dead SSE emitter for deal {}: {}", dealId, ex.toString());
                deregister(dealId, emitter);
            }
        }
    }

    /** Test/introspection helper — number of live emitters currently registered for a deal. */
    int emitterCount(String dealId) {
        DealStream stream = streamsByDealId.get(dealId);
        return stream == null ? 0 : stream.emitters.size();
    }

    private void deregister(String dealId, SseEmitter emitter) {
        DealStream stream = streamsByDealId.get(dealId);
        if (stream == null) {
            return;
        }
        synchronized (stream) {
            stream.emitters.remove(emitter);
            evictExpired(stream);
            // Nothing live and nothing left worth replaying — drop the entry so the map stays
            // bounded to deals with either an open connection or a recent (<5min) message.
            if (stream.emitters.isEmpty() && stream.replayBuffer.isEmpty()) {
                streamsByDealId.remove(dealId, stream);
            }
        }
    }

    /** Replays buffered events newer than {@code lastEventId} to {@code emitter}. Caller must hold {@code stream}'s monitor. */
    private void replayMissedEvents(
            String dealId, DealStream stream, SseEmitter emitter, String lastEventId) {
        if (lastEventId == null || lastEventId.isBlank()) {
            return;
        }
        long since;
        try {
            since = Long.parseLong(lastEventId.trim());
        } catch (NumberFormatException ex) {
            log.debug("Ignoring unparseable Last-Event-ID '{}' for deal {}", lastEventId, dealId);
            return;
        }
        for (BufferedEvent buffered : stream.replayBuffer) {
            if (buffered.id <= since) {
                continue;
            }
            try {
                emitter.send(
                        SseEmitter.event()
                                .id(Long.toString(buffered.id))
                                .name("deal-message")
                                .data(buffered.payload));
            } catch (Exception ex) {
                log.debug(
                        "Dropping dead SSE emitter for deal {} during replay: {}", dealId, ex.toString());
                emitter.completeWithError(ex);
                return;
            }
        }
    }

    /** Evicts buffered events past the count/age cap. Caller must hold {@code stream}'s monitor. */
    private void evictExpired(DealStream stream) {
        long cutoff = System.currentTimeMillis() - REPLAY_BUFFER_MAX_AGE_MS;
        while (!stream.replayBuffer.isEmpty() && stream.replayBuffer.peekFirst().publishedAtMs < cutoff) {
            stream.replayBuffer.pollFirst();
        }
        while (stream.replayBuffer.size() > REPLAY_BUFFER_MAX_EVENTS) {
            stream.replayBuffer.pollFirst();
        }
    }
}
