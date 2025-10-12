---
allowed-tools: Bash(git add:*), Bash(git status:*)
description: Review the staged changes (uses ultrathink)
---

## Context

- Current git status: !`git status`
- Current `git diff --staged`: !`git diff --staged`
- Current branch: !`git branch --show-current`
- Recent commits: !`git log --oneline -10`

## Steps

- First look at the **staged** changes
- You **must** read one or more files containing the staged changes, to get more context for what changed
- Do a code review of what changed

## Guidelines

- **Native-only changes** (iOS Swift, Android Kotlin) that don't affect the TypeScript API are **not considered breaking changes**
- Only changes to the TypeScript/JavaScript API surface should be flagged as breaking
