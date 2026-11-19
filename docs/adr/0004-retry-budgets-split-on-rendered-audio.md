# Retry budgets split on whether the load ever rendered audio

**Status:** accepted, amended by [ADR 0014](0014-silence-bounds-retry-and-connects-pace-it.md): the recovery budget measures silence, not time since the first error.

Automatic retry uses two **duration** budgets, selected per load by a single piece of evidence: has this load ever produced audio?

- A load that has never played gets a short first-connect budget (default 12s, online time only).
- A load that played and then failed gets the full recovery budget (default 2 minutes).

Before this decision, one 2-minute budget covered both situations.

The two situations have opposite user contexts.

A first connect that fails is usually a dead source. A connection refused in ~300ms fails identically on every attempt, and sources that recover on attempt twenty essentially don't exist.

Meanwhile the listener is _actively waiting_ for a verdict. Their decision loop is seconds long, and two minutes of "retrying…" only delays the diagnosis they would act on.

A mid-play drop is the reverse. Playback has proven the source works, and drops are usually transient (network handovers, a live encoder restarting). The listener is typically not watching, and unattended recovery after a minute is the product working.

The asymmetry of mistakes decides the numbers. Giving up too early costs a tap: a restart from terminal error begins a new load with fresh budgets. Giving up too late costs the listener minutes of false hope with no action available.

The boundary is **per load, evidence-based**: "has ever rendered audio", not "is the first track of the session". A source that played for an hour, died, and now refuses connections is still a recovery case (its encoder is probably restarting).

The flag is set when audio starts and cleared when a new track loads. It is _not_ cleared by the retry-state resets that run mid-play (e.g. the healthy-playback budget refill), which would silently reclassify a playing stream.

While the device is offline, the first-connect budget does not apply, and any offline observation restarts its clock. The budget only ever measures a contiguous online stretch.

Without this, a load started in a tunnel, or interrupted by an offline stretch mid-budget, would burn its seconds against a network that cannot answer. It would go terminal at the moment connectivity returns.

## Considered options

- **One budget, tuned smaller** — shrinks the false-hope window but destroys unattended recovery for live streams, the case the long budget exists for. Rejected.
- **Attempt-count cap for first connects** (e.g. "3 attempts"). Rejected for two reasons:
  - **Counts don't convert to time.** A refused connection fails in milliseconds, but a black-holed host can hang 30–60s per attempt. So "3 attempts" is anywhere from 5 seconds to minutes, defeating the fast-verdict goal.
  - **Counts aren't portable.** ExoPlayer's `errorCount` is per _loadable_: an HLS stream has playlist and segment loadables, each counting independently. The same cap means different things per platform and per stream format.

  Attempt counts remain what they are good at, pacing the backoff, while durations make the promise: _counts pace, durations bound_.

- **Let consumers implement fast give-up in JS** — they receive the advisory retrying errors and could call `stop()` on their own timer. Rejected: it pushes a subtle, parity-sensitive policy onto every consumer, and JS timers are unreliable exactly where this matters most (backgrounded CarPlay / Android Auto contexts).
- **Split duration budgets on rendered audio (chosen)** — both platforms already enforce the recovery budget from a first-error timestamp, so a second, shorter timestamp-based budget is the minimal, parity-safe delta.

## Consequences

- **A dead source yields its real diagnosis in seconds**, not minutes, while a dropped live stream keeps its patient unattended recovery. UI copy for the retrying state can stay honest ("retrying…" that actually resolves promptly for dead sources).
- **Android must enforce budgets at the policy level** (its own timestamps), never via ExoPlayer's per-loadable `errorCount` — that invariant is documented in the policy and is what keeps the platforms behaviorally identical.
- **The `hasPlayed` flag has a lifecycle asymmetry**: cleared on new-track load, preserved across retry reloads and mid-play retry-state resets. Tests on both platforms pin this, because getting it wrong silently hands a playing stream the short budget.
- **Consumers can tune both budgets** (`maxRetryDurationMs`, `firstConnectMaxRetryDurationMs`); `retry: true` opts into the split defaults. Native only — the web implementation has no automatic retry.
