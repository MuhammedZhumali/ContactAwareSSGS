package org.contactaware.security.simulation;

import org.contactaware.contacts.Model.Interval;
import org.contactaware.contacts.schedule.*;
import org.contactaware.security.*;
import java.time.*;
import java.util.*;

public record SimulationScenario(String name, Instant start, Instant end, SecurityState initialState,
                                 SecurityConstraints constraints, SecurityCostProfile costs,
                                 List<SimulationContact> contacts, List<PlannedOperation> plan) {
    public SimulationScenario {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Scenario name is required");
        var period = new Interval(start, end);
        Objects.requireNonNull(initialState); Objects.requireNonNull(constraints); Objects.requireNonNull(costs);
        if (initialState.asOf().isAfter(start)) throw new IllegalArgumentException("Initial state is from the future");
        if (initialState.contexts().values().stream().anyMatch(c -> c.status() == AssociationStatus.OPERATIONAL))
            throw new IllegalArgumentException("Start from a closed initial session");
        contacts = contacts.stream().sorted(Comparator.comparing(c -> c.window().start())).toList();
        var windows = contacts.stream().map(SimulationContact::window).toList();
        ScheduleValidator.validate(windows, windows, initialState.satelliteId(), period,
                ScheduleSelectionConfig.DEFAULT).requireValid();
        plan = plan.stream().sorted(Comparator.comparing((PlannedOperation p) -> p.intent().scheduledAt())
                .thenComparing(PlannedOperation::id)).toList();
        var ids = new HashSet<String>();
        var contactIds = new HashSet<String>();
        windows.forEach(c -> contactIds.add(c.id()));
        for (var operation : plan) {
            if (!ids.add(operation.id())) throw new IllegalArgumentException("Duplicate operation ID");
            var at = operation.intent().scheduledAt();
            if (at.isBefore(start) || !at.isBefore(end)) throw new IllegalArgumentException("Action outside simulation period");
            if (!initialState.satelliteId().equals(operation.intent().contextId().satelliteId()))
                throw new IllegalArgumentException("Action for a different spacecraft");
            if (operation.contactId() != null && !contactIds.contains(operation.contactId()))
                throw new IllegalArgumentException("Unknown action contact");
        }
    }
}
