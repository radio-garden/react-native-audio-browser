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

### A stream that misbehaves on demand

The hard playback cases are server behaviours: a live stream that closes, one that stops sending without an error, a track that ends short. `corepack yarn stream-server` starts a local server that produces each of them on request, so you can reproduce one in the example app or on a device without hunting for a station that misbehaves. It needs nothing but Node.

```sh
corepack yarn stream-server     # port 7385; PORT=9000 for another
```

It serves one 10 s, 32 kbit/s MP3 tone (`scripts/stream-server/tone.mp3`) in two ways:

| URL         | What it is                                                               |
| ----------- | ------------------------------------------------------------------------ |
| `/file.mp3` | the file, with `Content-Length` and `Range` support                      |
| `/live.mp3` | the tone looped without end, sent in real time, with no `Content-Length` |

A behaviour is a query parameter on either one. `S` is seconds of audio: on the file from its start, on the live stream from the start of the response, which begins with a 2 s burst. `N` is bytes. Both count audio only, never the ICY titles interleaved with it.

| Behaviour         | Parameter                           | What the server does                                                                                                         |
| ----------------- | ----------------------------------- | ---------------------------------------------------------------------------------------------------------------------------- |
| a clean close     | `close=S` or `closeBytes=N`         | ends the connection there, on every request                                                                                  |
| a short end       | `close=S` on `/file.mp3`            | declares the whole file's `Content-Length` and closes at `S`                                                                 |
| a silent stall    | `stall=S` or `stallBytes=N`         | stops sending there and keeps the connection open                                                                            |
| a status          | `status=CODE`, with `nth=K`         | answers `CODE` (300–599) on every request, or only on the `K`th request to the same URL                                      |
| ICY titles        | `icy=S`, with `charset=latin1`      | live only: for a client that sends `Icy-MetaData: 1`, a title once a second that changes every `S` seconds; UTF-8 by default |
| a slow start      | `delay=S`                           | waits `S` seconds, at most 3600, before it answers                                                                           |
| a missing `Range` | `requireRange`, or `requireRange=N` | closes after 32 KiB, or `N` bytes, when the request has no `Range` header                                                    |

They combine: `/live.mp3?icy=5&stall=20` sends titles and then stalls, and `/live.mp3?status=503&nth=2` refuses the second request. Count every request the player makes: AVPlayer opens with a `bytes=0-1` probe and then a second request, so on iOS the first reconnect is the third. `GET /` prints this list against the address you reached it on.

`GET /log` returns every request as JSON: its attempt (how many valid requests that exact URL has had; a refused one is 0), headers, `Range`, the bytes sent and where it stands: `sending`, `stalled`, or how it ended: `complete`, `closed`, `status` or `client-gone`. `DELETE /log` clears it and the attempt counts. The terminal prints a line per request and per outcome.

The server listens on every interface. It refuses to start when another process answers on `127.0.0.1` at its port, which macOS allows beside it, and which an emulator would reach instead. Where to reach it:

- **iOS simulator:** `http://localhost:7385`
- **Android emulator:** `http://10.0.2.2:7385`, the emulator's address for the host
- **A phone on the same network:** `http://<your machine's LAN address>:7385`; the server prints the addresses at start

In `apps/example-native`, the Library tab's **Stream server** section plays the main cases from the simulator or the emulator, from the server on port 7385. A URL typed into search plays as a track: for a phone, another port, or any other combination. The example's debug builds allow plain HTTP on both platforms; an app of your own needs an App Transport Security exception on iOS and cleartext traffic allowed on Android.

Its tests (`scripts/stream-server/index.test.ts`) run with `corepack yarn test`.

## Project layout

- `src/` — the TypeScript API and the web implementation (`src/web/`)
- `src/specs/` — the Nitro spec (the canonical native API surface)
- `ios/` — Swift · `android/` — Kotlin
- `website/` — the docs site ([audiobrowser.dev](https://audiobrowser.dev))

Changing the Nitro spec means re-running `codegen` **and** implementing the change on every surface (iOS, Android, and the web implementation in `src/web/NativeAudioBrowser.ts`) before it will type-check.

## Checks

CI runs the checks in [`.github/workflows/test.yml`](.github/workflows/test.yml); these are the ones to run before opening a PR:

```sh
corepack yarn types        # TypeScript
corepack yarn ci:lint      # lint (oxlint)
corepack yarn test         # unit tests (vitest)
corepack yarn vocab-check  # GLOSSARY.md format
corepack yarn ios:test     # Swift tests (macOS)
corepack yarn android:test # Kotlin unit tests (needs the Android SDK)
```

Formatting is handled for you: `corepack yarn codegen` (through the `prepare` script it ends in) points `core.hooksPath` at `.githooks`, whose [`pre-commit`](https://github.com/radio-garden/react-native-audio-browser/blob/next/.githooks/pre-commit) formats your staged files with the repo's formatters and re-stages them.

The hook never blocks a commit, and `git commit --no-verify` skips it. Run `yarn hooks:install` if you haven't run `codegen` or `build` since cloning, or cloned before the hook existed, and `yarn format` to format the repo by hand.

## Commits & releases

Releases are automated with [semantic-release](https://semantic-release.gitbook.io/) from [Conventional Commits](https://www.conventionalcommits.org/). Please format commit messages accordingly — e.g. `feat: …`, `fix: …`, `docs: …`.

Links to the example app in `README.md` and the guides in `website/guide/` (`android-auto.md`, `carplay.md`, `browser.md`) point at the `next` branch. When the prerelease line merges to `main`, switch them all to `/tree/HEAD/` and `/blob/HEAD/` together.

## Code of Conduct

By participating, you agree to uphold our [Code of Conduct](CODE_OF_CONDUCT.md).
