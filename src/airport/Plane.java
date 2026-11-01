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

    @Override
    public void run() {
        try {
            approachAndLand();
            turnaroundAtGate();
            departAndTakeOff();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.say(name + ": Interrupted - flight aborted.");
        }
    }

    // ------------------------------------------------------------------
    // 1. Arrival
    // ------------------------------------------------------------------

    private void approachAndLand() throws InterruptedException {
        if (fuelEmergency) {
            Log.say(name + ": MAYDAY MAYDAY MAYDAY! Fuel critically low (" + fuelLevel
                    + "%). Requesting EMERGENCY landing!");
        } else {
            Log.say(name + ": Approaching with " + passengersOnArrival + " passengers. Requesting landing.");
        }

        long askedAt = Log.elapsedMillis();
        gate = atc.requestLanding(this);              // blocks while circling, until the ATC clears us
        landingWait = Log.elapsedMillis() - askedAt;

        Log.say(name + ": Landing on the runway.");
        runway.enter();
        stats.planeEnteredGround();
        touchdownTime = Log.elapsedMillis();
        Thread.sleep(SimConfig.LANDING_TIME);

        Log.say(name + ": Landed. Vacating the runway.");
        atc.reportRunwayVacated();                    // hands the runway back to the ATC

        Log.say(name + ": Coasting to " + gate + ".");
        Thread.sleep(SimConfig.COAST_TIME);
    }

    // ------------------------------------------------------------------
    // 2. At the gate
    // ------------------------------------------------------------------

    private void turnaroundAtGate() throws InterruptedException {
        gate.dock(this);
        Log.say(name + ": Docking at " + gate + ".");
        Thread.sleep(SimConfig.DOCKING_TIME);
        Log.say(name + ": Docked at " + gate + ". Starting ground services.");

        // Passengers and the cleaning crew each get their own thread ...
        PassengerGroup passengers = new PassengerGroup(this, gate, passengersOnArrival, stats);
        Thread passengerThread = new Thread(passengers, name + "-Passengers");
        Thread crewThread = new Thread(new GroundCrew(this, gate), gate + "-Crew");
        passengerThread.start();
        crewThread.start();

        // ... while this plane thread asks the (single) refuel truck for fuel.
        Log.say(name + ": Requesting the refuel truck (fuel at " + fuelLevel + "%).");
        refuelTruck.requestRefuel(this, gate);         // blocks until the truck has served us
        fuelLevel = 100;
        Log.say(name + ": Refuelling complete. Fuel at 100%.");

        // Wait for the other two concurrent activities to finish.
        passengerThread.join();
        crewThread.join();
        passengersOnDeparture = passengers.getBoardedPassengers();

        Log.say(name + ": All ground services complete. " + passengersOnDeparture
                + " passengers on board, ready to depart.");
    }

    // ------------------------------------------------------------------
    // 3. Departure
    // ------------------------------------------------------------------

    private void departAndTakeOff() throws InterruptedException {
        Log.say(name + ": Undocking from " + gate + ".");
        Thread.sleep(SimConfig.UNDOCKING_TIME);
        gate.undock(this);
        atc.reportGateReleased();

        Log.say(name + ": Coasting to the runway.");
        Thread.sleep(SimConfig.COAST_TIME);

        Log.say(name + ": Requesting take-off.");
        long askedAt = Log.elapsedMillis();
        atc.requestTakeoff(this);                      // blocks until the runway is ours
        takeoffWait = Log.elapsedMillis() - askedAt;

        Log.say(name + ": Taking off.");
        runway.enter();
        Thread.sleep(SimConfig.TAKEOFF_TIME);
        stats.planeLeftGround();
        Log.say(name + ": Airborne. Leaving Asia Pacific Airport. Goodbye!");

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
