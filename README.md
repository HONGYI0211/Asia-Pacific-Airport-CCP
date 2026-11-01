# Asia-Pacific-Airport-CCP

Concurrent Programming (CT074-3-2) individual assignment: a Java simulation of the
Asia Pacific Airport, with 1 runway, 3 gates, 1 refuel truck and 6 arriving planes.

## Run

Requires JDK 8 or newer. No external libraries.

```bash
javac -d out src/airport/*.java
java -cp out airport.AirportSimulation
```

A run takes 30–35 seconds (the limit is 60). Every log line shows the elapsed time and the
**thread that printed it**, so you can see that no thread acts for another:

```
[ 11.84s] [ATC               ] ATC: EMERGENCY landing APPROVED for Plane-6 (waited 7.29s). The runway is free.
[ 11.84s] [Plane-6           ] Plane-6: Coming down onto the runway.
```

## Threads

| Thread | Class | Role |
|---|---|---|
| `Main` | `AirportSimulation` | Builds the airport, spawns 6 planes 0–2 s apart, joins them, shuts down |
| `ATC` | `AirTrafficControl` | Answers every landing/take-off request, assigns gates, prints the end-of-day report |
| `RefuelTruck` | `RefuelTruck` | The single truck; refuels one plane at a time, only when asked |
| `Plane-N` | `Plane` | One per plane: land → coast → dock → services → undock → coast → take off |
| `Plane-N-Passengers` | `PassengerGroup` | Disembark then embark (max 50 passengers) |
| `Gate-N-Crew` | `GroundCrew` | Cleans the cabin and refills supplies |

## Requirements → code

| Requirement | Where | Java facility |
|---|---|---|
| 1 runway for landing and take-off | `Runway` | Binary `Semaphore(1)`: the plane calls `acquire()` to enter and `release()` to leave |
| Max 3 aircraft on the grounds incl. runway | `AirTrafficControl.groundSlots` | Counting `Semaphore(3)`, held by each plane from landing to take-off |
| No waiting area on the ground | `AirTrafficControl.canAcceptLanding()` | A plane is only cleared to land once a gate is reserved for it |
| Planes ask permission, ATC answers | `AirTrafficControl.requestLanding/requestTakeoff` | `synchronized` monitor; each plane does `wait()` on its own `Request` and the ATC wakes it with `notify()` |
| Each step takes time | `Plane`, `SimConfig` | `Thread.sleep` |
| Passengers, cleaning/resupply and refuelling run at the same time | `Plane.turnaroundAtGate()` | Separate threads + `Thread.join()` |
| 1 refuel truck, one plane at a time, on request | `RefuelTruck` | Producer/consumer monitor: `synchronized`, `wait()`, `notifyAll()` |
| Congested scenario + emergency landing | `AirTrafficControl.awaitAirportFull()`, `AirTrafficControl.enqueue()`, `SimConfig.EMERGENCY_PLANE_ID` | Plane-6 has 5% fuel, arrives once the airport is full and is put ahead of the circling planes (ordering done by hand: `PriorityBlockingQueue` is not allowed) |
| Gates never double-booked | `Gate` | `synchronized` check-then-act; throws on collision |
| Sanity checks + statistics | `AirTrafficControl.printEndOfDayReport()` | `AtomicInteger` counters with `compareAndSet`, `synchronized` flight list |

The restricted libraries (`ExecutorService`, `ForkJoinPool`, `CompletableFuture`, parallel
streams, `Timer`, `PriorityBlockingQueue`) are not used. Every concurrency feature used
appears in the class tutorial examples.

## Assumptions

- **Ground capacity and gates.** The airport has 3 gates and allows at most 3 aircraft on the
  grounds. A plane holds its ground slot from landing clearance until it is airborne. It holds
  its gate from landing clearance until it undocks.
- **Congested scenario.** The brief's congested scenario mentions "2 gates", but the airport
  layout has 3 gates, so it is read as "all gates occupied". The first three planes fill the
  gates, the next two circle, and Plane-6 arrives with 5% fuel. Plane-6 is not started until
  three landings have been cleared, so in the rare runs where the first five planes arrive close
  together its arrival gap can run slightly over 2 s. The airport is still full (3/3) when it
  arrives: the last arrival is at most 10 s after the first, and a plane needs at least 11 s from
  landing clearance to take-off.
- **Runway priority.** Emergency landing first, then take-offs (each take-off frees a slot for
  the circling planes), then normal landings in arrival order.
- **Ground services.** Passengers, cleaning/resupply and refuelling start together when the plane
  docks. The plane undocks only after all three finish.
- **Passengers.** Each plane carries 30–50 passengers (max 50). The terminal has no capacity
  limit.
- **Waiting time.** "Waiting time" is the time from a plane's landing request to its landing
  clearance. Take-off waiting time is reported separately.
- **Thread output.** A plane's passengers report as one thread with one line per event, not
  one line per passenger.
