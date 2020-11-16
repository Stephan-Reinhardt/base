import logger.Logger;
import server.HttpServer;
import server.ServerSettings;

import java.util.Objects;

public class Start {
    private static final Logger LOGGER = new Logger(Start.class.getName());

    public static void main(String[] args){

        String staticContentFolder = Objects.requireNonNull(Start.class.getClassLoader().getResource("www")).getPath();
        LOGGER.info("Starting server");
        HttpServer server = new HttpServer(staticContentFolder);
        new Thread(server, "http-server").start();
    }
}
