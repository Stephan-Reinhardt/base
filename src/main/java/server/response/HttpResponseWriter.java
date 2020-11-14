package server.response;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

public class HttpResponseWriter {

    private final Charset charset = StandardCharsets.UTF_8;
    private final CharsetEncoder encoder = charset.newEncoder();

    public void writeHeaders(WritableByteChannel channel, HttpResponse response) throws IOException {
        if (response.wroteHeaders()) {
            return;
        }

        String prefix = response.generatePrefix();
        writeLine(channel, prefix);

        Set<Map.Entry<String, String>> headers = response.getHeaders().entrySet();
        for (Map.Entry<String, String> header : headers) {
            writeLine(channel, header.getKey() + ": " + header.getValue());
        }
        writeLine(channel, "");

        response.markAsWroteHeaders();
    }

    public void writeContent(WritableByteChannel channel, HttpResponse response) throws IOException {
        if (!response.hasPendingContent()) {
            return;
        }

        byte[] content = response.flushPendingContent();
        ByteBuffer byteBuffer = ByteBuffer.wrap(content);
        channel.write(byteBuffer);
    }

    private void writeLine(WritableByteChannel channel, String line) throws IOException {
        CharBuffer charBuffer = CharBuffer.wrap(line + "\r\n");
        ByteBuffer byteBuffer = encoder.encode(charBuffer);
        channel.write(byteBuffer);
    }

}