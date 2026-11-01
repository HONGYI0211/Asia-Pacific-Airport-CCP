package airport;

/**
 * One of the terminal gates.
 *
 * A gate goes through three states:
 *   FREE  --(ATC assigns it)-->  ASSIGNED  --(plane docks)-->  OCCUPIED
 *   OCCUPIED  --(plane undocks)-->  FREE
 *
 * The ATC thread assigns the gate, but the plane thread docks and undocks, so the
 * state is shared between threads. Every method is synchronized, which makes each
 * check-then-act (e.g. "is it free? then take it") atomic. If two planes ever
 * ended up at the same gate an IllegalStateException is thrown immediately.
 */
public class Gate {

    private final String name;

    private Plane assignedPlane = null;   // reserved by the ATC when landing is granted
    private Plane dockedPlane = null;     // the plane physically at the gate
    private int planesServed = 0;         // number of planes that have used this gate

    public Gate(int number) {
        this.name = "Gate-" + number;
    }

    /** Called by the ATC thread when it grants a landing. */
    public synchronized void assign(Plane plane) {
        if (assignedPlane != null || dockedPlane != null) {
            throw new IllegalStateException(name + " is already taken - cannot assign it to " + plane);
        }
        assignedPlane = plane;
    }

    /** Called by the plane thread when it reaches the gate. */
    public synchronized void dock(Plane plane) {
        if (assignedPlane != plane) {
            throw new IllegalStateException(plane + " tried to dock at " + name + " which is assigned to " + assignedPlane);
        }
        if (dockedPlane != null) {
            throw new IllegalStateException("COLLISION at " + name + ": " + plane + " and " + dockedPlane);
        }
        dockedPlane = plane;
    }

    /** Called by the plane thread when it pushes back. Frees the gate. */
    public synchronized void undock(Plane plane) {
        if (dockedPlane != plane) {
            throw new IllegalStateException(plane + " is not docked at " + name);
        }
        dockedPlane = null;
        assignedPlane = null;
        planesServed++;
    }

    /** True if no plane is assigned to or docked at this gate. */
    public synchronized boolean isFree() {
        return assignedPlane == null && dockedPlane == null;
    }

    public synchronized int getPlanesServed() {
        return planesServed;
    }

    @Override
    public String toString() {
        return name;
    }
}
