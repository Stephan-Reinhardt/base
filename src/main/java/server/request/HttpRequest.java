package server.request;

public final class HttpRequest {

    public final String method;
    public final String path;
    public final String version;

    public HttpRequest(String method, String path, String version) {
        this.method = method;
        this.path = path;
        this.version = version;
    }

    @Override
    public String toString() {
        return "HttpRequest{" +
                "method='" + method + '\'' +
                ", path='" + path + '\'' +
                ", version='" + version + '\'' +
                '}';
    }
}
