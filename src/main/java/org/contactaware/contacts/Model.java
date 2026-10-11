package org.contactaware.contacts;

import java.time.Instant;
import java.time.Duration;
import java.util.*;

public final class Model {
    private Model() {}
    public record Interval(Instant start, Instant end) {
        public Interval { if (start == null || end == null || !start.isBefore(end)) throw new IllegalArgumentException("start must precede end"); }
    }
    public record ContactWindow(String id, String satelliteId, String groundStationId,
                                Instant start, Instant end, Long linkRateBps, Long expectedDataBytes,
                                List<String> sourceRecordIds) {
        public ContactWindow {
            requireText(id, "contact id"); requireText(satelliteId, "satelliteId"); requireText(groundStationId, "groundStationId");
            new Interval(start, end);
            if (linkRateBps != null && linkRateBps <= 0) throw new IllegalArgumentException("linkRateBps must be positive or null (unknown)");
            if (expectedDataBytes != null && expectedDataBytes < 0) throw new IllegalArgumentException("expectedDataBytes must be nonnegative or null");
            sourceRecordIds = List.copyOf(sourceRecordIds);
        }
        public double durationSeconds() { return seconds(start, end); }
    }
    public record GroundStation(String id, String name, double latitudeDeg, double longitudeDeg,
                                double altitudeMeters, String coordinateSource) {
        public GroundStation {
            requireText(id,"station id"); requireText(name,"station name"); requireText(coordinateSource,"coordinate source");
            if (!Double.isFinite(latitudeDeg) || Math.abs(latitudeDeg) > 90 || !Double.isFinite(longitudeDeg) || Math.abs(longitudeDeg) > 180 || !Double.isFinite(altitudeMeters))
                throw new IllegalArgumentException("Invalid station coordinates: " + id);
        }
    }
    public record Source(String url, Instant retrievedAt, String file, String sha256, Map<String,String> responseHeaders) {
        public Source { responseHeaders = Map.copyOf(responseHeaders); }
    }
    public record Dataset(String schemaVersion, String kind, int noradId, Interval interval,
                          List<GroundStation> stations, List<ContactWindow> contacts,
                          List<Source> sources, Map<String,Object> parameters, List<String> limitations) {
        public Dataset {
            if (noradId <= 0) throw new IllegalArgumentException("NORAD id must be positive");
            stations = List.copyOf(stations); contacts = sorted(contacts); sources = List.copyOf(sources);
            parameters = Collections.unmodifiableMap(new LinkedHashMap<>(parameters)); limitations = List.copyOf(limitations);
            Set<String> stationIds = new HashSet<>(), ids = new HashSet<>();
            for (var s: stations) if (!stationIds.add(s.id())) throw new IllegalArgumentException("Duplicate station " + s.id());
            for (var c: contacts) {
                if (!ids.add(c.id())) throw new IllegalArgumentException("Duplicate contact " + c.id());
                if (!c.satelliteId().equals("NORAD-" + noradId) || !stationIds.contains(c.groundStationId())) throw new IllegalArgumentException("Contact outside satellite/station cohort");
                if (c.start().isBefore(interval.start()) || c.end().isAfter(interval.end())) throw new IllegalArgumentException("Contact outside requested interval");
            }
        }
    }
    public static final Comparator<ContactWindow> ORDER = Comparator.comparing(ContactWindow::start).thenComparing(ContactWindow::end).thenComparing(ContactWindow::groundStationId).thenComparing(ContactWindow::id);
    public static List<ContactWindow> sorted(Collection<ContactWindow> contacts) { return contacts.stream().sorted(ORDER).toList(); }
    public static double seconds(Instant start, Instant end) { return Duration.between(start, end).toNanos() / 1e9; }
    public static void requireText(String s,String label) { if(s==null || s.isBlank()) throw new IllegalArgumentException("Missing " + label); }
}
