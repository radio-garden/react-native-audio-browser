# Consolidate iOS now-playing/player options under IOSUpdateOptions — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move the iOS player-UI options (`carPlayUpNextButton`, `carPlayNowPlayingButtons`) out of `BrowserConfiguration` and the flat `iosPlaybackRates` off the top level, consolidating all three under a new nested `ios:` bag (`IOSUpdateOptions` / `IOSOptions`) so they become runtime-updatable via `updateOptions()` — mirroring the existing `android:` taxonomy.

**Architecture:** The library already nests platform options under `android` (`AndroidUpdateOptions` / `AndroidOptions` / `NitroAndroidUpdateOptions`, wire field `NativeUpdateOptions.android`) and exposes a merged `IOSSetupOptions` at setup time. This change introduces the symmetric iOS trio (`IOSUpdateOptions` / `IOSOptions` / `NitroIOSUpdateOptions`, wire field `NativeUpdateOptions.ios`), relocates the three options into it, removes the two CarPlay fields from `BrowserConfiguration`/`NativeBrowserConfiguration`, makes the Swift CarPlay layer read them from the live `PlayerUpdateOptions` (and rebuild the now-playing template when they change at runtime), and migrates the one consumer (native-apps).

**Tech Stack:** TypeScript (vitest), Swift (Nitro modules, CarPlay), Kotlin (codegen consumer only — no behavior), Nitrogen codegen 0.35.0.

## Global Constraints

- **Pre-1.0, no shipped consumers.** Breaking the config surface is acceptable; no deploy-ordering or forward-compat concerns. (CLAUDE.md)
- **Keep the CarPlay-scoped names.** Fields stay `carPlayUpNextButton` and `carPlayNowPlayingButtons` (the issue's optional rename to `iosNowPlayingButtons` is explicitly deferred pending physical-device testing). Only their _location_ changes.
- **iOS-only behavior.** Android has no equivalent now-playing button UI. The wire types are shared, so Kotlin must still _compile_ against the regenerated types, but gets no logic changes.
- **Nested wire field name is `playbackRates`,** not `iosPlaybackRates`, inside the new `ios` bag (the `ios` prefix is redundant once nested) — matching how `AndroidUpdateOptions.skipSilence` drops no prefix.
- **CarPlay button cap stays 5** (`MAX_CARPLAY_NOW_PLAYING_BUTTONS = 5`); the validation warning moves with the field.
- After any TS change run `yarn check`. After Swift changes run the iOS build + `swift test --disable-sandbox` (ignore pre-existing PlaybackStateMachineTests failures). After codegen run `yarn build`.
- Codegen is `yarn codegen` (`node codegen/index.js … && node post-script.cjs && yarn build`). It regenerates Swift + Kotlin Nitro structs from the TS specs.

---

## File Structure

**Library — TypeScript (`~/rg/_libraries/react-native-audio-browser/`):**

- `src/features/player/options.ts` — add `IOSUpdateOptions`, `IOSOptions`, `NitroIOSUpdateOptions`; add `ios?` to `Options` / `UpdateOptions` / `NativeUpdateOptions`; remove `iosPlaybackRates` from all three; add `validateIOSUpdateOptions` + call it in `updateOptions`.
- `src/features/player/setup.ts` — redefine `IOSSetupOptions` as `NativeIOSSetupOptions & IOSUpdateOptions`; split the runtime iOS fields into `options.ios` (was flat `iosPlaybackRates`); validate buttons.
- `src/types/browser.ts` — remove `carPlayUpNextButton` + `carPlayNowPlayingButtons` from `BrowserConfiguration` (keep `CarPlayNowPlayingButton` type + `carPlayLoadingTitle`/`resolveAlbumUrl`/etc).
- `src/types/browser-native.ts` — remove the two fields from `NativeBrowserConfiguration`.
- `src/features/browser-config.ts` — remove the two from `toNativeConfig`; delete the relocated buttons validation.
- `src/features/browser-config.test.ts` — drop the `carPlayNowPlayingButtons` valid-config usage + the "warns on more than 5" test (relocated).
- `src/features/player/setup.test.ts` — update the "moves ios playbackRates" test to expect `options.ios`.
- `src/features/player/options.test.ts` — **new** unit tests for `updateOptions({ ios: … })` forwarding + button-count validation.

**Library — Swift (`ios/`):**

- `ios/Browser/BrowserConfig.swift` — remove the two CarPlay fields (struct + both inits).
- `ios/Model/PlayerUpdateOptions.swift` — add `carPlayUpNextButton` + `carPlayNowPlayingButtons`; read from `options.ios?…`; nest `toOptions()` under `ios`.
- `ios/HybridAudioBrowser.swift` — fix the empty `NativeBrowserConfiguration` initializer; add `carPlayNowPlayingButtons` / `carPlayUpNextButton` accessors; add `playerOptionsChangedEmitter` and emit it from `updateOptions`.
- `ios/CarPlay/CarPlayNowPlayingManager.swift` — read buttons + Up-Next flag from the player options accessors instead of `config`.
- `ios/CarPlay/CarPlayController.swift` — subscribe to `playerOptionsChangedEmitter` → refresh now-playing buttons + states.

**Library — Kotlin (`android/`, compile-only):**

- `android/src/main/java/com/audiobrowser/AudioBrowser.kt` — drop the two `carPlay…= null` lines from the default `NativeBrowserConfiguration`.
- `android/src/main/java/com/audiobrowser/model/PlayerUpdateOptions.kt` — change `iosPlaybackRates = null` to `ios = null` in the `Options(…)` builder.

**Consumer — native-apps (`~/rg/native-apps/`):**

- `src/player/track-player/configuration.ts` — remove `carPlayNowPlayingButtons: ['favorite']`.
- `src/player/track-player/setup.ts` — add `carPlayNowPlayingButtons: ['favorite']` to the `ios:` bag passed to `setupBrowserPlayer`.

---

## Task 1: TS type taxonomy + setup split + validation

Define the iOS option trio, relocate the three options, split the setup wiring, and relocate the button-count validation. This is one task because the TS types, `setup.ts`, and the tests are mutually dependent and only `yarn check` as a whole proves them.

**Files:**

- Modify: `src/features/player/options.ts`
- Modify: `src/features/player/setup.ts`
- Modify: `src/types/browser.ts:980-994`
- Modify: `src/types/browser-native.ts:79-80`
- Modify: `src/features/browser-config.ts:223-279,300-301`
- Test: `src/features/player/options.test.ts` (new), `src/features/player/setup.test.ts`, `src/features/browser-config.test.ts`

**Interfaces:**

- Produces:
  - `interface IOSUpdateOptions { playbackRates?: number[]; carPlayUpNextButton?: boolean; carPlayNowPlayingButtons?: CarPlayNowPlayingButton[] }`
  - `interface IOSOptions { playbackRates: number[]; carPlayUpNextButton: boolean; carPlayNowPlayingButtons: CarPlayNowPlayingButton[] }`
  - `interface NitroIOSUpdateOptions { playbackRates?: number[]; carPlayUpNextButton?: boolean; carPlayNowPlayingButtons?: CarPlayNowPlayingButton[] }`
  - `Options.ios?: IOSOptions`, `UpdateOptions.ios?: IOSUpdateOptions`, `NativeUpdateOptions.ios?: NitroIOSUpdateOptions`
  - `IOSSetupOptions = NativeIOSSetupOptions & IOSUpdateOptions`
- Consumes: `CarPlayNowPlayingButton` (exported from `src/types/browser.ts:1062`).

- [ ] **Step 1: Update the failing setup.test.ts expectation**

In `src/features/player/setup.test.ts`, the test at line ~70 currently asserts the flat shape. Replace its body so it expects the nested `options.ios`:

```typescript
it('moves ios playbackRates into the runtime options', async () => {
  await setupPlayer({
    ios: { category: 'playback', playbackRates: [0.5, 1, 2] }
  })

  const sent = payload()
  expect(sent.ios).toEqual({ category: 'playback' })
  expect(sent.options).toEqual({ ios: { playbackRates: [0.5, 1, 2] } })
})
```

- [ ] **Step 2: Write the new options.test.ts (failing)**

Create `src/features/player/options.test.ts`:

```typescript
import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { updateOptions } from './options'
import { nativeBrowser } from '../../native'

vi.mock('../../native', () => ({
  nativeBrowser: { updateOptions: vi.fn() }
}))

describe('updateOptions', () => {
  beforeEach(() => vi.clearAllMocks())
  afterEach(() => vi.restoreAllMocks())

  it('forwards a nested ios bag to the native layer unchanged', () => {
    updateOptions({ ios: { carPlayNowPlayingButtons: ['favorite'] } })
    expect(nativeBrowser.updateOptions).toHaveBeenCalledWith({
      ios: { carPlayNowPlayingButtons: ['favorite'] }
    })
  })

  it('warns on more than 5 CarPlay now-playing buttons', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    updateOptions({
      ios: {
        carPlayNowPlayingButtons: [
          'shuffle',
          'repeat',
          'favorite',
          'playback-rate',
          'shuffle',
          'repeat'
        ]
      }
    })
    expect(warn.mock.calls.some(([m]) => String(m).includes('at most 5'))).toBe(
      true
    )
  })
})
```

- [ ] **Step 3: Run the failing tests to confirm they fail**

Run: `cd ~/rg/_libraries/react-native-audio-browser && yarn vitest run src/features/player/options.test.ts src/features/player/setup.test.ts`
Expected: FAIL — `options.test.ts` has no validation yet (second test fails) and `setup.test.ts` still sees flat `iosPlaybackRates`.

- [ ] **Step 4: Add the iOS option types and nest them in options.ts**

In `src/features/player/options.ts`:

a) Add the `CarPlayNowPlayingButton` import to the top type import block:

```typescript
import type { FavoriteConfig } from '../../types'
import type { CarPlayNowPlayingButton } from '../../types/browser'
import type { RatingType } from '../metadata'
```

b) Remove `iosPlaybackRates?: number[]` from `Options` (line ~234) and replace the trailing field with an `ios` bag:

```typescript
  /**
   * The capabilities that the player has.
   * Most capabilities are enabled by default - this shows which ones are disabled.
   */
  capabilities: PlayerCapabilities

  /** iOS-specific player options with resolved defaults (only present on iOS). */
  ios?: IOSOptions
}

/**
 * iOS-specific player options with resolved defaults (from {@link getOptions}).
 * Only present on iOS.
 */
export interface IOSOptions {
  /**
   * Supported playback rates for the playback-rate capability.
   * Used by CarPlay and lock screen rate controls.
   * @default [0.5, 1.0, 1.5, 2.0]
   */
  playbackRates: number[]

  /**
   * Whether the "Up Next" button is enabled on the CarPlay Now Playing screen.
   * @default true
   */
  carPlayUpNextButton: boolean

  /**
   * Buttons shown on the CarPlay Now Playing screen (left-to-right, up to 5).
   * @default []
   */
  carPlayNowPlayingButtons: CarPlayNowPlayingButton[]
}
```

c) Add `IOSUpdateOptions` next to `AndroidUpdateOptions` (after the `AndroidUpdateOptions` interface, before `NitroAndroidUpdateOptions`):

````typescript
/**
 * iOS-specific player options that can be changed at runtime via {@link updateOptions}.
 * @platform ios
 */
export interface IOSUpdateOptions {
  /**
   * Supported playback rates for the playback-rate capability.
   * Used by CarPlay and lock screen rate controls.
   * @default [0.5, 1.0, 1.5, 2.0]
   */
  playbackRates?: number[]

  /**
   * Enable the "Up Next" button on the CarPlay Now Playing screen. The button is
   * automatically hidden when the queue has only one track.
   * @default true
   */
  carPlayUpNextButton?: boolean

  /**
   * Configure up to 5 buttons on the CarPlay Now Playing screen, arranged in
   * array order (left to right).
   *
   * @example
   * ```typescript
   * updateOptions({ ios: { carPlayNowPlayingButtons: ['repeat'] } })
   * ```
   * @default []
   */
  carPlayNowPlayingButtons?: CarPlayNowPlayingButton[]
}
````

d) Add `NitroIOSUpdateOptions` next to `NitroAndroidUpdateOptions`:

```typescript
export interface NitroIOSUpdateOptions {
  playbackRates?: number[]
  carPlayUpNextButton?: boolean
  carPlayNowPlayingButtons?: CarPlayNowPlayingButton[]
}
```

e) In `UpdateOptions`, remove `iosPlaybackRates?: number[]` (line ~379) and add an `ios` bag after `android`:

```typescript
export interface UpdateOptions {
  /** Android-specific configuration options */
  android?: AndroidUpdateOptions

  /** iOS-specific configuration options */
  ios?: IOSUpdateOptions
```

(Delete the old `iosPlaybackRates` block at the end of `UpdateOptions`.)

f) In `NativeUpdateOptions`, remove `iosPlaybackRates?: number[]` (line ~411) and add:

```typescript
export interface NativeUpdateOptions {
  /** Android-specific configuration options */
  android?: NitroAndroidUpdateOptions

  /** iOS-specific configuration options */
  ios?: NitroIOSUpdateOptions
```

(Delete the old `iosPlaybackRates` block at the end of `NativeUpdateOptions`.)

- [ ] **Step 5: Add the validation helper and call it from updateOptions**

In `src/features/player/options.ts`, add above `updateOptions`:

```typescript
const MAX_CARPLAY_NOW_PLAYING_BUTTONS = 5

/**
 * Warns when more CarPlay now-playing buttons are configured than CarPlay renders.
 * Shared by {@link updateOptions} and `setupPlayer` (both can carry `ios` options).
 */
export function validateIOSUpdateOptions(ios?: IOSUpdateOptions): void {
  const buttons = ios?.carPlayNowPlayingButtons
  if (buttons && buttons.length > MAX_CARPLAY_NOW_PLAYING_BUTTONS) {
    console.warn(
      `[react-native-audio-browser] ${buttons.length} CarPlay now-playing ` +
        `buttons configured; CarPlay shows at most ${MAX_CARPLAY_NOW_PLAYING_BUTTONS}.`
    )
  }
}
```

Then update `updateOptions`:

```typescript
export function updateOptions(options: UpdateOptions): void {
  validateIOSUpdateOptions(options.ios)
  nativeBrowser.updateOptions(options)
}
```

- [ ] **Step 6: Split the iOS runtime fields in setup.ts**

In `src/features/player/setup.ts`:

a) Extend the `options.ts` import to include the new pieces:

```typescript
import type {
  AndroidUpdateOptions,
  IOSUpdateOptions,
  NativeUpdateOptions,
  UpdateOptions
} from './options'
import { validateIOSUpdateOptions } from './options'
```

b) Replace the `IOSSetupOptions` definition (lines ~531-539) so it merges the full runtime iOS options (mirroring `AndroidSetupOptions = NativeAndroidSetupOptions & AndroidUpdateOptions`):

```typescript
/** iOS setup options: the audio-session fields plus the iOS runtime options. */
export type IOSSetupOptions = NativeIOSSetupOptions & IOSUpdateOptions
```

c) In `setupPlayer` (line ~696), replace the iOS destructure + the flat `iosPlaybackRates` line. Change:

```typescript
const { playbackRates, ...iosSetup } = ios
```

to:

```typescript
const {
  playbackRates,
  carPlayUpNextButton,
  carPlayNowPlayingButtons,
  ...iosSetup
} = ios
const iosUpdate = definedFields({
  playbackRates,
  carPlayUpNextButton,
  carPlayNowPlayingButtons
})
validateIOSUpdateOptions(iosUpdate)
```

d) In the `updates: NativeUpdateOptions = definedFields({…})` block (lines ~698-713), remove the `iosPlaybackRates: playbackRates,` line and add an `ios` nest alongside the existing `android` nest:

```typescript
const updates: NativeUpdateOptions = definedFields({
  capabilities,
  forwardJumpInterval,
  backwardJumpInterval,
  progressUpdateEventInterval,
  ...nonEmpty(
    'android',
    definedFields({
      appKilledPlaybackBehavior,
      skipSilence,
      ratingType,
      notificationButtons
    })
  ),
  ...nonEmpty('ios', iosUpdate)
})
```

(`iosSetup` continues to flow to the top-level `ios` bag at line ~718 via `...nonEmpty('ios', definedFields(iosSetup))` — that carries only the audio-session fields now.)

- [ ] **Step 7: Remove the CarPlay button fields from BrowserConfiguration**

In `src/types/browser.ts`, delete the `carPlayUpNextButton` block (lines ~967-980) and the `carPlayNowPlayingButtons` block (lines ~982-994), including the `// ─── CarPlay Options ───` divider comment that introduces them. Leave `carPlayLoadingTitle` and everything after intact. **Keep** the `CarPlayNowPlayingButton` type export at line ~1062.

In `src/types/browser-native.ts`, delete these two lines (79-80):

```typescript
  carPlayUpNextButton?: boolean
  carPlayNowPlayingButtons?: CarPlayNowPlayingButton[]
```

The `CarPlayNowPlayingButton` import at line 12 is still used by nothing else in this file after the removal — verify with `yarn check`; if it becomes unused, drop it from the import.

- [ ] **Step 8: Remove the two fields from toNativeConfig + relocate validation**

In `src/features/browser-config.ts`:

a) Delete the two lines in `toNativeConfig` (lines ~300-301):

```typescript
    carPlayUpNextButton: config.carPlayUpNextButton,
    carPlayNowPlayingButtons: config.carPlayNowPlayingButtons,
```

b) Delete the `MAX_CARPLAY_NOW_PLAYING_BUTTONS` constant (line ~223) and the buttons-count warning block inside `validateBrowserConfiguration` (lines ~273-279):

```typescript
const buttons = config.carPlayNowPlayingButtons
if (buttons && buttons.length > MAX_CARPLAY_NOW_PLAYING_BUTTONS) {
  warn(
    `${buttons.length} CarPlay now-playing buttons configured; CarPlay ` +
      `shows at most ${MAX_CARPLAY_NOW_PLAYING_BUTTONS}.`
  )
}
```

- [ ] **Step 9: Update browser-config.test.ts**

In `src/features/browser-config.test.ts`:

a) Remove `carPlayNowPlayingButtons: ['shuffle', 'repeat']` from the "is silent for a valid configuration" test (line ~77) — delete that line (and the trailing comma on the line above it stays valid since `tabs` is the last remaining field).

b) Delete the entire "warns on more than 5 CarPlay now-playing buttons" test (lines ~112-124) — it now lives in `options.test.ts`.

- [ ] **Step 10: Run the full TS check**

Run: `cd ~/rg/_libraries/react-native-audio-browser && yarn check`
Expected: PASS — types compile, lint clean, all vitest suites green (including the updated `setup.test.ts`, the new `options.test.ts`, and the trimmed `browser-config.test.ts`).

- [ ] **Step 11: Commit**

```bash
cd ~/rg/_libraries/react-native-audio-browser
git add src/features/player/options.ts src/features/player/setup.ts \
  src/types/browser.ts src/types/browser-native.ts src/features/browser-config.ts \
  src/features/player/options.test.ts src/features/player/setup.test.ts \
  src/features/browser-config.test.ts
git commit -m "feat(ios): consolidate iOS player-UI options under ios update bag (TS)"
```

---

## Task 2: Regenerate Nitro bindings + Swift readers

Regenerate the native structs from the new TS specs, then update the Swift readers so they compile and read the relocated options. iOS build is the gate.

**Files:**

- Run: `yarn codegen` (regenerates `nitrogen/generated/**`)
- Modify: `ios/Browser/BrowserConfig.swift`
- Modify: `ios/Model/PlayerUpdateOptions.swift`
- Modify: `ios/HybridAudioBrowser.swift:147-152` (empty config init), accessors near `:63`

**Interfaces:**

- Consumes: generated `NativeUpdateOptions.ios: NitroIOSUpdateOptions?`, generated `IOSOptions`, generated `Options.ios: IOSOptions?`, generated `NativeBrowserConfiguration` (now without the two CarPlay fields).
- Produces: `HybridAudioBrowser.carPlayNowPlayingButtons: [CarPlayNowPlayingButton]` and `HybridAudioBrowser.carPlayUpNextButton: Bool` accessors reading from `playerOptions`.

- [ ] **Step 1: Regenerate the Nitro bindings**

Run: `cd ~/rg/_libraries/react-native-audio-browser && yarn codegen`
Expected: completes; `nitrogen/generated/` now contains `NitroIOSUpdateOptions` (Swift + Kotlin), `IOSOptions` gains the three fields, and `NativeBrowserConfiguration` no longer has `carPlayUpNextButton` / `carPlayNowPlayingButtons`. (See the `rebuild-audio-browser` skill for the corepack-yarn / autolinking gotchas if codegen errors.)

- [ ] **Step 2: Remove the CarPlay fields from BrowserConfig.swift**

In `ios/Browser/BrowserConfig.swift`:

a) Delete the `// MARK: - CarPlay Options` section's two stored properties (lines ~44-50):

```swift
  // MARK: - CarPlay Options

  /// Enable the "Up Next" button on CarPlay Now Playing screen
  let carPlayUpNextButton: Bool

  /// Custom buttons for CarPlay Now Playing screen (e.g., .repeat, .favorite)
  let carPlayNowPlayingButtons: [CarPlayNowPlayingButton]
```

(Keep `carPlayLoadingTitle` and the rest.)

b) Delete the two init parameters (lines ~80-81):

```swift
    carPlayUpNextButton: Bool = true,
    carPlayNowPlayingButtons: [CarPlayNowPlayingButton] = [],
```

c) Delete the two assignments in the designated init (lines ~97-98):

```swift
    self.carPlayUpNextButton = carPlayUpNextButton
    self.carPlayNowPlayingButtons = carPlayNowPlayingButtons
```

d) Delete the two assignments in `init(from config:)` (lines ~117-118):

```swift
    carPlayUpNextButton = config.carPlayUpNextButton ?? true
    carPlayNowPlayingButtons = config.carPlayNowPlayingButtons ?? []
```

- [ ] **Step 3: Store + read the CarPlay options in PlayerUpdateOptions.swift**

In `ios/Model/PlayerUpdateOptions.swift`:

a) Add stored properties after `playbackRates` (line ~27):

```swift
  /// Supported playback rates for the playback-rate capability
  var playbackRates: [Double] = [0.5, 1.0, 1.5, 2.0]

  /// Enable the "Up Next" button on the CarPlay Now Playing screen
  var carPlayUpNextButton: Bool = true

  /// Custom buttons for the CarPlay Now Playing screen
  var carPlayNowPlayingButtons: [CarPlayNowPlayingButton] = []
```

b) In `update(from:)`, replace the flat playback-rates read (lines ~60-63) with a nested-`ios` read of all three:

```swift
    // Update iOS player-UI options (nested under `ios` on the wire)
    if let ios = options.ios {
      if let rates = ios.playbackRates {
        playbackRates = rates
      }
      if let upNext = ios.carPlayUpNextButton {
        carPlayUpNextButton = upNext
      }
      if let buttons = ios.carPlayNowPlayingButtons {
        carPlayNowPlayingButtons = buttons
      }
    }
```

c) In `toOptions()`, replace the flat `iosPlaybackRates: playbackRates` (line ~77) with a nested `IOSOptions`:

```swift
    return Options(
      android: nil,
      forwardJumpInterval: forwardJumpInterval,
      backwardJumpInterval: backwardJumpInterval,
      progressUpdateEventInterval: progressInterval,
      capabilities: capabilities,
      ios: IOSOptions(
        playbackRates: playbackRates,
        carPlayUpNextButton: carPlayUpNextButton,
        carPlayNowPlayingButtons: carPlayNowPlayingButtons,
      ),
    )
```

- [ ] **Step 4: Fix the empty config init + add accessors in HybridAudioBrowser.swift**

In `ios/HybridAudioBrowser.swift`:

a) Fix the default `configuration` initializer (lines ~147-152) — remove `carPlayUpNextButton: nil, carPlayNowPlayingButtons: nil,`:

```swift
  public var configuration: NativeBrowserConfiguration = .init(
    path: nil, request: nil, requestResolver: nil, browse: nil, browseResolver: nil, media: nil, artwork: nil, nowPlayingArtwork: nil, routes: nil,
    singleTrack: nil, handleTrackLoad: nil,
    androidControllerOfflineError: nil, carPlayLoadingTitle: nil, resolveAlbumUrl: nil, formatNavigationError: nil,
  ) {
```

b) Add accessors next to the existing `playbackRates` accessor (line ~63):

```swift
  var playbackRates: [Double] { playerOptions.playbackRates }
  var carPlayUpNextButton: Bool { playerOptions.carPlayUpNextButton }
  var carPlayNowPlayingButtons: [CarPlayNowPlayingButton] { playerOptions.carPlayNowPlayingButtons }
```

- [ ] **Step 5: Build iOS to confirm it compiles**

Run: `cd ~/rg/native-apps && yarn pod-install && yarn ios --simulator` (or the project's iOS build command).
Expected: build succeeds. (CarPlay manager still references `config.carPlayNowPlayingButtons` at this point — **it won't compile yet**; that is fixed in Task 3. If you build before Task 3, expect the two `config.carPlay…` references in `CarPlayNowPlayingManager.swift` to error.)

> Note: Tasks 2 and 3 are a single compile unit on the Swift side. Treat the iOS build gate as belonging to the end of Task 3; this step is a checkpoint that the generated types and the readers line up.

- [ ] **Step 6: Commit**

```bash
cd ~/rg/_libraries/react-native-audio-browser
git add nitrogen/generated ios/Browser/BrowserConfig.swift \
  ios/Model/PlayerUpdateOptions.swift ios/HybridAudioBrowser.swift
git commit -m "feat(ios): read CarPlay options + playbackRates from PlayerUpdateOptions"
```

---

## Task 3: CarPlay runtime-update wiring (Swift)

Make the CarPlay now-playing layer read the relocated options from the live player options and rebuild when they change at runtime via `updateOptions()`. This is the behavioral payoff: changing the buttons no longer requires `configureBrowser()` (which resets CarPlay to the first tab).

**Files:**

- Modify: `ios/CarPlay/CarPlayNowPlayingManager.swift:113,351`
- Modify: `ios/HybridAudioBrowser.swift` (add emitter + emit in `updateOptions`)
- Modify: `ios/CarPlay/CarPlayController.swift:240-258` (subscribe)

**Interfaces:**

- Consumes: `HybridAudioBrowser.carPlayNowPlayingButtons`, `HybridAudioBrowser.carPlayUpNextButton` (from Task 2).
- Produces: `HybridAudioBrowser.playerOptionsChangedEmitter: Emitter<Void>` emitted from `updateOptions`.

- [ ] **Step 1: Read buttons + Up-Next from player options in CarPlayNowPlayingManager.swift**

In `ios/CarPlay/CarPlayNowPlayingManager.swift`:

a) In `setupNowPlayingButtons()` (line ~113), change the buttons source from `config` to the audioBrowser accessor:

```swift
    let buttons = (audioBrowser?.isGateActive ?? false) ? [] : (audioBrowser?.carPlayNowPlayingButtons ?? [])
```

b) In `updateNowPlayingUpNextButton()` (line ~351), change the flag source from `config`:

```swift
    template.isUpNextButtonEnabled = (audioBrowser?.carPlayUpNextButton ?? true) && (audioBrowser?.getPlayer()?.tracks.count ?? 0) > 1
```

(`config` is still used for `resolveAlbumUrl` elsewhere in this file — leave the `config` accessor in place.)

- [ ] **Step 2: Add the player-options emitter and emit it on update**

In `ios/HybridAudioBrowser.swift`:

a) Add the emitter next to the other internal emitters (after line ~100, `showNowPlayingRequestedEmitter`):

```swift
  /// Fires after `updateOptions` applies a change, so external-surface managers
  /// (CarPlay) can refresh UI driven by now-runtime-updatable options
  /// (now-playing buttons, Up Next).
  public let playerOptionsChangedEmitter = Emitter<Void>()
```

b) In `updateOptions` (line ~800), emit it after `onOptionsChanged`:

```swift
      onOptionsChanged(playerOptions.toOptions())
      playerOptionsChangedEmitter.emit(())
```

- [ ] **Step 3: Subscribe to the emitter in CarPlayController.swift**

In `ios/CarPlay/CarPlayController.swift`, after the `onConfigChanged` subscription (line ~258), add a listener that refreshes the now-playing buttons + states (registered for removal like its siblings):

```swift
    // Now-playing buttons + Up Next are runtime-updatable via updateOptions();
    // refresh them when player options change (configureBrowser is no longer
    // the only path that can change them).
    let optionsToken = audioBrowser.playerOptionsChangedEmitter.addListener { [weak self] in
      Task { @MainActor in
        self?.nowPlayingManager.setupNowPlayingButtons()
        self?.nowPlayingManager.updateNowPlayingButtonStates()
      }
    }
    listenerRemovals.append { [weak audioBrowser] in
      audioBrowser?.playerOptionsChangedEmitter.removeListener(optionsToken)
    }
```

- [ ] **Step 4: Build iOS + run Swift tests**

Run: `cd ~/rg/native-apps && yarn ios --simulator`
Expected: build succeeds (the two `config.carPlay…` references from Task 2's checkpoint are now gone).

Run: `cd ~/rg/_libraries/react-native-audio-browser/ios && swift test --disable-sandbox`
Expected: PASS (ignore the pre-existing `PlaybackStateMachineTests` failures noted in project memory).

- [ ] **Step 5: Commit**

```bash
cd ~/rg/_libraries/react-native-audio-browser
git add ios/CarPlay/CarPlayNowPlayingManager.swift ios/HybridAudioBrowser.swift \
  ios/CarPlay/CarPlayController.swift
git commit -m "feat(ios): refresh CarPlay now-playing buttons on updateOptions"
```

---

## Task 4: Kotlin compile fixes (Android)

Android has no equivalent UI, but shares the regenerated wire types. These two edits keep Kotlin compiling against the new struct shapes. No behavior changes.

**Files:**

- Modify: `android/src/main/java/com/audiobrowser/AudioBrowser.kt:129-130`
- Modify: `android/src/main/java/com/audiobrowser/model/PlayerUpdateOptions.kt:102`

**Interfaces:**

- Consumes: regenerated Kotlin `NativeBrowserConfiguration` (no CarPlay fields) and `Options.ios`.

- [ ] **Step 1: Drop the removed CarPlay fields from the default config in AudioBrowser.kt**

In `android/src/main/java/com/audiobrowser/AudioBrowser.kt`, delete these two lines (~129-130) from the default `NativeBrowserConfiguration(...)` builder:

```kotlin
      carPlayUpNextButton = null,
      carPlayNowPlayingButtons = null,
```

- [ ] **Step 2: Nest the iOS field in the Options builder in PlayerUpdateOptions.kt**

In `android/src/main/java/com/audiobrowser/model/PlayerUpdateOptions.kt`, replace line ~102:

```kotlin
      iosPlaybackRates = null, // iOS-only option
```

with:

```kotlin
      ios = null, // iOS-only options
```

- [ ] **Step 3: Build Android to confirm it compiles**

Run: `cd ~/rg/native-apps && yarn android:bundle` (or the project's Android build).
Expected: build succeeds.

- [ ] **Step 4: Commit**

```bash
cd ~/rg/_libraries/react-native-audio-browser
git add android/src/main/java/com/audiobrowser/AudioBrowser.kt \
  android/src/main/java/com/audiobrowser/model/PlayerUpdateOptions.kt
git commit -m "chore(android): compile against nested ios options wire type"
```

---

## Task 5: Migrate the consumer (native-apps)

Move the one consumer usage from the browser config into the player setup's `ios` bag.

**Files:**

- Modify: `~/rg/native-apps/src/player/track-player/configuration.ts:141`
- Modify: `~/rg/native-apps/src/player/track-player/setup.ts:152-155`

- [ ] **Step 1: Remove the field from the browser configuration**

In `~/rg/native-apps/src/player/track-player/configuration.ts`, delete line ~141:

```typescript
    carPlayNowPlayingButtons: ['favorite'],
```

(Leave `carPlayLoadingTitle` and `formatNavigationError` — those remain `BrowserConfiguration` fields.)

- [ ] **Step 2: Add it to the iOS setup bag**

In `~/rg/native-apps/src/player/track-player/setup.ts`, extend the `ios:` bag passed to `setupBrowserPlayer` (lines ~152-155):

```typescript
    ios: {
      category: 'playback',
      categoryOptions: ['allowAirPlay', 'allowBluetooth', 'allowBluetoothA2DP'],
      // The CarPlay now-playing favorite heart. Set here (runtime-updatable
      // player option) rather than in configureBrowser, so it no longer resets
      // the CarPlay UI to the first tab on change.
      carPlayNowPlayingButtons: ['favorite']
    }
```

- [ ] **Step 3: Run native-apps checks**

Run: `cd ~/rg/native-apps && yarn check`
Expected: PASS — the consumer typechecks against the new library surface (this depends on the library being rebuilt; `yarn build` ran as part of `yarn codegen` in Task 2).

- [ ] **Step 4: Commit**

```bash
cd ~/rg/native-apps
git add src/player/track-player/configuration.ts src/player/track-player/setup.ts
git commit -m "feat: set CarPlay now-playing favorite via setupPlayer ios options"
```

---

## Task 6: Manual device verification + docs

Runtime CarPlay behavior can only be confirmed on a device/DHU. This task gates the "runtime-updatable" claim.

**Files:**

- Modify (if behavior notes change): `~/rg/native-apps/manual-testing/` and `~/rg/native-apps/docs/manual-tests.md`

- [ ] **Step 1: Verify the favorite button still appears on CarPlay Now Playing**

Connect CarPlay (or DHU). Play a station. Confirm the favorite heart appears on the Now Playing screen and toggles (filled/empty) on tap, exactly as before the change.

- [ ] **Step 2: Verify runtime update no longer resets the browse stack**

From a state where you've navigated CarPlay into a non-first tab / a nested browse list, trigger a player-options update (e.g. the premium-gate path, or a temporary test call to `updateOptions({ ios: { carPlayNowPlayingButtons: [] } })` then back to `['favorite']`). Confirm the now-playing buttons update **without** the CarPlay UI jumping back to the first tab.

- [ ] **Step 3: Verify Up Next still toggles with queue size**

Confirm the "Up Next" button is enabled when the queue has >1 track and hidden for a single-track queue (default `carPlayUpNextButton: true` preserved).

- [ ] **Step 4: Update manual-test docs if behavior notes changed**

If any tester walkthrough referenced these options living in `configureBrowser`, update `manual-testing/` and `docs/manual-tests.md` accordingly. Commit if changed:

```bash
cd ~/rg/native-apps
git add manual-testing docs/manual-tests.md
git commit -m "docs(manual-testing): CarPlay now-playing options now runtime-updatable"
```

- [ ] **Step 5: Close the loop on the issue**

Comment on / close `radio-garden/react-native-audio-browser#66` summarizing what shipped (steps 1+2 done; rename to `iosNowPlayingButtons` deferred — kept CarPlay-scoped names pending the lock-screen/Control-Center device test the issue calls for).

---

## Self-Review

**1. Spec coverage:**

- Issue proposal 1 (move `carPlayUpNextButton` + `carPlayNowPlayingButtons` to `IOSUpdateOptions`, runtime-updatable) → Task 1 (TS), Task 2 (Swift read), Task 3 (runtime refresh wiring). ✅
- Issue proposal 2 (nest `iosPlaybackRates` under `ios: { playbackRates }`) → Task 1 (types + setup split), Task 2 (Swift `toOptions`/`update`). ✅
- Issue proposal 3 (investigate rename) → explicitly deferred per the user's "Keep CarPlay-scoped name" decision; surfaced in Task 6 Step 5 and the Global Constraints. ✅
- Scope note "iOS only; Android no changes" → Task 4 is compile-only, no logic. ✅
- Scope note "touches TS spec + iOS readers" → Tasks 1–3. ✅

**2. Placeholder scan:** No TBD/TODO/"handle edge cases"/"similar to Task N" — each step shows exact code and exact commands. ✅

**3. Type consistency:**

- `IOSUpdateOptions` (optional fields) vs `IOSOptions` (resolved/required) vs `NitroIOSUpdateOptions` (wire, optional) — names and field sets consistent across Task 1 (TS), Task 2 (Swift `IOSOptions(...)` and `options.ios?...`), Task 4 (Kotlin `ios = null`). ✅
- Wire field is `playbackRates` inside `ios` everywhere (Swift `ios.playbackRates`, TS `nonEmpty('ios', { playbackRates, … })`, test expects `options.ios: { playbackRates }`). ✅
- `HybridAudioBrowser.carPlayNowPlayingButtons` / `.carPlayUpNextButton` accessor names match between Task 2 (definition) and Task 3 (use). ✅
- `playerOptionsChangedEmitter` name matches between Task 3 Step 2 (def) and Step 3 (subscribe). ✅
