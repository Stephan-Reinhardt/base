package context;

import common.ThrowingSupplier;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

public final class Result<T> {
    private final Either<Problem, T> either;

    private Result(Either<Problem, T> either) {
        this.either = Objects.requireNonNull(either, "either");
    }

    public static <T> Result<T> ok(T value) {
        return new Result<>(Either.right(value));
    }

    public static <T> Result<T> err(Problem problem) {
        return new Result<>(Either.left(Objects.requireNonNull(problem, "problem")));
    }

    public static <T> Result<T> fromEither(Either<Problem, T> either) {
        return new Result<>(either);
    }

    public static <T> Result<T> ofThrowing(ThrowingSupplier<? extends T> supplier,
                                   Function<? super Exception, ? extends Problem> toProblem) {
        Objects.requireNonNull(supplier, "supplier");
        Objects.requireNonNull(toProblem, "toProblem");
        try {
            return ok(supplier.get());
        } catch (Exception e) { // catches checked + RuntimeException, but not Errors
            return err(Objects.requireNonNull(toProblem.apply(e), "toProblem returned null"));
        }
    }

    public static <T> Result<T> ofThrowing(String code, ThrowingSupplier<? extends T> supplier) {
        Objects.requireNonNull(code, "code");
        return ofThrowing(supplier, e -> new Problem(code, e.getMessage() != null ? e.getMessage() : e.toString(), e));
    }

    /** Optional default code variant */
    public static <T> Result<T> ofThrowing(ThrowingSupplier<? extends T> supplier) {
        return ofThrowing("UNEXPECTED_EXCEPTION", supplier);
    }

    /** Existing helper: catch everything and create a Problem using the given code. */
    public static <T> Result<T> catching(String code, Supplier<? extends T> supplier) {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(supplier, "supplier");
        try {
            return ok(supplier.get());
        } catch (Throwable t) {
            String msg = t.getMessage() != null ? t.getMessage() : t.toString();
            return err(new Problem(code, msg, t));
        }
    }

    public static <T> Result<T> of(
            Try.ThrowingSupplier<? extends T> supplier,
            Function<? super Throwable, ? extends Problem> toProblem
    ) {
        Objects.requireNonNull(supplier, "supplier");
        Objects.requireNonNull(toProblem, "toProblem");

        try {
            return ok(supplier.get());
        } catch (Throwable t) {
            return err(Objects.requireNonNull(toProblem.apply(t), "toProblem returned null"));
        }
    }

    public static <T> Result<T> of(Supplier<? extends T> supplier) {
        Objects.requireNonNull(supplier, "supplier");
        try {
            return ok(supplier.get());
        } catch (Throwable t) {
            return err(new Problem(t));
        }
    }

    /** Convenience overload: uses code + throwable message. */
    public static <T> Result<T> of(String code, Try.ThrowingSupplier<? extends T> supplier) {
        return of(supplier, t -> {
            String msg = t.getMessage() != null ? t.getMessage() : t.toString();
            return new Problem(code, msg, t);
        });
    }

    /** Convert from Try -> Result using provided throwable->Problem mapping. */
    public static <T> Result<T> fromTry(Try<? extends T> t, Function<? super Throwable, ? extends Problem> toProblem) {
        Objects.requireNonNull(t, "t");
        Objects.requireNonNull(toProblem, "toProblem");

        if (t instanceof Try.Success<? extends T> s) return ok(s.value());
        Throwable err = ((Try.Failure<? extends T>) t).error();
        return err(Objects.requireNonNull(toProblem.apply(err), "toProblem returned null"));
    }

    public Either<Problem, T> toEither() { return either; }

    public <U> Result<U> map(Function<? super T, ? extends U> f) {
        Objects.requireNonNull(f, "f");
        return new Result<>(either.map(f));
    }

    public <U> Result<U> flatMap(Function<? super T, Result<U>> f) {
        Objects.requireNonNull(f, "f");
        return new Result<>(either.flatMap(t -> Objects.requireNonNull(f.apply(t), "flatMap returned null").either));
    }

    public <U> U fold(Function<? super Problem, ? extends U> onErr,
                      Function<? super T, ? extends U> onOk) {
        return either.fold(onErr, onOk);
    }

    public T getOrElse(T fallback) { return either.getOrElse(fallback); }

    /** Optional helper that can be convenient with Try-like flows. */
    public Optional<T> toOptional() {
        return fold(p -> Optional.empty(), Optional::ofNullable);
    }
}