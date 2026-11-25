import java.time.Instant;

/** Test builders for {@link Bumps.Pr}. */
final class Prs {

    static final Instant NOW = Instant.parse("2026-11-25T00:00:00Z");
    static final String SHA = "a".repeat(40);
    static final String SHA2 = "b".repeat(40);

    private Prs() {}

    /** An open Dependabot bump with the given CI and merge state. */
    static Bumps.Pr pr(String ci, String mergeState, String mergeable) {
        return pr(ci, mergeState, mergeable, null, SHA);
    }

    static Bumps.Pr pr(String ci, String mergeState, String mergeable, String reviewDecision, String sha) {
        return new Bumps.Pr("smallrye/smallrye-graphql", 2813, "Bump org.mongodb:bson from 5.14.0 to 5.15.0",
                "https://github.com/smallrye/smallrye-graphql/pull/2813", "dependabot", "dependabot/maven/main/org.mongodb-bson-5.15.0",
                sha, "main", "main", ci, reviewDecision, mergeable, mergeState, false, false,
                Instant.parse("2026-11-20T00:00:00Z"), Instant.parse("2026-11-24T00:00:00Z"));
    }

    static MergeQueue.Item queued(Bumps.Pr pr) {
        return new MergeQueue.Item(pr.repo(), pr.number(), pr.title(), pr.base(), NOW, pr.headSha(), NOW, "Queued.", null);
    }
}
