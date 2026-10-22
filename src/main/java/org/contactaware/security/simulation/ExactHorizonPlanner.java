package org.contactaware.security.simulation;

import java.util.*;
import java.util.function.LongSupplier;
import java.util.function.UnaryOperator;

/** Exact DFS over a frozen observation, with an accessible unoptimized reference mode. */
public final class ExactHorizonPlanner implements HorizonPlanner {
    private final SearchLimits limits;
    private final PlannerOptions options;
    private final UnaryOperator<List<PlanningAction>> candidateOrder;
    private final LongSupplier clock;
    private final DecisionTimeModel decisionTimeModel;
    private final BranchEngine engine = new BranchEngine();
    private final SecurityActionGenerator generator = new SecurityActionGenerator();
    private final ContinuationEvaluator evaluator = new ContinuationEvaluator();
    private final PlanningResultComparator comparator = new PlanningResultComparator();

    public ExactHorizonPlanner() { this(SearchLimits.unlimited()); }
    public ExactHorizonPlanner(SearchLimits limits) { this(limits, PlannerOptions.accelerated()); }
    public ExactHorizonPlanner(SearchLimits limits, PlannerOptions options) {
        this(limits, options, UnaryOperator.identity());
    }
    public ExactHorizonPlanner(SearchLimits limits, UnaryOperator<List<PlanningAction>> candidateOrder) {
        this(limits, PlannerOptions.accelerated(), candidateOrder);
    }
    public ExactHorizonPlanner(SearchLimits limits, PlannerOptions options,
                               UnaryOperator<List<PlanningAction>> candidateOrder) {
        this(limits, options, candidateOrder, System::nanoTime);
    }
    /** Opt-in timing sensitivity. Existing constructors retain the frozen v2 event boundaries. */
    public ExactHorizonPlanner(SearchLimits limits, PlannerOptions options, DecisionTimeModel decisionTimeModel) {
        this(limits, options, UnaryOperator.identity(), System::nanoTime, decisionTimeModel);
    }
    ExactHorizonPlanner(SearchLimits limits, UnaryOperator<List<PlanningAction>> candidateOrder, LongSupplier clock) {
        this(limits, PlannerOptions.accelerated(), candidateOrder, clock);
    }
    private ExactHorizonPlanner(SearchLimits limits, PlannerOptions options,
                                UnaryOperator<List<PlanningAction>> candidateOrder, LongSupplier clock) {
        this(limits, options, candidateOrder, clock, DecisionTimeModel.V2_EVENT_BOUNDARIES);
    }
    private ExactHorizonPlanner(SearchLimits limits, PlannerOptions options,
                                UnaryOperator<List<PlanningAction>> candidateOrder, LongSupplier clock,
                                DecisionTimeModel decisionTimeModel) {
        this.limits = Objects.requireNonNull(limits);
        this.options = Objects.requireNonNull(options);
        this.candidateOrder = Objects.requireNonNull(candidateOrder);
        this.clock = Objects.requireNonNull(clock);
        this.decisionTimeModel = Objects.requireNonNull(decisionTimeModel);
    }

    public HorizonPlanResult plan(PlanningObservation observation) {
        return new Search(Objects.requireNonNull(observation)).run();
    }

    private final class Search {
        final PlanningObservation origin;
        PlanningOptimisticBound bound;
        SearchControl.Scope control;
        SearchRuntimeTelemetry.Recorder runtime;
        String stoppedAtCheckpoint;
        final SearchDiagnostics.Counter stats = new SearchDiagnostics.Counter();
        final Map<PlanningStateKey, Solved> memo = new HashMap<>();
        final ArrayDeque<Visit> stack = new ArrayDeque<>();
        long explored;
        PlanningResult best;

        Search(PlanningObservation origin) { this.origin = origin; }

        HorizonPlanResult run() {
            try (var scope = SearchControl.open(limits.maxElapsed(), SearchDiagnostics.StopReason.TIME_LIMIT,
                    clock, ignored -> { })) {
                control = scope;
                runtime = new SearchRuntimeTelemetry.Recorder();
                try {
                    try (var phase = runtime.phase("initialization")) {
                        SearchControl.checkpoint("initialization.begin");
                        long beforeBound = System.nanoTime();
                        try {
                            bound = options.optimisticBounds() ? new PlanningOptimisticBound(origin) : null;
                        } finally {
                            if (options.optimisticBounds()) stats.boundNanos += System.nanoTime() - beforeBound;
                        }
                        SearchControl.checkpoint("initialization.root");
                        var root = engine.create(origin);
                        stack.push(new Visit(root, PlanningStateKey.from(root.snapshot())));
                        SearchControl.checkpoint("initialization.end");
                    }
                    return search();
                } catch (SearchControl.Stopped stopped) {
                    stats.stopReason = stopped.reason();
                    stoppedAtCheckpoint = stopped.checkpoint();
                    return result(HorizonPlanResult.Status.INCOMPLETE);
                }
            }
        }

        HorizonPlanResult search() {
            while (!stack.isEmpty()) {
                var visit = stack.peek();
                if (visit.actions == null) {
                    checkBudget();
                    explored = Math.incrementExact(explored);
                    stats.maxDepth = Math.max(stats.maxDepth, visit.branch.plan().size());
                    if (visit.key.rejected()) { finish(null); continue; }
                    var cached = options.memoize() ? memo.get(visit.key) : null;
                    if (cached != null) {
                        stats.memoHits++;
                        var combined = cached.evaluation == null ? null : new PlanningResult(cached.evaluation,
                                concat(visit.branch.plan(), cached.suffix));
                        finish(combined);
                        continue;
                    }
                    if (visit.branch.snapshot().at().equals(origin.evaluationEnd())) {
                        stats.terminalPlans++;
                        PlanningResult evaluated;
                        try (var phase = runtime.phase("evaluation")) {
                            SearchControl.checkpoint("evaluation.begin");
                            evaluated = evaluator.evaluate(visit.branch);
                            SearchControl.checkpoint("evaluation.end");
                        }
                        finish(evaluated);
                        continue;
                    }
                    if (options.optimisticBounds() && best != null) {
                        long beforeBound = System.nanoTime();
                        BranchEvaluation optimistic;
                        try (var phase = runtime.phase("bound")) {
                            SearchControl.checkpoint("bound.begin");
                            optimistic = bound.upperBound(visit.branch);
                            SearchControl.checkpoint("bound.end");
                        } finally { stats.boundNanos += System.nanoTime() - beforeBound; }
                        int metricOrder = comparator.compareMetrics(optimistic, best.evaluation());
                        boolean canonicalLoser = options.canonicalBoundPruning() && metricOrder == 0
                                && PlanningActionReduction.prefixAlreadyWorse(visit.branch.plan(), best.plan());
                        // A proven equal metric bound permits pruning only after a strictly
                        // worse first differing committed action; proper prefixes are inconclusive.
                        if (metricOrder > 0 || canonicalLoser) {
                            stats.boundPrunedNodes++;
                            if (canonicalLoser) stats.canonicalBoundPrunedNodes++;
                            visit.cacheable = false;
                            finish(null);
                            continue;
                        }
                    }
                    long before = System.nanoTime();
                    List<PlanningAction> generated;
                    try (var phase = runtime.phase("generation")) {
                        SearchControl.checkpoint("generation.begin");
                        visit.observation = decisionTimeModel.apply(visit.branch.observation());
                        generated = generator.candidates(visit.observation);
                        SearchControl.checkpoint("generation.end");
                    } finally { stats.generationNanos += System.nanoTime() - before; }
                    stats.expandedNodes++;
                    stats.generatedActions += generated.size();
                    for (var action : generated) stats.generatedByAction.merge(action.kind(), 1L, Long::sum);
                    if (stack.size() == 1)
                        for (var action : generated) stats.rootGeneratedByAction.merge(action.kind(), 1L, Long::sum);
                    try (var phase = runtime.phase("candidateProcessing")) {
                        SearchControl.checkpoint("candidateProcessing.begin");
                        // Validate the ordering hook against ALL candidates, before exact reduction.
                        var ordered = ordered(generated, visit.observation);
                        if (options.preExecutionWaitReduction()) {
                            var reduced = PlanningActionReduction.coalesceWaits(visit.observation, ordered);
                            stats.preExecutionWaitSkips += ordered.size() - reduced.size();
                            ordered = reduced;
                        }
                        if (stack.size() == 1) stats.rootCandidateCount = ordered.size();
                        var representatives = new HashMap<PlanningAction, Candidate>();
                        if (options.collapseEquivalentWaits()) {
                            var waits = new HashMap<PlanningStateKey, Candidate>();
                            for (var action : ordered.stream().filter(a -> a.kind() == PlanningAction.Kind.WAIT)
                                    .sorted(comparator::compareActions).toList()) {
                                checkBudget();
                                var child = execute(visit, action);
                                var prior = waits.putIfAbsent(child.key, child);
                                if (prior == null) representatives.put(action, child);
                                else stats.collapsedWaitTransitions++;
                            }
                        }
                        var candidates = new ArrayList<Candidate>();
                        for (var action : ordered) {
                            SearchControl.checkpoint("candidateProcessing.action");
                            if (options.collapseEquivalentWaits() && action.kind() == PlanningAction.Kind.WAIT) {
                                if (representatives.containsKey(action)) candidates.add(representatives.get(action));
                            } else candidates.add(new Candidate(action, null, null));
                        }
                        visit.actions = candidates.iterator();
                        if (stack.size() == 1) stats.rootCandidateCount = candidates.size();
                        SearchControl.checkpoint("candidateProcessing.end");
                    }
                }
                if (!visit.actions.hasNext()) { finish(visit.best); continue; }
                checkBudget();
                var candidate = visit.actions.next();
                if (candidate.branch == null) candidate = execute(visit, candidate.action);
                stack.push(new Visit(candidate.branch, candidate.key));
            }
            SearchControl.checkpoint("search.complete");
            return result(best == null ? HorizonPlanResult.Status.INFEASIBLE : HorizonPlanResult.Status.COMPLETE);
        }

        Candidate execute(Visit visit, PlanningAction action) {
            long before = System.nanoTime();
            PlanningBranch branch;
            try (var phase = runtime.phase("execution")) {
                SearchControl.checkpoint("execution.begin");
                branch = engine.executeGenerated(visit.branch, visit.observation, action);
                SearchControl.checkpoint("execution.end");
            } finally { stats.executionNanos += System.nanoTime() - before; }
            stats.executedTransitions++;
            stats.executedByAction.merge(action.kind(), 1L, Long::sum);
            PlanningStateKey key;
            try (var phase = runtime.phase("stateKey")) {
                SearchControl.checkpoint("stateKey.begin");
                key = PlanningStateKey.from(branch.snapshot());
                SearchControl.checkpoint("stateKey.end");
            }
            if (!visit.children.add(key)) stats.repeatedTransitions++;
            return new Candidate(action, branch, key);
        }

        void finish(PlanningResult solved) {
            try (var phase = runtime.phase("completion")) {
                SearchControl.checkpoint("completion.begin");
                var visit = stack.pop();
                if (solved != null && (best == null || comparator.compare(solved, best) < 0)) best = solved;
                if (options.memoize() && visit.cacheable && !memo.containsKey(visit.key)) {
                    if (options.maxMemoEntries() == 0 || memo.size() < options.maxMemoEntries()) {
                        // Publish only after the WHOLE subtree is solved; never cache a budget-aborted prefix.
                        SearchControl.checkpoint("memo.publish");
                        memo.put(visit.key, solved == null ? new Solved(null, List.of()) : new Solved(solved.evaluation(),
                                List.copyOf(solved.plan().subList(visit.branch.plan().size(), solved.plan().size()))));
                        stats.memoEntries = memo.size();
                    } else stats.memoStoreSkips++;
                }
                if (!stack.isEmpty()) {
                    var parent = stack.peek();
                    // A globally bounded subtree is sufficient for the final optimum, but is NOT
                    // an independently solved local suffix. Do not publish it into the exact cache.
                    parent.cacheable &= visit.cacheable;
                    if (solved != null && (parent.best == null || comparator.compare(solved, parent.best) < 0)) parent.best = solved;
                }
            }
        }

        void checkBudget() {
            SearchControl.checkpoint("search.node");
            if (limits.maxNodes() != 0 && explored >= limits.maxNodes()) {
                throw new SearchControl.Stopped(SearchDiagnostics.StopReason.NODE_LIMIT, "search.node");
            }
        }
        HorizonPlanResult result(HorizonPlanResult.Status status) {
            long elapsed = control.elapsedNanos();
            stats.runtimeTelemetry = runtime.snapshot(elapsed, stoppedAtCheckpoint);
            var plan = best == null ? List.<PlanningAction>of() : best.plan();
            if (status != HorizonPlanResult.Status.INCOMPLETE) SearchControl.checkpoint("result.publish");
            return new HorizonPlanResult(plan, best == null ? null : best.evaluation(), plan.isEmpty() ? null : plan.getFirst(),
                    status, explored, elapsed, stats.snapshot());
        }
    }

    private List<PlanningAction> ordered(List<PlanningAction> candidates, PlanningObservation observation) {
        var initial = new ArrayList<>(candidates);
        if (options.traversalOrder() == PlannerOptions.TraversalOrder.SERVICE_FIRST)
            initial.sort(Comparator.comparingInt((PlanningAction a) -> serviceRank(a.kind()))
                    .thenComparing(comparator::compareActions));
        else if (options.traversalOrder() == PlannerOptions.TraversalOrder.RENEWAL_FIRST)
            initial.sort(Comparator.comparingInt((PlanningAction a) -> renewalRank(a, observation))
                    .thenComparing(comparator::compareActions));
        SearchControl.checkpoint("candidateProcessing.order.before");
        var ordered = List.copyOf(Objects.requireNonNull(candidateOrder.apply(initial)));
        SearchControl.checkpoint("candidateProcessing.order.after");
        var originalSorted = candidates.stream().sorted(comparator::compareActions).toList();
        if (!originalSorted.equals(ordered.stream().sorted(comparator::compareActions).toList()))
            throw new IllegalArgumentException("Candidate order must be a permutation of every generated action");
        return ordered;
    }

    /** Observation-only traversal hint, never a feasibility filter or a tie-break. */
    private static int renewalRank(PlanningAction action, PlanningObservation observation) {
        return switch (action.kind()) {
            case INSTALL -> 0;
            case SWITCH -> observation.securityState().context(action.contextId()).status()
                    == org.contactaware.security.AssociationStatus.OPERATIONAL ? 7 : 1;
            case OPEN -> 2;
            case REUSE -> 3;
            case PREPARE -> renewalVisible(action, observation) ? 4 : 8;
            case CONTINUE -> 5;
            case WAIT -> 6;
        };
    }

    private static boolean renewalVisible(PlanningAction action, PlanningObservation observation) {
        var latest = observation.visibleContacts().stream()
                .filter(c -> c.window().satelliteId().equals(action.contextId().satelliteId())
                        && c.window().groundStationId().equals(action.contextId().groundStationId()))
                .map(c -> c.window().start()).max(Comparator.naturalOrder()).orElse(null);
        if (latest == null) return false;
        for (var direction : org.contactaware.security.Direction.values()) {
            boolean reaches = observation.securityState().keys().values().stream().anyMatch(key ->
                    key.contextId().equals(action.contextId()) && key.direction() == direction
                            && key.status() == org.contactaware.security.KeyStatus.ACTIVE
                            && latest.isBefore(key.hardDeadline(observation.constraints())));
            if (!reaches) return true;
        }
        return false;
    }

    private static int serviceRank(PlanningAction.Kind kind) {
        return switch (kind) {
            case CONTINUE -> 0;
            case SWITCH -> 1;
            case INSTALL -> 2;
            case OPEN -> 3;
            case REUSE -> 4;
            case WAIT -> 5;
            case PREPARE -> 6;
        };
    }
    private static List<PlanningAction> concat(List<PlanningAction> prefix, List<PlanningAction> suffix) {
        var result = new ArrayList<>(prefix);
        result.addAll(suffix);
        return List.copyOf(result);
    }
    private record Solved(BranchEvaluation evaluation, List<PlanningAction> suffix) {}
    private record Candidate(PlanningAction action, PlanningBranch branch, PlanningStateKey key) {}
    private static final class Visit {
        final PlanningBranch branch;
        final PlanningStateKey key;
        final Set<PlanningStateKey> children = new HashSet<>();
        PlanningObservation observation;
        Iterator<Candidate> actions;
        PlanningResult best;
        boolean cacheable = true;
        Visit(PlanningBranch branch, PlanningStateKey key) { this.branch = branch; this.key = key; }
    }
}

