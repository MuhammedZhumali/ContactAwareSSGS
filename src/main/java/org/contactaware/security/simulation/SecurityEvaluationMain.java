package org.contactaware.security.simulation;

import org.contactaware.contacts.JsonFiles;
import org.contactaware.security.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Preregistered paired disclosure experiment. No incident information enters a policy constructor. */
public final class SecurityEvaluationMain {
    public static final SearchLimits LIMITS = new SearchLimits(100_000, Duration.ofSeconds(5));
    private static final List<String> POLICIES = List.of("fixed", "percontact", "next", "multi");
    private static final Path PROTOCOL = Path.of("docs/security-evaluation/protocol.md");
    public record CaseSpec(String name, String fixture, String policy, String mode, String baselineCase,
                           long fixedPeriodSeconds, String modelFile) {}
    public record Model(SimulationScenario scenario, Duration fixedPeriod, Map<String, String> provenance) {}
    public record Manifest(String version, String protocolSha256, String executableSha256, String javaVersion,
                            long maximumHeapBytes, SearchLimits limits, PlannerOptions plannerOptions,
                            int registeredRuns, List<CaseSpec> cases, String interpretation) {}
    public record Summary(String fixture, String policy, String mode, String baselineCase, String status,
                          SimulationResult.Metrics simulationMetrics, SecurityMetricsCollector.Metrics securityMetrics,
                          SecurityMetricsCollector.PairedDelta pairedDelta, long searchNanos, long exploredNodes,
                          int decisions, String eventsFile, String decisionsFile, String modelFile, String actualFile,
                          String error) {}
    private record Fixture(String id, SimulationScenario scenario, Duration fixedPeriod, ContextId target,
                           Instant disclosureAt, Map<String, String> provenance) {}
    private SecurityEvaluationMain() {}

    public static void main(String[] args) throws Exception {
        Path out = Path.of("target/security-evaluation-se1");
        Path schedule = PolicyExperimentFixtures.DEFAULT_SCHEDULE;
        for (int i = 0; i < args.length; i += 2) {
            if (i + 1 >= args.length) throw new IllegalArgumentException("Expected --out PATH / --schedule PATH");
            switch (args[i]) {
                case "--out" -> out = Path.of(args[i + 1]);
                case "--schedule" -> schedule = Path.of(args[i + 1]);
                default -> throw new IllegalArgumentException("Unknown option " + args[i]);
            }
        }
        run(out, schedule);
    }

    public static void run(Path destination, Path schedule) throws Exception {
        var fixtures = fixtures(schedule);
        var out = JsonFiles.newOutput(destination);
        var specs = new ArrayList<CaseSpec>();
        var scenarios = new TreeMap<String, SimulationScenario>();
        for (var fixture : fixtures) {
            var modes = new ArrayList<>(List.of("none", "undetected", "detected"));
            if (fixture.id().equals("manual-cp60")) modes.addAll(List.of("gap-notice", "later-disclosure"));
            for (var mode : modes) {
                var scenario = incidentScenario(fixture, mode);
                String modelFile = fixture.id() + "-" + mode + ".model.json";
                JsonFiles.write(out.resolve(modelFile), new Model(scenario, fixture.fixedPeriod(), fixture.provenance()));
                for (var policy : POLICIES) {
                    String name = fixture.id() + "-" + policy + "-" + mode;
                    var spec = new CaseSpec(name, fixture.id(), policy, mode, fixture.id() + "-" + policy + "-none",
                            fixture.fixedPeriod().toSeconds(), modelFile);
                    specs.add(spec); scenarios.put(name, scenario);
                }
            }
        }
        if (specs.size() != 104) throw new IllegalStateException("Registered matrix must have 104 runs");
        Path executable = Path.of(SecurityEvaluationMain.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        String executableHash = Files.isRegularFile(executable) ? JsonFiles.sha256(Files.readAllBytes(executable)) : "CLASSES_DIRECTORY_NOT_FROZEN";
        JsonFiles.write(out.resolve("manifest.json"), new Manifest("se1", JsonFiles.sha256(Files.readAllBytes(PROTOCOL)),
                executableHash, System.getProperty("java.version"), Runtime.getRuntime().maxMemory(), LIMITS,
                PlannerOptions.accelerated(), specs.size(), specs,
                "Modelled one-time traffic-key disclosure, independent authenticated management protection assumed. "
                        + "Security metrics are evaluation only. Same physical incident per policy; no target is retained. "
                        + "No-attack control first; unfinished search has no final metrics. Search wall time does not advance physical time."));
        var results = new LinkedHashMap<String, Summary>();
        var metrics = new SecurityMetricsCollector();
        for (var spec : specs) {
            System.out.println("START " + spec.name());
            var scenario = scenarios.get(spec.name());
            var result = controller(spec.policy(), Duration.ofSeconds(spec.fixedPeriodSeconds())).run(scenario);
            var fixture = new PolicyExperimentFixtures.Fixture(spec.fixture(), "AUTONOMOUS", "se1 protocol",
                    Map.of(), scenario, new SimulationEngine().start(scenario));
            var exported = FourPolicyExperimentMain.exportCase(out, spec.name(), fixture, result, 0, false);
            boolean finished = result.status() == ControlledSimulationResult.Status.FINISHED;
            var observed = finished ? metrics.collect(scenario, result.snapshot().journal()) : null;
            var base = results.get(spec.baselineCase());
            var delta = finished && base != null && base.simulationMetrics() != null
                    ? metrics.compare(base.simulationMetrics(), base.securityMetrics(), result.simulation().metrics(), observed) : null;
            var summary = new Summary(spec.fixture(), spec.policy(), spec.mode(), spec.baselineCase(), result.status().name(),
                    finished ? result.simulation().metrics() : null, observed, delta, result.totalSearchNanos(),
                    result.totalExploredNodes(), result.decisions().size(), exported.actualEventsFile(),
                    exported.compactDecisionsFile(), spec.modelFile(), exported.actualResultFile(), null);
            results.put(spec.name(), summary);
            JsonFiles.write(out.resolve("summary.json"), results);
            System.out.printf(Locale.ROOT, "DONE %s %s served=%s exposure=%s recovery=%s decisions=%d search=%.3fs%n",
                    spec.name(), result.status(), finished ? result.simulation().metrics().servedContacts() : "n/a",
                    observed == null ? "n/a" : observed.decryptableBeforeBytes() + observed.decryptableAfterBytes(),
                    observed == null ? "n/a" : observed.recoveryStatus(), result.decisions().size(), result.totalSearchNanos()/1e9);
        }
    }

    static SecurityPolicyController controller(String policy, Duration period) {
        if (policy.equals("fixed")) return new SecurityPolicyController(new FixedPeriodPolicy(period));
        if (policy.equals("percontact")) return new SecurityPolicyController(new PerContactPolicy(PerContactPolicy.AdvancePreparation.JUST_IN_TIME));
        var config = policy.equals("next") ? HorizonPolicy.next() : HorizonPolicy.multi(2);
        if (!Set.of("next", "multi").contains(policy)) throw new IllegalArgumentException("Unknown policy " + policy);
        var planner = new ExactHorizonPlanner(LIMITS, PlannerOptions.accelerated());
        return new SecurityPolicyController(new SecurityPolicy() {
            @Override public PolicyConfiguration configuration() { return config; }
            @Override public PolicySelection select(PlanningObservation observation, List<PlanningAction> candidates) {
                var search = planner.plan(observation);
                return new PolicySelection(search.status() == HorizonPlanResult.Status.COMPLETE ? search.firstAction() : null,
                        search, "First exact-horizon action; no attacker information");
            }
        });
    }

    private static SimulationScenario incidentScenario(Fixture fixture, String mode) {
        var s = fixture.scenario();
        Instant at = mode.equals("later-disclosure") ? HorizonWitness.at(18) : fixture.disclosureAt();
        Instant detect = switch (mode) {
            case "detected", "later-disclosure" -> at.plusSeconds(2);
            case "gap-notice" -> at.plusSeconds(15);
            default -> null;
        };
        var disclosures = mode.equals("none") ? List.<TrafficKeyDisclosure>of()
                : List.of(new TrafficKeyDisclosure("disclosure-1", fixture.target(), at, detect));
        var operations = new EnumMap<SecurityAction, SecurityCostProfile.OperationCost>(SecurityAction.class);
        operations.putAll(s.costs().operations());
        operations.put(SecurityAction.NOTIFY_REVOCATION, SecurityCostProfile.syntheticRevocationNotice());
        var costs = new SecurityCostProfile(s.costs().name() + "-se1-notice", SecurityCostProfile.Provenance.SYNTHETIC_ASSUMPTION,
                s.costs().source() + "; explicit se1 independent-management notice: 0.4s, 0.04s CPU, 96B, all modeled", operations);
        return new SimulationScenario(s.name() + "-" + mode, s.start(), s.end(), s.initialState(), s.constraints(),
                costs, s.contacts(), s.plan(), disclosures);
    }

    private static List<Fixture> fixtures(Path schedule) throws Exception {
        var result = new ArrayList<Fixture>();
        for (long cp : List.of(30L, 60L)) {
            var s = manualScenario(cp);
            result.add(new Fixture(s.name(), s, Duration.ofSeconds(20), HorizonWitness.A, HorizonWitness.at(10),
                    Map.of("source", "hand-checkable synthetic A-A; see protocol", "cryptoperiodSeconds", "" + cp)));
        }
        for (var sequence : HorizonWitness.Sequence.values()) for (long cp : List.of(120L, 150L)) {
            var f = PolicyExperimentFixtures.witness(sequence, false);
            var old = f.scenario(); var c = old.constraints();
            var constraints = new SecurityConstraints(Duration.ofSeconds(cp), c.maxMaterialAge(), c.maxReuseGap(),
                    c.authorizedContexts(), c.blockedKeyIds());
            String id = sequence + "-cp" + cp;
            var s = new SimulationScenario(id, old.start(), old.end(), old.initialState(), constraints, old.costs(), old.contacts(), List.of());
            result.add(new Fixture(id, s, Duration.ofSeconds(60), HorizonWitness.A, HorizonWitness.at(10), f.input()));
        }
        for (long cp : List.of(5400L, 7200L)) {
            var f = PolicyExperimentFixtures.selectedSchedule(schedule, 3, Duration.ofSeconds(cp));
            var first = f.scenario().contacts().getFirst().window();
            result.add(new Fixture("iss3-cp" + cp, f.scenario(), Duration.ofSeconds(1800),
                    new ContextId(first.satelliteId(), first.groundStationId()), first.start().plusSeconds(15), f.input()));
        }
        return List.copyOf(result);
    }

    static SimulationScenario manualScenario(long cp) {
        var id = HorizonWitness.A;
        var constraints = new SecurityConstraints(Duration.ofSeconds(cp), Duration.ofSeconds(400), Duration.ofSeconds(200), Set.of(id), Set.of());
        var transitions = new LifecycleTransitions(constraints);
        var state = transitions.createContext(SecurityState.empty(id.satelliteId(), HorizonWitness.at(-10)), id, "manual-up", "manual-down", HorizonWitness.at(-10));
        var pair = new KeyPairIds("manual-old-up", "manual-old-down");
        var past = HorizonWitness.window("manual-history", id, -5, -3);
        state = transitions.prepareKeys(state, id, pair, HorizonWitness.at(-10));
        state = transitions.confirmInstallation(state, id, pair, past, HorizonWitness.at(-5));
        state = transitions.activateKeys(state, id, pair, past, HorizonWitness.at(-5));
        state = transitions.bindKeys(state, id, pair, past, HorizonWitness.at(-5));
        var payload = new SimulationContact.PayloadProfile(100, 100, 2000, 2000, 100, 100);
        return new SimulationScenario("manual-cp" + cp, HorizonWitness.at(-2), HorizonWitness.at(45), state, constraints,
                SecurityCostProfile.syntheticWitness(), List.of(new SimulationContact(HorizonWitness.window("M1", id, 0, 20), payload),
                new SimulationContact(HorizonWitness.window("M2", id, 30, 45), payload)), List.of());
    }
}
