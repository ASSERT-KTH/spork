interface Iface {
    default void someMethod() {
        for (int i = 0; i < 10; i++) {
            System.out.println(i);
        }
    }
}
