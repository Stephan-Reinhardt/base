package context;

import java.io.PrintStream;

/**
 * Common output surface: string, multiline string, json, and console printing.
 * (No external libs.)
 *
 * If you use packages, put the same `package ...;` line on this as your other classes.
 */
public interface Formattable {
    /** Compact single-line representation (good for logs). */
    String pretty();

    /** Default multiline is just single-line, override when you want richer output. */
    default String prettyMultiline() { return pretty(); }

    /** JSON representation (no external libs). */
    String toJson();

    /** Print prettyMultiline to stdout by default. */
    default void printConsole() { printConsole(System.out); }

    default void printConsole(PrintStream out) { out.println(prettyMultiline()); }

    /** Print JSON to stdout by default. */
    default void printJson() { printJson(System.out); }

    default void printJson(PrintStream out) { out.println(toJson()); }
}
