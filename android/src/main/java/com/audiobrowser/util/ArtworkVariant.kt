package com.audiobrowser.util

import com.margelo.nitro.audiobrowser.Variant_String_ArtworkVariants

/**
 * The URL Android shows: the single URL, or the pair's `dark` one, since Android Auto is dark-only.
 * iOS keeps both (`ios/Extension/Variant+Artwork.swift`).
 */
val Variant_String_ArtworkVariants.url: String
  get() = match(first = { it }, second = { it.dark })

fun artworkOf(url: String?): Variant_String_ArtworkVariants? =
  url?.let { Variant_String_ArtworkVariants.First(it) }
