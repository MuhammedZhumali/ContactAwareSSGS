package org.contactaware.contacts;

import java.util.*;
import java.time.Instant;
import static org.contactaware.contacts.Model.*;

public final class DatasetComparison {
    private DatasetComparison() {}
    public record Distribution(int count,Double min,Double median,Double mean,Double p95,Double max) {}
    public record Stats(int contacts,int stations,Distribution durationSeconds,Distribution signedAdjacentGapSeconds,
                        Distribution sameStationGapSeconds,Distribution unionBlackoutGapSeconds,
                        int overlappingAdjacentPairs,int chronologicalStationChanges,int adjacentPairs,
                        Double chronologicalStationChangeFraction,double shortWindowThresholdSeconds,
                        int shortWindows,Double shortWindowFraction,double unionVisibleSeconds,
                        Map<String,Integer> contactsByStation) {}
    public record Comparison(Interval interval,int noradId,Stats satnogs,Stats orekit,
                             int geometricWindowsWithoutObservation,int observationsWithoutGeometricOverlap,List<String> interpretation) {}
    public static Stats stats(Dataset data,double shortSeconds) {
        if(!Double.isFinite(shortSeconds)||shortSeconds<=0) throw new IllegalArgumentException("Short-window threshold must be positive");
        var contacts=sorted(data.contacts()); List<Double> durations=new ArrayList<>(),gaps=new ArrayList<>(),sameGaps=new ArrayList<>(),blackouts=new ArrayList<>();
        Map<String,ContactWindow> last=new HashMap<>(); Map<String,Integer> counts=new TreeMap<>();
        int overlaps=0,changes=0,shorts=0; ContactWindow prev=null;
        Instant unionStart=null,unionEnd=null; double union=0;
        for(var c:contacts) {
            durations.add(c.durationSeconds()); if(c.durationSeconds()<shortSeconds)shorts++;
            counts.merge(c.groundStationId(),1,Integer::sum);
            if(prev!=null){double gap=seconds(prev.end(),c.start());gaps.add(gap);if(gap<0)overlaps++;if(!prev.groundStationId().equals(c.groundStationId()))changes++;}
            var stationPrev=last.put(c.groundStationId(),c);if(stationPrev!=null)sameGaps.add(seconds(stationPrev.end(),c.start()));
            if(unionStart==null){unionStart=c.start();unionEnd=c.end();}
            else if(c.start().isAfter(unionEnd)){union+=seconds(unionStart,unionEnd);blackouts.add(seconds(unionEnd,c.start()));unionStart=c.start();unionEnd=c.end();}
            else if(c.end().isAfter(unionEnd))unionEnd=c.end();
            prev=c;
        }
        if(unionStart!=null)union+=seconds(unionStart,unionEnd);
        int pairs=Math.max(0,contacts.size()-1);
        return new Stats(contacts.size(),counts.size(),distribution(durations),distribution(gaps),distribution(sameGaps),distribution(blackouts),overlaps,changes,pairs,pairs==0?null:(double)changes/pairs,shortSeconds,shorts,contacts.isEmpty()?null:(double)shorts/contacts.size(),union,counts);
    }
    public static Comparison compare(Dataset observations,Dataset geometry,double shortSeconds) {
        if(!observations.kind().equals("SATNOGS_OBSERVATION_INTERVALS")||!geometry.kind().equals("OREKIT_GEOMETRIC_WINDOWS"))throw new IllegalArgumentException("Expected SatNOGS then Orekit datasets");
        if(observations.noradId()!=geometry.noradId()||!observations.interval().equals(geometry.interval()))throw new IllegalArgumentException("Comparison requires same satellite and exact interval");
        var a=cohort(observations);var b=cohort(geometry);
        if(!a.keySet().equals(b.keySet()))throw new IllegalArgumentException("Comparison requires identical station cohorts");
        for(String id:a.keySet()){var x=a.get(id);var y=b.get(id);if(x.latitudeDeg()!=y.latitudeDeg()||x.longitudeDeg()!=y.longitudeDeg()||x.altitudeMeters()!=y.altitudeMeters())throw new IllegalArgumentException("Station coordinates differ for " + id);}
        return new Comparison(observations.interval(),observations.noradId(),stats(observations,shortSeconds),stats(geometry,shortSeconds),unmatched(geometry.contacts(),observations.contacts()),unmatched(observations.contacts(),geometry.contacts()),List.of(
                "Same satellite, period and station coordinates. Observation intervals and geometric visibility have different selection/mask semantics.",
                "Adjacent gaps are signed (negative means overlap); chronological station changes are NOT executed handovers.",
                "Union blackouts are internal gaps between merged visibility intervals, excluding study-boundary gaps.",
                "Unmatched uses positive temporal overlap at the same station, not a claim of precise boundary agreement or actual radio reception.",
                "Short means duration strictly less than configured threshold. Quantiles use linear interpolation. Null fractions/quantiles mean no observations."));
    }
    private static Map<String,GroundStation> cohort(Dataset data){Map<String,GroundStation> m=new TreeMap<>();data.stations().forEach(s->m.put(s.id(),s));return m;}
    private static int unmatched(List<ContactWindow> a,List<ContactWindow> b){return (int)a.stream().filter(x->b.stream().noneMatch(y->x.groundStationId().equals(y.groundStationId())&&x.start().isBefore(y.end())&&y.start().isBefore(x.end()))).count();}
    static Distribution distribution(List<Double> values){if(values.isEmpty())return new Distribution(0,null,null,null,null,null);var v=values.stream().sorted().toList();return new Distribution(v.size(),v.getFirst(),quantile(v,.5),v.stream().mapToDouble(Double::doubleValue).average().orElseThrow(),quantile(v,.95),v.getLast());}
    private static double quantile(List<Double> sorted,double p){double index=(sorted.size()-1)*p;int lo=(int)index;int hi=Math.min(lo+1,sorted.size()-1);return sorted.get(lo)+(sorted.get(hi)-sorted.get(lo))*(index-lo);}
    public static String csv(Comparison c){return "dataset,contacts,stations,duration_mean_seconds,duration_median_seconds,adjacent_gap_mean_seconds,same_station_gap_mean_seconds,chronological_station_changes,overlapping_adjacent_pairs,short_windows,short_window_fraction\n"+row("satnogs",c.satnogs())+row("orekit",c.orekit());}
    private static String row(String name,Stats s){return String.join(",",name,""+s.contacts(),""+s.stations(),value(s.durationSeconds().mean()),value(s.durationSeconds().median()),value(s.signedAdjacentGapSeconds().mean()),value(s.sameStationGapSeconds().mean()),""+s.chronologicalStationChanges(),""+s.overlappingAdjacentPairs(),""+s.shortWindows(),value(s.shortWindowFraction()))+"\n";}
    private static String value(Double d){return d==null?"":Double.toString(d);}
}
