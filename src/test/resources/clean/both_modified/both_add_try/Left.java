class Cls {
    public static void main(String[] args) {
        try {
            System.out.println("Hello");
        } catch (IllegalArgumentException e) {
            System.out.println("Woopsie!");
            System.out.println("My bad!");
        } finally {
            System.out.println("Bye bye!");
        }
    }
}