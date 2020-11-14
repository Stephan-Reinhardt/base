package server.fs;

import logger.Logger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.CompletionHandler;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

public class AsyncFileReaderImpl {

    private final int FILE_READ_BUFFER_SIZE_BYTES = 8192;

    private static final Logger LOGGER = new Logger(AsyncFileReaderImpl.class.getName());

    private final AsynchronousFileChannel fileChannel;
    private final FileMetadata metadata;

    private final ByteBuffer readBuffer = ByteBuffer.allocate(FILE_READ_BUFFER_SIZE_BYTES);
    private volatile int readPos;

    private volatile boolean reading;

    public AsyncFileReaderImpl(String filePath) throws IOException {
        fileChannel = AsynchronousFileChannel.open(Paths.get(filePath), StandardOpenOption.READ);
        metadata = new FileMetadata(fileChannel.size(), filePath);
    }

    public void readNextChunk(final ReadHandler handler) {
        // this method is called from a single thread, so volatile field is enough here
        if (reading) {
            return;
        }
        reading = true;

        fileChannel.read(readBuffer, readPos, null,
                new CompletionHandler<Integer, Void>() {
                    @Override
                    public void completed(Integer result, Void attachment) {
                        if (result == -1) {
                            handler.onComplete();
                            return;
                        }

                        readPos += result;
                        readBuffer.flip();
                        byte[] data = new byte[result];
                        System.arraycopy(readBuffer.array(), 0, data, 0, result);
                        readBuffer.clear();

                        handler.onRead(data);

                        if (readPos == metadata.getSize()) {
                            handler.onComplete();
                            return;
                        }

                        reading = false;
                    }

                    @Override
                    public void failed(Throwable e, Void attachment) {
                        handler.onError(e);
                        reading = false;
                    }
                }
        );
    }

    public FileMetadata getMetadata() {
        return metadata;
    }


    public void closeSilently() {
        try {
            fileChannel.close();
        } catch (IOException e) {
            LOGGER.warn("Error during closing file channel", e);
        }
    }

    public interface ReadHandler {

        void onRead(byte[] data);

        void onComplete();

        void onError(Throwable e);

    }
}