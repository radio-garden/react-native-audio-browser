export type Platform = 'android' | 'ios'

type Limit = number | Partial<Record<Platform, number>>

export type Expectation = (
  | {
      outcome: 'plays'
      maxGapSeconds?: Limit
      resumesWithinSeconds?: Limit
    }
  | { outcome: 'error'; atSeconds: Limit; toleranceSeconds: Limit }
) & { minAudibleShare?: Limit }

export type StreamCase = {
  name: string
  path?: string
  url?: string
  seconds: number
  platforms?: Platform[]
  network?: { at: number; online: boolean }[]
  expect: Expectation
}

/** A playback state JS heard, at seconds from play. */
export type HeardState = { at: number; state: string }

/** What JS heard, at seconds from play: a playback state, or the device going online or offline. */
export type Heard = HeardState | { at: number; online: boolean }

export type Verdict = {
  pass: boolean
  /** What happened, in a line: the error's time, or the audio and the longest gap. */
  observed: string
  failures: string[]
}

function limit(
  value: Limit | undefined,
  platform: Platform
): number | undefined {
  if (value === undefined || typeof value === 'number') return value
  return value[platform]
}

/** The silences after the first audio: each a span not in `playing`, up to the end of the run or the error. */
export function silences(
  heard: HeardState[],
  endSeconds: number
): { from: number; to: number }[] {
  const spans: { from: number; to: number }[] = []
  let playedOnce = false
  let silentSince: number | undefined
  for (const { at, state } of heard) {
    if (state === 'playing') {
      if (silentSince !== undefined) spans.push({ from: silentSince, to: at })
      silentSince = undefined
      playedOnce = true
    } else if (playedOnce && silentSince === undefined) {
      silentSince = at
    }
    if (state === 'error') break
  }
  const errorAt = heard.find((h) => h.state === 'error')?.at
  if (silentSince !== undefined && errorAt === undefined) {
    spans.push({ from: silentSince, to: endSeconds })
  }
  return spans
}

/** Seconds in `playing` before the end of the run or the error. */
export function audibleSeconds(
  heard: HeardState[],
  endSeconds: number
): number {
  let total = 0
  let since: number | undefined
  for (const { at, state } of heard) {
    if (since !== undefined) total += at - since
    since = state === 'playing' ? at : undefined
    if (state === 'error') return total
  }
  return since === undefined ? total : total + endSeconds - since
}

const round = (seconds: number) => Math.round(seconds * 10) / 10

/** The verdict on a run the app reloaded during: the reload takes the listeners, so what was heard stops there. */
export function reloadVerdict(heard: Heard[]): Verdict {
  return {
    pass: false,
    observed: `the app reloaded; last heard at ${round(heard.at(-1)?.at ?? 0)} s`,
    failures: ['the app reloaded during the case']
  }
}

/** Whether what JS heard during a run of `streamCase` on `platform` is what the case expects. */
export function judge(
  streamCase: StreamCase,
  platform: Platform,
  heard: Heard[]
): Verdict {
  const end = streamCase.seconds
  const states = heard.filter((h): h is HeardState => 'state' in h)
  const errorAt = states.find((h) => h.state === 'error')?.at
  const gaps = silences(states, end)
  const longest = Math.max(0, ...gaps.map((gap) => gap.to - gap.from))
  const audible = audibleSeconds(states, end)
  const failures: string[] = []
  const expect = streamCase.expect
  const minShare = limit(expect.minAudibleShare, platform)
  const share = audible / (errorAt ?? end)
  if (minShare !== undefined && share < minShare) {
    failures.push(
      `audible for ${Math.round(share * 100)}% of the run; expected at least ${Math.round(minShare * 100)}%`
    )
  }
  const observed =
    errorAt === undefined
      ? `played ${round(audible)} s of ${end}, longest gap ${round(longest)} s`
      : `error at ${round(errorAt)} s, after ${round(audible)} s audible`

  if (expect.outcome === 'error') {
    const at = limit(expect.atSeconds, platform)!
    const tolerance = limit(expect.toleranceSeconds, platform)!
    if (errorAt === undefined) {
      failures.push(`no error; expected one at ${at} ± ${tolerance} s`)
    } else if (Math.abs(errorAt - at) > tolerance) {
      failures.push(
        `error at ${round(errorAt)} s; expected ${at} ± ${tolerance} s`
      )
    }
    return { pass: failures.length === 0, observed, failures }
  }

  if (errorAt !== undefined) failures.push(`error at ${round(errorAt)} s`)
  if (!states.some((h) => h.state === 'playing')) failures.push('never played')
  const maxGap = limit(expect.maxGapSeconds, platform)
  if (maxGap !== undefined && longest > maxGap) {
    failures.push(`a gap of ${round(longest)} s; expected at most ${maxGap} s`)
  }
  const resumesWithin = limit(expect.resumesWithinSeconds, platform)
  const turnedOn = streamCase.network?.findLast((step) => step.online)?.at
  if (resumesWithin !== undefined && turnedOn !== undefined) {
    // The device takes its own time to come back: timed from when JS hears it.
    const backOnline =
      heard.find((h) => 'online' in h && h.online && h.at >= turnedOn)?.at ??
      turnedOn
    const resumed = states.find(
      (h) => h.state === 'playing' && h.at >= backOnline
    )
    if (resumed === undefined) {
      failures.push(
        `no audio after the network returned at ${round(backOnline)} s`
      )
    } else if (resumed.at - backOnline > resumesWithin) {
      failures.push(
        `audio ${round(resumed.at - backOnline)} s after the network returned; ` +
          `expected within ${resumesWithin} s`
      )
    }
  }
  return { pass: failures.length === 0, observed, failures }
}
