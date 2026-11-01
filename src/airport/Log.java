package airport;

import java.util.Locale;

/**
 * Thread-aware console logger.
 *
 * Every line shows the elapsed time and the name of the thread that printed it, e.g.
 *
 *   [  3.51s] [ATC               ] ATC: Landing APPROVED for Plane-1.
 *
 * This lets us prove that no thread ever "acts for" another one: ATC messages
 * always come from the ATC thread, Plane-1 messages from the Plane-1 thread, and
 * so on.
 */
public final class Log {

    private static final long START_NANOS = System.nanoTime();

    private Log() {
    }

    /**
     * synchronized so that one whole line is built and printed before the next
     * thread is allowed in - lines from different threads never get mixed up.
     */
    public static synchronized void say(String message) {
        System.out.println(String.format(Locale.ROOT, "[%6.2fs] [%-18s] %s",
                elapsedMillis() / 1000.0, Thread.currentThread().getName(), message));
    }

    /** Milliseconds since the simulation started. */
    public static long elapsedMillis() {
        return (System.nanoTime() - START_NANOS) / 1_000_000;
    }

    /** Formats a duration in milliseconds as seconds, e.g. 1234 -> "1.23s". */
    public static String seconds(long millis) {
        return String.format(Locale.ROOT, "%.2fs", millis / 1000.0);
    }
}
