package se.kth.spork.util;

import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A wrapper around an SL4J logger that allows for lazy logging.
 *
 * @author Simon Larsén
 */
public class LazyLogger {
    private final Logger logger;

    public LazyLogger(Class<?> cls) {
        logger = LoggerFactory.getLogger(cls);
    }

    public void debug(Supplier<String> messageSupplier) {
        if (logger.isDebugEnabled()) {
            logger.debug(messageSupplier.get());
        }
    }

    public void trace(Supplier<String> messageSupplier) {
        if (logger.isTraceEnabled()) {
            logger.trace(messageSupplier.get());
        }
    }

    public void info(Supplier<String> messageSupplier) {
        if (logger.isInfoEnabled()) {
            logger.info(messageSupplier.get());
        }
    }

    public void warn(Supplier<String> messageSupplier) {
        if (logger.isWarnEnabled()) {
            logger.warn(messageSupplier.get());
        }
    }

    public void error(Supplier<String> messageSupplier) {
        if (logger.isErrorEnabled()) {
            logger.error(messageSupplier.get());
        }
    }
}
