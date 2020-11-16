package context;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class Timing<T> implements Supplier<Result<T>> {

    private final String name;
    private final Supplier<? extends Result<T>> delegate;
    private final Consumer<? super TimingResult> timingConsumer;

    private Timing(
            String name,
            Supplier<? extends Result<T>> delegate,
            Consumer<? super TimingResult> timingConsumer
    ) {
        this.name = Objects.requireNonNull(name, "name");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.timingConsumer = Objects.requireNonNull(timingConsumer, "timingConsumer");
    }

    // Use this when your code returns Result<T> already (preferred).
    public static <T> Timing<T> trackResult(
            String name,
            Supplier<? extends Result<T>> delegate,
            Consumer<? super TimingResult> timingConsumer
    ) {
        return new Timing<>(name, delegate, timingConsumer);
    }

    // Use this when your code returns T and may throw.
    public static <T> Timing<T> track(
            String name,
            Supplier<? extends T> delegate,
            Consumer<? super TimingResult> timingConsumer
    ) {
        Objects.requireNonNull(delegate, "delegate");
        return new Timing<>(name, () -> Result.tryCatch(delegate::get), timingConsumer);
    }

    @Override
    public Result<T> get() {
        long start = System.nanoTime();
        Result<T> result = null;
        Throwable thrown = null;

        try {
            // delegate may still throw, even if it "returns Result"
            result = delegate.get();
            if (result == null) {
                result = Result.problem(Result.ProblemDetail.of("null_result", "Delegate returned null Result"));
            }
        } catch (Throwable t) {
            thrown = t;
            result = Result.problem(Result.ProblemDetail.fromThrowable(t));
        } finally {
            long duration = System.nanoTime() - start;

            timingConsumer.accept(
                    TimingResult.of(name, duration, result, thrown)
            );
        }

        return result;
    }

    private static Outcome outcomeOf(Result<?> r) {
        return (r instanceof Result.Success<?>) ? Outcome.SUCCESS : Outcome.PROBLEM;
    }

    private static Result.ProblemDetail problemOrNull(Result<?> r) {
        return (r instanceof Result.Problem<?> p) ? p.problem() : null;
    }

    public enum Outcome { SUCCESS, PROBLEM }

    public record TimingResult(
            String name,
            long durationNanos,
            Result<?> result,
            Throwable thrown              // non-null only if an exception was thrown
    ) {
        public static TimingResult of(
                String name,
                long durationNanos,
                Result<?> result,
                Throwable thrown
        ) {
            return new TimingResult(name, durationNanos, result, thrown);
        }

        @Override
        public String toString() {
            double ms = durationNanos / 1_000_000.0;

            String thrownPart = (thrown == null)
                    ? ""
                    : ", thrown=" + thrown.getClass().getSimpleName()
                    + (thrown.getMessage() == null ? "" : (": " + thrown.getMessage()));

            return result.fold(
                    success -> "TimingResult{name='%s', duration=%.3fms, outcome=SUCCESS%s}"
                            .formatted(name, ms, thrownPart),

                    problem -> {
                        String problemPart = ", problem=" + problem.code()
                                + (problem.message().isBlank() ? "" : (": " + problem.message()));

                        return "TimingResult{name='%s', duration=%.3fms, outcome=PROBLEM%s%s}"
                                .formatted(name, ms, problemPart, thrownPart);
                    }
            );
        }
    }

    public record Timed<T>(Result<T> result, TimingResult timing) {
        public boolean isSuccess() { return result instanceof Result.Success<?>; }
        public boolean isProblem() { return result instanceof Result.Problem<?>; }

        @Override
        public String toString() {
            return "Timed{timing=" + timing + ", result=" + result + "}";
        }
    }

}
