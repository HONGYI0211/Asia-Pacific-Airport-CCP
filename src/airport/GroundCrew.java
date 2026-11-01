package airport;

/**
 * The ground crew at a gate, running on its own thread.
 *
 * Cleans the cabin and refills supplies (food, water, amenities) while the
 * passengers and the refuel truck are working on the same plane.
 */
public class GroundCrew implements Runnable {

    private final Plane plane;
    private final Gate gate;

    public GroundCrew(Plane plane, Gate gate) {
        this.plane = plane;
        this.gate = gate;
    }

    /** Runs on the crew thread: cleans the cabin, then loads supplies. */
    @Override
    public void run() {
        String who = gate + " Crew";
        try {
            Log.say(who + ": Cleaning inside " + plane + ".");
            Thread.sleep(SimConfig.randomBetween(SimConfig.CLEANING_MIN_TIME, SimConfig.CLEANING_MAX_TIME));

            Log.say(who + ": Loading food, water and supplies onto " + plane + ".");
            Thread.sleep(SimConfig.randomBetween(SimConfig.RESUPPLY_MIN_TIME, SimConfig.RESUPPLY_MAX_TIME));

            Log.say(who + ": " + plane + " is clean and fully stocked.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
