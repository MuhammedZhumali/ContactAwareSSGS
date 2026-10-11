package org.contactaware.contacts;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import static org.contactaware.contacts.Model.*;
import static org.contactaware.contacts.JsonFiles.*;

public final class SatnogsImporter {
    private final PublicHttp http;
    private final URI endpoint;
    public SatnogsImporter(PublicHttp http) { this(http,URI.create("https://network.satnogs.org/api/observations/")); }
    public SatnogsImporter(PublicHttp http,URI endpoint) { this.http=http; this.endpoint=endpoint; }
    public Dataset fetch(int norad, Interval interval, Set<String> stationIds, Set<String> statuses, int maxPages, Path out) throws Exception {
        if(maxPages<1) throw new IllegalArgumentException("maxPages must be positive");
        URI next=URI.create(endpoint + "?format=json&norad_cat_id=" + norad + "&start__lt=" + enc(interval.end()) + "&end__gt=" + enc(interval.start()));
        Set<URI> seen=new HashSet<>(); List<Source> sources=new ArrayList<>(); List<JsonNode> rows=new ArrayList<>();
        while(next!=null) {
            if(!seen.add(next)) throw new IOException("Pagination cycle: " + next);
            if(seen.size()>maxPages) throw new IOException("maxPages reached with more data available; no complete dataset written");
            if(!Objects.equals(next.getScheme(),endpoint.getScheme()) || !Objects.equals(next.getRawAuthority(),endpoint.getRawAuthority()) || !next.getPath().equals(endpoint.getPath())) throw new IOException("Unexpected pagination target " + next);
            var page=http.get(next,out,"raw/observations-%04d.json".formatted(seen.size())); sources.add(page.source());
            JsonNode root=JSON.readTree(page.bytes());
            JsonNode items=root.isArray()?root:root.get("results");
            if(items==null || !items.isArray()) throw new IOException("SatNOGS response is not an observation list");
            items.forEach(rows::add);
            String link=nextLink(page.link());
            if(link==null && root.isObject() && root.hasNonNull("next")) link=root.get("next").asText();
            next=link==null?null:next.resolve(link);
        }
        var result=normalize(norad,interval,stationIds,statuses,rows,sources);
        saveDataset(out,result); return result;
    }
    public static Dataset normalize(int norad, Interval interval, Set<String> stationFilter, Set<String> statuses, List<JsonNode> rows, List<Source> sources) {
        if(statuses.isEmpty() || !Set.of("good","bad","unknown").containsAll(statuses)) throw new IllegalArgumentException("Choose completed observation statuses: good,bad,unknown");
        Map<String,GroundStation> stations=new TreeMap<>(); Map<Long,JsonNode> unique=new TreeMap<>();
        List<ContactWindow> contacts=new ArrayList<>(); int excluded=0,duplicates=0;
        for(var row:rows) {
            int cat=Math.toIntExact(integer(row,"norad_cat_id"));
            var start=Instant.parse(text(row,"start")); var end=Instant.parse(text(row,"end")); new Interval(start,end);
            long stationId=integer(row,"ground_station"); if(stationId<=0)throw new IllegalArgumentException("Invalid station id");
            String gs="SATNOGS-" + stationId;
            if(cat!=norad || !start.isBefore(interval.end()) || !end.isAfter(interval.start()) || !statuses.contains(text(row,"status")) || (!stationFilter.isEmpty()&&!stationFilter.contains(gs))) { excluded++; continue; }
            long id=integer(row,"id"); if(id<=0) throw new IllegalArgumentException("Invalid observation id");
            var old=unique.putIfAbsent(id,row);
            if(old!=null) { if(!old.equals(row)) throw new IllegalArgumentException("Conflicting observation versions: " + id); duplicates++; continue; }
            var station=new GroundStation(gs,text(row,"station_name"),number(row,"station_lat").doubleValue(),number(row,"station_lng").doubleValue(),number(row,"station_alt").doubleValue(),"https://network.satnogs.org/observations/"+id+"/");
            var prev=stations.putIfAbsent(gs,station);
            if(prev!=null && (Math.abs(prev.latitudeDeg()-station.latitudeDeg())>1e-7 || Math.abs(prev.longitudeDeg()-station.longitudeDeg())>1e-7 || Math.abs(prev.altitudeMeters()-station.altitudeMeters())>0.01)) throw new IllegalArgumentException("Station coordinates changed within period: " + gs + "; split the cohort/period explicitly");
            // Payload demand and link bit rate are unknown; transmitter baud is not a measured useful-data rate.
            contacts.add(new ContactWindow("satnogs-"+id,"NORAD-"+norad,gs,start.isBefore(interval.start())?interval.start():start,end.isAfter(interval.end())?interval.end():end,null,null,List.of(Long.toString(id))));
        }
        if(contacts.isEmpty()) throw new IllegalArgumentException("No matching completed observations in selected period/cohort");
        if(!stationFilter.isEmpty()&&!stations.keySet().containsAll(stationFilter)) throw new IllegalArgumentException("Selected station has no matching observations");
        var merged=mergeSameStation(contacts);
        Map<String,Object> params=new LinkedHashMap<>(); params.put("statuses",new TreeSet<>(statuses)); params.put("rawRecords",rows.size()); params.put("excludedRecords",excluded); params.put("duplicateRecords",duplicates); params.put("selectedObservations",contacts.size()); params.put("mergedWindows",merged.size()); params.put("paginationComplete",true); params.put("boundaryPolicy","overlap query; clip to [start,end)"); params.put("normalization","union overlapping/touching records of same station; preserve all observation IDs");
        return new Dataset("1.0","SATNOGS_OBSERVATION_INTERVALS",norad,interval,new ArrayList<>(stations.values()),merged,sources,params,List.of(
                "Published observation start/end are scheduled recording intervals, not measured RF availability or authenticated spacecraft-ground sessions.",
                "Selection and station scheduling bias: absent observations do not imply absent geometric visibility; bad/unknown are retained by default.",
                "Overlaps across stations are alternatives, not an executable single-radio schedule; station changes in sorted records are not actual handovers.",
                "Rates and payload demand are unknown. SatNOGS API data: CC BY-SA; attribute SatNOGS / Libre Space Foundation and preserve source snapshots."));
    }
    static String nextLink(String header) {
        var matcher=Pattern.compile("<([^>]+)>\\s*;\\s*rel=\"?next\"?",Pattern.CASE_INSENSITIVE).matcher(header);
        return matcher.find()?matcher.group(1):null;
    }
    static List<ContactWindow> mergeSameStation(List<ContactWindow> windows) {
        Map<String,List<ContactWindow>> groups=new TreeMap<>(); windows.forEach(c->groups.computeIfAbsent(c.groundStationId(),k->new ArrayList<>()).add(c));
        List<ContactWindow> result=new ArrayList<>();
        for(var group:groups.values()) {
            ContactWindow current=null;
            for(var c:sorted(group)) {
                if(current!=null && !c.start().isAfter(current.end())) {
                    var ids=new TreeSet<>(current.sourceRecordIds()); ids.addAll(c.sourceRecordIds());
                    current=new ContactWindow("satnogs-"+String.join("-",ids),c.satelliteId(),c.groundStationId(),current.start(),c.end().isAfter(current.end())?c.end():current.end(),null,null,new ArrayList<>(ids));
                } else { if(current!=null) result.add(current); current=c; }
            }
            if(current!=null) result.add(current);
        }
        return sorted(result);
    }
    private static String enc(Instant i) { return URLEncoder.encode(i.toString(),StandardCharsets.UTF_8); }
    static String text(JsonNode n,String key) { var v=n.get(key); if(v==null || !v.isTextual() || v.asText().isBlank()) throw new IllegalArgumentException("Missing/invalid field " + key); return v.asText(); }
    static Number number(JsonNode n,String key) { var v=n.get(key); if(v==null || !v.isNumber() || !Double.isFinite(v.asDouble())) throw new IllegalArgumentException("Missing/invalid field " + key); return v.numberValue(); }
    static long integer(JsonNode n,String key) { var v=n.get(key);if(v==null||!v.isIntegralNumber()||!v.canConvertToLong())throw new IllegalArgumentException("Expected integer " + key);return v.longValue(); }
}
