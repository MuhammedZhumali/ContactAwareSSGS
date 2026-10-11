package org.contactaware.contacts;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.contactaware.contacts.Model.*;
import static org.contactaware.contacts.SatnogsImporterTest.*;
import static org.contactaware.contacts.SatnogsImporterTest.PERIOD;

class DatasetComparisonTest {
    @TempDir Path temp;
    static ContactWindow c(String id,String gs,int start,int end){return new ContactWindow(id,"NORAD-25544",gs,START.plusSeconds(start),START.plusSeconds(end),null,null,List.of());}
    static final List<GroundStation> STATIONS=List.of(new GroundStation("A","A",0,0,0,"test"),new GroundStation("B","B",10,10,0,"test"));
    static Dataset ds(String kind,List<ContactWindow> contacts){return new Dataset("1.0",kind,25544,PERIOD,STATIONS,contacts,List.of(),Map.of(),List.of());}
    @Test void handlesOverlappingAlternativesWithoutInventingBlackouts(){
        var s=DatasetComparison.stats(ds("x",List.of(c("1","A",0,100),c("2","B",10,20),c("3","B",30,40),c("4","A",120,150))),30);
        assertThat(s.signedAdjacentGapSeconds().mean()).isEqualTo(0);assertThat(s.overlappingAdjacentPairs()).isEqualTo(1);assertThat(s.unionBlackoutGapSeconds().mean()).isEqualTo(20);
        assertThat(s.unionVisibleSeconds()).isEqualTo(130);assertThat(s.shortWindows()).isEqualTo(2);assertThat(s.shortWindowFraction()).isEqualTo(.5);assertThat(s.chronologicalStationChanges()).isEqualTo(2);
        assertThat(s.sameStationGapSeconds().mean()).isEqualTo(15);
    }
    @Test void emptyAndSingletonStatsHaveNoFabricatedRatios(){
        assertThat(DatasetComparison.stats(ds("x",List.of()),300).shortWindowFraction()).isNull();assertThat(DatasetComparison.stats(ds("x",List.of(c("a","A",1,10))),300).chronologicalStationChangeFraction()).isNull();
    }
    @Test void comparesOnlySameCohortAndCountsUnobservedWindows(){
        var a=ds("SATNOGS_OBSERVATION_INTERVALS",List.of(c("a","A",10,20)));var b=ds("OREKIT_GEOMETRIC_WINDOWS",List.of(c("b","A",0,30),c("c","B",100,200)));
        assertThat(DatasetComparison.compare(a,b,30).geometricWindowsWithoutObservation()).isEqualTo(1);
        var wrong=new Dataset("1.0",b.kind(),25544,PERIOD,List.of(STATIONS.getFirst()),List.of(c("b","A",0,30)),List.of(),Map.of(),List.of());
        assertThatThrownBy(()->DatasetComparison.compare(a,wrong,30)).hasMessageContaining("identical station");
    }
    @Test void validatesHashAndPreventsSnapshotTraversal()throws Exception{
        var file=temp.resolve("raw.json");Files.writeString(file,"original");var source=new Source("test",null,"raw.json",JsonFiles.sha256(Files.readAllBytes(file)),Map.of());Files.writeString(file,"altered");
        assertThatThrownBy(()->Snapshots.copyVerified(file,source,temp,"copy.json")).hasMessageContaining("checksum");assertThatThrownBy(()->Snapshots.safeResolve(temp,"../escape")).hasMessageContaining("escapes");
    }
    @Test void rejectsSilentCommandLineTypos(){assertThatThrownBy(()->Main.run(new String[]{"compare","--satnogs","x","--orekit","y","--out","z","--short-second","3"})).hasMessageContaining("Unknown options");}
}
