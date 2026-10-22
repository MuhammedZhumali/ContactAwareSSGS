package org.contactaware.security.simulation;

import org.contactaware.contacts.JsonFiles;
import java.nio.file.*;
import java.util.*;

/** Light read-only projection using the frozen SE2.2 JAR, no historical searches. */
public final class Se221ObservationAudit {
    public static void main(String[] args) throws Exception {
        Path old=Path.of("target/security-evaluation-se22"), out=Path.of(args[0]);
        Files.createDirectories(out);
        var summary=JsonFiles.JSON.readTree(old.resolve("summary.json").toFile());
        var rows=new ArrayList<Object>();
        com.fasterxml.jackson.databind.JsonNode reference=null;
        var names=summary.fieldNames();
        while(names.hasNext()) {
            String name=names.next(); var spec=summary.get(name);
            if(!"SEARCH_INCOMPLETE".equals(spec.path("status").asText())) continue;
            var snapshot=ExecutionCheckpointFiles.read(Path.of(spec.required("checkpoint").asText()));
            var observation=PlanningObservation.from(snapshot,2);
            var decisions=JsonFiles.JSON.readTree(old.resolve(spec.required("decisionsFile").asText()).toFile());
            var last=decisions.get(decisions.size()-1);
            if(!"INCOMPLETE".equals(last.path("searchStatus").asText()) || !snapshot.at().toString().equals(last.path("at").asText())
                || !observation.evaluationEnd().toString().equals(last.path("evaluationEnd").asText())
                || observation.visibleContacts().size()!=last.path("visibleContacts").asInt()) throw new IllegalStateException("Wrong decision root");
            var node=JsonFiles.JSON.readTree(JsonFiles.JSON.writeValueAsBytes(observation));
            if(reference==null) reference=node;
            boolean equal=reference.equals(node);
            JsonFiles.write(out.resolve(name+".observation.json"),observation);
            rows.add(Map.of("case",name,"equalToFirst",equal,"at",observation.at(),"decisionIndex",decisions.size()-1,
                "checkpoint",spec.required("checkpoint").asText(),"historicalSearches",0));
            if(!equal) throw new IllegalStateException("Observations differ: "+name);
        }
        if(rows.size()!=5) throw new IllegalStateException("Expected five failed roots");
        JsonFiles.write(out.resolve("equivalence.json"),Map.of("status","PASS","cases",rows,"fieldsCompared","all serialized PlanningObservation fields"));
        System.out.println("PASS: five complete observations equal; zero historical searches");
    }
}
