package org.contactaware.security.simulation;

import org.contactaware.contacts.Model.ContactWindow;
import org.contactaware.security.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.stream.*;
import static org.contactaware.security.simulation.HorizonWitness.*;
import static org.contactaware.security.simulation.SimulationEvent.Type.*;

/** Independent audit checks against the frozen SE2.1 jar. Not part of the project. */
public class SimAudit {
    static int fails = 0;
    static void check(String name, boolean ok, Object detail) {
        System.out.println((ok ? "PASS " : "FAIL ") + name + " :: " + detail);
        if (!ok) fails++;
    }
    static void info(String name, Object detail) { System.out.println("INFO " + name + " :: " + detail); }

    static Instant t(double seconds) { return EPOCH.plusNanos(Math.round(seconds * 1e9)); }
    static SimulationContact contact(String id, ContextId ctx, Instant s, Instant e) {
        return new SimulationContact(new ContactWindow(id, SATELLITE, ctx.groundStationId(), s, e, null, null, List.of()),
                SimulationContact.PayloadProfile.syntheticWitness());
    }
    static SimulationScenario scen(String name, Instant end, List<SimulationContact> contacts, List<PlannedOperation> plan,
                                   List<TrafficKeyDisclosure> d) {
        return new SimulationScenario(name, at(-2), end, initialState(), CONSTRAINTS, SecurityCostProfile.syntheticWitness(),
                contacts, plan, d);
    }
    static PlannedOperation op(String id, SecurityAction a, Instant when, ContextId ctx, KeyPairIds keys, String contact) {
        return new PlannedOperation(id, new ScheduledSecurityAction(a, when, ctx, keys, "audit"), contact);
    }
    static List<SimulationEvent> of(List<SimulationEvent> j, SimulationEvent.Type type) {
        return j.stream().filter(e -> e.type() == type).toList();
    }

    public static void main(String[] args) throws Exception {
        String only = args.length > 0 ? args[0] : "all";
        if (only.equals("all") || only.equals("lifetime")) lifetime();
        if (only.equals("all") || only.equals("boundary")) boundary();
        if (only.equals("all") || only.equals("ordering")) ordering();
        if (only.equals("all") || only.equals("snapshot")) snapshot();
        if (only.equals("all") || only.equals("observation")) observation();
        if (only.equals("all") || only.equals("policies")) policies(args.length > 1 ? args[1].split(",") : new String[]{"fixed", "percontact", "next", "multi"});
        System.out.println("FAILURES=" + fails);
    }

    // ---------- 1. Key lifetime ----------
    static void lifetime() {
        var s0 = initialState();
        var k = s0.key(OLD_A.uplink());
        check("1a initial OLD_A history from real transitions", k.createdAt().equals(at(-90)) && k.activatedAt().equals(at(-80))
                && k.hardDeadline(CONSTRAINTS).equals(at(70)) && k.lastSuccessfulContactEnd().equals(at(-60)) && k.nextNonceCounter() == 1,
                "created=" + k.createdAt() + " activated=" + k.activatedAt() + " deadline=" + k.hardDeadline(CONSTRAINTS)
                        + " lastSuccess=" + k.lastSuccessfulContactEnd() + " ctr=" + k.nextNonceCounter());
        // REUSE at 60, OPEN 1 s => frames from 61 every 0.25 s; deadline 70 lands on a frame boundary.
        var c = contact("CA", A, at(60), at(75));
        var r = new SimulationEngine().run(scen("life", at(76), List.of(c), List.of(op("reuse", SecurityAction.REUSE, at(60), A, null, "CA")), List.of()));
        var j = r.events();
        var kk = r.finalState().key(OLD_A.uplink());
        var lastFrame = of(j, PAYLOAD_TRANSFERRED).stream().map(SimulationEvent::at).max(Comparator.naturalOrder()).orElse(null);
        var expiry = of(j, KEY_EXPIRED).stream().map(SimulationEvent::at).toList();
        check("1b REUSE does not reset activation/deadline", kk.activatedAt().equals(at(-80)) && kk.createdAt().equals(at(-90))
                && kk.hardDeadline(CONSTRAINTS).equals(at(70)), "activated=" + kk.activatedAt() + " deadline=" + kk.hardDeadline(CONSTRAINTS) + " status=" + kk.status());
        int frameAt70 = -1, expireAt70 = -1;
        for (int i = 0; i < j.size(); i++) {
            if (j.get(i).type() == PAYLOAD_TRANSFERRED && j.get(i).at().equals(at(70))) frameAt70 = i;
            if (j.get(i).type() == KEY_EXPIRED && j.get(i).at().equals(at(70)) && expireAt70 < 0) expireAt70 = i;
        }
        check("1c frame [69.75,70) delivered at deadline, then expiry at exactly 70", lastFrame != null && lastFrame.equals(at(70))
                && frameAt70 >= 0 && expireAt70 > frameAt70, "lastFrame=" + lastFrame + " expiries=" + expiry);
        check("1d nonce counter progressed monotonically from history (not reset by REUSE)",
                kk.nextNonceCounter() == 1 + of(j, PAYLOAD_TRANSFERRED).stream().filter(e -> e.uplinkFrames() == 1).count(),
                "finalCtr=" + kk.nextNonceCounter() + " ulFrames=" + of(j, PAYLOAD_TRANSFERRED).stream().filter(e -> e.uplinkFrames() == 1).count());
        // Re-activation impossible.
        var tr = new LifecycleTransitions(CONSTRAINTS);
        boolean reactivationRejected;
        try { tr.activateKeys(s0, A, OLD_A, c.window(), at(61)); reactivationRejected = false; }
        catch (IllegalArgumentException e) { reactivationRejected = true; }
        check("1e re-activation of ACTIVE key rejected", reactivationRejected, "");
        // Checker boundaries.
        var open = tr.startContext(s0, A, c.window(), at(65));
        var ch = new SecurityConstraintChecker(CONSTRAINTS);
        boolean pIn = ch.canProtect(open, A, at(70).minusMillis(250), at(70), 1, 0).allowed();
        boolean pOut = ch.canProtect(open, A, at(70).minusMillis(250), at(70).plusNanos(1), 1, 0).allowed();
        boolean startBefore = ch.canReuseContext(s0, A, c.window(), at(70).minusNanos(1)).allowed();
        boolean startAt = ch.canReuseContext(s0, A, c.window(), at(70)).allowed();
        check("1f half-open lifetime: [d-0.25,d) ok, [d-0.25,d+1ns) rejected, REUSE at d-1ns ok, at d rejected",
                pIn && !pOut && startBefore && !startAt, "pIn=" + pIn + " pOut=" + pOut + " startBefore=" + startBefore + " startAt=" + startAt);
    }

    // ---------- 2. Contact boundaries / partial operations ----------
    static void boundary() {
        var c = contact("C1", A, at(0), at(20));
        var r = new SimulationEngine().run(scen("b1", at(21), List.of(c), List.of(op("reuse", SecurityAction.REUSE, at(19), A, null, "C1")), List.of()));
        var intr = of(r.events(), STEP_INTERRUPTED);
        check("2a OPEN/REUSE completing exactly at LOS is interrupted (CONTACT_END before STEP_COMPLETE)",
                intr.size() == 1 && "CONTACT_ENDED".equals(intr.getFirst().failureCode()),
                intr.stream().map(e -> e.at() + " " + e.failureCode() + " usage=" + e.usage()).toList());
        info("2a consequence: context marked uncertain for rest of run", r.uncertainContexts());
        var r2 = new SimulationEngine().run(scen("b2", at(21), List.of(c), List.of(op("reuse", SecurityAction.REUSE, at(19).minusNanos(1), A, null, "C1")), List.of()));
        check("2b REUSE completing 1 ns before LOS succeeds; no partial frame is started in remaining 1 ns",
                of(r2.events(), STEP_COMPLETED).stream().anyMatch(e -> e.action() == SecurityAction.REUSE)
                        && of(r2.events(), PAYLOAD_TRANSFERRED).isEmpty() && of(r2.events(), PAYLOAD_ABORTED).isEmpty()
                        && r2.finalState().key(OLD_A.uplink()).lastSuccessfulContactEnd().equals(at(-60)),
                "frames=" + of(r2.events(), PAYLOAD_TRANSFERRED).size() + " lastSuccess=" + r2.finalState().key(OLD_A.uplink()).lastSuccessfulContactEnd());
        // Interrupted op partial cost: interrupt at 70.0000013 ms -> ground 20 ms + 50.0000013 ms of a 100 ms/40 B uplink phase.
        var interrupted = new PlannedOperation("reuse", new ScheduledSecurityAction(SecurityAction.REUSE, at(0), A, null, "audit"), "C1",
                at(0).plusNanos(70_000_001L + 300), Set.of());
        var r3 = new SimulationEngine().run(scen("b3", at(21), List.of(c), List.of(interrupted), List.of()));
        var e3 = of(r3.events(), STEP_INTERRUPTED).getFirst();
        check("2c partial-step cost = exact ns elapsed, bytes floor(40*50.0000013/100)=20",
                e3.usage().uplinkBytes() == 20 && e3.usage().elapsed().equals(Duration.ofNanos(70_000_301L))
                        && e3.usage().groundCpu().equals(Duration.ofMillis(20)), e3.usage());
        var total = new MetricsCollector().collect(r3.finalState() == null ? List.of() : List.of(c), r3.events()).controlCosts();
        check("2d partial cost charged once in metrics", total.equals(e3.usage()), total);
    }

    // ---------- 3. Same-timestamp ordering ----------
    static void ordering() {
        var c = contact("C1", A, at(0), at(20));
        var plan = List.of(op("reuse", SecurityAction.REUSE, at(0), A, null, "C1"));
        // Disclosure exactly at a frame boundary (first frame [1,1.25) UL).
        var sc = scen("o1", at(21), List.of(c), plan, List.of(new TrafficKeyDisclosure("d", A, t(1.25), null)));
        var r = new SimulationEngine().run(sc);
        var m = new SecurityMetricsCollector().collect(sc, r.events());
        check("3a frame ending at disclosure instant is 'before' (FRAME_COMPLETE prio 0 < DISCLOSURE 5)",
                m.decryptableBeforeBytes() == 100, "before=" + m.decryptableBeforeBytes() + " after=" + m.decryptableAfterBytes());
        var sc2 = scen("o2", at(21), List.of(c), plan, List.of(new TrafficKeyDisclosure("d", A, t(1.30), null)));
        var m2 = new SecurityMetricsCollector().collect(sc2, new SimulationEngine().run(sc2).events());
        check("3b disclosure mid-frame splits bytes by floor(100*0.05/0.25)=20", m2.decryptableBeforeBytes() == 120,
                "before=" + m2.decryptableBeforeBytes() + " after=" + m2.decryptableAfterBytes() + " emitted=" + m2.emittedBytes());
        check("3b' before+after == emitted on compromised keys (all frames here use OLD_A)",
                m2.decryptableBeforeBytes() + m2.decryptableAfterBytes() == m2.emittedBytes(), m2.emittedBytes());
        // Disclosure at exactly the key deadline -> no target; 1 ns earlier -> target.
        var cl = contact("CA", A, at(60), at(75));
        var lplan = List.of(op("reuse", SecurityAction.REUSE, at(60), A, null, "CA"));
        var at70 = new SimulationEngine().run(scen("o3", at(76), List.of(cl), lplan, List.of(new TrafficKeyDisclosure("d", A, at(70), null))));
        var before70 = new SimulationEngine().run(scen("o4", at(76), List.of(cl), lplan, List.of(new TrafficKeyDisclosure("d", A, at(70).minusNanos(1), null))));
        check("3c disclosure at deadline -> NO_TARGET; at deadline-1ns -> KEY_DISCLOSED",
                of(at70.events(), DISCLOSURE_NO_TARGET).size() == 1 && of(before70.events(), KEY_DISCLOSED).size() == 1, "");
        // Notice at the same instant as deadline.
        var nr = new SimulationEngine().run(scen("o5", at(76), List.of(cl), lplan, List.of(new TrafficKeyDisclosure("d", A, at(69), at(70)))));
        info("3d notice coincident with deadline: deactivationReason / ground notice",
                nr.finalState().key(OLD_A.uplink()).deactivationReason() + " / " + of(nr.events(), REVOCATION_RECEIVED_GROUND).size());
        // Uplink frame aborted by notice: nonce check.
        var sc6 = scen("o6", at(21), List.of(c), plan, List.of(new TrafficKeyDisclosure("d", A, t(2), t(3.1))));
        var r6 = new SimulationEngine().run(sc6);
        nonceAudit("5a predefined UL abort", sc6, r6.events(), r6.finalState());
        var sc7 = scen("o7", at(21), List.of(c), plan, List.of(new TrafficKeyDisclosure("d", A, t(2), t(3.35))));
        var r7 = new SimulationEngine().run(sc7);
        nonceAudit("5b predefined DL rejected", sc7, r7.events(), r7.finalState());
        info("5b DL-rejected events", r7.events().stream().filter(e -> e.type() == PAYLOAD_ABORTED || e.type() == REVOCATION_RECEIVED_GROUND)
                .map(e -> e.type() + "@" + Duration.between(EPOCH, e.at()) + (e.frameEvidence() == null ? "" : " emitted=" + e.frameEvidence().emittedBytes() + " dir=" + e.frameEvidence().direction())).toList());
    }

    /** Every delivered frame increments the directional counter once; aborted frames only on keys retired by then and never reused. */
    static void nonceAudit(String name, SimulationScenario sc, List<SimulationEvent> journal, SecurityState fin) {
        var initial = sc.initialState();
        var delivered = new TreeMap<String, Long>();
        var aborted = new ArrayList<SimulationEvent>();
        for (var e : journal) {
            if (e.type() == PAYLOAD_TRANSFERRED) {
                String key = e.uplinkFrames() == 1 ? e.keyIds().uplink() : e.keyIds().downlink();
                delivered.merge(key, 1L, Long::sum);
            }
            if (e.type() == PAYLOAD_ABORTED) aborted.add(e);
        }
        boolean ok = true; var detail = new StringBuilder();
        for (var key : fin.keys().values()) {
            long start = initial.keys().containsKey(key.id()) ? initial.key(key.id()).nextNonceCounter() : 0;
            long expect = start + delivered.getOrDefault(key.id(), 0L);
            if (key.nextNonceCounter() != expect) { ok = false; detail.append(key.id()).append(" ctr=").append(key.nextNonceCounter()).append(" exp=").append(expect).append("; "); }
        }
        for (var a : aborted) {
            var key = fin.key(a.frameEvidence().keyId());
            boolean retired = key.deactivatedAt() != null && !key.deactivatedAt().isAfter(a.at());
            boolean laterUse = journal.stream().anyMatch(e -> e.type() == PAYLOAD_TRANSFERRED && e.frameEvidence() != null
                    && e.frameEvidence().keyId().equals(key.id()) && e.frameEvidence().from().isAfter(a.frameEvidence().from()));
            if (!retired || laterUse) { ok = false; detail.append("aborted on ").append(key.id()).append(" retired=").append(retired).append(" laterUse=").append(laterUse); }
            detail.append("aborted ").append(a.frameEvidence().direction()).append(" key=").append(key.id()).append(" status=").append(key.status())
                    .append(" reason=").append(key.deactivationReason()).append(" ctrNotAdvanced; ");
        }
        check(name + ": counters == delivered frames; aborted frame keys retired, never reused", ok, detail.length() == 0 ? "counters consistent, no aborts" : detail);
    }

    // ---------- 4. Snapshot restore ----------
    static void snapshot() throws Exception {
        var base = HorizonWitness.scenario(Sequence.X, Plan.MULTI);
        var sc = new SimulationScenario(base.name(), base.start(), base.end(), base.initialState(), base.constraints(), base.costs(),
                base.contacts(), base.plan(), List.of(new TrafficKeyDisclosure("d", A, at(10), at(12))));
        var engine = new SimulationEngine();
        var full = engine.run(sc);
        var snap = engine.start(sc);
        var rnd = new Random(7);
        Instant cur = snap.at();
        int pauses = 0;
        while (true) {
            var next = cur.plusNanos(1 + (long) (rnd.nextDouble() * 900_000_000L));
            if (!next.isBefore(sc.end())) break;
            snap = engine.advance(snap, next); cur = next; pauses++;
            // Repeated pauses at the same instant must be idempotent.
            if (pauses % 17 == 0) snap = engine.advance(snap, cur);
        }
        var resumed = engine.resume(snap);
        check("4a run() == start+" + pauses + " random pauses+resume (journal, metrics, state)",
                full.events().equals(resumed.events()) && full.metrics().equals(resumed.metrics()) && full.finalState().equals(resumed.finalState()),
                "events=" + full.events().size() + "/" + resumed.events().size());
        var m1 = new SecurityMetricsCollector().collect(sc, full.events());
        var m2 = new SecurityMetricsCollector().collect(sc, resumed.events());
        check("4b security metrics identical after pauses", m1.equals(m2), "before=" + m1.decryptableBeforeBytes() + " after=" + m1.decryptableAfterBytes());
        var recomputed = new MetricsCollector().collect(sc.contacts(), full.events());
        check("4c metrics are a pure function of the journal (no hidden carried totals)", recomputed.equals(full.metrics()), "");

        // Controlled: resume from a mid-run checkpoint (in memory and serialized) must reproduce the run.
        var fixture = SecurityEvaluationSe2Fixtures.noticeFixture("X", PolicyExperimentFixtures.witness(Sequence.X, false), 1);
        var det = SecurityEvaluationSe2Main.incidentScenario(fixture.scenario(), A, at(10), "detected");
        var saved = new ArrayList<ExecutionSnapshot>();
        var policy = SecurityEvaluationSe2Main.policy("fixed", Duration.ofSeconds(60), DecisionTimeModel.V2_EVENT_BOUNDARIES);
        var ref = new SecurityPolicyController(policy, Duration.ZERO, (s, n) -> saved.add(s)).run(det);
        int mid = saved.size() / 2;
        var chk = saved.get(mid);
        Path file = Files.createTempFile("audit-ckpt", ".json");
        ExecutionCheckpointFiles.write(file, chk);
        var restored = ExecutionCheckpointFiles.read(file);
        var a = new SecurityPolicyController(policy).run(chk);
        var b = new SecurityPolicyController(policy).run(restored);
        check("4d controlled resume from checkpoint #" + mid + " (at " + chk.at() + ") reproduces journal (in-memory and JSON round-trip)",
                ref.simulation().events().equals(a.simulation().events()) && ref.simulation().events().equals(b.simulation().events())
                        && ref.simulation().metrics().equals(b.simulation().metrics()), "status=" + ref.status() + "/" + a.status() + "/" + b.status());
        check("4e recordedCosts of final snapshot == metrics controlCosts", ref.snapshot().recordedCosts().equals(ref.simulation().metrics().controlCosts()), "");
        Files.deleteIfExists(file);
        // Planning branch root must carry exactly the observed totals (no double counting of pending/in-flight work).
        int checked = 0; boolean allEqual = true; var bad = new ArrayList<String>();
        for (var d : ref.decisions()) {
            var o = d.observation();
            if (o.ongoingOperations().isEmpty() && o.inFlightFrame() == null) continue;
            if (!o.at().isBefore(o.evaluationEnd())) continue;
            var root = new BranchEngine().create(o).snapshot();
            boolean eq = root.accruedCosts().equals(o.accruedCosts()) && root.recordedCosts().equals(o.recordedCosts())
                    && root.usefulSecureTime().equals(o.usefulSecureTime()) && root.createdKeys() == o.createdKeys();
            var eval = new ContinuationEvaluator().evaluate(o, root);
            eq &= eval.equals(new BranchEvaluation(0, Duration.ZERO, Duration.ZERO, 0, 0)) || !root.at().equals(o.at());
            if (!eq) { allEqual = false; bad.add(o.at() + " root=" + root.accruedCosts() + " obs=" + o.accruedCosts() + " eval=" + eval); }
            checked++;
        }
        check("4f planning-branch root totals == observation totals at " + checked + " mid-operation/mid-frame decisions", allEqual && checked > 0, bad);
    }

    // ---------- 7. Observation H1 vs H2 ----------
    static void observation() {
        var fixture = SecurityEvaluationSe2Fixtures.noticeFixture("X", PolicyExperimentFixtures.witness(Sequence.X, false), 1);
        var engine = new SimulationEngine();
        var snap = engine.controlledSnapshot(engine.start(SecurityEvaluationSe2Main.incidentScenario(fixture.scenario(), A, at(10), "detected")));
        for (Instant when : List.of(at(0), at(5), at(25))) {
            var s = engine.controlledAdvance(snap, when, false);
            var o1 = PlanningObservation.from(s, 1); var o2 = PlanningObservation.from(s, 2);
            boolean same = o1.securityState().equals(o2.securityState()) && Objects.equals(o1.currentContact(), o2.currentContact())
                    && o1.ongoingOperations().equals(o2.ongoingOperations()) && o1.payloadProgress().equals(o2.payloadProgress())
                    && o1.recordedCosts().equals(o2.recordedCosts()) && o1.issuedKeyIds().equals(o2.issuedKeyIds())
                    && o2.visibleContacts().containsAll(o1.visibleContacts());
            check("7a H1 vs H2 observation at " + Duration.between(EPOCH, when) + " differs only in visible contacts/evaluationEnd/wakeups", same,
                    "H1 visible=" + o1.visibleContacts().stream().map(c -> c.window().id()).toList() + " end=" + o1.evaluationEnd()
                            + "; H2 visible=" + o2.visibleContacts().stream().map(c -> c.window().id()).toList() + " end=" + o2.evaluationEnd());
            boolean noAttack = new BranchEngine().create(o2).snapshot().queuedEvents().stream()
                    .noneMatch(e -> e.kind() == ExecutionSnapshot.EventKind.DISCLOSURE || e.kind() == ExecutionSnapshot.EventKind.REVOCATION_NOTICE);
            check("7b planning branch has no disclosure/notice events at " + Duration.between(EPOCH, when), noAttack, "");
        }
        snap = engine.controlledSnapshot(engine.start(fixture.scenario()));
        // The privileged snapshot is cut at the next event; only the observation is visible.
    }

    // ---------- 6/7/8/9. Full controlled policies ----------
    static void policies(String[] names) {
        var fixture = SecurityEvaluationSe2Fixtures.noticeFixture("X", PolicyExperimentFixtures.witness(Sequence.X, false), 1);
        for (var name : names) {
            var policy = SecurityEvaluationSe2Main.policy(name, Duration.ofSeconds(60), DecisionTimeModel.V2_EVENT_BOUNDARIES);
            var res = new LinkedHashMap<String, ControlledSimulationResult>();
            for (var mode : List.of("none", "undetected", "detected")) {
                long t0 = System.nanoTime();
                res.put(mode, new SecurityPolicyController(policy).run(SecurityEvaluationSe2Main.incidentScenario(fixture.scenario(), A, at(10), mode)));
                info(name + "/" + mode, res.get(mode).status() + " decisions=" + res.get(mode).decisions().size() + " wall=" + (System.nanoTime() - t0) / 1_000_000 + "ms");
            }
            if (res.values().stream().anyMatch(r -> r.status() != ControlledSimulationResult.Status.FINISHED)) { check(name + " all finished", false, ""); continue; }
            var none = res.get("none"); var und = res.get("undetected"); var det = res.get("detected");
            check("6/7 " + name + ": undetected attack leaves every decision unchanged", sameDecisions(none.decisions(), und.decisions(), null),
                    none.decisions().size() + " vs " + und.decisions().size());
            check("6/7 " + name + ": undetected journal == none journal except KEY_DISCLOSED",
                    strip(none.simulation().events()).equals(strip(und.simulation().events())), "");
            check("6/7 " + name + ": detected run decisions before GS notice (t=12) unchanged", sameDecisions(none.decisions(), det.decisions(), at(12)), "");
            var j = det.simulation().events();
            var ground = of(j, REVOCATION_RECEIVED_GROUND).getFirst().at();
            var sc = of(j, REVOCATION_RECEIVED_SPACECRAFT).stream().map(SimulationEvent::at).findFirst().orElse(null);
            var firstAfter = det.decisions().stream().filter(d -> !d.at().isBefore(ground)).findFirst().orElseThrow();
            info("8 " + name + " response", "GS notice=" + Duration.between(EPOCH, ground) + " SC notice=" + (sc == null ? "none" : Duration.between(EPOCH, sc))
                    + " firstDecision@" + Duration.between(EPOCH, firstAfter.at()) + " source=" + firstAfter.source() + " reason=" + firstAfter.reason());
            var recovery = det.decisions().stream().filter(d -> d.reason().startsWith("Common")).map(d -> Duration.between(EPOCH, d.at()) + ":" + (d.executedAction() == null ? "NOTIFY" : d.executedAction().kind())).toList();
            info("8 " + name + " common-response decisions", recovery);
            var scen = det.snapshot().scenario();
            var m = new SecurityMetricsCollector().collect(scen, j);
            info("9 " + name + " exposure", "before=" + m.decryptableBeforeBytes() + " after=" + m.decryptableAfterBytes() + " delivered=" + m.deliveredBytes()
                    + " emitted=" + m.emittedBytes() + " compromisedDelivered=" + m.compromisedDeliveredBytes() + " afterGS=" + m.bytesAfterGroundNotice()
                    + " afterSC=" + m.bytesAfterSpacecraftNotice() + " status=" + m.recoveryStatus() + " firstIndependent=" + (m.firstIndependentFrameAt() == null ? null : Duration.between(EPOCH, m.firstIndependentFrameAt())));
            // independent recount
            var keys = m.disclosedKeys();
            long emittedC = 0, deliveredC = 0, afterGsTransferred = 0;
            for (var e : j) {
                if (e.frameEvidence() == null) continue;
                var f = e.frameEvidence();
                if (!f.keyId().equals(keys.uplink()) && !f.keyId().equals(keys.downlink())) continue;
                emittedC += f.emittedBytes();
                if (f.delivered()) deliveredC += f.frameBytes();
                if (e.type() == PAYLOAD_TRANSFERRED && !f.from().isBefore(ground)) afterGsTransferred++;
            }
            check("9 " + name + ": before+after == recount of emitted on disclosed keys; delivered recount; no new compromised frame started after GS notice",
                    emittedC == m.decryptableBeforeBytes() + m.decryptableAfterBytes() && deliveredC == m.compromisedDeliveredBytes() && afterGsTransferred == 0
                            && (sc == null || m.bytesAfterSpacecraftNotice() == 0),
                    "emittedRecount=" + emittedC + " deliveredRecount=" + deliveredC + " newAfterGS=" + afterGsTransferred);
            nonceAudit("5 " + name + " detected", scen, j, det.simulation().finalState());
            boolean noOnlineCut = j.stream().noneMatch(e -> (e.type() == STEP_INTERRUPTED || e.type() == STEP_FAILED)
                    && !"KNOWN_COMPROMISE".equals(e.failureCode()));
            check("2e " + name + ": no policy-selected step cut by LOS/deadline/ACK in detected run", noOnlineCut,
                    j.stream().filter(e -> e.type() == STEP_INTERRUPTED || e.type() == STEP_FAILED).map(e -> e.action() + ":" + e.failureCode()).toList());
        }
    }

    static List<String> strip(List<SimulationEvent> j) {
        return j.stream().filter(e -> e.type() != KEY_DISCLOSED && e.type() != DISCLOSURE_NO_TARGET)
                .map(e -> e.at() + "|" + e.type() + "|" + e.operationId() + "|" + e.action() + "|" + e.keyIds() + "|" + e.usage() + "|" + e.uplinkPayloadBytes() + "|" + e.downlinkPayloadBytes()
                        )
                .toList();
    }

    static boolean sameDecisions(List<ControlledSimulationResult.Decision> a, List<ControlledSimulationResult.Decision> b, Instant before) {
        var x = a.stream().filter(d -> before == null || d.at().isBefore(before)).map(SimAudit::key).toList();
        var y = b.stream().filter(d -> before == null || d.at().isBefore(before)).map(SimAudit::key).toList();
        if (!x.equals(y)) {
            for (int i = 0; i < Math.min(x.size(), y.size()); i++) if (!x.get(i).equals(y.get(i))) { System.out.println("   first diff #" + i + ": " + x.get(i) + " VS " + y.get(i)); break; }
        }
        return x.equals(y);
    }
    static String key(ControlledSimulationResult.Decision d) {
        return d.at() + "|" + d.executedAction() + "|" + d.resumedAt() + "|" + d.source() + "|" + d.observation().wakeUps() + "|"
                + (d.search() == null ? "" : d.search().bestPlan() + "|" + d.search().score());
    }
}

