# What's new

What changed for you in each version of the Dependabot elf, newest first. The desk shows the parts that are new when
it offers you an update.

## 1.0.0

- New: Dependabot's PRs in the repos you list, on a tab of their own, by whose move each one is. They leave the GitHub
  board (with GitHub elf 1.22.0 or later). The mvnpm elf's bumps stay on its tab.
- New: the merge queue. Queue a bump, or every ready one at once, and it merges them one at a time per repo and branch.
  It asks Dependabot to rebase the next one, waits for its CI, and merges it. One that fails stops and says why.
