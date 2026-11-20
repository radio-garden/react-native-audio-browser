/** How long a caller joins a running computation before starting another. */
const SHARE_RUN_MS = 10_000

type Run<T> = {
  generation: number
  started: number
  settled: Promise<T>
  resolve: (value: T) => void
  reject: (error: unknown) => void
}

/**
 * A value kept per generation and shared by its callers; the iOS
 * `GenerationMemo` holds the same contract.
 *
 * `invalidate()` starts a new generation. A caller whose computation settles
 * after an invalidation computes again, so `value` never returns a stale
 * result; `compute` must read the state it depends on when it runs. A failure
 * is not kept: the next caller tries again.
 *
 * A caller joins the newest run of its generation only while that run is
 * younger than `shareForMs`; past that it starts another, so one silent
 * computation does not hold every later caller. The current generation's first
 * value releases the callers of every run, earlier generations' included; a
 * run's failure releases only its own.
 */
export class GenerationMemo<T> {
  private generation = 0
  private kept?: { value: T }
  /** Runs of every generation still computing and not yet released, oldest first. */
  private running: Run<T>[] = []

  constructor(
    private readonly shareForMs = SHARE_RUN_MS,
    private readonly now: () => number = () => performance.now()
  ) {}

  invalidate(): void {
    this.generation += 1
    this.kept = undefined
  }

  async value(compute: () => Promise<T>): Promise<T> {
    for (;;) {
      if (this.kept) return this.kept.value
      const generation = this.generation
      const newest = this.running
        .filter((run) => run.generation === generation)
        .pop()
      const run =
        newest && this.now() - newest.started < this.shareForMs
          ? newest
          : this.start(compute)
      try {
        const value = await run.settled
        if (generation === this.generation) return value
      } catch (error) {
        if (generation === this.generation) throw error
      }
    }
  }

  private start(compute: () => Promise<T>): Run<T> {
    let resolve!: (value: T) => void
    let reject!: (error: unknown) => void
    const settled = new Promise<T>((res, rej) => {
      resolve = res
      reject = rej
    })
    const run: Run<T> = {
      generation: this.generation,
      started: this.now(),
      settled,
      resolve,
      reject
    }
    this.running.push(run)
    Promise.resolve()
      .then(compute)
      .then(
        (value) => this.finish(run, { ok: true, value }),
        (error: unknown) => this.finish(run, { ok: false, error })
      )
    return run
  }

  private finish(
    run: Run<T>,
    result: { ok: true; value: T } | { ok: false; error: unknown }
  ): void {
    if (run.generation !== this.generation || this.kept || !result.ok) {
      this.running = this.running.filter((other) => other !== run)
      if (result.ok) run.resolve(result.value)
      else run.reject(result.error)
      return
    }
    const { value } = result
    this.kept = { value }
    const released = this.running
    this.running = []
    for (const other of released) other.resolve(value)
  }
}
