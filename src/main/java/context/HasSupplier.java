package context;

import java.util.function.Supplier;

public interface HasSupplier<T> {

    Supplier<Result<T>> getSupplier();

}
