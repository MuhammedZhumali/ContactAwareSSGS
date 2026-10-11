package org.contactaware.contacts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.contactaware.contacts.Model.*;

class SatnogsImporterTest {
    static final Instant START=Instant.parse("2026-10-10T00:00:00Z");
    static final Interval PERIOD=new Interval(START,START.plusSeconds(3600));
    static ObjectNode observation(long id,long station,long start,long end) {
        var n=JsonFiles.JSON.createObjectNode();n.put("id",id).put("ground_station",station).put("norad_cat_id",25544).put("status","good")
                .put("start",START.plusSeconds(start).toString()).put("end",START.plusSeconds(end).toString())
                .put("station_name","test-station-"+station).put("station_lat",45).put("station_lng",10).put("station_alt",100);
        return n;
    }
    static Dataset normalize(JsonNode... rows){return SatnogsImporter.normalize(25544,PERIOD,Set.of(),Set.of("good","bad","unknown"),List.of(rows),List.of());}
    @Test void clipsSortsAndFiltersEvenIfRemoteFiltersAreIgnored(){
        var wrong=observation(1,1,0,100).put("norad_cat_id",12345);var future=observation(2,1,0,100).put("status","future");
        var result=normalize(observation(4,2,3500,3700),wrong,future,observation(3,1,-50,100),observation(5,1,3600,4000));
        assertThat(result.contacts()).extracting(ContactWindow::id).containsExactly("satnogs-3","satnogs-4");
        assertThat(result.contacts().getFirst().start()).isEqualTo(PERIOD.start());assertThat(result.contacts().getLast().end()).isEqualTo(PERIOD.end());
        assertThat(result.contacts()).allSatisfy(c->{assertThat(c.linkRateBps()).isNull();assertThat(c.expectedDataBytes()).isNull();});
    }
    @Test void mergesOnlySameStationAndRetainsSources(){
        var result=normalize(observation(1,1,0,100),observation(2,1,50,150),observation(3,2,70,90),observation(4,1,150,200));
        assertThat(result.contacts()).hasSize(2);assertThat(result.contacts().getFirst().sourceRecordIds()).containsExactly("1","2","4");assertThat(result.contacts().getFirst().durationSeconds()).isEqualTo(200);
    }
    @Test void deduplicatesIdsButRejectsConflictingVersions(){
        var n=observation(1,1,0,100);assertThat(normalize(n,n).contacts()).hasSize(1);
        assertThatThrownBy(()->normalize(n,observation(1,1,0,101))).hasMessageContaining("Conflicting");
    }
    @Test void enforcesStatusesAndStationCohort(){
        var d=SatnogsImporter.normalize(25544,PERIOD,Set.of("SATNOGS-2"),Set.of("bad"),List.of(observation(1,1,0,100),observation(2,2,100,200).put("status","bad")),List.of());
        assertThat(d.contacts()).extracting(ContactWindow::groundStationId).containsExactly("SATNOGS-2");
        assertThatThrownBy(()->SatnogsImporter.normalize(25544,PERIOD,Set.of(),Set.of("future"),List.of(),List.of())).hasMessageContaining("completed");
    }
    @Test void refusesEmptyDataAndMovingStation(){
        assertThatThrownBy(()->normalize()).hasMessageContaining("No matching");
        assertThatThrownBy(()->normalize(observation(1,1,0,100),observation(2,1,200,300).put("station_lat",46))).hasMessageContaining("coordinates changed");
    }
    @Test void rejectsInvalidGeometryIdsAndIntervals(){
        assertThatThrownBy(()->normalize(observation(1,1,100,0))).hasMessageContaining("start must precede");
        assertThatThrownBy(()->normalize(observation(1,1,0,100).put("station_lat",91))).hasMessageContaining("coordinates");
        assertThatThrownBy(()->normalize(observation(1,1,0,100).put("id",1.5))).hasMessageContaining("integer");
    }
}
