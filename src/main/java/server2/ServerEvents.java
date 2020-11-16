package server2;

import actions.Event;

import java.nio.file.Path;

public final class ServerEvents {
    private ServerEvents() {}

    public record ServerStartEvent(
            String id,
            String host,
            int port,
            boolean tlsEnabled,
            TlsConfig tls
    ) implements Event {}

    public record ServerStopEvent(String id) implements Event {}

    public record ServerStartedEvent(String id, String host, int port, boolean tlsEnabled) implements Event {}
    public record ServerStoppedEvent(String id) implements Event {}
    public record ServerFailedEvent(String id, Throwable error) implements Event {}

    public record TlsConfig(
            Path pkcs12Path,
            char[] pkcs12Password,
            boolean needClientAuth,
            String[] enabledProtocols
    ) {
        public static TlsConfig defaults(Path p12, char[] pwd) {
            return new TlsConfig(p12, pwd, false, new String[]{"TLSv1.3", "TLSv1.2"});
        }
    }
}