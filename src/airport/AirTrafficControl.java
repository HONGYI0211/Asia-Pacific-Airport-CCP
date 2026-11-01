package airport;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Semaphore;

/**
 * The Air Traffic Control tower, running on its own thread named "ATC".
 *
 * How planes talk to the tower (a monitor built with synchronized, wait() and notify()):
 *   1. A plane thread calls requestLanding()/requestTakeoff(). This puts a Request
 *      in the "incoming" radio queue, wakes the tower (notifyAll()) and then makes
 *      the plane wait on its OWN Request object.
 *   2. The ATC thread wakes up, reads the request and decides. Every ATC message
 *      is therefore printed by the ATC thread, never by a plane thread.
 *   3. When the ATC grants permission it notifies exactly that plane's Request.
 *
 * Resources the tower hands out:
 *   - the runway         : clearedForRunway (1 plane at a time)
 *   - ground capacity    : aircraftOnGround (3 aircraft at most)
 *   - a gate             : Gate.assign()
 * A plane is only cleared to land if the runway, a ground slot AND a gate are all
 * free, because there is no waiting area on the ground.
 *
 * The tower decides with plain counters because it must never block. The plane
 * threads then take the real permits with Semaphore.acquire() (Runway and
 * groundSlots). The tower's decision means these calls do not have to wait, and if
 * the tower ever made a mistake the semaphore would still hold the plane back.
 *
 * Priority on the runway:
 *   1. an emergency (low fuel) landing
 *   2. take-offs (each take-off frees a ground slot for the planes circling above)
 *   3. normal landings, first come first served
 */
public class AirTrafficControl implements Runnable {

    private enum Kind { LANDING, TAKEOFF }

    /** One radio call from a plane. The plane thread waits on this object until the tower answers. */
    private static final class Request {
        final Plane plane;
        final Kind kind;
        final long requestedAt = Log.elapsedMillis();
        private boolean granted = false;    // guarded by this Request's monitor
        private Gate gate = null;           // guarded by this Request's monitor

        Request(Plane plane, Kind kind) {
            this.plane = plane;
            this.kind = kind;
        }

        /** Called by the ATC thread. Only one plane waits here, so notify() wakes exactly that plane. */
        synchronized void grant(Gate gate) {
            this.gate = gate;
            granted = true;
            notify();
        }

        synchronized boolean isGranted() {
            return granted;
        }

        /** Called by the plane thread; blocks until the tower has answered. */
        synchronized void awaitGrant() throws InterruptedException {
            while (!granted) {       // loop guards against spurious wake-ups
                wait();
            }
        }

        /** Same as awaitGrant(), but the circling pilot reports in now and then. Returns the gate. */
        synchronized Gate awaitLandingClearance() throws InterruptedException {
            while (!granted) {
                // wait(ms) releases this object's monitor. Only the tower notifies this
                // object, so waking up without clearance means the time simply ran out.
                wait(SimConfig.HOLDING_REPORT_INTERVAL);
                if (!granted) {
                    Log.say(plane + ": Still circling in the holding pattern, waiting for landing clearance...");
                }
            }
            return gate;
        }
    }

    // Everything below is guarded by this object's monitor (synchronized).
    private final LinkedList<Request> incoming = new LinkedList<Request>();     // radio calls not yet answered
    private final LinkedList<Request> landingQueue = new LinkedList<Request>(); // planes circling (holding pattern)
    private final LinkedList<Request> takeoffQueue = new LinkedList<Request>(); // planes holding short of the runway
    private Plane clearedForRunway = null;   // the plane allowed on the runway right now
    private int aircraftOnGround = 0;        // cleared to land and not yet airborne
    private int landingsCleared = 0;         // landing clearances given so far (never goes down)
    private boolean stateChanged = false;
    private boolean shutdownRequested = false;

    /** Max 3 aircraft on the airport grounds, including the runway. Each plane holds one permit. */
    private final Semaphore groundSlots = new Semaphore(SimConfig.GROUND_CAPACITY);

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
        Request request = new Request(plane, Kind.LANDING);
        radioCall(request);
        // The plane holds no lock on the tower while circling, so the tower can keep working.
        Gate gate = request.awaitLandingClearance();
        groundSlots.acquire();                        // the plane takes its ground slot
        return gate;
    }

    /** Radio call: "requesting take-off". Blocks until the runway is cleared for this plane. */
    public void requestTakeoff(Plane plane) throws InterruptedException {
        Request request = new Request(plane, Kind.TAKEOFF);
        radioCall(request);
        request.awaitGrant();
    }

    private synchronized void radioCall(Request request) {
        incoming.addLast(request);
        notifyAll();
    }

    /** The plane has landed and turned off the runway. */
    public synchronized void reportRunwayVacated() {
        runway.leave();
        clearedForRunway = null;
        stateChanged = true;
        notifyAll();
    }

    /** The plane has pushed back from its gate. */
    public synchronized void reportGateReleased() {
        stateChanged = true;
        notifyAll();
    }

    /** The plane is airborne: it gives back the runway and its ground slot. */
    public synchronized void reportDeparted() {
        runway.leave();
        groundSlots.release();
        clearedForRunway = null;
        aircraftOnGround--;
        stateChanged = true;
        notifyAll();
    }

    // ==================================================================
    // Called by the MAIN thread
    // ==================================================================

    /**
     * Blocks until the first planes have filled the airport, so that the emergency plane
     * always arrives in the congested scenario. landingsCleared never goes down, so this
     * wait cannot miss the moment and hang.
     */
    public synchronized void awaitAirportFull() throws InterruptedException {
        while (landingsCleared < SimConfig.GROUND_CAPACITY) {
            wait();
        }
    }

    /** All planes have gone - tell the tower to run its end-of-day report and stop. */
    public synchronized void shutdown() {
        shutdownRequested = true;
        notifyAll();
    }

    // ==================================================================
    // The ATC thread
    // ==================================================================

    @Override
    public void run() {
        Log.say("ATC: Asia Pacific Airport tower online. 1 runway, " + gates.length
                + " gates, max " + SimConfig.GROUND_CAPACITY + " aircraft on the ground.");
        try {
            synchronized (this) {
                while (true) {
                    // Sleep until there is a new radio call or something on the ground changed.
                    while (incoming.isEmpty() && !stateChanged && !shutdownRequested) {
                        wait();                       // releases the monitor so planes can call in
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
                        if (!request.isGranted()) {
                            tellToHold(request);
                        }
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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
        // PriorityBlockingQueue is not allowed, so the ordering is done by hand inside the monitor.
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
        if (clearedForRunway != null) {
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
        return aircraftOnGround < SimConfig.GROUND_CAPACITY && findFreeGate() != null;
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
        Gate gate = findFreeGate();                   // canAcceptLanding() has checked there is one
        gate.assign(request.plane);
        clearedForRunway = request.plane;
        aircraftOnGround++;
        landingsCleared++;

        long waited = Log.elapsedMillis() - request.requestedAt;
        String type = request.plane.isFuelEmergency() ? "EMERGENCY landing" : "Landing";
        Log.say("ATC: " + type + " permission GRANTED for " + request.plane
                + " (waited " + Log.seconds(waited) + "). Runway is clear.");
        Log.say("ATC: " + gate + " assigned to " + request.plane + ". Aircraft on the ground: "
                + aircraftOnGround + "/" + SimConfig.GROUND_CAPACITY + ".");

        request.grant(gate);                          // wake exactly this plane
        notifyAll();                                  // the main thread may be in awaitAirportFull()
    }

    private void grantTakeoff(Request request) {
        clearedForRunway = request.plane;

        long waited = Log.elapsedMillis() - request.requestedAt;
        Log.say("ATC: Take-off GRANTED for " + request.plane + " (waited " + Log.seconds(waited)
                + "). Runway is free.");

        request.grant(null);
    }

    private void tellToHold(Request request) {
        if (request.kind == Kind.TAKEOFF) {
            Log.say("ATC: " + request.plane + ", hold short of the runway - runway in use by "
                    + clearedForRunway + ".");
            return;
        }
        Log.say("ATC: Landing permission DENIED for " + request.plane + " - " + reasonCannotLand(request)
                + ". Join the holding pattern, queue position #" + (landingQueue.indexOf(request) + 1) + ".");
    }

    private String reasonCannotLand(Request request) {
        if (aircraftOnGround == SimConfig.GROUND_CAPACITY) {
            return "airport full (" + SimConfig.GROUND_CAPACITY + "/" + SimConfig.GROUND_CAPACITY
                    + " aircraft on the ground)";
        }
        if (clearedForRunway != null) {
            return "runway in use by " + clearedForRunway;
        }
        if (landingQueue.indexOf(request) > 0) {
            return "other aircraft are ahead in the landing queue";
        }
        return "departing traffic has priority";
    }

    // ==================================================================
    // End of simulation: sanity checks + statistics (printed by the ATC thread)
    // ==================================================================

    private void printEndOfDayReport() {
        synchronized (this) {
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
            allOk &= check(clearedForRunway == null && runway.getAircraftOnRunway() == 0, "Runway is clear.");
            allOk &= check(aircraftOnGround == 0 && stats.getPlanesOnGround() == 0,
                    "No aircraft left on the airport grounds (" + (SimConfig.GROUND_CAPACITY - aircraftOnGround)
                            + "/" + SimConfig.GROUND_CAPACITY + " ground slots free).");
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
