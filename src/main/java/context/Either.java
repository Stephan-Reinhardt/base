package context;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

public sealed interface Either<L, R> permits Either.Left, Either.Right {

    static <L, R> Either<L, R> left(L value) {
        return new Left<>(value);
    }

    static <L, R> Either<L, R> right(R value) {
        return new Right<>(value);
    }

    record Left<L, R>(L value) implements Either<L, R> {
        public Left {
            Objects.requireNonNull(value, "value is null");
        }
    }

    record Right<L, R>(R value) implements Either<L, R> {
        public Right {
            Objects.requireNonNull(value, "value is null");
        }
    }

    default <T> T fold(Function<? super L, ? extends T> onLeft,
                       Function<? super R, ? extends T> onRight) {
        Objects.requireNonNull(onLeft, "onLeft");
        Objects.requireNonNull(onRight, "onRight");
        return switch (this) { // pattern matching for switch :contentReference[oaicite:0]{index=0}
            case Left<L, R>  l -> onLeft.apply(l.value());
            case Right<L, R> r -> onRight.apply(r.value());
        };
    }

    default <R2> Either<L, R2> map(Function<? super R, ? extends R2> f) {
        Objects.requireNonNull(f, "f");
        return switch (this) {
            case Left<L, R>  l -> Either.left(l.value());
            case Right<L, R> r -> Either.right(f.apply(r.value()));
        };
    }

    default <R2> Either<L, R2> flatMap(Function<? super R, ? extends Either<L, R2>> f) {
        Objects.requireNonNull(f, "f");
        return switch (this) {
            case Left<L, R>  l -> Either.left(l.value());
            case Right<L, R> r -> Objects.requireNonNull(f.apply(r.value()), "flatMap returned null");
        };
    }

    default R getOrElse(R fallback) {
        return (this instanceof Right<L, R>(R value)) ? value : fallback;
    }
}