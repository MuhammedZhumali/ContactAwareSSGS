package org.contactaware.contacts;

import java.nio.file.*;
import java.util.*;
import static org.contactaware.contacts.Model.*;
import static org.contactaware.contacts.JsonFiles.*;

public final class Snapshots {
    private Snapshots() {}
    public static Source copyInput(Path input,Path out,String relative) throws Exception {
        Path metadata=input.resolveSibling(input.getFileName()+".source.json");
        Source source=Files.exists(metadata)?JSON.readValue(metadata.toFile(),Source.class):
                new Source(input.toAbsolutePath().toUri().toString(),null,input.getFileName().toString(),sha256(Files.readAllBytes(input)),Map.of());
        return copyVerified(input,source,out,relative);
    }
    public static Source copyVerified(Path input,Source source,Path out,String relative) throws Exception {
        byte[] content=Files.readAllBytes(input);
        if(!sha256(content).equals(source.sha256()))throw new IllegalArgumentException("Snapshot checksum mismatch: " + input);
        Path destination=safeResolve(out,relative); Files.createDirectories(destination.getParent()); Files.write(destination,content);
        var result=new Source(source.url(),source.retrievedAt(),relative,source.sha256(),source.responseHeaders());
        write(destination.resolveSibling(destination.getFileName()+".source.json"),result); return result;
    }
    public static Path safeResolve(Path root,String relative) {
        Path normalized=root.toAbsolutePath().normalize(); Path path=normalized.resolve(relative).normalize();
        if(!path.startsWith(normalized) || Path.of(relative).isAbsolute())throw new IllegalArgumentException("Snapshot path escapes dataset directory");return path;
    }
    public static void verify(Dataset dataset,Path directory) throws Exception {
        for(var source:dataset.sources())if(!sha256(Files.readAllBytes(safeResolve(directory,source.file()))).equals(source.sha256()))throw new IllegalArgumentException("Snapshot checksum mismatch: " + source.file());
    }
}
