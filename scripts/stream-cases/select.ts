import type { Platform, StreamCase } from './judge.ts'

/** The cases that run on `platform` whose name, path or url contains `only`, or all of them without it. */
export function selectCases(
  cases: StreamCase[],
  platform: Platform,
  only: string | undefined
): StreamCase[] {
  return cases.filter(
    (c) =>
      (c.platforms ?? [platform]).includes(platform) &&
      (only === undefined ||
        [c.name, c.path, c.url].some((text) => text?.includes(only)))
  )
}
