import { describe, expect, it } from 'vitest'
import type { StreamCase } from './judge'
import { selectCases } from './select'

const expectation = {
  outcome: 'error',
  atSeconds: 12,
  toleranceSeconds: 1
} as const
const close2: StreamCase = {
  name: 'a stream that closes after 2 s of audio gives up',
  path: '/live.mp3?close=2',
  seconds: 140,
  expect: expectation
}
const dead: StreamCase = {
  name: 'a dead address gives up',
  url: 'http://127.0.0.1:{dead}/live.mp3',
  seconds: 25,
  expect: expectation
}
const offline: StreamCase = {
  name: 'a stream offline for 40 s plays again',
  path: '/live.mp3',
  seconds: 90,
  platforms: ['android'],
  expect: expectation
}
const cases = [close2, dead, offline]

describe('selectCases', () => {
  it('takes every case of the platform without --only', () => {
    expect(selectCases(cases, 'ios', undefined)).toEqual([close2, dead])
    expect(selectCases(cases, 'android', undefined)).toEqual(cases)
  })

  it('matches --only against the name', () => {
    expect(selectCases(cases, 'android', 'closes after 2 s')).toEqual([close2])
  })

  it('matches --only against the path or the url', () => {
    expect(selectCases(cases, 'android', 'close=2')).toEqual([close2])
    expect(selectCases(cases, 'android', '{dead}')).toEqual([dead])
  })

  it('leaves out a case of another platform that matches', () => {
    expect(selectCases(cases, 'ios', 'offline')).toEqual([])
  })
})
