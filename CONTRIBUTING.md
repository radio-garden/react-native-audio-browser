# Contributing

Thanks for your interest in improving **react-native-audio-browser** — issues and pull requests are welcome.

## Reporting bugs & requesting features

- Search [existing issues](https://github.com/radio-garden/react-native-audio-browser/issues) first.
- For bugs, include your **React Native version**, the **platform** (iOS / Android / web), a **minimal reproduction**, and what you expected vs. what happened.

## Development setup

This repo uses **Yarn 4** (via Corepack) and the React Native **New Architecture**.

```sh
mise install               # Node + SwiftFormat, at the versions this repo pins
corepack yarn install
corepack yarn codegen      # regenerate the Nitro bindings + build lib/
```

[mise](https://mise.jdx.dev) is optional for Node, but it is the easiest way to get the pinned **SwiftFormat**. SwiftFormat is a global tool whose output changes between releases, so `mise.toml` pins one version and `yarn ios:format` / `yarn ci:format:ios` refuse to run any other — a different build would reformat files CI considers correct. Without mise, install that exact version from the [SwiftFormat releases](https://github.com/nicklockwood/SwiftFormat/releases); the error message tells you which one.

Run an example app to try changes end-to-end:

- `apps/example-native` — the React Native app (iOS / Android, CarPlay / Android Auto)
- `apps/example-nextjs` — the web example

See each app's README for run commands.

## Project layout

- `src/` — the TypeScript API and the web implementation (`src/web/`)
- `src/specs/` — the Nitro spec (the canonical native API surface)
- `ios/` — Swift · `android/` — Kotlin
- `website/` — the docs site ([audiobrowser.dev](https://audiobrowser.dev))

Changing the Nitro spec means re-running `codegen` **and** implementing the change on every surface (iOS, Android, and the web stub) before it will type-check.

## Checks

Please run these before opening a PR:

```sh
corepack yarn types        # TypeScript
corepack yarn ci:lint      # lint (oxlint)
corepack yarn test         # unit tests (vitest)
corepack yarn ios:test     # Swift tests (macOS)
corepack yarn android:test # Kotlin unit tests (needs the Android SDK)
```

Formatting is handled for you: `yarn install` points `core.hooksPath` at `.githooks`, whose `pre-commit` runs oxfmt over your staged files and re-stages them. It never blocks a commit, and `git commit --no-verify` skips it. Run `yarn hooks:install` if you cloned before the hook existed, and `yarn format` to format the repo by hand.

## Commits & releases

Releases are automated with [semantic-release](https://semantic-release.gitbook.io/) from [Conventional Commits](https://www.conventionalcommits.org/). Please format commit messages accordingly — e.g. `feat: …`, `fix: …`, `docs: …`.

## Code of Conduct

By participating, you agree to uphold our [Code of Conduct](CODE_OF_CONDUCT.md).
