package org.contactaware.security.simulation;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.contactaware.contacts.JsonFiles;
import org.contactaware.security.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.TreeMap;

/** Versioned local checkpoints. A full checkpoint is privileged and must not be passed to a planner. */
public final class ExecutionCheckpointFiles {
    private ExecutionCheckpointFiles() {}
    public record Checkpoint(String version, ExecutionSnapshot snapshot) {
        public Checkpoint {
            if (!"se21-1".equals(version)) throw new IllegalArgumentException("Unsupported checkpoint version");
            java.util.Objects.requireNonNull(snapshot);
        }
    }
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties("securityOperation")
    private abstract static class PlanningActionFields { }
    private static final ObjectMapper JSON = JsonFiles.JSON.copy().addMixIn(PlanningAction.class, PlanningActionFields.class)
            .registerModule(new SimpleModule()
            .addDeserializer(SecurityState.class, new JsonDeserializer<>() {
                @Override public SecurityState deserialize(JsonParser parser, DeserializationContext context) throws IOException {
                    JsonNode node = parser.getCodec().readTree(parser);
                    var contexts = new TreeMap<ContextId, SecurityContext>();
                    var keys = new TreeMap<String, KeyState>();
                    for (var value : node.required("contexts")) {
                        var entry = parser.getCodec().treeToValue(value, SecurityContext.class);
                        if (contexts.put(entry.id(), entry) != null) throw new IOException("Duplicate context identity");
                    }
                    for (var value : node.required("keys")) {
                        var entry = parser.getCodec().treeToValue(value, KeyState.class);
                        if (keys.put(entry.id(), entry) != null) throw new IOException("Duplicate key identity");
                    }
                    return new SecurityState(node.required("satelliteId").asText(),
                            Instant.parse(node.required("asOf").asText()), contexts, keys);
                }
            }));

    public static <T> T read(Path path, Class<T> type) throws IOException { return JSON.readValue(path.toFile(), type); }
    public static <T> T convert(JsonNode value, Class<T> type) throws IOException { return JSON.treeToValue(value, type); }
    public static ExecutionSnapshot read(Path path) throws IOException { return read(path, Checkpoint.class).snapshot(); }
    public static void write(Path path, ExecutionSnapshot snapshot) throws IOException { writeAtomic(path, new Checkpoint("se21-1", snapshot)); }
    public static void writeAtomic(Path path, Object value) throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        JSON.writeValue(temporary.toFile(), value);
        try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
