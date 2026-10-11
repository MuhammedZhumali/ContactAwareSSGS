package org.contactaware.contacts.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import org.contactaware.contacts.Model.*;
import org.contactaware.contacts.Snapshots;
import org.contactaware.contacts.schedule.ScheduleFiles;
import java.nio.file.*;
import java.util.*;
import static org.contactaware.contacts.JsonFiles.*;

public final class GapAnalysisFiles {
    private GapAnalysisFiles() {}
    public record Report(String schemaVersion, String analyzerVersion, String inputSource, String inputSha256,
                         String inputKind, String satelliteId, Interval interval, List<String> interpretation,
                         ContactGapAnalyzer.Result statistics) {}

    public static Report analyze(Path input, Path out, double shortSeconds) throws Exception {
        var tree = JSON.readTree(input.toFile()); String kind = tree.path("kind").asText();
        List<ContactWindow> contacts; List<GroundStation> stations; Interval period; String satellite;
        boolean selected = ScheduleFiles.KIND.equals(kind);
        if (selected) {
            var verified = ScheduleFiles.readVerified(input); var schedule = verified.schedule();
            contacts = schedule.contacts(); stations = verified.source().stations(); period = schedule.interval(); satellite = schedule.satelliteId();
        } else {
            var dataset = readDataset(input); Snapshots.verify(dataset, input.toAbsolutePath().getParent());
            contacts = dataset.contacts(); stations = dataset.stations(); period = dataset.interval(); satellite = "NORAD-" + dataset.noradId();
        }
        var stats = new ContactGapAnalyzer().analyze(contacts, satellite, period,
                stations.stream().map(GroundStation::id).toList(), selected, shortSeconds);
        var report = new Report("1.0", ContactGapAnalyzer.VERSION, ScheduleFiles.relative(out, input),
                sha256(Files.readAllBytes(input)), kind, satellite, period, List.of(
                selected ? "Selected whole-window schedule for a single simultaneous link; not observed communications"
                         : "Alternative contact windows; chronological GS changes are not executed handovers",
                "Adjacent and same-GS recurrence gaps: next.start - previous.end; sorted by start, end, contact ID",
                "Recurrence uses consecutive appearances of each GS, including returns after other GS contacts",
                "Union availability merges overlaps; unavailable time includes both analysis-period boundaries",
                "Candidate duration sum may double-count time; scheduled duration is defined only for selected schedules",
                "Reuse eligibility is temporal only, not permission to reuse keys or Security Associations",
                "Empty distributions and fractions have null values; short contacts have duration strictly below the saved threshold"), stats);
        newOutput(out); write(out.resolve("gap-analysis.json"), report);
        StringBuilder csv = new StringBuilder("metric,value\n"); flatten("", JSON.valueToTree(report), csv);
        Files.writeString(out.resolve("gap-analysis.csv"), csv);
        Files.writeString(out.resolve("same-gs-recurrence.csv"), recurrenceCsv(stats.sameGroundStationRecurrence()));
        return report;
    }

    private static void flatten(String path, JsonNode node, StringBuilder csv) {
        if (node.isObject()) node.fields().forEachRemaining(e -> flatten(path.isEmpty() ? e.getKey() : path + "." + e.getKey(), e.getValue(), csv));
        else if (node.isArray()) for (int i = 0; i < node.size(); i++) flatten(path + "[" + i + "]", node.get(i), csv);
        else csv.append(quote(path)).append(',').append(quote(node.isNull() ? "" : node.asText())).append('\n');
    }

    private static String recurrenceCsv(GroundStationRecurrenceAnalyzer.Result result) {
        StringBuilder csv = new StringBuilder("groundStationId,previousContactId,nextContactId,previousEnd,nextStart,gapSeconds,bucket,eligible30min,eligible1h,eligible2h,eligible6h\n");
        for (var p : result.pairs()) csv.append(String.join(",", List.of(p.groundStationId(), p.previousContactId(), p.nextContactId(),
                p.previousEnd().toString(), p.nextStart().toString(), p.gapSeconds().toPlainString(), p.bucket(),
                "" + p.eligible30min(), "" + p.eligible1h(), "" + p.eligible2h(), "" + p.eligible6h()).stream().map(GapAnalysisFiles::quote).toList())).append('\n');
        return csv.toString();
    }
    private static String quote(String s) { return "\"" + s.replace("\"", "\"\"") + "\""; }
}
