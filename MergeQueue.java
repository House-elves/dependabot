import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * The merge queue: the bumps you queued with a click, merged one at a time per repo and branch, as you'd do it by
 * hand. Bumps into the same branch all touch the same files, so each merge puts the next one behind its base (or in
 * conflict): the queue asks Dependabot to rebase it, waits for CI to pass again, and merges it, then goes on to the next.
 * Nothing merges that you didn't queue, and only at a head whose CI passed.
 *
 * <p>
 * A bump that can't go on (CI failed, Dependabot changed it to another version, CI waits for your approval, nothing
 * moved for {@link #STUCK}) stops: it stays in the queue with why, and the ones after it go on without it.
 *
 * <p>
 * It moves on at each refresh (every 10 minutes on weekdays) and right after you queue something: state/queue.json,
 * in the order you queued them. One process at a time changes it ({@link #locked}).
 */
final class MergeQueue {

    private MergeQueue() {}

    /** How long a queued bump may sit at the front of its line with nothing happening before it stops. */
    static final Duration STUCK = Duration.ofHours(6);
    /** How long a rebase you or the queue asked for counts as on its way. */
    static final Duration REBASE_WAIT = Duration.ofHours(1);

    /**
     * A bump in the queue.
     *
     * @param title   its title when you queued it: a new title means Dependabot moved it to another version
     * @param base    the branch it merges into: the queue's line it waits in
     * @param sha     its head when the queue last looked
     * @param since   when it last moved (queued, rebased, CI started): for {@link #STUCK}
     * @param note    what the queue last did or waits for, in a few words
     * @param stopped why it stopped, or null while it goes on
     */
    record Item(String repo, int pr, String title, String base, Instant queuedAt, String sha, Instant since, String note,
                String stopped) {

        String key() { return repo + "#" + pr; }

        String lane() { return repo + "@" + base; }

        Item with(String newSha, Instant newSince, String newNote, String newStopped) {
            return new Item(repo, pr, title, base, queuedAt, newSha, newSince, newNote, newStopped);
        }
    }

    /** You or the queue asked Dependabot to rebase, at this head. Done once the head moves. */
    record Rebase(String sha, Instant at) {
    }

    /** A bump that merged lately, for the tab's "Merged" line: by the queue, or by your Merge. */
    record Merged(String repo, int pr, String title, String url, Instant at, boolean queued) {
    }

    enum Do { MERGE, APPROVE, REBASE, WAIT, STOP, GONE }

    record Step(Do what, String say) {
    }

    /**
     * What to do next about the bump at the front of its line. Pure.
     *
     * @param state       OPEN, MERGED or CLOSED
     * @param rebaseAsked when a rebase was asked at this head, or null
     * @param approved    you approved it at this head already
     */
    static Step next(Bumps.Pr pr, String state, Item it, Rebase rebaseAsked, boolean approved, Instant now) {
        if (!"OPEN".equals(state)) return new Step(Do.GONE, "MERGED".equals(state) ? "Merged" : "Closed");
        if (it.title() != null && !it.title().equals(pr.title())) {
            return new Step(Do.STOP, "Dependabot changed it since you queued it (" + pr.title() + "). Queue it again.");
        }
        if (Boolean.TRUE.equals(pr.draft())) return new Step(Do.STOP, "It's a draft now.");
        boolean asked = rebaseAsked != null && pr.headSha() != null && pr.headSha().equals(rebaseAsked.sha());
        if (Bumps.conflicts(pr) || Bumps.behind(pr)) {
            if (!asked) return new Step(Do.REBASE, Bumps.conflicts(pr) ? "Conflicts with its base: asked Dependabot to rebase it."
                    : "Behind its base: asked Dependabot to rebase it.");
            if (rebaseAsked.at().isBefore(now.minus(REBASE_WAIT))) {
                return new Step(Do.STOP, "Dependabot didn't rebase it within an hour of being asked.");
            }
            return new Step(Do.WAIT, "Waiting for Dependabot's rebase.");
        }
        if (Bumps.AWAITING_APPROVAL.equals(pr.ci())) {
            return new Step(Do.STOP, "CI waits for you to approve its run: Approve CI run, then queue it again.");
        }
        if (Bumps.ciFailing(pr)) return new Step(Do.STOP, "CI failed. Look at what failed, then queue it again.");
        if (Bumps.green(pr)) return new Step(Do.MERGE, "Merged");
        if (Bumps.greenButReview(pr)) {
            return approved ? new Step(Do.WAIT, "Approved; waiting for GitHub to let it merge.")
                    : new Step(Do.APPROVE, "The repo wants a review: approved it.");
        }
        Step wait = new Step(Do.WAIT, pr.ci() == null ? "Waiting for CI to start."
                : Bumps.ciRunning(pr) ? "CI is running." : "Waiting for GitHub to let it merge (" + lower(pr.mergeState()) + ").");
        if (it.since() != null && it.since().isBefore(now.minus(STUCK))) {
            return new Step(Do.STOP, "Nothing moved for " + STUCK.toHours() + " hours (" + wait.say().toLowerCase().replace(".", "") + ").");
        }
        return wait;
    }

    // ---- the queue's file ----

    static List<Item> all(Config config) {
        try {
            if (!Files.isRegularFile(config.queuePath())) return List.of();
            return Board.MAPPER.readValue(config.queuePath().toFile(), new TypeReference<List<Item>>() {});
        } catch (IOException e) {
            return List.of();
        }
    }

    static void write(Config config, List<Item> items) throws IOException {
        Board.writeJson(config.queuePath(), items);
    }

    static Map<String, Rebase> rebasing(Config config) {
        try {
            if (!Files.isRegularFile(config.rebasingPath())) return Map.of();
            return Board.MAPPER.readValue(config.rebasingPath().toFile(), new TypeReference<Map<String, Rebase>>() {});
        } catch (IOException e) {
            return Map.of();
        }
    }

    static void putRebase(Config config, String key, Rebase r) throws IOException {
        Map<String, Rebase> next = new HashMap<>(rebasing(config));
        if (r == null) next.remove(key);
        else next.put(key, r);
        Board.writeJson(config.rebasingPath(), next);
    }

    static List<Merged> merged(Config config) {
        try {
            if (!Files.isRegularFile(config.mergedPath())) return List.of();
            return Board.MAPPER.readValue(config.mergedPath().toFile(), new TypeReference<List<Merged>>() {});
        } catch (IOException e) {
            return List.of();
        }
    }

    /** Keeps what merged in the last three days, newest first. */
    static void addMerged(Config config, Merged m) throws IOException {
        List<Merged> next = new ArrayList<>();
        next.add(m);
        Instant cutoff = Instant.now().minus(Duration.ofDays(3));
        merged(config).stream().filter(x -> x.at() != null && x.at().isAfter(cutoff) && !(x.repo().equals(m.repo()) && x.pr() == m.pr()))
                .forEach(next::add);
        Board.writeJson(config.mergedPath(), next);
    }

    /** One process at a time changes the queue: the schedule's refresh, a click on the tab. Waits for the other. */
    static <T> T locked(Config config, Callable<T> work) throws IOException {
        Files.createDirectories(config.lockPath().getParent());
        try (RandomAccessFile raf = new RandomAccessFile(config.lockPath().toFile(), "rw");
             FileChannel ch = raf.getChannel(); FileLock ignored = ch.lock()) {
            return work.call();
        } catch (IOException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    // ---- changing it ----

    /** Adds bumps at the end of the queue (one already in it that stopped goes on again from where it is). */
    static List<Item> add(Config config, List<Bumps.Pr> prs) throws IOException {
        List<Item> items = new ArrayList<>(all(config));
        Instant now = Instant.now();
        for (Bumps.Pr pr : prs) {
            int at = indexOf(items, pr.key());
            Item fresh = new Item(pr.repo(), pr.number(), pr.title(), pr.base(), now, pr.headSha(), now, "Queued.", null);
            if (at >= 0) items.set(at, fresh);
            else items.add(fresh);
        }
        write(config, items);
        return items;
    }

    static List<Item> remove(Config config, String key) throws IOException {
        List<Item> items = new ArrayList<>(all(config));
        items.removeIf(i -> i.key().equals(key));
        write(config, items);
        return items;
    }

    private static int indexOf(List<Item> items, String key) {
        for (int i = 0; i < items.size(); i++) if (items.get(i).key().equals(key)) return i;
        return -1;
    }

    /** Moves the queue on: in each line, merges what's green, asks for the rebases, stops what can't go on. Under the lock. */
    static void advance(Config config) throws IOException {
        locked(config, () -> {
            advanceUnlocked(config);
            return null;
        });
    }

    /** {@link #advance}, for a caller that holds the lock already. */
    static void advanceUnlocked(Config config) throws IOException {
        List<Item> items = new ArrayList<>(all(config));
        if (items.isEmpty()) return;
        Map<String, List<Item>> lanes = new LinkedHashMap<>();
        for (Item it : items) lanes.computeIfAbsent(it.lane(), k -> new ArrayList<>()).add(it);
        Map<String, GitHub> clients = new HashMap<>();
        for (List<Item> lane : lanes.values()) {
            for (Item it : lane) {
                if (it.stopped() != null) continue;
                if (config.repo(it.repo()) == null) {
                    replace(items, it, null);
                    continue;
                }
                GitHub gh = clients.computeIfAbsent(it.repo(), r -> new GitHub(config, r));
                boolean goOn;
                try {
                    goOn = step(config, gh, items, it);
                } catch (IOException | RuntimeException e) {
                    // GitHub couldn't be reached: the line waits for the next refresh.
                    System.err.println("Merge queue, " + it.key() + ": " + e.getMessage());
                    goOn = false;
                }
                write(config, items);
                if (!goOn) break;
            }
        }
        write(config, items);
    }

    /** One bump at the front of its line. True when the line goes on to the next bump now (this one merged, went or stopped). */
    private static boolean step(Config config, GitHub gh, List<Item> items, Item it) throws IOException {
        JsonNode repository = gh.pull(it.pr());
        JsonNode node = repository.path("pullRequest");
        if (!node.isObject()) {
            replace(items, it, null);
            return true;
        }
        Bumps.Pr pr = Bumps.pr(it.repo(), node);
        Instant now = Instant.now();
        // It moved (Dependabot pushed, a rebase landed): the clock for STUCK starts again.
        Item seen = pr.headSha() != null && !pr.headSha().equals(it.sha()) ? it.with(pr.headSha(), now, it.note(), null) : it;
        Rebase asked = rebasing(config).get(it.key());
        // Your reviews are read only when the repo wants one: one call less for every other bump.
        boolean approved = Bumps.greenButReview(pr) && Actions.approvedAt(gh, it.pr(), pr.headSha(), config.githubUser);
        Step s = next(pr, node.path("state").asText(), seen, asked, approved, now);
        switch (s.what()) {
            case GONE -> {
                replace(items, it, null);
                System.out.println(it.key() + ": " + s.say().toLowerCase() + " on GitHub; off the queue");
                return true;
            }
            case STOP -> {
                replace(items, it, seen.with(seen.sha(), seen.since(), null, s.say()));
                System.out.println(it.key() + " stopped: " + s.say());
                return true;
            }
            case REBASE -> {
                Actions.askRebase(config, gh, pr);
                replace(items, it, seen.with(seen.sha(), now, s.say(), null));
                System.out.println(it.key() + ": " + s.say());
                return false;
            }
            case APPROVE -> {
                Actions.approve(gh, pr);
                replace(items, it, seen.with(seen.sha(), now, s.say(), null));
                System.out.println(it.key() + ": " + s.say());
                return false;
            }
            case MERGE -> {
                try {
                    Actions.mergeNow(config, gh, repository, pr, true);
                } catch (IOException e) {
                    // GitHub's mergeState can lag a merge just before: "Head branch was modified" / "not up to date".
                    if (outOfDate(e.getMessage())) {
                        Actions.askRebase(config, gh, pr);
                        replace(items, it, seen.with(seen.sha(), now, "Behind its base: asked Dependabot to rebase it.", null));
                        return false;
                    }
                    replace(items, it, seen.with(seen.sha(), seen.since(), null, "GitHub didn't merge it: " + e.getMessage()));
                    return true;
                }
                replace(items, it, null);
                return true;
            }
            default -> {
                if (!s.say().equals(seen.note()) || seen != it) replace(items, it, seen.with(seen.sha(), seen.since(), s.say(), null));
                return false;
            }
        }
    }

    static boolean outOfDate(String message) {
        String m = message == null ? "" : message.toLowerCase();
        return m.contains("out of date") || m.contains("not up to date") || m.contains("was modified");
    }

    private static void replace(List<Item> items, Item old, Item now) {
        int at = indexOf(items, old.key());
        if (at < 0) return;
        if (now == null) items.remove(at);
        else items.set(at, now);
    }

    private static String lower(String s) {
        return s == null ? "unknown" : s.toLowerCase().replace('_', ' ');
    }

    /** For the tab: the queue as JSON. */
    static ObjectNode answer(List<Item> items) {
        ObjectNode a = Board.MAPPER.createObjectNode();
        a.set("queue", Board.MAPPER.valueToTree(items));
        return a;
    }
}
