import { describe, expect, it } from 'vitest'
import { GenerationMemo } from './GenerationMemo'

/** Computations that each wait until settled on their own, numbered from 0 in start order. */
function runs() {
  const pending: Array<{
    resolve: (value: number) => void
    reject: (error: Error) => void
  }> = []
  return {
    compute: () =>
      new Promise<number>((resolve, reject) =>
        pending.push({ resolve, reject })
      ),
    get count() {
      return pending.length
    },
    resolve: (index: number, value: number) => pending[index]!.resolve(value),
    reject: (index: number, error: Error) => pending[index]!.reject(error)
  }
}

const settle = () => new Promise((resolve) => setTimeout(resolve, 0))

function memoAt() {
  const clock = { now: 0 }
  return { memo: new GenerationMemo<number>(10_000, () => clock.now), clock }
}

describe('GenerationMemo', () => {
  it('shares one run between concurrent callers and keeps its value', async () => {
    const { memo } = memoAt()
    const r = runs()
    const first = memo.value(r.compute)
    const second = memo.value(r.compute)
    await settle()
    r.resolve(0, 1)
    expect(await first).toBe(1)
    expect(await second).toBe(1)
    expect(await memo.value(async () => 2)).toBe(1)
    expect(r.count).toBe(1)
  })

  it('computes again after invalidate', async () => {
    const { memo } = memoAt()
    await memo.value(async () => 1)
    memo.invalidate()
    expect(await memo.value(async () => 2)).toBe(2)
  })

  it('does not keep a failure', async () => {
    const { memo } = memoAt()
    await expect(
      memo.value(async () => {
        throw new Error('failed')
      })
    ).rejects.toThrow('failed')
    expect(await memo.value(async () => 3)).toBe(3)
  })

  it('computes again when a value lands after an invalidation', async () => {
    const { memo } = memoAt()
    let input = 1
    const r = runs()
    const result = memo.value(async () => (await r.compute()) + input)
    await settle()
    input = 10
    memo.invalidate()
    r.resolve(0, 0)
    await settle()
    r.resolve(1, 0)
    expect(await result).toBe(10)
  })

  it('is joined by a caller within the bound', async () => {
    const { memo, clock } = memoAt()
    const r = runs()
    const early = memo.value(r.compute)
    await settle()
    clock.now = 9_999
    const late = memo.value(r.compute)
    await settle()
    expect(r.count).toBe(1)
    r.resolve(0, 1)
    expect(await late).toBe(1)
    expect(await early).toBe(1)
  })

  it('is not joined past the bound, and the next value releases every caller', async () => {
    const { memo, clock } = memoAt()
    const r = runs()
    const early = memo.value(r.compute)
    await settle()
    clock.now = 10_000
    const late = memo.value(r.compute)
    await settle()
    expect(r.count).toBe(2)
    r.resolve(1, 2)
    expect(await late).toBe(2)
    expect(await early).toBe(2)
    r.resolve(0, 1)
    await settle()
    expect(await memo.value(async () => 3)).toBe(2)
  })

  it('counts the bound from the newest run', async () => {
    const { memo, clock } = memoAt()
    const r = runs()
    void memo.value(r.compute)
    await settle()
    clock.now = 10_000
    void memo.value(r.compute)
    await settle()
    clock.now = 15_000
    const third = memo.value(r.compute)
    await settle()
    expect(r.count).toBe(2)
    r.resolve(1, 2)
    expect(await third).toBe(2)
  })

  it("leaves the earlier run's callers waiting on a later run's failure", async () => {
    const { memo, clock } = memoAt()
    const r = runs()
    const early = memo.value(r.compute)
    await settle()
    clock.now = 10_000
    const late = memo.value(r.compute)
    await settle()
    r.reject(1, new Error('later run failed'))
    await expect(late).rejects.toThrow('later run failed')
    r.resolve(0, 1)
    expect(await early).toBe(1)
  })

  it("releases an earlier generation's silent run with the new generation's value", async () => {
    const { memo } = memoAt()
    const r = runs()
    const early = memo.value(r.compute)
    await settle()
    memo.invalidate()
    const late = memo.value(r.compute)
    await settle()
    r.resolve(1, 2)
    expect(await late).toBe(2)
    expect(await early).toBe(2)
  })
})
