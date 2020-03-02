public class Outer {
    private int x = 1;
    private int y = 2;

    public int getX() {
        return x;
    }

    private static class SecondInner {
        private int x = 4;
        private int y = 5;

        public int getX() {
            return x;
        }

        public int getY() {
            return y;
        }
    }

    public int getY() {
        return y;
    }

    private static class FirstInner {
        private static class NestedInner {
            private int z = 2;
            private int q = 3;

            public int getZ() {
                return z;
            }

            public int getQ() {
                return q;
            }
        }

        private int x = 2;
        private int y = 3;

        public int getY() {
            return y;
        }

        public int getX() {
            return x;
        }
    }

}
