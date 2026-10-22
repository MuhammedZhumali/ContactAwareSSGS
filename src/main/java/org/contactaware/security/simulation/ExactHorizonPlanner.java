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
    private final BranchEngine engine = new BranchEngine();
    private final SecurityActionGenerator generator = new SecurityActionGenerator();
    private final ContinuationEvaluator evaluator = new ContinuationEvaluator();
    private final PlanningResultComparator comparator = new PlanningResultComparator();
    private final PlanningOptimisticBound bound = new PlanningOptimisticBound();

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
    ExactHorizonPlanner(SearchLimits limits, UnaryOperator<List<PlanningAction>> candidateOrder, LongSupplier clock) {
        this(limits, PlannerOptions.accelerated(), candidateOrder, clock);
    }
    private ExactHorizonPlanner(SearchLimits limits, PlannerOptions options,
                                UnaryOperator<List<PlanningAction>> candidateOrder, LongSupplier clock) {
        this.limits = Objects.requireNonNull(limits);
        this.options = Objects.requireNonNull(options);
        this.candidateOrder = Objects.requireNonNull(candidateOrder);
        this.clock = Objects.requireNonNull(clock);
    }

    public HorizonPlanResult plan(PlanningObservation observation) {
        return new Search(Objects.requireNonNull(observation)).run();
    }

    private final class Search {
        final PlanningObservation origin;
        final long start = clock.getAsLong();
        final SearchDiagnostics.Counter stats = new SearchDiagnostics.Counter();
        final Map<PlanningStateKey, Solved> memo = new HashMap<>();
        final ArrayDeque<Visit> stack = new ArrayDeque<>();
        long explored;
        PlanningResult best;

        Search(PlanningObservation origin) {
            this.origin = origin;
            var root = engine.create(origin);
            stack.push(new Visit(root, PlanningStateKey.from(root.snapshot())));
        }

        HorizonPlanResult run() {
            while (!stack.isEmpty()) {
                var visit = stack.peek();
                if (visit.actions == null) {
                    if (exhausted()) return result(HorizonPlanResult.Status.INCOMPLETE);
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
                        finish(evaluator.evaluate(visit.branch));
                        continue;
                    }
                    if (options.optimisticBounds() && best != null) {
                        long beforeBound = System.nanoTime();
                        var optimistic = bound.upperBound(visit.branch);
                        stats.boundNanos += System.nanoTime() - beforeBound;
                        // Equality is NOT enough: an equal-score branch may win the canonical tie.
                        if (comparator.compareMetrics(optimistic, best.evaluation()) > 0) {
                            stats.boundPrunedNodes++;
                            visit.cacheable = false;
                            finish(null);
                            continue;
                        }
                    }
                    long before = System.nanoTime();
                    visit.observation = visit.branch.observation();
                    var generated = generator.candidates(visit.observation);
                    stats.generationNanos += System.nanoTime() - before;
                    stats.expandedNodes++;
                    stats.generatedActions += generated.size();
                    for (var action : generated) stats.generatedByAction.merge(action.kind(), 1L, Long::sum);
                    // Validate the ordering hook against ALL candidates, before exact reduction.
                    var ordered = ordered(generated);
                    var representatives = new HashMap<PlanningAction, Candidate>();
                    if (options.collapseEquivalentWaits()) {
                        var waits = new HashMap<PlanningStateKey, Candidate>();
                        for (var action : generated.stream().filter(a -> a.kind() == PlanningAction.Kind.WAIT)
                                .sorted(comparator::compareActions).toList()) {
                            if (exhausted()) return result(HorizonPlanResult.Status.INCOMPLETE);
                            var child = execute(visit, action);
                            var prior = waits.putIfAbsent(child.key, child);
                            if (prior == null) representatives.put(action, child);
                            else stats.collapsedWaitTransitions++;
                        }
                    }
                    var candidates = new ArrayList<Candidate>();
                    for (var action : ordered) {
                        if (options.collapseEquivalentWaits() && action.kind() == PlanningAction.Kind.WAIT) {
                            if (representatives.containsKey(action)) candidates.add(representatives.get(action));
                        } else candidates.add(new Candidate(action, null, null));
                    }
                    visit.actions = candidates.iterator();
                }
                if (!visit.actions.hasNext()) { finish(visit.best); continue; }
                if (exhausted()) return result(HorizonPlanResult.Status.INCOMPLETE);
                var candidate = visit.actions.next();
                if (candidate.branch == null) candidate = execute(visit, candidate.action);
                stack.push(new Visit(candidate.branch, candidate.key));
            }
            return result(best == null ? HorizonPlanResult.Status.INFEASIBLE : HorizonPlanResult.Status.COMPLETE);
        }

        Candidate execute(Visit visit, PlanningAction action) {
            long before = System.nanoTime();
            var branch = engine.executeGenerated(visit.branch, visit.observation, action);
            stats.executionNanos += System.nanoTime() - before;
            stats.executedTransitions++;
            stats.executedByAction.merge(action.kind(), 1L, Long::sum);
            var key = PlanningStateKey.from(branch.snapshot());
            if (!visit.children.add(key)) stats.repeatedTransitions++;
            return new Candidate(action, branch, key);
        }

        void finish(PlanningResult solved) {
            var visit = stack.pop();
            if (solved != null && (best == null || comparator.compare(solved, best) < 0)) best = solved;
            if (options.memoize() && visit.cacheable && !memo.containsKey(visit.key)) {
                if (options.maxMemoEntries() == 0 || memo.size() < options.maxMemoEntries()) {
                    // Publish only after the WHOLE subtree is solved; never cache a budget-aborted prefix.
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

        boolean exhausted() {
            return limits.maxNodes() != 0 && explored >= limits.maxNodes()
                    || !limits.maxElapsed().isZero() && elapsed() >= limits.maxElapsed().toNanos();
        }
        long elapsed() { return Math.max(0, clock.getAsLong() - start); }
        HorizonPlanResult result(HorizonPlanResult.Status status) {
            var plan = best == null ? List.<PlanningAction>of() : best.plan();
            return new HorizonPlanResult(plan, best == null ? null : best.evaluation(), plan.isEmpty() ? null : plan.getFirst(),
                    status, explored, elapsed(), stats.snapshot());
        }
    }

    private List<PlanningAction> ordered(List<PlanningAction> candidates) {
        var initial = new ArrayList<>(candidates);
        if (options.traversalOrder() == PlannerOptions.TraversalOrder.SERVICE_FIRST)
            initial.sort(Comparator.comparingInt((PlanningAction a) -> serviceRank(a.kind()))
                    .thenComparing(comparator::compareActions));
        var ordered = List.copyOf(Objects.requireNonNull(candidateOrder.apply(initial)));
        var originalSorted = candidates.stream().sorted(comparator::compareActions).toList();
        if (!originalSorted.equals(ordered.stream().sorted(comparator::compareActions).toList()))
            throw new IllegalArgumentException("Candidate order must be a permutation of every generated action");
        return ordered;
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

