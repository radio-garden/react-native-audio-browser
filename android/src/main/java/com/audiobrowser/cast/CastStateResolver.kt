package com.audiobrowser.cast

import com.margelo.nitro.audiobrowser.CastState

/**
 * Pure mapping from the three observable Cast-session facts to the cross-platform [CastState]
 * (CONNECTED > CONNECTING > NOT_CONNECTED > NO_DEVICES). Lives in **main** (touches only the
 * generated [CastState] enum, no Cast SDK) so it compiles in the default build and is unit-testable
 * from `src/test`. `CastSessionController.getState()` reads the booleans off the Cast SDK and
 * delegates here.
 */
object CastStateResolver {
  fun resolve(connected: Boolean, connecting: Boolean, hasDevices: Boolean): CastState =
    when {
      connected -> CastState.CONNECTED
      connecting -> CastState.CONNECTING
      hasDevices -> CastState.NOT_CONNECTED
      else -> CastState.NO_DEVICES
    }
}
