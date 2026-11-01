package airport;

/**
 * The passengers of one plane, running on their own thread.
 *
 * The passengers disembark and then the next group embarks. Because this is a
 * separate thread it runs at the same time as the cleaning crew, the refuel truck
 * and the passengers at the other gates.
 *
 * Passengers are always ready in the terminal (no terminal capacity limits).
 */
public class PassengerGroup implements Runnable {

    private final Plane plane;
    private final Gate gate;
    private final int arrivingPassengers;
    private final Statistics stats;

    // Written by this passenger thread, read by the plane thread AFTER join().
    // Thread.join() guarantees the plane thread sees the final value.
    private int boardedPassengers = 0;

    public PassengerGroup(Plane plane, Gate gate, int arrivingPassengers, Statistics stats) {
        this.plane = plane;
        this.gate = gate;
        this.arrivingPassengers = arrivingPassengers;
        this.stats = stats;
    }

    /** Runs on the passenger thread: the old group gets off, then the new group boards. */
    @Override
    public void run() {
        String who = plane + "'s Passengers";
        try {
            Log.say(who + ": " + arrivingPassengers + " passengers are getting off " + plane
                    + " at " + gate + ".");
            Thread.sleep(arrivingPassengers * SimConfig.TIME_PER_PASSENGER);
            stats.addPassengersDisembarked(arrivingPassengers);
            Log.say(who + ": Everyone is off " + plane + " (" + arrivingPassengers + " passengers).");

            int departing = SimConfig.randomBetween(SimConfig.MIN_PASSENGERS, SimConfig.MAX_PASSENGERS);
            Log.say(who + ": " + departing + " new passengers are boarding " + plane + " at " + gate + ".");
            Thread.sleep(departing * SimConfig.TIME_PER_PASSENGER);
            stats.addPassengersBoarded(departing);
            boardedPassengers = departing;
            Log.say(who + ": Everyone is on board " + plane + " (" + departing + " passengers).");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Only call after the passenger thread has been joined. */
    public int getBoardedPassengers() {
        return boardedPassengers;
    }
}
