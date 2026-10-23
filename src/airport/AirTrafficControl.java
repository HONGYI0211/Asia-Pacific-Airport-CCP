package airport;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The Air Traffic Control tower, running on its own thread named "ATC".
 *
 * How planes talk to the tower (a monitor built with ReentrantLock + Condition):
 *   1. A plane thread calls requestLanding()/requestTakeoff(). This puts a Request
 *      in the "incoming" radio queue, wakes the tower (towerWakeUp.signal()) and
 *      then makes the plane wait on its OWN condition (request.answered).
 *   2. The ATC thread wakes up, reads the request and decides. Every ATC message
 *      is therefore printed by the ATC thread, never by a plane thread.
 *   3. When the ATC grants permission it signals exactly that plane's condition.
 *
 * Resources the tower hands out:
 *   - the runway         : Runway (binary semaphore, 1 aircraft at a time)
 *   - ground capacity    : groundSlots (counting semaphore, 3 permits)
 *   - a gate             : Gate.assign()
 * A plane is only cleared to land if the runway, a ground slot AND a gate are all
 * free, because there is no waiting area on the ground.
 *
 * Priority on the runway:
 *   1. an emergency (low fuel) landing
 *   2. take-offs (each take-off frees a ground slot for the planes circling above)
 *   3. normal landings, first come first served
 */
public class AirTrafficControl implements Runnable {

    private enum Kind { LANDING, TAKEOFF }

    /** One radio call from a plane. The plane thread waits on {@code answered}. */
    private final class Request {
        final Plane plane;
        final Kind kind;
        final long requestedAt = Log.elapsedMillis();
        final Condition answered = lock.newCondition();
        boolean granted = false;    // guarded by lock
        Gate gate = null;           // guarded by lock

        Request(Plane plane, Kind kind) {
            this.plane = plane;
            this.kind = kind;
        }
    }

    // fair = true: threads get the lock in the order they asked for it
    private final ReentrantLock lock = new ReentrantLock(true);
    private final Condition towerWakeUp = lock.newCondition();

    // Everything below is guarded by "lock".
    private final LinkedList<Request> incoming = new LinkedList<Request>();     // radio calls not yet answered
    private final LinkedList<Request> landingQueue = new LinkedList<Request>(); // planes circling (holding pattern)
    private final LinkedList<Request> takeoffQueue = new LinkedList<Request>(); // planes holding short of the runway
    private boolean stateChanged = false;
    private boolean shutdownRequested = false;

    /** Max 3 aircraft on the airport grounds, including the runway. */
    private final Semaphore groundSlots = new Semaphore(SimConfig.GROUND_CAPACITY, true);

    private final Runway runway;
    private final Gate[] gates;
    private final RefuelTruck refuelTruck;
    private final Statistics stats;

    public AirTrafficControl(Runway runway, Gate[] gates, RefuelTruck refuelTruck, Statistics stats) {
        this.runway = runway;
        this.gates = gates;
        this.refuelTruck = refuelTruck;
        this.stats = stats;
    }

    // ==================================================================
    // Called by PLANE threads
    // ==================================================================

    /**
     * Radio call: "requesting landing". Blocks the calling plane (it circles in the
     * holding pattern) until the ATC thread clears it. Returns the assigned gate.
     */
    public Gate requestLanding(Plane plane) throws InterruptedException {
        lock.lock();
        try {
            Request request = new Request(plane, Kind.LANDING);
            incoming.addLast(request);
            towerWakeUp.signal();

            while (!request.granted) {
                // Timed wait so the pilot can report that it is still circling.
                // await() releases the lock while waiting, so the tower can work.
                boolean signalled = request.answered.await(SimConfig.HOLDING_REPORT_INTERVAL, TimeUnit.MILLISECONDS);
                if (!signalled && !request.granted) {
                    Log.say(plane + ": Still circling in the holding pattern, waiting for landing clearance...");
                }
            }
            return request.gate;
        } finally {
            lock.unlock();
        }
    }

    /** Radio call: "requesting take-off". Blocks until the runway is cleared for this plane. */
    public void requestTakeoff(Plane plane) throws InterruptedException {
        lock.lock();
        try {
            Request request = new Request(plane, Kind.TAKEOFF);
            incoming.addLast(request);
            towerWakeUp.signal();

            while (!request.granted) {
                request.answered.await();
            }
        } finally {
            lock.unlock();
        }
    }

    /** The plane has landed and turned off the runway. */
    public void reportRunwayVacated(Plane plane) {
        lock.lock();
        try {
            runway.leave(plane);
            stateChanged = true;
            towerWakeUp.signal();
        } finally {
            lock.unlock();
        }
    }

    /** The plane has pushed back from its gate. */
    public void reportGateReleased(Plane plane, Gate gate) {
        lock.lock();
        try {
            stateChanged = true;
            towerWakeUp.signal();
        } finally {
            lock.unlock();
        }
    }

    /** The plane is airborne: it gives back the runway and its ground slot. */
    public void reportDeparted(Plane plane) {
        lock.lock();
        try {
            runway.leave(plane);
            groundSlots.release();
            stateChanged = true;
            towerWakeUp.signal();
        } finally {
            lock.unlock();
        }
    }

    // ==================================================================
    // Called by the MAIN thread
    // ==================================================================

    /** All planes have gone - tell the tower to run its end-of-day report and stop. */
    public void shutdown() {
        lock.lock();
        try {
            shutdownRequested = true;
            towerWakeUp.signal();
        } finally {
            lock.unlock();
        }
    }

    // ==================================================================
    // The ATC thread
    // ==================================================================

    @Override
    public void run() {
        Log.say("ATC: Asia Pacific Airport tower online. 1 runway, " + gates.length
                + " gates, max " + SimConfig.GROUND_CAPACITY + " aircraft on the ground.");
        lock.lock();
        try {
            while (true) {
                // Sleep until there is a new radio call or something on the ground changed.
                while (incoming.isEmpty() && !stateChanged && !shutdownRequested) {
                    towerWakeUp.await();
                }
                if (shutdownRequested && incoming.isEmpty()) {
                    break;
                }
                stateChanged = false;

                // 1. Read every new radio call and put it in the right queue.
                List<Request> newCalls = new ArrayList<Request>();
                while (!incoming.isEmpty()) {
                    Request request = incoming.removeFirst();
                    enqueue(request);
                    newCalls.add(request);
                }

                // 2. Give the runway to the most important waiting plane (if it is free).
                dispatchRunway();

                // 3. Anyone who called in just now and did not get the runway is told to wait.
                for (Request request : newCalls) {
                    if (!request.granted) {
                        tellToHold(request);
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            lock.unlock();
        }
        printEndOfDayReport();
    }

    /** Puts a new request into the landing or take-off queue. Emergencies jump the landing queue. */
    private void enqueue(Request request) {
        if (request.kind == Kind.TAKEOFF) {
            takeoffQueue.addLast(request);
            return;
        }
        if (!request.plane.isFuelEmergency()) {
            landingQueue.addLast(request);
            return;
        }

        // Emergency: insert in front of every normal plane (but behind earlier emergencies).
        // PriorityBlockingQueue is not allowed, so the ordering is done by hand under the lock.
        int position = 0;
        while (position < landingQueue.size() && landingQueue.get(position).plane.isFuelEmergency()) {
            position++;
        }
        List<String> overtaken = new ArrayList<String>();
        for (int i = position; i < landingQueue.size(); i++) {
            overtaken.add(landingQueue.get(i).plane.getName());
        }
        landingQueue.add(position, request);

        Log.say("ATC: MAYDAY acknowledged for " + request.plane + "! Emergency priority given"
                + (overtaken.isEmpty() ? "." : " - moved ahead of " + String.join(", ", overtaken)
                + " in the landing queue."));
    }

    /** Clears at most one plane for the runway, following the priority rules. */
    private void dispatchRunway() {
        if (!runway.isFree()) {
            return;                                   // only one aircraft on the runway at a time
        }
        Request firstLanding = landingQueue.peekFirst();
        boolean firstCanLand = firstLanding != null && canAcceptLanding();

        if (firstCanLand && firstLanding.plane.isFuelEmergency()) {
            grantLanding(landingQueue.removeFirst());
        } else if (!takeoffQueue.isEmpty()) {
            grantTakeoff(takeoffQueue.removeFirst());
        } else if (firstCanLand) {
            grantLanding(landingQueue.removeFirst());
        }
    }

    /** There is no waiting area on the ground, so we need a free ground slot AND a free gate. */
    private boolean canAcceptLanding() {
        return groundSlots.availablePermits() > 0 && findFreeGate() != null;
    }

    private Gate findFreeGate() {
        for (Gate gate : gates) {
            if (gate.isFree()) {
                return gate;
            }
        }
        return null;
    }

    private void grantLanding(Request request) {
        Gate gate = findFreeGate();
        // The ATC thread is the only one that takes runway/ground permits and we have just
        // checked they are available, so these calls cannot fail. If they ever did, the
        // airport would be unsafe, so stop immediately.
        if (gate == null || !groundSlots.tryAcquire() || !runway.tryClear(request.plane)) {
            throw new IllegalStateException("ATC bookkeeping error while clearing " + request.plane);
        }
        gate.assign(request.plane);

        long waited = Log.elapsedMillis() - request.requestedAt;
        String type = request.plane.isFuelEmergency() ? "EMERGENCY landing" : "Landing";
        Log.say("ATC: " + type + " permission GRANTED for " + request.plane
                + " (waited " + Log.seconds(waited) + "). Runway is clear.");
        Log.say("ATC: " + gate + " assigned to " + request.plane + ". Aircraft on the ground: "
                + aircraftOnGround() + "/" + SimConfig.GROUND_CAPACITY + ".");

        request.gate = gate;
        request.granted = true;
        request.answered.signal();                    // wake exactly this plane
    }

    private void grantTakeoff(Request request) {
        if (!runway.tryClear(request.plane)) {
            throw new IllegalStateException("ATC bookkeeping error while clearing " + request.plane);
        }
        long waited = Log.elapsedMillis() - request.requestedAt;
        Log.say("ATC: Take-off GRANTED for " + request.plane + " (waited " + Log.seconds(waited)
                + "). Runway is free.");

        request.granted = true;
        request.answered.signal();
    }

    private void tellToHold(Request request) {
        if (request.kind == Kind.TAKEOFF) {
            Log.say("ATC: " + request.plane + ", hold short of the runway - runway in use by "
                    + runway.getClearedPlane() + ".");
            return;
        }
        Log.say("ATC: Landing permission DENIED for " + request.plane + " - " + reasonCannotLand(request)
                + ". Join the holding pattern, queue position #" + (landingQueue.indexOf(request) + 1) + ".");
    }

    private String reasonCannotLand(Request request) {
        if (groundSlots.availablePermits() == 0) {
            return "airport full (" + SimConfig.GROUND_CAPACITY + "/" + SimConfig.GROUND_CAPACITY
                    + " aircraft on the ground)";
        }
        if (!runway.isFree()) {
            return "runway in use by " + runway.getClearedPlane();
        }
        if (landingQueue.indexOf(request) > 0) {
            return "other aircraft are ahead in the landing queue";
        }
        return "departing traffic has priority";
    }

    private int aircraftOnGround() {
        return SimConfig.GROUND_CAPACITY - groundSlots.availablePermits();
    }

    // ==================================================================
    // End of simulation: sanity checks + statistics (printed by the ATC thread)
    // ==================================================================

    private void printEndOfDayReport() {
        lock.lock();
        try {
            List<FlightRecord> flights = stats.getFlights();
            int served = flights.size();

            int sumDisembarked = 0;
            int sumBoarded = 0;
            List<String> emergencies = new ArrayList<String>();
            for (FlightRecord f : flights) {
                sumDisembarked += f.passengersDisembarked;
                sumBoarded += f.passengersBoarded;
                if (f.emergency) {
                    emergencies.add(f.planeName);
                }
            }

            Log.say("ATC: ===================== END OF DAY REPORT =====================");
            Log.say("ATC: --------------------- Sanity checks -----------------------");
            boolean allOk = true;
            for (Gate gate : gates) {
                allOk &= check(gate.isFree(), gate + " is empty.");
            }
            allOk &= check(runway.isFree() && runway.getAircraftOnRunway() == 0, "Runway is clear.");
            allOk &= check(groundSlots.availablePermits() == SimConfig.GROUND_CAPACITY && stats.getPlanesOnGround() == 0,
                    "No aircraft left on the airport grounds (" + groundSlots.availablePermits() + "/"
                            + SimConfig.GROUND_CAPACITY + " ground slots free).");
            allOk &= check(incoming.isEmpty() && landingQueue.isEmpty() && takeoffQueue.isEmpty(),
                    "Landing and take-off queues are empty.");
            allOk &= check(!refuelTruck.hasPendingRequests(), "Refuel truck has no pending requests.");
            allOk &= check(served == SimConfig.TOTAL_PLANES,
                    "All planes served (" + served + "/" + SimConfig.TOTAL_PLANES + ").");
            allOk &= check(stats.getMaxPlanesOnGround() <= SimConfig.GROUND_CAPACITY,
                    "Max aircraft on the airport grounds at any time: " + stats.getMaxPlanesOnGround()
                            + " (limit " + SimConfig.GROUND_CAPACITY + ").");
            allOk &= check(runway.getMaxAircraftOnRunway() <= 1,
                    "Max aircraft on the runway at any time: " + runway.getMaxAircraftOnRunway()
                            + " (limit 1) - no runway collisions.");
            allOk &= check(runway.getMovements() == 2 * served,
                    "Runway movements: " + runway.getMovements() + " (" + served + " landings + "
                            + served + " take-offs).");
            allOk &= check(stats.getRefuelsCompleted() == served,
                    "Every plane refuelled exactly once (" + stats.getRefuelsCompleted() + " refuels).");
            allOk &= check(sumDisembarked == stats.getPassengersDisembarked() && sumBoarded == stats.getPassengersBoarded(),
                    "Passenger counters consistent - no lost updates (atomic counters match per-plane totals).");
            Log.say("ATC: Overall: " + (allOk ? "ALL SANITY CHECKS PASSED" : "SOME SANITY CHECKS FAILED"));

            Log.say("ATC: ------------------------ Statistics -----------------------");
            stat("Planes served", String.valueOf(served));
            stat("Emergency landings", emergencies.size()
                    + (emergencies.isEmpty() ? "" : " (" + String.join(", ", emergencies) + ")"));
            stat("Passengers disembarked", String.valueOf(stats.getPassengersDisembarked()));
            stat("Passengers boarded", String.valueOf(stats.getPassengersBoarded()));
            stat("Refuels completed", String.valueOf(stats.getRefuelsCompleted()));
            reportWaitingTimes("Waiting time to land", flights, true);
            reportWaitingTimes("Waiting time to take off", flights, false);
            for (Gate gate : gates) {
                stat(gate + " served", gate.getPlanesServed() + " plane(s)");
            }

            Log.say("ATC: ------------------------ Per plane ------------------------");
            Log.say(String.format(Locale.ROOT, "ATC: %-8s %-7s %-9s %9s %9s %5s %5s %10s",
                    "Plane", "Gate", "Type", "LandWait", "T/O Wait", "Off", "On", "AtAirport"));
            for (FlightRecord f : flights) {
                Log.say(String.format(Locale.ROOT, "ATC: %-8s %-7s %-9s %9s %9s %5d %5d %10s",
                        f.planeName, f.gateName, f.emergency ? "EMERGENCY" : "normal",
                        Log.seconds(f.landingWaitMillis), Log.seconds(f.takeoffWaitMillis),
                        f.passengersDisembarked, f.passengersBoarded, Log.seconds(f.timeOnGroundMillis)));
            }
            Log.say("ATC: ===========================================================");
            Log.say("ATC: Tower closing. Good night!");
        } finally {
            lock.unlock();
        }
    }

    private boolean check(boolean ok, String description) {
        Log.say("ATC: [" + (ok ? "PASS" : "FAIL") + "] " + description);
        return ok;
    }

    private void stat(String label, String value) {
        Log.say(String.format(Locale.ROOT, "ATC: %-24s: %s", label, value));
    }

    private void reportWaitingTimes(String label, List<FlightRecord> flights, boolean landing) {
        if (flights.isEmpty()) {
            return;
        }
        FlightRecord min = flights.get(0);
        FlightRecord max = flights.get(0);
        long total = 0;
        for (FlightRecord f : flights) {
            long wait = landing ? f.landingWaitMillis : f.takeoffWaitMillis;
            if (wait < (landing ? min.landingWaitMillis : min.takeoffWaitMillis)) {
                min = f;
            }
            if (wait > (landing ? max.landingWaitMillis : max.takeoffWaitMillis)) {
                max = f;
            }
            total += wait;
        }
        long minWait = landing ? min.landingWaitMillis : min.takeoffWaitMillis;
        long maxWait = landing ? max.landingWaitMillis : max.takeoffWaitMillis;
        stat(label, String.format(Locale.ROOT, "min %s (%s) | avg %s | max %s (%s)",
                Log.seconds(minWait), min.planeName, Log.seconds(total / flights.size()),
                Log.seconds(maxWait), max.planeName));
    }
}
