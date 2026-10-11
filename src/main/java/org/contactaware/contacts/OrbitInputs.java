package org.contactaware.contacts;

import com.fasterxml.jackson.databind.JsonNode;
import org.orekit.data.*;
import org.orekit.propagation.analytical.tle.TLE;
import org.orekit.time.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.io.*;
import java.util.regex.Pattern;
import java.util.*;
import static org.contactaware.contacts.JsonFiles.*;

public final class OrbitInputs {
    private OrbitInputs() {}
    public static LazyLoadedDataContext context(Path data) {
        if(!Files.isDirectory(data)) throw new IllegalArgumentException("Missing Orekit data directory: " + data);
        var ctx=new LazyLoadedDataContext();
        Path finals=data.resolve("finals2000A.all");
        if(Files.exists(finals)) {
            final byte[] valid;
            try {
                // IERS appends dated placeholder rows with no measured/predicted pole or UT1 values.
                // Orekit otherwise interprets these blanks as zero EOP and extends the reported coverage.
                valid=String.join("\n",Files.readAllLines(finals,StandardCharsets.US_ASCII).stream()
                        .filter(line->line.length()>=78 && !line.substring(16,55).isBlank() && !line.substring(57,78).isBlank()).toList()).getBytes(StandardCharsets.US_ASCII);
            } catch(IOException e) { throw new UncheckedIOException(e); }
            ctx.getDataProvidersManager().addProvider(new DataProvider() {
                @Override public boolean feed(Pattern supported,DataLoader loader,DataProvidersManager manager) {
                    if(supported.matcher("finals2000A.all").matches() && loader.stillAcceptsData()) {
                        try { loader.loadData(new ByteArrayInputStream(valid),"finals2000A.all"); }
                        catch(IOException | java.text.ParseException e) { throw new IllegalArgumentException("Could not load valid IERS rows",e); }
                        return true;
                    }
                    return false;
                }
            });
        }
        ctx.getDataProvidersManager().addProvider(new DirectoryCrawler(data.toFile()));
        ctx.getTimeScales().getUTC(); // Fail rather than substitute an invented UTC/TAI table.
        return ctx;
    }
    public static TLE read(Path path,int norad,TimeScale utc) throws Exception {
        String content=Files.readString(path,StandardCharsets.UTF_8).strip();
        TLE tle;
        if(content.startsWith("[")||content.startsWith("{")) {
            JsonNode root=JSON.readTree(content); List<JsonNode> matches=new ArrayList<>();
            if(root.isArray()) root.forEach(n->{if(n.path("NORAD_CAT_ID").asInt()==norad)matches.add(n);});
            else if(root.path("NORAD_CAT_ID").asInt()==norad) matches.add(root);
            if(matches.size()!=1) throw new IllegalArgumentException("Expected exactly one matching GP/OMM JSON element set; choose an epoch explicitly");
            tle=fromGpJson(matches.getFirst(),utc);
        } else {
            var lines=content.lines().filter(l->!l.isBlank()).toList();
            int offset=lines.size()==3?1:0;
            if(lines.size()-offset!=2 || !TLE.isFormatOK(lines.get(offset),lines.get(offset+1))) throw new IllegalArgumentException("Expected one valid 2LE/3LE with checksums");
            tle=new TLE(lines.get(offset),lines.get(offset+1),utc);
        }
        if(tle.getSatelliteNumber()!=norad) throw new IllegalArgumentException("Orbital data NORAD mismatch");
        return tle;
    }
    /** CelesTrak JSON uses OMM names and TLE-compatible GP units; omitted redundant metadata has documented defaults. */
    static TLE fromGpJson(JsonNode n,TimeScale utc) {
        for(var field:Map.of("CENTER_NAME","EARTH","REF_FRAME","TEME","TIME_SYSTEM","UTC","MEAN_ELEMENT_THEORY","SGP4").entrySet()) {
            if(n.hasNonNull(field.getKey())&&!n.get(field.getKey()).asText().equals(field.getValue())) throw new IllegalArgumentException("Unsupported " + field.getKey());
        }
        int cat=integer(n,"NORAD_CAT_ID"); double motion=num(n,"MEAN_MOTION"),ecc=num(n,"ECCENTRICITY"),incl=num(n,"INCLINATION");
        if(cat<=0 || motion<=0 || ecc<0 || ecc>=1 || incl<0 || incl>180) throw new IllegalArgumentException("Invalid GP elements");
        // Current GP profile supports SGP4/SDP4 selection, not alternate ephemeris theories.
        int type=integer(n,"EPHEMERIS_TYPE"); if(type!=0 && type!=2 && type!=3) throw new IllegalArgumentException("Unsupported EPHEMERIS_TYPE " + type);
        String object=n.path("OBJECT_ID").asText(""); var m=java.util.regex.Pattern.compile("(\\d{4})-(\\d{3})([A-Z]*)").matcher(object);
        int year=2000,launch=0; String piece="";
        if(m.matches()) { year=Integer.parseInt(m.group(1)); launch=Integer.parseInt(m.group(2)); piece=m.group(3); }
        else if(!object.isBlank()) throw new IllegalArgumentException("Invalid OBJECT_ID");
        String classification=n.path("CLASSIFICATION_TYPE").asText("U"); if(classification.length()!=1) throw new IllegalArgumentException("Invalid classification");
        // Convert rev/day, TLE n-dot/2 and n-double-dot/6 to Orekit's SI derivatives.
        return new TLE(cat,classification.charAt(0),year,launch,piece,type,integer(n,"ELEMENT_SET_NO"),
                new AbsoluteDate(SatnogsImporter.text(n,"EPOCH"),utc),motion*2*Math.PI/86400,
                num(n,"MEAN_MOTION_DOT")*4*Math.PI/(86400.0*86400),num(n,"MEAN_MOTION_DDOT")*12*Math.PI/Math.pow(86400,3),
                ecc,Math.toRadians(incl),Math.toRadians(num(n,"ARG_OF_PERICENTER")),Math.toRadians(num(n,"RA_OF_ASC_NODE")),
                Math.toRadians(num(n,"MEAN_ANOMALY")),integer(n,"REV_AT_EPOCH"),num(n,"BSTAR"),utc);
    }
    private static double num(JsonNode n,String key) { return SatnogsImporter.number(n,key).doubleValue(); }
    private static int integer(JsonNode n,String key) {
        var v=n.get(key); if(v==null || !v.isIntegralNumber() || !v.canConvertToInt()) throw new IllegalArgumentException("Expected integer " + key); return v.intValue();
    }
}
