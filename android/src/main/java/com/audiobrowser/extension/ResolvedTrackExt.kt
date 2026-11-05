package com.audiobrowser.extension

import com.margelo.nitro.audiobrowser.ResolvedTrack
import com.margelo.nitro.audiobrowser.Track
import com.margelo.nitro.audiobrowser.TrackStyle

/**
 * The Track fields of a ResolvedTrack; the page-only fields (`sections`, `children`,
 * `carPlaySiriListButton`) drop.
 */
fun ResolvedTrack.toTrack(): Track =
  Track(
    id = id,
    path = path,
    src = src,
    artwork = artwork,
    artworkSource = artworkSource,
    request = request,
    title = title,
    subtitle = subtitle,
    artist = artist,
    albumPath = albumPath,
    album = album,
    description = description,
    genre = genre,
    duration = duration,
    // SectionStyle narrows to TrackStyle by hand, dropping the page-wide `gridWrap`: Nitro
    // flattens the spec's `extends`, so there is no upcast (ADR 0011).
    style =
      style?.let {
        TrackStyle(
          display = it.display,
          artworkRendering = it.artworkRendering,
          imageShape = it.imageShape,
          accessorySymbol = it.accessorySymbol,
          cardTint = it.cardTint,
          cardImage = it.cardImage,
        )
      },
    disabled = disabled,
    favorited = favorited,
    live = live,
    icyCharset = icyCharset,
  )
