package airport;

import java.util.ArrayList;
import java.util.List;

/**
 * Entry point of the Asia Pacific Airport simulation.
 *
 * Threads in the simulation:
 *   Main                 - builds the airport, spawns planes, waits for them to finish
 *   ATC                  - the control tower (one)
 *   RefuelTruck          - the single refuelling truck (one)
 *   Plane-N              - one per plane (6)
 *   Plane-N-Passengers   - passengers of plane N while it is at the gate
 *   Gate-N-Crew          - cleaning / resupply crew working on the plane at gate N
 *
 * Restricted libraries (ExecutorService, ForkJoinPool, CompletableFuture, parallel
 * streams, Timer, PriorityBlockingQueue, ...) are NOT used - every thread is created,
 * started and joined explicitly.
 */
public class AirportSimulation {

    public static void main(String[] args) throws InterruptedException {
        Thread.currentThread().setName("Main");
        Log.say("Main: ========== STARTED: Asia Pacific Airport simulation==========");

        // ---- Build the airport ----
        Statistics stats = new Statistics();
        Runway runway = new Runway();
        Gate[] gates = new Gate[SimConfig.NUMBER_OF_GATES];
        for (int i = 0; i < gates.length; i++) {
            gates[i] = new Gate(i + 1);
        }
        RefuelTruck refuelTruck = new RefuelTruck(stats);
        AirTrafficControl atc = new AirTrafficControl(runway, gates, refuelTruck, stats);

        // ---- Start the long-running service threads ----
        Thread atcThread = new Thread(atc, "ATC");
        Thread truckThread = new Thread(refuelTruck, "RefuelTruck");
        atcThread.start();
        truckThread.start();

        // ---- Planes arrive every 0 - 2 seconds ----
        List<Thread> planeThreads = new ArrayList<Thread>();
        for (int id = 1; id <= SimConfig.TOTAL_PLANES; id++) {
            // Congested scenario: the emergency plane arrives once the airport is full.
            if (id == SimConfig.EMERGENCY_PLANE_ID) {
                atc.awaitAirportFull();
            }

            int fuel = (id == SimConfig.EMERGENCY_PLANE_ID)
                    ? SimConfig.EMERGENCY_FUEL_PERCENT
                    : SimConfig.randomBetween(SimConfig.MIN_NORMAL_FUEL_PERCENT, SimConfig.MAX_NORMAL_FUEL_PERCENT);
            int passengers = SimConfig.randomBetween(SimConfig.MIN_PASSENGERS, SimConfig.MAX_PASSENGERS);

            Plane plane = new Plane(id, fuel, passengers, atc, runway, refuelTruck, stats);
            Thread planeThread = new Thread(plane, plane.getName());
            planeThreads.add(planeThread);
            planeThread.start();

            if (id < SimConfig.TOTAL_PLANES) {
                Thread.sleep(SimConfig.RANDOM.nextInt(SimConfig.MAX_ARRIVAL_GAP));
            }
        }

        // ---- Wait for every plane to leave ----
        for (Thread planeThread : planeThreads) {
            planeThread.join();
        }
        Log.say("Main: Every plane has flown away. Shutting down the truck and the tower.");

        // ---- Stop the truck, then let the ATC do its sanity checks and statistics ----
        refuelTruck.shutdown();
        truckThread.join();
        atc.shutdown();
        atcThread.join();

        Log.say("Main: ========== FINISHED: Simulation took " + Log.seconds(Log.elapsedMillis()) + " ==========");
    }
}
