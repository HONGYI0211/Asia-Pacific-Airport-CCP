package airport;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The single runway used for BOTH landing and take-off.
 *
 * Mutual exclusion is enforced with a binary semaphore (1 permit). A plane thread
 * calls acquire() as it touches down or starts its take-off roll, and release()
 * once it has left the runway. The ATC only clears one plane at a time, so acquire()
 * does not have to wait, but if the ATC ever cleared a second plane by mistake that
 * plane would be held back here instead of colliding.
 *
 * The atomic counters are updated by the plane threads themselves, and record the
 * highest number of aircraft on the runway at once for the sanity check at the end.
 */
public class Runway {

    private final Semaphore permit = new Semaphore(1);   // binary semaphore: one plane at a time

    // Collision detector, updated by the plane threads themselves.
    private final AtomicInteger aircraftOnRunway = new AtomicInteger(0);
    private final AtomicInteger maxAircraftOnRunway = new AtomicInteger(0);
    private final AtomicInteger movements = new AtomicInteger(0);

    /** Called by a plane thread as it touches down / starts its take-off roll. */
    public void enter() throws InterruptedException {
        permit.acquire();
        int now = aircraftOnRunway.incrementAndGet();
        Statistics.recordPeak(maxAircraftOnRunway, now);
        movements.incrementAndGet();
    }

    /** Called by a plane thread once it has left the runway. Gives the permit back. */
    public void leave() {
        aircraftOnRunway.addAndGet(-1);
        permit.release();
    }

    public int getAircraftOnRunway() {
        return aircraftOnRunway.get();
    }

    public int getMaxAircraftOnRunway() {
        return maxAircraftOnRunway.get();
    }

    /** Total number of landings + take-offs. */
    public int getMovements() {
        return movements.get();
    }
}
