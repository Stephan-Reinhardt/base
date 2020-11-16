package common;

@FunctionalInterface
public interface ThrowingFunction<T, U> {
    U apply(T t) throws Exception;
}
