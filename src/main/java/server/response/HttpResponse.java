package server.response;

import server.fs.FileMetadata;

import java.text.SimpleDateFormat;
import java.util.*;

public final class HttpResponse {

    private final String SUPPORTED_HTTP_VERSION = "HTTP/1.1";

    private static final SimpleDateFormat dateFormat = new SimpleDateFormat(
            "EEE, dd MMM yyyy HH:mm:ss z", Locale.GERMANY);

    static {
        dateFormat.setTimeZone(TimeZone.getTimeZone("GMT"));
    }

    public static HttpResponse buildBadRequest(String msg) {
        return buildImmediateResponse(HttpStatus.BAD_REQUEST, msg);
    }

    public static HttpResponse buildNotFound(String msg) {
        return buildImmediateResponse(HttpStatus.NOT_FOUND, msg);
    }

    public static HttpResponse buildRequestTimeout() {
        return buildImmediateResponse(HttpStatus.REQUEST_TIMEOUT,
                "Session timeout exceeded");
    }

    public static HttpResponse buildTooManyRequests() {
        return buildImmediateResponse(HttpStatus.TOO_MANY_REQUESTS,
                "Connections number exceeded the limit");
    }

    private static HttpResponse buildImmediateResponse(HttpStatus status, String msg) {
        HttpResponse response = new HttpResponse();
        response.setCode(status.code);
        response.setReason(status.message);
        byte[] content = msg.getBytes();
        response.addContentChunk(content);
        response.setContentLength(content.length);
        response.markAsComplete();
        response.addDefaultHeaders();
        return response;
    }

    public static HttpResponse buildFileResponse(FileMetadata metadata) {
        HttpResponse response = new HttpResponse();
        response.setCode(HttpStatus.SUCCESS.code);
        response.setReason(HttpStatus.SUCCESS.message);
        response.setContentLength(metadata.getSize());
        response.addDefaultHeaders();
        return response;
    }

    private int code;
    private String reason;
    private final Map<String, String> headers = new HashMap<>();

    private List<byte[]> pendingContent = new LinkedList<>();
    private int pendingContentLength;
    private long contentLength;

    private boolean complete;
    private boolean wroteHeaders;

    public void addDefaultHeaders() {
        Calendar calendar = Calendar.getInstance();
        this.headers.put("Date", dateFormat.format(calendar.getTime()));
        this.headers.put("Server", "Simple NIO HTTP Server v1.0.0");
        this.headers.put("Connection", "closeSilently");
        this.headers.put("Content-Length", Long.toString(contentLength));
    }

    public String generatePrefix() {
        return SUPPORTED_HTTP_VERSION + " " + code + " " + reason;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public boolean hasPendingContent() {
        return pendingContentLength > 0;
    }

    public byte[] flushPendingContent() {
        byte[] result = new byte[pendingContentLength];
        int pos = 0;
        for (byte[] chunk : pendingContent) {
            System.arraycopy(chunk, 0, result, pos, chunk.length);
            pos += chunk.length;
        }

        pendingContent = new LinkedList<>();
        pendingContentLength = 0;

        return result;
    }

    public void addContentChunk(byte[] chunk) {
        pendingContent.add(chunk);
        pendingContentLength += chunk.length;
    }

    public void setContentLength(long contentLength) {
        this.contentLength = contentLength;
    }

    public boolean isComplete() {
        return complete;
    }

    public void markAsComplete() {
        this.complete = true;
    }

    public boolean wroteHeaders() {
        return wroteHeaders;
    }

    public void markAsWroteHeaders() {
        this.wroteHeaders = true;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    @Override
    public String toString() {
        return "HttpResponse{" +
                "code=" + code +
                ", reason='" + reason + '\'' +
                '}';
    }
}