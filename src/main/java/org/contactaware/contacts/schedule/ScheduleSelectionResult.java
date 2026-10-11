package org.contactaware.contacts.schedule;

import org.contactaware.contacts.Model.ContactWindow;
import java.util.List;

public record ScheduleSelectionResult(List<ContactWindow> contacts, ScheduleSelectionMetrics metrics) {
    public ScheduleSelectionResult { contacts = List.copyOf(contacts); }
}
