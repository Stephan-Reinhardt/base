package context;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

public final class Measured {

    public record ErrorInfo(String type, String message, Instant at) {
        public String toJson() {
            return "{"
                    + "\"type\":" + Json.value(type) + ","
                    + "\"message\":" + Json.value(message) + ","
                    + "\"at\":" + Json.value(at)
                    + "}";
        }
    }

    public record Metrics(
            String name,
            long invocations,
            long thrownErrors,
            double thrownErrorRate,
            long totalNanos,
            long minNanos,
            long maxNanos,
            double avgNanos,
            long lastNanos,
            Optional<ErrorInfo> lastThrownError,
            Optional<Instant> lastStartedAt,
            Optional<Instant> lastFinishedAt
    ) implements Formattable{

        @Override public String pretty() {
            return "Metrics(" + name
                    + " calls=" + invocations
                    + " thrown=" + thrownErrors
                    + " rate=" + String.format("%.2f%%", thrownErrorRate * 100.0)
                    + " min/avg/max=" + minNanos + "/" + String.format("%.2f", avgNanos) + "/" + maxNanos
                    + " last=" + lastNanos
                    + ")";
        }

        @Override
        public String prettyMultiline() {
            return ""
                    + "Metrics: " + name + "\n"
                    + "  invocations      : " + invocations + "\n"
                    + "  thrownErrors     : " + thrownErrors + "\n"
                    + "  thrownErrorRate  : " + formatPct(thrownErrorRate) + "\n"
                    + "  totalNanos       : " + totalNanos + "\n"
                    + "  min/avg/max nanos: " + minNanos + " / " + formatDouble(avgNanos) + " / " + maxNanos + "\n"
                    + "  lastNanos        : " + lastNanos + "\n"
                    + "  lastThrownError  : " + lastThrownError.map(e -> e.type() + ": " + e.message()).orElse("<none>") + "\n"
                    + "  lastStartedAt    : " + lastStartedAt.map(Object::toString).orElse("<none>") + "\n"
                    + "  lastFinishedAt   : " + lastFinishedAt.map(Object::toString).orElse("<none>");
        }
        @Override
        public String toJson() {
            return "{"
                    + "\"name\":" + Json.value(name) + ","
                    + "\"invocations\":" + invocations + ","
                    + "\"thrownErrors\":" + thrownErrors + ","
                    + "\"thrownErrorRate\":" + thrownErrorRate + ","
                    + "\"totalNanos\":" + totalNanos + ","
                    + "\"minNanos\":" + minNanos + ","
                    + "\"maxNanos\":" + maxNanos + ","
                    + "\"avgNanos\":" + avgNanos + ","
                    + "\"lastNanos\":" + lastNanos + ","
                    + "\"lastThrownError\":" + lastThrownError.map(ErrorInfo::toJson).orElse("null") + ","
                    + "\"lastStartedAt\":" + lastStartedAt.map(Json::value).orElse("null") + ","
                    + "\"lastFinishedAt\":" + lastFinishedAt.map(Json::value).orElse("null")
                    + "}";
        }

        public void printConsole() {
            System.out.println(prettyMultiline());
        }

        private static String formatPct(double d) {
            return String.format("%.2f%%", d * 100.0);
        }
        private static String formatDouble(double d) {
            return String.format("%.2f", d);
        }
    }

    public record ResultWithMetrics<T> (
            Result<T> result,
            long nanos,
            Instant startedAt,
            Instant finishedAt,
            Metrics aggregate
    ) implements Formattable {
        @Override
        public String pretty() {
            return aggregate.name() + " " + result.toString() + " nanos=" + nanos;
        }

        @Override
        public String prettyMultiline() {
            return ""
                    + "Call: " + aggregate.name() + "\n"
                    + "  startedAt : " + startedAt + "\n"
                    + "  finishedAt: " + finishedAt + "\n"
                    + "  nanos     : " + nanos + "\n"
                    + "  result    : " + result.toString()+ "\n"
                    + "\n"
                    + aggregate.prettyMultiline();
        }

        @Override
        public String toJson() {
            return "{"
                    + "\"name\":" + Json.value(aggregate.name()) + ","
                    + "\"startedAt\":" + Json.value(startedAt) + ","
                    + "\"finishedAt\":" + Json.value(finishedAt) + ","
                    + "\"nanos\":" + nanos + ","
                    + "\"result\":" + result.toString() + ","
                    + "\"aggregate\":" + aggregate.toJson()
                    + "}";
        }

        @Override
        public void printConsole() {
            // stdout for OK, stderr for ERR
            System.out.println(prettyMultiline());

        }
    }

    private final String name;

    private final LongAdder invocations = new LongAdder();
    private final LongAdder thrownErrors = new LongAdder();
    private final LongAdder totalNanos = new LongAdder();

    private final AtomicLong minNanos = new AtomicLong(Long.MAX_VALUE);
    private final AtomicLong maxNanos = new AtomicLong(0L);

    private final AtomicLong lastNanos = new AtomicLong(0L);
    private final AtomicReference<ErrorInfo> lastThrownError = new AtomicReference<>(null);

    private final AtomicReference<Instant> lastStartedAt = new AtomicReference<>(null);
    private final AtomicReference<Instant> lastFinishedAt = new AtomicReference<>(null);

    public Measured(String name) {
        this.name = Objects.requireNonNull(name, "name");
    }

    public String name() { return name; }

    public <T> ResultWithMetrics<T> run(Supplier<Result<T>> work) {
        Objects.requireNonNull(work, "work");
        invocations.increment();

        Instant startedAt = Instant.now();
        lastStartedAt.set(startedAt);
        long start = System.nanoTime();

        Result<T> r;
        try {
            r = Objects.requireNonNull(work.get(), "work returned null Result");
        } catch (Throwable t) {
            thrownErrors.increment();
            lastThrownError.set(new ErrorInfo(t.getClass().getName(), safeMessage(t), Instant.now()));
            r = Result.err(new Problem("exception", safeMessage(t), t).withDetail("measured", name));
        } finally {
            long dur = System.nanoTime() - start;
            lastNanos.set(dur);
            totalNanos.add(dur);
            minNanos.getAndUpdate(prev -> Math.min(prev, dur));
            maxNanos.getAndUpdate(prev -> Math.max(prev, dur));
            lastFinishedAt.set(Instant.now());
        }

        long dur = lastNanos.get();
        Instant finishedAt = lastFinishedAt.get();
        Metrics agg = metrics();

        return new ResultWithMetrics<>(r, dur, startedAt, finishedAt, agg);
    }

    public <T> ResultWithMetrics<T> runValue(String exceptionCode, Supplier<? extends T> work) {
        Objects.requireNonNull(exceptionCode, "exceptionCode");
        Objects.requireNonNull(work, "work");
        return run(() -> Result.catching(exceptionCode, work::get));
    }

    public Metrics metrics() {
        long calls = invocations.sum();
        long errs = thrownErrors.sum();
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
                Optional.ofNullable(lastThrownError.get()),
                Optional.ofNullable(lastStartedAt.get()),
                Optional.ofNullable(lastFinishedAt.get())
        );
    }

    public void reset() {
        invocations.reset();
        thrownErrors.reset();
        totalNanos.reset();
        minNanos.set(Long.MAX_VALUE);
        maxNanos.set(0L);
        lastNanos.set(0L);
        lastThrownError.set(null);
        lastStartedAt.set(null);
        lastFinishedAt.set(null);
    }

    private static String safeMessage(Throwable t) {
        String msg = t.getMessage();
        if (msg == null) return "";
        return (msg.length() <= 500) ? msg : msg.substring(0, 500);
    }

    private static final class Json {
        static String value(Object o) {
            if (o == null) return "null";
            if (o instanceof String s) return quote(s);
            if (o instanceof Character c) return quote(String.valueOf(c));
            if (o instanceof Number || o instanceof Boolean) return String.valueOf(o);
            if (o instanceof Instant i) return quote(i.toString());
            if (o instanceof Optional<?> opt) return opt.map(Json::value).orElse("null");
            if (o instanceof Map<?, ?> m) return map(m);
            if (o instanceof Iterable<?> it) return array(it);
            if (o.getClass().isArray()) return arrayFromArray(o);
            return quote(String.valueOf(o));
        }

        private static String map(Map<?, ?> m) {
            StringBuilder sb = new StringBuilder();
            sb.append("{");
            boolean first = true;
            for (var e : m.entrySet()) {
                if (!first) sb.append(",");
                first = false;
                sb.append(quote(String.valueOf(e.getKey()))).append(":").append(value(e.getValue()));
            }
            sb.append("}");
            return sb.toString();
        }

        private static String array(Iterable<?> it) {
            StringBuilder sb = new StringBuilder();
            sb.append("[");
            boolean first = true;
            for (Object v : it) {
                if (!first) sb.append(",");
                first = false;
                sb.append(value(v));
            }
            sb.append("]");
            return sb.toString();
        }

        private static String arrayFromArray(Object arr) {
            StringBuilder sb = new StringBuilder();
            sb.append("[");
            int len = java.lang.reflect.Array.getLength(arr);
            for (int i = 0; i < len; i++) {
                if (i > 0) sb.append(",");
                sb.append(value(java.lang.reflect.Array.get(arr, i)));
            }
            sb.append("]");
            return sb.toString();
        }

        private static String quote(String s) {
            StringBuilder sb = new StringBuilder(s.length() + 2);
            sb.append('"');
            for (int i = 0; i < s.length(); i++) {
                char ch = s.charAt(i);
                switch (ch) {
                    case '"'  -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '\b' -> sb.append("\\b");
                    case '\f' -> sb.append("\\f");
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    default -> {
                        if (ch < 0x20) sb.append(String.format("\\u%04x", (int) ch));
                        else sb.append(ch);
                    }
                }
            }
            sb.append('"');
            return sb.toString();
        }
    }
}
