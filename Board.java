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
 * The refresh moves the merge queue on first, then reads GitHub (one search per repo, one at a time) and writes the
 * board. Everything else here only joins what the last read found with the elf's own files again, without asking
 * GitHub: after one of the tab's buttons.
 */
final class Board {

    static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private Board() {}

    /** One bump: the PR, what it bumps (null if the title isn't one bump), where it is, and its queue entry. */
    record Row(Bumps.Pr pr, Bumps.Bump bump, Flows.Flow flow, MergeQueue.Item queued) {
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

    /** Moves the queue on, reads GitHub and writes the board. 1 when a repo couldn't be read (the others are still written). */
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
            v.rows().forEach(r -> System.out.println(r.pr().key() + "  " + r.pr().title() + "\n    " + r.flow().say()
                    + (r.flow().next() == null ? "" : "  [" + r.flow().next().label() + "]")));
            System.out.println(v.rows().size() + " open Dependabot PR(s); nothing saved");
            return errors.isEmpty() ? 0 : 1;
        }
        // A rebase asked for is done once the head moved (repos that couldn't be read keep theirs).
        MergeQueue.locked(config, () -> {
            Map<String, MergeQueue.Rebase> asked = new HashMap<>(MergeQueue.rebasing(config));
            asked.entrySet().removeIf(e -> {
                String repo = e.getKey().substring(0, e.getKey().indexOf('#'));
                return errors.stream().noneMatch(x -> x.startsWith(repo + ": ")) && !e.getValue().sha().equals(heads.get(e.getKey()));
            });
            writeJson(config.rebasingPath(), asked);
            // A stopped bump the queue no longer looks at, merged or closed on GitHub since: off the queue.
            List<MergeQueue.Item> queue = new ArrayList<>(MergeQueue.all(config));
            if (queue.removeIf(q -> errors.stream().noneMatch(x -> x.startsWith(q.repo() + ": ")) && !heads.containsKey(q.key()))) {
                MergeQueue.write(config, queue);
            }
            return null;
        });
        View v = build(config, Instant.now(), prs, errors);
        writeJson(config.boardPath(), v);
        long queued = v.queue().stream().filter(q -> q.stopped() == null).count();
        System.out.println(v.rows().size() + " open Dependabot PR(s) in " + String.join(", ", config.repos)
                + (queued > 0 ? ", " + queued + " in the merge queue" : "")
                + (errors.isEmpty() ? "" : "; couldn't read " + String.join("; ", errors)));
        return errors.isEmpty() ? 0 : 1;
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
        return build(config.githubUser, fetchedAt, config.repos, prs, MergeQueue.all(config), MergeQueue.rebasing(config),
                MergeQueue.merged(config), errors, Instant.now());
    }

    static View build(String login, Instant fetchedAt, List<String> repos, List<Bumps.Pr> prs, List<MergeQueue.Item> queue,
                      Map<String, MergeQueue.Rebase> asked, List<MergeQueue.Merged> merged, List<String> errors, Instant now) {
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
            MergeQueue.Rebase r = asked.get(pr.key());
            Instant rebaseAsked = r != null && r.sha() != null && r.sha().equals(pr.headSha())
                    && r.at().isAfter(now.minus(MergeQueue.REBASE_WAIT)) ? r.at() : null;
            Bumps.Bump bump = Bumps.bump(pr.title());
            rows.add(new Row(pr, bump, Flows.flow(pr, bump, new Flows.Ctx(now, rebaseAsked, q, position)), q));
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
