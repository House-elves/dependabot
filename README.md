<img src="https://raw.githubusercontent.com/House-elves/elf-desk/main/docs/brand/mark.svg" width="72" alt="House Elves logo: a checkmark that ends in an elf's hat">

# dependabot

A [House Elves](https://github.com/House-elves) elf for Dependabot's PRs. The repos you list get a tab of their own on
Elf Desk, and their Dependabot PRs leave the GitHub elf's board. The elf gets every bump ready to merge by itself, and
you click **Merge**, or **Merge all**. It never merges by itself.

**Getting each bump ready.** At every refresh (every 10 minutes on weekdays), for each bump:

- Behind its base or in conflict, after any merge into that branch (yours on the tab, on GitHub, or the queue's): it
  comments `@dependabot rebase`, once per head.
- CI failing: it re-runs the failed jobs once. Failing again, it asks Dependabot to rebase it once, which brings any CI
  fix on the base. Failing after that, it's yours.
- A coding agent reads the bump's release notes, changelog and diff, and says whether it's safe to merge once CI passes
  or that you should look first, and why. Once per bump and version.
- CI waiting for approval (a run held for a maintainer, or at a deployment environment): it approves the runs, as
  you, only once the check said the bump is safe. When the check says to look first, approving is yours.

A bump is **Ready to merge** when it's up to date, its CI passed and the check says it's safe. One the check wants you
to look at, or that the elf can't get ready alone (CI still failing, CI waiting for your approval, a rebase Dependabot
didn't do within the hour), is under **Needs you**, with why.

**Merge all.** Bumps into the same branch all touch the same files, so merging one puts the rest behind their base.
Merge all puts every bump checked safe into a merge queue, which merges them one after the other in each repo and
branch: it asks Dependabot to rebase the next one, waits for its CI and merges it at that head. One that can't go on
stops and says why, and the ones after it carry on. While the queue works through a branch, the elf doesn't rebase
the other bumps into it: they're rebased once it's through.

- A bump into a maintenance branch (`[2.x] Bump ...`) waits in that branch's line, not main's.
- In a repo that wants a review before merging, Merge and the queue approve the PR as you first.

The mvnpm elf's bumps (`org.mvnpm`, in the repos it checks) stay on its tab, since it builds and tests them first.

- **Reads:** the open Dependabot PRs in the repos you list, with their CI, reviews and merge state, through `gh` as
  your account. Each bump's description and diff go to the coding agent for its check (it gets no tools).
- **Writes:** on GitHub, as your account, by itself: the comment `@dependabot rebase`, re-running failed CI jobs, and
  approving the CI runs of a bump checked safe, as above. Only after your click: merging a PR at the head you saw,
  approving a PR the repo wants reviewed, approving CI runs of the others, closing a PR, and Merge all, whose queue does those writes for the bumps in it. Its own files are in
  `~/.config/dependabot/`.

## Adding it

On Elf Desk, click **+** and add **Dependabot**. Set the repos whose Dependabot PRs it handles in its settings.

## Running it by hand

    mkdir -p ~/.config/dependabot
    printf 'GITHUB_USER=you\nREPOS=owner/repo\n' > ~/.config/dependabot/config
    jbang Dependabot.java --dry-run   # prints where each PR is and what it would do; writes nothing
    jbang Dependabot.java --board     # moves the queue on, tends each bump, checks a few and writes the board

The tests: `mvn test`.
