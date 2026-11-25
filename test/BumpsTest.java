import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Reading a bump's title, the writes the elf may make, and which PRs it leaves to the mvnpm elf. */
class BumpsTest {

    @Test
    void readsTheBumpFromTheTitle() {
        Bumps.Bump b = Bumps.bump("[2.x] Bump org.mvnpm:graphql-ws from 6.4.1 to 6.4.3");
        assertEquals("org.mvnpm:graphql-ws", b.dependency());
        assertEquals("patch", b.kind());
        assertEquals("minor", Bumps.bump("build(deps): bump io.micrometer:micrometer-core from 1.17.2 to 1.18.0").kind());
        assertEquals("major", Bumps.bump("Bump io.smallrye.config:smallrye-config from 3.18.3 to 4.2.2 in /server").kind());
        assertEquals("patch", Bumps.bump("Bump org.hibernate.validator:hibernate-validator from 9.1.5.Final to 9.1.6.Final").kind());
        assertEquals("major", Bumps.bump("Bump io.smallrye:smallrye-parent from 52 to 53").kind());
        // 0.x: the minor is the major.
        assertEquals("major", Bumps.bump("Bump x from 0.31.3 to 0.32.0").kind());
        assertNull(Bumps.bump("Bump the quarkus group across 1 directory with 4 updates"));
    }

    @Test
    void writesOnlyWhatItSays() {
        ObjectMapper m = new ObjectMapper();
        String repo = "smallrye/smallrye-graphql";
        String sha = "a".repeat(40);
        assertTrue(GitHub.allowedWrite(repo, "PUT", "/repos/" + repo + "/pulls/1/merge",
                m.createObjectNode().put("merge_method", "squash").put("sha", sha)));
        assertTrue(GitHub.allowedWrite(repo, "POST", "/repos/" + repo + "/issues/1/comments", m.createObjectNode().put("body", "@dependabot rebase")));
        assertTrue(GitHub.allowedWrite(repo, "POST", "/repos/" + repo + "/pulls/1/reviews",
                m.createObjectNode().put("event", "APPROVE").put("commit_id", sha)));
        // Another repo, another comment, a review with words or that requests changes, a merge without a head: no.
        assertFalse(GitHub.allowedWrite(repo, "PUT", "/repos/other/repo/pulls/1/merge",
                m.createObjectNode().put("merge_method", "squash").put("sha", sha)));
        assertFalse(GitHub.allowedWrite(repo, "POST", "/repos/" + repo + "/issues/1/comments", m.createObjectNode().put("body", "@dependabot merge")));
        assertFalse(GitHub.allowedWrite(repo, "POST", "/repos/" + repo + "/pulls/1/reviews",
                m.createObjectNode().put("event", "REQUEST_CHANGES").put("commit_id", sha)));
        assertFalse(GitHub.allowedWrite(repo, "POST", "/repos/" + repo + "/pulls/1/reviews",
                m.createObjectNode().put("event", "APPROVE").put("commit_id", sha).put("body", "LGTM")));
        assertFalse(GitHub.allowedWrite(repo, "PUT", "/repos/" + repo + "/pulls/1/merge", m.createObjectNode().put("merge_method", "squash")));
        assertFalse(GitHub.allowedWrite(repo, "PATCH", "/repos/" + repo + "/pulls/1", m.createObjectNode().put("state", "open")));
    }

    @Test
    void leavesTheMvnpmElfsBumpsToIt(@TempDir Path dir) throws Exception {
        Path config = dir.resolve("config");
        assertEquals(Set.of(), MvnpmElf.repos(config));
        Files.writeString(config, "REPOS=quarkusio/quarkus,smallrye/smallrye-graphql\n");
        Set<String> repos = MvnpmElf.repos(config);
        assertTrue(MvnpmElf.handles(repos, "smallrye/smallrye-graphql", "[2.x] Bump org.mvnpm:graphql-ws from 6.4.1 to 6.4.3", null));
        assertFalse(MvnpmElf.handles(repos, "smallrye/smallrye-graphql", "Bump org.mongodb:bson from 5.14.0 to 5.15.0", null));
        assertFalse(MvnpmElf.handles(repos, "smallrye/smallrye-open-api", "Bump org.mvnpm:lit from 3.3.1 to 3.3.2", null));
    }

    @Test
    void readsItsConfig(@TempDir Path dir) {
        Config c = Config.of(Config.parse(List.of("GITHUB_USER=someone", "REPOS=a/b, c/d.e ,not a repo")), dir);
        assertEquals(List.of("a/b", "c/d.e"), c.repos);
        assertEquals("c/d.e", c.repo("C/D.E"));
        assertNull(c.problem());
        assertTrue(Config.of(Map.of("GITHUB_USER", "someone"), dir).problem().contains("repos"));
    }
}
