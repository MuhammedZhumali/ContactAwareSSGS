package org.contactaware.security.simulation;

import org.contactaware.security.SecurityAction;
import java.time.Duration;
import java.util.*;
import static org.contactaware.security.simulation.SimulationEvent.Type.*;

/** Rebuildable metrics: never use a plan, predicted costs, or expected fixture totals. */
public final class MetricsCollector {
    public SimulationResult.Metrics collect(List<SimulationContact> contacts, List<SimulationEvent> events) {
        var costs = ResourceUsage.ZERO;
        var useful = Duration.ZERO;
        long created = 0;
        var started = new EnumMap<SecurityAction, Long>(SecurityAction.class);
        var completed = new EnumMap<SecurityAction, Long>(SecurityAction.class);
        var actions = new EnumMap<SecurityAction, Long>(SecurityAction.class);
        var failures = new TreeMap<String, Long>();
        for (var event : events) {
            costs = costs.plus(event.usage());
            useful = useful.plus(event.usefulTime());
            created = Math.addExact(created, event.createdKeys());
            if (event.type() == STEP_STARTED) started.merge(event.action(), 1L, Math::addExact);
            if (event.type() == STEP_COMPLETED) completed.merge(event.action(), 1L, Math::addExact);
            if (event.type() == ACTION_COMPLETED) actions.merge(event.action(), 1L, Math::addExact);
            // One failure per root action, not both root and primitive events.
            if (event.type() == ACTION_FAILED) failures.merge(event.failureCode(), 1L, Math::addExact);
        }
        var outcomes = new ArrayList<SimulationResult.ContactOutcome>();
        for (var contact : contacts) {
            long up = 0, down = 0, upFrames = 0, downFrames = 0;
            var time = Duration.ZERO;
            var reasons = new TreeSet<String>();
            for (var event : events) if (contact.window().id().equals(event.contactId())) {
                up = Math.addExact(up, event.uplinkPayloadBytes());
                down = Math.addExact(down, event.downlinkPayloadBytes());
                upFrames = Math.addExact(upFrames, event.uplinkFrames());
                downFrames = Math.addExact(downFrames, event.downlinkFrames());
                time = time.plus(event.usefulTime());
                if (event.failureCode() != null) reasons.add(event.failureCode());
            }
            boolean served = up >= contact.payload().minimumUplinkBytes() && down >= contact.payload().minimumDownlinkBytes();
            if (!served) reasons.add(up == 0 && down == 0 ? "NO_SECURE_PAYLOAD" : "SERVICE_THRESHOLD_NOT_MET");
            outcomes.add(new SimulationResult.ContactOutcome(contact.window().id(), served, time, up, down,
                    upFrames, downFrames, List.copyOf(reasons)));
        }
        return new SimulationResult.Metrics(outcomes.stream().filter(SimulationResult.ContactOutcome::served).count(),
                useful, costs, created, started, completed, actions, failures, outcomes);
    }
}
