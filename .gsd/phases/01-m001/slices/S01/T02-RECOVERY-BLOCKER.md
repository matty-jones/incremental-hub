# BLOCKER — auto-mode recovery failed

Unit `execute-task` for `M001/S01/T02` failed to produce this artifact after idle recovery exhausted all retries.

**Reason**: hard recovery exhausted 1 attempts. Status: summary missing (.gsd/phases/01-m001/tasks/T02-SUMMARY.md); task checkbox unchecked in .gsd/phases/01-m001/01-01-PLAN.md; state next action still points at the timed-out task

This placeholder was written by auto-mode so the pipeline can advance.
Review and replace this file before relying on downstream artifacts.