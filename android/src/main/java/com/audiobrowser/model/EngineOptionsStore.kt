package com.audiobrowser.model

import android.content.Context
import androidx.core.content.edit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * The engine options the player last built from, kept across process death so the service builds
 * its first engine from them and the app's own setup finds nothing to rebuild.
 */
internal object EngineOptionsStore {
  private const val PREFS_NAME = "audio_browser_engine"
  private const val KEY_OPTIONS = "engine_options"

  private val json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
  }

  /**
   * [options] in their [PlayerSetupOptions.engineOptions] form, under the library's [defaults] at
   * the time: a field the app left at a default then follows a library upgrade that changes it.
   */
  @Serializable
  internal data class Stored(val defaults: PlayerSetupOptions, val options: PlayerSetupOptions)

  /** Best-effort: options it cannot store, such as an infinite buffer, leave none stored. */
  fun save(context: Context, options: PlayerSetupOptions) {
    val encoded =
      runCatching { encode(options) }
        .onFailure { Timber.w(it, "Could not store the engine options") }
        .getOrNull()
    prefs(context).edit {
      if (encoded != null) putString(KEY_OPTIONS, encoded) else remove(KEY_OPTIONS)
    }
  }

  /** Null when none is stored, it was stored under other defaults, or it is unreadable. */
  fun load(context: Context): PlayerSetupOptions? =
    prefs(context).getString(KEY_OPTIONS, null)?.let(::decode)

  internal fun encode(options: PlayerSetupOptions): String =
    json.encodeToString(Stored.serializer(), Stored(defaults(), options.engineOptions()))

  internal fun decode(stored: String): PlayerSetupOptions? =
    runCatching { json.decodeFromString(Stored.serializer(), stored) }
      .getOrNull()
      ?.takeIf { it.defaults == defaults() }
      ?.options

  private fun defaults() = PlayerSetupOptions().engineOptions()

  private fun prefs(context: Context) =
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
