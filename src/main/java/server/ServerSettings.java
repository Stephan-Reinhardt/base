package server;

public final class ServerSettings {

    private final String wwwRoot;

    public ServerSettings(String wwwRoot) {
        this.wwwRoot = wwwRoot;
    }

    public String getWwwRoot() {
        return wwwRoot;
    }

    @Override
    public String toString() {
        return "ServerSettings{" +
                ", wwwRoot=" + wwwRoot +
                '}';
    }

}