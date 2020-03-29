import java.util.Arrays;
/**
 * Implementation of the Sieve of Eratosthenes algorithm for checking if a
 * number is prime or not. The implementation is lacking in error-checking
 * and optimization, and needs some patching up!
 *
 * @author Simon Larsén
 * @version 2017-08-05
 */
public class Sieve {

    /**
     * Check if a number is prime or not!
     *
     * Note that prime[n] denotes the primality of number n.
     *
     * @param   number  An integer value to be checked for primality.
     * @return  true if number is prime, false otherwise.
     */
    public boolean isPrime(int number) {
        if (number <= 1) {
            System.out.println("Now it's in the right place!");
            return false;
        }

        boolean[] prime = new boolean[number + 1]; // + 1 because of 0-indexing
        Arrays.fill(prime, true); // assume all numbers are prime
        int sqrt = (int) Math.floor(Math.sqrt(number));
        for (int i = 2; i <= sqrt; i++) {
            if (prime[i]) {
                for (int j = i*2; j < prime.length; j+=i) {
                    prime[j] = false; // mark multiples of i as not prime
                }
            }
        }

        return prime[number];
    }
}