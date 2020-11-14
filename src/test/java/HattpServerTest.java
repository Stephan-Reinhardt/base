import org.junit.Test;
import server.HttpServer;

import static org.junit.Assert.*;

public class HattpServerTest {

    private HttpServer server = new HttpServer();

    @Test
    public void testHttpServer(){
        assertNotNull(server);
        server.run();
    }
}
