import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Where each bump is, whose move it is, and the next step. Pure, so the rules are easy to test.
 *
 * The elf gets a bump ready by itself ({@link Tend}, {@link SafetyCheck}): it's ready to merge once it's up to date,
 * its CI passed and the safety check says it's safe. It's yours sooner only when the elf can't go on alone.
 */
final class Flows {

    private Flows() {}

    static final List<String> STEPS = List.of("Up to date", "CI", "Checked", "Merge");

    /**
     * Whose move: ready (yours, a click on Merge), you (the elf can't go on alone), the merge queue's, or the elf's
     * (rebasing, CI, the safety check: at its refresh).
     */
    static final String READY = "ready", YOU = "you", QUEUE = "queue", ELF = "elf";

    /** Yours (ready, or needing you), but you put it aside: under Later, and Merge all leaves it out. */
    static final String LATER = "later";

    /**
     * @param steps   the flow's stages, for the row's stepper
     * @param step    the current stage, an index into {@code steps}
     * @param waiting whose move it is
     * @param since   when it started waiting there (best effort)
     * @param say     one sentence on where it is
     * @param next    the main step, or null when there is nothing for you to do
     * @param more    other steps that fit now
     * @param ready   Merge all takes it: the safety check said safe, it isn't queued, and nothing waits for you
     */
    record Flow(List<String> steps, int step, String waiting, Instant since, String say, Next next, List<Next> more,
                boolean ready) {
    }

    /**
     * A step the tab knows how to take: merge, queue, unqueue, dependabot-rebase, approve-ci, recheck, close (the
     * elf's actions); checks (its CI on GitHub), github (the PR on GitHub).
     */
    record Next(String id, String label) {
    }

    static final Next MERGE = new Next("merge", "Merge");
    static final Next MERGE_ANYWAY = new Next("merge", "Merge anyway");
    static final Next QUEUE_AGAIN = new Next("queue", "Queue again");
    static final Next UNQUEUE = new Next("unqueue", "Take off the queue");
    static final Next REBASE = new Next("dependabot-rebase", "Ask Dependabot to rebase");
    static final Next APPROVE_CI = new Next("approve-ci", "Approve CI run");
    static final Next RECHECK = new Next("recheck", "Check again");
    static final Next CLOSE = new Next("close", "Close");
    static final Next CHECKS = new Next("checks", "See CI on GitHub");
    static final Next GITHUB = new Next("github", "On GitHub");

    /**
     * @param rebaseAsked a rebase was asked for (by the elf, the queue or you), at whatever head, or null
     * @param queued      it's in the merge queue, or null
     * @param position    its place in its line of the queue (1 is next), 0 when it stopped
     * @param retry       the retries it had for failing CI, or null
     * @param check       the safety check at its title, or null while it wasn't checked
     * @param laneBusy    the merge queue works through its repo and branch now
     */
    record Ctx(Instant now, MergeQueue.Rebase rebaseAsked, MergeQueue.Item queued, int position, Tend.Retry retry,
               SafetyCheck.Check check, boolean laneBusy) {

        Ctx(Instant now) {
            this(now, null, null, 0, null, null, false);
        }
    }

    /** Put aside: only a flow that's yours moves to Later; the others carry on as they are. */
    static Flow later(Flow f) {
        if (!f.waiting().equals(READY) && !f.waiting().equals(YOU)) return f;
        return new Flow(f.steps(), f.step(), LATER, f.since(), f.say(), f.next(), f.more(), false);
    }

    static Flow flow(Bumps.Pr i, Bumps.Bump bump, Ctx c) {
        if (c.queued() != null) return queued(base(i, bump, c), i, c);
        Flow f = base(i, bump, c);
        boolean ready = c.check() != null && c.check().safe() && (f.waiting().equals(READY) || f.waiting().equals(ELF));
        return new Flow(f.steps(), f.step(), f.waiting(), f.since(), f.say(), f.next(), f.more(), ready);
    }

    /** In the merge queue: the queue's move, unless it stopped there. */
    private static Flow queued(Flow f, Bumps.Pr i, Ctx c) {
        MergeQueue.Item q = c.queued();
        if (q.stopped() != null) {
            List<Next> more = new ArrayList<>(List.of(UNQUEUE));
            if (f.next() != null && !f.next().id().equals("queue")) more.add(0, f.next());
            if (Bumps.ciFailing(i)) more.add(CHECKS);
            more.add(CLOSE);
            return new Flow(f.steps(), f.step(), YOU, q.since(), "Stopped in the merge queue: " + q.stopped(), QUEUE_AGAIN,
                    List.copyOf(more), false);
        }
        String where = c.position() <= 1 ? "Next in the merge queue" : "In the merge queue, " + ordinal(c.position()) + " in line";
        String note = c.position() <= 1 && q.note() != null ? ". " + q.note() : ".";
        return new Flow(f.steps(), f.step(), QUEUE, q.queuedAt(), where + note, null, List.of(UNQUEUE), false);
    }

    private static Flow base(Bumps.Pr i, Bumps.Bump bump, Ctx c) {
        if (Boolean.TRUE.equals(i.draft())) return flow(0, YOU, i.updatedAt(), "A draft: the elf leaves it alone.", GITHUB, List.of(CLOSE));
        MergeQueue.Rebase r = c.rebaseAsked();
        boolean askedHere = r != null && r.sha() != null && r.sha().equals(i.headSha());
        boolean needsRebase = Bumps.conflicts(i) || Bumps.behind(i) || Bumps.ciFailing(i);
        if (askedHere && needsRebase) {
            if (r.at() != null && r.at().isBefore(c.now().minus(MergeQueue.REBASE_WAIT))) {
                return flow(0, YOU, r.at(), "Dependabot didn't rebase it within an hour of being asked.", REBASE, List.of(CLOSE));
            }
            return flow(0, ELF, r.at(), "Asked Dependabot to rebase it. Waiting for its push.", null, List.of(CLOSE));
        }
        if (Bumps.conflicts(i)) {
            return flow(0, ELF, i.updatedAt(), c.laneBusy() ? "Conflicts with its base: rebased once the merge queue is through."
                    : "Conflicts with its base: the elf asks Dependabot to rebase it.", null, List.of(CLOSE));
        }
        if (Bumps.behind(i)) {
            return flow(0, ELF, i.updatedAt(), c.laneBusy() ? "Behind its base: rebased once the merge queue is through."
                    : "Behind its base: the elf asks Dependabot to rebase it.", null, List.of(CLOSE));
        }
        if (Bumps.AWAITING_DEPLOYMENT.equals(i.ci())) {
            return flow(1, YOU, i.updatedAt(), "CI waits at a deployment environment: approving that hands the job its secrets, "
                    + "so it's yours.", APPROVE_CI, List.of(CLOSE));
        }
        if (Bumps.AWAITING_APPROVAL.equals(i.ci())) {
            // Approved by the elf only once the safety check says safe, and never when it changes the workflows.
            SafetyCheck.Check check = c.check();
            if (Bumps.touchesWorkflows(i, check)) {
                String said = check == null ? "" : check.safe() ? " The safety check says safe"
                        + (check.reason() == null ? "." : ": " + check.reason()) : " The safety check says to look first"
                        + (check.reason() == null ? "." : ": " + check.reason());
                return flow(1, YOU, i.updatedAt(), "CI waits for your approval: the bump changes the workflows that run."
                        + said, APPROVE_CI, check == null ? List.of(CLOSE) : List.of(RECHECK, CLOSE));
            }
            if (check == null) {
                return flow(1, ELF, i.updatedAt(), "CI waits for approval: the elf approves it once the safety check says safe.",
                        null, List.of(APPROVE_CI, CLOSE));
            }
            if (check.safe()) {
                return flow(1, ELF, i.updatedAt(), "CI waits for approval: the safety check says safe, so the elf approves it.",
                        null, List.of(CLOSE));
            }
            return flow(1, YOU, check.at(), "CI waits for your approval. The safety check says to look first"
                    + (check.reason() == null ? "." : ": " + check.reason()), APPROVE_CI, List.of(RECHECK, CLOSE));
        }
        if (Bumps.ciFailing(i)) {
            if (Tend.gaveUp(i, c.retry())) {
                // GitHub lets it merge with failing checks the repo doesn't require (UNSTABLE).
                Next next = "UNSTABLE".equals(i.mergeState()) ? MERGE_ANYWAY : null;
                return flow(1, YOU, i.updatedAt(), "CI still fails after a re-run and a rebase. Look at what failed.", next,
                        List.of(CHECKS, CLOSE));
            }
            return flow(1, ELF, i.updatedAt(), "CI failed: the elf re-runs it, then asks for a rebase.", null, List.of(CHECKS, CLOSE));
        }
        if (i.ci() == null || Bumps.ciRunning(i)) {
            return flow(1, ELF, i.updatedAt(), i.ci() == null ? "Waiting for CI to start." : "CI is running.", null, List.of(CLOSE));
        }
        if (Bumps.green(i) || Bumps.greenButReview(i)) {
            String review = Bumps.greenButReview(i) ? " The repo wants a review: Merge approves it first." : "";
            SafetyCheck.Check check = c.check();
            if (check == null) {
                return flow(2, ELF, i.updatedAt(), "CI passed. Waiting for the safety check.", null, List.of(MERGE, CLOSE));
            }
            if (!check.safe()) {
                return flow(2, YOU, check.at(), "CI passed. The safety check says to look first"
                        + (check.reason() == null ? "." : ": " + check.reason()) + review, MERGE, List.of(RECHECK, CLOSE));
            }
            String major = bump != null && bump.major() ? " A major bump." : "";
            return flow(3, READY, check.at(), "CI passed and the safety check says it's safe"
                    + (check.reason() == null ? "." : ": " + check.reason()) + major + review, MERGE, List.of(CLOSE));
        }
        return flow(1, YOU, i.updatedAt(), "GitHub doesn't let it merge yet (" + lower(i.mergeState()) + ").", GITHUB, List.of(CLOSE));
    }

    private static Flow flow(int step, String waiting, Instant since, String say, Next next, List<Next> more) {
        return new Flow(STEPS, step, waiting, since, say, next, more, false);
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
