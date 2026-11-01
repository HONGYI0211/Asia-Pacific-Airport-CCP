package airport;

import java.util.Random;

/**
 * Every tunable number of the simulation lives here so the scenario can be
 * changed without touching the concurrency logic.
 *
 * All durations are in milliseconds.
 */
public final class SimConfig {

    private SimConfig() {
    }

    // ------------------------------------------------------------------
    // Airport layout (from the case study)
    // ------------------------------------------------------------------

    /** Total number of planes that try to land during the simulation. */
    public static final int TOTAL_PLANES = 6;

    /** Number of gates at the terminal (Gate-1, Gate-2, Gate-3). */
    public static final int NUMBER_OF_GATES = 3;

    /** Maximum number of aircraft allowed on the airport grounds, INCLUDING the runway. */
    public static final int GROUND_CAPACITY = 3;

    /** Maximum number of passengers a plane can carry. */
    public static final int MAX_PASSENGERS = 50;

    /** Smallest passenger load we generate, so every plane has some passengers. */
    public static final int MIN_PASSENGERS = 30;

    // ------------------------------------------------------------------
    // Arrivals and the emergency (congested) scenario
    // ------------------------------------------------------------------

    /** A new plane arrives every 0 - 2 seconds: sleep(rand.nextInt(2000)). */
    public static final int MAX_ARRIVAL_GAP = 2000;

    /**
     * Plane-6 arrives with a fuel shortage. AirportSimulation does not start it until
     * the ATC has cleared 3 planes, so the 3 gates are always taken and the other two
     * planes are circling when it arrives, which reproduces the congested scenario from
     * the brief. Must be higher than GROUND_CAPACITY, or that wait would never end.
     */
    public static final int EMERGENCY_PLANE_ID = 6;
    public static final int EMERGENCY_FUEL_PERCENT = 5;
    /** A plane at or below this fuel level declares an emergency. */
    public static final int LOW_FUEL_THRESHOLD = 10;
    public static final int MIN_NORMAL_FUEL_PERCENT = 30;
    public static final int MAX_NORMAL_FUEL_PERCENT = 60;

    // ------------------------------------------------------------------
    // How long each step takes ("each step should take some time")
    // ------------------------------------------------------------------

    public static final int LANDING_TIME = 1500;
    public static final int COAST_TIME = 1500;
    public static final int DOCKING_TIME = 500;
    public static final int UNDOCKING_TIME = 500;
    public static final int TAKEOFF_TIME = 1500;

    /**
     * Time for one passenger to walk off / onto the plane. Long enough that the
     * passengers of Plane-1 are still moving when the third plane docks, so passengers
     * are active at all 3 gates at the same time.
     */
    public static final int TIME_PER_PASSENGER = 80;

    public static final int CLEANING_MIN_TIME = 2000;
    public static final int CLEANING_MAX_TIME = 2500;
    public static final int RESUPPLY_MIN_TIME = 2000;
    public static final int RESUPPLY_MAX_TIME = 2500;

    /** Time for the refuel truck to drive to a gate. */
    public static final int TRUCK_DRIVE_TIME = 500;
    /** Time to pump 1% of fuel into a plane. */
    public static final int REFUEL_TIME_PER_PERCENT = 40;

    /** How often a circling plane reports that it is still waiting. */
    public static final int HOLDING_REPORT_INTERVAL = 3000;

    // ------------------------------------------------------------------
    // Random numbers
    // ------------------------------------------------------------------

    /** java.util.Random is thread-safe, so all threads can share this one generator. */
    public static final Random RANDOM = new Random();

    /** Random integer in the inclusive range [min, max]. */
    public static int randomBetween(int min, int max) {
        return min + RANDOM.nextInt(max - min + 1);
    }
}
