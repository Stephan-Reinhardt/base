package context;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class Problem {
    private final String code;
    private final String message;
    private final Throwable cause;
    private final Map<String, Object> details;

    public Problem(String code, String message) {
        this(code, message, null, Map.of());
    }

    public Problem(Throwable e){
        this("nocode", e.getMessage(), e.getCause(), Map.of());
    }


    public Problem(String code, String message, Throwable cause) {
        this(code, message, cause, Map.of());
    }

    public Problem(String code, String message, Throwable cause, Map<String, ?> details) {
        this.code = Objects.requireNonNull(code, "code");
        this.message = Objects.requireNonNull(message, "message");
        this.cause = cause;
        var copy = new LinkedHashMap<String, Object>();
        if (details != null) copy.putAll(details);
        this.details = Collections.unmodifiableMap(copy);
    }

    public String code() { return code; }
    public String message() { return message; }
    public Optional<Throwable> cause() { return Optional.ofNullable(cause); }
    public Map<String, Object> details() { return details; }

    public Problem withDetail(String key, Object value) {
        var copy = new LinkedHashMap<>(this.details);
        copy.put(key, value);
        return new Problem(this.code, this.message, this.cause, copy);
    }

    private static String safeMessage(Throwable t) {
        String msg = t.getMessage();
        if (msg == null) return "";
        return (msg.length() <= 500) ? msg : msg.substring(0, 500);
    }
}
