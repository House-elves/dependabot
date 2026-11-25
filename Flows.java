import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Where each bump is, whose move it is, and the next step. Pure, so the rules are easy to test.
 */
final class Flows {

    private Flows() {}

    static final List<String> STEPS = List.of("Up to date", "CI", "Merge");

    /** Whose move: yours, the merge queue's, CI's, or someone else's (Dependabot's rebase, GitHub). */
    static final String YOU = "you", QUEUE = "queue", CI = "ci", OTHERS = "others";

    /**
     * @param steps   the flow's stages, for the row's stepper
     * @param step    the current stage, an index into {@code steps}
     * @param waiting whose move it is
     * @param since   when it started waiting there (best effort)
     * @param say     one sentence on where it is
     * @param next    the main step, or null when there is nothing for you to do
     * @param more    other steps that fit now
     * @param ready   "Queue every ready bump" takes it: not queued, not major, and green, behind, or with CI running
     */
    record Flow(List<String> steps, int step, String waiting, Instant since, String say, Next next, List<Next> more,
                boolean ready) {
    }

    /**
     * A step the tab knows how to take: merge, queue, unqueue, dependabot-rebase, approve-ci, close (the elf's
     * actions); checks (its CI on GitHub), github (the PR on GitHub).
     */
    record Next(String id, String label) {
    }

    static final Next MERGE = new Next("merge", "Merge");
    static final Next QUEUE_IT = new Next("queue", "Queue to merge");
    static final Next QUEUE_AGAIN = new Next("queue", "Queue again");
    static final Next UNQUEUE = new Next("unqueue", "Take off the queue");
    static final Next REBASE = new Next("dependabot-rebase", "Ask Dependabot to rebase");
    static final Next APPROVE_CI = new Next("approve-ci", "Approve CI run");
    static final Next CLOSE = new Next("close", "Close");
    static final Next CHECKS = new Next("checks", "See CI on GitHub");
    static final Next GITHUB = new Next("github", "On GitHub");

    /**
     * @param rebaseAsked you (or the queue) asked Dependabot to rebase this head within the last hour, or null
     * @param queued      it's in the merge queue, or null
     * @param position    its place in its line of the queue (1 is next), 0 when it stopped
     */
    record Ctx(Instant now, Instant rebaseAsked, MergeQueue.Item queued, int position) {
    }

    static Flow flow(Bumps.Pr i, Bumps.Bump bump, Ctx c) {
        Flow f = base(i, c);
        if (c.queued() != null) return queued(f, i, c);
        if (bump != null && bump.major()) {
            f = new Flow(f.steps(), f.step(), f.waiting(), f.since(), f.say() + " A major bump: read its release notes first.",
                    f.next(), f.more(), false);
        } else if (bump == null && f.ready()) {
            // A grouped update ("Bump the maven group with 3 updates") may hide a major one: you queue it yourself.
            f = new Flow(f.steps(), f.step(), f.waiting(), f.since(), f.say(), f.next(), f.more(), false);
        }
        return f;
    }

    /** In the merge queue: the queue's move, unless it stopped there. */
    private static Flow queued(Flow f, Bumps.Pr i, Ctx c) {
        MergeQueue.Item q = c.queued();
        if (q.stopped() != null) {
            List<Next> more = new ArrayList<>(List.of(UNQUEUE));
            if (f.next() != null && !f.next().id().equals("queue") && !f.next().id().equals("merge")) more.add(f.next());
            if (Bumps.ciFailing(i)) more.add(CHECKS);
            return new Flow(f.steps(), f.step(), YOU, q.since(), "Stopped in the merge queue: " + q.stopped(), QUEUE_AGAIN,
                    List.copyOf(more), false);
        }
        String where = c.position() <= 1 ? "Next in the merge queue" : "In the merge queue, " + ordinal(c.position()) + " in line";
        String note = c.position() <= 1 && q.note() != null ? ". " + q.note() : ".";
        return new Flow(f.steps(), f.step(), QUEUE, q.queuedAt(), where + note, null, List.of(UNQUEUE), false);
    }

    private static Flow base(Bumps.Pr i, Ctx c) {
        if (c.rebaseAsked() != null) {
            return flow(0, OTHERS, c.rebaseAsked(), "Dependabot was asked to rebase it. Waiting for its push.", null, List.of(CLOSE), false);
        }
        if (Bumps.conflicts(i)) {
            return flow(0, YOU, i.updatedAt(), "Conflicts with its base.", REBASE, List.of(CLOSE), false);
        }
        if (Bumps.AWAITING_APPROVAL.equals(i.ci())) {
            return flow(1, YOU, i.updatedAt(), "CI waits for you to approve its run.", APPROVE_CI, List.of(CLOSE), false);
        }
        if (Bumps.ciFailing(i)) {
            // A bump rarely breaks CI on its own: often CI broke on the base and was fixed there since. A rebase takes it.
            return flow(1, YOU, i.updatedAt(), "CI is failing. A rebase picks up any CI fixes on its base.", REBASE,
                    List.of(CHECKS, CLOSE), false);
        }
        if (Bumps.behind(i)) {
            return flow(0, YOU, i.updatedAt(), "Behind its base. The queue rebases it, waits for CI, then merges it.", QUEUE_IT,
                    List.of(REBASE, CLOSE), true);
        }
        if (Bumps.green(i)) return flow(2, YOU, i.updatedAt(), "Green.", MERGE, List.of(QUEUE_IT, CLOSE), true);
        if (Bumps.greenButReview(i)) {
            return flow(2, YOU, i.updatedAt(), "Green. The repo wants a review: Merge approves it first.", MERGE,
                    List.of(QUEUE_IT, CLOSE), true);
        }
        if (i.ci() == null || Bumps.ciRunning(i)) {
            return flow(1, CI, i.updatedAt(), i.ci() == null ? "Waiting for CI to start." : "CI is running.", null,
                    List.of(QUEUE_IT, CLOSE), true);
        }
        return flow(1, OTHERS, i.updatedAt(), "GitHub doesn't let it merge yet (" + lower(i.mergeState()) + ").", GITHUB,
                List.of(QUEUE_IT, CLOSE), false);
    }

    private static Flow flow(int step, String waiting, Instant since, String say, Next next, List<Next> more, boolean ready) {
        return new Flow(STEPS, step, waiting, since, say, next, more, ready);
    }

    private static String ordinal(int n) {
        String s = (n % 100 >= 11 && n % 100 <= 13) ? "th" : switch (n % 10) {
            case 1 -> "st";
            case 2 -> "nd";
            case 3 -> "rd";
            default -> "th";
        };
        return n + s;
    }

    private static String lower(String s) {
        return s == null ? "unknown" : s.toLowerCase().replace('_', ' ');
    }
}
