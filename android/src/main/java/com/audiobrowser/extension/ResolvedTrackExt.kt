package com.audiobrowser.extension

import com.margelo.nitro.audiobrowser.ResolvedTrack
import com.margelo.nitro.audiobrowser.Track

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
    artworkCarPlayTinted = artworkCarPlayTinted,
    title = title,
    subtitle = subtitle,
    artist = artist,
    albumPath = albumPath,
    album = album,
    description = description,
    genre = genre,
    duration = duration,
    style = style,
    childrenStyle = childrenStyle,
    favorited = favorited,
    live = live,
  )
