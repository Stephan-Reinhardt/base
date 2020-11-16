package hardware;

import context.Result;

import java.util.Objects;

public class Os implements Runnable {

    private Result<String> arch;
    private Result<String> name;
    private Result<String> version;

    @Override
    public void run() {
        this.name = Result.of(() -> System.getProperty("os.name"));
        this.version = Result.of(() -> System.getProperty("os.version"));
        this.arch = Result.of(() -> System.getProperty("os.arch"));
        print();
    }

    public record OsInfo(Result<String> name,
                         Result<String> version,
                         Result<String> arch) {

        public OsInfo {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(arch, "arch");
        }
    }

    /** Print current collected values (call after run()). */
    public void print() {
        print(new OsInfo(name, version, arch));
    }

    /** Print values to stdout; print problems to stderr. */
    private static void print(OsInfo info) {
        Objects.requireNonNull(info, "info");

        printField("os.name", info.name());
        printField("os.version", info.version());
        printField("os.arch", info.arch());
    }

    private static void printField(String label, Result<String> r) {
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
