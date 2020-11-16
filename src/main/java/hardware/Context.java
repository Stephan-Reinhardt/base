package hardware;

import context.Execution;
import context.Try;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

public class Context {

    private static final ExecutorService VT = Executors.newVirtualThreadPerTaskExecutor();

    public static <T> void supplyEssential(Execution<T> execution) {
        CompletableFuture
                .supplyAsync(execution.supplier(), VT)
                .thenApply(result -> {
                    execution.consumer().accept(result);
                    return null;
                });
    }

    // separate importance later
    public static <T> CompletableFuture<T> supply(Supplier<T> supplier) {
        return CompletableFuture.supplyAsync(supplier, VT);
    }

    public static <T> CompletableFuture<T> supplyThrowing(Try.ThrowingSupplier<T> supplier) {
        return CompletableFuture.supplyAsync(()-> {
            try {
                return supplier.get();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, VT);
    }

}
