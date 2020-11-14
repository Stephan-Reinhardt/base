package server.request;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;

public final class RawRequestReader {

    private final int SOCKET_READ_BUFFER_SIZE_BYTES = 8192;
    private final int SOCKET_READ_DATA_LIMIT_BYTES = 32768;

    private final ByteBuffer readBuffer = ByteBuffer.allocate(SOCKET_READ_BUFFER_SIZE_BYTES);

    public String readRaw(ReadableByteChannel channel) throws IOException {
        StringBuilder sb = new StringBuilder();
        readBuffer.clear();
        int read;
        int totalRead = 0;
        while ((read = channel.read(readBuffer)) > 0) {
            totalRead += read;
            if (totalRead > SOCKET_READ_DATA_LIMIT_BYTES) {
                throw new IOException("Request data limit exceeded");
            }

            readBuffer.flip();
            byte[] bytes = new byte[readBuffer.limit()];
            readBuffer.get(bytes);
            sb.append(new String(bytes));
            readBuffer.clear();
        }

        if (read < 0) {
            throw new IOException("End of input stream. Connection is closed by the client");
        }

        return sb.toString();
    }

}