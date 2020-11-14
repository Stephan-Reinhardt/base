import logger.Logger;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.concurrent.*;

public class HttpServer implements Runnable{
    private static final Logger logger = new Logger(server.HttpServer.class.getName());


    private AsynchronousChannelGroup asyncChannelGroup;
    private AsynchronousServerSocketChannel asyncServerSocketChannel;

    public final static int READ_MESSAGE_WAIT_TIME = 15;
    public final static int MESSAGE_INPUT_SIZE= 128;

    HttpServer() {
        try {
            asyncChannelGroup = AsynchronousChannelGroup.withThreadPool(Executors.newCachedThreadPool());
        } catch (IOException e) {
            logger.error(e.getMessage());
        }
    }


    void open() throws IOException{
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", 9999);
        logger.info("Opening aysnc server socket channel at " + address);
        asyncServerSocketChannel = AsynchronousServerSocketChannel.open(asyncChannelGroup).bind(
                address);
        asyncServerSocketChannel.setOption(StandardSocketOptions.SO_RCVBUF, MESSAGE_INPUT_SIZE);
        asyncServerSocketChannel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
    }

    public void run() {
        try {
            if (asyncServerSocketChannel.isOpen()) {
                asyncServerSocketChannel.accept(null, new CompletionHandler<AsynchronousSocketChannel, Object>() {
                    @Override
                    public void completed(final AsynchronousSocketChannel asyncSocketChannel, Object attachment) {
                        if (asyncServerSocketChannel.isOpen()) {
                            asyncServerSocketChannel.accept(null, this);
                        }
                        handleAcceptConnection(asyncSocketChannel);
                    }
                    @Override
                    public void failed(Throwable exc, Object attachment) {
                        if (asyncServerSocketChannel.isOpen()) {
                            asyncServerSocketChannel.accept(null, this);
                            System.out.println("***********" + exc  + " statement=" + attachment);
                        }
                    }
                });
            }
        } catch (AcceptPendingException ex) {
            ex.printStackTrace();
        }
    }

    public void stopServer() throws IOException {
        logger.info("stopping server...");
        this.asyncServerSocketChannel.close();
        this.asyncChannelGroup.shutdown();
    }

    private void handleAcceptConnection(AsynchronousSocketChannel asyncSocketChannel) {
        logger.info(">>handleAcceptConnection(), asyncSocketChannel=" +asyncSocketChannel);
        ByteBuffer messageByteBuffer = ByteBuffer.allocate(MESSAGE_INPUT_SIZE);
        try {
            // read a message from the client, timeout after 10 seconds
            Future<Integer> futureReadResult = asyncSocketChannel.read(messageByteBuffer);
            futureReadResult.get(READ_MESSAGE_WAIT_TIME, TimeUnit.SECONDS);

            String clientMessage = new String(messageByteBuffer.array()).trim();

            messageByteBuffer.clear();
            messageByteBuffer.flip();

            String responseString = "echo" + "_" + clientMessage;
            messageByteBuffer = ByteBuffer.wrap((responseString.getBytes()));
            Future<Integer> futureWriteResult = asyncSocketChannel.write(messageByteBuffer);
            futureWriteResult.get(READ_MESSAGE_WAIT_TIME, TimeUnit.SECONDS);
            if (messageByteBuffer.hasRemaining()) {
                messageByteBuffer.compact();
            } else {
                messageByteBuffer.clear();
            }
        } catch (InterruptedException | ExecutionException | TimeoutException | CancellationException e) {
            logger.error(e.getMessage());
        } finally {
            try {
                asyncSocketChannel.close();
            } catch (IOException ioEx) {
                logger.error(ioEx.getMessage());
            }
        }
    }
}
