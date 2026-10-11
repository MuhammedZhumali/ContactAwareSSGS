package org.contactaware.contacts;

import org.contactaware.contacts.Model.*;
import org.contactaware.contacts.schedule.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.contactaware.contacts.JsonFiles.*;
import static org.contactaware.contacts.ContactScheduleSelectorTest.c;
import static org.contactaware.contacts.ContactScheduleSelectorTest.PERIOD;

class ScheduleCliTest {
    @TempDir Path temp;
    private static final Path REAL = Path.of("data/runs/iss-20261010/orekit/dataset.json");

    @Test void actual146WindowsMatchIndependentDurationOptimumWithoutChangingInputs() throws Exception {
        byte[] before = Files.readAllBytes(REAL); var data = readDataset(REAL); assertThat(data.contacts()).hasSize(146);
        Main.run(new String[]{"schedule-select", "--dataset", REAL.toString(), "--out", temp.resolve("schedule").toString()});
        var schedule = ScheduleFiles.readVerified(temp.resolve("schedule/schedule.json")).schedule();
        var sorted = data.contacts().stream().sorted(Comparator.comparing(ContactWindow::end)).toList();
        long[] optimum = new long[sorted.size() + 1];
        for (int i = 1; i <= sorted.size(); i++) {
            var current = sorted.get(i - 1); int predecessor = i - 1;
            while (predecessor > 0 && sorted.get(predecessor - 1).end().isAfter(current.start())) predecessor--;
            optimum[i] = Math.max(optimum[i - 1], optimum[predecessor] + Duration.between(current.start(), current.end()).toNanos());
        }
        long selectedNanos = schedule.contacts().stream().mapToLong(c -> Duration.between(c.start(), c.end()).toNanos()).sum();
        assertThat(selectedNanos).isEqualTo(optimum[sorted.size()]);
        assertThat(Files.readAllBytes(REAL)).isEqualTo(before);
        assertThat(JSON.readValue(temp.resolve("schedule/selected-contacts.json").toFile(), ContactWindow[].class)).containsExactlyElementsOf(schedule.contacts());
    }

    @Test void cliReproducesEveryArtifactAndValidatesProvenanceAndGuards() throws Exception {
        Path input = temp.resolve("dataset.json");
        var data = new Dataset("1.0", "TEST", 25544, PERIOD, List.of(new GroundStation("A", "A", 0, 0, 0, "test"),
                new GroundStation("B", "B", 1, 1, 0, "test")), List.of(c("a", "A", 0, 10), c("b", "B", 11, 21), c("a2", "A", 60, 100)), List.of(), Map.of(), List.of());
        write(input, data);
        for (String run : List.of("one", "two")) {
            Main.run(new String[]{"schedule-select", "--dataset", input.toString(), "--switch-guard-seconds", "30", "--out", temp.resolve(run).toString()});
            Main.run(new String[]{"analyze-gaps", "--dataset", temp.resolve(run + "/schedule.json").toString(), "--out", temp.resolve(run + "/analysis").toString()});
            Main.run(new String[]{"analyze-gaps", "--dataset", input.toString(), "--out", temp.resolve(run + "/candidate-analysis").toString()});
        }
        for (String file : List.of("schedule.json", "selected-contacts.json", "selection-report.json", "analysis/gap-analysis.json", "analysis/gap-analysis.csv", "analysis/same-gs-recurrence.csv", "candidate-analysis/gap-analysis.json"))
            assertThat(Files.readAllBytes(temp.resolve("one/" + file))).as(file).isEqualTo(Files.readAllBytes(temp.resolve("two/" + file)));
        assertThatThrownBy(() -> Main.run(new String[]{"schedule-select", "--dataset", input.toString(), "--out", temp.resolve("one").toString()})).hasMessageContaining("empty");
        Files.writeString(input, "\n", StandardOpenOption.APPEND);
        assertThatThrownBy(() -> ScheduleFiles.readVerified(temp.resolve("one/schedule.json"))).hasMessageContaining("checksum");
    }
}
