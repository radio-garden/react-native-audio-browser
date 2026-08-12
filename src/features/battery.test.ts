/** @vitest-environment happy-dom */

import { renderHook, act, cleanup } from '@testing-library/react'
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import type { BatteryOptimizationStatus } from './battery'

const native = vi.hoisted(() => ({
  getBatteryWarningPending: vi.fn(() => false),
  getBatteryOptimizationStatus: vi.fn<() => BatteryOptimizationStatus>(
    () => 'unknown' as BatteryOptimizationStatus
  ),
  dismissBatteryWarning: vi.fn(),
  openBatterySettings: vi.fn(),
  onBatteryWarningPendingChanged: undefined as
    | ((event: { pending: boolean }) => void)
    | undefined,
  onBatteryOptimizationStatusChanged: undefined as
    | ((event: { status: BatteryOptimizationStatus }) => void)
    | undefined
}))

vi.mock('../native', () => ({ nativeBrowser: native }))

const { useBatteryWarning } = await import('./battery')

beforeEach(() => {
  vi.clearAllMocks()
  native.getBatteryWarningPending.mockReturnValue(false)
  native.getBatteryOptimizationStatus.mockReturnValue(
    'unknown' as BatteryOptimizationStatus
  )
})
afterEach(cleanup)

describe('useBatteryWarning', () => {
  it('combines the two underlying values into one object', () => {
    const { result } = renderHook(() => useBatteryWarning())
    expect(result.current).toMatchObject({ pending: false, status: 'unknown' })
  })

  it('tracks each source independently', () => {
    const { result } = renderHook(() => useBatteryWarning())

    act(() => native.onBatteryWarningPendingChanged?.({ pending: true }))
    expect(result.current).toMatchObject({ pending: true, status: 'unknown' })

    act(() =>
      native.onBatteryOptimizationStatusChanged?.({
        status: 'restricted' as BatteryOptimizationStatus
      })
    )
    expect(result.current).toMatchObject({
      pending: true,
      status: 'restricted'
    })
  })

  it('exposes actions that reach native', () => {
    const { result } = renderHook(() => useBatteryWarning())

    result.current.dismiss()
    expect(native.dismissBatteryWarning).toHaveBeenCalledOnce()

    result.current.openSettings()
    expect(native.openBatterySettings).toHaveBeenCalledOnce()
  })

  it('keeps the action identities stable across updates', () => {
    const { result } = renderHook(() => useBatteryWarning())
    const { dismiss, openSettings } = result.current

    act(() => native.onBatteryWarningPendingChanged?.({ pending: true }))

    // They are module-level functions, not closures — a consumer can safely put
    // them in a dependency array.
    expect(result.current.dismiss).toBe(dismiss)
    expect(result.current.openSettings).toBe(openSettings)
  })
})
