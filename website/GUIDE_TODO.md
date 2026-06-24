# Guide TODO

Missing guides, derived from `src/features/*` modules that are fully re-exported
from `src/features/index.ts` but have no dedicated guide page. The sidebar in
`.vitepress/config.ts` has no dead links among its declared entries — these are
whole feature areas without a page. (`ignoreDeadLinks: true` and the
`TODO.md` note to remove it "once all pages exist" acknowledge the gaps.)

## Already covered

- **Introduction:** getting-started, basic-usage, track, browser
- **Features:** now-playing, favorites, search, gate
- **Platform Setup:** android-auto, carplay
- **Troubleshooting:** android-certificates

## Substantial — only basics currently live in `basic-usage.md`

- [x] **Playback** (`src/features/playback/`) — `seekTo` / `seekBy` /
      `seekToLiveEdge`, `setRate`, `setVolume` / `setSystemVolume`,
      `setPlayWhenReady`, `retry`, `reset`, `useProgress` / `usePolledProgress`,
      `usePlayback`, `useSystemVolume`. `basic-usage` only shows play / pause /
      `togglePlayback`. → `guide/playback.md`
- [x] **Queue** (`src/features/queue/`) — `add` / `move` / `remove` / `skip` /
      `skipToNext` / `skipToPrevious` / `removeUpcomingTracks`, `setRepeatMode`,
      `setShuffle` / `toggleShuffle`, `load`, `useQueue` / `useActiveTrack`.
      `basic-usage` only shows `setQueue`. → `guide/queue.md`

## No coverage at all

- [x] **Sleep Timer** (`src/features/sleepTimer.ts`) — `setSleepTimer`,
      `setSleepTimerToEndOfTrack`, `clearSleepTimer`, `useSleepTimer` /
      `useSleepTimerActive`. → `guide/sleep-timer.md`
- [x] **Equalizer** (`src/features/equalizer.ts`) — `setEqualizerEnabled`,
      `setEqualizerPreset`, `setEqualizerLevels`, `useEqualizerSettings`.
      → `guide/equalizer.md`
- [x] **Errors** (`src/features/errors.ts`) — playback / navigation /
      formatted-navigation errors and their hooks. → `guide/errors.md`
- [x] **Metadata** (`src/features/metadata.ts`) — `onTrackMetadata` /
      `onTimedMetadata` / `onChapterMetadata` (ICY / stream metadata;
      `now-playing.md` references the idea but there's no guide).
      → `guide/metadata.md`
- [x] **Remote Controls** (`src/features/remoteControls.ts`) — `handleRemote*`
      and `onRemote*` (lock-screen / headphone / car control wiring).
      → `guide/remote-controls.md`
- [x] **Network** (`src/features/network.ts`) — `getOnline` / `useOnline`.
      → `guide/network.md`
- [x] **Battery** (`src/features/battery.ts`) — Android battery-optimization
      warnings, `openBatterySettings`, `dismissBatteryWarning`.
      → `guide/battery.md`
- [x] **Audio Output** (`src/features/output.ts`) — iOS output picker / AirPlay
      route (`openIosOutputPicker`, `useIosOutput`). → `guide/audio-output.md`
- [x] **Car Connection** (`src/features/carConnection.ts`) — `useCarConnected`.
      Covered in the Automotive overview (`guide/automotive.md`) + Hooks; no
      standalone page.

## Suggested priority

New **Features** pages, by undocumented API surface: Playback, Queue,
Sleep Timer, Equalizer, Errors. The rest (network / battery / output /
metadata / remote-controls / car-connection) are smaller — some could be
merged or tucked into existing pages.
