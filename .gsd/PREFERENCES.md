---
version: 1
mode: solo
custom_instructions:
  - Never use rm -rf or git clean or other destructive shell commands to clean Gradle or KSP build artifacts during normal task execution or verification. Do not purge successful build outputs between tasks. If generated Gradle or KSP state genuinely needs a clean rebuild then use ./gradlew clean followed by the required build or test command instead.
skill_discovery: auto
skill_staleness_days: 60
uat_dispatch: false
budget_enforcement: warn
git:
  auto_push: false
  push_branches: true
  snapshots: true
  pre_merge_check: auto
  commit_type: feat
  merge_strategy: squash
  main_branch: recovery/clean-gsd-continuation
  isolation: worktree
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
