package org.contactaware.security.simulation;

import org.contactaware.contacts.Model.Interval;
import org.contactaware.contacts.schedule.*;
import org.contactaware.security.*;
import java.time.*;
import java.util.*;

public record SimulationScenario(String name, Instant start, Instant end, SecurityState initialState,
                                 SecurityConstraints constraints, SecurityCostProfile costs,
                                 List<SimulationContact> contacts, List<PlannedOperation> plan,
                                 List<TrafficKeyDisclosure> disclosures,
                                 @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
                                 List<ChannelInterruption> channelInterruptions) {
    public SimulationScenario(String name, Instant start, Instant end, SecurityState initialState,
                              SecurityConstraints constraints, SecurityCostProfile costs,
                              List<SimulationContact> contacts, List<PlannedOperation> plan) {
        this(name, start, end, initialState, constraints, costs, contacts, plan, List.of());
    }

    public SimulationScenario(String name, Instant start, Instant end, SecurityState initialState,
                              SecurityConstraints constraints, SecurityCostProfile costs,
                              List<SimulationContact> contacts, List<PlannedOperation> plan,
                              List<TrafficKeyDisclosure> disclosures) {
        this(name, start, end, initialState, constraints, costs, contacts, plan, disclosures, List.of());
    }

    public SimulationScenario {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Scenario name is required");
        var period = new Interval(start, end);
        Objects.requireNonNull(initialState); Objects.requireNonNull(constraints); Objects.requireNonNull(costs);
        disclosures = List.copyOf(disclosures);
        var incidentIds = new HashSet<String>();
        for (var disclosure : disclosures) {
            if (!incidentIds.add(disclosure.id())) throw new IllegalArgumentException("Duplicate disclosure ID");
            if (disclosure.at().isBefore(start) || !disclosure.at().isBefore(end))
                throw new IllegalArgumentException("Disclosure outside experiment interval");
            if (!initialState.contexts().containsKey(disclosure.contextId()))
                throw new IllegalArgumentException("Disclosure context is not configured");
        }
        if (initialState.asOf().isAfter(start)) throw new IllegalArgumentException("Initial state is from the future");
        if (initialState.contexts().values().stream().anyMatch(c -> c.status() == AssociationStatus.OPERATIONAL))
            throw new IllegalArgumentException("Start from a closed initial session");
        contacts = contacts.stream().sorted(Comparator.comparing(c -> c.window().start())).toList();
        var windows = contacts.stream().map(SimulationContact::window).toList();
        ScheduleValidator.validate(windows, windows, initialState.satelliteId(), period,
                ScheduleSelectionConfig.DEFAULT).requireValid();
        // Missing JSON property preserves the historical scenario and serialization contract.
        channelInterruptions = channelInterruptions == null ? List.of() : channelInterruptions.stream()
                .sorted(Comparator.comparing(ChannelInterruption::contactId).thenComparing(ChannelInterruption::from)
                        .thenComparing(ChannelInterruption::until)).toList();
        var previousEnd = new HashMap<String, Instant>();
        for (var interruption : channelInterruptions) {
            var window = windows.stream().filter(w -> w.id().equals(interruption.contactId())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown channel interruption contact"));
            if (interruption.from().isBefore(window.start()) || interruption.until().isAfter(window.end()))
                throw new IllegalArgumentException("Channel interruption must stay inside its forecast contact");
            var previous = previousEnd.put(interruption.contactId(), interruption.until());
            if (previous != null && interruption.from().isBefore(previous))
                throw new IllegalArgumentException("Channel interruptions must not overlap for the same contact");
        }
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
