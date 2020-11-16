package server.request;

import java.io.IOException;
import java.nio.channels.ReadableByteChannel;
import java.util.StringTokenizer;

public class HttpRequestParser extends RawRequestReader{

    public HttpRequest parse(ReadableByteChannel channel) throws IOException {
        String raw = readRaw(channel);
        try {
            StringTokenizer tokenizer = new StringTokenizer(raw);
            String method = tokenizer.nextToken().toUpperCase();
            String path = tokenizer.nextToken();
            String version = tokenizer.nextToken();

            return new HttpRequest(method, path, version);
        } catch (Exception e) {
            throw new IOException("Malformed request", e);
        }
    }

}