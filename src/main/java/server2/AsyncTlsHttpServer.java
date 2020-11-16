package server2;

import actions.EventBus;
import hardware.Context;
import logger.Logger;

import javax.net.ssl.*;
import java.io.EOFException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static server2.ServerEvents.*;

public final class AsyncTlsHttpServer {

    private static final Logger LOGGER = new Logger(AsyncTlsHttpServer.class.getName());

    private static final ConcurrentHashMap<String, ServerHandle> SERVERS = new ConcurrentHashMap<>();

    private static final ExecutorService IO_POOL =
            Executors.newFixedThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors()));
    private static final AsynchronousChannelGroup GROUP = createGroup(IO_POOL);

    private static AsynchronousChannelGroup createGroup(ExecutorService pool) {
        try {
            return AsynchronousChannelGroup.withThreadPool(pool);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // --- Event handlers ---

    public static void onServerStart(ServerStartEvent e) {
        Objects.requireNonNull(e, "event");
        Objects.requireNonNull(e.id(), "id");
        Objects.requireNonNull(e.host(), "host");

        // idempotent-ish: ignore if already running
        if (SERVERS.containsKey(e.id())) {
            LOGGER.warn("Server already running: " + e.id());
            return;
        }

        // Kick off startup on your virtual-thread executor
        Context.supplyThrowing(() -> startServerInternal(e))
                .thenAccept(handle -> {
                    SERVERS.put(e.id(), handle);
                    EventBus.send(new ServerStartedEvent(e.id(), e.host(), e.port(), e.tlsEnabled()));
                    LOGGER.info("Started server [" + e.id() + "] on " +
                            (e.tlsEnabled() ? "https" : "http") + "://" + e.host() + ":" + e.port());
                })
                .exceptionally(ex -> {
                    EventBus.send(new ServerFailedEvent(e.id(), ex));
                    LOGGER.error("Failed to start server [" + e.id() + "]", ex);
                    return null;
                });
    }

    public static void onServerStop(ServerStopEvent e) {
        Objects.requireNonNull(e, "event");
        Objects.requireNonNull(e.id(), "id");

        ServerHandle h = SERVERS.remove(e.id());
        if (h == null) {
            LOGGER.warn("No server found to stop: " + e.id());
            return;
        }

        Context.supply(() -> {
            h.stop();
            return null;
        }).thenAccept(v -> {
            EventBus.send(new ServerStoppedEvent(e.id()));
            LOGGER.info("Stopped server [" + e.id() + "]");
        }).exceptionally(ex -> {
            LOGGER.error("Error while stopping server [" + e.id() + "]", ex);
            return null;
        });
    }

    // --- Public convenience APIs (optional) ---
    public static boolean isRunning(String id) {
        return SERVERS.containsKey(id);
    }

    public static void stopAll() {
        SERVERS.keySet().forEach(id -> EventBus.send(new ServerStopEvent(id)));
    }

    // --- Server startup ---

    private static ServerHandle startServerInternal(ServerStartEvent e) throws Exception {
        SSLContext sslContext = null;

        if (e.tlsEnabled()) {
            var tlsCfg = Objects.requireNonNull(e.tls(), "tls config required when tlsEnabled=true");
            sslContext = SslContexts.fromPkcs12(
                    Objects.requireNonNull(tlsCfg.pkcs12Path(), "pkcs12Path"),
                    Objects.requireNonNull(tlsCfg.pkcs12Password(), "pkcs12Password")
            );
        }

        AsynchronousServerSocketChannel server = AsynchronousServerSocketChannel.open(GROUP)
                .bind(new InetSocketAddress(e.host(), e.port()));

        acceptLoop(server, sslContext, e);

        return new ServerHandle(e.id(), server);
    }

    private static void acceptLoop(
            AsynchronousServerSocketChannel server,
            SSLContext sslContextOrNull,
            ServerStartEvent cfg
    ) {
        server.accept(null, new CompletionHandler<>() {
            @Override public void completed(AsynchronousSocketChannel ch, Object att) {
                // accept next ASAP
                server.accept(null, this);

                try {
                    ch.setOption(StandardSocketOptions.TCP_NODELAY, true);
                } catch (Exception exception) {
                    LOGGER.warn("Failed to set TCP_NODELAY option", exception);
                }

                if (cfg.tlsEnabled()) {
                    new TlsConnection(ch, sslContextOrNull, cfg).start();
                } else {
                    new PlainConnection(ch).start();
                }
            }

            @Override public void failed(Throwable exc, Object att) {
                // If the server is closed, accept will fail; don’t spin forever.
                if (!server.isOpen()) return;

                LOGGER.error("Accept failed: " + exc.getMessage(), exc);
                server.accept(null, this);
            }
        });
    }

    // --- Server handle ---
    private static final class ServerHandle {
        private final String id;
        private final AsynchronousServerSocketChannel server;
        private final AtomicBoolean stopped = new AtomicBoolean(false);

        private ServerHandle(String id, AsynchronousServerSocketChannel server) {
            this.id = id;
            this.server = server;
        }

        void stop() {
            if (!stopped.compareAndSet(false, true)) return;
            try { server.close(); } catch (Exception ignored) {}
        }
    }

    static final class TlsConnection {
        private final AsynchronousSocketChannel ch;
        private final SSLEngine engine;

        private ByteBuffer netIn;
        private ByteBuffer netOut;

        private final int packetBufSize;
        private final int appBufSize;

        private final Serial serial = new Serial();
        private final ServerStartEvent cfg;

        TlsConnection(AsynchronousSocketChannel ch, SSLContext sslContext, ServerStartEvent cfg) {
            this.ch = Objects.requireNonNull(ch, "ch");
            this.cfg = Objects.requireNonNull(cfg, "cfg");

            Objects.requireNonNull(sslContext, "sslContext");
            this.engine = sslContext.createSSLEngine();
            this.engine.setUseClientMode(false);

            var tlsCfg = Objects.requireNonNull(cfg.tls(), "tls config");
            this.engine.setNeedClientAuth(tlsCfg.needClientAuth());

            String[] protos = tlsCfg.enabledProtocols();
            if (protos != null && protos.length > 0) {
                engine.setEnabledProtocols(protos);
            }

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
                if (t != null) LOGGER.error("TLS connection error", t);
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
                    SSLEngineResult r;
                    try {
                        r = engine.wrap(ByteBuffer.allocate(0), netOut);
                    } catch (SSLException ex) {
                        throw new RuntimeException(ex);
                    }
                    netOut.flip();
                    yield writeFully(netOut).thenCompose(v -> handshakeLoop());
                }

                case NEED_UNWRAP -> {
                    CompletableFuture<Void> cf = new CompletableFuture<>();
                    unwrapHandshakeStep(cf);
                    yield cf;
                }

                case NEED_UNWRAP_AGAIN -> handshakeLoop();
            };
        }

        private void unwrapHandshakeStep(CompletableFuture<Void> cf) {
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
            ByteBuffer app = ByteBuffer.allocate(appBufSize);
            CompletableFuture<String> cf = new CompletableFuture<>();
            tlsReadInto(app, cf);
            return cf;
        }

        private void tlsReadInto(ByteBuffer appDst, CompletableFuture<String> cf) {
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
                case BUFFER_OVERFLOW -> cf.completeExceptionally(new IllegalStateException("App buffer too small (unexpected)"));
                case CLOSED -> cf.complete(null);
            }
        }

        private CompletionStage<Void> writeHelloResponse(String requestHeaders) {
            String firstLine = requestHeaders.lines().findFirst().orElse("<no request line>");

            byte[] body = ("Hello TLS!\nserver=" + cfg.id() + "\nYou said: " + firstLine + "\n")
                    .getBytes(StandardCharsets.UTF_8);

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
            try { engine.closeOutbound(); } catch (Exception ignored) {}

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
            return read(netIn);
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
                @Override public void completed(Integer n, Object att) {
                    if (n == null || n < 0) {
                        cf.completeExceptionally(new EOFException("write failed/closed"));
                        return;
                    }
                    writeFullyLoop(src, cf);
                }
                @Override public void failed(Throwable exc, Object att) { cf.completeExceptionally(exc); }
            });
        }

        // ---- helpers ----

        private void runDelegatedTasks() {
            Runnable task;
            while ((task = engine.getDelegatedTask()) != null) task.run();
        }

        private void ensureNetInCapacity(int minCapacity) {
            if (netIn.remaining() >= minCapacity) return;
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

    // ============================================================================================
    // Plain HTTP connection (no TLS)
    // ============================================================================================

    static final class PlainConnection {
        private final AsynchronousSocketChannel ch;
        private final ByteBuffer in = ByteBuffer.allocate(8192);

        PlainConnection(AsynchronousSocketChannel ch) {
            this.ch = Objects.requireNonNull(ch, "ch");
        }

        void start() {
            readHeaders(new StringBuilder(1024))
                    .thenCompose(this::writeHello)
                    .whenComplete((v, t) -> {
                        if (t != null) LOGGER.error("Plain connection error", t);
                        try { ch.close(); } catch (Exception ignored) {}
                    });
        }

        private CompletionStage<String> readHeaders(StringBuilder sb) {
            CompletableFuture<String> cf = new CompletableFuture<>();
            ch.read(in, null, new CompletionHandler<>() {
                @Override public void completed(Integer n, Object att) {
                    if (n == null || n < 0) {
                        cf.completeExceptionally(new EOFException("peer closed"));
                        return;
                    }
                    in.flip();
                    sb.append(StandardCharsets.US_ASCII.decode(in));
                    in.clear();

                    if (sb.indexOf("\r\n\r\n") >= 0) cf.complete(sb.toString());
                    else readHeaders(sb).whenComplete((s, t) -> {
                        if (t != null) cf.completeExceptionally(t);
                        else cf.complete(s);
                    });
                }

                @Override public void failed(Throwable exc, Object att) {
                    cf.completeExceptionally(exc);
                }
            });
            return cf;
        }

        private CompletionStage<Void> writeHello(String requestHeaders) {
            String firstLine = requestHeaders.lines().findFirst().orElse("<no request line>");

            byte[] body = ("Hello HTTP!\nYou said: " + firstLine + "\n").getBytes(StandardCharsets.UTF_8);
            String headers =
                    "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: text/plain; charset=utf-8\r\n" +
                            "Content-Length: " + body.length + "\r\n" +
                            "Connection: close\r\n" +
                            "\r\n";

            byte[] head = headers.getBytes(StandardCharsets.US_ASCII);
            ByteBuffer out = ByteBuffer.allocate(head.length + body.length);
            out.put(head).put(body).flip();

            CompletableFuture<Void> cf = new CompletableFuture<>();
            writeFully(out, cf);
            return cf;
        }

        private void writeFully(ByteBuffer src, CompletableFuture<Void> cf) {
            if (!src.hasRemaining()) {
                cf.complete(null);
                return;
            }
            ch.write(src, null, new CompletionHandler<>() {
                @Override public void completed(Integer n, Object att) {
                    if (n == null || n < 0) {
                        cf.completeExceptionally(new EOFException("write failed/closed"));
                        return;
                    }
                    writeFully(src, cf);
                }
                @Override public void failed(Throwable exc, Object att) { cf.completeExceptionally(exc); }
            });
        }
    }

    // ============================================================================================

    static final class Serial {
        private CompletableFuture<Void> tail = CompletableFuture.completedFuture(null);

        synchronized <T> CompletableFuture<T> submit(Supplier<? extends CompletionStage<T>> action) {
            var next = tail.thenCompose(v -> action.get().toCompletableFuture());
            tail = next.handle((v, t) -> null);
            return next;
        }
    }
}
