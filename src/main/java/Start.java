import hardware.Hardware;
import logger.Logger;
import server.HttpServer;

import java.nio.file.Path;

public class Start {
    private static final Logger LOGGER = new Logger(Start.class.getName());

    static void main(String[] args){
        Hardware.load();

//        Path p = Path.of("resources", "www");
//        var t = p.toFile().exists();
//        var x = p.toFile().getAbsolutePath();
//        LOGGER.info("Starting server");
//        HttpServer server = new HttpServer(x);
//        new Thread(server, "http-server").start();
    }
}
