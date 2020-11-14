package server.response;

enum HttpStatus {

    SUCCESS(200, "OK"),
    BAD_REQUEST(400, "Bad Request"),
    REQUEST_TIMEOUT(408, "Request Timeout"),
    TOO_MANY_REQUESTS(429, "Too Many Requests"),
    NOT_FOUND(404, "Not Found");

    public final int code;
    public final String message;

    HttpStatus(int code, String message) {
        this.code = code;
        this.message = message;
    }

}