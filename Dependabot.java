///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS info.picocli:picocli:4.7.6
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//DEPS com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2
//SOURCES Config.java Exec.java GitHub.java Bumps.java Flows.java MergeQueue.java Board.java Actions.java MvnpmElf.java

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

/**
 * A House Elf for Dependabot's PRs in the repos you list: they come off the GitHub elf's board onto a tab of their own,
 * by whose move each one is, and a merge queue merges the ones you queue one at a time: it asks Dependabot to rebase
 * the next, waits for its CI and merges it, then the next. Nothing merges that you didn't merge or queue with a click.
 *
 * The mvnpm elf's bumps (org.mvnpm in its repos) stay on its tab: it builds and tests them before you merge.
 */
@Command(name = "dependabot", mixinStandardHelpOptions = true, version = "1.0.0",
        description = "Dependabot's PRs on a tab of their own, and a merge queue that rebases and merges them one at a time.")
public class Dependabot implements Callable<Integer> {

    @Option(names = "--board", description = "Move the merge queue on, read the open Dependabot PRs and write the tab's board.")
    boolean board;

    @Option(names = "--dry-run", description = "Read the open Dependabot PRs and print where each is: nothing is merged or saved.")
    boolean dryRun;

    @Option(names = "--action", description = "One of the tab's actions, its values as JSON on stdin.")
    String action;

    public static void main(String[] args) {
        System.exit(new CommandLine(new Dependabot()).execute(args));
    }

    @Override
    public Integer call() throws Exception {
        Config config = Config.load();
        String problem = config.problem();
        if (problem != null) {
            System.err.println(problem);
            return 1;
        }
        if (action != null) return Actions.run(config, action);
        if (board) return Board.refresh(config, true);
        return Board.refresh(config, false);
    }
}
