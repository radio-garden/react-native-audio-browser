# The iOS Player decides; its engine carries out

**Status:** accepted

The iOS playback core was split the wrong way round.

`TrackPlayer` owned the AVPlayer, and with it most of the playback policy. It reloaded after a terminal error, held a seek while an item loaded, reconnected a stalled stream when the network returned, judged an end of item and recovered from an AirPlay stall.

`PlaybackCoordinator` held the state machine and forwarded side effects back to `TrackPlayer` through a protocol of renamed copies of its methods. About a sixth of `TrackPlayer` only forwarded to the coordinator. Only the coordinator compiles under SwiftPM, so the policy most likely to regress had no tests.

The split now follows what can be tested. The **Player** holds every decision. It compiles under SwiftPM and is tested against a mock engine. It holds:

- the state, the play intent, the **ErrorStatus** and the current **Load**
- the queue, retries, the sleep timer and the deferred seek
- persistence
- the reactions to everything the media stack observes

The **PlayerEngine** is the one seam beneath it. `AVPlayerEngine` implements it over AVFoundation and holds no policy. The engine:

- carries out the primitives the Player calls: load, seek, play, recreate
- reports `EngineEvent`s: item prepared, failed, stalled, played to end

## Considered options

- **Keep the split, add tests through the bridge** — rejected: the bridge needs Nitro's generated types, which SwiftPM cannot build without rewriting the codegen, and an AVPlayer under test is slow and timing-dependent.
- **Move policy into the coordinator but keep TrackPlayer as the façade** — rejected: it keeps a layer whose only job is forwarding, and two names for one concept.
- **A Player over a PlayerEngine (chosen)** — one decision-maker, one seam, and the seam is the test double.

## Consequences

- **A new behaviour goes in the Player** unless it needs AVFoundation to observe or act. An engine method that decides something (rather than reports or carries out) is a defect.
- **The bridge holds both**: `player` for playback, `engine` for what only AVFoundation has (now playing, buffering, volume, the remote commands).
- **A recreated AVPlayer gets new observers.** Each `PlayerObserver` and `ItemObserver` lives exactly as long as what it observes, and drops what arrives after it is invalidated.
- **Retry classification is one table** (`PlaybackFailure`). A failure reported as unplayable is not also retried, except for the temporary decoder shortage (`decoderTemporarilyUnavailable`): `decoderNotFound`, `formatUnsupported` and `contentIsUnavailable` fail at once instead of spending the retry budget.
