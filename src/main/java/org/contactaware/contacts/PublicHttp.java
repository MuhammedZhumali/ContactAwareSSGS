package org.contactaware.contacts;

import java.net.URI;
import java.net.http.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.contactaware.contacts.Model.*;

/** No automatic retries/redirects: a provider error is reported, never hidden by a retry storm. */
public class PublicHttp {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NEVER).build();
    public record Download(byte[] bytes, Source source, String link) {}
    public Download get(URI uri, Path out, String relativeFile) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60))
                .header("User-Agent","ContactAwareSS/0.1 (public-data research prototype)").header("Accept","application/json, text/plain, */*").GET().build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if(response.statusCode()!=200) throw new IOException("HTTP " + response.statusCode() + " from " + uri + "; stopped without retries. Retry-After=" + response.headers().firstValue("Retry-After").orElse("absent"));
        var path=out.resolve(relativeFile); Files.createDirectories(path.getParent()); Files.write(path,response.body());
        var headers=new TreeMap<String,String>();
        for(String h:List.of("date","etag","last-modified","content-type","link")) response.headers().firstValue(h).ifPresent(v->headers.put(h,v));
        var source=new Source(uri.toString(),Instant.now(),relativeFile,JsonFiles.sha256(response.body()),headers);
        JsonFiles.write(out.resolve(relativeFile + ".source.json"),source);
        return new Download(response.body(),source,response.headers().firstValue("link").orElse(""));
    }
}
