package hardware;

import context.*;

import java.lang.management.ManagementFactory;

public class Os {

    public static Timing<OsInfo> call() {
        return Timing.track("", () -> new OsInfo(
                Try.of(()-> System.getProperty("os.name")).getOrElse("unknown"),
                Try.of(()-> System.getProperty("os.version")).getOrElse("unknown"),
                Try.of(()-> System.getProperty("os.arch")).getOrElse("unknown"),
                Try.of(()-> Runtime.getRuntime().availableProcessors()).getOrElse(-1),
                new MemoryInfo(
                        Try.of(()-> Runtime.getRuntime().maxMemory()).getOrElse(-1L),
                        Try.of(()-> Runtime.getRuntime().totalMemory()).getOrElse(-1L),
                        Try.of(()-> Runtime.getRuntime().freeMemory()).getOrElse(-1L))),
                (t)-> System.out.println(t.toString()));
    }

    private static double readSystemCpuLoad() {
        var os = ManagementFactory.getOperatingSystemMXBean();

        // com.sun.management.OperatingSystemMXBean is present on HotSpot/OpenJDK.
        if (os instanceof com.sun.management.OperatingSystemMXBean sun) {
            double v = sun.getCpuLoad(); // 0..1, or negative if unavailable
            return (v >= 0.0 && v <= 1.0) ? v : -1.0;
        }
        return -1.0;
    }
}
