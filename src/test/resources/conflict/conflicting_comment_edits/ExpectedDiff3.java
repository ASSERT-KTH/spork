import java.util.EmptyStackException;

/**
 * An array-based implementation of the Stack interface.
 *
 * @author Simon Larsén
 */
public class ArrayStack<T> implements Stack<T> {
    private static final int INITIAL_CAPACITY = 10;
    private Object[] elements;
    private int size;

    /**
<<<<<<< LEFT
     * Create an empty ArrayStack.
||||||| BASE
     * Creat an empty ArrayStack.
=======
     * Creat an empty ArrayStack with an initial capacity of 10.
>>>>>>> RIGHT
     */
    public ArrayStack() {
        elements = new Object[INITIAL_CAPACITY];
    }

    @Override
    public void push(T element) {
        ensureCapacity(size + 1);
        elements[size++] = element;
    }

    @Override
    public T pop() {
        T elem = checkedTop();
<<<<<<< LEFT
        // null element to avoid blocking garbage collector
||||||| BASE
        // null element to avoid blocking GC
=======
        // null element to avoid blocking the garbage collector
>>>>>>> RIGHT
        elements[size--] = null;
        return elem;
    }

    @Override
    public T top() {
        return checkedTop();
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public boolean isEmpty() {
        return size == 0;
    }

    /**
     * Checked fetching of the top element, throws an EmptyStackException if
     * the stack is empty.
     */
    private T checkedTop() {
        if (size == 0) {
            throw new EmptyStackException();
        }

        return (T) elements[size - 1];
    }

    /**
     * Ensure that the capacity is at least minCapacity.
     */
    private void ensureCapacity(int minCapacity) {
        if (minCapacity > elements.length) {
            grow();
        }
    }

    /**
     * Replace the current backing array with a larger one and copy over the
     * elements to the now array.
     */
    private void grow() {
        Object[] newElements = new Object[elements.length << 1];
        System.arraycopy(elements, 0, newElements, 0, elements.length);
        elements = newElements;
    }
}
