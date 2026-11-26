import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Where each bump is, and what the row offers. */
class FlowsTest {

    static SafetyCheck.Check safe(Bumps.Pr pr) {
        return new SafetyCheck.Check(pr.title(), "safe", "A minor release with bug fixes.", Prs.NOW);
    }

    static Flows.Flow flow(Bumps.Pr pr, SafetyCheck.Check check) {
        return Flows.flow(pr, Bumps.bump(pr.title()), new Flows.Ctx(Prs.NOW, null, null, 0, null, check, false));
    }

    @Test
    void greenAndCheckedSafeIsReadyToMerge() {
        Bumps.Pr pr = Prs.pr("SUCCESS", "CLEAN", "MERGEABLE");
        Flows.Flow f = flow(pr, safe(pr));
        assertEquals(Flows.READY, f.waiting());
        assertEquals("merge", f.next().id());
        assertTrue(f.ready());
        assertTrue(f.say().contains("bug fixes"), f.say());
    }

    @Test
    void greenButNotCheckedYetIsTheElfs() {
        Flows.Flow f = flow(Prs.pr("SUCCESS", "CLEAN", "MERGEABLE"), null);
        assertEquals(Flows.ELF, f.waiting());
        assertFalse(f.ready());
    }

    @Test
    void aCarefulVerdictIsYoursAndNotForMergeAll() {
        Bumps.Pr pr = Prs.pr("SUCCESS", "CLEAN", "MERGEABLE");
        Flows.Flow f = flow(pr, new SafetyCheck.Check(pr.title(), "careful", "Drops Java 17.", Prs.NOW));
        assertEquals(Flows.YOU, f.waiting());
        assertEquals("merge", f.next().id());
        assertFalse(f.ready());
        assertTrue(f.say().contains("Drops Java 17"), f.say());
    }

    @Test
    void behindIsTheElfsToRebaseAndStillGoesInMergeAll() {
        Bumps.Pr pr = Prs.pr("SUCCESS", "BEHIND", "MERGEABLE");
        Flows.Flow f = flow(pr, safe(pr));
        assertEquals(Flows.ELF, f.waiting());
        assertNull(f.next());
        assertTrue(f.ready());
    }

    @Test
    void aRebaseDependabotDidntDoIsYours() {
        Bumps.Pr pr = Prs.pr("SUCCESS", "BEHIND", "MERGEABLE");
        MergeQueue.Rebase old = new MergeQueue.Rebase(Prs.SHA, Prs.NOW.minus(Duration.ofHours(2)));
        Flows.Flow f = Flows.flow(pr, Bumps.bump(pr.title()), new Flows.Ctx(Prs.NOW, old, null, 0, null, safe(pr), false));
        assertEquals(Flows.YOU, f.waiting());
        assertEquals("dependabot-rebase", f.next().id());
    }

    @Test
    void failingCiIsTheElfsUntilItGaveUp() {
        Bumps.Pr pr = Prs.pr("FAILURE", "UNSTABLE", "MERGEABLE");
        assertEquals(Flows.ELF, flow(pr, safe(pr)).waiting());
        Tend.Retry both = new Tend.Retry(pr.title(), Prs.SHA2, Prs.SHA2);
        Flows.Flow f = Flows.flow(pr, Bumps.bump(pr.title()), new Flows.Ctx(Prs.NOW, null, null, 0, both, safe(pr), false));
        assertEquals(Flows.YOU, f.waiting());
        assertEquals("Merge anyway", f.next().label());
        assertFalse(f.ready());
    }

    @Test
    void ciWaitingForApprovalIsYoursOnlyWhenTheCheckSaysLookFirst() {
        Bumps.Pr pr = Prs.pr(Bumps.AWAITING_APPROVAL, "BLOCKED", "MERGEABLE");
        assertEquals(Flows.ELF, flow(pr, safe(pr)).waiting());
        assertTrue(flow(pr, safe(pr)).ready());
        assertEquals(Flows.ELF, flow(pr, null).waiting());
        Flows.Flow f = flow(pr, new SafetyCheck.Check(pr.title(), "careful", "Drops Java 17.", Prs.NOW));
        assertEquals(Flows.YOU, f.waiting());
        assertEquals("approve-ci", f.next().id());
    }

    @Test
    void ciHeldAtAnEnvironmentOrForAWorkflowChangeIsAlwaysYours() {
        Bumps.Pr atEnv = Prs.pr(Bumps.AWAITING_DEPLOYMENT, "BLOCKED", "MERGEABLE");
        Flows.Flow f = flow(atEnv, safe(atEnv));
        assertEquals(Flows.YOU, f.waiting());
        assertEquals("approve-ci", f.next().id());
        assertFalse(f.ready());

        Bumps.Pr action = Prs.onBranch(Prs.pr(Bumps.AWAITING_APPROVAL, "BLOCKED", "MERGEABLE"),
                "dependabot/github_actions/actions/checkout-6");
        Flows.Flow g = flow(action, safe(action));
        assertEquals(Flows.YOU, g.waiting());
        assertEquals("approve-ci", g.next().id());
        assertTrue(g.say().contains("changes the workflows"), g.say());
        assertFalse(g.ready());
    }

    @Test
    void putAsideIsLaterUntilANewVersionAndNotForMergeAll() {
        Bumps.Pr ready = Prs.pr("SUCCESS", "CLEAN", "MERGEABLE");
        Bumps.Pr running = new Bumps.Pr(ready.repo(), 2900, ready.title(), ready.url(), ready.author(), ready.headRef(),
                Prs.SHA2, "main", "main", "PENDING", null, "MERGEABLE", "BLOCKED", false, false, ready.createdAt(), ready.updatedAt());
        Map<String, SafetyCheck.Check> checks = Map.of(ready.key(), safe(ready), running.key(), safe(running));
        Board.View v = Board.build("me", Prs.NOW, List.of(ready.repo()), List.of(ready, running), List.of(), Map.of(), List.of(),
                Map.of(), checks, Map.of(ready.key(), ready.title(), running.key(), running.title()), List.of(), Prs.NOW);
        Map<Integer, Flows.Flow> by = new java.util.HashMap<>();
        v.rows().forEach(r -> by.put(r.pr().number(), r.flow()));
        assertEquals(Flows.LATER, by.get(2813).waiting());
        assertFalse(by.get(2813).ready());
        // Only a bump that's yours goes to Later: the elf's carry on.
        assertEquals(Flows.ELF, by.get(2900).waiting());
        // Dependabot moved it to another version: it's back.
        Board.View newer = Board.build("me", Prs.NOW, List.of(ready.repo()), List.of(ready), List.of(), Map.of(), List.of(),
                Map.of(), checks, Map.of(ready.key(), "Bump org.mongodb:bson from 5.14.0 to 5.14.1"), List.of(), Prs.NOW);
        assertEquals(Flows.READY, newer.rows().get(0).flow().waiting());
    }

    @Test
    void queuedIsTheQueuesMoveWithItsPlaceInLine() {
        Bumps.Pr pr = Prs.pr("PENDING", "BLOCKED", "MERGEABLE");
        MergeQueue.Item q = Prs.queued(pr).with(Prs.SHA, Prs.NOW, "CI is running.", null);
        Flows.Flow first = Flows.flow(pr, Bumps.bump(pr.title()), new Flows.Ctx(Prs.NOW, null, q, 1, null, safe(pr), true));
        assertEquals(Flows.QUEUE, first.waiting());
        assertEquals("Next in the merge queue. CI is running.", first.say());
        Flows.Flow third = Flows.flow(pr, Bumps.bump(pr.title()), new Flows.Ctx(Prs.NOW, null, q, 3, null, safe(pr), true));
        assertEquals("In the merge queue, 3rd in line.", third.say());
        assertEquals(List.of("unqueue"), third.more().stream().map(Flows.Next::id).toList());
        assertFalse(third.ready());
    }

    @Test
    void stoppedInTheQueueIsYoursAgain() {
        Bumps.Pr pr = Prs.pr("FAILURE", "UNSTABLE", "MERGEABLE");
        MergeQueue.Item q = Prs.queued(pr).with(Prs.SHA, Prs.NOW, null, "CI still fails after a re-run and a rebase.");
        Flows.Flow f = Flows.flow(pr, Bumps.bump(pr.title()), new Flows.Ctx(Prs.NOW, null, q, 0, null, null, false));
        assertEquals(Flows.YOU, f.waiting());
        assertEquals("queue", f.next().id());
        assertTrue(f.say().startsWith("Stopped in the merge queue: CI still fails"), f.say());
    }

    @Test
    void theBoardNumbersEachLineOfTheQueueOnItsOwnAndHoldsBackTheRestOfABusyLine() {
        Bumps.Pr a = Prs.pr("SUCCESS", "BEHIND", "MERGEABLE");
        Bumps.Pr b = new Bumps.Pr(a.repo(), 2814, "[2.x] Bump io.micrometer:micrometer-core from 1.17.2 to 1.17.3", a.url(),
                a.author(), a.headRef(), Prs.SHA2, "2.x", "main", "PENDING", null, "MERGEABLE", "BLOCKED", false, false,
                a.createdAt(), a.updatedAt());
        Bumps.Pr c = new Bumps.Pr(a.repo(), 2815, "Bump io.vertx:vertx-core from 4.5.1 to 4.5.2", a.url(),
                a.author(), a.headRef(), Prs.SHA2, "main", "main", "SUCCESS", null, "MERGEABLE", "BEHIND", false, false,
                a.createdAt(), a.updatedAt());
        Board.View v = Board.build("me", Prs.NOW, List.of(a.repo()), List.of(a, b, c), List.of(Prs.queued(a), Prs.queued(b)),
                Map.of(), List.of(), Map.of(), Map.of(), Map.of(), List.of(), Prs.NOW);
        Map<Integer, Flows.Flow> by = new java.util.HashMap<>();
        v.rows().forEach(r -> by.put(r.pr().number(), r.flow()));
        // Different branches, different lines: both are next in theirs.
        assertTrue(by.get(2813).say().startsWith("Next in the merge queue"), by.get(2813).say());
        assertTrue(by.get(2814).say().startsWith("Next in the merge queue"), by.get(2814).say());
        // Not queued, behind, in main where the queue works: rebased once it's through.
        assertTrue(by.get(2815).say().contains("once the merge queue is through"), by.get(2815).say());
    }
}
