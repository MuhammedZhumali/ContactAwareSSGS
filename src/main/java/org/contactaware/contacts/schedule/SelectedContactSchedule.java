package org.contactaware.contacts.schedule;

import org.contactaware.contacts.Model.ContactWindow;
import org.contactaware.contacts.Model.Interval;
import java.util.List;

public record SelectedContactSchedule(String schemaVersion, String kind, String satelliteId,
        String datasetSource, String datasetKind, Interval interval, String selectionAlgorithm,
        String algorithmVersion, String configurationVersion, List<String> optimizationObjective,
        ScheduleSelectionConfig configuration, String inputDatasetSha256, List<ContactWindow> contacts) {
    public SelectedContactSchedule {
        contacts = List.copyOf(contacts); optimizationObjective = List.copyOf(optimizationObjective);
    }
}
