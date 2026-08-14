package com.audiobrowser.extension

import com.margelo.nitro.audiobrowser.ResolvedTrack
import com.margelo.nitro.audiobrowser.Track
import com.margelo.nitro.audiobrowser.TrackStyle

/**
 * The Track view of a ResolvedTrack: every display/playback field carried over, the browse-only
 * fields (`children`, `carPlaySiriListButton`) dropped. The one place that knows the two shapes
 * share their field set — used to funnel ResolvedTracks through the single Track→MediaItem
 * conversion in TrackFactory.
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
    // An explicit SectionStyle → TrackStyle projection: the page's container
    // properties (gridWrap) have no meaning on a plain Track, so they drop —
    // Nitro flattens the spec's `extends`, making this narrowing a hand-written
    // field list rather than an upcast (ADR 0011).
    style = style?.let { TrackStyle(display = it.display, artworkRendering = it.artworkRendering) },
    disabled = disabled,
    favorited = favorited,
    live = live,
  )
