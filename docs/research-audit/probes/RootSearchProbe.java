package org.contactaware.security.simulation;
import org.contactaware.contacts.JsonFiles;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
/** Same source compiled against the frozen baseline; run unchanged with either JAR. */
public final class RootSearchProbe {
    public static void main(String[] args) throws Exception {
        var checkpoint=ExecutionCheckpointFiles.read(Path.of(args[0]));
        var o=PlanningObservation.from(checkpoint,2);
        var result=new ExactHorizonPlanner(new SearchLimits(20000,Duration.ofSeconds(30)),PlannerOptions.acceleratedServiceFirst(),
                DecisionTimeModel.V2_EVENT_BOUNDARIES,PlanTieBreak.valueOf(args[1])).plan(o);
        var r=Runtime.getRuntime();
        JsonFiles.write(Path.of(args[2]),Map.of("observation",o,"search",result,"heapUsedBytes",r.totalMemory()-r.freeMemory(),
                "meaning","20k-node diagnostic, not an optimality claim for incomplete search"));
    }
}
