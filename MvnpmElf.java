import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The mvnpm elf (mvnpm-dependabot) checks Dependabot's org.mvnpm bumps in its own repos: it builds them and drives the
 * UI that uses them, then says whether they're safe. Those stay on its tab, so this elf leaves them alone. Installed
 * means its config file is there; only its REPOS line is read. The GitHub elf has the same rule.
 */
final class MvnpmElf {

    /** Its own default, when its config has no REPOS line. */
    static final String DEFAULT_REPOS = "quarkusio/quarkus";

    private MvnpmElf() {}

    static Path config() {
        return Path.of(System.getProperty("user.home"), ".config", "mvnpm-dependabot", "config");
    }

    /** The repos (lower case) whose mvnpm bumps the mvnpm elf checks; empty when it isn't installed. */
    static Set<String> repos() {
        return repos(config());
    }

    static Set<String> repos(Path config) {
        if (!Files.isRegularFile(config)) return Set.of();
        String value = DEFAULT_REPOS;
        try {
            for (String line : Files.readAllLines(config)) {
                if (line.startsWith("REPOS=")) value = line.substring("REPOS=".length());
            }
        } catch (Exception e) {
            // Installed but unreadable: its default, as it would itself.
        }
        Set<String> out = new HashSet<>();
        for (String r : value.split(",")) {
            if (!r.isBlank()) out.add(r.strip().toLowerCase(Locale.ROOT));
        }
        return Set.copyOf(out);
    }

    /** An org.mvnpm bump (by its title or branch: org.mvnpm:lit, org.mvnpm.at.hpcc-js:wasm) in a repo the mvnpm elf checks. */
    static boolean handles(Set<String> mvnpmRepos, String repo, String title, String headRef) {
        if (repo == null || !mvnpmRepos.contains(repo.toLowerCase(Locale.ROOT))) return false;
        return (title != null && title.contains("org.mvnpm")) || (headRef != null && headRef.contains("org.mvnpm"));
    }
}
