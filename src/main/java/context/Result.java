package context;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

public sealed interface Result<T> permits Result.Success, Result.Problem {

    // -------- factories --------

    static <T> Success<T> success(T value) {
        return new Success<>(value);
    }

    static <T> Problem<T> problem(ProblemDetail problem) {
        return new Problem<>(problem);
    }

    static <T> Result<T> execute(Supplier<? extends T> supplier) {
        try {
            return success(supplier.get());
        } catch (Throwable t) {
            return problem(ProblemDetail.fromThrowable(t));
        }
    }

    static <T> Result<T> tryCatch(Try.ThrowingSupplier<? extends T> supplier) {
        try {
            return success(supplier.get());
        } catch (Throwable t) {
            return problem(ProblemDetail.fromThrowable(t));
        }
    }

    // -------- variants --------

    record Success<T>(T value) implements Result<T> {
        public Success {
            // You can remove this if you want to allow null successes.
            Objects.requireNonNull(value, "value");
        }
    }

    record Problem<T>(ProblemDetail problem) implements Result<T> {
        public Problem {
            Objects.requireNonNull(problem, "problem");
        }
    }

    record ProblemDetail(
            String code,
            String message,
            Throwable cause,
            Map<String, Object> details
    ) {
        public ProblemDetail {
            code = (code == null || code.isBlank()) ? "problem" : code;
            message = (message == null) ? "" : message;
            details = (details == null) ? Map.of() : Map.copyOf(details);
        }

        public static ProblemDetail of(String code, String message) {
            return new ProblemDetail(code, message, null, Map.of());
        }

        public static ProblemDetail fromThrowable(Throwable t) {
            String msg = (t.getMessage() != null) ? t.getMessage() : t.getClass().getSimpleName();
            return new ProblemDetail("exception", msg, t, Map.of());
        }
    }

    // -------- helpers --------

    default boolean isSuccess() { return this instanceof Success<?>; }
    default boolean isProblem() { return this instanceof Problem<?>; }

    default Optional<T> toOptional() {
        return (this instanceof Success<T>(var v)) ? Optional.ofNullable(v) : Optional.empty();
    }

    default T orElse(T fallback) {
        return (this instanceof Success<T>(var v)) ? v : fallback;
    }

    default T orElseGet(Supplier<? extends T> fallback) {
        return (this instanceof Success<T>(var v)) ? v : fallback.get();
    }

    default T orElseThrow() {
        return orElseThrow(p -> new RuntimeException(p.message(), p.cause()));
    }

    default T orElseThrow(Function<? super ProblemDetail, ? extends RuntimeException> exceptionFactory) {
        if (this instanceof Success<T>(var v)) return v;
        var p = ((Problem<T>) this).problem();
        throw exceptionFactory.apply(p);
    }

    default ProblemDetail problemOrNull() {
        return (this instanceof Problem<T>(var p)) ? p : null;
    }

    default <U> Result<U> map(Function<? super T, ? extends U> f) {
        Objects.requireNonNull(f, "f");
        if (this instanceof Success<T>(var v)) return Result.success(f.apply(v));
        return Result.problem(((Problem<T>) this).problem());
    }

    default <U> Result<U> flatMap(Function<? super T, ? extends Result<U>> f) {
        Objects.requireNonNull(f, "f");
        if (this instanceof Success<T>(var v)) return Objects.requireNonNull(f.apply(v));
        return Result.problem(((Problem<T>) this).problem());
    }

    default Result<T> mapProblem(Function<? super ProblemDetail, ? extends ProblemDetail> f) {
        Objects.requireNonNull(f, "f");
        if (this instanceof Problem<T>(var p)) return Result.problem(f.apply(p));
        return this;
    }

    default <R> R fold(
            Function<? super T, ? extends R> onSuccess,
            Function<? super ProblemDetail, ? extends R> onProblem
    ) {
        Objects.requireNonNull(onSuccess, "onSuccess");
        Objects.requireNonNull(onProblem, "onProblem");

        if (this instanceof Success<T>(var v)) return onSuccess.apply(v);
        return onProblem.apply(((Problem<T>) this).problem());
    }
}