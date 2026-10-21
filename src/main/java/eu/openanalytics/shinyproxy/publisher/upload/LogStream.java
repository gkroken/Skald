/*
 * Skald
 *
 * Copyright (C) 2026 Gard Kroken
 *
 * Built on ShinyProxy, Copyright (C) 2016-2026 Open Analytics NV.
 *
 * ===========================================================================
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the Apache License as published by
 * The Apache Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * Apache License for more details.
 *
 * You should have received a copy of the Apache License
 * along with this program.  If not, see <http://www.apache.org/licenses/>
 */
package eu.openanalytics.shinyproxy.publisher.upload;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * build.logs.stream (spec shapes.stream): Server-Sent Events over the same reads as replay.
 *
 * <p><b>Pull, not push.</b> Each stream is one virtual thread that reads committed chunks from
 * storage when its previous write has gone out, and polls the index once a second when it
 * has caught up. Nothing the build writes waits for a reader and nothing is queued for one,
 * so a slow or vanished client holds only its own thread and the page it is sending; its
 * write fails or blocks, and no one else notices.
 *
 * <p>Events: {@code line} and {@code gap} (id = the record's cursor, data = the ndjson
 * record), {@code state} when the build's or the log's state changes (the first is sent at
 * once), and {@code end} (the ndjson end record), after which the server closes. A reconnect
 * after end gets end again at once. {@code retry: 5000}, a {@code : keepalive} comment when
 * nothing else was sent for 15 s. A store that cannot be read sends {@code state} with
 * logState {@code storage_unavailable} and retries with backoff until the connection limit;
 * it does not end the stream. At the connection limit the server closes without end.
 *
 * <p>Bounds: {@link StreamSettings}; past either count the open is refused, 429
 * STREAM_LIMIT, before anything is streamed.
 */
public class LogStream {

    static final long RETRY_MS = 5000;
    private static final Logger log = LoggerFactory.getLogger(LogStream.class);

    private final BuildService service;
    private final StreamSettings settings;
    private final Duration keepalive;
    private final Duration poll;
    private final Duration maxBackoff;
    private final AtomicInteger open = new AtomicInteger();
    private final Map<String, AtomicInteger> perUser = new ConcurrentHashMap<>();

    public LogStream(BuildService service, StreamSettings settings) {
        // Backoff at most 10 s: with a read's 5 s budget the stream is never quiet past a keepalive.
        this(service, settings, Duration.ofSeconds(15), Duration.ofSeconds(1), Duration.ofSeconds(10));
    }

    /** Test seam: the spec's keepalive and poll periods, shortened. */
    LogStream(BuildService service, StreamSettings settings, Duration keepalive, Duration poll, Duration maxBackoff) {
        this.service = service;
        this.settings = settings;
        this.keepalive = keepalive;
        this.poll = poll;
        this.maxBackoff = maxBackoff;
    }

    /** Streams open on this instance now. */
    int openStreams() {
        return open.get();
    }

    /**
     * Opens a stream after {@code after}. Everything that can refuse the open -- a malformed
     * cursor, no such build, an expired log, a bound -- is decided here, before the response
     * is committed, so a refusal is the transport's envelope and not a broken stream.
     */
    public SseEmitter open(UUID contentId, UUID buildId, String user, String after) {
        BuildService.Cursor.parse(after);
        Map<String, Object> status = service.status(contentId, buildId, p -> p);
        if ("expired".equals(((Map<?, ?>) status.get("log")).get("state"))) {
            throw new TransportException(TransportError.LOG_EXPIRED,
                    "build " + buildId + "'s log was removed by retention");
        }
        AtomicInteger mine = perUser.computeIfAbsent(user, u -> new AtomicInteger());
        if (open.incrementAndGet() > settings.perInstance()) {
            open.decrementAndGet();
            throw limit("this instance already serves " + settings.perInstance() + " log streams");
        }
        if (mine.incrementAndGet() > settings.perUser()) {
            mine.decrementAndGet();
            open.decrementAndGet();
            throw limit("you already have " + settings.perUser() + " log streams open");
        }
        // The emitter's own timeout is a backstop past the connection limit, which the loop keeps.
        SseEmitter emitter = emitter(settings.connection().plusSeconds(30).toMillis());
        AtomicBoolean closed = new AtomicBoolean();
        Runnable release = () -> {
            if (closed.compareAndSet(false, true)) {
                mine.decrementAndGet();
                open.decrementAndGet();
            }
        };
        emitter.onCompletion(release);
        emitter.onTimeout(release);
        emitter.onError(t -> release.run());
        Thread.ofVirtual().name("skald-log-stream-" + buildId).start(() -> {
            try {
                run(emitter, closed, contentId, buildId, after);
            } finally {
                release.run();
            }
        });
        return emitter;
    }

    /** Test seam: the emitter a stream writes to. */
    SseEmitter emitter(long timeoutMillis) {
        return new SseEmitter(timeoutMillis);
    }

    private static TransportException limit(String why) {
        return new TransportException(TransportError.STREAM_LIMIT, why + "; close one, or read the log by replay");
    }

    private void run(SseEmitter emitter, AtomicBoolean closed, UUID contentId, UUID buildId, String after) {
        long deadline = System.nanoTime() + settings.connection().toNanos();
        String cursor = after;
        String sentState = null;
        long lastSent = System.nanoTime();
        Duration backoff = poll;
        try {
            emitter.send(SseEmitter.event().reconnectTime(RETRY_MS).comment("skald log stream"));
            while (!closed.get() && System.nanoTime() < deadline) {
                BuildService.Replay page;
                try {
                    page = service.replay(contentId, buildId, cursor, BuildService.REPLAY_DEFAULT);
                    backoff = poll;
                } catch (TransportException e) {
                    if (e.code() != TransportError.STORAGE_UNAVAILABLE) {
                        // LOG_EXPIRED or BUILD_NOT_FOUND mid-stream: the content was deleted or
                        // retention ran; nothing more will come.
                        emitter.complete();
                        return;
                    }
                    String state = "{\"logState\":\"storage_unavailable\"}";
                    if (!state.equals(sentState)) {
                        emitter.send(SseEmitter.event().name("state").data(state));
                        sentState = state;
                        lastSent = System.nanoTime();
                    }
                    // An outage is not silence: a proxy or client watching for life still sees
                    // one within a keepalive, counting the next read's budget, which may hang too.
                    long due = keepalive.minus(service.storeBudget()).toNanos();
                    long wakeAt = System.nanoTime() + backoff.toNanos();
                    while (!closed.get() && System.nanoTime() < Math.min(wakeAt, deadline)) {
                        if (System.nanoTime() - lastSent >= due) {
                            emitter.send(SseEmitter.event().comment("keepalive"));
                            lastSent = System.nanoTime();
                        }
                        sleep(Duration.ofMillis(Math.min(100, Math.max(1, poll.toMillis()))), wakeAt);
                    }
                    if (System.nanoTime() - lastSent >= due) {
                        emitter.send(SseEmitter.event().comment("keepalive"));
                        lastSent = System.nanoTime();
                    }
                    backoff = backoff.multipliedBy(2).compareTo(maxBackoff) > 0 ? maxBackoff : backoff.multipliedBy(2);
                    continue;
                }
                ObjectNode stateNode = TransportJson.JSON.createObjectNode()
                        .put("buildState", page.buildState()).put("logState", page.logState());
                String state = TransportJson.JSON.writeValueAsString(stateNode);
                if (!state.equals(sentState)) {
                    emitter.send(SseEmitter.event().name("state").data(state));
                    sentState = state;
                    lastSent = System.nanoTime();
                }
                for (ObjectNode r : page.records()) {
                    String at = r.get("cursor").asText();
                    emitter.send(SseEmitter.event().id(at).name(r.has("gap") ? "gap" : "line")
                            .data(TransportJson.JSON.writeValueAsString(r)));
                    cursor = at;
                    lastSent = System.nanoTime();
                }
                if (page.end() != null) {
                    emitter.send(SseEmitter.event().name("end").data(TransportJson.JSON.writeValueAsString(page.end())));
                    emitter.complete();
                    return;
                }
                if (page.next() != null) {
                    continue;
                }
                if (System.nanoTime() - lastSent >= keepalive.toNanos()) {
                    emitter.send(SseEmitter.event().comment("keepalive"));
                    lastSent = System.nanoTime();
                }
                sleep(poll, deadline);
            }
            // The connection limit: closed without end; the client resumes by cursor.
            emitter.complete();
        } catch (IOException | IllegalStateException e) {
            // The client went away, or the emitter was completed under us: nothing to tell anyone.
            log.debug("build {}: log stream ended: {}", buildId, e.getMessage());
            emitter.completeWithError(e);
        } catch (RuntimeException e) {
            log.warn("build {}: log stream failed", buildId, e);
            emitter.completeWithError(e);
        }
    }

    private static void sleep(Duration d, long deadline) {
        long left = deadline - System.nanoTime();
        long nanos = Math.max(0, Math.min(d.toNanos(), left));
        try {
            Thread.sleep(Duration.ofNanos(nanos));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
