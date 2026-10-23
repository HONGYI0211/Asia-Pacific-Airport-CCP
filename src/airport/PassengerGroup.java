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

    @Override
    public void run() {
        String who = plane + "'s Passengers";
        try {
            Log.say(who + ": Disembarking out of " + plane + " at " + gate
                    + " (" + arrivingPassengers + " passengers).");
            Thread.sleep(arrivingPassengers * SimConfig.TIME_PER_PASSENGER);
            stats.addPassengersDisembarked(arrivingPassengers);
            Log.say(who + ": All " + arrivingPassengers + " passengers have left " + plane + ".");

            int departing = SimConfig.randomBetween(SimConfig.MIN_PASSENGERS, SimConfig.MAX_PASSENGERS);
            Log.say(who + ": " + departing + " new passengers embarking onto " + plane + " at " + gate + ".");
            Thread.sleep(departing * SimConfig.TIME_PER_PASSENGER);
            stats.addPassengersBoarded(departing);
            boardedPassengers = departing;
            Log.say(who + ": Boarding complete, " + departing + " passengers on " + plane + ".");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Only call after the passenger thread has been joined. */
    public int getBoardedPassengers() {
        return boardedPassengers;
    }
}
