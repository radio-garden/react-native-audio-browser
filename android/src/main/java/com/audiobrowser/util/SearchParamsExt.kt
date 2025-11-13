package com.audiobrowser.util

import com.margelo.nitro.audiobrowser.SearchParams

/**
 * Creates a SearchParams object from a plain query string.
 *
 * @param query The search query string
 * @return SearchParams configured for unstructured search
 */
fun SearchParams(query: String): SearchParams {
  return SearchParams(
    query = query,
    mode = null,
    genre = null,
    artist = null,
    album = null,
    title = null,
    playlist = null,
  )
}
