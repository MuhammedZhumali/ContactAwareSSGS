package org.contactaware.contacts;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.nio.file.*;
import java.security.*;
import java.util.HexFormat;
import static org.contactaware.contacts.Model.*;

public final class JsonFiles {
    public static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).enable(SerializationFeature.INDENT_OUTPUT).enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private JsonFiles() {}
    public static void write(Path file, Object value) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        JSON.writeValue(file.toFile(),value);
    }
    public static String sha256(byte[] data) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)); }
        catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public static Path newOutput(Path path) throws IOException {
        if (Files.exists(path)) {
            try(var entries=Files.list(path)) { if(entries.findAny().isPresent()) throw new IOException("Output directory must be empty: " + path); }
        }
        Files.createDirectories(path); return path;
    }
    public static void saveDataset(Path out, Dataset dataset) throws IOException {
        write(out.resolve("dataset.json"),dataset);
        write(out.resolve("contacts.json"),dataset.contacts());
        write(out.resolve("stations.json"),dataset.stations());
    }
    public static Dataset readDataset(Path path) throws IOException { return JSON.readValue(path.toFile(),Dataset.class); }
}
