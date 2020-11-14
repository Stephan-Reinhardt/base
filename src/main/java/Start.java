import logger.Logger;
import server.HttpServer;
import server.ServerSettings;

public class Start {
    private static final Logger LOGGER = new Logger(Start.class.getName());

    public static void main(String[] args){

        String www = Start.class.getClassLoader().getResource("www").getPath();
        ServerSettings settings = new ServerSettings(9999, www, 30, 10000);
        LOGGER.info("Loaded settings: " + settings);

        LOGGER.info("Starting server");
        HttpServer server = new HttpServer(settings);
        new Thread(server, "http-server").start();
    }
}
