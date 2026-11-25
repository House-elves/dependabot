import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An open Dependabot PR as the tab shows it, read from GitHub's GraphQL answer the way the GitHub and mvnpm elves read
 * theirs, so CI, the merge state and the runs waiting for approval mean the same on every tab.
 */
final class Bumps {

    private Bumps() {}

    /** CI waits for you to approve its workflow runs (a fork's, or one held at a deployment environment you review). */
    static final String AWAITING_APPROVAL = "AWAITING_APPROVAL";

    static final Set<String> RUNNING_CHECKS = Set.of("QUEUED", "IN_PROGRESS", "PENDING", "WAITING", "REQUESTED");
    static final Set<String> PASSED = Set.of("SUCCESS", "NEUTRAL", "SKIPPED");

    /** "Bump x from 1 to 2", "build(deps): bump x from 1 to 2", "[2.x] Bump x from 1 to 2 in /ui". */
    static final Pattern BUMP = Pattern.compile("(?i)\\bbump (\\S+) from (\\S+) to (\\S+)");

    /**
     * @param base           the branch it merges into
     * @param defaultBranch  the repo's default branch: a bump into another one (2.x) says so on its row
     * @param ci             SUCCESS, FAILURE, ERROR, PENDING, EXPECTED, AWAITING_APPROVAL, or null when no checks ran
     * @param mergeable      MERGEABLE, CONFLICTING or UNKNOWN
     * @param mergeState     GitHub's mergeStateStatus: CLEAN (or HAS_HOOKS) when every required check passed, BEHIND
     *                       when the repo wants it up to date with its base first
     * @param approved       approved by someone with write access and not blocked by a change request
     */
    record Pr(String repo, int number, String title, String url, String author, String headRef, String headSha,
              String base, String defaultBranch, String ci, String reviewDecision, String mergeable, String mergeState,
              Boolean approved, Boolean draft, Instant createdAt, Instant updatedAt) {

        String key() { return repo + "#" + number; }

        /** Bumps into the same branch of the same repo merge one after the other: each merge moves that base. */
        String lane() { return repo + "@" + base; }

        Pr withCi(String newCi) {
            return new Pr(repo, number, title, url, author, headRef, headSha, base, defaultBranch, newCi, reviewDecision,
                    mergeable, "UNKNOWN", approved, draft, createdAt, updatedAt);
        }
    }

    /** What a bump moves: null when the title isn't one bump (a grouped update). kind: major, minor, patch or other. */
    record Bump(String dependency, String from, String to, String kind) {

        boolean major() { return "major".equals(kind); }
    }

    /** A workflow run waiting for approval, with the deployment environments it waits at (none: a fork's run). */
    record Approval(long run, List<Long> environments) {
    }

    static boolean isDependabot(String author) {
        return author != null && (author.equals("dependabot") || author.equals("app/dependabot") || author.equals("dependabot[bot]"));
    }

    static Pr pr(String repo, JsonNode n) {
        JsonNode commit = n.path("commits").path("nodes").path(0).path("commit");
        String reviewDecision = text(n.path("reviewDecision"));
        List<String> writers = new ArrayList<>();
        n.path("latestOpinionatedReviews").path("nodes").forEach(r -> writers.add(r.path("state").asText()));
        boolean approved = reviewDecision != null ? reviewDecision.equals("APPROVED")
                : writers.contains("APPROVED") && !writers.contains("CHANGES_REQUESTED");
        return new Pr(repo, n.path("number").asInt(), n.path("title").asText(), n.path("url").asText(),
                n.path("author").path("login").asText(""), text(n.path("headRefName")), text(n.path("headRefOid")),
                text(n.path("baseRefName")), text(n.path("baseRepository").path("defaultBranchRef").path("name")),
                awaitingApproval(commit).isEmpty() ? commitCi(commit) : AWAITING_APPROVAL, reviewDecision,
                text(n.path("mergeable")), text(n.path("mergeStateStatus")), approved, n.path("isDraft").asBoolean(),
                instant(n.path("createdAt")), instant(n.path("updatedAt")));
    }

    static Bump bump(String title) {
        Matcher m = BUMP.matcher(title == null ? "" : title);
        if (!m.find()) return null;
        return new Bump(m.group(1), m.group(2), m.group(3), kind(m.group(2), m.group(3)));
    }

    /** Which part of the version moved: major (1.x to 2.x; 0.1 to 0.2 too, as semver has it), minor, patch, or other. */
    static String kind(String from, String to) {
        List<Integer> a = numbers(from), b = numbers(to);
        if (a.isEmpty() || b.isEmpty()) return "other";
        // 0.x: the minor is the major.
        int lead = a.get(0) == 0 && b.get(0) == 0 && a.size() > 1 && b.size() > 1 ? 1 : 0;
        for (int i = 0; i < Math.max(a.size(), b.size()); i++) {
            int x = i < a.size() ? a.get(i) : 0, y = i < b.size() ? b.get(i) : 0;
            if (x != y) return i <= lead ? "major" : i == lead + 1 ? "minor" : "patch";
        }
        return "other";
    }

    /** The leading dot-separated numbers of a version: v6.4.1 is [6, 4, 1], 2.0.0-RC1 is [2, 0, 0]. */
    private static List<Integer> numbers(String v) {
        List<Integer> out = new ArrayList<>();
        Matcher m = Pattern.compile("^v?(\\d{1,9}(?:\\.\\d{1,9})*)").matcher(v);
        if (!m.find()) return out;
        for (String p : m.group(1).split("\\.")) out.add(Integer.parseInt(p));
        return out;
    }

    /** CI as the board shows it: PENDING while any check is still queued or running, otherwise GitHub's rollup. */
    static String ciState(JsonNode rollup) {
        if (!rollup.isObject()) return null;
        JsonNode contexts = rollup.path("contexts");
        for (JsonNode c : contexts.path("checkRunCountsByState")) {
            if (RUNNING_CHECKS.contains(c.path("state").asText()) && c.path("count").asInt() > 0) return "PENDING";
        }
        for (JsonNode c : contexts.path("statusContextCountsByState")) {
            if (c.path("state").asText().equals("PENDING") && c.path("count").asInt() > 0) return "PENDING";
        }
        return rollup.path("state").asText(null);
    }

    /** {@link #ciState} of a commit, SUCCESS when it only fails on runs a newer run of the same workflow replaced. */
    static String commitCi(JsonNode commit) {
        String state = ciState(commit.path("statusCheckRollup"));
        return ("FAILURE".equals(state) || "ERROR".equals(state)) && passedOnRerun(commit) ? "SUCCESS" : state;
    }

    /** The workflow runs on a commit that a newer run of the same workflow replaced. */
    static Set<Long> replacedRuns(JsonNode commit) {
        Map<Long, Long> newest = new HashMap<>();
        for (JsonNode suite : commit.path("checkSuites").path("nodes")) {
            JsonNode run = suite.path("workflowRun");
            if (!run.path("databaseId").isNumber() || !run.path("workflow").path("databaseId").isNumber()) continue;
            newest.merge(run.path("workflow").path("databaseId").asLong(), run.path("databaseId").asLong(), Math::max);
        }
        Set<Long> replaced = new HashSet<>();
        for (JsonNode suite : commit.path("checkSuites").path("nodes")) {
            JsonNode run = suite.path("workflowRun");
            if (!run.path("databaseId").isNumber() || !run.path("workflow").path("databaseId").isNumber()) continue;
            long id = run.path("databaseId").asLong();
            if (id != newest.get(run.path("workflow").path("databaseId").asLong())) replaced.add(id);
        }
        return replaced;
    }

    /** Every check passed once the replaced runs are left out: at least one was, and nothing else failed. */
    static boolean passedOnRerun(JsonNode commit) {
        Set<Long> replaced = replacedRuns(commit);
        if (replaced.isEmpty()) return false;
        if (!commit.path("status").path("state").asText("SUCCESS").equals("SUCCESS")) return false;
        for (JsonNode suite : commit.path("checkSuites").path("nodes")) {
            JsonNode run = suite.path("workflowRun").path("databaseId");
            if (run.isNumber() && replaced.contains(run.asLong())) continue;
            String conclusion = suite.path("conclusion").asText("");
            if (conclusion.isEmpty() && !run.isNumber()) continue;
            if (!PASSED.contains(conclusion)) return false;
        }
        return true;
    }

    /** Workflow runs on this commit waiting for you to approve them: a fork's, or one held at an environment you review. */
    static List<Approval> awaitingApproval(JsonNode commit) {
        List<Approval> runs = new ArrayList<>();
        for (JsonNode suite : commit.path("checkSuites").path("nodes")) {
            JsonNode run = suite.path("workflowRun").path("databaseId");
            if (!run.isNumber()) continue;
            if (suite.path("conclusion").asText().equals("ACTION_REQUIRED")) {
                runs.add(new Approval(run.asLong(), List.of()));
                continue;
            }
            List<Long> environments = new ArrayList<>();
            for (JsonNode d : suite.path("workflowRun").path("pendingDeploymentRequests").path("nodes")) {
                JsonNode env = d.path("environment").path("databaseId");
                if (d.path("currentUserCanApprove").asBoolean() && env.isNumber()) environments.add(env.asLong());
            }
            if (!environments.isEmpty()) runs.add(new Approval(run.asLong(), List.copyOf(environments)));
        }
        return runs;
    }

    // ---- where it stands: the same words the queue and the rows use ----

    static boolean ciFailing(Pr i) {
        return "FAILURE".equals(i.ci()) || "ERROR".equals(i.ci());
    }

    static boolean ciRunning(Pr i) {
        return "PENDING".equals(i.ci()) || "EXPECTED".equals(i.ci());
    }

    static boolean conflicts(Pr i) {
        return "CONFLICTING".equals(i.mergeable()) || "DIRTY".equals(i.mergeState());
    }

    /** The repo wants it up to date with its base before it merges, and it isn't. */
    static boolean behind(Pr i) {
        return "BEHIND".equals(i.mergeState()) && !conflicts(i);
    }

    /** It can merge now: CI passed and GitHub says every required check passed and nothing else blocks it. */
    static boolean green(Pr i) {
        return "SUCCESS".equals(i.ci()) && !Boolean.TRUE.equals(i.draft())
                && ("CLEAN".equals(i.mergeState()) || "HAS_HOOKS".equals(i.mergeState()));
    }

    /** Green except that the repo wants a review first: your approval is the only thing missing. */
    static boolean greenButReview(Pr i) {
        return "SUCCESS".equals(i.ci()) && !Boolean.TRUE.equals(i.draft()) && "BLOCKED".equals(i.mergeState())
                && "REVIEW_REQUIRED".equals(i.reviewDecision());
    }

    private static String text(JsonNode n) {
        return n.isTextual() ? n.asText() : null;
    }

    private static Instant instant(JsonNode n) {
        return n.isTextual() ? Instant.parse(n.asText()) : null;
    }
}
