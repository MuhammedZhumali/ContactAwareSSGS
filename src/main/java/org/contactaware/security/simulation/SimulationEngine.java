package org.contactaware.security.simulation;

import org.contactaware.contacts.Model.ContactWindow;
import org.contactaware.security.*;
import java.time.*;
import java.util.*;
import static org.contactaware.security.SecurityAction.*;
import org.contactaware.security.simulation.ExecutionSnapshot.*;
import static org.contactaware.security.simulation.SimulationEvent.Type.*;

/** Deterministic event execution. Each run owns its clock, queue, resources and journal. */
public final class SimulationEngine {
    public SimulationResult run(SimulationScenario scenario) { return resume(start(scenario)); }

    /** Initialize and settle the complete first timestamp, including zero-duration steps. */
    public ExecutionSnapshot start(SimulationScenario scenario) {
        var run = new Run(Objects.requireNonNull(scenario));
        run.initialize();
        run.advanceTo(scenario.start());
        return run.snapshot();
    }

    /** Pause without aborting operations, closing sessions or writing artificial journal entries. */
    public ExecutionSnapshot advance(ExecutionSnapshot snapshot, Instant until) {
        Objects.requireNonNull(snapshot); Objects.requireNonNull(until);
        if (until.isBefore(snapshot.at()) || until.isAfter(snapshot.scenario().end()))
            throw new IllegalArgumentException("Advance must stay between the snapshot and experiment end");
        var run = new Run(snapshot);
        run.advanceTo(until);
        return run.snapshot();
    }

    /** Finish the real experiment from an independent copy of the snapshot. */
    public SimulationResult resume(ExecutionSnapshot snapshot) {
        if (Objects.requireNonNull(snapshot).executionMode() == ExecutionMode.PLANNING_BRANCH)
            throw new IllegalArgumentException("Evaluate a planning branch with ContinuationEvaluator; it is not a full experiment");
        if (snapshot.executionMode() == ExecutionMode.CONTROLLED_REAL && snapshot.at().isBefore(snapshot.scenario().end()))
            throw new IllegalArgumentException("Resume controlled execution through RecedingHorizonController");
        return new Run(Objects.requireNonNull(snapshot)).finish();
    }

    /** Handoff retains the actual queue, committed work, outcome controls and complete past journal. */
    ExecutionSnapshot controlledSnapshot(ExecutionSnapshot snapshot) {
        Objects.requireNonNull(snapshot);
        if (snapshot.executionMode() == ExecutionMode.PLANNING_BRANCH)
            throw new IllegalArgumentException("A nominal planning branch is not an actual execution checkpoint");
        if (snapshot.queuedEvents().stream().peek(ignored -> SearchControl.checkpoint("simulation.stream")).anyMatch(e -> e.kind() == EventKind.ACTION_START))
            throw new IllegalArgumentException("Unexecuted prescribed actions cannot compete with the controller");
        if (snapshot.journal().stream().peek(ignored -> SearchControl.checkpoint("simulation.stream")).anyMatch(e -> e.type() == SIMULATION_ENDED))
            throw new IllegalArgumentException("The experiment is already finalized");
        var run = new Run(snapshot);
        run.executionMode = ExecutionMode.CONTROLLED_REAL;
        return run.snapshot();
    }

    ExecutionSnapshot controlledStart(ExecutionSnapshot snapshot, PlannedOperation operation) {
        requireControlled(snapshot);
        if (!operation.intent().scheduledAt().equals(snapshot.at()))
            throw new IllegalArgumentException("Controlled operations must start now");
        var run = new Run(snapshot);
        run.startAction(operation);
        run.advanceTo(run.now);
        return run.snapshot();
    }

    ExecutionSnapshot controlledAdvance(ExecutionSnapshot snapshot, Instant until, boolean startFrame) {
        requireControlled(snapshot);
        return manualAdvance(snapshot, until, startFrame);
    }

    record ControlledCompletion(ExecutionSnapshot snapshot, SimulationResult result) { }

    ControlledCompletion finishControlled(ExecutionSnapshot snapshot) {
        requireControlled(snapshot);
        if (!snapshot.at().equals(snapshot.scenario().end()))
            throw new IllegalArgumentException("Actual completion is only allowed at experiment end");
        var run = new Run(snapshot);
        var result = run.finish();
        return new ControlledCompletion(run.snapshot(), result);
    }

    private static void requireControlled(ExecutionSnapshot snapshot) {
        if (snapshot.executionMode() != ExecutionMode.CONTROLLED_REAL)
            throw new IllegalArgumentException("Expected a controlled actual execution snapshot");
    }

    /** Trusted adapter: only the supplied observation can enter a planning branch. */
    ExecutionSnapshot planningSnapshot(PlanningObservation observation) {
        SearchControl.checkpoint("simulation.planningSnapshot");
        Instant start = observation.at();
        for (var contact : observation.visibleContacts()) {
            SearchControl.checkpoint("simulation.rootContact");
            start = Run.earlier(start, contact.window().start());
        }
        for (var operation : observation.ongoingOperations()) {
            SearchControl.checkpoint("simulation.rootOperation");
            start = Run.earlier(start, operation.stepStartedAt());
        }
        if (!start.isBefore(observation.evaluationEnd())) start = observation.evaluationEnd().minusNanos(1);
        // Scenario is a configuration carrier. Its closed empty initial state is never executed;
        // the factual (possibly operational) state below is restored directly.
        var scenario = new SimulationScenario("nominal-visible-branch", start, observation.evaluationEnd(),
                SecurityState.empty(observation.securityState().satelliteId(), start),
                observation.constraints(), observation.costs(), observation.visibleContacts(), List.of());
        var run = new Run(scenario);
        run.executionMode = ExecutionMode.PLANNING_BRANCH;
        run.now = observation.at(); run.state = observation.securityState();
        run.carriedRecordedCosts = observation.recordedCosts();
        run.carriedUsefulTime = observation.usefulSecureTime(); run.carriedCreatedKeys = observation.createdKeys();
        run.uncertain.addAll(observation.uncertainContexts());
        run.issuedKeyIds.addAll(observation.issuedKeyIds());
        run.issuedOperationIds.addAll(observation.issuedOperationIds());
        observation.payloadProgress().forEach((id, p) -> {
            run.delivered.put(id, new long[]{p.uplinkBytes(), p.downlinkBytes()});
            run.nextUp.put(id, p.nextUplink());
        });
        for (var contact : observation.visibleContacts()) {
            SearchControl.checkpoint("simulation.loop");
            var window = contact.window();
            if (window.start().isAfter(run.now))
                run.schedule(window.start(), EventKind.CONTACT_START, "contact-start:" + window.id(), window.id(), 0, null);
            if (window.end().isAfter(run.now))
                run.schedule(window.end(), EventKind.CONTACT_END, "contact-end:" + window.id(), window.id(), 0, null);
        }
        for (var operation : observation.ongoingOperations()) {
            SearchControl.checkpoint("simulation.loop");
            var planned = new PlannedOperation(operation.id(),
                    new ScheduledSecurityAction(operation.action(), operation.stepStartedAt(), operation.contextId(),
                            operation.candidateKeys(), "Known committed operation; nominal future confirmation"),
                    operation.contactId());
            var resources = observation.resourceReservations().stream().peek(ignored -> SearchControl.checkpoint("simulation.stream")).filter(r -> r.operationId().equals(operation.id()))
                    .map(r -> new ResourceReservation(r.resource(), r.from(), r.until())).toList();
            var execution = new Execution(new PendingOperation(planned, operation.steps(), resources,
                    operation.stepIndex(), operation.stepStartedAt(), operation.stepKeys()));
            run.running.put(planned.id(), execution);
            run.schedule(operation.expectedStepCompletion(), EventKind.STEP_COMPLETE, "step:" + planned.id(),
                    planned.id(), execution.index, null);
        }
        run.frame = observation.inFlightFrame();
        if (run.frame != null)
            run.schedule(run.frame.until(), EventKind.FRAME_COMPLETE, "payload:" + run.frame.contact().window().id(),
                    run.frame.contact().window().id(), 0, null);
        run.registerDeadlines();
        run.advanceTo(run.now);
        return run.snapshot();
    }

    ExecutionSnapshot planningStart(ExecutionSnapshot snapshot, PlannedOperation operation) {
        if (snapshot.executionMode() != ExecutionMode.PLANNING_BRANCH)
            throw new IllegalArgumentException("Expected a nominal planning branch");
        var run = new Run(snapshot);
        run.executionMode = ExecutionMode.PLANNING_BRANCH;
        run.startAction(operation);
        run.advanceTo(run.now);
        return run.snapshot();
    }

    ExecutionSnapshot planningAdvance(ExecutionSnapshot snapshot, Instant until, boolean startFrame) {
        if (snapshot.executionMode() != ExecutionMode.PLANNING_BRANCH)
            throw new IllegalArgumentException("Expected a nominal planning branch");
        return manualAdvance(snapshot, until, startFrame);
    }

    /** Both manual modes stop at the next event; only their preserved queues/outcomes differ. */
    private ExecutionSnapshot manualAdvance(ExecutionSnapshot snapshot, Instant until, boolean startFrame) {
        if (!until.isAfter(snapshot.at()) || until.isAfter(snapshot.scenario().end()))
            throw new IllegalArgumentException("Manual time must advance within the execution interval");
        var run = new Run(snapshot);
        if (startFrame) {
            if (run.frame != null) throw new IllegalArgumentException("A frame is already committed");
            run.sendNextFrame();
            if (run.frame == null) throw new IllegalArgumentException("No admissible frame");
        }
        // Obsolete timers are not physical events or additional allowed decision opportunities.
        // In particular an interrupt scheduled AFTER completion must not reveal a private
        // outcome control, and an interrupted step must not wake at its old completion time.
        run.queue.removeIf(e -> switch (e.kind()) {
            case KEY_DEADLINE -> (run.state.key(e.referenceId()).status() != KeyStatus.ACTIVE
                    && run.state.key(e.referenceId()).status() != KeyStatus.PRE_ACTIVE)
                    || !run.state.key(e.referenceId()).hardDeadline(run.input.constraints()).equals(e.expectedDeadline());
            case STEP_COMPLETE -> !run.running.containsKey(e.referenceId())
                    || run.running.get(e.referenceId()).index != e.stepIndex();
            case INTERRUPT -> !run.running.containsKey(e.referenceId());
            default -> false;
        });
        // A disclosure is attacker-only knowledge, not a controller decision opportunity.
        // A notice with no captured target is likewise invisible. Process such events in
        // the actual queue while preserving the original committed wait/frame interval.
        while (true) {
            SearchControl.checkpoint("simulation.loop");
            var next = run.queue.stream().peek(ignored -> SearchControl.checkpoint("simulation.stream")).filter(e -> e.kind() != EventKind.DISCLOSURE)
                    .min(ExecutionSnapshot.EVENT_ORDER).orElse(null);
            var target = next == null ? until : Run.earlier(until, next.at());
            long before = run.journal.size();
            run.advanceTo(target);
            if (target.equals(until) || next == null || next.kind() != EventKind.REVOCATION_NOTICE
                    || run.journal.subList((int) before, run.journal.size()).stream().peek(ignored -> SearchControl.checkpoint("simulation.stream"))
                        .anyMatch(e -> e.at().equals(target) && e.type() != KEY_DISCLOSED && e.type() != DISCLOSURE_NO_TARGET))
                break;
        }
        return run.snapshot();
    }

    private static final class Execution {
        final PlannedOperation planned;
        final List<SecurityAction> steps;
        final List<ResourceReservation> reservations;
        int index;
        Instant stepStart;
        KeyPairIds stepKeys;
        Execution(PlannedOperation planned, List<SecurityAction> steps, List<ResourceReservation> reservations) {
            this.planned = planned; this.steps = steps; this.reservations = reservations;
        }
        Execution(PendingOperation saved) {
            this(saved.planned(), saved.steps(), saved.reservations());
            index = saved.stepIndex(); stepStart = saved.stepStartedAt(); stepKeys = saved.stepKeys();
        }
        PendingOperation snapshot() { return new PendingOperation(planned, steps, reservations, index, stepStart, stepKeys); }
        SecurityAction step() { return steps.get(index); }
    }

    private static final class Run {
        final SimulationScenario input;
        final LifecycleTransitions transitions;
        final SecurityConstraintChecker checker;
        final SecurityActionExecutor executor;
        final PriorityQueue<QueuedEvent> queue = new PriorityQueue<>(ExecutionSnapshot.EVENT_ORDER);
        final Map<String, SimulationContact> contacts = new TreeMap<>();
        final Map<String, PlannedOperation> plannedOperations = new TreeMap<>();
        final Map<String, Execution> running = new TreeMap<>();
        final Set<ContextId> uncertain = new TreeSet<>();
        // Derived from factual journal events on restore; never supplied to a planning branch.
        final Set<String> unavailableContacts = new HashSet<>();
        final Set<String> expiryEvents = new HashSet<>();
        final List<SimulationEvent> journal = new ArrayList<>();
        final Map<String, long[]> delivered = new TreeMap<>();
        final Map<String, Boolean> nextUp = new TreeMap<>();
        final Set<String> issuedKeyIds = new TreeSet<>();
        final Set<String> issuedOperationIds = new TreeSet<>();
        ResourceUsage carriedRecordedCosts = ResourceUsage.ZERO;
        Duration carriedUsefulTime = Duration.ZERO;
        long carriedCreatedKeys;
        ExecutionMode executionMode = ExecutionMode.PREDEFINED_PLAN;
        SecurityState state;
        Instant now;
        long insertion;
        InFlightFrame frame;

        Run(SimulationScenario input) {
            SearchControl.checkpoint("simulation.run");
            this.input = input; state = input.initialState(); now = input.start();
            transitions = new LifecycleTransitions(input.constraints());
            checker = new SecurityConstraintChecker(input.constraints());
            executor = new SecurityActionExecutor(input.constraints());
            input.contacts().forEach(c -> contacts.put(c.window().id(), c));
            input.plan().forEach(p -> plannedOperations.put(p.id(), p));
            issuedKeyIds.addAll(state.keys().keySet());
        }

        Run(ExecutionSnapshot snapshot) {
            this(snapshot.scenario());
            now = snapshot.at(); state = snapshot.securityState(); insertion = snapshot.nextInsertion();
            queue.addAll(snapshot.queuedEvents()); expiryEvents.addAll(snapshot.registeredExpiryEvents());
            journal.addAll(snapshot.journal()); uncertain.addAll(snapshot.uncertainContexts());
            for (var event : journal) {
                if (event.type() == CHANNEL_DOWN) unavailableContacts.add(event.contactId());
                else if (event.type() == CHANNEL_UP) unavailableContacts.remove(event.contactId());
            }
            snapshot.pendingOperations().forEach(p -> running.put(p.planned().id(), new Execution(p)));
            snapshot.payloadProgress().forEach((id, p) -> {
                delivered.put(id, new long[]{p.uplinkBytes(), p.downlinkBytes()}); nextUp.put(id, p.nextUplink());
            });
            frame = snapshot.inFlightFrame();
            carriedRecordedCosts = snapshot.carriedRecordedCosts();
            carriedUsefulTime = snapshot.carriedUsefulTime();
            carriedCreatedKeys = snapshot.carriedCreatedKeys();
            issuedKeyIds.clear(); issuedKeyIds.addAll(snapshot.issuedKeyIds());
            issuedOperationIds.addAll(snapshot.issuedOperationIds());
            executionMode = snapshot.executionMode();
        }

        ExecutionSnapshot snapshot() {
            SearchControl.checkpoint("simulation.snapshot");
            var progress = new TreeMap<String, PayloadProgress>();
            delivered.forEach((id, counts) -> progress.put(id,
                    new PayloadProgress(counts[0], counts[1], nextUp.getOrDefault(id, true))));
            return new ExecutionSnapshot(input, now, state, running.values().stream().peek(ignored -> SearchControl.checkpoint("simulation.stream")).map(Execution::snapshot).toList(),
                    List.copyOf(queue), insertion, expiryEvents, journal, uncertain, progress, frame,
                    carriedRecordedCosts, carriedUsefulTime, carriedCreatedKeys, issuedKeyIds, issuedOperationIds, executionMode);
        }

        void initialize() {
            for (var c : input.contacts()) {
                SearchControl.checkpoint("simulation.loop");
                var window = c.window();
                schedule(window.start(), EventKind.CONTACT_START, "contact-start:" + window.id(), window.id(), 0, null);
                schedule(window.end(), EventKind.CONTACT_END, "contact-end:" + window.id(), window.id(), 0, null);
            }
            registerDeadlines();
            for (var operation : input.plan())
                schedule(operation.intent().scheduledAt(), EventKind.ACTION_START, operation.id(), operation.id(), 0, null);
            for (var incident : input.disclosures()) {
                SearchControl.checkpoint("simulation.loop");
                schedule(incident.at(), EventKind.DISCLOSURE, "disclosure:" + incident.id(), incident.id(), 0, null);
                if (incident.detectionAt() != null)
                    schedule(incident.detectionAt(), EventKind.REVOCATION_NOTICE, "notice:" + incident.id(), incident.id(), 0, null);
            }
            for (var interruption : input.channelInterruptions()) {
                schedule(interruption.from(), EventKind.CHANNEL_DOWN, "channel-down:" + interruption.contactId(),
                        interruption.contactId(), 0, null);
                schedule(interruption.until(), EventKind.CHANNEL_UP, "channel-up:" + interruption.contactId(),
                        interruption.contactId(), 0, null);
            }
        }

        void advanceTo(Instant until) {
            while (!queue.isEmpty() && !queue.peek().at().isAfter(until)) {
                SearchControl.checkpoint("simulation.loop");
                now = queue.peek().at();
                // Complete the entire timestamp before starting another payload frame.
                do { dispatch(queue.remove()); } while (!queue.isEmpty() && queue.peek().at().equals(now));
                if (executionMode == ExecutionMode.PREDEFINED_PLAN && now.isBefore(input.end())) sendNextFrame();
            }
            // An artificial pause is not a new event or a new opportunity to send a frame.
            now = until;
        }

        SimulationResult finish() {
            advanceTo(input.end());
            for (var execution : List.copyOf(running.values()))
                fail(execution, true, "SIMULATION_ENDED", "Execution cut by simulation horizon");
            log(SIMULATION_ENDED, null, null, null, null, null, ResourceUsage.ZERO, 0, null, "No fabricated cleanup after horizon");
            var events = List.copyOf(journal);
            return new SimulationResult(input.name(), input.end(), state, events,
                    new MetricsCollector().collect(input.contacts(), events), uncertain, input.costs().name(), input.costs().provenance());
        }

        void schedule(Instant at, EventKind kind, String stableId, String referenceId, int stepIndex, Instant deadline) {
            if (at.isBefore(now)) throw new IllegalStateException("Event scheduled in the past");
            if (!at.isAfter(input.end())) queue.add(new QueuedEvent(at, kind, stableId, insertion++, referenceId, stepIndex, deadline));
        }

        void dispatch(QueuedEvent event) {
            SearchControl.checkpoint("simulation.event");
            switch (event.kind()) {
                case CONTACT_START -> {
                    var window = contacts.get(event.referenceId()).window();
                    log(CONTACT_STARTED, null, null, context(window), window.id(), null, ResourceUsage.ZERO, 0, null, "Contact begins");
                }
                case CONTACT_END -> closeContact(contacts.get(event.referenceId()).window());
                case ACTION_START -> startAction(plannedOperations.get(event.referenceId()));
                case KEY_DEADLINE -> expire(event.referenceId(), event.expectedDeadline());
                case STEP_COMPLETE -> {
                    var execution = running.get(event.referenceId());
                    if (execution != null) completeStep(execution, event.stepIndex());
                }
                case INTERRUPT -> {
                    var execution = running.get(event.referenceId());
                    if (execution != null) fail(execution, true, "INTERRUPTED", "Explicit execution interruption");
                }
                case FRAME_COMPLETE -> {
                    if (frame == null || !frame.contact().window().id().equals(event.referenceId()) || !frame.until().equals(now))
                        throw new IllegalStateException("Frame completion does not match the in-flight frame");
                    completeFrame(frame);
                }
                case DISCLOSURE -> disclose(event.referenceId());
                case REVOCATION_NOTICE -> receiveGroundNotice(event.referenceId());
                case CHANNEL_DOWN -> channelDown(event.referenceId());
                case CHANNEL_UP -> {
                    unavailableContacts.remove(event.referenceId());
                    var window = contacts.get(event.referenceId()).window();
                    log(CHANNEL_UP, null, null, context(window), window.id(), null, ResourceUsage.ZERO, 0, null,
                            "Channel available; online admission and explicit reopening remain required");
                }
            }
        }

        void startAction(PlannedOperation planned) {
            var intent = planned.intent();
            if (!issuedOperationIds.add(planned.id())) throw new IllegalArgumentException("Operation ID was already issued");
            log(ACTION_STARTED, planned.id(), intent.action(), intent.contextId(), planned.contactId(), intent.newKeys(),
                    ResourceUsage.ZERO, 0, null, intent.reason());
            try {
                // IDs are issued on the actual request, even if admission or PREPARE later fails.
                // Unexecuted actions elsewhere in the supplied plan do not reserve any ID.
                if (intent.action() == PREPARE || (intent.action() == ROTATE || intent.action() == ESTABLISH)
                        && !state.keys().containsKey(intent.newKeys().uplink())) {
                    boolean reused = issuedKeyIds.contains(intent.newKeys().uplink())
                            || issuedKeyIds.contains(intent.newKeys().downlink());
                    issuedKeyIds.add(intent.newKeys().uplink()); issuedKeyIds.add(intent.newKeys().downlink());
                    if (reused) throw new SecurityActionExecutor.Rejected("KEY_ID_ALREADY_ISSUED", "Preparation IDs cannot be reused");
                }
                if (uncertain.contains(intent.contextId()) && intent.action().requiresContact())
                    throw new SecurityActionExecutor.Rejected("CONTEXT_UNCERTAIN", "Reconciliation is required before further online operations");
                requireChannel(planned, intent.action());
                var steps = executor.resolve(state, intent);
                executor.admit(state, intent, steps.getFirst(), contact(planned), now);
                var reservations = reservations(planned, steps);
                for (var active : running.values()) {
                    SearchControl.checkpoint("simulation.loop");
                    boolean sameOnlineContext = active.planned.intent().contextId().equals(intent.contextId())
                            && active.planned.intent().action().requiresContact() && intent.action() != PREPARE;
                    boolean conflicts = reservations.stream().peek(ignored -> SearchControl.checkpoint("simulation.stream")).anyMatch(a -> active.reservations.stream().peek(ignored -> SearchControl.checkpoint("simulation.stream")).anyMatch(a::conflicts));
                    if (sameOnlineContext || conflicts)
                        throw new SecurityActionExecutor.Rejected("RESOURCE_BUSY", "Conflicts with " + active.planned.id());
                }
                var execution = new Execution(planned, steps, reservations);
                running.put(planned.id(), execution);
                startStep(execution);
                if (planned.interruptAt() != null)
                    schedule(planned.interruptAt(), EventKind.INTERRUPT, "interrupt:" + planned.id(), planned.id(), 0, null);
            } catch (SecurityActionExecutor.Rejected rejection) {
                rejected(planned, rejection.code(), rejection.getMessage());
            } catch (IllegalArgumentException rejection) {
                rejected(planned, "INVALID_TRANSITION", rejection.getMessage());
            }
        }

        void startStep(Execution execution) {
            var planned = execution.planned;
            try {
                requireChannel(planned, execution.step());
                executor.admit(state, planned.intent(), execution.step(), contact(planned), now);
            } catch (SecurityActionExecutor.Rejected rejection) {
                running.remove(planned.id());
                rejected(planned, rejection.code(), rejection.getMessage());
                return;
            }
            execution.stepStart = now;
            execution.stepKeys = switch (execution.step()) {
                case OPEN, REUSE -> state.context(planned.intent().contextId()).boundKeys();
                default -> planned.intent().newKeys();
            };
            log(STEP_STARTED, planned.id(), execution.step(), planned.intent().contextId(), planned.contactId(),
                    execution.stepKeys, ResourceUsage.ZERO, 0, null, "Admitted; no state checkpoint yet");
            int index = execution.index;
            var end = now.plus(input.costs().cost(execution.step()).duration());
            if (end.equals(now)) completeStep(execution, index);
            else schedule(end, EventKind.STEP_COMPLETE, "step:" + planned.id(), planned.id(), index, null);
        }

        void completeStep(Execution execution, int index) {
            var planned = execution.planned;
            if (running.get(planned.id()) != execution || execution.index != index) return;
            if (planned.missingAcknowledgements().contains(execution.step())) {
                fail(execution, false, "MISSING_ACKNOWLEDGEMENT", "Work completed without a confirmation checkpoint");
                return;
            }
            try {
                requireChannel(planned, execution.step());
                state = executor.complete(state, planned.intent(), execution.step(), contact(planned), now);
            } catch (SecurityActionExecutor.Rejected rejection) {
                fail(execution, false, rejection.code(), rejection.getMessage());
                return;
            }
            log(STEP_COMPLETED, planned.id(), execution.step(), planned.intent().contextId(), planned.contactId(),
                    execution.stepKeys, input.costs().cost(execution.step()).total(), execution.step() == PREPARE ? 2 : 0,
                    null, "Confirmed checkpoint");
            if (execution.step() == NOTIFY_REVOCATION) {
                for (var incident : input.disclosures()) {
                    SearchControl.checkpoint("simulation.loop");
                    var disclosure = disclosed(incident.id());
                    if (disclosure != null && disclosure.keyIds().equals(execution.stepKeys)
                            && hasEvent(REVOCATION_RECEIVED_GROUND, incident.id())
                            && !hasEvent(REVOCATION_RECEIVED_SPACECRAFT, incident.id()))
                        log(REVOCATION_RECEIVED_SPACECRAFT, incident.id(), NOTIFY_REVOCATION,
                                disclosure.contextId(), planned.contactId(), disclosure.keyIds(), ResourceUsage.ZERO,
                                0, null, "Spacecraft receipt confirmed over independent authenticated management channel");
                }
            }
            registerDeadlines();
            execution.index++;
            if (execution.index == execution.steps.size()) {
                running.remove(planned.id());
                log(ACTION_COMPLETED, planned.id(), planned.intent().action(), planned.intent().contextId(), planned.contactId(),
                        planned.intent().newKeys(), ResourceUsage.ZERO, 0, null, "All requested steps confirmed");
            } else startStep(execution);
        }

        void rejected(PlannedOperation planned, String code, String detail) {
            log(ACTION_FAILED, planned.id(), planned.intent().action(), planned.intent().contextId(), planned.contactId(),
                    planned.intent().newKeys(), ResourceUsage.ZERO, 0, code, detail);
        }

        void fail(Execution execution, boolean interrupted, String code, String detail) {
            if (running.remove(execution.planned.id()) != execution) return;
            var planned = execution.planned;
            var usage = input.costs().cost(execution.step()).consumedUntil(Duration.between(execution.stepStart, now));
            log(interrupted ? STEP_INTERRUPTED : STEP_FAILED, planned.id(), execution.step(), planned.intent().contextId(),
                    planned.contactId(), execution.stepKeys, usage, 0, code, detail);
            if (!code.equals("KNOWN_COMPROMISE") && (execution.step() == SWITCH || execution.step() == OPEN || execution.step() == REUSE)) {
                // No protocol rollback is invented after a possibly delivered switch/open.
                uncertain.add(planned.intent().contextId());
                stopIfOperational(planned.intent().contextId(), planned.contactId());
                log(CONTEXT_UNCERTAIN, planned.id(), execution.step(), planned.intent().contextId(), planned.contactId(),
                        execution.stepKeys, ResourceUsage.ZERO, 0, code, "Blocked until explicit reconciliation (not modeled)");
            }
            rejected(planned, code, detail);
        }

        List<ResourceReservation> reservations(PlannedOperation planned, List<SecurityAction> steps) {
            var result = new ArrayList<ResourceReservation>();
            Instant cursor = now;
            Instant limit = input.end();
            if (planned.intent().action().requiresContact()) limit = earlier(limit, contact(planned).end());
            if (planned.interruptAt() != null) limit = earlier(limit, planned.interruptAt());
            for (var step : steps) for (var phase : input.costs().cost(step).phases()) {
                SearchControl.checkpoint("simulation.loop");
                var end = cursor.plus(phase.duration());
                String resource = switch (phase.resource()) {
                    case GROUND_CPU -> "ground:" + planned.intent().contextId().groundStationId();
                    case SPACECRAFT_CPU -> "spacecraft";
                    case UPLINK, DOWNLINK -> "radio";
                    case WAIT -> null;
                };
                if (resource != null && cursor.isBefore(limit))
                    result.add(new ResourceReservation(resource, cursor, earlier(end, limit)));
                cursor = end;
            }
            return List.copyOf(result);
        }

        void closeContact(ContactWindow window) {
            for (var execution : List.copyOf(running.values()))
                if (execution.planned.intent().action().requiresContact() && window.id().equals(execution.planned.contactId()))
                    fail(execution, true, "CONTACT_ENDED", "No online commit at or beyond contact end");
            stopIfOperational(context(window), window.id());
            log(CONTACT_ENDED, null, null, context(window), window.id(), null, ResourceUsage.ZERO, 0, null, "Contact ends");
        }

        void channelDown(String contactId) {
            unavailableContacts.add(contactId);
            var window = contacts.get(contactId).window();
            log(CHANNEL_DOWN, null, null, context(window), contactId, null, ResourceUsage.ZERO, 0, null,
                    "Actual channel unavailable; forecast window is unchanged");
            if (frame != null && contactId.equals(frame.contact().window().id())) abortFrame("CHANNEL_DOWN");
            for (var execution : List.copyOf(running.values()))
                if (execution.step().requiresContact() && contactId.equals(execution.planned.contactId()))
                    fail(execution, true, "CHANNEL_DOWN", "Online work interrupted by actual channel loss");
            stopIfOperational(context(window), contactId);
        }

        void requireChannel(PlannedOperation planned, SecurityAction action) {
            if (action.requiresContact() && unavailableContacts.contains(planned.contactId()))
                throw new SecurityActionExecutor.Rejected("CHANNEL_DOWN", "The matching channel is currently unavailable");
        }

        void stopIfOperational(ContextId id, String contactId) {
            var ctx = state.contexts().get(id);
            if (ctx == null || ctx.status() != AssociationStatus.OPERATIONAL) return;
            state = transitions.stopContext(state, id, now);
            log(STEP_STARTED, "auto-stop:" + contactId, STOP, id, contactId, ctx.boundKeys(), ResourceUsage.ZERO, 0, null, "Local stop");
            log(STEP_COMPLETED, "auto-stop:" + contactId, STOP, id, contactId, ctx.boundKeys(),
                    input.costs().cost(STOP).total(), 0, null, "Counters and successful-use history retained");
            log(CONTEXT_STOPPED, null, STOP, id, contactId, ctx.boundKeys(), ResourceUsage.ZERO, 0, null, "Local session closed");
        }

        void registerDeadlines() {
            for (var key : state.keys().values()) {
                SearchControl.checkpoint("simulation.loop");
                if (key.status() != KeyStatus.ACTIVE && key.status() != KeyStatus.PRE_ACTIVE) continue;
                var deadline = key.hardDeadline(input.constraints());
                if (expiryEvents.add(key.id() + ":" + deadline))
                    schedule(deadline.isBefore(now) ? now : deadline, EventKind.KEY_DEADLINE,
                            "expiry:" + key.id(), key.id(), 0, deadline);
            }
        }

        void expire(String keyId, Instant expectedDeadline) {
            var key = state.key(keyId);
            if ((key.status() != KeyStatus.ACTIVE && key.status() != KeyStatus.PRE_ACTIVE)
                    || !key.hardDeadline(input.constraints()).equals(expectedDeadline)) return;
            var ctx = state.context(key.contextId());
            boolean bound = keyId.equals(ctx.uplink().keyId()) || keyId.equals(ctx.downlink().keyId());
            String contactId = ctx.activeContact() == null ? null : ctx.activeContact().id();
            if (bound) {
                var pair = ctx.boundKeys();
                stopIfOperational(ctx.id(), contactId);
                state = transitions.expireContext(state, ctx.id(), DeactivationReason.EXPIRED, now);
                log(STEP_STARTED, "auto-expire:" + keyId, EXPIRE_CONTEXT, ctx.id(), contactId, pair,
                        ResourceUsage.ZERO, 0, null, "Mandatory deadline gate");
                log(STEP_COMPLETED, "auto-expire:" + keyId, EXPIRE_CONTEXT, ctx.id(), contactId, pair,
                        input.costs().cost(EXPIRE_CONTEXT).total(), 0, null, "Bound pair retired; staged material retained");
            } else state = transitions.expireUnboundKey(state, keyId, now);
            log(KEY_EXPIRED, null, EXPIRE_CONTEXT, key.contextId(), contactId, null, ResourceUsage.ZERO, 0,
                    "KEY_EXPIRED", keyId + (bound ? " (paired context retired)" : " (unbound material)"));
            for (var execution : List.copyOf(running.values())) {
                SearchControl.checkpoint("simulation.loop");
                if (execution.stepKeys == null || execution.step() == PREPARE || execution.step() == NOTIFY_REVOCATION) continue;
                boolean unusable = List.of(execution.stepKeys.uplink(), execution.stepKeys.downlink()).stream().peek(ignored -> SearchControl.checkpoint("simulation.stream"))
                        .map(state::key).anyMatch(k -> k.status() == KeyStatus.DEACTIVATED || k.status() == KeyStatus.DESTROYED);
                if (unusable) fail(execution, true, "KEY_EXPIRED", "Required material retired during operation");
            }
        }

        boolean hasEvent(SimulationEvent.Type type, String id) {
            return journal.stream().peek(ignored -> SearchControl.checkpoint("simulation.stream")).anyMatch(e -> e.type() == type && id.equals(e.operationId()));
        }

        SimulationEvent disclosed(String id) {
            return journal.stream().peek(ignored -> SearchControl.checkpoint("simulation.stream")).filter(e -> e.type() == KEY_DISCLOSED && id.equals(e.operationId())).findFirst().orElse(null);
        }

        void disclose(String id) {
            var incident = input.disclosures().stream().peek(ignored -> SearchControl.checkpoint("simulation.stream")).filter(d -> d.id().equals(id)).findFirst().orElseThrow();
            var context = state.context(incident.contextId());
            KeyPairIds keys = null;
            if (context.uplink().keyId() != null && context.downlink().keyId() != null) {
                var candidate = context.boundKeys();
                if (List.of(candidate.uplink(), candidate.downlink()).stream().peek(ignored -> SearchControl.checkpoint("simulation.stream")).map(state::key)
                        .allMatch(k -> k.status() == KeyStatus.ACTIVE && now.isBefore(k.hardDeadline(input.constraints()))))
                    keys = candidate;
            }
            log(keys == null ? DISCLOSURE_NO_TARGET : KEY_DISCLOSED, id, null, incident.contextId(),
                    context.activeContact() == null ? null : context.activeContact().id(), keys, ResourceUsage.ZERO,
                    0, null, keys == null ? "No active bound traffic pair at the physical attack time"
                            : "Attacker-only one-time disclosure; controller is not notified");
        }

        void receiveGroundNotice(String id) {
            var incident = disclosed(id);
            if (incident == null) return; // No affected pair, hence no revocation instruction.
            var keys = incident.keyIds();
            var context = state.context(incident.contextId());
            var contactId = context.activeContact() == null ? null : context.activeContact().id();
            log(REVOCATION_RECEIVED_GROUND, id, null, context.id(), contactId, keys, ResourceUsage.ZERO,
                    0, null, "Authenticated terrestrial notice received by GS; spacecraft has not yet been notified");
            if (frame != null && frame.keys().equals(keys)) {
                if (frame.uplink()) abortFrame("KNOWN_COMPROMISE");
                else frame = new InFlightFrame(frame.contact(), frame.context(), frame.keys(), frame.from(), frame.until(), false, true);
                // GS can stop its own transmitter immediately. A committed spacecraft downlink
                // continues until its frame boundary; local rejection is not remote notification.
            }
            for (var execution : List.copyOf(running.values()))
                if (keys.equals(execution.stepKeys)) fail(execution, true, "KNOWN_COMPROMISE", "Affected operation stopped by known revocation");
            context = state.context(incident.contextId());
            if (context.uplink().keyId() != null && context.boundKeys().equals(keys)) {
                stopIfOperational(context.id(), contactId);
                state = transitions.expireContext(state, context.id(), DeactivationReason.KNOWN_COMPROMISE, now);
            } else if (List.of(keys.uplink(), keys.downlink()).stream().peek(ignored -> SearchControl.checkpoint("simulation.stream")).map(state::key)
                    .allMatch(k -> k.status() == KeyStatus.ACTIVE || k.status() == KeyStatus.PRE_ACTIVE)) {
                state = transitions.deactivateKeys(state, keys, DeactivationReason.KNOWN_COMPROMISE, now);
            }
            // Retired old generations stay retired; an independent current generation is untouched.
        }

        void abortFrame(String reason) {
            var aborted = frame;
            long emitted = java.math.BigInteger.valueOf(aborted.contact().payload().frameBytes())
                    .multiply(java.math.BigInteger.valueOf(Duration.between(aborted.from(), now).toNanos()))
                    .divide(java.math.BigInteger.valueOf(Duration.between(aborted.from(), aborted.until()).toNanos())).longValueExact();
            var direction = aborted.uplink() ? Direction.UPLINK : Direction.DOWNLINK;
            state = transitions.recordAbortedTransmission(state, aborted.keys().forDirection(direction), now);
            var evidence = new SimulationEvent.FrameEvidence(aborted.from(), now, direction,
                    aborted.keys().forDirection(direction), aborted.contact().payload().frameBytes(), emitted, false);
            journal.add(new SimulationEvent(journal.size(), now, PAYLOAD_ABORTED, null, null, aborted.context(),
                    aborted.contact().window().id(), aborted.keys(), ResourceUsage.ZERO, Duration.ZERO,
                    0, 0, 0, 0, 0, reason, "In-flight frame interrupted; emitted ciphertext recorded separately from delivered payload", evidence));
            queue.removeIf(e -> e.kind() == EventKind.FRAME_COMPLETE
                    && e.referenceId().equals(aborted.contact().window().id()) && e.at().equals(aborted.until()));
            frame = null;
        }

        void sendNextFrame() {
            if (frame != null) return;
            var ctx = state.contexts().values().stream().peek(ignored -> SearchControl.checkpoint("simulation.stream")).filter(c -> c.status() == AssociationStatus.OPERATIONAL).findFirst().orElse(null);
            if (ctx == null || uncertain.contains(ctx.id())) return;
            var contact = contacts.get(ctx.activeContact().id());
            if (unavailableContacts.contains(contact.window().id())) return;
            if (running.values().stream().peek(ignored -> SearchControl.checkpoint("simulation.stream")).anyMatch(e -> e.planned.intent().action().requiresContact()
                    && contact.window().id().equals(e.planned.contactId()))) return;
            var profile = contact.payload();
            var counts = delivered.computeIfAbsent(contact.window().id(), ignored -> new long[2]);
            boolean up = nextUp.getOrDefault(contact.window().id(), true);
            if (!hasBudget(profile, counts, up)) up = !up;
            if (!hasBudget(profile, counts, up)) return;
            Instant bound = earlier(contact.window().end(), input.end());
            for (var keyId : List.of(ctx.boundKeys().uplink(), ctx.boundKeys().downlink()))
                bound = earlier(bound, state.key(keyId).hardDeadline(input.constraints()));
            // Complete frames only; reserve management boundaries without using future outcomes.
            for (var planned : input.plan()) {
                SearchControl.checkpoint("simulation.loop");
                var intent = planned.intent();
                boolean blocks = intent.action().requiresContact() && contact.window().id().equals(planned.contactId())
                        || (intent.action() == STOP || intent.action() == EXPIRE_CONTEXT) && intent.contextId().equals(ctx.id());
                if (blocks && intent.scheduledAt().isAfter(now)) bound = earlier(bound, intent.scheduledAt());
            }
            var until = now.plus(profile.frameDuration());
            if (until.isAfter(bound)) return;
            var admission = checker.canProtect(state, ctx.id(), now, until, up ? 1 : 0, up ? 0 : 1);
            if (!admission.allowed()) {
                log(PAYLOAD_REJECTED, null, null, ctx.id(), contact.window().id(), ctx.boundKeys(), ResourceUsage.ZERO, 0,
                        admission.violations().getFirst().code().name(), admission.violations().toString());
                stopIfOperational(ctx.id(), contact.window().id());
                return;
            }
            frame = new InFlightFrame(contact, ctx.id(), ctx.boundKeys(), now, until, up);
            schedule(until, EventKind.FRAME_COMPLETE, "payload:" + contact.window().id(), contact.window().id(), 0, null);
        }

        void completeFrame(InFlightFrame completed) {
            if (frame == completed && completed.deliveryRejectedAtGround()) {
                abortFrame("KNOWN_COMPROMISE_DOWNLINK_DROPPED");
                return;
            }
            if (frame != completed || !state.context(completed.context()).boundKeys().equals(completed.keys()))
                throw new IllegalStateException("An in-flight frame lost its admitted security context");
            // A concurrent ground-only checkpoint can advance asOf. The frame was admitted at its original
            // start; recheck the remaining interval in the current snapshot, then commit its counter once.
            var from = state.asOf().isAfter(completed.from()) ? state.asOf() : completed.from();
            state = transitions.recordProtectedTraffic(state, completed.context(), from, now, completed.uplink() ? 1 : 0, completed.uplink() ? 0 : 1);
            var bytes = completed.contact().payload().frameBytes();
            var counts = delivered.get(completed.contact().window().id());
            counts[completed.uplink() ? 0 : 1] = Math.addExact(counts[completed.uplink() ? 0 : 1], bytes);
            nextUp.put(completed.contact().window().id(), !completed.uplink());
            journal.add(new SimulationEvent(journal.size(), now, PAYLOAD_TRANSFERRED, null, null, completed.context(),
                    completed.contact().window().id(), completed.keys(), ResourceUsage.ZERO, Duration.between(completed.from(), now),
                    completed.uplink() ? bytes : 0, completed.uplink() ? 0 : bytes, completed.uplink() ? 1 : 0,
                    completed.uplink() ? 0 : 1, 0, null, "Complete protected frame", input.disclosures().isEmpty() ? null
                    : new SimulationEvent.FrameEvidence(completed.from(), now,
                    completed.uplink() ? Direction.UPLINK : Direction.DOWNLINK,
                    completed.keys().forDirection(completed.uplink() ? Direction.UPLINK : Direction.DOWNLINK), bytes, bytes, true)));
            frame = null;
        }

        boolean hasBudget(SimulationContact.PayloadProfile profile, long[] counts, boolean up) {
            return (up ? profile.offeredUplinkBytes() - counts[0] : profile.offeredDownlinkBytes() - counts[1]) >= profile.frameBytes();
        }

        ContactWindow contact(PlannedOperation planned) {
            return planned.contactId() == null ? null : contacts.get(planned.contactId()).window();
        }
        ContextId context(ContactWindow contact) { return new ContextId(contact.satelliteId(), contact.groundStationId()); }
        static Instant earlier(Instant a, Instant b) { return a.isBefore(b) ? a : b; }

        void log(SimulationEvent.Type type, String operationId, SecurityAction action, ContextId contextId, String contactId,
                 KeyPairIds keys, ResourceUsage usage, long createdKeys, String failureCode, String detail) {
            journal.add(new SimulationEvent(journal.size(), now, type, operationId, action, contextId, contactId, keys,
                    usage, Duration.ZERO, 0, 0, 0, 0, createdKeys, failureCode, detail));
        }
    }
}
