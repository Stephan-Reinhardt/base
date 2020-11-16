package context;

import java.util.function.Function;

public final class TimingFunction<I,O> implements Function<I,O> {

    @Override
    public O apply(I i) {
        return null;
    }

    @Override
    public <V> Function<V, O> compose(Function<? super V, ? extends I> before) {
        return Function.super.compose(before);
    }

    @Override
    public <V> Function<I, V> andThen(Function<? super O, ? extends V> after) {
        return Function.super.andThen(after);
    }
}
