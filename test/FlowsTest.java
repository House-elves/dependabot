import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Where each bump is, and what the row offers. */
class FlowsTest {

    static Flows.Flow flow(Bumps.Pr pr) {
        return Flows.flow(pr, Bumps.bump(pr.title()), new Flows.Ctx(Prs.NOW, null, null, 0));
    }

    @Test
    void greenIsYoursToMergeOrQueue() {
        Flows.Flow f = flow(Prs.pr("SUCCESS", "CLEAN", "MERGEABLE"));
        assertEquals(Flows.YOU, f.waiting());
        assertEquals("merge", f.next().id());
        assertTrue(f.ready());
    }

    @Test
    void behindIsQueuedSoTheQueueRebasesIt() {
        Flows.Flow f = flow(Prs.pr("SUCCESS", "BEHIND", "MERGEABLE"));
        assertEquals("queue", f.next().id());
        assertTrue(f.ready());
    }

    @Test
    void failingCiAsksForARebaseAndIsntReady() {
        Flows.Flow f = flow(Prs.pr("FAILURE", "UNSTABLE", "MERGEABLE"));
        assertEquals("dependabot-rebase", f.next().id());
        assertFalse(f.ready());
    }

    @Test
    void runningCiIsCisMoveButCanBeQueued() {
        Flows.Flow f = flow(Prs.pr("PENDING", "BLOCKED", "MERGEABLE"));
        assertEquals(Flows.CI, f.waiting());
        assertNull(f.next());
        assertTrue(f.ready());
    }

    @Test
    void aMajorBumpIsNeverQueuedWithTheRest() {
        Bumps.Pr pr = Prs.pr("SUCCESS", "CLEAN", "MERGEABLE");
        Bumps.Pr major = new Bumps.Pr(pr.repo(), 2776, "Bump io.smallrye.config:smallrye-config from 3.18.3 to 4.2.2", pr.url(),
                pr.author(), pr.headRef(), pr.headSha(), pr.base(), pr.defaultBranch(), pr.ci(), null, pr.mergeable(),
                pr.mergeState(), false, false, pr.createdAt(), pr.updatedAt());
        Flows.Flow f = flow(major);
        assertFalse(f.ready());
        assertEquals("merge", f.next().id());
        assertTrue(f.say().contains("major"), f.say());
    }

    @Test
    void queuedIsTheQueuesMoveWithItsPlaceInLine() {
        Bumps.Pr pr = Prs.pr("PENDING", "BLOCKED", "MERGEABLE");
        MergeQueue.Item q = Prs.queued(pr).with(Prs.SHA, Prs.NOW, "CI is running.", null);
        Flows.Flow first = Flows.flow(pr, Bumps.bump(pr.title()), new Flows.Ctx(Prs.NOW, null, q, 1));
        assertEquals(Flows.QUEUE, first.waiting());
        assertEquals("Next in the merge queue. CI is running.", first.say());
        Flows.Flow third = Flows.flow(pr, Bumps.bump(pr.title()), new Flows.Ctx(Prs.NOW, null, q, 3));
        assertEquals("In the merge queue, 3rd in line.", third.say());
        assertEquals(List.of("unqueue"), third.more().stream().map(Flows.Next::id).toList());
    }

    @Test
    void stoppedInTheQueueIsYoursAgain() {
        Bumps.Pr pr = Prs.pr("FAILURE", "UNSTABLE", "MERGEABLE");
        MergeQueue.Item q = Prs.queued(pr).with(Prs.SHA, Prs.NOW, null, "CI failed. Look at what failed, then queue it again.");
        Flows.Flow f = Flows.flow(pr, Bumps.bump(pr.title()), new Flows.Ctx(Prs.NOW, null, q, 0));
        assertEquals(Flows.YOU, f.waiting());
        assertEquals("queue", f.next().id());
        assertTrue(f.say().startsWith("Stopped in the merge queue: CI failed"), f.say());
    }

    @Test
    void theBoardNumbersEachLineOfTheQueueOnItsOwn() {
        Bumps.Pr a = Prs.pr("SUCCESS", "BEHIND", "MERGEABLE");
        Bumps.Pr b = new Bumps.Pr(a.repo(), 2814, "[2.x] Bump io.micrometer:micrometer-core from 1.17.2 to 1.17.3", a.url(),
                a.author(), a.headRef(), Prs.SHA2, "2.x", "main", "PENDING", null, "MERGEABLE", "BLOCKED", false, false,
                a.createdAt(), a.updatedAt());
        Board.View v = Board.build("me", Prs.NOW, List.of(a.repo()), List.of(a, b), List.of(Prs.queued(a), Prs.queued(b)),
                Map.of(), List.of(), List.of(), Prs.NOW);
        // Different branches, different lines: both are next in theirs.
        v.rows().forEach(r -> assertTrue(r.flow().say().startsWith("Next in the merge queue"), r.flow().say()));
    }
}
