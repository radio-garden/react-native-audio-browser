/**
 * Stands in for `react-native` under vitest, via the alias in `vitest.config.ts`.
 *
 * Not a convenience: `react-native/index.js` is Flow-typed (`import typeof ...`)
 * and Rollup cannot parse it, so any test that reaches a module importing from
 * `'react-native'` fails to load at all. Jest consumers get this from
 * `@react-native/jest-preset`; vitest has no equivalent.
 *
 * Keep it to the surface the library actually touches. Two symbols so far —
 * `AppState` (usePolledProgress) and `Image.resolveAssetSource`. Anything a test
 * needs to drive should get an explicit control here rather than a cast at the
 * call site.
 *
 * @internal — never imported by production code, only aliased in by the runner.
 */

export type AppStateStatus = 'active' | 'background' | 'inactive'

type ChangeListener = (status: AppStateStatus) => void

const changeListeners = new Set<ChangeListener>()

export const AppState = {
  currentState: 'active' as AppStateStatus,

  // `type` is widened to string so the guard stays live: real AppState also
  // emits 'memoryWarning' and 'focus', and silently treating one of those as a
  // 'change' subscription would be a confusing way to find out this stub is thin.
  addEventListener(type: string, listener: ChangeListener) {
    if (type !== 'change') throw new Error(`AppState stub: unhandled "${type}"`)
    changeListeners.add(listener)
    return { remove: () => changeListeners.delete(listener) }
  }
}

/** Move the app to the foreground or background and notify subscribers. */
export function setAppState(status: AppStateStatus): void {
  AppState.currentState = status
  // Deleting from a Set mid-iteration is safe, so a listener that removes itself
  // during the callback needs no defensive copy.
  for (const listener of changeListeners) listener(status)
}

/**
 * Drop every subscription and return to the foreground. Call between tests —
 * the stub is a module singleton, so state outlives an individual test.
 */
export function resetAppState(): void {
  changeListeners.clear()
  AppState.currentState = 'active'
}

/** Number of live `change` subscriptions, for asserting cleanup. */
export function appStateListenerCount(): number {
  return changeListeners.size
}

export const Image = {
  resolveAssetSource: (source: unknown) => source
}
