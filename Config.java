import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * KEY=VALUE config at ~/.config/dependabot/config, as the other elves have it. No secrets live here: the GitHub token
 * is read from gh's keyring for GITHUB_USER on each run.
 *
 * What the tab reads lives next to it, in the folders elf.yaml lists: board/ (board.json) and state/ (the merge queue,
 * the rebases asked for, what merged lately).
 */
public class Config {

    static Path dir() {
        return Path.of(System.getProperty("user.home"), ".config", "dependabot");
    }

    List<String> repos;     // owner/name of each repo whose Dependabot PRs it handles
    String githubUser;      // the account it reads, merges and comments as
    Path stateDir;
    Path boardDir;

    static Config load() {
        Path file = dir().resolve("config");
        Map<String, String> raw = new HashMap<>();
        if (Files.isRegularFile(file)) {
            try {
                raw = parse(Files.readAllLines(file));
            } catch (IOException e) {
                throw new IllegalStateException("Couldn't read " + file + ": " + e.getMessage());
            }
        }
        return of(raw, dir());
    }

    static Map<String, String> parse(List<String> lines) {
        Map<String, String> raw = new HashMap<>();
        for (String line : lines) {
            line = line.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int eq = line.indexOf('=');
            if (eq > 0) raw.put(line.substring(0, eq).strip(), line.substring(eq + 1).strip());
        }
        return raw;
    }

    static Config of(Map<String, String> raw, Path dir) {
        Config c = new Config();
        c.repos = Arrays.stream(raw.getOrDefault("REPOS", "").split(","))
                .map(String::strip).filter(r -> GitHub.REPO_NAME.matcher(r).matches()).toList();
        c.githubUser = raw.getOrDefault("GITHUB_USER", "");
        c.stateDir = dir.resolve("state");
        c.boardDir = dir.resolve("board");
        return c;
    }

    /** What to do about a config it can't work with, in the desk's words; null when it's fine. */
    String problem() {
        if (githubUser.isEmpty()) return "Set your GitHub account in the Dependabot elf's settings on the desk.";
        if (repos.isEmpty()) return "Add the repos whose Dependabot PRs it handles in its settings on the desk.";
        return null;
    }

    /** A repo in REPOS, by owner/name, any case; null if it isn't one. */
    String repo(String given) {
        for (String r : repos) if (r.equalsIgnoreCase(given)) return r;
        return null;
    }

    Path boardPath()    { return boardDir.resolve("board.json"); }
    Path queuePath()    { return stateDir.resolve("queue.json"); }
    Path rebasingPath() { return stateDir.resolve("rebasing.json"); }
    Path mergedPath()   { return stateDir.resolve("merged.json"); }
    Path retriesPath()  { return stateDir.resolve("retries.json"); }
    Path checksPath()   { return stateDir.resolve("checks.json"); }
    Path lockPath()     { return stateDir.resolve(".lock"); }
}
