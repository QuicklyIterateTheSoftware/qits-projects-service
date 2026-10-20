# qits-agent-config

This repository is **platform state**, not software (epic qits-1382). It holds what every Claude
Code agent of this project inherits:

- `memory/` — the agents' memories, indexed by `memory/MEMORY.md`.
- `skills/<name>/SKILL.md` — skills, shipped as the `qits-agent-config` plugin
  (`.claude-plugin/plugin.json`).
- `settings.json` — the plugins agents run with, under `enabledPlugins`.

## How it changes

The workspace daemon writes it: each agent gets one branch, named after its wrapper branch
(`ticket/<slug>` or `epic/<slug>`). When the work item reaches VERIFIED, that branch is merged into
`main` by a release request, and `.config/qits/release-requests.yml` makes every such request wait
for a person's approval. **Nobody edits `main` by hand.**

It is not part of the project's wrapper, is never mirrored to a backup twin, and has no CI and no
artifact.
