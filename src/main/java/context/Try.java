package context;

import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

sealed public interface Try<T> permits Try.Success, Try.Failure {

    record Success<T>(T value) implements Try<T> {}
    record Failure<T>(Throwable error) implements Try<T> {}

    @FunctionalInterface
    interface ThrowingSupplier<T> { T get() throws Exception; }

    static <T> Try<T> ofThrowing(ThrowingSupplier<? extends T> s) {
        try {
            return new Success<>(s.get());
        } catch (Throwable t) {
            return new Failure<>(t);
        }
    }

    static <T> Try<T> of(Supplier<? extends T> s) {
        try {
            return new Success<>(s.get());
        } catch (Throwable t) {
            return new Failure<>(t);
        }
    }

    default boolean isSuccess() { return this instanceof Success<?>; }

    default Optional<T> toOptional() {
        return (this instanceof Success<T>(T value)) ? Optional.ofNullable(value) : Optional.empty();
    }

    default T getOrElse(T fallback) {
        return (this instanceof Success<T>(T value)) ? value : fallback;
    }

    default T getOrElseGet(Function<? super Throwable, ? extends T> fallback) {
        return (this instanceof Success<T>(T value)) ? value : fallback.apply(((Failure<T>) this).error());
    }

    default <U> Try<U> map(Function<? super T, ? extends U> f) {
        if (this instanceof Success<T>(T value)) return Try.of(() -> f.apply(value));
        return new Failure<>(((Failure<T>) this).error());
    }

}