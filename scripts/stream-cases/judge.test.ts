import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'
import {
  audibleSeconds,
  judge,
  reloadVerdict,
  silences,
  type HeardState,
  type StreamCase
} from './judge'

const plays: StreamCase = {
  name: 'plays',
  path: '/live.mp3',
  seconds: 60,
  expect: { outcome: 'plays', maxGapSeconds: 1.5 }
}

const errs: StreamCase = {
  name: 'errs',
  path: '/live.mp3',
  seconds: 60,
  expect: { outcome: 'error', atSeconds: 12, toleranceSeconds: 1 }
}

describe('silences', () => {
  it('counts only what follows the first audio', () => {
    const heard = [
      { at: 0, state: 'loading' },
      { at: 1, state: 'playing' },
      { at: 10, state: 'buffering' },
      { at: 10.5, state: 'playing' }
    ]
    expect(silences(heard, 20)).toEqual([{ from: 10, to: 10.5 }])
  })

  it('runs a silence to the end of the run', () => {
    const heard = [
      { at: 1, state: 'playing' },
      { at: 50, state: 'buffering' }
    ]
    expect(silences(heard, 60)).toEqual([{ from: 50, to: 60 }])
  })

  it('drops the silence an error ends', () => {
    const heard = [
      { at: 1, state: 'playing' },
      { at: 5, state: 'buffering' },
      { at: 30, state: 'error' }
    ]
    expect(silences(heard, 60)).toEqual([])
  })
})

describe('audibleSeconds', () => {
  it('adds up the playing spans, the last to the end of the run', () => {
    const heard = [
      { at: 1, state: 'playing' },
      { at: 10, state: 'buffering' },
      { at: 11, state: 'playing' }
    ]
    expect(audibleSeconds(heard, 20)).toBe(18)
  })

  it('stops at an error', () => {
    const heard = [
      { at: 1, state: 'playing' },
      { at: 4, state: 'error' }
    ]
    expect(audibleSeconds(heard, 20)).toBe(3)
  })
})

describe('judge', () => {
  it('passes a run that plays with short gaps', () => {
    const heard = [
      { at: 0.5, state: 'playing' },
      { at: 10, state: 'buffering' },
      { at: 10.2, state: 'playing' }
    ]
    expect(judge(plays, 'android', heard).pass).toBe(true)
  })

  it('fails a gap past the limit, and an error', () => {
    const heard = [
      { at: 0.5, state: 'playing' },
      { at: 10, state: 'buffering' },
      { at: 13, state: 'playing' },
      { at: 40, state: 'error' }
    ]
    expect(judge(plays, 'android', heard).failures).toEqual([
      'error at 40 s',
      'a gap of 3 s; expected at most 1.5 s'
    ])
  })

  it('fails a run the app reloaded during, naming when it last heard', () => {
    const heard = [{ at: 0.1, state: 'loading' }]
    expect(reloadVerdict(heard)).toEqual({
      pass: false,
      observed: 'the app reloaded; last heard at 0.1 s',
      failures: ['the app reloaded during the case']
    })
  })

  it('reads a limit per platform', () => {
    const perPlatform: StreamCase = {
      ...plays,
      expect: { outcome: 'plays', maxGapSeconds: { android: 2, ios: 15 } }
    }
    const heard = [
      { at: 0.5, state: 'playing' },
      { at: 10, state: 'buffering' },
      { at: 20, state: 'playing' }
    ]
    expect(judge(perPlatform, 'ios', heard).pass).toBe(true)
    expect(judge(perPlatform, 'android', heard).pass).toBe(false)
  })

  it('passes an error inside the tolerance, and fails one outside it', () => {
    expect(judge(errs, 'ios', [{ at: 12.8, state: 'error' }]).pass).toBe(true)
    expect(judge(errs, 'ios', [{ at: 13.4, state: 'error' }]).failures).toEqual(
      ['error at 13.4 s; expected 12 ± 1 s']
    )
  })

  it('fails a run that never errs when it should', () => {
    expect(judge(errs, 'ios', [{ at: 1, state: 'playing' }]).failures).toEqual([
      'no error; expected one at 12 ± 1 s'
    ])
  })

  it('times the audio from when JS hears the network return', () => {
    const offline: StreamCase = {
      ...plays,
      network: [
        { at: 20, online: false },
        { at: 60, online: true }
      ],
      expect: { outcome: 'plays', resumesWithinSeconds: 6 }
    }
    const heard = [
      { at: 1, state: 'playing' },
      { at: 20.5, online: false },
      { at: 23, state: 'buffering' },
      { at: 70, online: true },
      { at: 72, state: 'playing' }
    ]
    expect(judge(offline, 'android', heard).pass).toBe(true)
  })

  it('times the audio after the network returns', () => {
    const offline: StreamCase = {
      ...plays,
      network: [
        { at: 20, online: false },
        { at: 60, online: true }
      ],
      expect: { outcome: 'plays', resumesWithinSeconds: 6 }
    }
    const heard = [
      { at: 1, state: 'playing' },
      { at: 23, state: 'buffering' },
      { at: 67, state: 'playing' }
    ]
    expect(judge(offline, 'android', heard).failures).toEqual([
      'audio 7 s after the network returned; expected within 6 s'
    ])
  })
})

/**
 * A run of the stream that closes after 2 s of audio, whose bursts start 2.2 s apart and play
 * where `played` says, ending in the error at `errorAt`.
 */
function bursts(
  played: (index: number) => boolean,
  count: number,
  errorAt: number
) {
  const heard: HeardState[] = [{ at: 0, state: 'loading' }]
  for (let index = 0; index < count; index++) {
    const at = index * 2.2
    if (played(index)) {
      heard.push(
        { at: at + 0.1, state: 'playing' },
        { at: at + 2.1, state: 'buffering' }
      )
    }
  }
  return [...heard, { at: errorAt, state: 'error' }]
}

describe('the stream that closes after 2 s of audio', () => {
  const close2 = JSON.parse(
    readFileSync(
      new URL('../../spec/stream-cases.json', import.meta.url),
      'utf8'
    )
  ).cases.find((c: StreamCase) => c.path === '/live.mp3?close=2') as StreamCase

  it('passes when every burst plays', () => {
    const verdict = judge(
      close2,
      'android',
      bursts(() => true, 55, 120.7)
    )
    expect(verdict.failures).toEqual([])
  })

  it('fails when most bursts are lost, though the error comes on time', () => {
    expect(
      judge(
        close2,
        'android',
        bursts((index) => index % 3 === 0, 61, 120)
      ).failures
    ).toEqual(['audible for 35% of the run; expected at least 90%'])
    expect(
      judge(
        close2,
        'android',
        bursts((index) => index === 0, 27, 120)
      ).failures
    ).toEqual(['audible for 2% of the run; expected at least 90%'])
  })
})

describe('spec/stream-cases.json', () => {
  const spec = JSON.parse(
    readFileSync(
      new URL('../../spec/stream-cases.json', import.meta.url),
      'utf8'
    )
  ) as { cases: StreamCase[] }

  it('gives every case a source and a run long enough for its expectation', () => {
    for (const streamCase of spec.cases) {
      expect(streamCase.path ?? streamCase.url, streamCase.name).toBeTruthy()
      const expectation = streamCase.expect
      if (expectation.outcome === 'error') {
        const latest = Math.max(
          ...[expectation.atSeconds]
            .flatMap((at) =>
              typeof at === 'number' ? [at] : Object.values(at)
            )
            .map((at) => at!)
        )
        expect(streamCase.seconds, streamCase.name).toBeGreaterThan(latest + 5)
      }
    }
  })
})
