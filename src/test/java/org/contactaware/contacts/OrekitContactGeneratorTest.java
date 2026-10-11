package org.contactaware.contacts;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.orekit.data.LazyLoadedDataContext;
import org.orekit.bodies.*;
import org.orekit.frames.*;
import org.orekit.propagation.analytical.tle.*;
import org.orekit.propagation.events.ElevationExtremumDetector;
import org.orekit.time.*;
import org.orekit.utils.*;
import org.hipparchus.ode.events.Action;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.contactaware.contacts.Model.*;

class OrekitContactGeneratorTest {
    static final Path EARTH=Path.of("data/sources/earth-20261011"),ORBIT=Path.of("data/sources/celestrak-iss-20261011/gp.json");
    static final GroundStation STATION=new GroundStation("SATNOGS-2987","VU2JEK_Nitin",13.1458,77.5417,900,"SatNOGS published observation snapshot");
    static final Interval DAY=new Interval(Instant.parse("2026-10-10T00:00:00Z"),Instant.parse("2026-10-11T00:00:00Z"));
    static LazyLoadedDataContext context;static TLE tle;static TopocentricFrame topo;
    @TempDir Path temp;
    @BeforeAll static void setup()throws Exception{
        context=OrbitInputs.context(EARTH);tle=OrbitInputs.read(ORBIT,25544,context.getTimeScales().getUTC());
        var earth=new OneAxisEllipsoid(Constants.WGS84_EARTH_EQUATORIAL_RADIUS,Constants.WGS84_EARTH_FLATTENING,context.getFrames().getITRF(IERSConventions.IERS_2010,true));
        topo=new TopocentricFrame(earth,new GeodeticPoint(Math.toRadians(STATION.latitudeDeg()),Math.toRadians(STATION.longitudeDeg()),STATION.altitudeMeters()),STATION.id());
    }
    static Dataset generate(Interval interval,double mask,double check){return new OrekitContactGenerator(context).generate(25544,interval,List.of(STATION),tle,new OrekitContactGenerator.Settings(mask,check,.0001,72),List.of());}
    static double elevation(Instant date){var s=TLEPropagator.selectExtrapolator(tle,context.getFrames().getTEME()).propagate(new AbsoluteDate(date.toString(),context.getTimeScales().getUTC()));return Math.toDegrees(topo.getElevation(s.getPosition(),s.getFrame(),s.getDate()));}
    @Test void boundariesMatchElevationAndDenseSamplingFindsNoMissedNormalPasses(){
        var ds=generate(DAY,10,30);assertThat(ds.contacts()).isNotEmpty();
        for(var c:ds.contacts()) {
            assertThat(elevation(c.start())).isCloseTo(10,within(.001));assertThat(elevation(c.end())).isCloseTo(10,within(.001));
            assertThat(elevation(c.start().minusSeconds(1))).isLessThan(10);assertThat(elevation(c.end().plusSeconds(1))).isLessThan(10);
            assertThat(elevation(c.start().plusNanos(Duration.between(c.start(),c.end()).toNanos()/2))).isGreaterThan(10);
        }
        for(Instant t=DAY.start().plusSeconds(7);t.isBefore(DAY.end());t=t.plusSeconds(20)) {
            Instant sample=t;boolean inWindow=ds.contacts().stream().anyMatch(c->!sample.isBefore(c.start())&&sample.isBefore(c.end()));
            assertThat(inWindow).as("visibility at %s",sample).isEqualTo(elevation(sample)>=10);
        }
    }
    @Test void survivesFinerStepAndRepeatsDeterministically(){
        var coarse=generate(DAY,10,30).contacts();var fine=generate(DAY,10,7).contacts();assertThat(fine).hasSameSizeAs(coarse);
        for(int i=0;i<coarse.size();i++){assertThat(Math.abs(seconds(coarse.get(i).start(),fine.get(i).start()))).isLessThan(.001);assertThat(Math.abs(seconds(coarse.get(i).end(),fine.get(i).end()))).isLessThan(.001);}
        assertThat(generate(DAY,10,30).contacts()).isEqualTo(coarse);
    }
    @Test void clipsPassAlreadyActiveAtBothStudyBoundaries(){
        var c=generate(DAY,10,30).contacts().getFirst();var clipped=new Interval(c.start().plusSeconds(20),c.end().minusSeconds(20));var result=generate(clipped,10,30).contacts();
        assertThat(result).hasSize(1);assertThat(result.getFirst().start()).isEqualTo(clipped.start());assertThat(result.getFirst().end()).isEqualTo(clipped.end());
    }
    @Test void detectsGrazingPassShorterThanCheckingStep(){
        var first=generate(DAY,10,30).contacts().getFirst();var interval=new Interval(first.start().minusSeconds(100),first.end().plusSeconds(100));
        var p=TLEPropagator.selectExtrapolator(tle,context.getFrames().getTEME());List<Double> maxima=new ArrayList<>();
        p.addEventDetector(new ElevationExtremumDetector(10,.000001,topo).withHandler((s,d,increasing)->{if(!increasing)maxima.add(Math.toDegrees(topo.getElevation(s.getPosition(),s.getFrame(),s.getDate())));return Action.CONTINUE;}));
        p.propagate(new AbsoluteDate(interval.start().toString(),context.getTimeScales().getUTC()),new AbsoluteDate(interval.end().toString(),context.getTimeScales().getUTC()));
        assertThat(maxima).hasSize(1);var narrow=generate(interval,maxima.getFirst()-.00001,30).contacts();
        assertThat(narrow).hasSize(1);assertThat(narrow.getFirst().durationSeconds()).isBetween(0.001,5.0);
    }
    @Test void rejectsDistantEpochAndMissingEop()throws Exception{
        assertThatThrownBy(()->generate(new Interval(DAY.start().minusSeconds(864000),DAY.end()),10,30)).hasMessageContaining("Epoch distance");
        Files.copy(EARTH.resolve("tai-utc.dat"),temp.resolve("tai-utc.dat"));
        assertThatThrownBy(()->new OrekitContactGenerator(OrbitInputs.context(temp)).generate(25544,DAY,List.of(STATION),tle,new OrekitContactGenerator.Settings(10,30,.001,72),List.of())).hasMessageContaining("EOP");
    }
    @Test void gpJsonAndPublishedTleHaveEquivalentUnitsAndPropagation()throws Exception{
        // Same public SatNOGS observation TLE, independently expressed as CelesTrak GP JSON fields.
        String line1="1 25544U 98067A   26282.95044904  .00006711  00000-0  13074-3 0  9998";
        String line2="2 25544  51.6315  92.1575 0006672 244.0714 115.9587 15.48795780589546";
        var utc=context.getTimeScales().getUTC();assertThat(TLE.isFormatOK(line1,line2)).isTrue();var published=new TLE(line1,line2,utc);
        var json=JsonFiles.JSON.createObjectNode();json.put("NORAD_CAT_ID",25544).put("OBJECT_ID","1998-067A").put("EPOCH",published.getDate().toString(utc))
                .put("MEAN_MOTION",15.48795780).put("MEAN_MOTION_DOT",.00006711).put("MEAN_MOTION_DDOT",0).put("BSTAR",.00013074)
                .put("INCLINATION",51.6315).put("RA_OF_ASC_NODE",92.1575).put("ECCENTRICITY",.0006672).put("ARG_OF_PERICENTER",244.0714).put("MEAN_ANOMALY",115.9587)
                .put("EPHEMERIS_TYPE",0).put("ELEMENT_SET_NO",999).put("REV_AT_EPOCH",58954);
        var parsed=OrbitInputs.fromGpJson(json,utc);assertThat(parsed.getMeanMotionFirstDerivative()).isCloseTo(published.getMeanMotionFirstDerivative(),within(1e-20));
        for(int seconds:List.of(0,3600,86400)){
            var when=published.getDate().shiftedBy(seconds);var frame=context.getFrames().getTEME();
            var a=TLEPropagator.selectExtrapolator(parsed,frame).propagate(when).getPosition();var b=TLEPropagator.selectExtrapolator(published,frame).propagate(when).getPosition();assertThat(a.distance(b)).isLessThan(.001);
        }
        Path file=temp.resolve("iss.tle");Files.writeString(file,"ISS\n"+line1+"\n"+line2+"\n");assertThat(OrbitInputs.read(file,25544,utc).getDate()).isEqualTo(published.getDate());
        json.put("NORAD_CAT_ID",123456);assertThat(OrbitInputs.fromGpJson(json,utc).getSatelliteNumber()).isEqualTo(123456);
        json.put("MEAN_MOTION_DDOT",.00000314);assertThat(OrbitInputs.fromGpJson(json,utc).getMeanMotionSecondDerivative()).isCloseTo(.00000314*12*Math.PI/Math.pow(86400,3),within(1e-30));
    }
    @Test void rejectsWrongTheoryAndCatalog()throws Exception{
        assertThatThrownBy(()->OrbitInputs.read(ORBIT,1,context.getTimeScales().getUTC())).hasMessageContaining("matching");
        var n=(com.fasterxml.jackson.databind.node.ObjectNode)JsonFiles.JSON.readTree(ORBIT.toFile()).get(0);n.put("REF_FRAME","EME2000");assertThatThrownBy(()->OrbitInputs.fromGpJson(n,context.getTimeScales().getUTC())).hasMessageContaining("REF_FRAME");
    }
    @Test void ignoresDatedIersPlaceholderRowsWithoutEopValues(){
        var eop=context.getFrames().getEOPHistory(IERSConventions.IERS_2010,true);
        assertThat(eop.getEndDate().toString(context.getTimeScales().getUTC())).startsWith("2027-10-04");
    }
}
