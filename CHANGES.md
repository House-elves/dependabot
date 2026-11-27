# What's new

What changed for you in each version of the Dependabot elf, newest first. The desk shows the parts that are new when
it offers you an update.

## 1.3.0

- New: **Have the agent look**. When the safety check says to look first, which it does whenever the release notes
  are missing or mention a breaking change, an agent now looks in the bump's code instead of you: where the project
  uses the dependency, and whether it uses what changed. It reads a checkout of the PR, read-only. Its verdict replaces
  the safety check's, and the row says **Agent: safe** or **Agent: look first**, with what it found on hover.
- It's the main button on each such row, and **Have the agent look at N bumps** at the top goes through them all,
  one at a time. A bump the agent says is safe is ready to merge, and goes in Merge all.

## 1.2.1

- Fixed: 1.2.0 didn't start ("cannot find symbol: Later"). Its new Later file wasn't in the list of files the elf is built from.

## 1.2.0

- New: **Later**. The zz under a row's icon puts a bump that's ready or needs you aside, under a folded Later section,
  as on the GitHub board. Merge all leaves it out, and the elf still tends it. Doing anything with it, or Dependabot
  moving it to a new version, brings it back. The zz on a row in Later brings it back too.
- A bump whose CI passed but that still needs an approving review now says "Needs a review" instead of "Can't merge
  yet". Merge approves it as you first, so it was always mergeable from the tab.

## 1.1.1

- The elf no longer approves CI by itself for a run held at a deployment environment, or for a bump that changes the
  workflows (GitHub Actions bumps, or any bump touching `.github/workflows` or `.github/actions`). Those show under
  **Needs you** with Approve CI run, and the merge queue stops for them. Plain held runs of bumps checked safe are
  still approved by the elf.
- Bumps checked before this version are checked again once, to note whether they change the workflows.

## 1.1.0

- The elf now gets every bump ready to merge by itself, at each refresh. It asks Dependabot to rebase a bump that's
  behind or in conflict, so after any merge the others into that branch are rebased without a click. When CI fails it
  re-runs the failed jobs once, then asks for a rebase once.
- New: a safety check. A coding agent reads each bump's release notes and diff, and says whether it's safe or that you
  should look first, and why. A bump is **Ready to merge** once CI passed and the check says safe.
- CI waiting for approval is approved by the elf, but only for a bump the safety check said is safe. When the check
  says to look first, approving it stays your click.
- New: **Merge all** replaces Queue every ready PR. It merges every bump checked safe through the merge queue, one at a
  time per repo and branch. Majors go in too when the check says they're safe.
- A bump still failing CI after the retries can be merged anyway when GitHub allows it (the failing checks aren't
  required).

## 1.0.0

- New: Dependabot's PRs in the repos you list, on a tab of their own, by whose move each one is. They leave the GitHub
  board (with GitHub elf 1.22.0 or later). The mvnpm elf's bumps stay on its tab.
- New: the merge queue. Queue a bump, or every ready one at once, and it merges them one at a time per repo and branch.
  It asks Dependabot to rebase the next one, waits for its CI, and merges it. One that fails stops and says why.
