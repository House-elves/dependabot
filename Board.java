import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * The tab's board, board/board.json: every open Dependabot PR in REPOS (but the mvnpm elf's), read from GitHub, each
 * with where it is ({@link Flows}) and its place in the merge queue.
 *
 * The refresh moves the merge queue on first, then reads GitHub (one search per repo, one at a time), gets each bump
 * ready ({@link Tend}, {@link SafetyCheck}) and writes the board. Everything else here only joins what the last read found with the elf's own files again, without asking
 * GitHub: after one of the tab's buttons.
 */
final class Board {

    static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private Board() {}

    /**
     * One bump: the PR, what it bumps (null if the title isn't one bump), the safety check, where it is, its queue
     * entry, and whether an agent is looking in its code now ({@link Look}).
     */
    record Row(Bumps.Pr pr, Bumps.Bump bump, SafetyCheck.Check check, Flows.Flow flow, MergeQueue.Item queued, boolean looking) {

        Row(Bumps.Pr pr, Bumps.Bump bump, SafetyCheck.Check check, Flows.Flow flow, MergeQueue.Item queued) {
            this(pr, bump, check, flow, queued, false);
        }
    }

    /**
     * @param fetchedAt when GitHub was read
     * @param queue     the merge queue, in order
     * @param merged    what merged lately (the queue's and your Merge's), newest first
     * @param errors    repos GitHub couldn't be read for (their rows are from the read before)
     */
    record View(String login, Instant fetchedAt, List<String> repos, List<Row> rows, List<MergeQueue.Item> queue,
                List<MergeQueue.Merged> merged, List<String> errors) {
    }

    /**
     * Moves the queue on, reads GitHub, gets each bump ready ({@link Tend}, then the {@link SafetyCheck} for a few
     * not checked yet) and writes the board. 1 when a repo couldn't be read (the others are still written).
     */
    static int refresh(Config config, boolean save) throws IOException {
        // What's green in the queue merges before GitHub is read, so it's off the board this time.
        if (save) MergeQueue.advance(config);
        View before = read(config.boardPath());
        Set<String> mvnpm = MvnpmElf.repos();
        List<Bumps.Pr> prs = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Map<String, String> heads = new HashMap<>();
        for (String repo : config.repos) {
            try {
                for (JsonNode n : new GitHub(config, repo).openDependabotPrs()) {
                    Bumps.Pr pr = Bumps.pr(repo, n);
                    if (!Bumps.isDependabot(pr.author()) || MvnpmElf.handles(mvnpm, repo, pr.title(), pr.headRef())) continue;
                    prs.add(pr);
                    heads.put(pr.key(), pr.headSha());
                }
            } catch (IOException e) {
                errors.add(repo + ": " + e.getMessage());
                // What the last read found is better than nothing.
                if (before != null) before.rows().stream().map(Row::pr).filter(p -> p.repo().equals(repo)).forEach(prs::add);
            }
        }
        if (!save) {
            View v = build(config, Instant.now(), prs, errors);
            Map<String, MergeQueue.Rebase> asked = MergeQueue.rebasing(config);
            Map<String, Tend.Retry> retries = Tend.retries(config);
            Set<String> busy = Tend.busyLanes(MergeQueue.all(config));
            v.rows().forEach(r -> {
                String would = r.queued() == null ? Tend.wouldDo(r.pr(), retries.get(r.pr().key()), asked.get(r.pr().key()),
                        busy.contains(r.pr().lane()), r.check()) : null;
                System.out.println(r.pr().key() + "  " + r.pr().title() + "\n    " + r.flow().say()
                        + (r.flow().next() == null ? "" : "  [" + r.flow().next().label() + "]")
                        + (would == null ? "" : "\n    The elf " + would + "."));
            });
            System.out.println(v.rows().size() + " open Dependabot PR(s); nothing written");
            return errors.isEmpty() ? 0 : 1;
        }
        // Only what this read found is tended: the rows kept from the read before may be stale.
        List<Bumps.Pr> fresh = prs.stream().filter(p -> heads.containsKey(p.key())).toList();
        // The checks first: a bump checked safe now has its CI runs approved in this same refresh.
        checkSome(config, fresh);
        List<Bumps.Pr> tended = Tend.run(config, fresh);
        Map<String, Bumps.Pr> after = new HashMap<>();
        tended.forEach(p -> after.put(p.key(), p));
        List<Bumps.Pr> now = prs.stream().map(p -> after.getOrDefault(p.key(), p)).toList();
        cleanUp(config, heads, errors);
        View v = build(config, Instant.now(), now, errors);
        writeJson(config.boardPath(), v);
        long queued = v.queue().stream().filter(q -> q.stopped() == null).count();
        long ready = v.rows().stream().filter(r -> Flows.READY.equals(r.flow().waiting())).count();
        System.out.println(v.rows().size() + " open Dependabot PR(s) in " + String.join(", ", config.repos)
                + (ready > 0 ? ", " + ready + " ready to merge" : "")
                + (queued > 0 ? ", " + queued + " in the merge queue" : "")
                + (errors.isEmpty() ? "" : "; couldn't read " + String.join("; ", errors)));
        return errors.isEmpty() ? 0 : 1;
    }

    /**
     * The safety check for up to {@link SafetyCheck#PER_REFRESH} bumps not checked at their version yet: the ones
     * closest to ready first (CI passed, then running), oldest first. Outside the lock: each takes some seconds.
     */
    static void checkSome(Config config, List<Bumps.Pr> prs) {
        Map<String, SafetyCheck.Check> checks = SafetyCheck.all(config);
        List<Bumps.Pr> todo = prs.stream()
                .filter(p -> !Boolean.TRUE.equals(p.draft()) && SafetyCheck.current(checks, p) == null)
                .sorted(Comparator.comparingInt((Bumps.Pr p) -> "SUCCESS".equals(p.ci()) ? 0 : Bumps.ciFailing(p) ? 2 : 1)
                        .thenComparing(Bumps.Pr::createdAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .limit(SafetyCheck.PER_REFRESH).toList();
        for (Bumps.Pr pr : todo) {
            try {
                SafetyCheck.Check c = SafetyCheck.ask(new GitHub(config, pr.repo()), pr);
                if (c == null) continue;
                SafetyCheck.put(config, pr.key(), c);
                System.out.println(pr.key() + ": the safety check says " + c.verdict() + (c.reason() == null ? "" : " (" + c.reason() + ")"));
            } catch (IOException | RuntimeException e) {
                System.err.println("Couldn't check " + pr.key() + ": " + e.getMessage());
            }
        }
    }

    /**
     * What the elf keeps per bump, for bumps no longer open (repos that couldn't be read keep theirs): rebases asked
     * at a head that moved since, stopped queue items, retries, checks and Later.
     */
    private static void cleanUp(Config config, Map<String, String> heads, List<String> errors) throws IOException {
        java.util.function.Predicate<String> read = key -> errors.stream().noneMatch(x -> x.startsWith(key.substring(0, key.indexOf('#')) + ": "));
        MergeQueue.locked(config, () -> {
            Map<String, MergeQueue.Rebase> asked = new HashMap<>(MergeQueue.rebasing(config));
            if (asked.entrySet().removeIf(e -> read.test(e.getKey()) && !e.getValue().sha().equals(heads.get(e.getKey())))) {
                writeJson(config.rebasingPath(), asked);
            }
            List<MergeQueue.Item> queue = new ArrayList<>(MergeQueue.all(config));
            if (queue.removeIf(q -> read.test(q.key()) && !heads.containsKey(q.key()))) MergeQueue.write(config, queue);
            Map<String, Tend.Retry> retries = new HashMap<>(Tend.retries(config));
            if (retries.keySet().removeIf(k -> read.test(k) && !heads.containsKey(k))) writeJson(config.retriesPath(), retries);
            Map<String, SafetyCheck.Check> checks = new HashMap<>(SafetyCheck.all(config));
            if (checks.keySet().removeIf(k -> read.test(k) && !heads.containsKey(k))) writeJson(config.checksPath(), checks);
            Map<String, String> later = new HashMap<>(Later.all(config));
            if (later.keySet().removeIf(k -> read.test(k) && !heads.containsKey(k))) writeJson(config.laterPath(), later);
            return null;
        });
    }

    /** The board with its PRs changed by {@code change} (after one of the tab's buttons), joined again. */
    static void update(Config config, UnaryOperator<List<Bumps.Pr>> change) {
        try {
            View before = read(config.boardPath());
            if (before == null) return;
            List<Bumps.Pr> prs = change.apply(before.rows().stream().map(Row::pr).toList());
            View v = build(config, before.fetchedAt(), prs, before.errors());
            writeJson(config.boardPath(), new View(before.login(), before.fetchedAt(), before.repos(), v.rows(), v.queue(), v.merged(), v.errors()));
        } catch (IOException | RuntimeException e) {
            System.err.println("Could not update the board: " + e.getMessage());
        }
    }

    static void rejoin(Config config) {
        update(config, prs -> prs);
    }

    static View build(Config config, Instant fetchedAt, List<Bumps.Pr> prs, List<String> errors) {
        Instant now = Instant.now();
        View v = build(config.githubUser, fetchedAt, config.repos, prs, MergeQueue.all(config), MergeQueue.rebasing(config),
                MergeQueue.merged(config), Tend.retries(config), SafetyCheck.all(config), Later.all(config), errors, now);
        Map<String, String> looking = Look.busy(config, now);
        if (looking.isEmpty()) return v;
        List<Row> rows = v.rows().stream().map(r -> looking.containsKey(r.pr().key())
                ? new Row(r.pr(), r.bump(), r.check(), r.flow(), r.queued(), true) : r).toList();
        return new View(v.login(), v.fetchedAt(), v.repos(), rows, v.queue(), v.merged(), v.errors());
    }

    static View build(String login, Instant fetchedAt, List<String> repos, List<Bumps.Pr> prs, List<MergeQueue.Item> queue,
                      Map<String, MergeQueue.Rebase> asked, List<MergeQueue.Merged> merged, Map<String, Tend.Retry> retries,
                      Map<String, SafetyCheck.Check> checks, Map<String, String> later, List<String> errors, Instant now) {
        Set<String> busy = Tend.busyLanes(queue);
        Map<String, Bumps.Pr> open = new HashMap<>();
        prs.forEach(p -> open.put(p.key(), p));
        // A queued bump that isn't open any more is off the board: it's gone from the queue at the next refresh.
        List<MergeQueue.Item> shown = queue.stream().filter(q -> open.containsKey(q.key())).toList();
        List<Row> rows = new ArrayList<>();
        for (Bumps.Pr pr : prs) {
            MergeQueue.Item q = shown.stream().filter(x -> x.key().equals(pr.key())).findFirst().orElse(null);
            int position = 0;
            if (q != null && q.stopped() == null) {
                for (MergeQueue.Item x : shown) {
                    if (x.stopped() != null || !x.lane().equals(q.lane())) continue;
                    position++;
                    if (x == q) break;
                }
            }
            Bumps.Bump bump = Bumps.bump(pr.title());
            SafetyCheck.Check check = SafetyCheck.current(checks, pr);
            Flows.Flow flow = Flows.flow(pr, bump, new Flows.Ctx(now, asked.get(pr.key()), q, position,
                    retries.get(pr.key()), check, busy.contains(pr.lane())));
            rows.add(new Row(pr, bump, check, Later.current(later, pr) ? Flows.later(flow) : flow, q));
        }
        rows.sort(Comparator.comparing((Row x) -> x.pr().updatedAt(), Comparator.nullsLast(Comparator.reverseOrder())));
        return new View(login, fetchedAt, List.copyOf(repos), List.copyOf(rows), shown, merged, List.copyOf(errors));
    }

    static View read(Path file) throws IOException {
        return Files.isRegularFile(file) ? MAPPER.readValue(file.toFile(), View.class) : null;
    }

    /** Written next to the file, then moved into place: the tab never reads half of one. */
    static void writeJson(Path file, Object value) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling("." + file.getFileName() + ".tmp");
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), value);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
