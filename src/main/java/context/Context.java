package context;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A Function wrapper that tracks execution time and errors across invocations.
 *
 * - Thread-safe counters (LongAdder)
 * - Timing based on System.nanoTime()
 * - Error rate, min/max/avg duration, last error info
 */
public final class Context<I, O> implements Function<I, O> {

    @FunctionalInterface
    public interface CheckedFunction<I, O> {
        O apply(I input) throws Exception;
    }

    public record ErrorInfo(String type, String message, Instant at) {}

    public record Metrics(
            String name,
            long invocations,
            long errors,
            double errorRate,
            long totalNanos,
            long minNanos,
            long maxNanos,
            double avgNanos,
            long lastNanos,
            Optional<ErrorInfo> lastError,
            Optional<Instant> lastStartedAt,
            Optional<Instant> lastFinishedAt
    ) {}

    private final String name;
    private final Function<I, O> delegate;

    private final LongAdder invocations = new LongAdder();
    private final LongAdder errors = new LongAdder();
    private final LongAdder totalNanos = new LongAdder();

    private final AtomicLong minNanos = new AtomicLong(Long.MAX_VALUE);
    private final AtomicLong maxNanos = new AtomicLong(0L);

    private final AtomicLong lastNanos = new AtomicLong(0L);
    private final AtomicReference<ErrorInfo> lastError = new AtomicReference<>(null);

    private final AtomicReference<Instant> lastStartedAt = new AtomicReference<>(null);
    private final AtomicReference<Instant> lastFinishedAt = new AtomicReference<>(null);

    public Context(String name, Function<I, O> delegate) {
        this.name = Objects.requireNonNull(name, "name");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    /** Factory for lambdas that throw checked exceptions (they get wrapped into RuntimeException). */
    public static <I, O> Context<I, O> ofChecked(String name, CheckedFunction<I, O> fn) {
        Objects.requireNonNull(fn, "fn");
        return new Context<>(name, input -> {
            try {
                return fn.apply(input);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    /** Factory for no-arg work (use apply(null) or the returned supplier). */
    public static <O> Context<Void, O> ofSupplier(String name, Supplier<O> supplier) {
        Objects.requireNonNull(supplier, "supplier");
        return new Context<>(name, ignored -> supplier.get());
    }

    public String name() {
        return name;
    }

    @Override
    public O apply(I input) {
        invocations.increment();
        lastStartedAt.set(Instant.now());

        long start = System.nanoTime();
        try {
            return delegate.apply(input);
        } catch (Throwable t) {
            errors.increment();
            lastError.set(new ErrorInfo(t.getClass().getName(), safeMessage(t), Instant.now()));
            throw t;
        } finally {
            long dur = System.nanoTime() - start;
            lastNanos.set(dur);
            totalNanos.add(dur);
            minNanos.getAndUpdate(prev -> Math.min(prev, dur));
            maxNanos.getAndUpdate(prev -> Math.max(prev, dur));
            lastFinishedAt.set(Instant.now());
        }
    }

    public Metrics metrics() {
        long calls = invocations.sum();
        long errs = errors.sum();
        long total = totalNanos.sum();

        long min = minNanos.get();
        if (calls == 0 || min == Long.MAX_VALUE) min = 0;

        long max = (calls == 0) ? 0 : maxNanos.get();
        double avg = (calls == 0) ? 0.0 : ((double) total / (double) calls);
        double rate = (calls == 0) ? 0.0 : ((double) errs / (double) calls);

        return new Metrics(
                name,
                calls,
                errs,
                rate,
                total,
                min,
                max,
                avg,
                lastNanos.get(),
                Optional.ofNullable(lastError.get()),
                Optional.ofNullable(lastStartedAt.get()),
                Optional.ofNullable(lastFinishedAt.get())
        );
    }

    /** Clear counters/stats (keeps the underlying function). */
    public void reset() {
        invocations.reset();
        errors.reset();
        totalNanos.reset();
        minNanos.set(Long.MAX_VALUE);
        maxNanos.set(0L);
        lastNanos.set(0L);
        lastError.set(null);
        lastStartedAt.set(null);
        lastFinishedAt.set(null);
    }

    private static String safeMessage(Throwable t) {
        String msg = t.getMessage();
        if (msg == null) return "";
        return (msg.length() <= 500) ? msg : msg.substring(0, 500);
    }
}
