---
allowed-tools: Bash(git add:*), Bash(git status:*)
description: Create a git commit from the staged changes
---

## Context

- Current git status: !`git status`
- Current `git diff --staged`: !`git diff --staged`
- Current branch: !`git branch --show-current`
- Recent commits: !`git log --oneline -10`

## Steps

- First look at the **staged** changes and think about what has changed
- Don't use `git diff --staged` the staged changes are already above
- Check that all staged files belong together
- If anything stands out as wrong (typo, code mistake, missing staged file), notify the user
- Feel free to suggest splitting up commits if files do not belong together
- Then write the commit message
- Don't use `git add` since everything we want to commit is already staged
- With linting issues, **never commit using `--no-verify`**: fix the underlying issue instead
- **IMPORTANT**: Only create the single commit for what is currently staged, then STOP. Do not automatically stage and commit other files. Wait for explicit user instruction for any additional commits.

## Style

The commit message should be structured as a conventional commit:

```
<type>[optional scope]: <description>

[optional body]

[optional footer(s)]
```

The commit contains the following structural elements:

- fix: a commit of the type fix patches a bug in your codebase (this correlates with `PATCH` in Semantic Versioning).
- feat: a commit of the type feat introduces a new feature to the codebase (this correlates with `MINOR` in Semantic Versioning).
- todo: a commit that adds or updates TODO items documenting future work
- types other than fix: and feat: are allowed, for example build:, chore:, ci:, docs:, style:, refactor:, perf:, test:, and others.
- footers other than `BREAKING CHANGE: <description>` may be provided and follow a convention similar to git trailer format.
Additional types are not mandated by the Conventional Commits specification, and have no implicit effect in Semantic Versioning (unless they include a BREAKING CHANGE). A scope may be provided to a commit's type, to provide additional contextual information and is contained within parenthesis, e.g., feat(parser): add ability to parse arrays.

## Scopes

### Platform/Technology Scopes

  - android - Android platform specific changes
  - ios - iOS platform specific changes
  - ts - TypeScript specific changes
  - web - Web platform specific changes

### Project Structure Scopes

  - example - Changes to the example app
  - package.json - Package.json and dependency changes
  - docs - Changes to library documentation

### Build/Infrastructure Scopes

  - ci - Continuous integration changes
  - nightly - Nightly build related changes
  - build - Build system changes
  - yarn - Yarn specific changes
  - ai - Changed to ai documentation, i.e. Claude.md etc

**Wording Guidelines:**

- The message and description should be as plain as possible
- Your language should be humble and practical
- Keep description concise and descriptive
- For larger commits use a markdown list to lay out the changes, but keep the description simple
- NEVER use emojis
- NEVER use words that create unproven expectations like 'comprehensive'.
