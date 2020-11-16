package hardware;

import context.Result;

import java.util.Objects;

public class Memory implements Runnable {

    private Result<Long> maxBytes;
    private Result<Long> totalBytes;
    private Result<Long> freeBytes;

    @Override
    public void run() {
        this.maxBytes = Result.of(() -> Runtime.getRuntime().maxMemory());
        this.totalBytes = Result.of(() -> Runtime.getRuntime().totalMemory());
        this.freeBytes = Result.of(() -> Runtime.getRuntime().freeMemory());
        print();
    }

    public record MemoryInfo(Result<Long> maxBytes,
                             Result<Long> totalBytes,
                             Result<Long> freeBytes) {
        public MemoryInfo {
            Objects.requireNonNull(maxBytes, "maxBytes");
            Objects.requireNonNull(totalBytes, "totalBytes");
            Objects.requireNonNull(freeBytes, "freeBytes");
        }
    }

    public void print() {
        print(new MemoryInfo(maxBytes, totalBytes, freeBytes));
    }

    private static void print(MemoryInfo info) {
        Objects.requireNonNull(info, "info");

        printField("mem.maxBytes", info.maxBytes());
        printField("mem.totalBytes", info.totalBytes());
        printField("mem.freeBytes", info.freeBytes());
    }

    private static <T> void printField(String label, Result<T> r) {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(r, "result");

        r.fold(
                problem -> {
                    System.err.println(label + " ERROR: " + problem);
                    return null;
                },
                x -> {
                    System.out.println(label + ": " + x);
                    return null;
                }
        );
    }
}
