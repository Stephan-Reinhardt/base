package hardware;

import context.Result;

import java.util.Objects;

public class Cpu implements Runnable {

    private Result<String> arch;
    private Result<Integer> processors;
    private Result<String> model;

    @Override
    public void run() {
        this.arch = Result.of(() -> System.getProperty("os.arch"));
        this.processors = Result.of(() -> Runtime.getRuntime().availableProcessors());
        this.model = Result.of(() -> System.getProperty("os.name")); // best-effort fallback
        // NOTE: Java stdlib doesn't expose a reliable CPU model cross-platform.
        // Replace "model" supplier with something OS-specific if you want actual CPU model.
        print();
    }

    public record CpuInfo(Result<String> arch,
                          Result<Integer> processors,
                          Result<String> model) {
        public CpuInfo {
            Objects.requireNonNull(arch, "arch");
            Objects.requireNonNull(processors, "processors");
            Objects.requireNonNull(model, "model");
        }
    }

    public void print() {
        print(new CpuInfo(arch, processors, model));
    }

    private static void print(CpuInfo info) {
        Objects.requireNonNull(info, "info");

        printField("cpu.arch", info.arch());
        printField("cpu.processors", info.processors());
        printField("cpu.model", info.model());
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
