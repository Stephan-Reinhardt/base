package actions;

import hardware.Context;
import logger.Logger;

import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class EventBus {

    private static final Logger LOGGER = new Logger("EventBus");

    private EventBus() {}

    // Handlers stored per exact event class
    private static final ConcurrentHashMap<Class<?>, CopyOnWriteArrayList<Consumer<? super Event>>> handlers =
            new ConcurrentHashMap<>();

    // Background dispatch queue (unbounded; see note below)
    private static final BlockingQueue<Event> queue = new LinkedBlockingQueue<>();

    private static final AtomicBoolean running = new AtomicBoolean(false);
    private static volatile Thread dispatcher;

    /** Start the background dispatcher (idempotent). */
    public static void start() {
        if (!running.compareAndSet(false, true)) return;

        dispatcher = Thread.ofVirtual().name("eventbus-dispatcher").start(() -> {
            while (running.get()) {
                try {
                    Event e = queue.take();
                    dispatch(e);
                } catch (InterruptedException ie) {
                    // Stop signal
                    Thread.currentThread().interrupt();
                    break;
                } catch (Throwable t) {
                    LOGGER.error("Error starting EventBus", t);
                }
            }
        });
    }

    /** Stop the background dispatcher (idempotent). */
    public static void stop() {
        if (!running.compareAndSet(true, false)) return;

        Thread t = dispatcher;
        dispatcher = null;
        if (t != null) t.interrupt();
    }

    /**
     * Register a handler for an event type.
     * Returns a handle you can close() to unregister safely.
     */
    public static <T extends Event> Subscription register(Class<T> type, Consumer<? super T> handler) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(handler, "handler");

        Consumer<? super Event> wrapper = (Event e) -> handler.accept(type.cast(e));

        handlers.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>()).add(wrapper);
        return new Subscription(type, wrapper);
    }

    /** Unregister a handler (if it exists). */
    public static boolean unregister(Class<? extends Event> type, Consumer<? super Event> wrapper) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(wrapper, "wrapper");

        var list = handlers.get(type);
        if (list == null) return false;

        boolean removed = list.remove(wrapper);
        if (list.isEmpty()) handlers.remove(type, list);
        return removed;
    }

    /**
     * Enqueue an event for background dispatch.
     * (Call EventBus.start() once during boot.)
     */
    public static <T extends Event> void send(T event) {
        Objects.requireNonNull(event, "event");
        // Auto-start is optional; remove if you want explicit lifecycle only.
        if (!running.get()) start();

        queue.offer(event);
    }

    /**
     * Enqueue an event and return a future that completes when all handlers finish.
     * Useful for tests or when the sender needs to know dispatch completed.
     */
    public static <T extends Event> CompletableFuture<Void> sendAndWait(T event) {
        Objects.requireNonNull(event, "event");
        if (!running.get()) start();

        CompletableFuture<Void> done = new CompletableFuture<>();
        queue.offer(new EnvelopeEvent(event, done));
        return done;
    }

    private static void dispatch(Event raw) {
        // Support sendAndWait via a tiny envelope
        final Event event;
        final CompletableFuture<Void> done;
        if (raw instanceof EnvelopeEvent ee) {
            event = ee.inner;
            done = ee.done;
        } else {
            event = raw;
            done = null;
        }

        var list = handlers.get(event.getClass());
        if (list == null || list.isEmpty()) {
            if (done != null) done.complete(null);
            return;
        }

        CompletableFuture<?>[] futures = new CompletableFuture<?>[list.size()];
        int i = 0;

        for (Consumer<? super Event> h : list) {
            // Run each handler on your virtual-thread executor.
            futures[i++] = Context
                    .supply(() -> {
                        h.accept(event);
                        return null;
                    })
                    .exceptionally(ex -> {
                        // IMPORTANT: this is where async handler exceptions are caught
                        ex.printStackTrace();
                        return null;
                    });
        }

        if (done != null) {
            CompletableFuture
                    .allOf(futures)
                    .whenComplete((v, ex) -> {
                        // We already printed handler exceptions above; just complete.
                        done.complete(null);
                    });
        }
    }

    public static final class Subscription {
        private final Class<? extends Event> type;
        private final Consumer<? super Event> wrapper;
        private volatile boolean unsubscribed;

        private Subscription(Class<? extends Event> type, Consumer<? super Event> wrapper) {
            this.type = type;
            this.wrapper = wrapper;
        }

        public void unsubscribe() {
            if (unsubscribed) return;
            unsubscribed = true;

            var list = handlers.get(type);
            if (list == null) return;

            list.remove(wrapper);
            if (list.isEmpty()) handlers.remove(type, list);
        }
    }

    /** Internal wrapper to support sendAndWait without changing the public Event type. */
    private static final class EnvelopeEvent implements Event {
        private final Event inner;
        private final CompletableFuture<Void> done;

        private EnvelopeEvent(Event inner, CompletableFuture<Void> done) {
            this.inner = inner;
            this.done = done;
        }
    }
}
