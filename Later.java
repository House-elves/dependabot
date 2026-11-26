import com.fasterxml.jackson.core.type.TypeReference;

import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

/**
 * The bumps you put aside for later, in state/later.json, by the title they had then: while they'd be yours (ready to
 * merge, or needing you), the tab shows them under Later instead, and Merge all leaves them out. The elf still tends
 * them. Any of the tab's actions on one brings it back, and so does Dependabot moving it to another version.
 */
final class Later {

    private Later() {}

    static Map<String, String> all(Config config) {
        try {
            if (!Files.isRegularFile(config.laterPath())) return Map.of();
            return Board.MAPPER.readValue(config.laterPath().toFile(), new TypeReference<Map<String, String>>() {});
        } catch (IOException e) {
            return Map.of();
        }
    }

    /** Put aside at this title, or (title null) back. True when that changed something. */
    static boolean put(Config config, String key, String title) throws IOException {
        return MergeQueue.locked(config, () -> {
            Map<String, String> next = new HashMap<>(all(config));
            boolean changed = title == null ? next.remove(key) != null : !title.equals(next.put(key, title));
            if (changed) Board.writeJson(config.laterPath(), next);
            return changed;
        });
    }

    /** Put aside at the title it has now. */
    static boolean current(Map<String, String> later, Bumps.Pr pr) {
        return pr.title().equals(later.get(pr.key()));
    }
}
