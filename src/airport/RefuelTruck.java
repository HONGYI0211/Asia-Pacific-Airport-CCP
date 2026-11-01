package airport;

import java.util.LinkedList;

/**
 * The airport's ONE refuelling truck, running on its own thread.
 *
 * This is a classic producer / consumer monitor built with synchronized,
 * wait() and notifyAll():
 *   - producers: plane threads call requestRefuel(), which adds a job to the queue
 *   - consumer : the truck thread takes jobs one at a time and refuels the plane
 *
 * Because there is a single consumer thread, only one plane can ever be refuelled
 * at a time, and the truck only moves when a plane has asked for it.
 */
public class RefuelTruck implements Runnable {

    /** One refuel request. The requesting plane waits on this object until it is done. */
    private static final class RefuelJob {
        final String planeName;
        final Gate gate;
        final int fuelBefore;
        private boolean done = false;    // guarded by this job's monitor

        RefuelJob(String planeName, Gate gate, int fuelBefore) {
            this.planeName = planeName;
            this.gate = gate;
            this.fuelBefore = fuelBefore;
        }

        /** Called by the truck thread when the plane is full. */
        synchronized void markDone() {
            done = true;
            notifyAll();
        }

        /** Called by the plane thread; blocks until the truck has finished. */
        synchronized void awaitDone() throws InterruptedException {
            while (!done) {          // loop guards against spurious wake-ups
                wait();
            }
        }
    }

    // Guarded by "this" (the RefuelTruck monitor).
    private final LinkedList<RefuelJob> jobs = new LinkedList<RefuelJob>();
    private boolean shutdownRequested = false;

    private final Statistics stats;

    public RefuelTruck(Statistics stats) {
        this.stats = stats;
    }

    /**
     * Called by a PLANE thread. Puts the request in the truck's queue and blocks
     * until the truck has refuelled this plane.
     */
    public void requestRefuel(Plane plane, Gate gate) throws InterruptedException {
        RefuelJob job = new RefuelJob(plane.getName(), gate, plane.getFuelLevel());
        synchronized (this) {
            jobs.addLast(job);
            notifyAll();             // wake the truck if it is idle
        }
        job.awaitDone();
    }

    /** Called by the main thread once every plane has left. */
    public synchronized void shutdown() {
        shutdownRequested = true;
        notifyAll();
    }

    /** Used by the ATC's sanity check at the end of the run. */
    public synchronized boolean hasPendingRequests() {
        return !jobs.isEmpty();
    }

    /** The truck thread's life: wait for a request, serve it, repeat. */
    @Override
    public void run() {
        Log.say("Refuel Truck: Parked at the fuel depot, ready for requests.");
        try {
            while (true) {
                RefuelJob job;
                synchronized (this) {
                    while (jobs.isEmpty() && !shutdownRequested) {
                        wait();      // releases the monitor while idle - no busy waiting
                    }
                    if (jobs.isEmpty()) {
                        break;       // shutdown requested and nothing left to do
                    }
                    job = jobs.removeFirst();
                }
                // Refuel OUTSIDE the monitor so other planes can still queue requests.
                serve(job);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Log.say("Refuel Truck: All jobs done. Going back to the depot.");
    }

    /** Drives to the gate and refuels one plane. The truck thread holds no monitor here. */
    private void serve(RefuelJob job) throws InterruptedException {
        Log.say("Refuel Truck: On the way to " + job.gate + " for " + job.planeName + ".");
        Thread.sleep(SimConfig.TRUCK_DRIVE_TIME);

        Log.say("Refuel Truck: Filling up " + job.planeName + " at " + job.gate
                + " (" + job.fuelBefore + "% to 100%).");
        Thread.sleep((100 - job.fuelBefore) * SimConfig.REFUEL_TIME_PER_PERCENT);

        Log.say("Refuel Truck: " + job.planeName + " is full. Job done.");
        stats.refuelCompleted();
        job.markDone();
    }
}
