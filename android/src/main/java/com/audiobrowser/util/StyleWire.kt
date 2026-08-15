package com.audiobrowser.util

import com.margelo.nitro.audiobrowser.ArtworkRendering
import com.margelo.nitro.audiobrowser.CardImage
import com.margelo.nitro.audiobrowser.GridTile
import com.margelo.nitro.audiobrowser.ImageShape
import com.margelo.nitro.audiobrowser.SectionStyle
import com.margelo.nitro.audiobrowser.StyleDisplay
import com.margelo.nitro.audiobrowser.TrackStyle

/**
 * The all-nil blocks a decode collapses to "no declaration". Every field is listed exactly once
 * here — the decoders and the persistence restore compare against these instead of re-enumerating
 * the fields in each guard (a constructor gaining a field breaks this file, not silently a guard).
 */
internal val EMPTY_TRACK_STYLE =
  TrackStyle(
    display = null,
    artworkRendering = null,
    imageShape = null,
    accessorySymbol = null,
    cardTint = null,
    cardImage = null,
  )

internal val EMPTY_SECTION_STYLE =
  SectionStyle(
    display = null,
    artworkRendering = null,
    imageShape = null,
    accessorySymbol = null,
    cardTint = null,
    cardImage = null,
    gridWrap = null,
    gridTile = null,
  )

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

internal fun GridTile.toWireString(): String =
  when (this) {
    GridTile.PLAIN -> "plain"
    GridTile.CARD -> "card"
    GridTile.CONDENSED -> "condensed"
  }

internal fun String?.toGridTile(): GridTile? =
  when (this?.takeIf { it.isNotEmpty() }?.lowercase()) {
    "plain" -> GridTile.PLAIN
    "card" -> GridTile.CARD
    "condensed" -> GridTile.CONDENSED
    else -> null
  }

internal fun CardImage.toWireString(): String =
  when (this) {
    CardImage.NORMAL -> "normal"
    CardImage.BACKGROUND -> "background"
  }

internal fun String?.toCardImage(): CardImage? =
  when (this?.takeIf { it.isNotEmpty() }?.lowercase()) {
    "normal" -> CardImage.NORMAL
    "background" -> CardImage.BACKGROUND
    else -> null
  }
