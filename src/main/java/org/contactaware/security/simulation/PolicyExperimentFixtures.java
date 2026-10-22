package org.contactaware.security.simulation;

import org.contactaware.contacts.JsonFiles;
import org.contactaware.contacts.Model.ContactWindow;
import org.contactaware.contacts.schedule.ScheduleFiles;
import org.contactaware.security.*;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Common, policy-independent inputs. Geometry is preserved; security and traffic remain model assumptions. */
public final class PolicyExperimentFixtures {
    public static final Path DEFAULT_SCHEDULE = Path.of("data/runs/iss-20261010/schedule/schedule.json");
    public static final SimulationContact.PayloadProfile FINITE_SCHEDULE_TRAFFIC =
            new SimulationContact.PayloadProfile(100, 1000, 1000, 1000, 1000, 1000);

    public enum TrafficWorkload { LIGHT, MODERATE, SATURATED }

    public record ContactPayloadFeasibility(String contactId, Duration windowDuration, long minimumUplinkFrames,
                                             long minimumDownlinkFrames, Duration requiredPayloadTime,
                                             boolean fitsWithoutSecurityOverhead) {}
    public record PayloadFeasibility(long payloadOnlyServiceCeiling, int contacts, String interpretation,
                                      List<ContactPayloadFeasibility> perContact) {
        public PayloadFeasibility { perContact = List.copyOf(perContact); }
    }

    private PolicyExperimentFixtures() {}

    public record Fixture(String id, String initialization, String provenance, Map<String, String> input,
                          SimulationScenario scenario, ExecutionSnapshot checkpoint) {
        public Fixture { input = Collections.unmodifiableMap(new TreeMap<>(input)); }
    }

    /** Both modes begin at t=-2 with the same old material. Only prepared mode prescribes the common warmup. */
    public static Fixture witness(HorizonWitness.Sequence sequence, boolean prepared) {
        var original = HorizonWitness.scenario(sequence, HorizonWitness.Plan.NEXT);
        var id = sequence + (prepared ? "-prepared" : "-autonomous");
        var warmup = prepared ? List.of(HorizonWitness.operation("warmup", SecurityAction.PREPARE,
                -2, HorizonWitness.A, HorizonWitness.NEW_A, null)) : List.<PlannedOperation>of();
        var scenario = new SimulationScenario(id, original.start(), original.end(), original.initialState(),
                original.constraints(), original.costs(), original.contacts(), warmup);
        var engine = new SimulationEngine();
        var checkpoint = engine.start(scenario);
        if (prepared) checkpoint = engine.advance(checkpoint, HorizonWitness.at(0));
        return new Fixture(id, prepared ? "COMMON_PREPARATION" : "AUTONOMOUS_PREPARATION",
                "SYNTHETIC_ASSUMPTION: HorizonWitness contacts, payload, prior material and operation costs. "
                        + "All policies share the same t=-2 start; actual warmup costs are included once.",
                Map.of("source", "HorizonWitness", "sequence", sequence.name()), scenario, checkpoint);
    }

    /** A deterministic prefix of a verified schedule; zero selects every original window. */
    public static Fixture selectedSchedule(Path path, int contactLimit, Duration cryptoperiod) throws Exception {
        return selectedSchedule(path, contactLimit, cryptoperiod, 1);
    }

    public static Fixture selectedSchedule(Path path, int contactLimit, Duration cryptoperiod, int costScale) throws Exception {
        return selectedSchedule(path, contactLimit, cryptoperiod, costScale, TrafficWorkload.LIGHT);
    }

    public static Fixture selectedSchedule(Path path, int contactLimit, Duration cryptoperiod, int costScale,
                                           TrafficWorkload workload) throws Exception {
        Objects.requireNonNull(workload);
        if (contactLimit < 0) throw new IllegalArgumentException("Contact limit must be nonnegative");
        var verified = ScheduleFiles.readVerified(path);
        var schedule = verified.schedule();
        if (schedule.contacts().isEmpty()) throw new IllegalArgumentException("Selected schedule is empty");
        if (contactLimit > schedule.contacts().size()) throw new IllegalArgumentException("Contact limit exceeds schedule size");
        var windows = contactLimit == 0 ? schedule.contacts() : schedule.contacts().subList(0, contactLimit);
        var costs = scaledCosts(costScale);
        var start = windows.getFirst().start().minus(costs.cost(SecurityAction.PREPARE).duration());
        var contexts = new TreeSet<ContextId>();
        for (var window : windows) contexts.add(new ContextId(window.satelliteId(), window.groundStationId()));
        var constraints = new SecurityConstraints(cryptoperiod, Duration.ofDays(2), Duration.ofDays(1), contexts, Set.of());
        var transitions = new LifecycleTransitions(constraints);
        var initial = SecurityState.empty(schedule.satelliteId(), start);
        for (var context : contexts) initial = transitions.createContext(initial, context,
                context.groundStationId() + "-SA-up", context.groundStationId() + "-SA-down", start);
        var id = "schedule" + windows.size() + "-cp" + cryptoperiod.toSeconds() + "-cost" + costScale
                + (workload == TrafficWorkload.LIGHT ? "" : "-" + workload.name().toLowerCase(Locale.ROOT));
        var scenario = new SimulationScenario(id, start, windows.getLast().end(), initial, constraints, costs,
                windows.stream().map(w -> new SimulationContact(w, traffic(workload, w))).toList(), List.of());
        var input = new TreeMap<String, String>();
        input.put("schedulePath", path.toAbsolutePath().normalize().toString());
        input.put("scheduleSha256", JsonFiles.sha256(Files.readAllBytes(path)));
        input.put("datasetSource", schedule.datasetSource());
        input.put("datasetSha256", schedule.inputDatasetSha256());
        input.put("datasetKind", schedule.datasetKind());
        input.put("selection", "First " + windows.size() + " windows in the verified chronological schedule; original IDs and nanosecond timestamps unchanged");
        input.put("availableSelectedContacts", Integer.toString(schedule.contacts().size()));
        input.put("costScale", Integer.toString(costScale));
        input.put("costScaling", "Every security phase duration and management-byte count multiplied by the same positive integer; management radio B/s unchanged, CPU and elapsed overhead increase together. Payload unchanged. Model sensitivity, not hardware calibration.");
        input.put("trafficWorkload", workload.name());
        input.put("traffic", trafficDescription(workload));
        input.put("payloadOnlyServiceCeiling", Long.toString(payloadFeasibility(scenario.contacts()).payloadOnlyServiceCeiling()));
        input.put("payloadOnlyServiceCeilingMeaning", "Required complete frames fit the original window without any security overhead; optimistic geometry-only bound, not achieved service or a key-feasibility guarantee");
        return new Fixture(id, "EMPTY_KEYS_AUTONOMOUS", "Original selected geometric contact windows; not observed authenticated communications. "
                + "Contexts are configured at the common start with no keys or fabricated past contacts. "
                + "Security costs, authorization, payload and lifecycle limits are synthetic model assumptions, not hardware measurements.",
                input, scenario, new SimulationEngine().start(scenario));
    }

    /** Finite registered workloads; saturated offered demand cannot constrain a full-window payload run. */
    public static SimulationContact.PayloadProfile traffic(TrafficWorkload workload, ContactWindow window) {
        Objects.requireNonNull(workload); Objects.requireNonNull(window);
        if (workload == TrafficWorkload.LIGHT) return FINITE_SCHEDULE_TRAFFIC;
        if (workload == TrafficWorkload.MODERATE)
            return new SimulationContact.PayloadProfile(100, 1000, 3000, 3000, 3000, 3000);
        long frames = Math.max(1, Duration.between(window.start(), window.end()).dividedBy(Duration.ofSeconds(10)));
        long bytes = Math.multiplyExact(frames, 1000L);
        return new SimulationContact.PayloadProfile(100, 1000, bytes, bytes, 1000, 1000);
    }

    public static PayloadFeasibility payloadFeasibility(List<SimulationContact> contacts) {
        var results = new ArrayList<ContactPayloadFeasibility>();
        for (var contact : contacts) {
            var p = contact.payload();
            long up = requiredFrames(p.minimumUplinkBytes(), p.frameBytes());
            long down = requiredFrames(p.minimumDownlinkBytes(), p.frameBytes());
            var requiredTime = p.frameDuration().multipliedBy(Math.addExact(up, down));
            var windowTime = Duration.between(contact.window().start(), contact.window().end());
            results.add(new ContactPayloadFeasibility(contact.window().id(), windowTime, up, down,
                    requiredTime, requiredTime.compareTo(windowTime) <= 0));
        }
        return new PayloadFeasibility(results.stream().filter(ContactPayloadFeasibility::fitsWithoutSecurityOverhead).count(),
                contacts.size(), "Optimistic payload-only upper bound: required complete frames must fit the window. "
                + "Excludes security operations, key availability, cryptoperiod and counters; not achievable-service certification.", results);
    }

    private static long requiredFrames(long bytes, long frameBytes) {
        return bytes / frameBytes + (bytes % frameBytes == 0 ? 0 : 1);
    }

    private static String trafficDescription(TrafficWorkload workload) {
        return "SYNTHETIC_ASSUMPTION: 100 B/s, 1000 B complete frames (10 s). " + switch (workload) {
            case LIGHT -> "One frame offered and required per direction per contact. Useful time is demand-capped at 20 s/contact, not link utilization.";
            case MODERATE -> "Three frames offered and required per direction per contact. Useful demand and service threshold are 60 s/contact; windows shorter than 60 s are structurally unservable even without security overhead.";
            case SATURATED -> "One frame required per direction; each direction offers max(1, floor(window duration / 10 s)) frames. This finite offered budget cannot restrict full-window payload utilization; the service threshold remains 20 s/contact.";
        };
    }

    /** Scale all modeled security work, preserving management-channel B/s; local termination remains instantaneous. */
    public static SecurityCostProfile scaledCosts(int factor) {
        if (factor <= 0) throw new IllegalArgumentException("Cost scale must be a positive integer");
        var original = SecurityCostProfile.syntheticWitness();
        if (factor == 1) return original;
        var operations = new EnumMap<SecurityAction, SecurityCostProfile.OperationCost>(SecurityAction.class);
        original.operations().forEach((action, cost) -> operations.put(action, new SecurityCostProfile.OperationCost(
                cost.phases().stream().map(phase -> new SecurityCostProfile.Phase(phase.resource(),
                        phase.duration().multipliedBy(factor), Math.multiplyExact(phase.bytes(), (long) factor))).toList())));
        // Operation totals also must fit the model's exact nanosecond arithmetic.
        operations.values().forEach(cost -> { cost.duration().toNanos(); cost.total(); });
        return new SecurityCostProfile(original.name() + "-scale-" + factor,
                SecurityCostProfile.Provenance.SYNTHETIC_ASSUMPTION,
                original.source() + "; all security phase durations and bytes multiplied by " + factor
                        + ", preserving management radio rate; synthetic cost sensitivity only", operations);
    }
}
