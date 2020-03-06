import java.util.List;

public class Clazz {
    public void meth(List<? extends Number> listOne, List<? super String> listTwo) {
        System.out.println(listOne);
        System.out.println(listTwo);
    }
}
