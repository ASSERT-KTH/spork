import java.util.List;

public class Clazz {
    public void meth(List<? super Number> listOne, List<? super String> listTwo) {
        System.out.println(listOne);
        System.out.println(listTwo);
    }
}
