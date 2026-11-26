import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * What the tab's buttons do. The writes to GitHub (approve CI, re-run it, merge, merge all, queue, rebase, close) are
 * actions with ask: true in elf.yaml, so the desk shows its confirm bar and nothing is written until you click there. The values
 * come as JSON on stdin (the runner checked them against elf.yaml first); the answer is the last line printed. Each
 * reads the PR from GitHub again first and refuses what doesn't fit: only open Dependabot PRs in REPOS that aren't
 * the mvnpm elf's, and a merge only at the head you saw, with CI green.
 */
final class Actions {

    private Actions() {}

    static int run(Config config, String action) throws IOException {
        String stdin = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
        return run(config, action, Board.MAPPER.readTree(stdin.isBlank() ? "{}" : stdin));
    }

    static int run(Config config, String action, JsonNode values) {
        try {
            ObjectNode answer = switch (action) {
                case "merge-all" -> mergeAll(config);
                case "unqueue" -> unqueue(config, key(config, values));
                default -> {
                    String repo = repo(config, values);
                    int number = number(values);
                    GitHub gh = new GitHub(config, repo);
                    yield switch (action) {
                        case "approve-ci" -> approveCi(config, gh, repo, number);
                        case "ci-report" -> {
                            pull(gh, repo, number);
                            yield gh.ci(number);
                        }
                        case "ci-rerun" -> ciRerun(config, gh, repo, number);
                        case "merge" -> merge(config, gh, repo, number, values.path("headSha").asText(""));
                        case "queue" -> queue(config, gh, repo, number);
                        case "dependabot-rebase" -> rebase(config, gh, repo, number);
                        case "close" -> close(config, gh, repo, number);
                        case "recheck" -> recheck(config, gh, repo, number);
                        default -> throw new IllegalArgumentException("Not one of the Dependabot elf's actions: " + action);
                    };
                }
            };
            System.out.println(Board.MAPPER.writeValueAsString(answer));
            return 0;
        } catch (IOException | RuntimeException e) {
            System.err.println(e.getMessage());
            return 1;
        }
    }

    private static String repo(Config config, JsonNode values) {
        String repo = config.repo(values.path("repo").asText(""));
        if (repo == null) throw new IllegalArgumentException(values.path("repo").asText() + " isn't one of the repos in its settings");
        return repo;
    }

    private static int number(JsonNode values) {
        int number = values.path("pr").asInt(0);
        if (number <= 0) throw new IllegalArgumentException("Which PR?");
        return number;
    }

    private static String key(Config config, JsonNode values) {
        return repo(config, values) + "#" + number(values);
    }

    /** The PR as GitHub has it now, if it's an open Dependabot PR this elf handles; the repository node it came in. */
    record Pull(JsonNode repository, JsonNode node, Bumps.Pr pr) {
    }

    static Pull pull(GitHub gh, String repo, int number) throws IOException {
        JsonNode r = gh.pull(number);
        JsonNode n = r.path("pullRequest");
        if (!n.isObject()) throw new IllegalArgumentException(repo + "#" + number + " isn't a pull request");
        if (!"OPEN".equals(n.path("state").asText())) throw new IllegalArgumentException(repo + "#" + number + " isn't open any more");
        Bumps.Pr pr = Bumps.pr(repo, n);
        if (!Bumps.isDependabot(pr.author())) throw new IllegalArgumentException(repo + "#" + number + " isn't Dependabot's");
        if (MvnpmElf.handles(MvnpmElf.repos(), repo, pr.title(), pr.headRef())) {
            throw new IllegalArgumentException(repo + "#" + number + " is an mvnpm bump: the mvnpm tab handles it");
        }
        return new Pull(r, n, pr);
    }

    static ObjectNode approveCi(Config config, GitHub gh, String repo, int number) throws IOException {
        Pull p = pull(gh, repo, number);
        int n = approveRuns(gh, repo, p.node(), false);
        if (n == 0) throw new IllegalStateException("No workflow runs on " + repo + "#" + number + " wait for approval.");
        System.out.println("Approved " + n + " workflow run(s) on " + repo + "#" + number);
        Board.update(config, prs -> prs.stream().map(x -> x.key().equals(p.pr().key()) ? x.withCi("PENDING") : x).toList());
        return Board.MAPPER.createObjectNode().put("approved", n);
    }

    /**
     * Approves the workflow runs waiting on the PR's head (a GraphQL pullRequest node). How many it approved.
     *
     * @param plainOnly only runs held for a maintainer, none held at a deployment environment (the elf's own approvals)
     */
    static int approveRuns(GitHub gh, String repo, JsonNode node, boolean plainOnly) throws IOException {
        List<Bumps.Approval> runs = Bumps.awaitingApproval(node.path("commits").path("nodes").path(0).path("commit"));
        if (plainOnly) runs = runs.stream().filter(r -> r.environments().isEmpty()).toList();
        for (Bumps.Approval run : runs) {
            if (run.environments().isEmpty()) {
                gh.write("POST", "/repos/" + repo + "/actions/runs/" + run.run() + "/approve", Board.MAPPER.createObjectNode());
            } else {
                ObjectNode body = Board.MAPPER.createObjectNode().put("state", "approved").put("comment", "");
                run.environments().forEach(body.putArray("environment_ids")::add);
                gh.write("POST", "/repos/" + repo + "/actions/runs/" + run.run() + "/pending_deployments", body);
            }
        }
        return runs.size();
    }

    /** Re-runs the failed jobs of each failed GitHub Actions run on the PR's head, as the GitHub elf's Retry does. */
    static ObjectNode ciRerun(Config config, GitHub gh, String repo, int number) throws IOException {
        Pull p = pull(gh, repo, number);
        ObjectNode ci = gh.ci(number);
        if (!ci.path("canRerun").asBoolean()) throw new IllegalStateException("You don't have write access to " + repo + ", which GitHub needs to re-run its workflows.");
        if (ci.path("runs").isEmpty()) throw new IllegalStateException("Nothing to re-run: the failed checks aren't GitHub Actions jobs.");
        int n = rerun(gh, repo, ci);
        System.out.println("Re-ran the failed jobs of " + n + " workflow run(s) on " + repo + "#" + number);
        Board.update(config, prs -> prs.stream().map(x -> x.key().equals(p.pr().key()) ? x.withCi("PENDING") : x).toList());
        return Board.MAPPER.createObjectNode().put("rerun", n);
    }

    /** The elf's own retry: re-runs what it can of the failed CI. 0 when nothing can be (not Actions, no write access). */
    static int rerunFailed(GitHub gh, String repo, int number) throws IOException {
        ObjectNode ci = gh.ci(number);
        return ci.path("canRerun").asBoolean() ? rerun(gh, repo, ci) : 0;
    }

    private static int rerun(GitHub gh, String repo, ObjectNode ci) throws IOException {
        for (JsonNode run : ci.path("runs")) {
            gh.write("POST", "/repos/" + repo + "/actions/runs/" + run.asLong() + "/rerun-failed-jobs", Board.MAPPER.createObjectNode());
        }
        return ci.path("runs").size();
    }

    /**
     * Merge: at the head you saw, with CI green, or failing only checks the repo doesn't require (Merge anyway). When
     * the repo wants a review, you approve it first, at that head.
     */
    static ObjectNode merge(Config config, GitHub gh, String repo, int number, String headSha) throws IOException {
        if (!GitHub.SHA.matcher(headSha).matches()) throw new IllegalArgumentException("Merge needs the head you saw");
        Pull p = pull(gh, repo, number);
        if (!headSha.equals(p.pr().headSha())) {
            throw new IllegalStateException(p.pr().key() + " changed since you saw it. Look at it again, or queue it to merge.");
        }
        if (Bumps.behind(p.pr()) || Bumps.conflicts(p.pr())) {
            throw new IllegalStateException(p.pr().key() + " is behind its base. The elf asks Dependabot to rebase it at its next refresh.");
        }
        if (Bumps.greenButReview(p.pr()) && !approvedAt(gh, number, headSha, config.githubUser)) approve(gh, p.pr());
        else if (!Bumps.green(p.pr()) && !"UNSTABLE".equals(p.pr().mergeState())) {
            throw new IllegalStateException("GitHub doesn't let " + p.pr().key() + " merge yet" + (p.pr().ci() == null ? "" : " (CI " + p.pr().ci().toLowerCase() + ")")
                    + ". The elf gets it ready at its next refresh.");
        }
        ObjectNode done = mergeNow(config, gh, p.repository(), p.pr(), false);
        // A merge in that branch: the queue's next bump there is behind now. Moved on at once. The other bumps there are
        // rebased at the next refresh, once GitHub has worked out that they're behind.
        MergeQueue.advance(config);
        Board.rejoin(config);
        return done;
    }

    /** The merge itself, pinned to the PR's head; it leaves the board and goes on the merged list. */
    static ObjectNode mergeNow(Config config, GitHub gh, JsonNode repository, Bumps.Pr pr, boolean queued) throws IOException {
        String method = method(repository);
        if (method == null) throw new IllegalStateException(pr.repo() + " allows no merge method");
        ObjectNode body = Board.MAPPER.createObjectNode().put("merge_method", method).put("sha", pr.headSha());
        JsonNode done = gh.write("PUT", "/repos/" + pr.repo() + "/pulls/" + pr.number() + "/merge", body);
        System.out.println("Merged " + pr.key() + " (" + method + ") at " + pr.headSha().substring(0, 7) + (queued ? ", from the merge queue" : ""));
        MergeQueue.addMerged(config, new MergeQueue.Merged(pr.repo(), pr.number(), pr.title(), pr.url(), Instant.now(), queued));
        Board.update(config, prs -> prs.stream().filter(x -> !x.key().equals(pr.key())).toList());
        return Board.MAPPER.createObjectNode().put("merged", done.path("sha").asText(pr.headSha())).put("method", method);
    }

    /** Approves the PR at its head, as you, with no comment: what a repo that wants a review needs before it merges. */
    static void approve(GitHub gh, Bumps.Pr pr) throws IOException {
        gh.write("POST", "/repos/" + pr.repo() + "/pulls/" + pr.number() + "/reviews",
                Board.MAPPER.createObjectNode().put("event", "APPROVE").put("commit_id", pr.headSha()));
        System.out.println("Approved " + pr.key() + " at " + pr.headSha().substring(0, 7));
    }

    /** You approved this PR at exactly this head already (reviewDecision only says whether the PR as a whole is). */
    static boolean approvedAt(GitHub gh, int number, String sha, String login) throws IOException {
        if (sha == null) return false;
        for (JsonNode page : gh.reviews(number)) {
            for (JsonNode r : page) {
                if (r.path("user").path("login").asText().equalsIgnoreCase(login) && "APPROVED".equals(r.path("state").asText())
                        && sha.equals(r.path("commit_id").asText())) return true;
            }
        }
        return false;
    }

    /** Queue to merge: at the end of its line, then the queue moves on at once (a green one merges now). */
    static ObjectNode queue(Config config, GitHub gh, String repo, int number) throws IOException {
        Pull p = pull(gh, repo, number);
        List<MergeQueue.Item> items = MergeQueue.locked(config, () -> {
            MergeQueue.add(config, List.of(p.pr()));
            MergeQueue.advanceUnlocked(config);
            return MergeQueue.all(config);
        });
        Board.rejoin(config);
        return MergeQueue.answer(items);
    }

    /**
     * Merge all: every bump the safety check said is safe and nothing waits on you for, into the merge queue in the
     * order Dependabot opened them. The queue merges them one at a time per repo and branch, rebasing each after the
     * merge before it.
     */
    static ObjectNode mergeAll(Config config) throws IOException {
        Board.View v = Board.read(config.boardPath());
        if (v == null) throw new IllegalStateException("Refresh first: there's no board yet.");
        List<Bumps.Pr> ready = new ArrayList<>();
        v.rows().stream().filter(r -> r.flow().ready()).map(Board.Row::pr)
                .sorted(java.util.Comparator.comparing(Bumps.Pr::createdAt, java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())))
                .forEach(ready::add);
        if (ready.isEmpty()) throw new IllegalStateException("No bump is checked safe and ready to merge.");
        List<MergeQueue.Item> items = MergeQueue.locked(config, () -> {
            MergeQueue.add(config, ready);
            MergeQueue.advanceUnlocked(config);
            return MergeQueue.all(config);
        });
        System.out.println("Merge all: queued " + ready.size() + " bump(s)");
        Board.rejoin(config);
        return MergeQueue.answer(items).put("added", ready.size());
    }

    /** Check again: the safety check, asked again now (it doesn't write to GitHub). */
    static ObjectNode recheck(Config config, GitHub gh, String repo, int number) throws IOException {
        Pull p = pull(gh, repo, number);
        SafetyCheck.Check c = SafetyCheck.ask(gh, p.pr());
        if (c == null) throw new IllegalStateException("The coding agent didn't give a verdict. Try again later.");
        SafetyCheck.put(config, p.pr().key(), c);
        Board.rejoin(config);
        return Board.MAPPER.createObjectNode().put("verdict", c.verdict()).put("reason", c.reason());
    }

    static ObjectNode unqueue(Config config, String key) throws IOException {
        List<MergeQueue.Item> items = MergeQueue.locked(config, () -> MergeQueue.remove(config, key));
        Board.rejoin(config);
        return MergeQueue.answer(items);
    }

    /** The merge method the repo allows: your default there when it's allowed, else the first it allows. */
    static String method(JsonNode repository) {
        List<String> allowed = new ArrayList<>();
        if (repository.path("mergeCommitAllowed").asBoolean()) allowed.add("merge");
        if (repository.path("squashMergeAllowed").asBoolean()) allowed.add("squash");
        if (repository.path("rebaseMergeAllowed").asBoolean()) allowed.add("rebase");
        String preferred = repository.path("viewerDefaultMergeMethod").asText("").toLowerCase();
        return allowed.contains(preferred) ? preferred : allowed.isEmpty() ? null : allowed.get(0);
    }

    static ObjectNode rebase(Config config, GitHub gh, String repo, int number) throws IOException {
        Pull p = pull(gh, repo, number);
        MergeQueue.locked(config, () -> {
            askRebase(config, gh, p.pr());
            return null;
        });
        Board.rejoin(config);
        return Board.MAPPER.createObjectNode().put("asked", true);
    }

    /** "@dependabot rebase" on the PR, kept so neither the row nor the queue asks again at this head. */
    static void askRebase(Config config, GitHub gh, Bumps.Pr pr) throws IOException {
        gh.write("POST", "/repos/" + pr.repo() + "/issues/" + pr.number() + "/comments",
                Board.MAPPER.createObjectNode().put("body", GitHub.REBASE));
        System.out.println("Asked Dependabot to rebase " + pr.key());
        MergeQueue.putRebase(config, pr.key(), new MergeQueue.Rebase(pr.headSha(), Instant.now()));
    }

    /** Closes a bump without merging it: Dependabot then leaves that version alone, and opens a PR for the next one. */
    static ObjectNode close(Config config, GitHub gh, String repo, int number) throws IOException {
        Pull p = pull(gh, repo, number);
        gh.write("PATCH", "/repos/" + repo + "/pulls/" + number, Board.MAPPER.createObjectNode().put("state", "closed"));
        System.out.println("Closed " + repo + "#" + number);
        MergeQueue.locked(config, () -> MergeQueue.remove(config, p.pr().key()));
        Board.update(config, prs -> prs.stream().filter(x -> !x.key().equals(p.pr().key())).toList());
        return Board.MAPPER.createObjectNode().put("closed", true);
    }
}
