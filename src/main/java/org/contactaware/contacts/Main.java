package org.contactaware.contacts;

import com.fasterxml.jackson.databind.JsonNode;
import org.contactaware.contacts.analysis.GapAnalysisFiles;
import org.contactaware.contacts.schedule.*;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.contactaware.contacts.Model.*;
import static org.contactaware.contacts.JsonFiles.*;

/** CLI adapter. Import, geometry and statistics are usable independently of any framework. */
public final class Main {
    public static void main(String[] args) {
        try { run(args); }
        catch(Exception e){System.err.println("ERROR: " + e.getMessage());System.exit(1);}
    }
    static void run(String[] args) throws Exception {
        if(args.length==0 || args[0].equals("help") || args[0].equals("--help")){System.out.println(HELP);return;}
        var options=new Options(Arrays.copyOfRange(args,1,args.length));
        switch(args[0]) {
            case "satnogs" -> {
                int norad=options.integer("norad"); var interval=options.interval();
                var stations=options.set("stations",""); var statuses=options.set("statuses","good,bad,unknown"); int max=options.integer("max-pages",1000); Path out=options.path("out");options.done();
                if(interval.end().isAfter(Instant.now()))throw new IllegalArgumentException("Use a completed historical interval for SatNOGS observations");
                var dataset=new SatnogsImporter(new PublicHttp()).fetch(norad,interval,stations,statuses,max,newOutput(out));
                System.out.printf("SatNOGS: %d windows, %d stations, %d pages -> %s%n",dataset.contacts().size(),dataset.stations().size(),dataset.sources().size(),out);
            }
            case "satnogs-replay" -> {
                Path input=options.path("dataset"),out=options.path("out");options.done();var prior=readDataset(input);Snapshots.verify(prior,input.toAbsolutePath().getParent());
                if(!prior.kind().equals("SATNOGS_OBSERVATION_INTERVALS"))throw new IllegalArgumentException("Not a SatNOGS dataset");
                newOutput(out);List<JsonNode> rows=new ArrayList<>();List<Source> sources=new ArrayList<>();
                for(var source:prior.sources()) {
                    Path file=Snapshots.safeResolve(input.toAbsolutePath().getParent(),source.file());
                    var root=JSON.readTree(file.toFile());(root.isArray()?root:root.get("results")).forEach(rows::add);
                    sources.add(Snapshots.copyVerified(file,source,out,source.file()));
                }
                Set<String> statuses=new TreeSet<>();JSON.valueToTree(prior.parameters()).path("statuses").forEach(n->statuses.add(n.asText()));
                Set<String> stations=new TreeSet<>();prior.stations().forEach(s->stations.add(s.id()));
                saveDataset(out,SatnogsImporter.normalize(prior.noradId(),prior.interval(),stations,statuses,rows,sources));
                System.out.println("Offline SatNOGS replay -> " + out);
            }
            case "fetch-orbit" -> {
                int norad=options.integer("norad");String format=options.optional("format","json").toLowerCase(Locale.ROOT);Path out=options.path("out");options.done();
                if(norad<=0 || !Set.of("json","tle").contains(format))throw new IllegalArgumentException("Positive NORAD and format json/tle required");
                Path file=out.resolve("gp."+format),sidecar=out.resolve("gp."+format+".source.json");
                if(Files.exists(file)) {
                    var source=JSON.readValue(sidecar.toFile(),Source.class);
                    if(!source.url().equals(orbitUrl(norad,format)) || !source.sha256().equals(sha256(Files.readAllBytes(file))))throw new IllegalArgumentException("Cache identity/hash mismatch");
                    if(source.retrievedAt()==null || Duration.between(source.retrievedAt(),Instant.now()).toHours()>=2)throw new IllegalArgumentException("Snapshot already exists; use a NEW directory for a new epoch, or use this snapshot offline");
                    System.out.println("Reused cached CelesTrak snapshot: " + file);return;
                }
                newOutput(out);new PublicHttp().get(URI.create(orbitUrl(norad,format)),out,"gp."+format);System.out.println("CelesTrak snapshot -> " + file);
            }
            case "fetch-earth-data" -> {
                Path out=options.path("out");options.done();newOutput(out);var http=new PublicHttp();
                http.get(URI.create("https://gitlab.orekit.org/orekit/orekit-data/-/raw/main/tai-utc.dat"),out,"tai-utc.dat");
                http.get(URI.create("https://datacenter.iers.org/data/9/finals2000A.all"),out,"finals2000A.all");System.out.println("UTC/EOP snapshots -> " + out);
            }
            case "orekit" -> {
                int norad=options.integer("norad");var interval=options.interval();Path orbit=options.path("orbit"),stationsFile=options.path("stations"),earthData=options.path("orekit-data"),out=options.path("out");
                var settings=new OrekitContactGenerator.Settings(options.decimal("min-elevation-deg",10),options.decimal("max-check-seconds",30),options.decimal("root-tolerance-seconds",0.001),options.decimal("max-epoch-distance-hours",72));options.done();
                List<GroundStation> stations=Arrays.asList(JSON.readValue(stationsFile.toFile(),GroundStation[].class));
                newOutput(out);List<Source> sources=new ArrayList<>();
                sources.add(Snapshots.copyInput(orbit,out,"raw/orbit/"+orbit.getFileName()));
                sources.add(Snapshots.copyInput(stationsFile,out,"raw/stations.json"));
                for(String file:List.of("tai-utc.dat","finals2000A.all")) sources.add(Snapshots.copyInput(earthData.resolve(file),out,"raw/earth/"+file));
                Path stationsDataset=stationsFile.resolveSibling("dataset.json");
                if(Files.exists(stationsDataset)) {
                    var stationSource=readDataset(stationsDataset);Snapshots.verify(stationSource,stationsDataset.toAbsolutePath().getParent());
                    if(!stationSource.stations().equals(stations))throw new IllegalArgumentException("stations.json differs from its dataset provenance");
                    sources.add(Snapshots.copyInput(stationsDataset,out,"raw/station-dataset.json"));
                    for(var source:stationSource.sources())sources.add(Snapshots.copyVerified(Snapshots.safeResolve(stationsDataset.toAbsolutePath().getParent(),source.file()),source,out,"raw/station-sources/"+source.file()));
                }
                var context=OrbitInputs.context(out.resolve("raw/earth"));
                var tle=OrbitInputs.read(out.resolve("raw/orbit/"+orbit.getFileName()),norad,context.getTimeScales().getUTC());
                var dataset=new OrekitContactGenerator(context).generate(norad,interval,stations,tle,settings,sources);saveDataset(out,dataset);
                System.out.printf("Orekit: %d windows, %d stations -> %s%n",dataset.contacts().size(),dataset.stations().size(),out);
            }
            case "compare" -> {
                Path a=options.path("satnogs"),b=options.path("orekit"),out=options.path("out");double threshold=options.decimal("short-seconds",300);options.done();
                var observations=readDataset(a);var geometry=readDataset(b);Snapshots.verify(observations,a.toAbsolutePath().getParent());Snapshots.verify(geometry,b.toAbsolutePath().getParent());
                var result=DatasetComparison.compare(observations,geometry,threshold);newOutput(out);
                write(out.resolve("comparison.json"),result);Files.writeString(out.resolve("comparison.csv"),DatasetComparison.csv(result));
                write(out.resolve("input-manifest.json"),Map.of("satnogs",Map.of("path",a.toAbsolutePath().toString(),"sha256",sha256(Files.readAllBytes(a))),"orekit",Map.of("path",b.toAbsolutePath().toString(),"sha256",sha256(Files.readAllBytes(b)))));
                System.out.print(DatasetComparison.csv(result));System.out.println("Geometric windows without observation: " + result.geometricWindowsWithoutObservation());
            }
            case "schedule-select" -> {
                Path input=options.path("dataset"),out=options.path("out");
                var config=new ScheduleSelectionConfig(new BigDecimal(options.optional("switch-guard-seconds","0")));options.done();
                var result=ScheduleFiles.select(input,out,config);var m=result.metrics();
                System.out.printf(Locale.ROOT,"Schedule: %d/%d windows, %s s, %d GS transitions, %d overlapping candidate pairs -> %s%n",
                        m.selectedWindows(),m.candidateWindows(),m.totalSelectedDurationSeconds(),m.groundStationTransitions(),m.overlappingCandidatePairs(),out);
            }
            case "analyze-gaps" -> {
                Path input=options.path("dataset"),out=options.path("out");double threshold=options.decimal("short-seconds",300);options.done();
                var s=GapAnalysisFiles.analyze(input,out,threshold).statistics();
                System.out.printf(Locale.ROOT,"Gaps: %d contacts; adjacent gap s %s; %d same-GS recurrence pairs; union %s s; uncovered %s s -> %s%n",
                        s.contactCount(),s.adjacentGapSeconds(),s.sameGroundStationRecurrence().pairCount(),s.unionAvailableSeconds(),s.timeWithoutAnyContactSeconds(),out);
            }
            default -> throw new IllegalArgumentException("Unknown command " + args[0] + "; run help");
        }
    }
    private static String orbitUrl(int norad,String format){return "https://celestrak.org/NORAD/elements/gp.php?CATNR="+norad+"&FORMAT="+format.toUpperCase(Locale.ROOT);}
    static final class Options {
        private final Map<String,String> values=new HashMap<>();
        Options(String[] args){if(args.length%2!=0)throw new IllegalArgumentException("Arguments must be --name value pairs");for(int i=0;i<args.length;i+=2){if(!args[i].startsWith("--")||values.putIfAbsent(args[i].substring(2),args[i+1])!=null)throw new IllegalArgumentException("Invalid/duplicate argument "+args[i]);}}
        String required(String key){String value=values.remove(key);if(value==null||value.isBlank())throw new IllegalArgumentException("Missing --"+key);return value;}
        String optional(String key,String fallback){return values.containsKey(key)?required(key):fallback;}
        int integer(String key){return Integer.parseInt(required(key));} int integer(String key,int fallback){return Integer.parseInt(optional(key,""+fallback));}
        double decimal(String key,double fallback){return Double.parseDouble(optional(key,""+fallback));} Path path(String key){return Path.of(required(key));}
        Set<String> set(String key,String fallback){String text=optional(key,fallback);return text.isBlank()?Set.of():new TreeSet<>(Arrays.asList(text.split(",")));}
        Interval interval(){return new Interval(Instant.parse(required("start")),Instant.parse(required("end")));}
        void done(){if(!values.isEmpty())throw new IllegalArgumentException("Unknown options: "+values.keySet());}
    }
    private static final String HELP="""
            ContactAwareSS public contact datasets (Java 21)
            satnogs --norad N --start UTC --end UTC --out DIR [--stations SATNOGS-1,SATNOGS-2] [--statuses good,bad,unknown] [--max-pages 1000]
            satnogs-replay --dataset FILE --out DIR
            fetch-orbit --norad N --out DIR [--format json|tle]
            fetch-earth-data --out DIR
            orekit --norad N --start UTC --end UTC --orbit FILE --stations FILE --orekit-data DIR --out DIR
              [--min-elevation-deg 10] [--max-check-seconds 30] [--root-tolerance-seconds 0.001] [--max-epoch-distance-hours 72]
            compare --satnogs DATASET_JSON --orekit DATASET_JSON --out DIR [--short-seconds 300]
            schedule-select --dataset DATASET_JSON --out DIR [--switch-guard-seconds 0]
            analyze-gaps --dataset DATASET_OR_SCHEDULE_JSON --out DIR [--short-seconds 300]
            Use ISO-8601 UTC instants. Outputs require new/empty directories. Raw snapshots and SHA-256 provenance are retained.
            """;
}
