package org.contactaware.security.simulation;

import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Read-only scan of the frozen SE2 factual journals for coincidence / invariant risks. */
public class JournalScan {
    public static void main(String[] args) throws Exception {
        var dir = Path.of(args[0]);
        var json = new ObjectMapper();
        var counts = new TreeMap<String, Integer>();
        var examples = new TreeMap<String, List<String>>();
        int files = 0;
        try (var stream = Files.newDirectoryStream(dir, "*.events.json")) {
            for (var file : stream) {
                files++;
                var name = file.getFileName().toString().replace(".events.json", "");
                JsonNode events = json.readTree(file.toFile());
                Instant disclosure = null, ground = null, sc = null; String up = null, down = null;
                for (var e : events) {
                    var type = e.get("type").asText();
                    if (type.equals("KEY_DISCLOSED") || type.equals("DISCLOSURE_NO_TARGET")) disclosure = Instant.parse(e.get("at").asText());
                    if (type.equals("KEY_DISCLOSED")) { up = e.get("keyIds").get("uplink").asText(); down = e.get("keyIds").get("downlink").asText(); }
                    if (type.equals("REVOCATION_RECEIVED_GROUND") && ground == null) ground = Instant.parse(e.get("at").asText());
                    if (type.equals("REVOCATION_RECEIVED_SPACECRAFT") && sc == null) sc = Instant.parse(e.get("at").asText());
                }
                for (var e : events) {
                    var type = e.get("type").asText();
                    var at = Instant.parse(e.get("at").asText());
                    var action = e.get("action").isNull() ? "" : e.get("action").asText();
                    var code = e.get("failureCode").isNull() ? "" : e.get("failureCode").asText();
                    if (disclosure != null && at.equals(disclosure) && !type.equals("KEY_DISCLOSED") && !type.equals("DISCLOSURE_NO_TARGET")
                            && !type.equals("ACTION_STARTED") && !type.equals("STEP_STARTED"))
                        add(counts, examples, "coincident-with-disclosure:" + type + ":" + action, name);
                    if (ground != null && at.equals(ground) && !type.startsWith("REVOCATION")
                            && !type.equals("ACTION_STARTED") && !type.equals("STEP_STARTED"))
                        add(counts, examples, "coincident-with-GS-notice:" + type + ":" + action + ":" + code, name);
                    if (type.equals("STEP_INTERRUPTED") || type.equals("STEP_FAILED") || type.equals("ACTION_FAILED"))
                        add(counts, examples, "failure:" + type + ":" + action + ":" + code, name);
                    if (type.equals("CONTEXT_UNCERTAIN") || type.equals("PAYLOAD_REJECTED")) add(counts, examples, "flag:" + type, name);
                    if (type.equals("PAYLOAD_ABORTED")) add(counts, examples, "aborted:" + code + ":" + e.get("frameEvidence").get("direction").asText(), name);
                    if ((type.equals("PAYLOAD_TRANSFERRED") || type.equals("PAYLOAD_ABORTED")) && up != null && !e.get("frameEvidence").isNull()) {
                        var f = e.get("frameEvidence");
                        var key = f.get("keyId").asText();
                        var from = Instant.parse(f.get("from").asText());
                        if (key.equals(up) || key.equals(down)) {
                            if (ground != null && !from.isBefore(ground)) add(counts, examples, "INVARIANT:compromised-frame-started-after-GS", name);
                            if (sc != null && Instant.parse(f.get("until").asText()).isAfter(sc)) add(counts, examples, "INVARIANT:compromised-emission-after-SC", name);
                            if (type.equals("PAYLOAD_TRANSFERRED") && ground != null && at.isAfter(ground)) add(counts, examples, "INVARIANT:compromised-delivered-after-GS", name);
                        }
                    }
                    if (type.equals("KEY_EXPIRED") && disclosure != null && at.equals(disclosure)) add(counts, examples, "expiry-at-disclosure", name);
                }
            }
        }
        System.out.println("files=" + files);
        counts.forEach((k, v) -> System.out.println(k + " = " + v + "  e.g. " + examples.get(k).subList(0, Math.min(4, examples.get(k).size()))));
    }
    static void add(Map<String, Integer> counts, Map<String, List<String>> ex, String key, String name) {
        counts.merge(key, 1, Integer::sum);
        var l = ex.computeIfAbsent(key, k -> new ArrayList<>());
        if (!l.contains(name)) l.add(name);
    }
}
