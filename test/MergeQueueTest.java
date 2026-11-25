import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

/** The queue's next move for the bump at the front of its line. */
class MergeQueueTest {

    static final Instant NOW = Prs.NOW;

    static MergeQueue.Do next(Bumps.Pr pr, MergeQueue.Rebase asked, boolean approved) {
        return MergeQueue.next(pr, "OPEN", Prs.queued(pr), asked, approved, NOW).what();
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
    void failingOrHeldCiStops() {
        assertEquals(MergeQueue.Do.STOP, next(Prs.pr("FAILURE", "UNSTABLE", "MERGEABLE"), null, false));
        assertEquals(MergeQueue.Do.STOP, next(Prs.pr(Bumps.AWAITING_APPROVAL, "BLOCKED", "MERGEABLE"), null, false));
    }

    @Test
    void runningCiWaitsUntilItsStuck() {
        Bumps.Pr running = Prs.pr("PENDING", "BLOCKED", "MERGEABLE");
        assertEquals(MergeQueue.Do.WAIT, next(running, null, false));
        MergeQueue.Item old = Prs.queued(running).with(Prs.SHA, NOW.minus(Duration.ofHours(7)), "CI is running.", null);
        assertEquals(MergeQueue.Do.STOP, MergeQueue.next(running, "OPEN", old, null, false, NOW).what());
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
        MergeQueue.Step s = MergeQueue.next(pr, "OPEN", it, null, false, NOW);
        assertEquals(MergeQueue.Do.STOP, s.what());
        assertTrue(s.say().contains("5.15.0"), s.say());
    }

    @Test
    void mergedOrClosedLeavesTheQueue() {
        Bumps.Pr pr = Prs.pr("SUCCESS", "CLEAN", "MERGEABLE");
        assertEquals(MergeQueue.Do.GONE, MergeQueue.next(pr, "MERGED", Prs.queued(pr), null, false, NOW).what());
        assertEquals(MergeQueue.Do.GONE, MergeQueue.next(pr, "CLOSED", Prs.queued(pr), null, false, NOW).what());
    }

    @Test
    void aMergeRefusedForBeingBehindIsTakenAsBehind() {
        assertTrue(MergeQueue.outOfDate("gh api failed: Head branch was modified. Review and try the merge again. (HTTP 405)"));
        assertTrue(MergeQueue.outOfDate("Head branch is out of date (HTTP 405)"));
        assertFalse(MergeQueue.outOfDate("At least 1 approving review is required by reviewers with write access. (HTTP 405)"));
    }
}
