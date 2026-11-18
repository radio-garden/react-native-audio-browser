package com.audiobrowser.browser

/**
 * A pattern that matched a path: its `{param}` values by name, and a [specificity] score where the
 * highest wins (rules in [SimpleRouter]).
 */
data class RouteMatch(val params: Map<String, String>, val specificity: Int)
