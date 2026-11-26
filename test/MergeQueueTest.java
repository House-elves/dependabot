import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

/** The queue's next move for the bump at the front of its line. */
class MergeQueueTest {

    static final Instant NOW = Prs.NOW;

    static final SafetyCheck.Check SAFE = new SafetyCheck.Check("Bump org.mongodb:bson from 5.14.0 to 5.15.0", "safe", "A minor release.", NOW);

    static MergeQueue.Do next(Bumps.Pr pr, MergeQueue.Rebase asked, boolean approved) {
        return MergeQueue.next(pr, "OPEN", Prs.queued(pr), asked, approved, null, SAFE, NOW).what();
    }

    @Test
    void greenMerges() {
        assertEquals(MergeQueue.Do.MERGE, next(Prs.pr("SUCCESS", "CLEAN", "MERGEABLE"), null, false));
        assertEquals(MergeQueue.Do.MERGE, next(Prs.pr("SUCCESS", "HAS_HOOKS", "MERGEABLE"), null, false));
    }

    @Test
    void behindOrConflictingAsksForARebaseOnceThenWaits() {
        Bumps.Pr behind = Prs.pr("SUCCESS", "BEHIND", "MERGEABLE");
        assertEquals(MergeQueue.Do.REBASE, next(behind, null, false));
        assertEquals(MergeQueue.Do.WAIT, next(behind, new MergeQueue.Rebase(Prs.SHA, NOW.minusSeconds(60)), false));
        // Asked at an older head: this one is new, so it's asked again.
        assertEquals(MergeQueue.Do.REBASE, next(behind, new MergeQueue.Rebase(Prs.SHA2, NOW.minusSeconds(60)), false));
        // Asked over an hour ago and nothing came: it stops.
        assertEquals(MergeQueue.Do.STOP, next(behind, new MergeQueue.Rebase(Prs.SHA, NOW.minus(Duration.ofMinutes(61))), false));
        assertEquals(MergeQueue.Do.REBASE, next(Prs.pr(null, "DIRTY", "CONFLICTING"), null, false));
    }

    @Test
    void failingCiIsReRunThenRebasedThenStops() {
        Bumps.Pr failing = Prs.pr("FAILURE", "UNSTABLE", "MERGEABLE");
        String title = failing.title();
        assertEquals(MergeQueue.Do.RERUN, MergeQueue.next(failing, "OPEN", Prs.queued(failing), null, false, null, SAFE, NOW).what());
        Tend.Retry rerun = new Tend.Retry(title, Prs.SHA2, null);
        assertEquals(MergeQueue.Do.REBASE_FOR_CI, MergeQueue.next(failing, "OPEN", Prs.queued(failing), null, false, rerun, SAFE, NOW).what());
        // Rebased for it, and failing at the new head too: yours.
        Tend.Retry both = new Tend.Retry(title, Prs.SHA2, Prs.SHA2);
        assertEquals(MergeQueue.Do.STOP, MergeQueue.next(failing, "OPEN", Prs.queued(failing), null, false, both, SAFE, NOW).what());
        // A rebase asked at this head: it waits for it.
        assertEquals(MergeQueue.Do.WAIT, MergeQueue.next(failing, "OPEN", Prs.queued(failing),
                new MergeQueue.Rebase(Prs.SHA, NOW.minusSeconds(60)), false, both, SAFE, NOW).what());
        Bumps.Pr held = Prs.pr(Bumps.AWAITING_APPROVAL, "BLOCKED", "MERGEABLE");
        assertEquals(MergeQueue.Do.APPROVE_CI, next(held, null, false));
        assertEquals(MergeQueue.Do.STOP, MergeQueue.next(held, "OPEN", Prs.queued(held), null, false, null,
                new SafetyCheck.Check(held.title(), "careful", "Drops Java 17.", NOW), NOW).what());
    }

    @Test
    void theQueueStopsForRunsOnlyYouApprove() {
        Bumps.Pr atEnv = Prs.pr(Bumps.AWAITING_DEPLOYMENT, "BLOCKED", "MERGEABLE");
        assertEquals(MergeQueue.Do.STOP, MergeQueue.next(atEnv, "OPEN", Prs.queued(atEnv), null, false, null, SAFE, NOW).what());
        Bumps.Pr action = Prs.onBranch(Prs.pr(Bumps.AWAITING_APPROVAL, "BLOCKED", "MERGEABLE"),
                "dependabot/github_actions/actions/checkout-6");
        MergeQueue.Step s = MergeQueue.next(action, "OPEN", Prs.queued(action), null, false, null, SAFE, NOW);
        assertEquals(MergeQueue.Do.STOP, s.what());
        assertTrue(s.say().contains("workflows"), s.say());
    }

    @Test
    void onlyWhatTheSafetyCheckSaidIsSafeMerges() {
        Bumps.Pr green = Prs.pr("SUCCESS", "CLEAN", "MERGEABLE");
        assertEquals(MergeQueue.Do.WAIT, MergeQueue.next(green, "OPEN", Prs.queued(green), null, false, null, null, NOW).what());
        SafetyCheck.Check careful = new SafetyCheck.Check(green.title(), "careful", "Drops Java 17.", NOW);
        MergeQueue.Step s = MergeQueue.next(green, "OPEN", Prs.queued(green), null, false, null, careful, NOW);
        assertEquals(MergeQueue.Do.STOP, s.what());
        assertTrue(s.say().contains("Java 17"), s.say());
    }

    @Test
    void runningCiWaitsUntilItsStuck() {
        Bumps.Pr running = Prs.pr("PENDING", "BLOCKED", "MERGEABLE");
        assertEquals(MergeQueue.Do.WAIT, next(running, null, false));
        MergeQueue.Item old = Prs.queued(running).with(Prs.SHA, NOW.minus(Duration.ofHours(7)), "CI is running.", null);
        assertEquals(MergeQueue.Do.STOP, MergeQueue.next(running, "OPEN", old, null, false, null, SAFE, NOW).what());
    }

    @Test
    void aReviewTheRepoWantsIsGivenOnce() {
        Bumps.Pr review = Prs.pr("SUCCESS", "BLOCKED", "MERGEABLE", "REVIEW_REQUIRED", Prs.SHA);
        assertEquals(MergeQueue.Do.APPROVE, next(review, null, false));
        assertEquals(MergeQueue.Do.WAIT, next(review, null, true));
    }

    @Test
    void aBumpDependabotMovedToAnotherVersionStops() {
        Bumps.Pr pr = Prs.pr("SUCCESS", "CLEAN", "MERGEABLE");
        MergeQueue.Item it = new MergeQueue.Item(pr.repo(), pr.number(), "Bump org.mongodb:bson from 5.14.0 to 5.14.1", "main",
                NOW, Prs.SHA, NOW, null, null);
        MergeQueue.Step s = MergeQueue.next(pr, "OPEN", it, null, false, null, SAFE, NOW);
        assertEquals(MergeQueue.Do.STOP, s.what());
        assertTrue(s.say().contains("5.15.0"), s.say());
    }

    @Test
    void mergedOrClosedLeavesTheQueue() {
        Bumps.Pr pr = Prs.pr("SUCCESS", "CLEAN", "MERGEABLE");
        assertEquals(MergeQueue.Do.GONE, MergeQueue.next(pr, "MERGED", Prs.queued(pr), null, false, null, SAFE, NOW).what());
        assertEquals(MergeQueue.Do.GONE, MergeQueue.next(pr, "CLOSED", Prs.queued(pr), null, false, null, SAFE, NOW).what());
    }

    @Test
    void aMergeRefusedForBeingBehindIsTakenAsBehind() {
        assertTrue(MergeQueue.outOfDate("gh api failed: Head branch was modified. Review and try the merge again. (HTTP 405)"));
        assertTrue(MergeQueue.outOfDate("Head branch is out of date (HTTP 405)"));
        assertFalse(MergeQueue.outOfDate("At least 1 approving review is required by reviewers with write access. (HTTP 405)"));
    }
}
