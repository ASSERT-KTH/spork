import java.util.List;

class Cls {
    public static void print(List<? extends String> list) {
        System.out.println(list);
    }
}