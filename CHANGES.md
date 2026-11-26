# What's new

What changed for you in each version of the Dependabot elf, newest first. The desk shows the parts that are new when
it offers you an update.

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
