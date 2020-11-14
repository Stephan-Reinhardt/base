import logger.Logger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.Assert.*;

public class LoggerTest {

    Logger l = new Logger("Test");

    private final ByteArrayOutputStream outContent = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errContent = new ByteArrayOutputStream();
    private final PrintStream originalOut = System.out;
    private final PrintStream originalErr = System.err;

    @Before
    public void setUpStreams() {
        System.setOut(new PrintStream(outContent));
        System.setErr(new PrintStream(errContent));
    }

    @After
    public void restoreStreams() {
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    @Test
    public void testCreationLogger(){
        Logger l = new Logger("Test");
        assertNotNull(l);
    }

    @Test
    public void testLogError(){
        Logger l = new Logger("logger.Logger");
        l.error("test");
        assertEquals("logger.Logger" + " " + "error" + " " + "test" + System.lineSeparator(), outContent.toString());
    }

    @Test
    public void testLogInfo(){
        Logger l = new Logger("logger.Logger");
        l.info("test");
        assertEquals("logger.Logger" + " " + "info" + " " + "test" + System.lineSeparator(), outContent.toString());
    }
}
