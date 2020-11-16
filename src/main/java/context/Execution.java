package context;

import java.util.function.Consumer;
import java.util.function.Supplier;

public record Execution<T>(Supplier<T> supplier, Consumer<T> consumer) {

}
