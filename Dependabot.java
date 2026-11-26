///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS info.picocli:picocli:4.7.6
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//DEPS com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2
//SOURCES Config.java Exec.java GitHub.java Bumps.java Flows.java MergeQueue.java Board.java Actions.java MvnpmElf.java Tend.java SafetyCheck.java Claude.java

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

/**
 * A House Elf for Dependabot's PRs in the repos you list: they come off the GitHub elf's board onto a tab of their own.
 * At each refresh it gets every bump ready for your click by itself: it asks Dependabot to rebase one that's behind or
 * in conflict (after any merge), re-runs failed CI once and then asks for a rebase, and has a coding agent check that
 * the bump is safe. You merge one with a click, or Merge all: a merge queue then merges them one at a time per repo
 * and branch, rebasing the next after each merge. Nothing merges that you didn't merge with a click.
 *
 * The mvnpm elf's bumps (org.mvnpm in its repos) stay on its tab: it builds and tests them before you merge.
 */
@Command(name = "dependabot", mixinStandardHelpOptions = true, version = "1.1.0",
        description = "Dependabot's PRs on a tab of their own, each made ready to merge, and a merge queue for Merge all.")
public class Dependabot implements Callable<Integer> {

    @Option(names = "--board", description = "Move the merge queue on, read the open Dependabot PRs, get each ready to merge and write the tab's board.")
    boolean board;

    @Option(names = "--dry-run", description = "Read the open Dependabot PRs and print where each is and what it would do: nothing is written to GitHub or saved.")
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
