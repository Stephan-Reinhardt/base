package server2;

import logger.Logger;

import javax.net.ssl.*;
import java.io.EOFException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.Supplier;

public final class AsyncTlsHttpServer {

    private static final Logger LOGGER = new Logger(AsyncTlsHttpServer.class.getName());

    static void main(String[] args) throws Exception {
        int port = 8443;

        SSLContext sslContext = SslContexts.fromPkcs12(Path.of("server.p12"), "changeit".toCharArray());


        // Async channel group (completions run on this pool)
        var group = AsynchronousChannelGroup.withFixedThreadPool(
                Math.max(2, Runtime.getRuntime().availableProcessors()),
                Executors.defaultThreadFactory()
        );

        var server = AsynchronousServerSocketChannel.open(group)
                .bind(new InetSocketAddress("0.0.0.0", port));

        System.out.println("Listening on: https://localhost:" + port + "/");
        System.out.println("Test with:    curl -k https://localhost:" + port + "/ -v");

        acceptLoop(server, sslContext);

        // Keep main alive
        new CountDownLatch(1).await();
    }

    private static void acceptLoop(AsynchronousServerSocketChannel server, SSLContext sslContext) {
        server.accept(null, new CompletionHandler<>() {
            @Override public void completed(AsynchronousSocketChannel ch, Object att) {
                // accept next connection ASAP
                server.accept(null, this);

                try {
                    ch.setOption(StandardSocketOptions.TCP_NODELAY, true);
                } catch (Exception exception) {
                    LOGGER.warn("Failed to set TCP_NODELAY option", exception);
                }
                new Connection(ch, sslContext).start();
            }

            @Override public void failed(Throwable exc, Object att) {
                LOGGER.error(exc.getMessage(), exc);
                server.accept(null, this);
            }
        });
    }

    static final class Connection {
        private final AsynchronousSocketChannel ch;
        private final SSLEngine engine;

        private ByteBuffer netIn;
        private ByteBuffer netOut;

        private final int packetBufSize;
        private final int appBufSize;

        // Ensure SSLEngine isn't driven concurrently: chain operations per connection.
        private final Serial serial = new Serial();

        Connection(AsynchronousSocketChannel ch, SSLContext sslContext) {
            this.ch = Objects.requireNonNull(ch, "ch");
            this.engine = sslContext.createSSLEngine();
            this.engine.setUseClientMode(false);
            this.engine.setNeedClientAuth(false);

             engine.setEnabledProtocols(new String[] {"TLSv1.3", "TLSv1.2"});

            SSLSession session = engine.getSession();
            this.packetBufSize = session.getPacketBufferSize();
            this.appBufSize = session.getApplicationBufferSize();

            this.netIn = ByteBuffer.allocate(packetBufSize);
            this.netOut = ByteBuffer.allocate(packetBufSize);
        }

        void start() {
            serial.submit(() ->
                    handshake()
                            .thenCompose(v -> readHttpHeaders())
                            .thenCompose(this::writeHelloResponse)
                            .thenCompose(v -> closeGracefully())
            ).whenComplete((v, t) -> {
                if (t != null) t.printStackTrace();
                closeNow();
            });
        }

        private CompletionStage<Void> handshake() {
            try {
                engine.beginHandshake();
            } catch (SSLException e) {
                throw new RuntimeException(e);
            }
            return handshakeLoop();
        }

        private CompletionStage<Void> handshakeLoop() {
            SSLEngineResult.HandshakeStatus hs = engine.getHandshakeStatus();

            return switch (hs) {
                case FINISHED, NOT_HANDSHAKING -> CompletableFuture.completedFuture(null);

                case NEED_TASK -> {
                    runDelegatedTasks();
                    yield handshakeLoop();
                }

                case NEED_WRAP -> {
                    netOut.clear();
                    SSLEngineResult r = null;
                    try {
                        r = engine.wrap(ByteBuffer.allocate(0), netOut);
                    } catch (SSLException e) {
                        throw new RuntimeException(e);
                    }
                    handleEngineResultStatus(r);

                    netOut.flip();
                    yield writeFully(netOut).thenCompose(v -> handshakeLoop());
                }

                case NEED_UNWRAP -> {
                    // context.Try unwrap with whatever we currently have
                    CompletableFuture<Void> cf = new CompletableFuture<>();
                    unwrapHandshakeStep(cf);
                    yield cf;
                }
                case NEED_UNWRAP_AGAIN -> null;
            };
        }

        private void unwrapHandshakeStep(CompletableFuture<Void> cf) {
            // Read more if we don't have enough encrypted bytes
            if (netIn.position() == 0) {
                readMoreNet()
                        .whenComplete((n, t) -> {
                            if (t != null) cf.completeExceptionally(t);
                            else if (n < 0) cf.completeExceptionally(new EOFException("peer closed during handshake"));
                            else unwrapHandshakeStep(cf);
                        });
                return;
            }

            netIn.flip();
            ByteBuffer dummyApp = ByteBuffer.allocate(appBufSize);
            SSLEngineResult r;
            try {
                r = engine.unwrap(netIn, dummyApp);
            } catch (SSLException e) {
                netIn.compact();
                cf.completeExceptionally(e);
                return;
            }
            netIn.compact();

            switch (r.getStatus()) {
                case OK -> {
                    if (r.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NEED_TASK) runDelegatedTasks();
                    handshakeLoop().whenComplete((v, t) -> {
                        if (t != null) cf.completeExceptionally(t);
                        else cf.complete(null);
                    });
                }
                case BUFFER_UNDERFLOW -> {
                    ensureNetInCapacity(packetBufSize);
                    readMoreNet().whenComplete((n, t) -> {
                        if (t != null) cf.completeExceptionally(t);
                        else if (n < 0) cf.completeExceptionally(new EOFException("peer closed during handshake"));
                        else unwrapHandshakeStep(cf);
                    });
                }
                case BUFFER_OVERFLOW -> cf.completeExceptionally(
                        new IllegalStateException("Unexpected BUFFER_OVERFLOW during handshake unwrap")
                );
                case CLOSED -> cf.completeExceptionally(new EOFException("Engine closed during handshake"));
            }
        }

        private CompletionStage<String> readHttpHeaders() {
            // Read plaintext bytes until we see \r\n\r\n
            StringBuilder sb = new StringBuilder(1024);
            return readHeadersLoop(sb);
        }

        private CompletionStage<String> readHeadersLoop(StringBuilder sb) {
            return tlsReadTextChunk()
                    .thenCompose(chunk -> {
                        if (chunk == null) return CompletableFuture.failedFuture(new EOFException("peer closed"));
                        sb.append(chunk);
                        if (sb.indexOf("\r\n\r\n") >= 0) return CompletableFuture.completedFuture(sb.toString());
                        return readHeadersLoop(sb);
                    });
        }

        private CompletionStage<String> tlsReadTextChunk() {
            // Produce some plaintext. Returns null on clean EOF.
            ByteBuffer app = ByteBuffer.allocate(appBufSize);

            CompletableFuture<String> cf = new CompletableFuture<>();
            tlsReadInto(app, cf);
            return cf;
        }

        private void tlsReadInto(ByteBuffer appDst, CompletableFuture<String> cf) {
            // If we have no encrypted bytes buffered, read some first
            if (netIn.position() == 0) {
                readMoreNet().whenComplete((n, t) -> {
                    if (t != null) cf.completeExceptionally(t);
                    else if (n < 0) cf.complete(null);
                    else tlsReadInto(appDst, cf);
                });
                return;
            }

            netIn.flip();
            SSLEngineResult r;
            try {
                r = engine.unwrap(netIn, appDst);
            } catch (SSLException e) {
                netIn.compact();
                cf.completeExceptionally(e);
                return;
            }
            netIn.compact();

            switch (r.getStatus()) {
                case OK -> {
                    if (r.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NEED_TASK) runDelegatedTasks();
                    if (r.bytesProduced() > 0) {
                        appDst.flip();
                        CharBuffer chars = StandardCharsets.US_ASCII.decode(appDst);
                        cf.complete(chars.toString());
                    } else {
                        // Need more net data (or more unwrap attempts)
                        if (r.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NEED_UNWRAP || netIn.position() == 0) {
                            readMoreNet().whenComplete((n, t) -> {
                                if (t != null) cf.completeExceptionally(t);
                                else if (n < 0) cf.complete(null);
                                else tlsReadInto(appDst, cf);
                            });
                        } else {
                            tlsReadInto(appDst, cf);
                        }
                    }
                }
                case BUFFER_UNDERFLOW -> {
                    ensureNetInCapacity(packetBufSize);
                    readMoreNet().whenComplete((n, t) -> {
                        if (t != null) cf.completeExceptionally(t);
                        else if (n < 0) cf.complete(null);
                        else tlsReadInto(appDst, cf);
                    });
                }
                case BUFFER_OVERFLOW -> cf.completeExceptionally(
                        new IllegalStateException("App buffer too small (unexpected)")
                );
                case CLOSED -> cf.complete(null);
            }
        }

        private CompletionStage<Void> writeHelloResponse(String requestHeaders) {
            // Very tiny parsing: just show first line
            String firstLine = requestHeaders.lines().findFirst().orElse("<no request line>");

            byte[] body = ("Hello TLS!\nYou said: " + firstLine + "\n").getBytes(StandardCharsets.UTF_8);
            String headers =
                    "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: text/plain; charset=utf-8\r\n" +
                            "Content-Length: " + body.length + "\r\n" +
                            "Connection: close\r\n" +
                            "\r\n";

            byte[] head = headers.getBytes(StandardCharsets.US_ASCII);
            ByteBuffer app = ByteBuffer.allocate(head.length + body.length);
            app.put(head).put(body).flip();

            return tlsWrite(app);
        }

        private CompletionStage<Void> tlsWrite(ByteBuffer appSrc) {
            CompletableFuture<Void> cf = new CompletableFuture<>();
            tlsWriteLoop(appSrc, cf);
            return cf;
        }

        private void tlsWriteLoop(ByteBuffer appSrc, CompletableFuture<Void> cf) {
            if (!appSrc.hasRemaining()) {
                cf.complete(null);
                return;
            }

            netOut.clear();
            SSLEngineResult r;
            try {
                r = engine.wrap(appSrc, netOut);
            } catch (SSLException e) {
                cf.completeExceptionally(e);
                return;
            }

            switch (r.getStatus()) {
                case OK -> {
                    if (r.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NEED_TASK) runDelegatedTasks();

                    netOut.flip();
                    writeFully(netOut).whenComplete((v, t) -> {
                        if (t != null) cf.completeExceptionally(t);
                        else tlsWriteLoop(appSrc, cf);
                    });
                }
                case BUFFER_OVERFLOW -> {
                    // Grow netOut if needed
                    netOut = grow(netOut, packetBufSize);
                    tlsWriteLoop(appSrc, cf);
                }
                case BUFFER_UNDERFLOW -> cf.completeExceptionally(
                        new IllegalStateException("wrap() returned BUFFER_UNDERFLOW (unexpected)")
                );
                case CLOSED -> cf.complete(null);
            }
        }

        private CompletionStage<Void> closeGracefully() {
            try {
                engine.closeOutbound();
            } catch (Exception ignored) {}

            // Send close_notify
            netOut.clear();
            try {
                SSLEngineResult r = engine.wrap(ByteBuffer.allocate(0), netOut);
                if (r.getStatus() == SSLEngineResult.Status.CLOSED) {
                    netOut.flip();
                    return writeFully(netOut);
                }
            } catch (SSLException ignored) {}

            return CompletableFuture.completedFuture(null);
        }

        private void closeNow() {
            try { ch.close(); } catch (Exception ignored) {}
        }

        // ---- async primitives ----

        private CompletionStage<Integer> readMoreNet() {
            ensureNetInCapacity(packetBufSize);
            return read(netIn); // returns bytes read, or -1 on EOF :contentReference[oaicite:1]{index=1}
        }

        private static CompletableFuture<Integer> read(AsynchronousSocketChannel ch, ByteBuffer dst) {
            var cf = new CompletableFuture<Integer>();
            ch.read(dst, null, new CompletionHandler<>() {
                @Override public void completed(Integer n, Object att) { cf.complete(n); }
                @Override public void failed(Throwable exc, Object att) { cf.completeExceptionally(exc); }
            });
            return cf;
        }

        private CompletableFuture<Integer> read(ByteBuffer dst) {
            return read(ch, dst);
        }

        private CompletionStage<Void> writeFully(ByteBuffer src) {
            Objects.requireNonNull(src, "src");
            var cf = new CompletableFuture<Void>();
            writeFullyLoop(src, cf);
            return cf;
        }

        private void writeFullyLoop(ByteBuffer src, CompletableFuture<Void> cf) {
            if (!src.hasRemaining()) {
                cf.complete(null);
                return;
            }
            ch.write(src, null, new CompletionHandler<>() {
                @Override
                public void completed(Integer n, Object att) {
                    if (n == null || n < 0) {
                        cf.completeExceptionally(new EOFException("write failed/closed"));
                        return;
                    }
                    writeFullyLoop(src, cf);
                }
                @Override
                public void failed(Throwable exc, Object att) { cf.completeExceptionally(exc); }
            });
        }

        // ---- helpers ----

        private void runDelegatedTasks() {
            Runnable task;
            while ((task = engine.getDelegatedTask()) != null) {
                task.run();
            }
        }

        private void handleEngineResultStatus(SSLEngineResult r) {
            if (r.getStatus() == SSLEngineResult.Status.CLOSED) {
                // caller will close
            }
        }

        private void ensureNetInCapacity(int minCapacity) {
            if (netIn.remaining() >= minCapacity) return;
            // If it's packed, grow.
            netIn = grow(netIn, minCapacity);
        }

        private static ByteBuffer grow(ByteBuffer buf, int minExtraOrTarget) {
            int needed = Math.max(buf.capacity() * 2, buf.position() + minExtraOrTarget);
            ByteBuffer bigger = ByteBuffer.allocate(needed);
            buf.flip();
            bigger.put(buf);
            return bigger;
        }
    }

    static final class Serial {
        private CompletableFuture<Void> tail = CompletableFuture.completedFuture(null);

        synchronized <T> CompletableFuture<T> submit(Supplier<? extends CompletionStage<T>> action) {
            var next = tail.thenCompose(v -> action.get().toCompletableFuture());
            tail = next.handle((v, t) -> null);
            return next;
        }
    }
}
