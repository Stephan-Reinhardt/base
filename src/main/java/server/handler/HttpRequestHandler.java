package server.handler;

import logger.Logger;
import server.fs.AsyncFileReaderImpl;
import server.request.HttpRequest;
import server.request.HttpRequestParser;
import server.response.HttpResponse;
import server.response.HttpResponseWriter;

import java.io.File;
import java.io.IOException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;


public class HttpRequestHandler {

    private static final Logger LOGGER = new Logger(HttpRequestHandler.class.getName());
    public static final int SESSION_TIMEOUT_MILLIS = 30 * 1000;
    private static final long maxConnectionsNum = 10000;

    private HttpRequest request;
    private HttpResponse response;

    private final HttpResponseWriter responseWriter = new HttpResponseWriter();
    private final ReentrantLock writeLock = new ReentrantLock(true);
    private AsyncFileReaderImpl fileReader;
    private FileReadHandler fileReadHandler;

    private final String staticContentFolder;
    private final int sessionTimeoutMillis;
    private final long creationTimeMillis;
    private final long connectionNum;

    public HttpRequestHandler(String staticContentFolder, int connectionNum) {
        this.staticContentFolder = staticContentFolder;
        this.sessionTimeoutMillis = SESSION_TIMEOUT_MILLIS;
        this.creationTimeMillis = System.currentTimeMillis();
        this.connectionNum = connectionNum;
    }

    public void read(ReadableByteChannel channel) throws IOException {
        request = new HttpRequestParser().parse(channel);
        LOGGER.info("Parsed incoming HTTP request: " + request);

        response = validateRequestTimeoutAndRateLimit();
        if (response != null) {
            LOGGER.warn("Invalid incoming HTTP request: " + request + ", response: " + response);
        }
    }

    private HttpResponse validateRequestTimeoutAndRateLimit() {
        long time = System.currentTimeMillis();
        if (time - creationTimeMillis > sessionTimeoutMillis) {
            return HttpResponse.buildRequestTimeout();
        }
        // check if connection number exceeds the limit
        if (connectionNum > maxConnectionsNum) {
            return HttpResponse.buildTooManyRequests();
        }
        return null;
    }

    public void write(WritableByteChannel channel) throws IOException {
        if (request == null) {
            throw new IllegalStateException("Request is not initialized");
        }

        if (Objects.equals(request.method, "post") || Objects.equals(request.method, "POST")){
//            initJsonResponse
        }

        initFileResponse();
        responseWriter.writeHeaders(channel, response);
        validateSessionTimeout();
        writePendingContent(channel);
        scheduleFileForRead();
    }

    private void initFileResponse() {
        // an error response may be already there
        if (response != null) {
            return;
        }

        try {
            String filePath = (staticContentFolder + request.path).replace("/", File.separator);
            fileReader = new AsyncFileReaderImpl(filePath);
            fileReadHandler = new FileReadHandler();
            response = HttpResponse.buildFileResponse(fileReader.getMetadata());
            LOGGER.info("Started reading file for request: " + request);
        } catch (IOException e) {
            LOGGER.warn("Could not read file for request: " + request);
            response = HttpResponse.buildNotFound("Could not read file");
        }
    }

    private void validateSessionTimeout() {
        long time = System.currentTimeMillis();
        if (time - creationTimeMillis > sessionTimeoutMillis) {
            writeLock.lock();

            try {
                LOGGER.warn("Session timeout exceeded for request: " + request);
                response.markAsComplete();
                // get rid of buffered pending data
                response.flushPendingContent();
            } finally {
                writeLock.unlock();
            }
        }
    }

    private void writePendingContent(WritableByteChannel channel) throws IOException {
        // write only if there is an opportunity, otherwise - write on next tick
        boolean responseLocked = writeLock.tryLock();
        if (responseLocked) {
            try {
                responseWriter.writeContent(channel, response);
            } finally {
                writeLock.unlock();
            }
        }
    }

    private void scheduleFileForRead() {
        if (fileReader == null) {
            return;
        }
        fileReader.readNextChunk(fileReadHandler);
    }

    public boolean hasNothingToWrite() {
        boolean responseLocked = writeLock.tryLock();
        if (responseLocked) {
            try {
                return response.isComplete() && !response.hasPendingContent();
            } finally {
                writeLock.unlock();
            }
        }
        return false;
    }

    public void releaseSilently() {
        if (fileReader != null) {
            fileReader.closeSilently();
        }
    }

    private class FileReadHandler implements AsyncFileReaderImpl.ReadHandler {
        @Override
        public void onRead(byte[] data) {
            writeLock.lock();
            try {
                if (!response.isComplete()) {
                    response.addContentChunk(data);
                }
            } finally {
                writeLock.unlock();
            }
        }

        @Override
        public void onComplete() {
            LOGGER.info("Finished reading file for request: " + request);
            writeLock.lock();
            try {
                response.markAsComplete();
            } finally {
                writeLock.unlock();
            }
        }

        @Override
        public void onError(Throwable e) {
            LOGGER.error("Error during reading file for request: " + request, e);
            writeLock.lock();
            try {
                response.markAsComplete();
            } finally {
                writeLock.unlock();
            }
        }
    }
}