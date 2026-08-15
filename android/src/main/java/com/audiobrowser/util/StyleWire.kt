package com.audiobrowser.util

import com.margelo.nitro.audiobrowser.ArtworkRendering
import com.margelo.nitro.audiobrowser.ImageShape
import com.margelo.nitro.audiobrowser.StyleDisplay

/**
 * The style block's wire strings (ADR 0011) ↔ Nitro enums, shared by the browse decoder and the
 * playback-state store. Inbound mapping is lenient: an unknown or empty value is "no declaration",
 * never an error.
 */
internal fun StyleDisplay.toWireString(): String =
  when (this) {
    StyleDisplay.LIST -> "list"
    StyleDisplay.GRID -> "grid"
  }

internal fun ArtworkRendering.toWireString(): String =
  when (this) {
    ArtworkRendering.ORIGINAL -> "original"
    ArtworkRendering.STENCIL -> "stencil"
  }

internal fun String?.toStyleDisplay(): StyleDisplay? =
  when (this?.takeIf { it.isNotEmpty() }?.lowercase()) {
    "list" -> StyleDisplay.LIST
    "grid" -> StyleDisplay.GRID
    else -> null
  }

internal fun String?.toArtworkRendering(): ArtworkRendering? =
  when (this?.takeIf { it.isNotEmpty() }?.lowercase()) {
    "original" -> ArtworkRendering.ORIGINAL
    "stencil" -> ArtworkRendering.STENCIL
    else -> null
  }

internal fun ImageShape.toWireString(): String =
  when (this) {
    ImageShape.CIRCULAR -> "circular"
    ImageShape.ROUNDED_RECTANGLE -> "rounded-rectangle"
  }

internal fun String?.toImageShape(): ImageShape? =
  when (this?.takeIf { it.isNotEmpty() }?.lowercase()) {
    "circular" -> ImageShape.CIRCULAR
    "rounded-rectangle" -> ImageShape.ROUNDED_RECTANGLE
    else -> null
  }
