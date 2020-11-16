package logger;

public class Logger {

    private final String name;

    private final String ERROR_LEVEL = "error";
    private final String WARN_LEVEL = "warning";
    private final String INFO_LEVEL = "info";

    public Logger(String name) {
        this.name = name;
    }

    public void error(String msg){
        print(msg, ERROR_LEVEL);
    }

    public void warn(String msg){
        print(msg, WARN_LEVEL);
    }

    public void warn(String msg, Throwable e){
        print(msg, WARN_LEVEL);
        print(formatException(e), WARN_LEVEL);
    }

    public void error(String msg, Throwable e){
        print(msg, ERROR_LEVEL);
        print(formatException(e), ERROR_LEVEL);
    }

    public void info(String msg){
        print(msg, INFO_LEVEL);
    }

    private String formatException(Throwable e){
        return e.getStackTrace()[0].getLineNumber() + e.toString();
    }

    private void print(String msg, String level){
        System.out.println(name + " " + level  + " " + msg);
    }

}
