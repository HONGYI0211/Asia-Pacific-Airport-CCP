package airport;

/**
 * Immutable summary of one plane's visit, created by the plane thread just before
 * it leaves. Because every field is final the object can be handed to other
 * threads (the ATC) without any extra synchronization.
 */
public final class FlightRecord {

    public final String planeName;
    public final boolean emergency;
    public final String gateName;
    public final long landingWaitMillis;    // landing request to approval
    public final long takeoffWaitMillis;    // take-off request to approval
    public final int passengersDisembarked;
    public final int passengersBoarded;
    public final long timeOnGroundMillis;   // touchdown until airborne

    public FlightRecord(String planeName, boolean emergency, String gateName,
                        long landingWaitMillis, long takeoffWaitMillis,
                        int passengersDisembarked, int passengersBoarded,
                        long timeOnGroundMillis) {
        this.planeName = planeName;
        this.emergency = emergency;
        this.gateName = gateName;
        this.landingWaitMillis = landingWaitMillis;
        this.takeoffWaitMillis = takeoffWaitMillis;
        this.passengersDisembarked = passengersDisembarked;
        this.passengersBoarded = passengersBoarded;
        this.timeOnGroundMillis = timeOnGroundMillis;
    }
}
