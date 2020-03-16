import java.util.List;

class Cls {
    public static void print(List<? super String> list) {
        System.out.println(list);
    }
}