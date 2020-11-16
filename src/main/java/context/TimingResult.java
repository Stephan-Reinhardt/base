package context;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

public final class TimingResult {

    private final String operationName;
    private final long durationNanos;

    private TimingResult(String operationName, long durationNanos) {
        this.operationName = Objects.requireNonNull(operationName, "operationName must not be null");
        if (durationNanos < 0) {
            throw new IllegalArgumentException("durationNanos must be >= 0");
        }
        this.durationNanos = durationNanos;
    }

    public static TimingResult of(String operationName, long durationNanos) {
        return new TimingResult(operationName, durationNanos);
    }

    public String operationName() {
        return operationName;
    }

    public long durationNanos() {
        return durationNanos;
    }

    public long durationMillis() {
        return TimeUnit.NANOSECONDS.toMillis(durationNanos);
    }

    public double durationSeconds() {
        return durationNanos / 1_000_000_000.0;
    }

    /**
     * Human-readable representation, e.g.:
     * "expensiveCalculation took 12 ms (12,345,678 ns)"
     */
    public String toHumanReadableString() {
        long millis = durationMillis();
        if (millis > 0) {
            return operationName + " took " + millis + " ms (" + durationNanos + " ns)";
        }

        // For very fast operations, show micros or nanos
        long micros = TimeUnit.NANOSECONDS.toMicros(durationNanos);
        if (micros > 0) {
            return operationName + " took " + micros + " µs (" + durationNanos + " ns)";
        }

        return operationName + " took " + durationNanos + " ns";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TimingResult that)) return false;
        return durationNanos == that.durationNanos &&
                operationName.equals(that.operationName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(operationName, durationNanos);
    }

    @Override
    public String toString() {
        return "TimingResult{" +
                "operationName='" + operationName + '\'' +
                ", durationNanos=" + durationNanos +
                '}';
    }
}
