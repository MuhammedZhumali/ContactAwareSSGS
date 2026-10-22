package org.contactaware.security.simulation;

import org.contactaware.contacts.JsonFiles;
import org.contactaware.contacts.Model.ContactWindow;
import org.contactaware.contacts.schedule.ScheduleFiles;
import org.contactaware.security.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Preregistered SE2 physical inputs; no policy or future execution is consulted. */
public final class SecurityEvaluationSe2Fixtures {
    private SecurityEvaluationSe2Fixtures() {}
    public record Configuration(String id, String block, String workload, PolicyExperimentFixtures.Fixture fixture,
                                Duration fixedPeriod, DecisionTimeModel timingModel, int noticeDurationScale,
                                ContextId target, Instant originalAttackAt, boolean fullAttackGrid) {}
    public record AttackPoint(String id, Instant at, List<String> sources) {
        public AttackPoint { sources = List.copyOf(sources); }
    }

    public static List<Configuration> configurations(Path schedule) throws Exception {
        var all = new ArrayList<Configuration>();
        for (var seq : HorizonWitness.Sequence.values())
            all.add(config(seq.name(), "attack-time", "WITNESS", PolicyExperimentFixtures.witness(seq, false),
                    Duration.ofSeconds(60), true));
        for (long cp : List.of(5400L, 7200L))
            all.add(config("iss3-cp" + cp, "attack-time", "LIGHT", fragment(schedule, 0, 3, cp,
                    PolicyExperimentFixtures.TrafficWorkload.LIGHT), Duration.ofSeconds(1800), true));
        var primary = List.copyOf(all);
        for (long cp : List.of(5400L, 7200L)) for (var load : List.of(
                PolicyExperimentFixtures.TrafficWorkload.MODERATE, PolicyExperimentFixtures.TrafficWorkload.SATURATED))
            all.add(config("iss3-cp" + cp + "-" + load.name().toLowerCase(Locale.ROOT), "load", load.name(),
                    fragment(schedule, 0, 3, cp, load), Duration.ofSeconds(1800), false));
        for (long length : List.of(2L, 12L)) all.add(config("X-length" + length, "contacts", "WITNESS",
                changedWitness(80, length), Duration.ofSeconds(60), false));
        for (long start : List.of(60L, 100L)) all.add(config("X-start" + start, "contacts", "WITNESS",
                changedWitness(start, 5), Duration.ofSeconds(60), false));
        for (int from : List.of(3, 6)) for (long cp : List.of(5400L, 7200L))
            all.add(config("iss-fragment" + from + "-cp" + cp, "fragments", "LIGHT",
                    fragment(schedule, from, 3, cp, PolicyExperimentFixtures.TrafficWorkload.LIGHT), Duration.ofSeconds(1800), false));
        all.add(config("X-start100-length12", "combinations", "WITNESS", changedWitness(100, 12), Duration.ofSeconds(60), false));
        all.add(config("iss-fragment3-cp5400-moderate", "combinations", "MODERATE",
                fragment(schedule, 3, 3, 5400, PolicyExperimentFixtures.TrafficWorkload.MODERATE), Duration.ofSeconds(1800), false));
        for (var base : primary) {
            all.add(variant(base, "-alt-wait", DecisionTimeModel.PREPARE_DURATION_WAIT, 1));
            all.add(variant(base, "-notice2", DecisionTimeModel.V2_EVENT_BOUNDARIES, 2));
        }
        if (all.size() != 26 || all.stream().map(Configuration::id).distinct().count() != all.size())
            throw new IllegalStateException("SE2 configuration matrix changed");
        return List.copyOf(all);
    }

    private static Configuration config(String id, String block, String workload, PolicyExperimentFixtures.Fixture fixture,
                                        Duration period, boolean grid) {
        var f = noticeFixture(id, fixture, 1);
        var first = f.scenario().contacts().getFirst().window();
        var target = new ContextId(first.satelliteId(), first.groundStationId());
        var at = workload.equals("WITNESS") ? HorizonWitness.at(10) : first.start().plusSeconds(15);
        return new Configuration(id, block, workload, f, period, DecisionTimeModel.V2_EVENT_BOUNDARIES, 1, target, at, grid);
    }

    private static Configuration variant(Configuration base, String suffix, DecisionTimeModel model, int scale) {
        return new Configuration(base.id() + suffix, "assumptions", base.workload(),
                noticeFixture(base.id() + suffix, base.fixture(), scale), base.fixedPeriod(), model, scale,
                base.target(), base.originalAttackAt(), false);
    }

    static PolicyExperimentFixtures.Fixture noticeFixture(String id, PolicyExperimentFixtures.Fixture base, int scale) {
        if (scale < 1) throw new IllegalArgumentException("Positive notice duration scale required");
        var s = base.scenario();
        var operations = new EnumMap<SecurityAction, SecurityCostProfile.OperationCost>(SecurityAction.class);
        operations.putAll(s.costs().operations());
        operations.put(SecurityAction.NOTIFY_REVOCATION, new SecurityCostProfile.OperationCost(
                SecurityCostProfile.syntheticRevocationNotice().phases().stream().map(p ->
                        new SecurityCostProfile.Phase(p.resource(), p.duration().multipliedBy(scale), p.bytes())).toList()));
        var costs = new SecurityCostProfile("se2-synthetic-notice-duration-" + scale,
                SecurityCostProfile.Provenance.SYNTHETIC_ASSUMPTION,
                s.costs().source() + "; SE2 notice: base phase durations multiplied by " + scale + ", 96 bytes unchanged", operations);
        var scenario = new SimulationScenario(id, s.start(), s.end(), s.initialState(), s.constraints(), costs, s.contacts(), List.of());
        var input = new TreeMap<>(base.input());
        input.put("referenceFixture", base.id()); input.put("noticeDurationScale", "" + scale);
        return new PolicyExperimentFixtures.Fixture(id, base.initialization(), base.provenance(), input,
                scenario, new SimulationEngine().start(scenario));
    }

    /** Changes only C3 geometry, preserving the complete original key history and all traffic parameters. */
    static PolicyExperimentFixtures.Fixture changedWitness(long thirdStart, long thirdLength) {
        var base = PolicyExperimentFixtures.witness(HorizonWitness.Sequence.X, false);
        var s = base.scenario(); var old = s.contacts().get(2);
        var w = old.window();
        var changed = new ContactWindow(w.id(), w.satelliteId(), w.groundStationId(), HorizonWitness.at(thirdStart),
                HorizonWitness.at(thirdStart + thirdLength), w.linkRateBps(), w.expectedDataBytes(), w.sourceRecordIds());
        var contacts = new ArrayList<>(s.contacts()); contacts.set(2, new SimulationContact(changed, old.payload()));
        var scenario = new SimulationScenario("X-C3-" + thirdStart + "-" + thirdLength, s.start(), changed.end(), s.initialState(),
                s.constraints(), s.costs(), contacts, List.of());
        return new PolicyExperimentFixtures.Fixture(scenario.name(), base.initialization(), base.provenance(),
                Map.of("source", "HorizonWitness X", "thirdStartSeconds", "" + thirdStart,
                        "thirdLengthSeconds", "" + thirdLength, "history", "Original X state unchanged"),
                scenario, new SimulationEngine().start(scenario));
    }

    static PolicyExperimentFixtures.Fixture fragment(Path path, int from, int count, long cp,
                                                    PolicyExperimentFixtures.TrafficWorkload load) throws Exception {
        // Reuse all existing physical/payload/security setup for the primary prefix.
        if (from == 0) {
            var base = PolicyExperimentFixtures.selectedSchedule(path, count, Duration.ofSeconds(cp), 1, load);
            var input = new TreeMap<>(base.input());
            input.put("fragmentStartIndex", "0"); input.put("fragmentCount", "" + count);
            return new PolicyExperimentFixtures.Fixture(base.id(), base.initialization(), base.provenance(), input,
                    base.scenario(), base.checkpoint());
        }
        var schedule = ScheduleFiles.readVerified(path).schedule();
        var windows = schedule.contacts().subList(from, from + count);
        var costs = PolicyExperimentFixtures.scaledCosts(1);
        var start = windows.getFirst().start().minus(costs.cost(SecurityAction.PREPARE).duration());
        var contexts = new TreeSet<ContextId>();
        windows.forEach(w -> contexts.add(new ContextId(w.satelliteId(), w.groundStationId())));
        var constraints = new SecurityConstraints(Duration.ofSeconds(cp), Duration.ofDays(2), Duration.ofDays(1), contexts, Set.of());
        var transitions = new LifecycleTransitions(constraints);
        var state = SecurityState.empty(schedule.satelliteId(), start);
        for (var c : contexts) state = transitions.createContext(state, c, c.groundStationId()+"-SA-up", c.groundStationId()+"-SA-down", start);
        var id = "iss-fragment" + from + "-cp" + cp + "-" + load.name().toLowerCase(Locale.ROOT);
        var scenario = new SimulationScenario(id, start, windows.getLast().end(), state, constraints, costs,
                windows.stream().map(w -> new SimulationContact(w, PolicyExperimentFixtures.traffic(load, w))).toList(), List.of());
        return new PolicyExperimentFixtures.Fixture(id, "EMPTY_KEYS_AUTONOMOUS",
                "Verified original geometry; independent cold start with synthetic security and traffic, not observed secure communications.",
                Map.of("schedulePath", path.toAbsolutePath().normalize().toString(),
                        "scheduleSha256", JsonFiles.sha256(Files.readAllBytes(path)),
                        "datasetSha256", schedule.inputDatasetSha256(), "datasetSource", schedule.datasetSource(),
                        "fragmentStartIndex", "" + from, "fragmentCount", "" + count, "trafficWorkload", load.name(),
                        "payloadOnlyServiceCeiling", "" + PolicyExperimentFixtures.payloadFeasibility(scenario.contacts()).payloadOnlyServiceCeiling()),
                scenario, new SimulationEngine().start(scenario));
    }

    public static List<AttackPoint> attackPoints(Configuration c) {
        var points = new LinkedHashMap<Instant, List<String>>();
        add(points, "original", c.originalAttackAt());
        if (c.fullAttackGrid()) {
            var windows = c.fixture().scenario().contacts().stream().map(SimulationContact::window).toList();
            var first = windows.stream().filter(w -> c.target().equals(new ContextId(w.satelliteId(), w.groundStationId())))
                    .findFirst().orElseThrow();
            long nanos = Duration.between(first.start(), first.end()).toNanos();
            for (int pct : List.of(10,30,50,70,90))
                add(points, "p" + pct, first.start().plusNanos(Math.multiplyExact(nanos, pct) / 100));
            windows.stream().filter(w -> !w.start().isBefore(first.end())).findFirst()
                    .filter(w -> w.start().isAfter(first.end())).ifPresent(w -> add(points, "gap-midpoint",
                            first.end().plusNanos(Duration.between(first.end(), w.start()).toNanos() / 2)));
        }
        return points.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(e -> new AttackPoint(e.getValue().getFirst(), e.getKey(), e.getValue())).toList();
    }
    private static void add(Map<Instant, List<String>> points, String source, Instant at) {
        points.computeIfAbsent(at, ignored -> new ArrayList<>()).add(source);
    }
}
