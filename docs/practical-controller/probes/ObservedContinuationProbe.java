package org.contactaware.security.simulation;

import org.contactaware.contacts.JsonFiles;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Diagnostic only: replay two saved continuations from the SAME observed root, without search. */
public final class ObservedContinuationProbe {
    public static void main(String[] args) throws Exception {
        var input=JsonFiles.JSON.readTree(Path.of(args[0]).toFile());
        var observation=ExecutionCheckpointFiles.convert(input.required("observation"),PlanningObservation.class);
        var branches=new BranchEngine();var evaluator=new ContinuationEvaluator();
        var comparator=new PlanningResultComparator(PlanTieBreak.valueOf(input.required("tieBreak").asText()));
        var results=new LinkedHashMap<String,Object>();var compared=new ArrayList<PlanningResult>();
        try(var control=SearchControl.open(Duration.ofSeconds(2),SearchDiagnostics.StopReason.TIME_LIMIT)) {
            for(var name:List.of("retained","fresh")) {
                var branch=branches.create(observation);
                if(!branch.observation().equals(observation))throw new IllegalStateException("Observation restoration mismatch");
                for(var node:input.required(name)) {
                    var action=ExecutionCheckpointFiles.convert(node,PlanningAction.class);
                    branch=branches.execute(branch,action);
                }
                if(!branch.snapshot().at().equals(observation.evaluationEnd()))throw new IllegalStateException("Nonterminal continuation");
                var evaluated=evaluator.evaluate(branch);compared.add(evaluated);
                results.put(name,Map.of("status","FEASIBLE_TERMINAL_REPLAY","score",evaluated.evaluation(),"plan",evaluated.plan()));
            }
            results.put("retainedVersusFresh",comparator.compare(compared.get(0),compared.get(1)));
            results.put("decisiveCriterion",comparator.decisiveCriterion(compared.get(0),compared.get(1)));
            results.put("at",observation.at());results.put("evaluationEnd",observation.evaluationEnd());
            results.put("scope","Two saved feasible continuations at the same root; no optimality claim or new search");
            JsonFiles.write(Path.of(args[1]),results);
        }
    }
}
