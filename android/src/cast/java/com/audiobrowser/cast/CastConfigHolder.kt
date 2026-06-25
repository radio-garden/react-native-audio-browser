package com.audiobrowser.cast

/**
 * Process-static holder for the Cast receiver application id, set by `configureCast()` BEFORE the
 * Cast context is first created. [AudioBrowserCastOptionsProvider] reads it when
 * `CastContext.getSharedInstance(...)` reflectively instantiates the provider, so the receiver id
 * supplied at runtime reaches the SDK without a native rebuild (the well-trodden "set a static
 * holder before getSharedInstance" trick — see ADR 0003).
 *
 * A null/blank [receiverApplicationId] resolves to Google's Default Media Receiver in the provider.
 */
object CastConfigHolder {
  @Volatile var receiverApplicationId: String? = null

  fun configure(receiverApplicationId: String?) {
    this.receiverApplicationId = receiverApplicationId?.takeIf { it.isNotBlank() }
  }
}
