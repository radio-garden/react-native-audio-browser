package com.audiobrowser.cast

import android.content.Context
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider

/**
 * The library's Cast [OptionsProvider]. play-services-cast-framework instantiates this reflectively
 * on the first `CastContext.getSharedInstance(...)` (it is named in the cast-sourceset manifest's
 * `OPTIONS_PROVIDER_CLASS_NAME` meta-data), so it must survive minification — see
 * `consumer-rules.pro`.
 *
 * The receiver application id comes from [CastConfigHolder] (set by `configureCast()` before the
 * context is created); an absent id falls back to Google's Default Media Receiver. No additional
 * session providers are registered.
 */
class AudioBrowserCastOptionsProvider : OptionsProvider {
  override fun getCastOptions(context: Context): CastOptions {
    val receiverAppId =
      CastConfigHolder.receiverApplicationId
        ?: CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID
    return CastOptions.Builder().setReceiverApplicationId(receiverAppId).build()
  }

  override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}
