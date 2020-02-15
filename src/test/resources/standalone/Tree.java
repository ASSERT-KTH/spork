import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Random;

public class Tree<T extends Comparable<T>> implements Iterable<T> {
    private Node<T> root;
    private int size;
    private Random random;

    private enum Orientation {
        LEFT, RIGHT;
    }

    private static class Node<T> {
        Node<T> left;
        Node<T> right;
        T data;
        int priority;

        Node(T data, int priority) {
            this.data = data;
            this.priority = priority;
        }

        void setChild(Node<T> node, Orientation orientation) {
            switch (orientation) {
                case LEFT:
                    left = node;
                    break;
                case RIGHT:
                    right = node;
                    break;
            }
        }

        Node<T> getChild(Orientation orientation) {
            switch (orientation) {
                case LEFT:
                    return left;
                case RIGHT:
                    return right;
                default:
                    throw new IllegalStateException("Unknown side: " + orientation);
            }
        }
    }

    public Tree() {
        root = null;
        size = 0;
        random = new Random();
    }

    Tree(int seed) {
        root = null;
        size = 0;
        random = new Random(seed);
    }

    public boolean search(T elem) {
        return search(elem, root);
    }

    private boolean search(T elem, Node<T> current) {
        if (current == null)
            return false;

        int cmp = elem.compareTo(current.data);
        if (cmp < 0) {
            return search(elem, current.left);
        } else if (cmp > 0) {
            return search(elem, current.right);
        } else {
            return true;
        }
    }

    public boolean insert(T elem) {
        int sizeBefore = size;
        root = insertInto(elem, root);
        return size > sizeBefore;
    }

    private Node<T> insertInto(T elem, Node<T> root) {
        if (root == null) {
            size++;
            return new Node<>(elem, random.nextInt());
        }

        int cmp = elem.compareTo(root.data);
        if (cmp < 0) {
            root.left = insertInto(elem, root.left);
            if (root.priority > root.left.priority) {
                root = rotate(root, Orientation.RIGHT);
            }
        } else if (cmp > 0) {
            root.right = insertInto(elem, root.right);
            if (root.priority > root.right.priority) {
                root = rotate(root, Orientation.LEFT);
            }
        } // else, element is equal

        return root;
    }

    private static <T> Node<T> rotateLeft(Node<T> root) {
        Node<T> pivot = root.right;
        root.right = pivot.left;
        pivot.left = root;
        return pivot;
    }

    private static <T> Node<T> rotateRight(Node<T> root) {
        Node<T> pivot = root.left;
        root.left = pivot.right;
        pivot.right = root;
        return pivot;
    }

    private static <T> Node<T> rotate(Node<T> root, Orientation rs) {
        Orientation os = rs == Orientation.LEFT ? Orientation.RIGHT : Orientation.LEFT;
        Node<T> pivot = root.getChild(os);
        root.setChild(pivot.getChild(rs), os);
        pivot.setChild(root, rs);
        return pivot;
    }

    public int size() {
        return size;
    }

    public int height() {
        if (size <= 1) {
            return 0;
        }
        return heightHelper(root);
    }

    private int heightHelper(Node<T> root) {
        if (root == null)
            return -1;

        int left = heightHelper(root.left);
        int right = heightHelper(root.right);
        return Math.max(left, right) + 1;
    }

    public int leaves() {
        return leaves(root);
    }

    private int leaves(Node<T> root) {
        if (root == null) {
            return 0;
        } else if (root.left == null && root.right == null) {
            return 1;
        }

        return leaves(root.left) + leaves(root.right);
    }

    public String toString() {
        if (size == 0)
            return "[]";

        StringBuilder sb = new StringBuilder();
        buildString(root, sb);
        sb.delete(sb.length() - 2, sb.length());
        return "[" + sb.toString() + "]";
    }

    public void buildString(Node<T> root, StringBuilder sb) {
        if (root == null)
            return;

        buildString(root.left, sb);
        sb.append(root.data).append(", ");
        buildString(root.right, sb);
    }

    @Override
    public Iterator<T> iterator() {
        return new TreeIterator<>(root);
    }

    // and an inner class like this (doesn't have to be an inner class, but it makes it easier here)
    private static class TreeIterator<T extends Comparable<T>> implements Iterator<T> {
        // you need to keep track of the current node
        Node<T> current;
        // you will need a stack. Trust me on this one.
        ArrayDeque<Node<T>> stack;

        TreeIterator(Node<T> root) {
            stack = new ArrayDeque<>();
            current = advanceLeft(root);
        }

        private Node<T> advanceLeft(Node<T> node) {
            while (node != null) {
                stack.push(node);
                node = node.left;
            }
            return stack.size() > 0 ? stack.pop() : null;
        }

        @Override
        public boolean hasNext() {
            return current != null;
        }

        @Override
        public T next() {
            T ret = current.data;
            current = advanceLeft(current.right);
            return ret;
        }
    }
}
