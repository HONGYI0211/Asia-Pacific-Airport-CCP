package airport;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Statistics shared by all threads.
 *
 * - Simple counters that many threads bump at the same time (passengers, refuels,
 *   planes on the ground) are AtomicIntegers: "count++" on a plain int is NOT
 *   atomic (read, add, write) and would lose updates under contention.
 * - The list of finished flights is guarded by this object's monitor
 *   (synchronized methods).
 */
public class Statistics {

    private final AtomicInteger passengersDisembarked = new AtomicInteger(0);
    private final AtomicInteger passengersBoarded = new AtomicInteger(0);
    private final AtomicInteger refuelsCompleted = new AtomicInteger(0);

    // Independent check of the "max 3 aircraft on the grounds" rule.
    private final AtomicInteger planesOnGround = new AtomicInteger(0);
    private final AtomicInteger maxPlanesOnGround = new AtomicInteger(0);

    private final List<FlightRecord> flights = new ArrayList<FlightRecord>();

    public void addPassengersDisembarked(int count) {
        passengersDisembarked.addAndGet(count);
    }

    public void addPassengersBoarded(int count) {
        passengersBoarded.addAndGet(count);
    }

    public void refuelCompleted() {
        refuelsCompleted.incrementAndGet();
    }

    /** Called by a plane thread when it touches down on the runway. */
    public void planeEnteredGround() {
        int now = planesOnGround.incrementAndGet();
        maxPlanesOnGround.accumulateAndGet(now, Math::max);
    }

    /** Called by a plane thread once it is airborne again. */
    public void planeLeftGround() {
        planesOnGround.decrementAndGet();
    }

    public synchronized void recordFlight(FlightRecord record) {
        flights.add(record);
    }

    /** Returns a copy so the caller can iterate without holding the lock. */
    public synchronized List<FlightRecord> getFlights() {
        return new ArrayList<FlightRecord>(flights);
    }

    public int getPassengersDisembarked() {
        return passengersDisembarked.get();
    }

    public int getPassengersBoarded() {
        return passengersBoarded.get();
    }

    public int getRefuelsCompleted() {
        return refuelsCompleted.get();
    }

    public int getPlanesOnGround() {
        return planesOnGround.get();
    }

    public int getMaxPlanesOnGround() {
        return maxPlanesOnGround.get();
    }
}
