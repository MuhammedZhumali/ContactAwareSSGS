package org.contactaware.security.simulation;

import org.contactaware.contacts.JsonFiles;
import org.contactaware.security.SecurityState;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Offline, bounded four-policy experiments with separate factual logs and compact comparisons. */
public final class FourPolicyExperimentMain {
    private FourPolicyExperimentMain() {}

    public record Options(String scope, String mode, Path output, Path schedule, List<String> policies,
                          SearchLimits limits, Duration fixedPeriod, Duration cryptoperiod,
                          PerContactPolicy.AdvancePreparation perContactPreparation, int multiHorizon,
                          int costScale, boolean trace, int contactCount, PolicyExperimentFixtures.TrafficWorkload traffic) {
        public Options { policies = List.copyOf(policies); }

        public Options(String scope, String mode, Path output, Path schedule, List<String> policies,
                       SearchLimits limits, Duration fixedPeriod, Duration cryptoperiod,
                       PerContactPolicy.AdvancePreparation perContactPreparation, int multiHorizon,
                       int costScale, boolean trace) {
            this(scope, mode, output, schedule, policies, limits, fixedPeriod, cryptoperiod, perContactPreparation,
                    multiHorizon, costScale, trace, scope.equals("schedule3") ? 3 : scope.equals("schedule46") ? 46 : 0,
                    scope.equals("xy") ? null : PolicyExperimentFixtures.TrafficWorkload.LIGHT);
        }
    }
    public record FiveMetrics(long servedContacts, Duration usefulSecureTime, Duration controlCpu,
                              long managementBytes, long newKeys) {}
    public record PartialProgress(Instant at, long servedContactsSoFar, Duration usefulSecureTimeSoFar,
                                  ResourceUsage recordedCosts, ResourceUsage accruedCosts,
                                  long createdKeysSoFar, int pendingOperations, boolean frameInFlight) {}
    public record Summary(String fixture, String policyName, int horizon, PolicyConfiguration policyConfiguration,
                          ControlledSimulationResult.Status status, Instant stoppedAt,
                          FiveMetrics finalMetrics, PartialProgress partialProgress,
                          int decisions, long ruleDecisions, Map<HorizonPlanResult.Status, Long> searchStatuses,
                          long exploredNodes, long searchNanos, long controllerWallNanos,
                          String actualEventsFile, String actualResultFile, String compactDecisionsFile,
                          SearchPerformance searchPerformance) {}
    public record SearchPerformance(long searchDecisions, long totalSearchNanos, long maximumSearchNanos,
                                    BigDecimal meanSearchNanos, long totalExploredNodes, long maximumExploredNodes,
                                    BigDecimal meanExploredNodes, long totalGeneratedActions, long totalExecutedTransitions,
                                    long totalMemoHits, long totalBoundPrunedNodes, long totalCanonicalBoundPrunedNodes,
                                    Map<String, Long> stopReasons) {
        public SearchPerformance { stopReasons = Collections.unmodifiableMap(new TreeMap<>(stopReasons)); }
    }
    public record CompactDecision(Instant at, Instant resumedAt, ControlledSimulationResult.DecisionSource source,
                                  String reason, PlanningAction executedAction,
                                  HorizonPlanResult.Status searchStatus, Long exploredNodes, Long searchNanos,
                                  int visibleContacts, Instant evaluationEnd, String searchStopReason,
                                  SearchDiagnostics searchDiagnostics, ControlledSimulationResult.ContinuationAudit continuation) {}
    public record ActualResult(ControlledSimulationResult.Status status, Instant at, SecurityState securityState,
                               SimulationResult.Metrics finalMetrics, PartialProgress partialProgress,
                               List<ExecutionSnapshot.PendingOperation> pendingOperations, ExecutionSnapshot.InFlightFrame inFlightFrame,
                               Set<org.contactaware.security.ContextId> uncertainContexts) {}
    public record FixtureModel(String id, String initialization, String provenance, Map<String, String> input,
                               SimulationScenario scenario, Instant controllerHandoffAt,
                               ResourceUsage costsAtHandoff, long createdKeysAtHandoff,
                               PolicyExperimentFixtures.PayloadFeasibility payloadFeasibility) {}
    public record Manifest(String schemaVersion, Options options, PlannerOptions plannerOptions,
                           String accounting, String interpretation, List<String> fixtureModels,
                           ExecutionEnvironment executionEnvironment) {}
    public record ExecutionEnvironment(String javaVersion, String vmName, String vmVersion, String osName,
                                       String osArchitecture, int availableProcessors, long maximumHeapBytes) {
        static ExecutionEnvironment current() {
            return new ExecutionEnvironment(System.getProperty("java.version"), System.getProperty("java.vm.name"),
                    System.getProperty("java.vm.version"), System.getProperty("os.name"), System.getProperty("os.arch"),
                    Runtime.getRuntime().availableProcessors(), Runtime.getRuntime().maxMemory());
        }
    }

    public static void main(String[] args) throws Exception { run(parse(args)); }

    public static void run(Options options) throws Exception {
        var fixtures = fixtures(options);
        var out = JsonFiles.newOutput(options.output());
        var models = new ArrayList<String>();
        for (var fixture : fixtures) {
            var file = fixture.id() + ".model.json";
            models.add(file);
            JsonFiles.write(out.resolve(file), new FixtureModel(fixture.id(), fixture.initialization(), fixture.provenance(),
                    fixture.input(), fixture.scenario(), fixture.checkpoint().at(), fixture.checkpoint().accruedCosts(),
                    fixture.checkpoint().createdKeys(), PolicyExperimentFixtures.payloadFeasibility(fixture.scenario().contacts())));
        }
        JsonFiles.write(out.resolve("manifest.json"), new Manifest("1.0", options, PlannerOptions.accelerated(),
                "Final metrics are collected from each complete actual journal, including common preparation once. "
                        + "Partial progress is not a final score; accrued costs already include recorded costs. "
                        + "Forecast scores are never summed. Search time is separate from security CPU and does not advance simulated time.",
                "Rule baselines perform no search and have no COMPLETE search certificate. Next and Multi use the same exact solver, "
                        + "constraints, actions and tie-break; only the visible horizon differs. COMPLETE certifies a finite nominal "
                        + "horizon search, not a globally optimal receding policy. Selected orbital windows are geometric inputs, "
                        + "with synthetic finite traffic and security parameters. No hardware, attack or compromise-risk claim.",
                models, ExecutionEnvironment.current()));
        var summaries = new LinkedHashMap<String, Summary>();
        for (var fixture : fixtures) for (var policy : options.policies()) {
            var name = fixture.id() + "-" + policy;
            System.out.println("Starting " + name);
            long started = System.nanoTime();
            var result = execute(fixture, policy, options);
            long elapsed = System.nanoTime() - started;
            var summary = exportCase(out, name, fixture, result, elapsed, options.trace());
            summaries.put(name, summary);
            // Persist each case: a later bounded search does not erase completed observations.
            JsonFiles.write(out.resolve("summary.json"), summaries);
            System.out.printf(Locale.ROOT, "%s: %s at=%s decisions=%d nodes=%d search=%.3fs final=%s%n",
                    name, summary.status(), summary.stoppedAt(), summary.decisions(), summary.exploredNodes(),
                    summary.searchNanos() / 1e9, summary.finalMetrics());
        }
    }

    static ControlledSimulationResult execute(PolicyExperimentFixtures.Fixture fixture, String policy, Options options) {
        return switch (policy) {
            case "fixed" -> new SecurityPolicyController(new FixedPeriodPolicy(options.fixedPeriod())).run(fixture.checkpoint());
            case "percontact" -> new SecurityPolicyController(new PerContactPolicy(options.perContactPreparation())).run(fixture.checkpoint());
            case "next", "multi" -> {
                var horizon = policy.equals("next") ? HorizonPolicy.next() : HorizonPolicy.multi(options.multiHorizon());
                var exact = new ExactHorizonPlanner(options.limits(), PlannerOptions.accelerated());
                HorizonPlanner traced = observation -> {
                    var search = exact.plan(observation);
                    if (search.elapsedNanos() >= 1_000_000_000L || search.status() != HorizonPlanResult.Status.COMPLETE)
                        System.out.printf(Locale.ROOT, "  %s/%s at %s: %s nodes=%d search=%.3fs%n", fixture.id(), policy,
                                observation.at(), search.status(), search.exploredNodes(), search.elapsedNanos() / 1e9);
                    return search;
                };
                yield new RecedingHorizonController(horizon, traced).run(fixture.checkpoint());
            }
            default -> throw new IllegalArgumentException("Unknown policy " + policy);
        };
    }

    static Summary exportCase(Path out, String name, PolicyExperimentFixtures.Fixture fixture,
                              ControlledSimulationResult result, long wallNanos, boolean trace) throws Exception {
        var snapshot = result.snapshot();
        var collected = new MetricsCollector().collect(snapshot.scenario().contacts(), snapshot.journal());
        boolean finished = result.status() == ControlledSimulationResult.Status.FINISHED;
        if (finished && !collected.equals(result.simulation().metrics()))
            throw new IllegalStateException("Actual journal disagrees with final metrics for " + name);
        var finalMetrics = finished ? new FiveMetrics(collected.servedContacts(), collected.usefulSecureTime(),
                collected.controlCosts().totalCpu(), collected.controlCosts().managementBytes(), collected.createdKeys()) : null;
        var partial = finished ? null : new PartialProgress(snapshot.at(), collected.servedContacts(), collected.usefulSecureTime(),
                snapshot.recordedCosts(), snapshot.accruedCosts(), snapshot.createdKeys(), snapshot.pendingOperations().size(),
                snapshot.inFlightFrame() != null);
        var eventsFile = name + ".events.json";
        var actualFile = name + ".actual.json";
        var decisionsFile = name + ".decisions.json";
        JsonFiles.write(out.resolve(eventsFile), snapshot.journal());
        JsonFiles.write(out.resolve(actualFile), new ActualResult(result.status(), snapshot.at(), snapshot.securityState(),
                finished ? collected : null, partial, snapshot.pendingOperations(), snapshot.inFlightFrame(), snapshot.uncertainContexts()));
        var compact = result.decisions().stream().map(d -> new CompactDecision(d.at(), d.resumedAt(), d.source(), d.reason(),
                d.executedAction(), d.search() == null ? null : d.search().status(),
                d.search() == null ? null : d.search().exploredNodes(), d.search() == null ? null : d.search().elapsedNanos(),
                d.observation().visibleContacts().size(), d.observation().evaluationEnd(),
                d.search() == null ? null : d.search().diagnostics().stopReason().name(),
                d.search() == null ? null : d.search().diagnostics(), d.continuation())).toList();
        JsonFiles.write(out.resolve(decisionsFile), compact);
        if (trace) JsonFiles.write(out.resolve(name + ".full-trace.json"), result);
        var statuses = new EnumMap<HorizonPlanResult.Status, Long>(HorizonPlanResult.Status.class);
        for (var d : result.decisions()) if (d.search() != null) statuses.merge(d.search().status(), 1L, Math::addExact);
        return new Summary(fixture.id(), result.policy().name(), result.policy().horizon(), result.policy(), result.status(),
                snapshot.at(), finalMetrics, partial, result.decisions().size(),
                result.decisions().stream().filter(d -> d.source() == ControlledSimulationResult.DecisionSource.RULE).count(),
                statuses, result.totalExploredNodes(), result.totalSearchNanos(), wallNanos, eventsFile, actualFile, decisionsFile,
                searchPerformance(result.decisions()));
    }

    static SearchPerformance searchPerformance(List<ControlledSimulationResult.Decision> decisions) {
        long count = 0, time = 0, maxTime = 0, nodes = 0, maxNodes = 0;
        long generated = 0, executed = 0, memo = 0, bounded = 0, canonicalBounded = 0;
        var reasons = new TreeMap<String, Long>();
        for (var decision : decisions) {
            var search = decision.search();
            if (search == null) continue;
            count = Math.incrementExact(count);
            time = Math.addExact(time, search.elapsedNanos());
            maxTime = Math.max(maxTime, search.elapsedNanos());
            nodes = Math.addExact(nodes, search.exploredNodes());
            maxNodes = Math.max(maxNodes, search.exploredNodes());
            var d = search.diagnostics();
            generated = Math.addExact(generated, d.generatedActions());
            executed = Math.addExact(executed, d.executedTransitions());
            memo = Math.addExact(memo, d.memoHits());
            bounded = Math.addExact(bounded, d.boundPrunedNodes());
            canonicalBounded = Math.addExact(canonicalBounded, d.canonicalBoundPrunedNodes());
            reasons.merge(d.stopReason().name(), 1L, Math::addExact);
        }
        return new SearchPerformance(count, time, maxTime, mean(time, count), nodes, maxNodes, mean(nodes, count),
                generated, executed, memo, bounded, canonicalBounded, reasons);
    }

    private static BigDecimal mean(long total, long count) {
        return count == 0 ? null : BigDecimal.valueOf(total).divide(BigDecimal.valueOf(count), 6, RoundingMode.HALF_UP);
    }

    static List<PolicyExperimentFixtures.Fixture> fixtures(Options options) throws Exception {
        if (!options.scope().equals("xy")) {
            return List.of(PolicyExperimentFixtures.selectedSchedule(options.schedule(), options.contactCount(),
                    options.cryptoperiod(), options.costScale(), options.traffic()));
        }
        var fixtures = new ArrayList<PolicyExperimentFixtures.Fixture>();
        for (var sequence : HorizonWitness.Sequence.values()) {
            if (!options.mode().equals("autonomous")) fixtures.add(PolicyExperimentFixtures.witness(sequence, true));
            if (!options.mode().equals("prepared")) fixtures.add(PolicyExperimentFixtures.witness(sequence, false));
        }
        return List.copyOf(fixtures);
    }

    static Options parse(String[] args) {
        var flags = new HashMap<String, String>();
        var allowed = Set.of("scope", "mode", "out", "schedule", "policies", "max-nodes", "max-seconds",
                "fixed-period-seconds", "cryptoperiod-seconds", "per-contact", "horizon", "multi-horizon", "cost-scale", "trace",
                "contact-count", "traffic");
        for (int i = 0; i < args.length; i += 2) {
            if (!args[i].startsWith("--") || i + 1 == args.length) throw new IllegalArgumentException(usage());
            var flag = args[i].substring(2);
            if (!allowed.contains(flag) || flags.putIfAbsent(flag, args[i + 1]) != null)
                throw new IllegalArgumentException("Unknown or repeated option: " + args[i] + "\n" + usage());
        }
        var scope = flags.getOrDefault("scope", "xy");
        var mode = flags.getOrDefault("mode", "both");
        if (!Set.of("xy", "schedule", "schedule3", "schedule46").contains(scope) || !Set.of("prepared", "autonomous", "both").contains(mode))
            throw new IllegalArgumentException(usage());
        if (scope.equals("xy") && flags.containsKey("cryptoperiod-seconds"))
            throw new IllegalArgumentException("--cryptoperiod-seconds applies only to schedule scopes; xy preserves the agreed witness constraints");
        if (!scope.equals("xy") && mode.equals("prepared"))
            throw new IllegalArgumentException("Schedule fixtures use autonomous preparation, so --mode prepared is unsupported");
        if (scope.equals("xy") && (flags.containsKey("contact-count") || flags.containsKey("traffic")))
            throw new IllegalArgumentException("--contact-count and --traffic apply only to schedule scopes; xy inputs are fixed");
        if (scope.equals("schedule") && !flags.containsKey("contact-count"))
            throw new IllegalArgumentException("--scope schedule requires an explicit --contact-count in 1..46");
        int count = Integer.parseInt(flags.getOrDefault("contact-count",
                scope.equals("schedule3") ? "3" : scope.equals("schedule46") ? "46" : "0"));
        if (!scope.equals("xy") && (count < 1 || count > 46))
            throw new IllegalArgumentException("--contact-count must be in 1..46");
        if (scope.equals("schedule3") && count != 3 || scope.equals("schedule46") && count != 46)
            throw new IllegalArgumentException("Named schedule3/schedule46 aliases have fixed contact counts; use --scope schedule");
        var traffic = switch (flags.getOrDefault("traffic", "light")) {
            case "light" -> PolicyExperimentFixtures.TrafficWorkload.LIGHT;
            case "moderate" -> PolicyExperimentFixtures.TrafficWorkload.MODERATE;
            case "saturated" -> PolicyExperimentFixtures.TrafficWorkload.SATURATED;
            default -> throw new IllegalArgumentException("--traffic must be light, moderate or saturated");
        };
        var policies = Arrays.stream(flags.getOrDefault("policies", "fixed,percontact,next,multi").split(",", -1)).map(String::trim).toList();
        if (policies.isEmpty() || new HashSet<>(policies).size() != policies.size()
                || !Set.of("fixed", "percontact", "next", "multi").containsAll(policies)) throw new IllegalArgumentException("Invalid policy selection");
        var preparation = switch (flags.getOrDefault("per-contact", "just-in-time")) {
            case "contact-only" -> PerContactPolicy.AdvancePreparation.CONTACT_ONLY;
            case "just-in-time" -> PerContactPolicy.AdvancePreparation.JUST_IN_TIME;
            default -> throw new IllegalArgumentException("Unknown per-contact preparation mode");
        };
        if (flags.containsKey("horizon") && flags.containsKey("multi-horizon"))
            throw new IllegalArgumentException("Use only --multi-horizon (--horizon is an alias)");
        int horizon = Integer.parseInt(flags.getOrDefault("multi-horizon", flags.getOrDefault("horizon", "2")));
        if (horizon < 2) throw new IllegalArgumentException("Multi horizon must be at least 2");
        int costScale = Integer.parseInt(flags.getOrDefault("cost-scale", "1"));
        if (costScale <= 0 || scope.equals("xy") && costScale != 1)
            throw new IllegalArgumentException("--cost-scale must be positive and is supported only for schedule scopes; xy preserves the agreed t=-2 warmup");
        var fixed = seconds(flags.getOrDefault("fixed-period-seconds", scope.equals("xy") ? "60" : "3600"));
        var cryptoperiod = scope.equals("xy") ? HorizonWitness.CONSTRAINTS.cryptoperiod()
                : seconds(flags.getOrDefault("cryptoperiod-seconds", "7200"));
        if (fixed.isZero() || fixed.isNegative() || cryptoperiod.isZero() || cryptoperiod.isNegative())
            throw new IllegalArgumentException("Lifecycle periods must be positive");
        var trace = flags.getOrDefault("trace", "false");
        if (!Set.of("true", "false").contains(trace)) throw new IllegalArgumentException("--trace expects true or false");
        return new Options(scope, mode, Path.of(flags.getOrDefault("out", "target/four-policy-experiment")),
                Path.of(flags.getOrDefault("schedule", PolicyExperimentFixtures.DEFAULT_SCHEDULE.toString())), policies,
                new SearchLimits(Long.parseLong(flags.getOrDefault("max-nodes", "100000")), seconds(flags.getOrDefault("max-seconds", "5"))),
                fixed, cryptoperiod, preparation, horizon, costScale, Boolean.parseBoolean(trace), count,
                scope.equals("xy") ? null : traffic);
    }

    private static Duration seconds(String value) {
        return Duration.ofNanos(new BigDecimal(value).movePointRight(9).longValueExact());
    }
    private static String usage() {
        return "FourPolicyExperimentMain [--scope xy|schedule|schedule3|schedule46] [--mode prepared|autonomous|both] "
                + "[--out NEW_OR_EMPTY_DIRECTORY] [--policies fixed,percontact,next,multi] [--max-nodes 100000] "
                + "[--max-seconds 5; 0=unlimited] [--fixed-period-seconds 60(xy)/3600(schedule)] "
                + "[--cryptoperiod-seconds 7200; schedule only] [--per-contact just-in-time|contact-only] "
                + "[--multi-horizon 2] [--cost-scale 1; positive integer, schedule only] [--schedule PATH] "
                + "[--contact-count 1..46; required for --scope schedule] [--traffic light|moderate|saturated; schedule only] [--trace true|false]";
    }
}
