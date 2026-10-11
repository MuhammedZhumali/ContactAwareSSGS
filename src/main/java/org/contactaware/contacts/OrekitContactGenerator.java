package org.contactaware.contacts;

import org.hipparchus.ode.events.Action;
import org.orekit.bodies.*;
import org.orekit.data.LazyLoadedDataContext;
import org.orekit.frames.*;
import org.orekit.propagation.analytical.tle.*;
import org.orekit.propagation.events.ElevationExtremumDetector;
import org.orekit.time.*;
import org.orekit.utils.*;
import java.time.Instant;
import java.util.*;
import static org.contactaware.contacts.Model.*;

public final class OrekitContactGenerator {
    public record Settings(double minElevationDeg,double maxCheckSeconds,double rootToleranceSeconds,double maxEpochDistanceHours) {
        public Settings {
            if(!Double.isFinite(minElevationDeg)||minElevationDeg<0||minElevationDeg>=90 || !Double.isFinite(maxCheckSeconds)||maxCheckSeconds<=0||maxCheckSeconds>60 || !Double.isFinite(rootToleranceSeconds)||rootToleranceSeconds<=0||rootToleranceSeconds>=maxCheckSeconds || !Double.isFinite(maxEpochDistanceHours)||maxEpochDistanceHours<=0)
                throw new IllegalArgumentException("Invalid geometry settings: elevation [0,90), check (0,60]s, tolerance (0,check), positive epoch distance");
        }
    }
    private final LazyLoadedDataContext context;
    public OrekitContactGenerator(LazyLoadedDataContext context) { this.context=context; }
    public Dataset generate(int norad,Interval interval,List<GroundStation> stations,TLE tle,Settings settings,List<Source> sources) {
        if(stations.isEmpty()) throw new IllegalArgumentException("At least one station required");
        if(tle.getSatelliteNumber()!=norad) throw new IllegalArgumentException("NORAD mismatch");
        var utc=context.getTimeScales().getUTC(); var start=date(interval.start(),utc); var end=date(interval.end(),utc);
        double age=Math.max(Math.abs(start.durationFrom(tle.getDate())),Math.abs(end.durationFrom(tle.getDate())))/3600;
        if(age>settings.maxEpochDistanceHours()) throw new IllegalArgumentException("Epoch distance %.2fh exceeds %.2fh; obtain orbital elements near the study period".formatted(age,settings.maxEpochDistanceHours()));
        var eop=context.getFrames().getEOPHistory(IERSConventions.IERS_2010,true);
        if(eop.getEntries().isEmpty() || start.isBefore(eop.getStartDate()) || end.isAfter(eop.getEndDate())) throw new IllegalArgumentException("IERS EOP does not cover the study interval; zero-EOP extrapolation is forbidden");
        var earth=new OneAxisEllipsoid(Constants.WGS84_EARTH_EQUATORIAL_RADIUS,Constants.WGS84_EARTH_FLATTENING,context.getFrames().getITRF(IERSConventions.IERS_2010,true));
        List<ContactWindow> contacts=new ArrayList<>();
        for(var gs:stations.stream().sorted(Comparator.comparing(GroundStation::id)).toList()) {
            var topo=new TopocentricFrame(earth,new GeodeticPoint(Math.toRadians(gs.latitudeDeg()),Math.toRadians(gs.longitudeDeg()),gs.altitudeMeters()),gs.id());
            contacts.addAll(windows(norad,interval,tle,topo,settings));
        }
        Map<String,Object> params=new LinkedHashMap<>(); params.put("orekitVersion","13.1.9"); params.put("settings",settings);
        params.put("orbitEpochUtc",tle.getDate().toString(utc)); params.put("maxEpochDistanceHoursActual",age);
        params.put("eopStartUtc",eop.getStartDate().toString(utc)); params.put("eopEndUtc",eop.getEndDate().toString(utc));
        params.put("propagation","Orekit TLEPropagator SGP4/SDP4, TEME -> ITRF IERS 2010 simple EOP; WGS84");
        params.put("eventMethod","Elevation extrema plus regular brackets; bisection of elevation-mask crossings; clip to interval");
        return new Dataset("1.0","OREKIT_GEOMETRIC_WINDOWS",norad,interval,stations,contacts,sources,params,List.of(
                "Geometric opportunities above the configured constant elevation mask, not guaranteed usable or scheduled radio links.",
                "All supplied stations scanned; no observation-presence filter, antenna scheduling, RF link budget, weather, obstruction, refraction or authorization model.",
                "Single public GP element set over a bounded epoch distance; accuracy is not a precision ephemeris guarantee. EOP may include predictions.",
                "Numerical event detection is resolution-limited: extrema guard narrow grazing passes; repeat with a finer check interval to assess convergence.",
                "WGS84 geodetic coordinates assumed; SatNOGS station-altitude datum may not be documented. Cross-station overlaps remain alternatives."));
    }
    List<ContactWindow> windows(int norad,Interval interval,TLE tle,TopocentricFrame topo,Settings cfg) {
        var utc=context.getTimeScales().getUTC(); var start=date(interval.start(),utc); var end=date(interval.end(),utc);
        var propagator=TLEPropagator.selectExtrapolator(tle,context.getFrames().getTEME());
        double span=end.durationFrom(start); TreeSet<Double> samples=new TreeSet<>(); samples.add(0.0); samples.add(span);
        // An extremum inside a coarse bracket reveals a grazing pass even if both endpoints are below the mask.
        var extrema=new ElevationExtremumDetector(cfg.maxCheckSeconds(),cfg.rootToleranceSeconds(),topo)
                .withHandler((state,detector,increasing)-> { double t=state.getDate().durationFrom(start); if(t>0&&t<span)samples.add(t); return Action.CONTINUE; });
        propagator.addEventDetector(extrema); propagator.propagate(start,end); propagator.clearEventsDetectors();
        for(double t=cfg.maxCheckSeconds();t<span;t+=cfg.maxCheckSeconds())samples.add(t);
        java.util.function.DoubleUnaryOperator g=t->{var state=propagator.propagate(start.shiftedBy(t)); return topo.getElevation(state.getPosition(),state.getFrame(),state.getDate())-Math.toRadians(cfg.minElevationDeg());};
        List<double[]> intervals=new ArrayList<>(); Double open=g.applyAsDouble(0)>=0?0.0:null;
        double previous=0,prevG=g.applyAsDouble(0);
        for(double t:samples.tailSet(0.0,false)) {
            double currentG=g.applyAsDouble(t);
            if((prevG<0 && currentG>=0)||(prevG>=0 && currentG<0)) {
                double crossing=root(g,previous,t,cfg.rootToleranceSeconds());
                if(currentG>=0) open=crossing;
                else if(open!=null) { if(crossing>open)intervals.add(new double[]{open,crossing}); open=null; }
            }
            previous=t; prevG=currentG;
        }
        if(open!=null&&open<span) intervals.add(new double[]{open,span});
        List<ContactWindow> result=new ArrayList<>(); int count=0;
        for(var bounds:intervals) result.add(new ContactWindow("orekit-"+norad+"-"+topo.getName()+"-"+(++count),"NORAD-"+norad,topo.getName(),interval.start().plusNanos(Math.round(bounds[0]*1e9)),interval.start().plusNanos(Math.round(bounds[1]*1e9)),null,null,List.of()));
        return result;
    }
    static double root(java.util.function.DoubleUnaryOperator g,double a,double b,double tolerance) {
        double ga=g.applyAsDouble(a),gb=g.applyAsDouble(b); if(ga==0)return a; if(gb==0)return b;
        if(Math.signum(ga)==Math.signum(gb)) throw new IllegalArgumentException("Root is not bracketed");
        for(int i=0;b-a>tolerance && i<100;i++) { double m=(a+b)/2,gm=g.applyAsDouble(m); if(gm==0)return m; if(Math.signum(gm)==Math.signum(ga)){a=m;ga=gm;}else b=m; }
        return (a+b)/2;
    }
    static AbsoluteDate date(Instant i,TimeScale utc) { return new AbsoluteDate(i.toString(),utc); }
}
