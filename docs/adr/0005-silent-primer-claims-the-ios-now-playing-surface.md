# A silence burst claims the iOS now-playing surface when the first load fails

**Status:** accepted

On iOS, when a load with play intent fails, the player does two things:

- It plays ~0.3s of generated silence (zero-valued PCM samples) through the already-active audio session (`SilentAudioPrimer`).
- It attaches the failed asset's `AVPlayerItem` as an unobserved _publishing carrier_.

Together these make the system now-playing surfaces (lock screen, Control Center, the CarPlay now-playing template) show the current track's metadata, the failure line, and working transport controls. Without them the surfaces stay blank until some other stream plays.

This is a workaround for a platform rule with no sanctioned alternative: iOS elects an app onto the system now-playing surfaces only once its audio session actually renders output.

Device logs show each layer of the sanctioned path working, and the surface still empty because no audio ever rendered:

- the `MPNowPlayingSession` activates (`becomeActiveIfPossible: true`)
- MediaRemote accepts a complete metadata dictionary (title, artist, artwork)
- the now-playing item registers

The declarative escape hatch does not exist for third parties. `MPNowPlayingInfoCenter.playbackState` writes are dropped on iOS without the private `com.apple.mediaremote.set-playback-state` entitlement; the OS logs an explicit "Ignoring setPlaybackState" error.

A working stream never hits any of this: real playback elects the app through the front door. So the gap only shows on a cold start whose _first_ load fails.

That is when a car user is left staring at an empty template with no way to pick another station from it.

The primer fires at most once per load, only on failures with play intent. Its samples are zeros: inaudible by construction, with no volume manipulation involved.

It does not check whether the app has rendered audio before, so a load that fails after earlier playback primes too; by then the app is already elected and the burst changes nothing.

The carrier item exists because `MPNowPlayingSession` auto-publishing reads metadata from the linked player's current item (`item.nowPlayingInfo`). A failed load otherwise leaves the player item-less, and the framework warns that direct info-center writes are unsupported under automatic publishing.

The carrier is not observed at the item level, so its `.failed` status cannot double-report the error the loader already delivered. Retry reloads leave it in place, and a successful load replaces it with the real, observed item.

## Considered options

- **Publish harder through sanctioned APIs** — carrier item alone, session re-activation on audio-session activation, direct info-center writes. All implemented or tested; none surfaced the panel without rendered audio. The election rule is about audio I/O, not publishing.
- **Disable auto-publishing and publish manually while item-less** — the framework logs manual center writes as unsupported under `MPNowPlayingSession` auto-publishing, and manual mode cannot claim election without audio either. It would reintroduce the manual publishing path (owning elapsed/rate) for no gain.
- **Present failures through a CarPlay-native alert instead** — honest, but it only patches one surface: lock screen and Control Center stay blank, and the empty now-playing template still exists behind the alert.
- **Accept the blank surface**: defensible for phone use, where the in-app UI shows the failure. Rejected for automotive: a driver who started a dead stream from the car gets an empty template with no next/previous, a dead end at the wheel.
- **Silence burst + carrier item (chosen)** — the established industry workaround, scoped to failed loads: it fires only when a load with play intent fails, once per load, and changes nothing once real playback has elected the app.

  It costs 0.3s of inaudible output, and every other layer it depends on (metadata publishing, remote commands) uses sanctioned APIs.

## Consequences

- **The now-playing surfaces work from a cold start even when the first stream is dead** — metadata, the advisory/terminal failure line, and next/previous all appear, verified on-device via CarPlay.
- **This is an election hack and should be re-audited on major iOS releases.** If Apple ever honors `becomeActiveIfPossible` without rendered audio, or opens the playback-state entitlement, the primer (and possibly the carrier) can be deleted. `SilentAudioPrimer`'s doc comment records the evidence to re-test against.
- **The carrier item slightly overloads `currentItem` semantics** (an item that is known unplayable, and unobserved). The eventual clean resolution is attaching items eagerly at load time, ExoPlayer-style, and letting item status drive failure detection — a larger refactor recorded here as the known better shape, not blocked on.
- **Android is untouched**: ExoPlayer's model keeps the `MediaItem` in the playlist regardless of load state, so its media notification never had this gap.
