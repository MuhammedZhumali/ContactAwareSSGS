package org.contactaware.contacts.schedule;

import org.contactaware.contacts.Model.Dataset;
import org.contactaware.contacts.Snapshots;
import java.nio.file.*;
import static org.contactaware.contacts.JsonFiles.*;

public final class ScheduleFiles {
    public static final String KIND = "SELECTED_CONTACT_SCHEDULE";
    private ScheduleFiles() {}

    public static ScheduleSelectionResult select(Path input, Path out, ScheduleSelectionConfig config) throws Exception {
        var data = readDataset(input); Snapshots.verify(data, input.toAbsolutePath().getParent());
        var result = new ContactScheduleSelector().select(data.contacts(), "NORAD-" + data.noradId(), data.interval(), config);
        var schedule = new SelectedContactSchedule("1.0", KIND, "NORAD-" + data.noradId(), relative(out, input),
                data.kind(), data.interval(), ContactScheduleSelector.ALGORITHM, ContactScheduleSelector.VERSION,
                ScheduleSelectionConfig.VERSION, ContactScheduleSelector.OBJECTIVE, config,
                sha256(Files.readAllBytes(input)), result.contacts());
        newOutput(out); write(out.resolve("selected-contacts.json"), result.contacts());
        write(out.resolve("schedule.json"), schedule); write(out.resolve("selection-report.json"), result.metrics());
        return result;
    }

    public record Verified(SelectedContactSchedule schedule, Dataset source) {}
    public static Verified readVerified(Path input) throws Exception {
        var schedule = JSON.readValue(input.toFile(), SelectedContactSchedule.class);
        if (!KIND.equals(schedule.kind()) || !"1.0".equals(schedule.schemaVersion())
                || !ContactScheduleSelector.ALGORITHM.equals(schedule.selectionAlgorithm())
                || !ContactScheduleSelector.VERSION.equals(schedule.algorithmVersion())
                || !ScheduleSelectionConfig.VERSION.equals(schedule.configurationVersion())
                || !ContactScheduleSelector.OBJECTIVE.equals(schedule.optimizationObjective()))
            throw new IllegalArgumentException("Unsupported schedule algorithm/schema/configuration metadata");
        var sourcePath = input.toAbsolutePath().getParent().resolve(schedule.datasetSource()).normalize();
        if (!sha256(Files.readAllBytes(sourcePath)).equals(schedule.inputDatasetSha256()))
            throw new IllegalArgumentException("Schedule input dataset checksum mismatch");
        var source = readDataset(sourcePath); Snapshots.verify(source, sourcePath.getParent());
        if (!source.interval().equals(schedule.interval()) || !schedule.satelliteId().equals("NORAD-" + source.noradId())
                || !source.kind().equals(schedule.datasetKind())) throw new IllegalArgumentException("Schedule/source metadata mismatch");
        ScheduleValidator.validate(schedule.contacts(), source.contacts(), schedule.satelliteId(),
                schedule.interval(), schedule.configuration()).requireValid();
        return new Verified(schedule, source);
    }

    public static String relative(Path directory, Path file) {
        var dir = directory.toAbsolutePath().normalize(); var path = file.toAbsolutePath().normalize();
        return (dir.getRoot().equals(path.getRoot()) ? dir.relativize(path) : path).toString().replace('\\', '/');
    }
}
