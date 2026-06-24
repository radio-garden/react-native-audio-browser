# Sleep Timer

A **sleep timer** stops playback on its own after a set time — the classic
"fall asleep to the radio" feature. The library runs the timer natively, so it
keeps counting down and fires even when your app is backgrounded or the screen
is off. There are two kinds:

- **Time-based** — pause after a fixed number of seconds (e.g. "in 30 minutes").
- **End of track** — pause when the current track finishes playing.

Only one timer is active at a time; setting a new one replaces the old.

## Setting a timer

Call `setSleepTimer` with a duration in **seconds**. This example stops playback
after 30 minutes:

```ts
import { setSleepTimer } from 'react-native-audio-browser'

setSleepTimer(30 * 60)
```

Most UIs offer a few presets rather than a free-form duration — multiply the
minutes you show by 60:

```ts
const PRESETS_MINUTES = [15, 30, 45, 60]

setSleepTimer(PRESETS_MINUTES[1] * 60) // 30 minutes
```

To pause when the current track ends instead of after a fixed time, use
`setSleepTimerToEndOfTrack`:

```ts
import { setSleepTimerToEndOfTrack } from 'react-native-audio-browser'

setSleepTimerToEndOfTrack()
```

When the timer completes, the library **pauses** playback (it doesn't stop or
tear the player down). Pressing play resumes as normal.

## Fading out

Pass a `fadeDuration` (in seconds) to ramp the volume down to silence as the
timer runs out — a gentler finish than an abrupt pause:

```ts
// Stop in 30 minutes, fading the last 20 seconds to silence.
setSleepTimer(30 * 60, { fadeDuration: 20 })
```

The fade always ends exactly at the deadline (if `fadeDuration` is longer than
the timer itself, it's clamped to the timer's length). Once playback pauses, the
library restores the pre-fade volume, so the next time the listener hits play it
starts at normal loudness.

During the fade the timer takes priority: seeking or skipping plays into the
remaining ramp rather than cancelling it. Only pausing, or clearing/replacing
the timer, stops the fade. The countdown keeps ticking throughout, so you can
show a "fading out" hint once `secondsLeft` drops to `fadeDuration` (see the
[complete example](#a-complete-screen)).

## Clearing a timer

`clearSleepTimer` cancels the active timer. It returns `true` if a timer was
actually cleared, or `false` if none was set:

```ts
import { clearSleepTimer } from 'react-native-audio-browser'

clearSleepTimer()
```

## Reading the timer in your UI

Use the `useSleepTimer` hook to show a live countdown. It re-renders every
second and returns:

- For a time-based timer: `{ time, secondsLeft }`, where `time` is the deadline
  (epoch milliseconds) and `secondsLeft` counts down to `0`.
- For an end-of-track timer: `{ sleepWhenPlayedToEnd: true }`.
- `undefined` when no timer is active.

```tsx
import { useSleepTimer } from 'react-native-audio-browser'

function SleepTimerLabel() {
  const timer = useSleepTimer()

  if (!timer) return <Text>No sleep timer</Text>
  if ('sleepWhenPlayedToEnd' in timer) {
    return <Text>Stopping at end of track</Text>
  }

  const minutes = Math.floor(timer.secondsLeft / 60)
  const seconds = timer.secondsLeft % 60
  return <Text>Sleeping in {minutes}m {seconds}s</Text>
}
```

The countdown only ticks while the app is in the foreground — `secondsLeft`
doesn't advance in the background (the native timer still fires on time
regardless). The only reason to care is to avoid pointless per-second re-renders
while backgrounded: derive the `inactive` flag from React Native's `AppState`
and pass it in. You can also tune the refresh rate with `updateInterval`
(milliseconds, default `1000`):

```tsx
import { useEffect, useState } from 'react'
import { AppState } from 'react-native'
import { useSleepTimer } from 'react-native-audio-browser'

function useAppInactive() {
  const [inactive, setInactive] = useState(
    () => AppState.currentState !== 'active'
  )
  useEffect(() => {
    const sub = AppState.addEventListener('change', (state) =>
      setInactive(state !== 'active')
    )
    return () => sub.remove()
  }, [])
  return inactive
}

// In a component: pause the countdown updates while backgrounded.
const timer = useSleepTimer({ inactive: useAppInactive() })
```

### Just need to know if one is set?

If you only want to toggle a button's appearance, `useSleepTimerActive` is a
lighter-weight hook that returns a boolean and doesn't re-render every second:

```tsx
import { useSleepTimerActive } from 'react-native-audio-browser'

function SleepTimerButton() {
  const active = useSleepTimerActive()
  return <Icon name={active ? 'timer-on' : 'timer-off'} />
}
```

## A complete screen

Putting it together — preset durations, an end-of-track option, an off button,
and a live countdown that switches to a "fading out" hint as the fade begins:

```tsx
import { View, Button, Text } from 'react-native'
import {
  setSleepTimer,
  setSleepTimerToEndOfTrack,
  clearSleepTimer,
  useSleepTimer
} from 'react-native-audio-browser'

const PRESETS_MINUTES = [15, 30, 45, 60]
const FADE_SECONDS = 20

function SleepTimerScreen() {
  const timer = useSleepTimer()

  return (
    <View>
      {PRESETS_MINUTES.map((minutes) => (
        <Button
          key={minutes}
          title={`${minutes} min`}
          onPress={() =>
            setSleepTimer(minutes * 60, { fadeDuration: FADE_SECONDS })
          }
        />
      ))}
      <Button
        title="End of track"
        onPress={() => setSleepTimerToEndOfTrack()}
      />
      <Button title="Off" onPress={() => clearSleepTimer()} />

      <Status timer={timer} fadeSeconds={FADE_SECONDS} />
    </View>
  )
}

function Status(props: {
  timer: ReturnType<typeof useSleepTimer>
  fadeSeconds: number
}) {
  const { timer, fadeSeconds } = props
  if (!timer) return <Text>No sleep timer</Text>
  if ('sleepWhenPlayedToEnd' in timer) {
    return <Text>Stopping at end of track</Text>
  }
  if (timer.secondsLeft <= fadeSeconds) return <Text>Fading out…</Text>

  const minutes = Math.floor(timer.secondsLeft / 60)
  const seconds = timer.secondsLeft % 60
  return <Text>Sleeping in {minutes}m {seconds}s</Text>
}
```

When the timer fires (or is cleared), `useSleepTimer` returns `undefined` and
the countdown stops on its own — so `Status` falls back to "No sleep timer"
without any extra handling on your part.

## Outside of React

If you're not in a component, read the current timer with `getSleepTimer` or
subscribe to changes with `onSleepTimerChanged`. Note that `getSleepTimer`
returns the raw timer state (`{ time }` in epoch milliseconds, or
`{ sleepWhenPlayedToEnd }`, or `null`) — it doesn't compute `secondsLeft`; that
convenience is added by the `useSleepTimer` hook.

```ts
import { getSleepTimer, onSleepTimerChanged } from 'react-native-audio-browser'

getSleepTimer() // { time } | { sleepWhenPlayedToEnd } | null

// addListener returns a cleanup function — call it to unsubscribe.
const unsubscribe = onSleepTimerChanged.addListener((timer) => {
  console.log('sleep timer is now', timer)
})

// later, when you no longer need updates:
unsubscribe()
```

## Behavior and edge cases

- **Setting replaces, it doesn't stack.** Calling any setter while a timer is
  running swaps it for the new one — there's only ever a single active timer.
- **Not persisted across launches.** The timer lives in memory. If the app is
  killed (or the OS reclaims it), the timer is gone on next launch — it does not
  resume. Persist your own "timer set at" value if you need to restore the UI.
- **No input validation.** `seconds` is passed straight through to the native
  scheduler, which isn't clamped — a value of `0` or a negative number fires
  essentially immediately (pausing right away). Validate in your own UI if that
  matters.
- **Nothing throws.** Every function here is synchronous; the setters return
  `void` and `clearSleepTimer` returns a boolean. No `try/catch` needed.

## API summary

| API | Purpose |
| --- | --- |
| `setSleepTimer(seconds, { fadeDuration? })` | Pause playback after `seconds`, optionally fading out. |
| `setSleepTimerToEndOfTrack()` | Pause when the current track finishes. |
| `clearSleepTimer()` | Cancel the active timer; returns `true` if one was cleared. |
| `getSleepTimer()` | Read the raw timer state (no `secondsLeft`). |
| `onSleepTimerChanged` | Subscribe to timer changes outside React; returns a cleanup fn. |
| `useSleepTimer({ updateInterval?, inactive? })` | Live state with a `secondsLeft` countdown. |
| `useSleepTimerActive()` | Boolean: is any timer set? |
