package org.contactaware.security.simulation;

import org.contactaware.contacts.JsonFiles;
import org.contactaware.security.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Source-derived synthetic family: initial deadlines are produced by actual lifecycle history. */
public final class AituFixtureMain {
    private static SecurityState history(LifecycleTransitions t, SecurityState s, ContextId c, KeyPairIds k,
                                         long creation,long activation,long end) {
        var window=HorizonWitness.window("initial-"+c.groundStationId(),c,activation,end);
        s=t.prepareKeys(s,c,k,HorizonWitness.at(creation));
        s=t.confirmInstallation(s,c,k,window,HorizonWitness.at(activation));
        s=t.activateKeys(s,c,k,window,HorizonWitness.at(activation));
        s=t.bindKeys(s,c,k,window,HorizonWitness.at(activation));
        s=t.startContext(s,c,window,HorizonWitness.at(activation));
        s=t.recordProtectedTraffic(s,c,HorizonWitness.at(activation+1),HorizonWitness.at(end),1,1);
        return t.stopContext(s,c,HorizonWitness.at(end));
    }
    public static void main(String[] args) throws Exception {
        var base=ExecutionCheckpointFiles.convert(JsonFiles.JSON.readTree(Path.of(args[0]).toFile()).required("scenario"),SimulationScenario.class);
        var out=Path.of(args[1]);Files.createDirectories(out);
        long[][] configs={{80,2,150},{80,9,150},{60,5,150},{180,5,150},{80,5,180},{80,5,90},{180,5,240},{430,5,150}};
        for(int i=0;i<configs.length;i++) {
            long[] c=configs[i];String name="X"+String.format("%02d",i+2);
            var constraints=new SecurityConstraints(Duration.ofSeconds(c[2]),base.constraints().maxMaterialAge(),
                    base.constraints().maxReuseGap(),base.constraints().authorizedContexts(),base.constraints().blockedKeyIds());
            var t=new LifecycleTransitions(constraints);
            var s=t.createContext(SecurityState.empty(HorizonWitness.SATELLITE,HorizonWitness.at(-90)),HorizonWitness.A,"A-SA-up","A-SA-down",HorizonWitness.at(-90));
            s=t.createContext(s,HorizonWitness.B,"B-SA-up","B-SA-down",HorizonWitness.at(-90));
            s=history(t,s,HorizonWitness.A,HorizonWitness.OLD_A,-90,-80,-60);
            s=history(t,s,HorizonWitness.B,HorizonWitness.OLD_B,-40,-30,-20);
            var contacts=new ArrayList<>(base.contacts());
            contacts.set(2,new SimulationContact(HorizonWitness.window("C3",HorizonWitness.A,c[0],c[0]+c[1]),base.contacts().get(2).payload()));
            var scenario=new SimulationScenario(base.name(),base.start(),HorizonWitness.at(c[0]+c[1]),s,constraints,base.costs(),contacts,List.of());
            var file=out.resolve(name+".json");if(Files.exists(file))throw new IllegalArgumentException("Existing model");
            JsonFiles.write(file,Map.of("scenario",scenario,"derivation",Map.of("id",name,"returnSeconds",c[0],"lengthSeconds",c[1],"cryptoperiodSeconds",c[2],"initialization","LifecycleTransitions historical replay")));
            var deadlines=new TreeMap<String,Object>();
            for(var key:s.keys().values())deadlines.put(key.id(),Map.of("createdAt",key.createdAt(),"activatedAt",key.activatedAt(),"committedDeadline",key.notAfter(),"effectiveDeadline",key.hardDeadline(constraints)));
            JsonFiles.write(out.resolve(name+"-deadlines.json"),deadlines);
        }
    }
}
