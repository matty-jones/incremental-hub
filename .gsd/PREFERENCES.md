---
version: 1
mode: solo
skill_discovery: auto
skill_staleness_days: 60
uat_dispatch: false
budget_enforcement: warn
git:
  main_branch: recovery/clean-gsd-continuation
  auto_push: false
  push_branches: true
  snapshots: true
  pre_merge_check: auto
  commit_type: feat
  merge_strategy: squash
  isolation: worktree
  absorb_snapshot_commits: true
stale_commit_threshold_minutes: 30
parallel:
  enabled: false
  max_workers: 1
  budget_ceiling: 9999
slice_parallel:
  enabled: false
  max_workers: 1
---
# GSD Skill Preferences

See `~/.gsd/agent/extensions/gsd/docs/preferences-reference.md` for full field documentation and examples.
