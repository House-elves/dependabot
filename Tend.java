import com.fasterxml.jackson.core.type.TypeReference;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Getting each bump ready for your click, with no click: at every refresh, for every open bump that isn't in the merge
 * queue (the queue tends its own).
 *
 * <ul>
 * <li>Behind its base or in conflict, after a merge into it (yours on the tab, on GitHub, or the queue's): it asks
 * Dependabot to rebase it, once per head. Not while the merge queue works through that repo and branch: each of its
 * merges would put the bump behind again, so it's rebased once the queue is through.</li>
 * <li>CI waiting for approval (a workflow run held for a maintainer, or at a deployment environment): it approves the
 * runs, as you, only once the safety check said the bump is safe ({@link SafetyCheck}). Not checked yet, it waits;
 * the check says to look first, it's yours.</li>
 * <li>CI failing: it re-runs the failed jobs once; failing again, it asks Dependabot to rebase it once (a fix on the
 * base comes with it). Failing after that, it's yours: {@link #gaveUp}.</li>
 * </ul>
 *
 * The retries are kept per bump in state/retries.json, for as long as Dependabot keeps the bump at that version, and
 * cleared once its CI passes.
 */
final class Tend {

    private Tend() {}

    /**
     * The retries a bump had for failing CI, at this title (a new version starts again).
     *
     * @param rerunSha  the head its failed jobs were re-run at, or null
     * @param rebaseSha the head a rebase was asked at because CI failed, or null
     */
    record Retry(String title, String rerunSha, String rebaseSha) {

        static Retry of(Retry r, Bumps.Pr pr) {
            return r != null && pr.title().equals(r.title()) ? r : new Retry(pr.title(), null, null);
        }
    }

    enum Do { REBASE, REBASE_FOR_CI, RERUN, APPROVE_CI, NOTHING }

    record Step(Do what, String say) {
    }

    static final Step NOTHING = new Step(Do.NOTHING, null);

    /** CI failed and both retries are spent: it's yours. */
    static boolean gaveUp(Bumps.Pr pr, Retry retry) {
        Retry r = retry != null && pr.title().equals(retry.title()) ? retry : null;
        return Bumps.ciFailing(pr) && r != null && r.rerunSha() != null && r.rebaseSha() != null;
    }

    /**
     * What to do about a bump now. Pure.
     *
     * @param asked    a rebase was asked for, at whatever head, or null
     * @param laneBusy the merge queue works through this repo and branch now
     * @param check    the safety check at its title, or null while it wasn't checked
     */
    static Step next(Bumps.Pr pr, Retry retry, MergeQueue.Rebase asked, boolean laneBusy, SafetyCheck.Check check) {
        if (Boolean.TRUE.equals(pr.draft())) return NOTHING;
        // One ask per head: the head moves once Dependabot rebased it.
        boolean askedHere = asked != null && asked.sha() != null && asked.sha().equals(pr.headSha());
        if (askedHere) return NOTHING;
        if (Bumps.conflicts(pr)) {
            return laneBusy ? NOTHING : new Step(Do.REBASE, "Conflicts with its base: asked Dependabot to rebase it.");
        }
        if (Bumps.behind(pr)) {
            return laneBusy ? NOTHING : new Step(Do.REBASE, "Behind its base: asked Dependabot to rebase it.");
        }
        if (Bumps.AWAITING_APPROVAL.equals(pr.ci())) {
            return check != null && check.safe() ? new Step(Do.APPROVE_CI, "The safety check says safe: approved its CI runs.") : NOTHING;
        }
        if (Bumps.ciFailing(pr)) {
            Retry r = Retry.of(retry, pr);
            if (r.rerunSha() == null) return new Step(Do.RERUN, "CI failed: re-ran its failed jobs.");
            if (r.rebaseSha() == null) return new Step(Do.REBASE_FOR_CI, "CI failed again: asked Dependabot to rebase it.");
        }
        return NOTHING;
    }

    // ---- its file ----

    static Map<String, Retry> retries(Config config) {
        try {
            if (!Files.isRegularFile(config.retriesPath())) return Map.of();
            return Board.MAPPER.readValue(config.retriesPath().toFile(), new TypeReference<Map<String, Retry>>() {});
        } catch (IOException e) {
            return Map.of();
        }
    }

    static void putRetry(Config config, String key, Retry r) throws IOException {
        Map<String, Retry> next = new HashMap<>(retries(config));
        if (r == null) next.remove(key);
        else next.put(key, r);
        Board.writeJson(config.retriesPath(), next);
    }

    /** The lines the merge queue works through now (repo@base): stopped bumps don't count. */
    static Set<String> busyLanes(List<MergeQueue.Item> queue) {
        return queue.stream().filter(q -> q.stopped() == null).map(MergeQueue.Item::lane).collect(Collectors.toSet());
    }

    // ---- doing it ----

    /**
     * Tends every bump that isn't queued, under the queue's lock, and returns the PRs as they are after it (a re-run
     * makes its CI pending). A bump GitHub refuses a write for is left for the next refresh.
     */
    static List<Bumps.Pr> run(Config config, List<Bumps.Pr> prs) throws IOException {
        return MergeQueue.locked(config, () -> {
            List<MergeQueue.Item> queue = MergeQueue.all(config);
            Set<String> queued = queue.stream().map(MergeQueue.Item::key).collect(Collectors.toSet());
            Set<String> busy = busyLanes(queue);
            Map<String, MergeQueue.Rebase> asked = MergeQueue.rebasing(config);
            Map<String, Retry> retries = retries(config);
            Map<String, SafetyCheck.Check> checks = SafetyCheck.all(config);
            Map<String, GitHub> clients = new HashMap<>();
            List<Bumps.Pr> out = new ArrayList<>();
            for (Bumps.Pr pr : prs) {
                Bumps.Pr now = pr;
                if (!queued.contains(pr.key())) {
                    try {
                        now = tend(config, clients.computeIfAbsent(pr.repo(), r -> new GitHub(config, r)), pr,
                                retries.get(pr.key()), asked.get(pr.key()), busy.contains(pr.lane()), SafetyCheck.current(checks, pr));
                    } catch (IOException | RuntimeException e) {
                        System.err.println("Couldn't tend " + pr.key() + ": " + e.getMessage());
                    }
                }
                out.add(now);
            }
            return out;
        });
    }

    /** One bump: what {@link #next} says, done. Its CI passed: its retries are cleared. */
    static Bumps.Pr tend(Config config, GitHub gh, Bumps.Pr pr, Retry retry, MergeQueue.Rebase asked, boolean laneBusy,
                         SafetyCheck.Check check) throws IOException {
        if ("SUCCESS".equals(pr.ci()) && retry != null) putRetry(config, pr.key(), null);
        Step s = next(pr, retry, asked, laneBusy, check);
        return act(config, gh, pr, retry, s);
    }

    /** Does a step (the queue does its own retries through here too). The PR as it is after it. */
    static Bumps.Pr act(Config config, GitHub gh, Bumps.Pr pr, Retry retry, Step s) throws IOException {
        Retry r = Retry.of(retry, pr);
        switch (s.what()) {
            case REBASE -> Actions.askRebase(config, gh, pr);
            case REBASE_FOR_CI -> {
                Actions.askRebase(config, gh, pr);
                putRetry(config, pr.key(), new Retry(r.title(), r.rerunSha() == null ? pr.headSha() : r.rerunSha(), pr.headSha()));
            }
            case RERUN -> {
                int rerun = Actions.rerunFailed(gh, pr.repo(), pr.number());
                if (rerun == 0) {
                    // Nothing it can re-run (not GitHub Actions, or no write access): a rebase runs CI again.
                    Actions.askRebase(config, gh, pr);
                    putRetry(config, pr.key(), new Retry(r.title(), pr.headSha(), pr.headSha()));
                    System.out.println(pr.key() + ": CI failed and can't be re-run: asked Dependabot to rebase it");
                    return pr;
                }
                putRetry(config, pr.key(), new Retry(r.title(), pr.headSha(), r.rebaseSha()));
                System.out.println(pr.key() + ": " + s.say());
                return pr.withCi("PENDING");
            }
            case APPROVE_CI -> {
                int approved = Actions.approveRuns(gh, pr.repo(), gh.pull(pr.number()).path("pullRequest"));
                if (approved == 0) return pr;
                System.out.println(pr.key() + ": " + s.say());
                return pr.withCi("PENDING");
            }
            case NOTHING -> {
                return pr;
            }
        }
        System.out.println(pr.key() + ": " + s.say());
        return pr;
    }

    /** For the dry run: what it would do, without doing it. */
    static String wouldDo(Bumps.Pr pr, Retry retry, MergeQueue.Rebase asked, boolean laneBusy, SafetyCheck.Check check) {
        Step s = next(pr, retry, asked, laneBusy, check);
        return switch (s.what()) {
            case REBASE, REBASE_FOR_CI -> "would ask Dependabot to rebase it";
            case RERUN -> "would re-run its failed CI jobs";
            case APPROVE_CI -> "would approve its CI runs (checked safe)";
            case NOTHING -> null;
        };
    }
}
