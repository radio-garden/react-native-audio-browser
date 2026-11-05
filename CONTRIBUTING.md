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

[mise](https://mise.jdx.dev) is optional for Node, but it is the easiest way to get the pinned **SwiftFormat**.

SwiftFormat is a global tool whose output changes between releases, so `mise.toml` pins one version and `yarn ios:format` / `yarn ci:format:ios` refuse to run any other — a different build would reformat files CI considers correct. Without mise, install that exact version from the [SwiftFormat releases](https://github.com/nicklockwood/SwiftFormat/releases); the error message tells you which one.

Run an example app to try changes end-to-end:

- `apps/example-native` — the React Native app (iOS / Android, CarPlay / Android Auto). Its JSON API tab browses `apps/example-nextjs`; to use that tab, start it with `corepack yarn workspace example-native api-server`
- `apps/example-nextjs` — the web example, and the API the native example's JSON API tab browses
- `apps/example-web` — the minimal Vite demo the docs site embeds: `corepack yarn workspace example-web dev`

See the README in `apps/example-native` and `apps/example-nextjs` for their run commands.

## Project layout

- `src/` — the TypeScript API and the web implementation (`src/web/`)
- `src/specs/` — the Nitro spec (the canonical native API surface)
- `ios/` — Swift · `android/` — Kotlin
- `website/` — the docs site ([audiobrowser.dev](https://audiobrowser.dev))

Changing the Nitro spec means re-running `codegen` **and** implementing the change on every surface (iOS, Android, and the web implementation in `src/web/NativeAudioBrowser.ts`) before it will type-check.

## Checks

Please run these before opening a PR:

```sh
corepack yarn types        # TypeScript
corepack yarn ci:lint      # lint (oxlint)
corepack yarn test         # unit tests (vitest)
corepack yarn ios:test     # Swift tests (macOS)
corepack yarn android:test # Kotlin unit tests (needs the Android SDK)
```

Formatting is handled for you: `corepack yarn codegen` (through the `prepare` script it ends in) points `core.hooksPath` at `.githooks`, whose `pre-commit` formats your staged files (oxfmt for JS/TS/JSON/Markdown, the pinned SwiftFormat for `ios/`, ktfmt for `android/`) and re-stages them.

The hook never blocks a commit, and `git commit --no-verify` skips it. Run `yarn hooks:install` if you haven't run `codegen` or `build` since cloning, or cloned before the hook existed, and `yarn format` to format the repo by hand.

## Commits & releases

Releases are automated with [semantic-release](https://semantic-release.gitbook.io/) from [Conventional Commits](https://www.conventionalcommits.org/). Please format commit messages accordingly — e.g. `feat: …`, `fix: …`, `docs: …`.

## Code of Conduct

By participating, you agree to uphold our [Code of Conduct](CODE_OF_CONDUCT.md).
