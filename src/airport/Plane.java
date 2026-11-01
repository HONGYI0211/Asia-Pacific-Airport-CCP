package airport;

/**
 * One aircraft. Each Plane runs on its own thread (named "Plane-N") and only ever
 * prints its OWN actions - it asks the ATC for permission and waits for the answer,
 * it never decides anything on the ATC's behalf.
 *
 * Life cycle:
 *   request landing -> land -> coast to gate -> dock
 *   -> [passengers + cleaning/resupply + refuelling run concurrently]
 *   -> undock -> coast to runway -> request take-off -> take off
 */
public class Plane implements Runnable {

    private final String name;
    private final boolean fuelEmergency;
    private final int passengersOnArrival;

    private final AirTrafficControl atc;
    private final Runway runway;
    private final RefuelTruck refuelTruck;
    private final Statistics stats;

    // The fields below are only touched by this plane's own thread (thread confinement).
    private int fuelLevel;
    private Gate gate;
    private int passengersOnDeparture;
    private long landingWait;
    private long takeoffWait;
    private long touchdownTime;

    public Plane(int id, int fuelLevel, int passengersOnArrival,
                 AirTrafficControl atc, Runway runway, RefuelTruck refuelTruck, Statistics stats) {
        this.name = "Plane-" + id;
        this.fuelLevel = fuelLevel;
        this.fuelEmergency = fuelLevel <= SimConfig.LOW_FUEL_THRESHOLD;
        this.passengersOnArrival = passengersOnArrival;
        this.atc = atc;
        this.runway = runway;
        this.refuelTruck = refuelTruck;
        this.stats = stats;
    }

    /** Runs on the plane thread: the three stages of one visit, in order. */
    @Override
    public void run() {
        try {
            approachAndLand();
            turnaroundAtGate();
            departAndTakeOff();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.say(name + ": Stopped early, flight cancelled.");
        }
    }

    // ------------------------------------------------------------------
    // 1. Arrival
    // ------------------------------------------------------------------

    private void approachAndLand() throws InterruptedException {
        if (fuelEmergency) {
            Log.say(name + ": EMERGENCY STATUS TRIGGERED! Only " + fuelLevel
                    + "% fuel left. Asking ATC for an EMERGENCY landing!");
        } else {
            Log.say(name + ": Near the airport with " + passengersOnArrival + " passengers. Asking ATC to land.");
        }

        long askedAt = Log.elapsedMillis();
        gate = atc.requestLanding(this);              // blocks while circling, until the ATC clears us
        landingWait = Log.elapsedMillis() - askedAt;

        Log.say(name + ": Coming down onto the runway.");
        runway.enter();
        stats.planeEnteredGround();
        touchdownTime = Log.elapsedMillis();
        Thread.sleep(SimConfig.LANDING_TIME);

        Log.say(name + ": Safely landed. Leaving the runway now.");
        atc.reportRunwayVacated();                    // hands the runway back to the ATC

        Log.say(name + ": Rolling slowly to " + gate + ".");
        Thread.sleep(SimConfig.COAST_TIME);
    }

    // ------------------------------------------------------------------
    // 2. At the gate
    // ------------------------------------------------------------------

    private void turnaroundAtGate() throws InterruptedException {
        gate.dock(this);
        Log.say(name + ": Reached " + gate + ". Docking now.");
        Thread.sleep(SimConfig.DOCKING_TIME);
        Log.say(name + ": Docked at " + gate + ". Ground work can start.");

        // Passengers and the cleaning crew each get their own thread ...
        PassengerGroup passengers = new PassengerGroup(this, gate, passengersOnArrival, stats);
        Thread passengerThread = new Thread(passengers, name + "-Passengers");
        Thread crewThread = new Thread(new GroundCrew(this, gate), gate + "-Crew");
        passengerThread.start();
        crewThread.start();

        // ... while this plane thread asks the (single) refuel truck for fuel.
        Log.say(name + ": Fuel is at " + fuelLevel + "%. Asking for the refuel truck.");
        refuelTruck.requestRefuel(this, gate);         // blocks until the truck has served us
        fuelLevel = 100;                               // only this thread writes it, not the truck
        Log.say(name + ": Fuel tank is full (100%).");

        // Wait for the other two concurrent activities to finish.
        passengerThread.join();
        crewThread.join();
        passengersOnDeparture = passengers.getBoardedPassengers();   // safe to read after join()

        Log.say(name + ": All ground work is done. " + passengersOnDeparture
                + " passengers on board, ready to leave.");
    }

    // ------------------------------------------------------------------
    // 3. Departure
    // ------------------------------------------------------------------

    private void departAndTakeOff() throws InterruptedException {
        Log.say(name + ": Undocking and moving away from " + gate + ".");
        Thread.sleep(SimConfig.UNDOCKING_TIME);
        gate.undock(this);
        atc.reportGateReleased();

        Log.say(name + ": Rolling slowly to the runway.");
        Thread.sleep(SimConfig.COAST_TIME);

        Log.say(name + ": At the runway. Asking ATC to take off.");
        long askedAt = Log.elapsedMillis();
        atc.requestTakeoff(this);                      // blocks until the runway is ours
        takeoffWait = Log.elapsedMillis() - askedAt;

        Log.say(name + ": Speeding down the runway to take off.");
        runway.enter();
        Thread.sleep(SimConfig.TAKEOFF_TIME);
        stats.planeLeftGround();
        Log.say(name + ": In the air now. Goodbye, Asia Pacific Airport!");

        stats.recordFlight(new FlightRecord(name, fuelEmergency, gate.toString(),
                landingWait, takeoffWait, passengersOnArrival, passengersOnDeparture,
                Log.elapsedMillis() - touchdownTime));
        atc.reportDeparted();                          // frees the runway and our ground slot
    }

    // ------------------------------------------------------------------
    // Read-only accessors (immutable data - safe to call from any thread)
    // ------------------------------------------------------------------

    public String getName() {
        return name;
    }

    public boolean isFuelEmergency() {
        return fuelEmergency;
    }

    /** Only called by this plane's own thread (when it asks for fuel). */
    public int getFuelLevel() {
        return fuelLevel;
    }

    @Override
    public String toString() {
        return name;
    }
}
