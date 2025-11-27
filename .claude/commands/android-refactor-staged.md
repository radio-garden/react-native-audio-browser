---
allowed-tools: Bash(git status:*), Bash(git diff:*), Read, Edit
description: Refactor staged Kotlin code to be more idiomatic
---

## Context

- Current git status: !`git status`
- Current `git diff --staged`: !`git diff --staged`

## Steps

1. Look at the **staged** changes and identify any Kotlin files
2. Read the full context of each changed Kotlin file
3. Refactor the staged code to be more idiomatic Kotlin:
   - Use `let`, `run`, `apply`, `also`, `with` scope functions appropriately
   - Prefer `when` over `if-else` chains
   - Use Elvis operator `?:` for null defaults
   - Use `takeIf`/`takeUnless` where appropriate
   - Prefer expression bodies for simple functions
   - Use destructuring declarations where helpful
   - Prefer `mapNotNull`, `filterNotNull`, `firstOrNull` etc.
   - Use `require`/`check` for preconditions
   - Prefer immutable `val` over mutable `var`
   - Use data classes appropriately
   - Leverage extension functions for cleaner APIs

## Guidelines

- Only refactor Kotlin files (`.kt`)
- Preserve the existing behavior - this is refactoring, not feature changes
- Don't over-engineer - only apply idioms where they improve readability
- Keep changes focused on the staged diff, don't refactor unrelated code
- Always add proper imports at the top of the file - never use fully-qualified inline names
