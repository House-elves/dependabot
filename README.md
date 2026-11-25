<img src="https://raw.githubusercontent.com/House-elves/elf-desk/main/docs/brand/mark.svg" width="72" alt="House Elves logo: a checkmark that ends in an elf's hat">

# dependabot

A [House Elves](https://github.com/House-elves) elf for Dependabot's PRs. The repos you list get a tab of their own on
Elf Desk, and their Dependabot PRs leave the GitHub elf's board. Each PR shows whose move it is: green and ready to
merge, behind its base, CI failing, CI running, or waiting for Dependabot.

**The merge queue.** Bumps into the same branch all touch the same files, so merging one puts the rest behind their
base. Merging them by hand means rebase, wait for CI, merge, and again for the next one. Queue them instead, one at a
time or with **Queue every ready PR**: the queue merges them one after the other in each repo and branch. When the next
one is behind, it comments `@dependabot rebase`, waits for CI to pass, and merges it at that head. It moves on at
every refresh, every 10 minutes on weekdays.

- Nothing merges that you didn't merge or queue with a click, and only once its CI has passed.
- **Queue every ready PR** takes the bumps that are green, behind their base, or have CI running. It leaves out major
  bumps and grouped updates: queue those yourself once you've read their release notes.
- A bump that can't go on stops and waits for you, and says why. That happens when CI fails, CI waits for your
  approval, Dependabot moves it to another version, or nothing moves for 6 hours. The ones after it carry on.
- A bump into a maintenance branch (`[2.x] Bump ...`) waits in that branch's line, not main's.
- In a repo that wants a review before merging, Merge and the queue approve the PR as you first.

The mvnpm elf's bumps (`org.mvnpm`, in the repos it checks) stay on its tab, since it builds and tests them first.

- **Reads:** the open Dependabot PRs in the repos you list, with their CI, reviews and merge state, through `gh` as
  your account.
- **Writes:** on GitHub, as your account, only after your click: approving CI runs, re-running failed jobs, merging a
  green PR at the head you saw, approving a PR the repo wants reviewed, closing a PR, and the comment
  `@dependabot rebase`. The merge queue does the same writes for the PRs you queued. Its own files are in
  `~/.config/dependabot/`.

## Adding it

On Elf Desk, click **+** and add **Dependabot**. Set the repos whose Dependabot PRs it handles in its settings.

## Running it by hand

    mkdir -p ~/.config/dependabot
    printf 'GITHUB_USER=you\nREPOS=owner/repo\n' > ~/.config/dependabot/config
    jbang Dependabot.java --dry-run   # prints where each PR is; merges and saves nothing
    jbang Dependabot.java --board     # moves the merge queue on and writes the tab's board

The tests: `mvn test`.
