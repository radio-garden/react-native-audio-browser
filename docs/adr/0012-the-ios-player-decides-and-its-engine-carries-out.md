# The iOS Player decides; its engine carries out

**Status:** accepted

The iOS playback core was split the wrong way round. `TrackPlayer` owned the AVPlayer, and with it most of the playback policy: reloading after a terminal error, holding a seek while an item loads, reconnecting a stalled stream when the network returns, judging an end of item, recovering from an AirPlay stall. `PlaybackCoordinator` held the state machine and forwarded side effects back to `TrackPlayer` through a protocol of renamed copies of its methods. Only the coordinator compiles under SwiftPM, so the policy most likely to regress had no tests, and about a sixth of `TrackPlayer` only forwarded to the coordinator.

The split now follows what can be tested. The **Player** holds every decision: the state, the play intent, the **ErrorStatus** and the current **Load**, the queue, retries, the sleep timer, the deferred seek, persistence, and the reactions to everything the media stack observes. It compiles under SwiftPM and is tested against a mock engine. The **PlayerEngine** is the one seam beneath it: primitives the Player calls (load, seek, play, recreate) and `EngineEvent`s it reports (item prepared, failed, stalled, played to end). `AVPlayerEngine` implements it over AVFoundation and holds no policy.

## Considered options

- **Keep the split, add tests through the bridge** — rejected: the bridge needs Nitro's generated types, which SwiftPM cannot build without rewriting the codegen, and an AVPlayer under test is slow and timing-dependent.
- **Move policy into the coordinator but keep TrackPlayer as the façade** — rejected: it keeps a layer whose only job is forwarding, and two names for one concept.
- **A Player over a PlayerEngine (chosen)** — one decision-maker, one seam, and the seam is the test double.

## Consequences

- **A new behaviour goes in the Player** unless it needs AVFoundation to observe or act. An engine method that decides something (rather than reports or carries out) is a defect.
- **The bridge holds both**: `player` for playback, `engine` for what only AVFoundation has (now playing, buffering, volume, the remote commands).
- **A recreated AVPlayer gets new observers.** Each `PlayerObserver` and `ItemObserver` lives exactly as long as what it observes, and drops what arrives after it is invalidated.
- **Retry classification is one table** (`PlaybackFailure`). A failure reported as unplayable is never also retried: `decoderNotFound`, `formatUnsupported` and `contentIsUnavailable` now fail at once instead of spending the retry budget. Only the temporary decoder shortage stays retryable.
