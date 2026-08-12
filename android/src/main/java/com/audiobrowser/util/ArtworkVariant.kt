package com.audiobrowser.util

import com.margelo.nitro.audiobrowser.Variant_String_ArtworkVariants

/**
 * Reading `Track.artwork`, which is either one URL or a URL per appearance.
 *
 * Android Auto is dark-only, so there is nothing here to choose: a pair always resolves to its dark
 * URL, which is also what a single-URL track would have shipped. The per-appearance half of the
 * feature is iOS/CarPlay only — see `ios/Extension/Variant+Artwork.swift`, where both URLs are kept
 * so a `UIImageAsset` can swap them without a re-fetch.
 */
val Variant_String_ArtworkVariants.url: String
  get() = match(first = { it }, second = { it.dark })

/**
 * Wraps a plain URL as the single-URL side of the union.
 *
 * For the places that build a `Track` from something that only ever has one image — restored
 * persistence, a `MediaMetadata` handed back by the platform. Restoring collapses a pair to its
 * dark URL, which costs nothing here: Android Auto would have rendered that one anyway.
 */
fun artworkOf(url: String?): Variant_String_ArtworkVariants? =
  url?.let { Variant_String_ArtworkVariants.First(it) }
