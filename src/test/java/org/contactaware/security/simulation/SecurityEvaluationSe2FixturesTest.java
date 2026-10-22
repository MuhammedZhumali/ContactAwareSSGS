package org.contactaware.security.simulation;

import org.junit.jupiter.api.Test;
import org.contactaware.security.SecurityAction;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.contactaware.security.simulation.HorizonWitness.*;

class SecurityEvaluationSe2FixturesTest {
    @Test void witnessGridIsScheduleBasedDeduplicatedAndRetainsOriginalAlias() {
        var f = PolicyExperimentFixtures.witness(Sequence.X, false);
        var c = new SecurityEvaluationSe2Fixtures.Configuration("X", "attack-time", "WITNESS", f,
                Duration.ofSeconds(60), DecisionTimeModel.V2_EVENT_BOUNDARIES, 1, A, at(10), true);
        var grid = SecurityEvaluationSe2Fixtures.attackPoints(c);
        assertEquals(List.of(at(2), at(6), at(10), at(14), at(18), at(25)), grid.stream().map(SecurityEvaluationSe2Fixtures.AttackPoint::at).toList());
        assertEquals("original", grid.get(2).id());
        assertEquals(List.of("original", "p50"), grid.get(2).sources());
        // Changing the hidden return must not change attack selection in C1 or the next gap.
        var changed = new SecurityEvaluationSe2Fixtures.Configuration("variant", c.block(), c.workload(),
                SecurityEvaluationSe2Fixtures.changedWitness(100, 12), c.fixedPeriod(), c.timingModel(), 1, A, at(10), true);
        assertEquals(grid, SecurityEvaluationSe2Fixtures.attackPoints(changed));
    }

    @Test void changingContactGeometryPreservesMaterialHistoryAndDeadlineExactly() {
        var original = PolicyExperimentFixtures.witness(Sequence.X, false).scenario();
        for (var geometry : List.of(new long[]{80,2},new long[]{80,12},new long[]{60,5},new long[]{100,5},new long[]{100,12})) {
            var changed = SecurityEvaluationSe2Fixtures.changedWitness(geometry[0], geometry[1]).scenario();
            assertEquals(original.initialState(), changed.initialState());
            assertEquals(original.constraints(), changed.constraints());
            assertEquals(original.contacts().subList(0,2), changed.contacts().subList(0,2));
            assertEquals(original.contacts().get(2).payload(), changed.contacts().get(2).payload());
            assertEquals(at(geometry[0]), changed.contacts().get(2).window().start());
            assertEquals(at(geometry[0]+geometry[1]), changed.end());
        }
    }

    @Test void noticeDurationVariantChangesEveryPhaseTimeButNeverBytesOrOtherOperations() {
        var f = PolicyExperimentFixtures.witness(Sequence.X, false);
        var base = SecurityEvaluationSe2Fixtures.noticeFixture("base", f, 1).scenario();
        var changed = SecurityEvaluationSe2Fixtures.noticeFixture("changed", f, 2).scenario();
        for (var entry : base.costs().operations().entrySet()) {
            var other = changed.costs().operations().get(entry.getKey());
            if (entry.getKey() != SecurityAction.NOTIFY_REVOCATION) assertEquals(entry.getValue(), other);
            else {
                assertEquals(Duration.ofMillis(800), other.duration());
                for (int i=0; i<entry.getValue().phases().size();i++) {
                    var a = entry.getValue().phases().get(i); var b = other.phases().get(i);
                    assertEquals(a.resource(), b.resource()); assertEquals(a.bytes(), b.bytes());
                    assertEquals(a.duration().multipliedBy(2), b.duration());
                }
            }
        }
        assertEquals(base.initialState(), changed.initialState());
        assertEquals(base.contacts(), changed.contacts());
    }

    @Test void fullRegistrationHasFixedBlocksAndOriginalIssGeometry() throws Exception {
        var all = SecurityEvaluationSe2Fixtures.configurations(PolicyExperimentFixtures.DEFAULT_SCHEDULE);
        assertEquals(26, all.size());
        assertEquals(488, all.stream().mapToInt(c -> 4 + 8 * SecurityEvaluationSe2Fixtures.attackPoints(c).size()).sum());
        assertEquals(48, all.stream().mapToInt(c -> SecurityEvaluationSe2Fixtures.attackPoints(c).size()).sum());
        assertEquals(4, all.stream().filter(c -> c.fullAttackGrid()).count());
        var windows = org.contactaware.contacts.schedule.ScheduleFiles.readVerified(PolicyExperimentFixtures.DEFAULT_SCHEDULE).schedule().contacts();
        for (var c : all) {
            assertTrue(c.fixture().scenario().disclosures().isEmpty());
            assertTrue(c.fixture().scenario().plan().isEmpty());
            if (!c.workload().equals("WITNESS")) {
                int from = Integer.parseInt(c.fixture().input().get("fragmentStartIndex"));
                assertEquals(windows.subList(from, from+3), c.fixture().scenario().contacts().stream().map(SimulationContact::window).toList());
                assertTrue(c.fixture().scenario().initialState().keys().isEmpty());
            }
        }
    }
}
