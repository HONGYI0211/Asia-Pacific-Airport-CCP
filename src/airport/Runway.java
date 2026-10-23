package airport;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The single runway used for BOTH landing and take-off.
 *
 * Mutual exclusion is enforced with a binary semaphore (1 permit):
 *  - the ATC thread takes the permit when it clears a plane (tryClear),
 *  - the plane thread gives it back once it has physically left the runway (leave).
 *
 * A semaphore (not a lock) is used on purpose: a ReentrantLock must be released by
 * the same thread that locked it, but here the clearance is issued by the ATC and
 * handed back by the plane - exactly the "permit" semantics of a semaphore.
 *
 * Independently of the semaphore, the plane threads report when they really are on
 * the runway (enter/leave). The atomic counters record the highest number of
 * aircraft ever seen on the runway at once, so the sanity check at the end can
 * prove that no collision ever happened.
 */
public class Runway {

    private final Semaphore permit = new Semaphore(1, true);

    // Collision detector, updated by the plane threads themselves.
    private final AtomicInteger aircraftOnRunway = new AtomicInteger(0);
    private final AtomicInteger maxAircraftOnRunway = new AtomicInteger(0);
    private final AtomicInteger movements = new AtomicInteger(0);

    /** Name of the plane holding the clearance - only used for log messages. */
    private volatile String clearedPlane = null;

    /**
     * Called by the ATC thread. Never blocks: returns false if the runway is
     * already cleared for another plane.
     */
    public boolean tryClear(Plane plane) {
        if (permit.tryAcquire()) {
            clearedPlane = plane.getName();
            return true;
        }
        return false;
    }

    /** Called by a plane thread as it touches down / starts its take-off roll. */
    public void enter(Plane plane) {
        int now = aircraftOnRunway.incrementAndGet();
        // atomic "max = Math.max(max, now)" without any lock
        maxAircraftOnRunway.accumulateAndGet(now, Math::max);
        movements.incrementAndGet();
    }

    /** Called by a plane thread once it has left the runway. Releases the clearance. */
    public void leave(Plane plane) {
        aircraftOnRunway.decrementAndGet();
        clearedPlane = null;
        permit.release();
    }

    public boolean isFree() {
        return permit.availablePermits() == 1;
    }

    public String getClearedPlane() {
        return clearedPlane;
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
