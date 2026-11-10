# Silence bounds retry; connects pace it

**Status:** accepted. Amends ADR 0004.

ADR 0004 split automatic retry into two duration budgets on whether the **Load** ever rendered audio. That split stays. What changes is what the recovery budget measures, and how many mechanisms adjust it.

The recovery budget promised to give up after two minutes without audio. It measured time since the first error instead. Playback between errors kept the clock running, so three patches grew around it:

- a refill after 20 s of unbroken playback, on both platforms;
- on Android, three recoveries from a stuck player, each refilling the budget;
- a budget that is checked only when a failure arrives, so a player that sits silent with no new error is never asked.

They interact badly. A live stream that the server closes every 10 s never plays 20 s in a row, so its budget only drains, and a stream that plays fine ends in an error after about two minutes. A stream that sends a moment of audio and stalls spends minutes in near silence on Android, because each stuck recovery refills the budget.

Two questions are now kept apart: when to give up, and when to connect.

## Silence bounds

1. **Silent** means the listener wants audio (the play intent is up) and is not hearing it. A silence ends only once audio has played for 5 s in a row; a shorter burst does not end it.
2. **Give up after 120 s of silence** (the recovery budget). A load that never played also gives up 12 s after its first failure seen online (the first-connect budget). Going offline clears that clock, and the next failure seen online starts it again, so it only ever measures one online stretch, as ADR 0004 says. Both platforms' code before this cleared the clock only when a failure arrived while offline. For a load that failed, went offline briefly and came back with no failure in between, the first failure after the blip gave up at once if 12 s had passed since the one before it; now that failure starts a fresh 12 s.
3. **A deadline enforces it.** The deadline is set when silence starts and cleared while audio plays. The verdict does not wait for the next failure.
4. **Time offline counts** toward the 120 s, so playback never resumes by surprise.

## Connects pace

5. **After a connection that delivered audio, reconnect at once.** This covers a clean close and a drop.
6. **After a connection that delivered nothing, wait.** The wait grows 1, 1.5, 2.25, 3.375, then 5 s, and starts again at 1 s once a connection delivers audio. `maxRetries` counts these connections, in a row.
7. **Connects start at least 1 s apart**, whatever ended the last one, so a server that sends a moment of audio and closes is never hit in a tight loop. A new Load or a play intent that comes up again starts over without it: those are the listener's, not the retry's.
8. **Offline, no connects.** The first goes out when the network returns: its return drops what remains of the pending wait, keeping only the 1 s floor. The backoff's growth stays, so if that connect delivers nothing, the next waits 1.5 s, not 1 s. A connection that ends while offline says nothing about the source, so it does not grow the wait. A report that the network is up while it already was changes nothing.
9. **A connection that is open but silent** is given up for a new one after a fixed watch, and paced by what it delivered before it went silent. Rule 2 bounds how long that can repeat.

## Buffers follow the cause

10. **The rebuffer threshold rises only after an underrun**: the connection was open and data came too slowly. A rebuffer that follows a close or a failed load leaves it alone.

## Two units, one case file

Per ADR 0003 the rule lives in two pure units with the same name on both platforms. Time comes in as an argument; neither holds a timer, a thread or a player type.

- `SilenceBudget` takes the play intent, audio starting and stopping, connectivity, connection ends and new Loads, and answers the deadline (rules 1 to 4). Each of those inputs can move the deadline, not only audio and the intent: a first failure brings a never-played Load's deadline forward to 12 s.
- `ReconnectPacer` takes whether each connection delivered audio, when it started and when it ended, the play intent, connectivity and new Loads, and answers the wait before the next connect, or that it waits for the network, or that `maxRetries` is spent (rules 5 to 8).

A restart from the terminal error is a new Load for both, so the waits and the deadline start over. A play intent that comes up again starts both over too, as a retry parked by a pause does today.

Both units take the play intent and connectivity, and the wiring feeds them the same transitions. A `ReconnectPacer` paces one sequence of connections made one at a time. Where the platform runs several at once, as media3 does with an HLS playlist and its segments, the wiring decides which sequence each one paces.

`spec/retry-cases.json` holds timelines of events with the outputs expected after each, and the Swift and Kotlin tests run every case. Parity is checked by the same cases, not by experiment. A behaviour the rule changes is changed in the case file first.

## What it replaces

- The clock from the first error (`firstRetryTime` on iOS, `firstErrorTime` on Android), and the 20 s healthy-playback refill on both.
- Android's stuck-recovery count and its refill: a stuck report becomes a plain reconnect.
- The backoff arithmetic in each platform's retry code, the `immediately` reconnect on iOS and the fixed offline delay on Android.
- The buffer manager's reaction to every rebuffer.

## Considered options

- **Lower the refill from 20 s to 5 s** — rejected: it fixes the stream that closes every 10 s, but keeps the clock from the first error, the stuck-recovery refill and the check that waits for a failure.
- **A budget that drains while silent and refills while playing**, with no 5 s threshold — rejected: harder to explain and to test, and a connect burst would refill it.
- **Its own longer limit for time offline**, for tunnels — rejected: playback resuming minutes later by surprise is worse than a tap.
- **Move Android's retry loop out of media3**, as on iOS — chosen for every failure that ends a connection: a live stream, a stream of unknown length. It was rejected at first, on the belief that media3's in-place retry kept buffered audio playing through a reconnect. It does not need to: media3 surfaces such a failure only once the buffer has played out, the moment its own deferred retry would have reconnected. Measured against the test stream server, the loop loses no audio, and a fresh prepare is never counted as a rebuffer. Where media3 resumes within the connection, an HLS segment or playlist and a file whose length the server declared, it still retries in place, and the loop's deadline gives up on it.
- **Silence bounds, connects pace (chosen).**

## Consequences

- **The two-minute promise is kept**: 120 s of silence, whatever happens in between.
- **A network frozen for longer than 120 s ends in the error on Android.** Before, a stuck report refilled the budget and playback came back by itself, at about 171 s for a 150 s freeze. Now the listener taps play.
- **A working stream that keeps closing keeps playing**, with a short gap at each close.
- **A stream that only ever sends short bursts ends at 120 s**, choppy until then, on both platforms. That includes one that closes every 5 s and is audible for about 4 s of each: no run reaches 5 s, so its silence never ends.
- **`maxRetries` counts connections in a row that delivered nothing.** A connection that delivered audio starts the count over, so a stream that played and dropped reconnects once more than `maxRetries` before giving up: with `maxRetries: 0`, a drop still reconnects once. Before, the count ran from the first error.
- **The terminal error codes stay as they are**: on Android the error of the connection that ended last (`io-network-connection-failed` when none surfaced, `playback-stalled` when it ended stuck), `playback-stalled` on iOS.
- **The players adopt the units one at a time**, each with the stream server's cases run before and after.
