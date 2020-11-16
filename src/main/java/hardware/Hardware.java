package hardware;

public class Hardware {

    public static void load(){
        new Os().run();
        new Cpu().run();
        new Memory().run();
    }

}
