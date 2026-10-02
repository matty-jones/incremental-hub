---
version: 1

planning_depth: deep
token_profile: quality

models:
  research: local-llama/blaskgpt
  planning: local-llama/blaskgpt
  discuss: local-llama/blaskgpt
  execution: local-llama/blaskgpt
  execution_simple: local-llama/blaskgpt
  completion: local-llama/blaskgpt
  validation: local-llama/blaskgpt
  subagent: local-llama/blaskgpt
  uat: local-llama/blaskgpt

reactive_execution:
  enabled: false

auto_supervisor:
  soft_timeout_minutes: 30
  idle_timeout_minutes: 10
  hard_timeout_minutes: 60
---
