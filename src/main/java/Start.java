import actions.EventBus;
import actions.SystemEvent;
import context.Result;
import hardware.Context;
import hardware.Os;
import hardware.OsInfo;
import logger.Logger;
import server2.AsyncTlsHttpServer;
import server2.ServerEvents;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class Start {
    private static final Logger LOGGER = new Logger(Start.class.getName());

    static {
        EventBus.register(ServerEvents.ServerStartEvent.class, AsyncTlsHttpServer::onServerStart);
        EventBus.register(ServerEvents.ServerStopEvent.class, AsyncTlsHttpServer::onServerStop);
    }

    static void main(String[] args) throws InterruptedException {



        EventBus.send(new ServerEvents.ServerStartEvent(
                "api-tls",
                "0.0.0.0",
                8443,
                true,
                ServerEvents.TlsConfig.defaults(Path.of("server.p12"), "changeit".toCharArray())
        ));

        // Plain HTTP server
        EventBus.send(new ServerEvents.ServerStartEvent(
                "api-http",
                "0.0.0.0",
                8080,
                false,
                null
        ));


        var subscription = EventBus.register(SystemEvent.class, (e) -> {
            System.out.println("Event fired " + e.toString());
        });



        CompletableFuture<Result<OsInfo>> f = Context.supply(Os.call()).orTimeout(2, TimeUnit.SECONDS);

        EventBus.send(new SystemEvent());

        System.out.println("Running. Press Ctrl+C to quit.");
        new CountDownLatch(1).await();

        subscription.unsubscribe();

    }
}
