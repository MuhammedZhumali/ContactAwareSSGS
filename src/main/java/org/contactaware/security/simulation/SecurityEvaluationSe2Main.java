package org.contactaware.security.simulation;

import org.contactaware.contacts.JsonFiles;
import org.contactaware.security.ContextId;

import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Frozen, schedule-derived SE2 matrix. Registration never runs a policy. */
public final class SecurityEvaluationSe2Main {
    public static final SearchLimits LIMITS = new SearchLimits(100_000, Duration.ofSeconds(5));
    static final List<String> POLICIES = List.of("fixed", "percontact", "next", "multi");
    static final Path PROTOCOL = Path.of("docs/security-evaluation-se2/protocol.md");
    public record CaseSpec(String name, String fixture, String block, String workload, String policy, String mode,
                           String baselineCase, String modelFile, long fixedPeriodSeconds, String attackPoint,
                           Instant attackAt, DecisionTimeModel timingModel, int noticeDurationScale,
                           List<String> attackSources) {}
    public record Model(SimulationScenario scenario, Duration fixedPeriod, Map<String, String> provenance,
                        String referenceFixture) {}
    public record ConfigurationSpec(String id, String block, String workload, long fixedPeriodSeconds,
                                    DecisionTimeModel timingModel, int noticeDurationScale, ContextId target,
                                    Instant originalAttackAt, boolean fullAttackGrid,
                                    List<SecurityEvaluationSe2Fixtures.AttackPoint> points,
                                    String referenceFixture, Map<String, String> provenance) {}
    public record Manifest(String version, String protocolSha256, String executableSha256, String scheduleSha256,
                            String javaVersion, long maximumHeapBytes, SearchLimits limits,
                            PlannerOptions plannerOptions, int registeredRuns,
                            List<ConfigurationSpec> configurations, List<CaseSpec> cases,
                            Map<String, String> modelSha256, String interpretation) {}
    public record Summary(String fixture, String block, String workload, String policy, String mode,
                          String baselineCase, String status, SimulationResult.Metrics simulationMetrics,
                          SecurityMetricsCollector.Metrics securityMetrics, SecurityMetricsCollector.PairedDelta pairedDelta,
                          long searchNanos, long exploredNodes, int decisions, String eventsFile, String decisionsFile,
                          String modelFile, String actualFile, String error, String attackPoint, Instant attackAt,
                          DecisionTimeModel timingModel, int noticeDurationScale) {}
    record Registration(List<ConfigurationSpec> configurations, List<CaseSpec> cases, Map<String, Model> models) {}
    private SecurityEvaluationSe2Main() {}

    public static void main(String[] args) throws Exception {
        Path out = Path.of("target/security-evaluation-se2");
        Path schedule = PolicyExperimentFixtures.DEFAULT_SCHEDULE;
        String command = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--register", "--run" -> {
                    if (command != null) throw new IllegalArgumentException("Choose exactly one of --register / --run");
                    command = args[i];
                }
                case "--out", "--schedule" -> {
                    String key = args[i];
                    if (++i >= args.length) throw new IllegalArgumentException("Missing path after " + key);
                    if (key.equals("--out")) out = Path.of(args[i]); else schedule = Path.of(args[i]);
                }
                default -> throw new IllegalArgumentException("Unknown option " + args[i]);
            }
        }
        if (command == null) throw new IllegalArgumentException("Use --register first, then --run with the same frozen JAR and inputs");
        if (command.equals("--register")) register(out, schedule); else run(out, schedule);
    }

    static Registration registration(Path schedule) throws Exception {
        var specifications = new ArrayList<ConfigurationSpec>();
        var cases = new ArrayList<CaseSpec>();
        var models = new TreeMap<String, Model>();
        for (var c : SecurityEvaluationSe2Fixtures.configurations(schedule)) {
            var points = SecurityEvaluationSe2Fixtures.attackPoints(c);
            specifications.add(new ConfigurationSpec(c.id(), c.block(), c.workload(), c.fixedPeriod().toSeconds(),
                    c.timingModel(), c.noticeDurationScale(), c.target(), c.originalAttackAt(), c.fullAttackGrid(),
                    points, referenceFixture(c), c.fixture().input()));
            addCases(c, null, "none", cases, models);
            for (var point : points) for (var mode : List.of("undetected", "detected"))
                addCases(c, point, mode, cases, models);
        }
        if (cases.size() != 488 || specifications.size() != 26)
            throw new IllegalStateException("SE2 registration must have 26 configurations and 488 runs; got "
                    + specifications.size() + "/" + cases.size());
        if (cases.stream().map(CaseSpec::name).distinct().count() != cases.size())
            throw new IllegalStateException("Duplicate registered case name");
        return new Registration(List.copyOf(specifications), List.copyOf(cases), Collections.unmodifiableMap(models));
    }

    private static void addCases(SecurityEvaluationSe2Fixtures.Configuration c,
                                 SecurityEvaluationSe2Fixtures.AttackPoint point, String mode,
                                 List<CaseSpec> cases, Map<String, Model> models) {
        var scenario = incidentScenario(c.fixture().scenario(), c.target(), point == null ? null : point.at(), mode);
        String suffix = point == null ? "none" : point.id() + "-" + mode;
        String modelFile = c.id() + "-" + suffix + ".model.json";
        if (models.putIfAbsent(modelFile, new Model(scenario, c.fixedPeriod(), c.fixture().input(), referenceFixture(c))) != null)
            throw new IllegalStateException("Duplicate model file " + modelFile);
        for (var policy : POLICIES) cases.add(new CaseSpec(c.id() + "-" + policy + "-" + suffix,
                c.id(), c.block(), c.workload(), policy, mode, c.id() + "-" + policy + "-none", modelFile,
                c.fixedPeriod().toSeconds(), point == null ? null : point.id(), point == null ? null : point.at(),
                c.timingModel(), c.noticeDurationScale(), point == null ? List.of() : point.sources()));
    }

    private static String referenceFixture(SecurityEvaluationSe2Fixtures.Configuration configuration) {
        return configuration.fixture().input().getOrDefault("referenceFixture", configuration.fixture().id());
    }

    static SimulationScenario incidentScenario(SimulationScenario base, ContextId target, Instant at, String mode) {
        if (!Set.of("none", "undetected", "detected").contains(mode)) throw new IllegalArgumentException("Unknown mode " + mode);
        var incidents = mode.equals("none") ? List.<TrafficKeyDisclosure>of()
                : List.of(new TrafficKeyDisclosure("disclosure-1", target, Objects.requireNonNull(at),
                mode.equals("detected") ? at.plusSeconds(2) : null));
        return new SimulationScenario(base.name() + "-" + mode + (at == null ? "" : "-" + at),
                base.start(), base.end(), base.initialState(), base.constraints(), base.costs(), base.contacts(), base.plan(), incidents);
    }

    public static void register(Path destination, Path schedule) throws Exception {
        var registration = registration(schedule);
        String executableHash = executableHash();
        var out = JsonFiles.newOutput(destination);
        var hashes = new TreeMap<String, String>();
        for (var model : registration.models().entrySet()) {
            JsonFiles.write(out.resolve(model.getKey()), model.getValue());
            hashes.put(model.getKey(), hash(out.resolve(model.getKey())));
        }
        JsonFiles.write(out.resolve("manifest.json"), new Manifest("se2", hash(PROTOCOL), executableHash, hash(schedule),
                System.getProperty("java.version"), Runtime.getRuntime().maxMemory(), LIMITS, PlannerOptions.accelerated(),
                registration.cases().size(), registration.configurations(), registration.cases(), hashes,
                "Deterministic registered grid, not a probability model. One physical attack per comparison; no target is retained. "
                        + "Independent newly generated traffic keys and authenticated independent management protection are assumptions. "
                        + "Shared actual executor and mandatory response; policy inputs do not contain future attacks. "
                        + "Each attack uses its identical-policy no-attack control. Partial/ERROR/NOT_RUN rows have no final quality score. "
                        + "100000 nodes or 5 seconds per decision, no adaptive escalation. Search time is separate from physical time."));
        System.out.println("REGISTERED " + registration.cases().size() + " cases; no policies executed; manifest=" + out.resolve("manifest.json"));
    }

    public static void run(Path out, Path schedule) throws Exception {
        if (Files.exists(out.resolve("summary.json")))
            throw new IllegalArgumentException("Refusing to overwrite an existing run summary; register a fresh directory for a new run");
        var registration = registration(schedule);
        verifyRegistration(out, schedule, registration);
        var results = new LinkedHashMap<String, Summary>();
        JsonFiles.write(out.resolve("summary.json"), results);
        var metrics = new SecurityMetricsCollector();
        for (var spec : registration.cases()) {
            System.out.println("START " + spec.name());
            Summary summary;
            try {
                var model = registration.models().get(spec.modelFile());
                var scenario = model.scenario();
                long started = System.nanoTime();
                var result = new SecurityPolicyController(policy(spec.policy(), Duration.ofSeconds(spec.fixedPeriodSeconds()),
                        spec.timingModel())).run(scenario);
                var fixture = new PolicyExperimentFixtures.Fixture(spec.fixture(), "AUTONOMOUS", "SE2 frozen protocol",
                        model.provenance(), scenario, new SimulationEngine().start(scenario));
                var exported = FourPolicyExperimentMain.exportCase(out, spec.name(), fixture, result, System.nanoTime() - started, false);
                boolean finished = result.status() == ControlledSimulationResult.Status.FINISHED;
                var observed = finished ? metrics.collect(scenario, result.snapshot().journal()) : null;
                var baseline = results.get(spec.baselineCase());
                var delta = finished && baseline != null && baseline.simulationMetrics() != null
                        ? metrics.compare(baseline.simulationMetrics(), baseline.securityMetrics(), result.simulation().metrics(), observed) : null;
                summary = new Summary(spec.fixture(), spec.block(), spec.workload(), spec.policy(), spec.mode(), spec.baselineCase(),
                        result.status().name(), finished ? result.simulation().metrics() : null, observed, delta,
                        result.totalSearchNanos(), result.totalExploredNodes(), result.decisions().size(),
                        exported.actualEventsFile(), exported.compactDecisionsFile(), spec.modelFile(), exported.actualResultFile(),
                        null, spec.attackPoint(), spec.attackAt(), spec.timingModel(), spec.noticeDurationScale());
            } catch (Exception error) {
                summary = new Summary(spec.fixture(), spec.block(), spec.workload(), spec.policy(), spec.mode(), spec.baselineCase(),
                        "ERROR", null, null, null, 0, 0, 0, null, null, spec.modelFile(), null,
                        error.getClass().getName() + ": " + error.getMessage(), spec.attackPoint(), spec.attackAt(),
                        spec.timingModel(), spec.noticeDurationScale());
                error.printStackTrace(System.err);
            }
            results.put(spec.name(), summary);
            JsonFiles.write(out.resolve("summary.json"), results);
            System.out.printf(Locale.ROOT, "DONE %s %s recovery=%s decisions=%d search=%.3fs%n", spec.name(), summary.status(),
                    summary.securityMetrics() == null ? "n/a" : summary.securityMetrics().recoveryStatus(),
                    summary.decisions(), summary.searchNanos() / 1e9);
        }
    }

    static void verifyRegistration(Path out, Path schedule, Registration registration) throws Exception {
        var manifest = JsonFiles.JSON.readTree(out.resolve("manifest.json").toFile());
        requireEqual(manifest.path("version").asText(), "se2", "version");
        requireEqual(manifest.path("protocolSha256").asText(), hash(PROTOCOL), "protocol");
        requireEqual(manifest.path("executableSha256").asText(), executableHash(), "executable");
        requireEqual(manifest.path("scheduleSha256").asText(), hash(schedule), "schedule");
        requireEqual(manifest.path("javaVersion").asText(), System.getProperty("java.version"), "Java runtime");
        requireEqual(manifest.path("maximumHeapBytes").asLong(), Runtime.getRuntime().maxMemory(), "maximum heap");
        requireEqual(manifest.path("cases"), serializedTree(registration.cases()), "cases");
        requireEqual(manifest.path("configurations"), serializedTree(registration.configurations()), "configurations");
        requireEqual(manifest.path("limits"), serializedTree(LIMITS), "search limits");
        requireEqual(manifest.path("plannerOptions"), serializedTree(PlannerOptions.accelerated()), "planner options");
        requireEqual(manifest.path("registeredRuns").asInt(), registration.cases().size(), "case count");
        requireEqual(manifest.path("modelSha256").size(), registration.models().size(), "model count");
        for (var entry : registration.models().entrySet()) {
            var path = out.resolve(entry.getKey());
            requireEqual(manifest.path("modelSha256").path(entry.getKey()).asText(), hash(path), "model file " + entry.getKey());
            requireEqual(JsonFiles.JSON.readTree(path.toFile()), serializedTree(entry.getValue()), "regenerated model " + entry.getKey());
        }
    }

    private static void requireEqual(Object expected, Object actual, String subject) {
        if (!Objects.equals(expected, actual)) throw new IllegalStateException("Frozen registration mismatch: " + subject);
    }
    // Normalize through the same JSON parser: valueToTree(Duration) may use DecimalNode,
    // while a persisted tree uses DoubleNode, despite identical serialized values.
    private static com.fasterxml.jackson.databind.JsonNode serializedTree(Object value) throws Exception {
        return JsonFiles.JSON.readTree(JsonFiles.JSON.writeValueAsBytes(value));
    }
    private static String hash(Path path) throws Exception { return JsonFiles.sha256(Files.readAllBytes(path)); }
    private static String executableHash() throws Exception {
        var path = Path.of(SecurityEvaluationSe2Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        if (!Files.isRegularFile(path)) throw new IllegalStateException("SE2 registration/execution requires a frozen JAR, not a classes directory");
        return hash(path);
    }

    /** Timing sensitivity adds the same finite timer to both actual and forecast execution. */
    static SecurityPolicy policy(String name, Duration period, DecisionTimeModel timing) {
        SecurityPolicy delegate = switch (name) {
            case "fixed" -> new FixedPeriodPolicy(period);
            case "percontact" -> new PerContactPolicy(PerContactPolicy.AdvancePreparation.JUST_IN_TIME);
            case "next", "multi" -> {
                var horizon = name.equals("next") ? HorizonPolicy.next() : HorizonPolicy.multi(2);
                var planner = new ExactHorizonPlanner(LIMITS, PlannerOptions.accelerated(), timing);
                yield new SecurityPolicy() {
                    @Override public PolicyConfiguration configuration() { return horizon; }
                    @Override public PolicySelection select(PlanningObservation observation, List<PlanningAction> candidates) {
                        var search = planner.plan(observation);
                        return new PolicySelection(search.status() == HorizonPlanResult.Status.COMPLETE ? search.firstAction() : null,
                                search, "Exact horizon; fixed SE2 budget; no future attack information");
                    }
                };
            }
            default -> throw new IllegalArgumentException("Unknown policy " + name);
        };
        return new SecurityPolicy() {
            @Override public PolicyConfiguration configuration() { return delegate.configuration(); }
            @Override public PolicySelection select(PlanningObservation observation, List<PlanningAction> candidates) {
                return delegate.select(observation, candidates);
            }
            @Override public List<Instant> additionalWakeUps(PlanningObservation observation) {
                var wakeUps = new TreeSet<>(delegate.additionalWakeUps(observation));
                wakeUps.addAll(timing.additionalWakeUps(observation));
                return List.copyOf(wakeUps);
            }
        };
    }
}
