package server;

public final class ServerSettings {

    private final int port;
    private final String wwwRoot;
    private final int sessionTimeoutSecs;
    private final int maxConnections;

    public ServerSettings(int port, String wwwRoot, int sessionTimeoutSecs, int maxConnections) {
        this.port = port;
        this.wwwRoot = wwwRoot;
        this.sessionTimeoutSecs = sessionTimeoutSecs;
        this.maxConnections = maxConnections;
    }

    public int getPort() {
        return port;
    }

    public String getWwwRoot() {
        return wwwRoot;
    }

    public int getSessionTimeoutSecs() {
        return sessionTimeoutSecs;
    }

    @Override
    public String toString() {
        return "ServerSettings{" +
                "port=" + port +
                ", wwwRoot=" + wwwRoot +
                ", sessionTimeoutSecs=" + sessionTimeoutSecs +
                '}';
    }

}