package json;

public class Json implements ToJson {


    @Override
    public String toJson() {
        return "{ " + "children" + " }";
    }

    public <T extends ToJson> void add(T model){

    }
}
