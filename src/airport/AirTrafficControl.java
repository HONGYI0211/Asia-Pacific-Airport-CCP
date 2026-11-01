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

    /** What a plane is asking the tower for. */
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

        /** Called by the ATC thread to see which planes must be told to wait. */
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
                    Log.say(plane + ": Still flying in circles, waiting for ATC to say it can land...");
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
    private boolean stateChanged = false;    // set when the runway or a gate may have become free
    private boolean shutdownRequested = false; // set by the main thread when every plane has left

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

    /**
     * Puts the call in the radio queue and wakes the tower. It uses notifyAll() because
     * the main thread can also be waiting on this monitor, in awaitAirportFull().
     */
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
    // enqueue(), dispatchRunway(), the grant methods and tellToHold() only
    // run on this thread, inside the synchronized block in run().
    // ==================================================================

    /** The tower's main loop: sleep, read new calls, give out the runway, tell the rest to wait. */
    @Override
    public void run() {
        Log.say("ATC: Tower is open. This airport has 1 runway, " + gates.length
                + " gates and room for " + SimConfig.GROUND_CAPACITY + " planes on the ground.");
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

        Log.say("ATC: EMERGENCY STATUS acknowledged for " + request.plane + "! Giving it priority"
                + (overtaken.isEmpty() ? "." : ", so it goes ahead of " + String.join(", ", overtaken)
                + " in the landing line."));
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

    /** Returns the first free gate, or null if every gate is taken. */
    private Gate findFreeGate() {
        for (Gate gate : gates) {
            if (gate.isFree()) {
                return gate;
            }
        }
        return null;
    }

    /** Reserves the runway, a ground space and a gate for this plane, then wakes it. */
    private void grantLanding(Request request) {
        Gate gate = findFreeGate();                   // canAcceptLanding() has checked there is one
        gate.assign(request.plane);
        clearedForRunway = request.plane;
        aircraftOnGround++;
        landingsCleared++;

        long waited = Log.elapsedMillis() - request.requestedAt;
        String type = request.plane.isFuelEmergency() ? "EMERGENCY landing" : "Landing";
        Log.say("ATC: " + type + " APPROVED for " + request.plane
                + " (waited " + Log.seconds(waited) + "). The runway is free.");
        Log.say("ATC: " + request.plane + ", please go to " + gate + " after landing. Planes on the ground: "
                + aircraftOnGround + "/" + SimConfig.GROUND_CAPACITY + ".");

        request.grant(gate);                          // wake exactly this plane
        notifyAll();                                  // the main thread may be in awaitAirportFull()
    }

    /** Reserves the runway and wakes the plane. It already holds a ground space and needs no gate. */
    private void grantTakeoff(Request request) {
        clearedForRunway = request.plane;

        long waited = Log.elapsedMillis() - request.requestedAt;
        Log.say("ATC: Take-off APPROVED for " + request.plane + " (waited " + Log.seconds(waited)
                + "). The runway is free.");

        request.grant(null);
    }

    /** Tells a plane why it must wait. The plane stays blocked on its Request until it is cleared. */
    private void tellToHold(Request request) {
        if (request.kind == Kind.TAKEOFF) {
            Log.say("ATC: " + request.plane + ", please wait before the runway. "
                    + clearedForRunway + " is using it.");
            return;
        }
        Log.say("ATC: Landing DENIED for " + request.plane + " because " + reasonCannotLand(request)
                + ". Keep circling, you are number " + (landingQueue.indexOf(request) + 1) + " in line.");
    }

    /** Picks the reason given in a denied message. */
    private String reasonCannotLand(Request request) {
        if (aircraftOnGround == SimConfig.GROUND_CAPACITY) {
            return "the airport is full (" + SimConfig.GROUND_CAPACITY + "/" + SimConfig.GROUND_CAPACITY
                    + " planes on the ground)";
        }
        if (clearedForRunway != null) {
            return clearedForRunway + " is using the runway";
        }
        if (landingQueue.indexOf(request) > 0) {
            return "other planes are ahead in the line";
        }
        return "a plane taking off goes first";
    }

    // ==================================================================
    // End of simulation: sanity checks + statistics (printed by the ATC thread)
    // ==================================================================

    /** Prints the results for the brief. It takes the monitor because the fields it reads are guarded by it. */
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

            Log.say("ATC: =================== FINAL AIRPORT REPORT ===================");
            Log.say("ATC: --------------------- Sanity checks -----------------------");
            boolean allOk = true;
            for (Gate gate : gates) {
                allOk &= check(gate.isFree(), gate + " is empty.");
            }
            allOk &= check(clearedForRunway == null && runway.getAircraftOnRunway() == 0,
                    "No plane is left on the runway.");
            allOk &= check(aircraftOnGround == 0 && stats.getPlanesOnGround() == 0,
                    "No planes are left on the airport grounds (" + (SimConfig.GROUND_CAPACITY - aircraftOnGround)
                            + "/" + SimConfig.GROUND_CAPACITY + " spaces free).");
            allOk &= check(incoming.isEmpty() && landingQueue.isEmpty() && takeoffQueue.isEmpty(),
                    "No plane is still waiting to land or take off.");
            allOk &= check(!refuelTruck.hasPendingRequests(), "The refuel truck has no jobs left.");
            allOk &= check(served == SimConfig.TOTAL_PLANES,
                    "Every plane was handled (" + served + "/" + SimConfig.TOTAL_PLANES + ").");
            allOk &= check(stats.getMaxPlanesOnGround() <= SimConfig.GROUND_CAPACITY,
                    "Most planes on the grounds at one time: " + stats.getMaxPlanesOnGround()
                            + " (limit " + SimConfig.GROUND_CAPACITY + ").");
            allOk &= check(runway.getMaxAircraftOnRunway() <= 1,
                    "Most planes on the runway at one time: " + runway.getMaxAircraftOnRunway()
                            + " (limit 1), so no collisions.");
            allOk &= check(runway.getMovements() == 2 * served,
                    "Runway used " + runway.getMovements() + " times (" + served + " landings + "
                            + served + " take-offs).");
            allOk &= check(stats.getRefuelsCompleted() == served,
                    "Each plane got fuel exactly once (" + stats.getRefuelsCompleted() + " refuels).");
            allOk &= check(sumDisembarked == stats.getPassengersDisembarked() && sumBoarded == stats.getPassengersBoarded(),
                    "Passenger totals add up, so no counts were lost.");
            Log.say("ATC: Result: " + (allOk ? "ALL SANITY CHECKS PASSED" : "SOME SANITY CHECKS FAILED"));

            Log.say("ATC: ------------------------ Statistics -----------------------");
            stat("Planes served", String.valueOf(served));
            stat("Emergency landings", emergencies.size()
                    + (emergencies.isEmpty() ? "" : " (" + String.join(", ", emergencies) + ")"));
            stat("Passengers got off", String.valueOf(stats.getPassengersDisembarked()));
            stat("Passengers boarded", String.valueOf(stats.getPassengersBoarded()));
            stat("Refuels done", String.valueOf(stats.getRefuelsCompleted()));
            reportWaitingTimes("Wait to land", flights, true);
            reportWaitingTimes("Wait to take off", flights, false);
            for (Gate gate : gates) {
                stat("Planes at " + gate, String.valueOf(gate.getPlanesServed()));
            }

            Log.say("ATC: ------------------------ Each plane -----------------------");
            Log.say(String.format(Locale.ROOT, "ATC: %-8s %-7s %-9s %9s %12s %7s %6s %9s",
                    "Plane", "Gate", "Type", "Land Wait", "Takeoff Wait", "Got Off", "Got On", "On Ground"));
            for (FlightRecord f : flights) {
                Log.say(String.format(Locale.ROOT, "ATC: %-8s %-7s %-9s %9s %12s %7d %6d %9s",
                        f.planeName, f.gateName, f.emergency ? "EMERGENCY" : "normal",
                        Log.seconds(f.landingWaitMillis), Log.seconds(f.takeoffWaitMillis),
                        f.passengersDisembarked, f.passengersBoarded, Log.seconds(f.timeOnGroundMillis)));
            }
            Log.say("ATC: ===========================================================");
            Log.say("ATC: The tower is now closed.");
        }
    }

    /** Prints one sanity check as PASS or FAIL and returns the result. */
    private boolean check(boolean ok, String description) {
        Log.say("ATC: [" + (ok ? "PASS" : "FAIL") + "] " + description);
        return ok;
    }

    /** Prints one statistic as an aligned "label : value" line. */
    private void stat(String label, String value) {
        Log.say(String.format(Locale.ROOT, "ATC: %-24s: %s", label, value));
    }

    /** Prints the minimum, average and maximum wait, for landings or for take-offs. */
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
        stat(label, String.format(Locale.ROOT, "Minimum %s (%s) | Average %s | Maximum %s (%s)",
                Log.seconds(minWait), min.planeName, Log.seconds(total / flights.size()),
                Log.seconds(maxWait), max.planeName));
    }
}
